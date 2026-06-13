package io.github.makaseloli.ghastfsd.automation;

import io.github.makaseloli.ghastfsd.content.GhastCouplingAttachment;
import io.github.makaseloli.ghastfsd.content.GhastStationGeometry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class GhastFlightController {
    private static final double VANILLA_HAPPY_GHAST_FLYING_SPEED = 0.05;
    private static final double RIDDEN_SPEED_MULTIPLIER = 3.9;
    private static final double CRUISE_CLEARANCE = 50.0;
    private static final double CRUISE_CLEARANCE_DISTANCE = 80.0;
    private static final double TERRAIN_LOOK_AHEAD_DISTANCE = 256.0;
    private static final double TERRAIN_LOOK_AHEAD_STEP = 8.0;
    private static final double TERRAIN_CLIMB_TRIGGER = 3.0;
    private static final double TERRAIN_CLIMB_MIN_LEAD = 12.0;
    private static final double TERRAIN_CLIMB_MAX_LEAD = 48.0;
    private static final double TERRAIN_CLIMB_SMOOTH_DISTANCE = 64.0;
    private static final double TERRAIN_VERTICAL_STEP = 2.8;
    private static final int TERRAIN_CACHE_TICKS = 4;
    private static final double TERRAIN_CACHE_MAX_MOVE_SQR = 16.0;
    private static final double TERRAIN_CACHE_MIN_DIRECTION_DOT = 0.985;
    private static final double OBSTACLE_LOOK_AHEAD_DISTANCE = 8.0;
    private static final double OBSTACLE_LOOK_AHEAD_STEP = 1.25;
    private static final double OBSTACLE_SIDE_WEIGHT = 0.72;
    private static final double OBSTACLE_UP_WEIGHT = 0.42;
    private static final double OBSTACLE_DOWN_WEIGHT = 0.56;
    private static final double OBSTACLE_PROGRESS_WEIGHT = 5.0;
    private static final double OBSTACLE_ALIGNMENT_WEIGHT = 2.2;
    private static final double OBSTACLE_TURN_PENALTY = 0.75;
    private static final double COUPLING_GAP = 1.5;
    private static final Map<UUID, TerrainCache> TERRAIN_CACHE = new HashMap<>();

    private GhastFlightController() {}

    static double cruiseSpeed() {
        return VANILLA_HAPPY_GHAST_FLYING_SPEED * RIDDEN_SPEED_MULTIPLIER;
    }

    static double speed(HappyGhast ghast) {
        double flyingSpeed = ghast.getAttributeValue(Attributes.FLYING_SPEED);
        return Math.max(0.02, flyingSpeed * RIDDEN_SPEED_MULTIPLIER);
    }

    static void clear(HappyGhast ghast) {
        TERRAIN_CACHE.remove(ghast.getUUID());
    }

    static Vec3 terrainAwareTarget(ServerLevel level, HappyGhast ghast, Vec3 target) {
        Vec3 horizontal = new Vec3(target.x - ghast.getX(), 0.0, target.z - ghast.getZ());
        double horizontalDistance = horizontal.length();
        if (horizontalDistance <= GhastStationGeometry.APPROACH_RADIUS * 0.65) {
            return target;
        }
        Vec3 direction = horizontal.normalize();
        double bottomOffset = ghast.getY() - ghast.getBoundingBox().minY;
        TerrainClearance clearance = terrainClearance(level, ghast, direction, horizontalDistance);
        double safeBottomY = clearance.safeBottomY();
        double currentBottomY = ghast.getBoundingBox().minY;
        if (safeBottomY > currentBottomY + TERRAIN_CLIMB_TRIGGER) {
            double urgency = 1.0 - Math.max(0.0, Math.min(1.0, clearance.firstClimbDistance() / TERRAIN_CLIMB_SMOOTH_DISTANCE));
            double desiredBottomY = lerp(currentBottomY + TERRAIN_VERTICAL_STEP, safeBottomY, urgency);
            double lead = Math.max(TERRAIN_CLIMB_MIN_LEAD, Math.min(TERRAIN_CLIMB_MAX_LEAD, clearance.firstClimbDistance()));
            Vec3 horizontalPoint = horizontalDistance <= TERRAIN_CLIMB_MAX_LEAD
                ? target
                : ghast.position().add(direction.scale(Math.min(horizontalDistance, lead)));
            return new Vec3(horizontalPoint.x, Math.max(target.y, desiredBottomY + bottomOffset), horizontalPoint.z);
        }
        if (horizontalDistance >= CRUISE_CLEARANCE_DISTANCE) {
            return new Vec3(target.x, Math.max(target.y, safeBottomY + bottomOffset), target.z);
        }
        double blendStart = GhastStationGeometry.APPROACH_RADIUS * 0.65;
        double cruiseBlend = Math.max(0.0, Math.min(1.0, (horizontalDistance - blendStart) / (CRUISE_CLEARANCE_DISTANCE - blendStart)));
        double desiredBottomY = lerp(target.y - bottomOffset, safeBottomY, cruiseBlend);
        return new Vec3(target.x, Math.max(target.y, desiredBottomY + bottomOffset), target.z);
    }

    static void moveToward(ServerLevel level, HappyGhast ghast, Vec3 target, double speed) {
        Vec3 delta = target.subtract(ghast.position());
        if (delta.lengthSqr() < 0.0001) {
            ghast.setDeltaMovement(Vec3.ZERO);
            return;
        }
        Vec3 desired = steeringVector(level, ghast, target, speed);
        Vec3 blended = ghast.getDeltaMovement().scale(0.72).add(desired.scale(0.28));
        if (!hasCollisionFreePath(level, ghast, blended, Math.min(OBSTACLE_LOOK_AHEAD_DISTANCE, Math.max(blended.length(), speed)))) {
            blended = desired;
        }
        ghast.setDeltaMovement(blended);
        ghast.setNoGravity(true);
        faceToward(ghast, blended);
        ghast.move(MoverType.SELF, blended);
        ghast.hurtMarked = true;
    }

    static void alignToDirection(HappyGhast ghast, Direction direction) {
        setRotation(ghast, direction.toYRot(), 0.0F);
    }

    private static TerrainClearance terrainClearance(ServerLevel level, HappyGhast ghast, Vec3 direction, double horizontalDistance) {
        Vec3 position = ghast.position();
        TerrainCache cached = TERRAIN_CACHE.get(ghast.getUUID());
        if (cached != null
            && cached.tick + TERRAIN_CACHE_TICKS >= ghast.tickCount
            && cached.dimension.equals(level.dimension())
            && cached.position.distanceToSqr(position) <= TERRAIN_CACHE_MAX_MOVE_SQR
            && cached.direction.dot(direction) >= TERRAIN_CACHE_MIN_DIRECTION_DOT
            && Math.abs(cached.horizontalDistance - horizontalDistance) <= TERRAIN_LOOK_AHEAD_STEP) {
            return cached.clearance;
        }
        double lookAhead = Math.min(horizontalDistance, TERRAIN_LOOK_AHEAD_DISTANCE);
        double currentBottomY = ghast.getBoundingBox().minY;
        double halfWidth = Math.max(1.0, ghast.getBbWidth() * 0.5 + 1.0);
        int highestSurface = surfaceYForWidth(level, position, direction, halfWidth);
        double firstClimbDistance = lookAhead;
        for (double distance = TERRAIN_LOOK_AHEAD_STEP; distance <= lookAhead; distance += TERRAIN_LOOK_AHEAD_STEP) {
            Vec3 sample = position.add(direction.scale(distance));
            int surface = surfaceYForWidth(level, sample, direction, halfWidth);
            highestSurface = Math.max(highestSurface, surface);
            if (firstClimbDistance == lookAhead && surface + CRUISE_CLEARANCE > currentBottomY + TERRAIN_CLIMB_TRIGGER) {
                firstClimbDistance = distance;
            }
        }
        if (horizontalDistance > lookAhead) {
            Vec3 targetSample = position.add(direction.scale(horizontalDistance));
            highestSurface = Math.max(highestSurface, surfaceYForWidth(level, targetSample, direction, halfWidth));
        }
        TerrainClearance clearance = new TerrainClearance(highestSurface + CRUISE_CLEARANCE, firstClimbDistance);
        TERRAIN_CACHE.put(ghast.getUUID(), new TerrainCache(level.dimension(), ghast.tickCount, position, direction, horizontalDistance, clearance));
        return clearance;
    }

    private static int surfaceYForWidth(ServerLevel level, Vec3 center, Vec3 direction, double halfWidth) {
        Vec3 side = new Vec3(-direction.z, 0.0, direction.x);
        int highest = surfaceY(level, (int)Math.floor(center.x), (int)Math.floor(center.z));
        highest = Math.max(highest, surfaceY(level, (int)Math.floor(center.x + side.x * halfWidth), (int)Math.floor(center.z + side.z * halfWidth)));
        highest = Math.max(highest, surfaceY(level, (int)Math.floor(center.x - side.x * halfWidth), (int)Math.floor(center.z - side.z * halfWidth)));
        return highest;
    }

    private static int surfaceY(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    private static Vec3 steeringVector(ServerLevel level, HappyGhast ghast, Vec3 target, double speed) {
        Vec3 toTarget = target.subtract(ghast.position());
        double distance = toTarget.length();
        if (distance < 0.0001) {
            return Vec3.ZERO;
        }
        double step = Math.min(speed, distance);
        Vec3 direct = toTarget.normalize().scale(step);
        double lookAhead = Math.min(OBSTACLE_LOOK_AHEAD_DISTANCE, Math.max(step, distance));
        if (hasCollisionFreePath(level, ghast, direct, lookAhead)) {
            return direct;
        }

        Vec3 targetDirection = toTarget.normalize();
        Vec3 horizontal = new Vec3(toTarget.x, 0.0, toTarget.z);
        Vec3 forward = horizontal.lengthSqr() > 1.0E-6 ? horizontal.normalize() : new Vec3(0.0, 0.0, 1.0);
        Vec3 side = new Vec3(-forward.z, 0.0, forward.x);
        double preferredSide = (ghast.getUUID().getLeastSignificantBits() & 1L) == 0L ? 1.0 : -1.0;
        Vec3[] candidates = new Vec3[] {
            forward.add(side.scale(preferredSide * OBSTACLE_SIDE_WEIGHT)),
            forward.add(side.scale(-preferredSide * OBSTACLE_SIDE_WEIGHT)),
            side.scale(preferredSide).add(forward.scale(0.25)),
            side.scale(-preferredSide).add(forward.scale(0.25)),
            forward.add(side.scale(preferredSide * OBSTACLE_SIDE_WEIGHT)).add(0.0, -OBSTACLE_DOWN_WEIGHT, 0.0),
            forward.add(side.scale(-preferredSide * OBSTACLE_SIDE_WEIGHT)).add(0.0, -OBSTACLE_DOWN_WEIGHT, 0.0),
            forward.add(side.scale(preferredSide * OBSTACLE_SIDE_WEIGHT)).add(0.0, OBSTACLE_UP_WEIGHT, 0.0),
            forward.add(side.scale(-preferredSide * OBSTACLE_SIDE_WEIGHT)).add(0.0, OBSTACLE_UP_WEIGHT, 0.0),
            new Vec3(0.0, -1.0, 0.0).add(forward.scale(0.2)),
            new Vec3(0.0, 1.0, 0.0).add(forward.scale(0.2)),
            targetDirection
        };

        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Vec3 previous = ghast.getDeltaMovement();
        Vec3 previousDirection = previous.lengthSqr() > 1.0E-6 ? previous.normalize() : Vec3.ZERO;
        for (Vec3 candidate : candidates) {
            if (candidate.lengthSqr() < 1.0E-6) {
                continue;
            }
            Vec3 movement = candidate.normalize().scale(step);
            if (!hasCollisionFreePath(level, ghast, movement, lookAhead)) {
                continue;
            }
            Vec3 nextPosition = ghast.position().add(movement);
            double progress = distance - target.distanceTo(nextPosition);
            double alignment = movement.normalize().dot(targetDirection);
            double turnPenalty = previousDirection == Vec3.ZERO ? 0.0 : 1.0 - Math.max(-1.0, Math.min(1.0, movement.normalize().dot(previousDirection)));
            double verticalPenalty = Math.abs(movement.y) / Math.max(step, 0.0001);
            double score = progress * OBSTACLE_PROGRESS_WEIGHT
                + alignment * OBSTACLE_ALIGNMENT_WEIGHT
                - turnPenalty * OBSTACLE_TURN_PENALTY
                - verticalPenalty * 0.35;
            if (score > bestScore) {
                bestScore = score;
                best = movement;
            }
        }
        if (best != null) {
            return best;
        }
        return findOpenEscapeVector(level, ghast, forward, side, step, preferredSide);
    }

    private static Vec3 findOpenEscapeVector(ServerLevel level, HappyGhast ghast, Vec3 forward, Vec3 side, double step, double preferredSide) {
        Vec3[] escapes = new Vec3[] {
            side.scale(preferredSide),
            side.scale(-preferredSide),
            side.scale(preferredSide).add(0.0, -0.6, 0.0),
            side.scale(-preferredSide).add(0.0, -0.6, 0.0),
            side.scale(preferredSide).add(0.0, 0.45, 0.0),
            side.scale(-preferredSide).add(0.0, 0.45, 0.0),
            forward.scale(-0.6).add(side.scale(preferredSide)),
            forward.scale(-0.6).add(side.scale(-preferredSide)),
            new Vec3(0.0, -1.0, 0.0),
            new Vec3(0.0, 1.0, 0.0)
        };
        for (Vec3 escape : escapes) {
            Vec3 movement = escape.normalize().scale(step);
            if (hasCollisionFreePath(level, ghast, movement, Math.max(step, OBSTACLE_LOOK_AHEAD_STEP))) {
                return movement;
            }
        }
        return Vec3.ZERO;
    }

    private static boolean hasCollisionFreePath(ServerLevel level, HappyGhast ghast, Vec3 movement, double lookAhead) {
        if (movement.lengthSqr() < 1.0E-8) {
            return true;
        }
        Vec3 direction = movement.normalize();
        double distance = Math.max(movement.length(), lookAhead);
        TrainFootprint footprint = TrainFootprint.of(level, ghast, direction);
        for (double checked = OBSTACLE_LOOK_AHEAD_STEP; checked <= distance; checked += OBSTACLE_LOOK_AHEAD_STEP) {
            if (!footprint.noCollision(level, ghast, direction.scale(checked))) {
                return false;
            }
        }
        return footprint.noCollision(level, ghast, movement);
    }

    private static void faceToward(HappyGhast ghast, Vec3 movement) {
        if (movement.horizontalDistanceSqr() > 1.0E-6) {
            float yaw = (float)(Math.atan2(movement.z, movement.x) * 180.0 / Math.PI) - 90.0F;
            setRotation(ghast, yaw, ghast.getXRot());
        }
        if (movement.lengthSqr() > 1.0E-6) {
            double horizontal = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
            float pitch = (float)(-(Math.atan2(movement.y, horizontal) * 180.0 / Math.PI));
            setRotation(ghast, ghast.getYRot(), pitch);
        }
    }

    private static void setRotation(HappyGhast ghast, float yaw, float pitch) {
        ghast.setYRot(yaw);
        ghast.yRotO = yaw;
        ghast.yBodyRot = yaw;
        ghast.yBodyRotO = yaw;
        ghast.yHeadRot = yaw;
        ghast.yHeadRotO = yaw;
        ghast.setXRot(pitch);
        ghast.xRotO = pitch;
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    private record TerrainClearance(double safeBottomY, double firstClimbDistance) {}

    private record TerrainCache(ResourceKey<Level> dimension, int tick, Vec3 position, Vec3 direction, double horizontalDistance, TerrainClearance clearance) {}

    private record TrainFootprint(List<AABB> currentBoxes, List<AABB> projectedBoxes) {
        static TrainFootprint of(ServerLevel level, HappyGhast head, Vec3 direction) {
            List<HappyGhast> members = GhastCouplingAttachment.chainMembers(level, head);
            if (members.size() <= 1) {
                return new TrainFootprint(List.of(head.getBoundingBox()), List.of());
            }
            java.util.ArrayList<AABB> currentBoxes = new java.util.ArrayList<>(members.size());
            java.util.ArrayList<AABB> projectedBoxes = new java.util.ArrayList<>(members.size() - 1);
            for (HappyGhast member : members) {
                currentBoxes.add(member.getBoundingBox());
            }

            Vec3 anchor = head.position();
            double trailingDistance = 0.0;
            HappyGhast previous = head;
            for (int i = 1; i < members.size(); i++) {
                HappyGhast member = members.get(i);
                trailingDistance += previous.getBbWidth() * 0.5 + member.getBbWidth() * 0.5 + COUPLING_GAP;
                Vec3 projectedCenter = anchor.subtract(direction.scale(trailingDistance));
                projectedBoxes.add(centerBoxAt(member.getBoundingBox(), projectedCenter));
                previous = member;
            }
            return new TrainFootprint(List.copyOf(currentBoxes), List.copyOf(projectedBoxes));
        }

        boolean noCollision(ServerLevel level, HappyGhast head, Vec3 offset) {
            for (AABB box : currentBoxes) {
                if (!level.noCollision(head, box.move(offset))) {
                    return false;
                }
            }
            for (AABB box : projectedBoxes) {
                if (!level.noCollision(head, box.move(offset))) {
                    return false;
                }
            }
            return true;
        }

        private static AABB centerBoxAt(AABB box, Vec3 center) {
            Vec3 currentCenter = box.getCenter();
            return box.move(center.x - currentCenter.x, center.y - currentCenter.y, center.z - currentCenter.z);
        }
    }
}
