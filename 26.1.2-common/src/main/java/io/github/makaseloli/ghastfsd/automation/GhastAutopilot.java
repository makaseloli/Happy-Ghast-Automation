package io.github.makaseloli.ghastfsd.automation;

import io.github.makaseloli.ghastfsd.content.GhastFsdContent;
import io.github.makaseloli.ghastfsd.content.FsdTaskAttachment;
import io.github.makaseloli.ghastfsd.content.GhastStationBlock;
import io.github.makaseloli.ghastfsd.content.GhastStationBlockEntity;
import io.github.makaseloli.ghastfsd.content.GhastStationData;
import io.github.makaseloli.ghastfsd.route.RouteData;
import io.github.makaseloli.ghastfsd.route.RouteInstruction;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
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
    private static final double TERRAIN_LOOK_AHEAD_DISTANCE = 128.0;
    private static final double TERRAIN_LOOK_AHEAD_STEP = 8.0;

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
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof HappyGhast ghast) {
                    seen.add(ghast.getUUID());
                    tickGhast(level, ghast);
                }
            }
        }
        VirtualGhastTracker.tickUnloaded(server, seen);
    }

    private static void tickGhast(ServerLevel level, HappyGhast ghast) {
        ItemStack task = ghast.getItemBySlot(EquipmentSlot.BODY);
        if (task.getItem() == GhastFsdContent.FSD_TASK) {
            ghast.setItemSlot(EquipmentSlot.BODY, ItemStack.EMPTY);
            FsdTaskAttachment.setTask(ghast, task);
            initializeAttachedTask(ghast, RouteData.focus(task));
        }
        task = FsdTaskAttachment.getTask(ghast);
        if (task.getItem() != GhastFsdContent.FSD_TASK) {
            FsdTaskAttachment.syncTaskFlag(ghast, false);
            syncNoAi(ghast, false);
            VirtualGhastTracker.remove(ghast.getUUID());
            tickFsdTaskTemptation(level, ghast);
            return;
        }
        FsdTaskAttachment.syncTaskFlag(ghast, true);
        syncNoAi(ghast, true);
        List<RouteInstruction> route = RouteData.read(task);
        if (route.isEmpty()) {
            VirtualGhastTracker.remove(ghast.getUUID());
            return;
        }

        AutopilotState state = AutopilotState.read(ghast);
        Vec3 virtualPosition = VirtualGhastTracker.virtualPosition(ghast.getUUID());
        if (virtualPosition != null && virtualPosition.distanceToSqr(ghast.position()) > 16.0) {
            ghast.setPos(virtualPosition);
            ghast.setDeltaMovement(Vec3.ZERO);
        }
        if (state.pauseTicks > 0) {
            state.pauseTicks--;
            state.write(ghast);
            VirtualGhastTracker.syncLoaded(ghast.getUUID(), level.dimension(), ghast.position(), state, route);
            return;
        }

        if (state.index < 0 || state.index >= route.size()) {
            state.index = Math.min(Math.max(0, RouteData.focus(task)), route.size() - 1);
        }
        RouteInstruction instruction = route.get(state.index);
        switch (instruction.type()) {
            case "fly_to_station" -> tickStation(level, ghast, state, instruction, route.size(), RouteData.loop(task));
            default -> advance(state, route.size(), RouteData.loop(task));
        }
        RouteData.setFocus(task, state.index);
        FsdTaskAttachment.setTask(ghast, task);
        state.write(ghast);
        VirtualGhastTracker.syncLoaded(ghast.getUUID(), level.dimension(), ghast.position(), state, route);
    }

    private static void tickStation(ServerLevel level, HappyGhast ghast, AutopilotState state, RouteInstruction instruction, int routeSize, boolean loop) {
        BlockPos station = resolveStation(level, instruction);
        if (station == null) {
            return;
        }
        int dockingHeight = dockingHeight(level, station, instruction);
        Vec3 target = dockingTarget(ghast, station, dockingHeight);
        GhastStationBlock.notifyComparator(level, station);
        if (!isDockedAt(ghast, station, target, dockingHeight)) {
            state.docked = false;
            Vec3 flightTarget = flightTarget(level, ghast, target);
            double horizontalDistance = horizontalDistance(ghast.position(), target);
            double cruiseSpeed = autopilotSpeed(ghast);
            double speed = horizontalDistance <= GhastStationBlock.ARRIVAL_RADIUS ? cruiseSpeed * DOCKING_SPEED_MULTIPLIER : cruiseSpeed;
            moveToward(level, ghast, flightTarget, speed);
            return;
        }

        boolean wasDocked = state.docked;
        state.docked = true;
        ghast.setDeltaMovement(Vec3.ZERO);
        alignDockedToGrid(ghast);
        if (!wasDocked) {
            playArrivalSound(level, station);
        }
        state.waitTicks++;
        if (departureSatisfied(level, ghast, station, instruction, state)) {
            advance(state, routeSize, loop);
        }
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
        double safeBottomY = terrainClearanceBottomY(level, ghast.position(), direction, horizontalDistance);
        double cruiseBlend = Math.min(1.0, horizontalDistance / CRUISE_CLEARANCE_DISTANCE);
        double desiredBottomY = lerp(target.y - bottomOffset, safeBottomY, cruiseBlend);
        return new Vec3(target.x, Math.max(target.y, desiredBottomY + bottomOffset), target.z);
    }

    private static double terrainClearanceBottomY(ServerLevel level, Vec3 position, Vec3 direction, double horizontalDistance) {
        double lookAhead = Math.min(horizontalDistance, TERRAIN_LOOK_AHEAD_DISTANCE);
        int highestSurface = surfaceY(level, (int)Math.floor(position.x), (int)Math.floor(position.z));
        for (double distance = TERRAIN_LOOK_AHEAD_STEP; distance <= lookAhead; distance += TERRAIN_LOOK_AHEAD_STEP) {
            Vec3 sample = position.add(direction.scale(distance));
            highestSurface = Math.max(highestSurface, surfaceY(level, (int)Math.floor(sample.x), (int)Math.floor(sample.z)));
        }
        Vec3 targetSample = position.add(direction.scale(horizontalDistance));
        highestSurface = Math.max(highestSurface, surfaceY(level, (int)Math.floor(targetSample.x), (int)Math.floor(targetSample.z)));
        return highestSurface + CRUISE_CLEARANCE;
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
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

    private static int surfaceY(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, level.getMaxY(), z);
        while (cursor.getY() > level.getMinY()) {
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir() || !state.getFluidState().isEmpty()) {
                return cursor.getY() + 1;
            }
            cursor.move(0, -1, 0);
        }
        return level.getMinY();
    }

    private static void moveToward(ServerLevel level, HappyGhast ghast, Vec3 target, double speed) {
        Vec3 delta = target.subtract(ghast.position());
        if (delta.lengthSqr() < 0.0001) {
            ghast.setDeltaMovement(Vec3.ZERO);
            return;
        }
        Vec3 desired = delta.normalize().scale(Math.min(speed, delta.length()));
        Vec3 blended = ghast.getDeltaMovement().scale(0.55).add(desired.scale(0.45));
        ghast.setDeltaMovement(blended);
        ghast.setNoGravity(true);
        faceToward(ghast, blended);
        ghast.move(MoverType.SELF, blended);
        ghast.hurtMarked = true;
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

    private static void alignDockedToGrid(HappyGhast ghast) {
        float yaw = Math.round(ghast.getYRot() / 90.0F) * 90.0F;
        setRotation(ghast, yaw, 0.0F);
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
