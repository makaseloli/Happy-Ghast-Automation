package io.github.makaseloli.ghastfsd.automation;

import io.github.makaseloli.ghastfsd.content.GhastCouplingAttachment;
import io.github.makaseloli.ghastfsd.content.GhastStationGeometry;
import java.util.ArrayList;
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

public final class GhastFlightController {
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
    private static final double OBSTACLE_PROGRESS_WEIGHT = 8.0;
    private static final double OBSTACLE_ALIGNMENT_WEIGHT = 3.0;
    private static final double OBSTACLE_TURN_PENALTY = 0.8;
    private static final double OBSTACLE_CLEARANCE_WEIGHT = 0.35;
    private static final double VERTICAL_APPROACH_RADIUS = 3.0;
    private static final double MOVEMENT_SMOOTHING = 0.72;
    private static final int MOVEMENT_PLAN_TICKS = 10;
    private static final double MOVEMENT_PLAN_TARGET_TOLERANCE_SQR = 1.0;
    private static final double COUPLING_GAP = 1.5;
    private static final Map<UUID, TerrainCache> TERRAIN_CACHE = new HashMap<>();
    private static final Map<UUID, FlightMemory> FLIGHT_MEMORY = new HashMap<>();
    private static final Map<UUID, MovementPlan> MOVEMENT_PLAN_CACHE = new HashMap<>();

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
        FLIGHT_MEMORY.remove(ghast.getUUID());
        MOVEMENT_PLAN_CACHE.remove(ghast.getUUID());
    }

    static Vec3 terrainAwareTarget(ServerLevel level, HappyGhast ghast, Vec3 target) {
        Vec3 horizontal = new Vec3(target.x - ghast.getX(), 0.0, target.z - ghast.getZ());
        double horizontalDistance = horizontal.length();
        double verticalDistance = Math.abs(target.y - ghast.getY());
        if (horizontalDistance <= GhastStationGeometry.APPROACH_RADIUS * 0.65) {
            if (verticalDistance > GhastStationGeometry.DOCKING_VERTICAL_TOLERANCE * 2.0) {
                return new Vec3(ghast.getX(), target.y, ghast.getZ());
            }
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
            stop(ghast);
            return;
        }
        if (!level.noCollision(ghast, ghast.getBoundingBox())) {
            Vec3 escape = delta.normalize().scale(Math.min(speed, delta.length()));
            forceControlledMove(ghast, escape);
            return;
        }
        Vec3 desired = plannedMovement(level, ghast, target, speed);
        Vec3 movement = smoothMovement(ghast, desired, speed);
        if (!hasCollisionFreePath(level, ghast, movement, Math.min(OBSTACLE_LOOK_AHEAD_DISTANCE, Math.max(movement.length(), speed)))) {
            movement = desired;
        }
        applyControlledMove(ghast, movement);
    }

    private static Vec3 plannedMovement(ServerLevel level, HappyGhast ghast, Vec3 target, double speed) {
        MovementPlan cached = MOVEMENT_PLAN_CACHE.get(ghast.getUUID());
        if (cached != null
            && cached.tick + MOVEMENT_PLAN_TICKS > ghast.tickCount
            && cached.target.distanceToSqr(target) <= MOVEMENT_PLAN_TARGET_TOLERANCE_SQR
            && Math.abs(cached.speed - speed) < 1.0E-6
            && cached.movement.lengthSqr() > 1.0E-8) {
            double remaining = target.distanceTo(ghast.position());
            return cached.movement.normalize().scale(Math.min(speed, remaining));
        }
        Vec3 planned = planMovement(level, ghast, target, speed);
        MOVEMENT_PLAN_CACHE.put(ghast.getUUID(), new MovementPlan(ghast.tickCount, target, speed, planned));
        return planned;
    }

    public static void follow(ServerLevel level, HappyGhast ghast, HappyGhast previousGhast, double gap, double speed) {
        Vec3 forward = horizontalForward(previousGhast.getYRot());
        double spacing = previousGhast.getBbWidth() * 0.5 + ghast.getBbWidth() * 0.5 + gap;
        Vec3 target = previousGhast.position().subtract(forward.scale(spacing));
        Vec3 delta = target.subtract(ghast.position());
        if (delta.lengthSqr() > 100.0) {
            ghast.setPos(target);
            stop(ghast);
        } else if (delta.lengthSqr() > 0.0001) {
            Vec3 desired = delta.normalize().scale(Math.min(speed, delta.length()));
            applyControlledMove(ghast, smoothMovement(ghast, desired, speed));
        } else {
            stop(ghast);
        }
        ghast.setNoGravity(true);
        setRotation(ghast, previousGhast.getYRot(), previousGhast.getXRot());
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

    private static Vec3 planMovement(ServerLevel level, HappyGhast ghast, Vec3 target, double speed) {
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
        NavigationBasis basis = NavigationBasis.from(targetDirection);
        List<Vec3> candidates = movementCandidates(ghast, toTarget, targetDirection, basis);
        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Vec3 previous = FLIGHT_MEMORY.getOrDefault(ghast.getUUID(), FlightMemory.STOPPED).movement;
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
            double clearance = collisionFreeDistance(level, ghast, movement, OBSTACLE_LOOK_AHEAD_DISTANCE);
            double score = progress * OBSTACLE_PROGRESS_WEIGHT
                + alignment * OBSTACLE_ALIGNMENT_WEIGHT
                - turnPenalty * OBSTACLE_TURN_PENALTY
                + clearance * OBSTACLE_CLEARANCE_WEIGHT;
            if (score > bestScore) {
                bestScore = score;
                best = movement;
            }
        }
        if (best != null) {
            return best;
        }
        return findOpenEscapeVector(level, ghast, targetDirection, basis, step);
    }

    private static List<Vec3> movementCandidates(HappyGhast ghast, Vec3 toTarget, Vec3 targetDirection, NavigationBasis basis) {
        ArrayList<Vec3> candidates = new ArrayList<>();
        candidates.add(targetDirection);
        if (GhastStationGeometry.horizontalDistance(ghast.position(), ghast.position().add(toTarget)) <= VERTICAL_APPROACH_RADIUS) {
            candidates.add(new Vec3(0.0, Math.signum(toTarget.y), 0.0));
        }
        double preferredSide = (ghast.getUUID().getLeastSignificantBits() & 1L) == 0L ? 1.0 : -1.0;
        double[] sideBiases = { preferredSide, -preferredSide };
        double[] forwardWeights = { 0.9, 0.55, 0.25, 0.0, -0.3 };
        double[] sideWeights = { 0.35, 0.7, 1.0 };
        double[] liftWeights = { 0.0, 0.35, -0.35, 0.7, -0.7 };
        for (double forwardWeight : forwardWeights) {
            for (double sideBias : sideBiases) {
                for (double sideWeight : sideWeights) {
                    for (double liftWeight : liftWeights) {
                        candidates.add(targetDirection.scale(forwardWeight)
                            .add(basis.side().scale(sideBias * sideWeight))
                            .add(basis.lift().scale(liftWeight)));
                    }
                }
            }
        }
        candidates.add(basis.lift());
        candidates.add(basis.lift().scale(-1.0));
        candidates.add(basis.side().scale(preferredSide));
        candidates.add(basis.side().scale(-preferredSide));
        candidates.add(targetDirection.scale(-0.5).add(basis.lift().scale(0.7)));
        candidates.add(targetDirection.scale(-0.5).add(basis.lift().scale(-0.7)));
        return candidates;
    }

    private static Vec3 smoothMovement(HappyGhast ghast, Vec3 desired, double maxStep) {
        if (desired.lengthSqr() < 1.0E-8) {
            return Vec3.ZERO;
        }
        FlightMemory memory = FLIGHT_MEMORY.get(ghast.getUUID());
        if (memory == null || memory.tick + 1 < ghast.tickCount || memory.movement.lengthSqr() < 1.0E-8) {
            return limit(desired, maxStep);
        }
        Vec3 blended = memory.movement.scale(MOVEMENT_SMOOTHING).add(desired.scale(1.0 - MOVEMENT_SMOOTHING));
        if (blended.lengthSqr() < 1.0E-8) {
            return limit(desired, maxStep);
        }
        return limit(blended, Math.min(maxStep, desired.length()));
    }

    private static Vec3 limit(Vec3 movement, double maxLength) {
        double length = movement.length();
        if (length <= maxLength || length < 1.0E-8) {
            return movement;
        }
        return movement.scale(maxLength / length);
    }

    private static void applyControlledMove(HappyGhast ghast, Vec3 movement) {
        if (movement.lengthSqr() < 1.0E-8) {
            stop(ghast);
            return;
        }
        Vec3 before = ghast.position();
        ghast.setNoGravity(true);
        faceToward(ghast, movement);
        ghast.move(MoverType.SELF, movement);
        Vec3 actual = ghast.position().subtract(before);
        ghast.setDeltaMovement(Vec3.ZERO);
        if (actual.lengthSqr() > 1.0E-8) {
            FLIGHT_MEMORY.put(ghast.getUUID(), new FlightMemory(ghast.tickCount, actual));
        } else {
            FLIGHT_MEMORY.put(ghast.getUUID(), FlightMemory.stoppedAt(ghast.tickCount));
        }
        ghast.hurtMarked = true;
    }

    private static void forceControlledMove(HappyGhast ghast, Vec3 movement) {
        if (movement.lengthSqr() < 1.0E-8) {
            stop(ghast);
            return;
        }
        Vec3 before = ghast.position();
        Vec3 after = before.add(movement);
        ghast.setNoGravity(true);
        faceToward(ghast, movement);
        ghast.setPos(after.x, after.y, after.z);
        ghast.setDeltaMovement(Vec3.ZERO);
        FLIGHT_MEMORY.put(ghast.getUUID(), new FlightMemory(ghast.tickCount, movement));
        MOVEMENT_PLAN_CACHE.remove(ghast.getUUID());
        ghast.hurtMarked = true;
    }

    private static void stop(HappyGhast ghast) {
        ghast.setDeltaMovement(Vec3.ZERO);
        FLIGHT_MEMORY.put(ghast.getUUID(), FlightMemory.stoppedAt(ghast.tickCount));
    }

    private static Vec3 findOpenEscapeVector(ServerLevel level, HappyGhast ghast, Vec3 targetDirection, NavigationBasis basis, double step) {
        double preferredSide = (ghast.getUUID().getLeastSignificantBits() & 1L) == 0L ? 1.0 : -1.0;
        Vec3[] escapes = new Vec3[] {
            basis.lift(),
            basis.lift().scale(-1.0),
            basis.side().scale(preferredSide),
            basis.side().scale(-preferredSide),
            basis.side().scale(preferredSide).add(basis.lift().scale(0.7)),
            basis.side().scale(-preferredSide).add(basis.lift().scale(0.7)),
            basis.side().scale(preferredSide).add(basis.lift().scale(-0.7)),
            basis.side().scale(-preferredSide).add(basis.lift().scale(-0.7)),
            targetDirection.scale(-0.6).add(basis.side().scale(preferredSide)),
            targetDirection.scale(-0.6).add(basis.side().scale(-preferredSide))
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

    private static double collisionFreeDistance(ServerLevel level, HappyGhast ghast, Vec3 movement, double maxDistance) {
        if (movement.lengthSqr() < 1.0E-8) {
            return 0.0;
        }
        Vec3 direction = movement.normalize();
        TrainFootprint footprint = TrainFootprint.of(level, ghast, direction);
        double distance = 0.0;
        for (double checked = OBSTACLE_LOOK_AHEAD_STEP; checked <= maxDistance; checked += OBSTACLE_LOOK_AHEAD_STEP) {
            if (!footprint.noCollision(level, ghast, direction.scale(checked))) {
                return distance;
            }
            distance = checked;
        }
        return distance;
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

    private static Vec3 horizontalForward(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));
    }

    private record TerrainClearance(double safeBottomY, double firstClimbDistance) {}

    private record TerrainCache(ResourceKey<Level> dimension, int tick, Vec3 position, Vec3 direction, double horizontalDistance, TerrainClearance clearance) {}

    private record FlightMemory(int tick, Vec3 movement) {
        private static final FlightMemory STOPPED = new FlightMemory(Integer.MIN_VALUE, Vec3.ZERO);

        private static FlightMemory stoppedAt(int tick) {
            return new FlightMemory(tick, Vec3.ZERO);
        }
    }

    private record MovementPlan(int tick, Vec3 target, double speed, Vec3 movement) {}

    private record NavigationBasis(Vec3 side, Vec3 lift) {
        static NavigationBasis from(Vec3 direction) {
            Vec3 reference = Math.abs(direction.y) > 0.82 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
            Vec3 side = direction.cross(reference);
            if (side.lengthSqr() < 1.0E-8) {
                side = new Vec3(1.0, 0.0, 0.0);
            } else {
                side = side.normalize();
            }
            Vec3 lift = side.cross(direction);
            if (lift.lengthSqr() < 1.0E-8) {
                lift = new Vec3(0.0, 1.0, 0.0);
            } else {
                lift = lift.normalize();
            }
            return new NavigationBasis(side, lift);
        }
    }

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
