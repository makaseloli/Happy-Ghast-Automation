package io.github.makaseloli.ghastfsd.automation;

import io.github.makaseloli.ghastfsd.content.GhastFsdContent;
import io.github.makaseloli.ghastfsd.content.GhastCouplingAttachment;
import io.github.makaseloli.ghastfsd.content.FsdTaskAttachment;
import io.github.makaseloli.ghastfsd.content.FsdTaskNotifier;
import io.github.makaseloli.ghastfsd.content.GhastStationBlock;
import io.github.makaseloli.ghastfsd.content.GhastStationBlockEntity;
import io.github.makaseloli.ghastfsd.content.GhastStationData;
import io.github.makaseloli.ghastfsd.route.RouteData;
import io.github.makaseloli.ghastfsd.route.RouteInstruction;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public final class GhastAutopilot {
    private static final String AI_DISABLED_BY_FSD = "ghastfsd_ai_disabled";
    private static final double VANILLA_HAPPY_GHAST_FLYING_SPEED = 0.05;
    private static final double RIDDEN_SPEED_MULTIPLIER = 3.9;
    private static final double DOCKING_SPEED_MULTIPLIER = 0.45;
    private static final double DOCKING_HORIZONTAL_TOLERANCE = 1.75;
    private static final double DOCKING_VERTICAL_TOLERANCE = 0.75;
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
    private static final Map<UUID, TerrainCache> TERRAIN_CACHE = new HashMap<>();

    private GhastAutopilot() {}

    static double autopilotCruiseSpeed() {
        return VANILLA_HAPPY_GHAST_FLYING_SPEED * RIDDEN_SPEED_MULTIPLIER;
    }

    public static void initializeAttachedTask(HappyGhast ghast, int focus) {
        AutopilotState.reset(ghast, focus);
        if (ghast.level() instanceof ServerLevel serverLevel) {
            VirtualGhastTracker.remove(serverLevel.getServer(), ghast.getUUID());
        }
    }

    public static void tickServer(MinecraftServer server) {
        VirtualGhastTracker.beginServerTick(server);
        Set<UUID> seen = VirtualGhastTracker.seenSet();
        for (ServerLevel level : server.getAllLevels()) {
            for (HappyGhast ghast : level.getEntities(EntityType.HAPPY_GHAST, ghast -> true)) {
                seen.add(ghast.getUUID());
                tickGhast(level, ghast);
            }
        }
        VirtualGhastTracker.tickUnloaded(server, seen);
    }

    private static void tickGhast(ServerLevel level, HappyGhast ghast) {
        GhastCouplingAttachment.syncCouplingData(ghast);
        ItemStack task = ghast.getItemBySlot(EquipmentSlot.BODY);
        if (task.getItem() == GhastFsdContent.FSD_TASK && !GhastCouplingAttachment.hasChainTask(level, ghast)) {
            HappyGhast carrier = GhastCouplingAttachment.chainHead(level, ghast);
            ghast.setItemSlot(EquipmentSlot.BODY, ItemStack.EMPTY);
            FsdTaskAttachment.setTask(carrier, task);
            initializeAttachedTask(carrier, RouteData.focus(task));
        }
        GhastCouplingAttachment.moveTaskToHead(level, ghast);
        task = FsdTaskAttachment.getTask(ghast);
        if (task.getItem() != GhastFsdContent.FSD_TASK) {
            TERRAIN_CACHE.remove(ghast.getUUID());
            FsdTaskAttachment.syncTaskFlag(ghast, false);
            syncNoAi(ghast, false);
            UUID previousId = GhastCouplingAttachment.previous(ghast).orElse(null);
            Vec3 virtualPosition = previousId != null && level.getEntity(previousId) == null
                ? VirtualGhastTracker.followVirtualCoupling(ghast.getUUID())
                : VirtualGhastTracker.virtualPosition(ghast.getUUID());
            if (virtualPosition != null && virtualPosition.distanceToSqr(ghast.position()) > 16.0) {
                ghast.setPos(virtualPosition);
                ghast.setDeltaMovement(Vec3.ZERO);
            }
            if (GhastCouplingAttachment.tick(level, ghast)) {
                syncVirtualCoupled(level, ghast);
                return;
            }
            VirtualGhastTracker.remove(ghast.getUUID());
            tickFsdTaskTemptation(level, ghast);
            return;
        }
        FsdTaskAttachment.syncTaskFlag(ghast, true);
        syncNoAi(ghast, true);
        List<RouteInstruction> route = RouteData.read(task);
        if (route.isEmpty()) {
            TERRAIN_CACHE.remove(ghast.getUUID());
            VirtualGhastTracker.remove(ghast.getUUID());
            syncVirtualTrain(level, ghast);
            return;
        }

        AutopilotState state = AutopilotState.read(ghast);
        int oldIndex = state.index;
        int oldWaitTicks = state.waitTicks;
        int oldPauseTicks = state.pauseTicks;
        boolean oldDocked = state.docked;
        Vec3 virtualPosition = VirtualGhastTracker.virtualPosition(ghast.getUUID());
        if (virtualPosition != null && virtualPosition.distanceToSqr(ghast.position()) > 16.0) {
            ghast.setPos(virtualPosition);
            ghast.setDeltaMovement(Vec3.ZERO);
        }
        if (state.pauseTicks > 0) {
            state.pauseTicks--;
            state.write(ghast);
            VirtualGhastTracker.syncLoaded(ghast.getUUID(), level.dimension(), ghast.position(), ghast.getYRot(), GhastCouplingAttachment.previous(ghast).orElse(null), GhastCouplingAttachment.next(ghast).orElse(null), FsdTaskNotifier.ownerUuid(task), FsdTaskNotifier.groupName(task), state, route);
            syncVirtualTrain(level, ghast);
            return;
        }

        if (state.index < 0 || state.index >= route.size()) {
            state.index = Math.min(Math.max(0, RouteData.focus(task)), route.size() - 1);
        }
        RouteInstruction instruction = route.get(state.index);
        boolean taskChanged = switch (instruction.type()) {
            case "fly_to_station" -> tickStation(level, ghast, task, state, instruction, route.size(), RouteData.loop(task));
            default -> {
                advance(state, route.size(), RouteData.loop(task));
                yield false;
            }
        };
        if (RouteData.focus(task) != state.index) {
            RouteData.setFocus(task, state.index);
            taskChanged = true;
        }
        if (taskChanged) {
            FsdTaskAttachment.setTask(ghast, task);
        }
        if (oldIndex != state.index || oldWaitTicks != state.waitTicks || oldPauseTicks != state.pauseTicks || oldDocked != state.docked) {
            state.write(ghast);
        }
        VirtualGhastTracker.syncLoaded(ghast.getUUID(), level.dimension(), ghast.position(), ghast.getYRot(), GhastCouplingAttachment.previous(ghast).orElse(null), GhastCouplingAttachment.next(ghast).orElse(null), FsdTaskNotifier.ownerUuid(task), FsdTaskNotifier.groupName(task), state, route);
        syncVirtualTrain(level, ghast);
    }

    private static void syncVirtualTrain(ServerLevel level, HappyGhast head) {
        Set<UUID> seen = new HashSet<>();
        HappyGhast current = head;
        while (seen.add(current.getUUID())) {
            GhastCouplingAttachment.syncCouplingData(current);
            UUID nextId = GhastCouplingAttachment.next(current).orElse(null);
            if (nextId == null) {
                return;
            }
            Entity next = level.getEntity(nextId);
            if (!(next instanceof HappyGhast nextGhast) || !nextGhast.isAlive()) {
                return;
            }
            current = nextGhast;
            syncVirtualCoupled(level, current);
        }
    }

    private static void syncVirtualCoupled(ServerLevel level, HappyGhast ghast) {
        VirtualGhastTracker.syncCoupled(
            ghast.getUUID(),
            level.dimension(),
            ghast.position(),
            ghast.getYRot(),
            GhastCouplingAttachment.previous(ghast).orElse(null),
            GhastCouplingAttachment.next(ghast).orElse(null)
        );
    }

    private static boolean tickStation(ServerLevel level, HappyGhast ghast, ItemStack task, AutopilotState state, RouteInstruction instruction, int routeSize, boolean loop) {
        BlockPos station = resolveStation(level, instruction);
        if (station == null) {
            boolean taskChanged = FsdTaskNotifier.notifyMissingStation(level, ghast, task, instruction.stationName());
            ghast.setDeltaMovement(Vec3.ZERO);
            return taskChanged;
        }
        boolean taskChanged = FsdTaskNotifier.notifyResumed(level, ghast, task);
        int dockingHeight = dockingHeight(level, station, instruction);
        Direction stationDirection = stationDirection(level, station, instruction);
        Vec3 target = dockingTarget(ghast, station, dockingHeight);
        if (!isDockedAt(ghast, station, target, dockingHeight)) {
            state.docked = false;
            Vec3 flightTarget = flightTarget(level, ghast, target);
            double horizontalDistance = horizontalDistance(ghast.position(), target);
            double cruiseSpeed = autopilotSpeed(ghast);
            double speed = horizontalDistance <= GhastStationBlock.ARRIVAL_RADIUS ? cruiseSpeed * DOCKING_SPEED_MULTIPLIER : cruiseSpeed;
            moveToward(level, ghast, flightTarget, speed);
            return taskChanged;
        }

        boolean wasDocked = state.docked;
        state.docked = true;
        ghast.setDeltaMovement(Vec3.ZERO);
        alignDockedToDirection(ghast, stationDirection);
        if (!wasDocked) {
            playArrivalSound(level, station);
        }
        state.waitTicks++;
        if (departureSatisfied(level, ghast, station, instruction, state)) {
            advance(state, routeSize, loop);
        }
        return taskChanged;
    }

    private static BlockPos resolveStation(ServerLevel level, RouteInstruction instruction) {
        GhastStationData.StationRef stationRef = GhastStationData.get(level).findIn(level, instruction.stationName()).orElse(null);
        if (stationRef != null) {
            return stationRef.pos();
        }
        return null;
    }

    private static int dockingHeight(ServerLevel level, BlockPos station, RouteInstruction instruction) {
        if (level.getBlockEntity(station) instanceof GhastStationBlockEntity stationEntity) {
            return stationEntity.dockingHeight();
        }
        GhastStationData.StationRef stationRef = GhastStationData.get(level).findIn(level, instruction.stationName()).orElse(null);
        if (stationRef != null) {
            return stationRef.dockingHeight();
        }
        return GhastStationBlockEntity.DEFAULT_DOCKING_HEIGHT;
    }

    private static Direction stationDirection(ServerLevel level, BlockPos station, RouteInstruction instruction) {
        if (level.getBlockEntity(station) instanceof GhastStationBlockEntity stationEntity) {
            return stationEntity.stationDirection();
        }
        GhastStationData.StationRef stationRef = GhastStationData.get(level).findIn(level, instruction.stationName()).orElse(null);
        if (stationRef != null) {
            return stationRef.direction();
        }
        return Direction.NORTH;
    }

    private static Vec3 dockingTarget(HappyGhast ghast, BlockPos station, int dockingHeight) {
        double bottomOffset = ghast.getY() - ghast.getBoundingBox().minY;
        return new Vec3(station.getX() + 0.5, station.getY() + dockingHeight + bottomOffset, station.getZ() + 0.5);
    }

    private static boolean isDockedAt(HappyGhast ghast, BlockPos station, Vec3 target, int dockingHeight) {
        if (!GhastStationBlock.arrivalBox(station).intersects(ghast.getBoundingBox())) {
            return false;
        }
        double horizontal = horizontalDistance(ghast.position(), target);
        double targetBottom = station.getY() + dockingHeight;
        double bottomError = Math.abs(ghast.getBoundingBox().minY - targetBottom);
        return horizontal <= DOCKING_HORIZONTAL_TOLERANCE && bottomError <= DOCKING_VERTICAL_TOLERANCE;
    }

    private static Vec3 flightTarget(ServerLevel level, HappyGhast ghast, Vec3 target) {
        Vec3 horizontal = new Vec3(target.x - ghast.getX(), 0.0, target.z - ghast.getZ());
        double horizontalDistance = horizontal.length();
        if (horizontalDistance <= GhastStationBlock.ARRIVAL_RADIUS * 0.65) {
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
        double blendStart = GhastStationBlock.ARRIVAL_RADIUS * 0.65;
        double cruiseBlend = Math.max(0.0, Math.min(1.0, (horizontalDistance - blendStart) / (CRUISE_CLEARANCE_DISTANCE - blendStart)));
        double desiredBottomY = lerp(target.y - bottomOffset, safeBottomY, cruiseBlend);
        return new Vec3(target.x, Math.max(target.y, desiredBottomY + bottomOffset), target.z);
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

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    private record TerrainClearance(double safeBottomY, double firstClimbDistance) {}

    private record TerrainCache(ResourceKey<Level> dimension, int tick, Vec3 position, Vec3 direction, double horizontalDistance, TerrainClearance clearance) {}

    private static void playArrivalSound(ServerLevel level, BlockPos station) {
        NoteBlockInstrument instrument = NoteBlockInstrument.HARP;
        int note = GhastStationBlockEntity.DEFAULT_NOTE;
        if (level.getBlockEntity(station) instanceof GhastStationBlockEntity stationEntity) {
            instrument = stationEntity.arrivalInstrument();
            note = stationEntity.arrivalNote();
        }
        float pitch = (float)Math.pow(2.0, (note - 12) / 12.0);
        level.playSound(null, station.getX() + 0.5, station.getY() + 0.5, station.getZ() + 0.5, instrument.getSoundEvent(), SoundSource.BLOCKS, 3.0F, pitch);
    }

    private static int surfaceY(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    private static void moveToward(ServerLevel level, HappyGhast ghast, Vec3 target, double speed) {
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
        for (double checked = OBSTACLE_LOOK_AHEAD_STEP; checked <= distance; checked += OBSTACLE_LOOK_AHEAD_STEP) {
            if (!level.noCollision(ghast, ghast.getBoundingBox().move(direction.scale(checked)))) {
                return false;
            }
        }
        return level.noCollision(ghast, ghast.getBoundingBox().move(movement));
    }

    private static double autopilotSpeed(HappyGhast ghast) {
        double flyingSpeed = ghast.getAttributeValue(Attributes.FLYING_SPEED);
        return Math.max(0.02, flyingSpeed * RIDDEN_SPEED_MULTIPLIER);
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        double x = first.x - second.x;
        double z = first.z - second.z;
        return Math.sqrt(x * x + z * z);
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

    private static void alignDockedToDirection(HappyGhast ghast, Direction direction) {
        setRotation(ghast, direction.toYRot(), 0.0F);
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

    private static void tickFsdTaskTemptation(ServerLevel level, HappyGhast ghast) {
        if (!ghast.isAlive() || ghast.isBaby() || FsdTaskAttachment.hasTask(ghast)) {
            return;
        }
        Player target = null;
        double bestDistance = 16.0 * 16.0;
        for (Player player : level.players()) {
            if (player.isSpectator() || !holdsFsdTask(player)) {
                continue;
            }
            double distance = player.distanceToSqr(ghast);
            if (distance < bestDistance) {
                bestDistance = distance;
                target = player;
            }
        }
        if (target == null) {
            return;
        }
        Vec3 wanted = target.position().add(0.0, 2.5, 0.0);
        ghast.getLookControl().setLookAt(target, 30.0F, 30.0F);
        ghast.getMoveControl().setWantedPosition(wanted.x, wanted.y, wanted.z, 1.25);
        if (bestDistance > 9.0) {
            Vec3 delta = wanted.subtract(ghast.position());
            if (delta.lengthSqr() > 0.0001) {
                ghast.setDeltaMovement(ghast.getDeltaMovement().scale(0.75).add(delta.normalize().scale(0.08)));
                ghast.setNoGravity(true);
                ghast.hurtMarked = true;
            }
        }
    }

    private static boolean holdsFsdTask(Player player) {
        return player.getMainHandItem().getItem() == GhastFsdContent.FSD_TASK
            || player.getOffhandItem().getItem() == GhastFsdContent.FSD_TASK;
    }

    private static boolean departureSatisfied(ServerLevel level, HappyGhast ghast, BlockPos station, RouteInstruction instruction, AutopilotState state) {
        return switch (instruction.departureCondition()) {
            case "wait_for_passengers" -> ghast.getPassengers().stream().filter(Player.class::isInstance).count() >= instruction.passengerCount();
            case "wait_for_redstone_on" -> GhastStationBlock.isRedstonePowered(level, station);
            case "wait_for_redstone_off" -> !GhastStationBlock.isRedstonePowered(level, station);
            case "wait_seconds" -> state.waitTicks >= Math.max(0, instruction.waitSeconds()) * 20;
            default -> state.waitTicks >= 100;
        };
    }

    private static void advance(AutopilotState state, int routeSize, boolean loop) {
        if (routeSize <= 0) {
            state.index = 0;
        } else if (state.index >= routeSize - 1) {
            state.index = loop ? 0 : routeSize - 1;
        } else {
            state.index++;
        }
        state.waitTicks = 0;
        state.docked = false;
    }

    private static void syncNoAi(HappyGhast ghast, boolean shouldDisable) {
        if (shouldDisable) {
            if (!ghast.isNoAi()) {
                ghast.setNoAi(true);
            }
            CompoundTag tag = ghast.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (tag.getBooleanOr(AI_DISABLED_BY_FSD, false)) {
                return;
            }
            tag.putBoolean(AI_DISABLED_BY_FSD, true);
            ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            return;
        }

        CustomData data = ghast.get(DataComponents.CUSTOM_DATA);
        boolean disabledByFsd = data != null && data.copyTag().getBooleanOr(AI_DISABLED_BY_FSD, false);
        if (disabledByFsd) {
            ghast.setNoAi(false);
            CompoundTag tag = data.copyTag();
            tag.remove(AI_DISABLED_BY_FSD);
            ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }
}
