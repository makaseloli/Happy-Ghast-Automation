package io.github.makaseloli.ghastfsd.automation;

import io.github.makaseloli.ghastfsd.content.FsdTaskNotifier;
import io.github.makaseloli.ghastfsd.content.GhastStationBlock;
import io.github.makaseloli.ghastfsd.content.GhastStationBlockEntity;
import io.github.makaseloli.ghastfsd.content.GhastStationData;
import io.github.makaseloli.ghastfsd.content.GhastStationGeometry;
import io.github.makaseloli.ghastfsd.route.RouteInstruction;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.phys.Vec3;

final class GhastStationNavigator {
    private static final double DOCKING_SPEED_MULTIPLIER = 0.38;
    private static final double APPROACH_HEIGHT_MARGIN = 4.0;
    private static final double APPROACH_HORIZONTAL_TOLERANCE = 2.25;
    private static final double DESCEND_RESET_RADIUS = 7.0;
    private static final double SNAP_HORIZONTAL_RADIUS = 2.75;
    private static final double SNAP_VERTICAL_RADIUS = 2.5;
    private static final double FORCE_DOCK_HORIZONTAL_RADIUS = 1.2;
    private static final double FORCE_DOCK_VERTICAL_RADIUS = 1.25;
    private static final int PHASE_TIMEOUT_TICKS = 20 * 20;
    private static final int STATION_PLAN_TICKS = 10;
    private static final Map<UUID, StationPlan> STATION_PLAN_CACHE = new HashMap<>();

    private GhastStationNavigator() {}

    static void clear(HappyGhast ghast) {
        STATION_PLAN_CACHE.remove(ghast.getUUID());
    }

    static boolean tick(ServerLevel level, HappyGhast ghast, ItemStack task, AutopilotState state, RouteInstruction instruction, int routeSize, boolean loop) {
        BlockPos station = resolveStation(level, instruction);
        if (station == null) {
            boolean taskChanged = FsdTaskNotifier.notifyMissingStation(level, ghast, task, instruction.stationName());
            ghast.setDeltaMovement(Vec3.ZERO);
            STATION_PLAN_CACHE.remove(ghast.getUUID());
            return taskChanged;
        }
        boolean taskChanged = FsdTaskNotifier.notifyResumed(level, ghast, task);
        int dockingHeight = dockingHeight(level, station, instruction);
        Direction stationDirection = stationDirection(level, station, instruction);
        Vec3 target = GhastStationGeometry.dockingTarget(ghast, station, dockingHeight);
        int targetBottomY = station.getY() + dockingHeight;
        state.phaseTicks++;

        DockResult dockResult = tryCompleteDock(level, ghast, station, target, targetBottomY, dockingHeight, stationDirection, state);
        if (dockResult != DockResult.NOT_DOCKED) {
            handleDocked(level, ghast, station, stationDirection, instruction, state, routeSize, loop);
            return taskChanged;
        }

        state.docked = false;
        switch (state.phase) {
            case DOCKED, ALIGN -> tickAlign(level, ghast, target, targetBottomY, stationDirection, state);
            case DESCEND -> tickDescend(level, ghast, target, targetBottomY, stationDirection, state);
            case APPROACH -> tickApproach(level, ghast, station, dockingHeight, stationDirection, target, state);
            case CRUISE -> tickCruise(level, ghast, station, dockingHeight, stationDirection, target, state);
        }
        return taskChanged;
    }

    private static void tickCruise(ServerLevel level, HappyGhast ghast, BlockPos station, int dockingHeight, Direction stationDirection, Vec3 dockingTarget, AutopilotState state) {
        Vec3 approachTarget = approachTarget(ghast, station, dockingHeight, dockingTarget);
        if (GhastStationGeometry.horizontalDistance(ghast.position(), dockingTarget) <= GhastStationGeometry.APPROACH_RADIUS * 1.5
            && ghast.getBoundingBox().minY >= station.getY() + dockingHeight - GhastStationGeometry.DOCKING_VERTICAL_TOLERANCE) {
            state.transitionTo(AutopilotPhase.APPROACH);
        }
        Vec3 flightTarget = stationFlightTarget(level, ghast, station, dockingHeight, stationDirection, approachTarget);
        GhastFlightController.moveToward(level, ghast, flightTarget, GhastFlightController.speed(ghast));
    }

    private static void tickApproach(ServerLevel level, HappyGhast ghast, BlockPos station, int dockingHeight, Direction stationDirection, Vec3 dockingTarget, AutopilotState state) {
        Vec3 approachTarget = approachTarget(ghast, station, dockingHeight, dockingTarget);
        double horizontalDistance = GhastStationGeometry.horizontalDistance(ghast.position(), dockingTarget);
        double verticalDistance = Math.abs(ghast.position().y - approachTarget.y);
        if ((horizontalDistance <= APPROACH_HORIZONTAL_TOLERANCE && verticalDistance <= APPROACH_HEIGHT_MARGIN)
            || state.phaseTicks >= PHASE_TIMEOUT_TICKS) {
            state.transitionTo(AutopilotPhase.DESCEND);
            tickDescend(level, ghast, dockingTarget, station.getY() + dockingHeight, stationDirection, state);
            return;
        }
        Vec3 flightTarget = stationFlightTarget(level, ghast, station, dockingHeight, stationDirection, approachTarget);
        GhastFlightController.moveToward(level, ghast, flightTarget, GhastFlightController.speed(ghast));
    }

    private static void tickDescend(ServerLevel level, HappyGhast ghast, Vec3 dockingTarget, int targetBottomY, Direction stationDirection, AutopilotState state) {
        double horizontalDistance = GhastStationGeometry.horizontalDistance(ghast.position(), dockingTarget);
        if (horizontalDistance > DESCEND_RESET_RADIUS && state.phaseTicks > 20) {
            state.transitionTo(AutopilotPhase.APPROACH);
            return;
        }
        if (trySnapDock(level, ghast, dockingTarget, targetBottomY, stationDirection, state.phaseTicks, true)) {
            state.transitionTo(AutopilotPhase.ALIGN);
            return;
        }
        if (isInsideFinalDockWindow(ghast, dockingTarget, targetBottomY)) {
            forceFinalDock(ghast, dockingTarget, stationDirection);
            state.transitionTo(AutopilotPhase.ALIGN);
            return;
        }
        double speed = GhastFlightController.speed(ghast) * DOCKING_SPEED_MULTIPLIER;
        GhastFlightController.moveToward(level, ghast, dockingTarget, speed);
    }

    private static void tickAlign(ServerLevel level, HappyGhast ghast, Vec3 dockingTarget, int targetBottomY, Direction stationDirection, AutopilotState state) {
        if (!trySnapDock(level, ghast, dockingTarget, targetBottomY, stationDirection, state.phaseTicks, true)) {
            state.transitionTo(AutopilotPhase.DESCEND);
            tickDescend(level, ghast, dockingTarget, targetBottomY, stationDirection, state);
            return;
        }
        GhastFlightController.alignToDirection(ghast, stationDirection);
    }

    private static void handleDocked(ServerLevel level, HappyGhast ghast, BlockPos station, Direction stationDirection, RouteInstruction instruction, AutopilotState state, int routeSize, boolean loop) {
        boolean wasDocked = state.docked;
        state.transitionTo(AutopilotPhase.DOCKED);
        state.docked = true;
        STATION_PLAN_CACHE.remove(ghast.getUUID());
        ghast.setDeltaMovement(Vec3.ZERO);
        GhastFlightController.alignToDirection(ghast, stationDirection);
        if (!wasDocked) {
            playArrivalSound(level, station);
        }
        state.waitTicks++;
        if (departureSatisfied(level, ghast, station, instruction, state)) {
            AutopilotRouteProgress.advance(state, routeSize, loop);
        }
    }

    private static BlockPos resolveStation(ServerLevel level, RouteInstruction instruction) {
        if (!instruction.matchesDimension(level.dimension())) {
            return null;
        }
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

    private static Vec3 stationFlightTarget(ServerLevel level, HappyGhast ghast, BlockPos station, int dockingHeight, Direction stationDirection, Vec3 target) {
        StationPlan cached = STATION_PLAN_CACHE.get(ghast.getUUID());
        if (cached != null
            && cached.tick + STATION_PLAN_TICKS > ghast.tickCount
            && cached.station.equals(station)
            && cached.dockingHeight == dockingHeight
            && cached.stationDirection == stationDirection
            && cached.dockingTarget.distanceToSqr(target) <= 0.25) {
            return cached.flightTarget;
        }
        Vec3 flightTarget = GhastFlightController.terrainAwareTarget(
            level,
            ghast,
            target
        );
        STATION_PLAN_CACHE.put(ghast.getUUID(), new StationPlan(ghast.tickCount, station, dockingHeight, stationDirection, target, flightTarget));
        return flightTarget;
    }

    private static Vec3 approachTarget(HappyGhast ghast, BlockPos station, int dockingHeight, Vec3 dockingTarget) {
        double targetBottomY = station.getY() + dockingHeight;
        double bottomOffset = ghast.getY() - ghast.getBoundingBox().minY;
        return new Vec3(dockingTarget.x, targetBottomY + bottomOffset + APPROACH_HEIGHT_MARGIN, dockingTarget.z);
    }

    private static boolean trySnapDock(ServerLevel level, HappyGhast ghast, Vec3 dockingTarget, int targetBottomY, Direction stationDirection, int phaseTicks, boolean allowForcedFinal) {
        double horizontalDistance = GhastStationGeometry.horizontalDistance(ghast.position(), dockingTarget);
        double bottomError = Math.abs(ghast.getBoundingBox().minY - targetBottomY);
        if (horizontalDistance > SNAP_HORIZONTAL_RADIUS || bottomError > SNAP_VERTICAL_RADIUS) {
            return false;
        }
        if (phaseTicks < 20 && horizontalDistance > GhastStationGeometry.DOCKING_HORIZONTAL_TOLERANCE) {
            return false;
        }
        if (!level.noCollision(ghast, ghast.getBoundingBox().move(dockingTarget.subtract(ghast.position())))
            && !(allowForcedFinal && isInsideFinalDockWindow(ghast, dockingTarget, targetBottomY))) {
            return false;
        }
        forceFinalDock(ghast, dockingTarget, stationDirection);
        return true;
    }

    private static DockResult tryCompleteDock(ServerLevel level, HappyGhast ghast, BlockPos station, Vec3 dockingTarget, int targetBottomY, int dockingHeight, Direction stationDirection, AutopilotState state) {
        if (GhastStationGeometry.isDockedAt(ghast, station, dockingTarget, dockingHeight)) {
            forceFinalDock(ghast, dockingTarget, stationDirection);
            return DockResult.DOCKED;
        }
        if (trySnapDock(level, ghast, dockingTarget, targetBottomY, stationDirection, state.phaseTicks, false)) {
            return DockResult.DOCKED;
        }
        if (state.phase == AutopilotPhase.DESCEND || state.phase == AutopilotPhase.ALIGN || state.phase == AutopilotPhase.DOCKED) {
            double horizontalDistance = GhastStationGeometry.horizontalDistance(ghast.position(), dockingTarget);
            double bottomError = Math.abs(ghast.getBoundingBox().minY - targetBottomY);
            if (horizontalDistance <= SNAP_HORIZONTAL_RADIUS && bottomError <= SNAP_VERTICAL_RADIUS) {
                forceFinalDock(ghast, dockingTarget, stationDirection);
                return DockResult.DOCKED;
            }
        }
        return DockResult.NOT_DOCKED;
    }

    private static boolean isInsideFinalDockWindow(HappyGhast ghast, Vec3 dockingTarget, int targetBottomY) {
        double horizontalDistance = GhastStationGeometry.horizontalDistance(ghast.position(), dockingTarget);
        double bottomError = Math.abs(ghast.getBoundingBox().minY - targetBottomY);
        return horizontalDistance <= FORCE_DOCK_HORIZONTAL_RADIUS && bottomError <= FORCE_DOCK_VERTICAL_RADIUS;
    }

    private static void forceFinalDock(HappyGhast ghast, Vec3 dockingTarget, Direction stationDirection) {
        ghast.setPos(dockingTarget);
        ghast.setDeltaMovement(Vec3.ZERO);
        GhastFlightController.alignToDirection(ghast, stationDirection);
        ghast.hurtMarked = true;
    }

    private record StationPlan(int tick, BlockPos station, int dockingHeight, Direction stationDirection, Vec3 dockingTarget, Vec3 flightTarget) {}

    private enum DockResult {
        DOCKED,
        NOT_DOCKED
    }

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

    private static boolean departureSatisfied(ServerLevel level, HappyGhast ghast, BlockPos station, RouteInstruction instruction, AutopilotState state) {
        return switch (instruction.departureCondition()) {
            case "wait_for_passengers" -> ghast.getPassengers().stream().filter(Player.class::isInstance).count() >= instruction.passengerCount();
            case "wait_for_redstone_on" -> GhastStationBlock.isRedstonePowered(level, station);
            case "wait_for_redstone_off" -> !GhastStationBlock.isRedstonePowered(level, station);
            case "wait_seconds" -> state.waitTicks >= Math.max(0, instruction.waitSeconds()) * 20;
            default -> state.waitTicks >= 100;
        };
    }

}
