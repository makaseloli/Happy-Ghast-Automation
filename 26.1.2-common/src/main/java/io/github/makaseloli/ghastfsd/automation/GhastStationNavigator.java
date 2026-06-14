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
    private static final double DOCKING_SPEED_MULTIPLIER = 0.45;
    private static final double STATION_EXIT_MARGIN = 3.0;
    private static final double ABOVE_STATION_MARGIN = 3.0;
    private static final int STATION_PLAN_TICKS = 10;
    private static final Map<UUID, StationPlan> STATION_PLAN_CACHE = new HashMap<>();

    private GhastStationNavigator() {}

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
        if (!GhastStationGeometry.isDockedAt(ghast, station, target, dockingHeight)) {
            state.docked = false;
            Vec3 flightTarget = stationFlightTarget(level, ghast, station, dockingHeight, stationDirection, target);
            double horizontalDistance = GhastStationGeometry.horizontalDistance(ghast.position(), target);
            double verticalDistance = Math.abs(ghast.getBoundingBox().minY - (station.getY() + dockingHeight));
            double cruiseSpeed = GhastFlightController.speed(ghast);
            boolean finalDockLeg = flightTarget.distanceToSqr(target) <= 0.25;
            boolean closeToDock = finalDockLeg && horizontalDistance <= GhastStationGeometry.APPROACH_RADIUS && verticalDistance <= GhastStationGeometry.APPROACH_RADIUS;
            double speed = closeToDock ? cruiseSpeed * DOCKING_SPEED_MULTIPLIER : cruiseSpeed;
            GhastFlightController.moveToward(level, ghast, flightTarget, speed);
            return taskChanged;
        }

        boolean wasDocked = state.docked;
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

    private static Vec3 stationFlightTarget(ServerLevel level, HappyGhast ghast, BlockPos station, int dockingHeight, Direction stationDirection, Vec3 dockingTarget) {
        StationPlan cached = STATION_PLAN_CACHE.get(ghast.getUUID());
        if (cached != null
            && cached.tick + STATION_PLAN_TICKS > ghast.tickCount
            && cached.station.equals(station)
            && cached.dockingHeight == dockingHeight
            && cached.stationDirection == stationDirection
            && cached.dockingTarget.distanceToSqr(dockingTarget) <= 0.25) {
            return cached.flightTarget;
        }
        Vec3 flightTarget = GhastFlightController.terrainAwareTarget(
            level,
            ghast,
            computeStationFlightTarget(level, ghast, station, dockingHeight, stationDirection, dockingTarget)
        );
        STATION_PLAN_CACHE.put(ghast.getUUID(), new StationPlan(ghast.tickCount, station, dockingHeight, stationDirection, dockingTarget, flightTarget));
        return flightTarget;
    }

    private static Vec3 computeStationFlightTarget(ServerLevel level, HappyGhast ghast, BlockPos station, int dockingHeight, Direction stationDirection, Vec3 dockingTarget) {
        Vec3 stationCenter = Vec3.atCenterOf(station);
        double horizontalDistance = GhastStationGeometry.horizontalDistance(ghast.position(), dockingTarget);
        double targetBottomY = station.getY() + dockingHeight;
        double verticalDistance = Math.abs(ghast.getBoundingBox().minY - targetBottomY);

        double stationColumnRadius = ghast.getBbWidth() * 0.5 + GhastStationGeometry.ARRIVAL_HORIZONTAL_RADIUS + STATION_EXIT_MARGIN;
        double aboveStationY = dockingTarget.y + ABOVE_STATION_MARGIN;
        boolean belowApproachHeight = ghast.getY() < aboveStationY - GhastStationGeometry.DOCKING_VERTICAL_TOLERANCE;
        if (belowApproachHeight) {
            Vec3 away = horizontalAwayFromStation(ghast, stationCenter, stationDirection);
            return new Vec3(
                stationCenter.x + away.x * stationColumnRadius,
                aboveStationY,
                stationCenter.z + away.z * stationColumnRadius
            );
        }
        if (horizontalDistance > GhastStationGeometry.DOCKING_HORIZONTAL_TOLERANCE) {
            return new Vec3(dockingTarget.x, aboveStationY, dockingTarget.z);
        }
        if (verticalDistance > GhastStationGeometry.DOCKING_VERTICAL_TOLERANCE) {
            return dockingTarget;
        }
        return GhastFlightController.terrainAwareTarget(level, ghast, dockingTarget);
    }

    private static Vec3 horizontalAwayFromStation(HappyGhast ghast, Vec3 stationCenter, Direction stationDirection) {
        Vec3 away = new Vec3(ghast.getX() - stationCenter.x, 0.0, ghast.getZ() - stationCenter.z);
        if (away.lengthSqr() > 1.0E-6) {
            return away.normalize();
        }
        return new Vec3(stationDirection.getStepX(), 0.0, stationDirection.getStepZ()).normalize();
    }

    private record StationPlan(int tick, BlockPos station, int dockingHeight, Direction stationDirection, Vec3 dockingTarget, Vec3 flightTarget) {}

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
