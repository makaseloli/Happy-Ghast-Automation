package io.github.makaseloli.ghastfsd.automation;

import io.github.makaseloli.ghastfsd.content.GhastStationData;
import io.github.makaseloli.ghastfsd.content.FsdTaskNotifier;
import io.github.makaseloli.ghastfsd.content.GhastStationBlock;
import io.github.makaseloli.ghastfsd.route.RouteData;
import io.github.makaseloli.ghastfsd.route.RouteInstruction;
import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

final class VirtualGhastTracker {
    private static final double VIRTUAL_COUPLING_SPACING = 5.5;
    private static final int MAX_COUPLED_MEMBERS = 6;
    private static final int MAX_STORED_WAIT_TICKS = 20 * 60 * 60;
    private static final int MAX_STORED_PAUSE_TICKS = 20 * 60;
    private static final int MAX_PASSENGER_COUNT = 4;
    private static final double MAX_BOTTOM_OFFSET = 32.0;
    private static final double MAX_COORDINATE = 30_000_000.0;
    private static final double MIN_STORED_SPEED = 0.08;
    private static final double MAX_STORED_SPEED = 0.75;

    static final SavedDataType<Data> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("ghastfsd", "virtual_ghasts"),
        Data::new,
        Data.CODEC,
        DataFixTypes.SAVED_DATA_MAP_DATA
    );

    private VirtualGhastTracker() {}

    static void beginServerTick(MinecraftServer server) {
        SERVER = server;
    }

    static Set<UUID> seenSet() {
        return new HashSet<>();
    }

    static Set<UUID> trackedIds(MinecraftServer server) {
        return Set.copyOf(data(server).ghasts.keySet());
    }

    static void syncLoaded(UUID id, ResourceKey<Level> dimension, Vec3 position, double bottomOffset, double speed, float yaw, UUID previous, UUID next, UUID owner, String groupName, AutopilotState state, List<RouteInstruction> route, int passengerCount, boolean loop) {
        Data data = data(stateServer());
        VirtualGhast virtual = data.ghasts.get(id);
        String missingStationNotified = "";
        String storedGroupName = FsdTaskNotifier.sanitizeGroupName(groupName);
        if (virtual != null && virtual.dimension.identifier().equals(dimension.identifier())) {
            missingStationNotified = virtual.missingStationNotified;
        }
        data.ghasts.put(id, new VirtualGhast(dimension, finitePosition(position), sanitizeBottomOffset(bottomOffset), sanitizeSpeed(speed), finiteYaw(yaw), previous, next, owner, storedGroupName, missingStationNotified, Math.max(0, state.index), clamp(state.waitTicks, 0, MAX_STORED_WAIT_TICKS), clamp(state.pauseTicks, 0, MAX_STORED_PAUSE_TICKS), state.docked, state.phase, clamp(state.phaseTicks, 0, MAX_STORED_PAUSE_TICKS), clamp(passengerCount, 0, MAX_PASSENGER_COUNT), false, loop, List.copyOf(route.size() > RouteData.MAX_ROUTE_INSTRUCTIONS ? route.subList(0, RouteData.MAX_ROUTE_INSTRUCTIONS) : route)));
        data.setDirty();
    }

    static void syncCoupled(UUID id, ResourceKey<Level> dimension, Vec3 position, double bottomOffset, double speed, float yaw, UUID previous, UUID next) {
        Data data = data(stateServer());
        data.ghasts.put(id, new VirtualGhast(dimension, finitePosition(position), sanitizeBottomOffset(bottomOffset), sanitizeSpeed(speed), finiteYaw(yaw), previous, next, null, "", "", 0, 0, 0, false, AutopilotPhase.CRUISE, 0, 0, false, true, List.of()));
        data.setDirty();
    }

    static boolean restoreUnloaded(HappyGhast ghast, AutopilotState state) {
        Data data = data(stateServer());
        VirtualGhast virtual = data.ghasts.get(ghast.getUUID());
        if (virtual == null || !virtual.unloaded || !sameDimension(ghast, virtual)) {
            return false;
        }
        if (virtual.position.distanceToSqr(ghast.position()) > 0.0001) {
            ghast.setPos(virtual.position);
            ghast.setDeltaMovement(Vec3.ZERO);
        }
        if (Math.abs(ghast.getYRot() - virtual.yaw) > 0.001F) {
            setRotation(ghast, virtual.yaw, ghast.getXRot());
        }
        state.index = virtual.index;
        state.waitTicks = virtual.waitTicks;
        state.pauseTicks = virtual.pauseTicks;
        state.docked = virtual.docked;
        state.phase = virtual.phase;
        state.phaseTicks = virtual.phaseTicks;
        return true;
    }

    static Vec3 unloadedPosition(UUID id) {
        VirtualGhast virtual = data(stateServer()).ghasts.get(id);
        return virtual == null ? null : virtual.position;
    }

    static Vec3 followVirtualCoupling(UUID id) {
        Data data = data(stateServer());
        VirtualGhast virtual = data.ghasts.get(id);
        if (virtual == null) {
            return null;
        }
        if (virtual.previous == null || !virtual.route.isEmpty()) {
            return virtual.position;
        }
        VirtualGhast previous = data.ghasts.get(virtual.previous);
        if (previous == null || !previous.dimension.identifier().equals(virtual.dimension.identifier())) {
            return virtual.position;
        }
        VirtualGhast followed = virtual.follow(previous);
        data.ghasts.put(id, followed);
        data.setDirty();
        return followed.position;
    }

    static void remove(UUID id) {
        Data data = data(stateServer());
        if (data.ghasts.remove(id) != null) {
            data.setDirty();
        }
    }

    static void remove(MinecraftServer server, UUID id) {
        Data data = data(server);
        if (data.ghasts.remove(id) != null) {
            data.setDirty();
        }
    }

    static void tickUnloaded(MinecraftServer server, Set<UUID> seen) {
        Data data = data(server);
        Map<UUID, VirtualGhast> nextGhasts = new HashMap<>(data.ghasts);
        boolean changed = false;
        for (Map.Entry<UUID, VirtualGhast> entry : nextGhasts.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                VirtualGhast ticked = entry.getValue().tick(server);
                if (!ticked.equals(entry.getValue())) {
                    entry.setValue(ticked);
                    changed = true;
                }
            }
        }
        for (int pass = 0; pass < MAX_COUPLED_MEMBERS; pass++) {
            for (Map.Entry<UUID, VirtualGhast> entry : nextGhasts.entrySet()) {
                if (seen.contains(entry.getKey()) || entry.getValue().previous == null || !entry.getValue().route.isEmpty()) {
                    continue;
                }
                VirtualGhast previous = nextGhasts.get(entry.getValue().previous);
                if (previous != null && previous.dimension.identifier().equals(entry.getValue().dimension.identifier())) {
                    VirtualGhast followed = entry.getValue().follow(previous);
                    if (!followed.equals(entry.getValue())) {
                        entry.setValue(followed);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            data.ghasts.clear();
            data.ghasts.putAll(nextGhasts);
            data.setDirty();
        }
    }

    private static MinecraftServer SERVER;

    private static MinecraftServer stateServer() {
        if (SERVER == null) {
            throw new IllegalStateException("Virtual ghast tracker accessed before server tick");
        }
        return SERVER;
    }

    private static Data data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    private static boolean sameDimension(HappyGhast ghast, VirtualGhast virtual) {
        return ghast.level().dimension().identifier().equals(virtual.dimension.identifier());
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

    static final class Data extends SavedData {
        static final Codec<Data> CODEC = CompoundTag.CODEC.xmap(Data::fromTag, Data::toTag);
        final Map<UUID, VirtualGhast> ghasts = new HashMap<>();

        static Data fromTag(CompoundTag tag) {
            Data data = new Data();
            for (net.minecraft.nbt.Tag raw : tag.getListOrEmpty("ghasts")) {
                raw.asCompound().ifPresent(compound -> {
                    UUID id = VirtualGhast.readUuid(compound, "uuid");
                    if (id != null) {
                        data.ghasts.put(id, VirtualGhast.fromTag(compound));
                    }
                });
            }
            return data;
        }

        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            ghasts.forEach((id, ghast) -> {
                CompoundTag child = ghast.toTag();
                child.putString("uuid", id.toString());
                list.add(child);
            });
            tag.put("ghasts", list);
            return tag;
        }
    }

    private record VirtualGhast(
        ResourceKey<Level> dimension,
        Vec3 position,
        double bottomOffset,
        double speed,
        float yaw,
        UUID previous,
        UUID next,
        UUID owner,
        String groupName,
        String missingStationNotified,
        int index,
        int waitTicks,
        int pauseTicks,
        boolean docked,
        AutopilotPhase phase,
        int phaseTicks,
        int passengerCount,
        boolean unloaded,
        boolean loop,
        List<RouteInstruction> route
    ) {
        static VirtualGhast fromTag(CompoundTag tag) {
            ResourceKey<Level> dimension = parseDimension(tag.getStringOr("dimension", Level.OVERWORLD.identifier().toString()));
            Vec3 position = finitePosition(new Vec3(tag.getDoubleOr("x", 0.0), tag.getDoubleOr("y", 0.0), tag.getDoubleOr("z", 0.0)));
            double bottomOffset = sanitizeBottomOffset(tag.getDoubleOr("bottom_offset", 0.0));
            double speed = sanitizeSpeed(tag.getDoubleOr("speed", GhastAutopilot.autopilotCruiseSpeed()));
            float yaw = finiteYaw(tag.getFloatOr("yaw", 0.0F));
            UUID previous = readUuid(tag, "previous");
            UUID next = readUuid(tag, "next");
            UUID owner = readUuid(tag, "owner");
            String groupName = FsdTaskNotifier.sanitizeGroupName(tag.getStringOr("group_name", ""));
            String missingStationNotified = tag.getStringOr("missing_station_notified", "");
            int index = Math.max(0, tag.getIntOr("index", 0));
            int waitTicks = clamp(tag.getIntOr("wait_ticks", 0), 0, MAX_STORED_WAIT_TICKS);
            int pauseTicks = clamp(tag.getIntOr("pause_ticks", 0), 0, MAX_STORED_PAUSE_TICKS);
            boolean docked = tag.getBooleanOr("docked", false);
            AutopilotPhase phase = AutopilotPhase.parse(tag.getStringOr("phase", AutopilotPhase.CRUISE.name()));
            int phaseTicks = clamp(tag.getIntOr("phase_ticks", 0), 0, MAX_STORED_PAUSE_TICKS);
            int passengerCount = clamp(tag.getIntOr("passenger_count", 0), 0, MAX_PASSENGER_COUNT);
            boolean unloaded = tag.getBooleanOr("unloaded", !tag.getBooleanOr("loaded", false));
            boolean loop = tag.getBooleanOr("loop", true);
            java.util.ArrayList<RouteInstruction> route = new java.util.ArrayList<>();
            for (net.minecraft.nbt.Tag raw : tag.getListOrEmpty("route")) {
                if (route.size() >= RouteData.MAX_ROUTE_INSTRUCTIONS) {
                    break;
                }
                raw.asCompound().ifPresent(cmd -> {
                    String type = cmd.getStringOr("type", "");
                    String stationName = sanitizedName(cmd.getStringOr("station", cmd.getStringOr("label", "")));
                    if (!"fly_to_station".equals(type) || stationName.isBlank()) {
                        return;
                    }
                    route.add(new RouteInstruction(
                        type,
                        parseDimension(cmd.getStringOr("dimension", Level.OVERWORLD.identifier().toString())),
                        new BlockPos(cmd.getIntOr("x", cmd.getIntOr("px", 0)), cmd.getIntOr("y", cmd.getIntOr("py", 0)), cmd.getIntOr("z", cmd.getIntOr("pz", 0))),
                        stationName,
                        sanitizeDepartureCondition(cmd.getStringOr("condition", "wait_seconds")),
                        clamp(cmd.getIntOr("wait_seconds", 0), 0, 3600),
                        clamp(cmd.getIntOr("passengers", 1), 1, MAX_PASSENGER_COUNT),
                        finiteDouble(cmd.getDoubleOr("value", 0.0), 0.0),
                        sanitizedName(cmd.getStringOr("label", stationName))
                    ));
                });
            }
            return new VirtualGhast(dimension, position, bottomOffset, speed, yaw, previous, next, owner, groupName, missingStationNotified, index, waitTicks, pauseTicks, docked, phase, phaseTicks, passengerCount, unloaded, loop, List.copyOf(route));
        }

        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", dimension.identifier().toString());
            tag.putDouble("x", position.x);
            tag.putDouble("y", position.y);
            tag.putDouble("z", position.z);
            tag.putDouble("bottom_offset", bottomOffset);
            tag.putDouble("speed", speed);
            tag.putFloat("yaw", yaw);
            if (previous != null) {
                tag.putString("previous", previous.toString());
            }
            if (next != null) {
                tag.putString("next", next.toString());
            }
            if (owner != null) {
                tag.putString("owner", owner.toString());
            }
            if (!groupName.isBlank()) {
                tag.putString("group_name", groupName);
            }
            if (!missingStationNotified.isBlank()) {
                tag.putString("missing_station_notified", missingStationNotified);
            }
            tag.putInt("index", index);
            tag.putInt("wait_ticks", waitTicks);
            tag.putInt("pause_ticks", pauseTicks);
            tag.putBoolean("docked", docked);
            tag.putString("phase", phase.name());
            tag.putInt("phase_ticks", phaseTicks);
            tag.putInt("passenger_count", passengerCount);
            tag.putBoolean("unloaded", unloaded);
            tag.putBoolean("loop", loop);
            ListTag routeTag = new ListTag();
            for (RouteInstruction instruction : route) {
                CompoundTag cmd = new CompoundTag();
                cmd.putString("type", instruction.type());
                cmd.putString("dimension", instruction.dimension().identifier().toString());
                cmd.putInt("x", instruction.pos().getX());
                cmd.putInt("y", instruction.pos().getY());
                cmd.putInt("z", instruction.pos().getZ());
                cmd.putString("station", instruction.stationName());
                cmd.putString("condition", instruction.departureCondition());
                cmd.putInt("wait_seconds", instruction.waitSeconds());
                cmd.putInt("passengers", instruction.passengerCount());
                cmd.putDouble("value", instruction.value());
                cmd.putString("label", instruction.label());
                routeTag.add(cmd);
            }
            tag.put("route", routeTag);
            return tag;
        }

        VirtualGhast tick(MinecraftServer server) {
            if (route.isEmpty()) {
                return unloaded ? this : copy(position, yaw, index, waitTicks, pauseTicks, docked, phase, phaseTicks, passengerCount, true, missingStationNotified);
            }
            if (pauseTicks > 0) {
                return copy(position, yaw, index, waitTicks, pauseTicks - 1, docked, phase, phaseTicks, passengerCount, true, missingStationNotified);
            }
            int nextIndex = Math.floorMod(index, route.size());
            int nextWait = waitTicks;
            boolean nextDocked = docked;
            Vec3 nextPos = position;
            float nextYaw = yaw;
            String nextMissingStationNotified = missingStationNotified;
            RouteInstruction instruction = route.get(nextIndex);
            ServerLevel level = server.getLevel(dimension);
            GhastStationData.StationRef stationRef = level == null || !instruction.matchesDimension(dimension)
                ? null
                : GhastStationData.get(level).findIn(level, instruction.stationName()).orElse(null);
            if (stationRef != null) {
                if (owner != null && !missingStationNotified.isBlank()) {
                    FsdTaskNotifier.notifyResumedAt(server, owner, FsdTaskNotifier.notificationName(groupName), position);
                    nextMissingStationNotified = "";
                }
                Vec3 target = targetFor(stationRef, bottomOffset);
                Vec3 delta = target.subtract(position);
                if (delta.lengthSqr() <= 9.0) {
                    nextYaw = stationRef.direction().toYRot();
                    nextDocked = true;
                    nextWait++;
                    if (!"fly_to_station".equals(instruction.type()) || departureSatisfied(level, stationRef, instruction, nextWait, passengerCount)) {
                        nextIndex = advance(nextIndex, route.size(), loop);
                        nextWait = 0;
                        nextDocked = false;
                    }
                } else {
                    nextPos = position.add(delta.normalize().scale(Math.min(speed, Math.sqrt(delta.lengthSqr()))));
                    nextYaw = yawFrom(delta);
                    nextWait = 0;
                    nextDocked = false;
                }
            } else if (owner != null && !instruction.stationName().equals(missingStationNotified)) {
                FsdTaskNotifier.notifyStoppedAt(server, owner, FsdTaskNotifier.notificationName(groupName), position);
                nextMissingStationNotified = instruction.stationName();
            }
            return copy(nextPos, nextYaw, nextIndex, nextWait, 0, nextDocked, phase, phaseTicks + 1, passengerCount, true, nextMissingStationNotified);
        }

        VirtualGhast follow(VirtualGhast previousGhast) {
            Vec3 forward = horizontalForward(previousGhast.yaw);
            Vec3 nextPos = previousGhast.position.subtract(forward.scale(VIRTUAL_COUPLING_SPACING));
            return copy(nextPos, previousGhast.yaw, index, waitTicks, pauseTicks, docked, phase, phaseTicks, passengerCount, unloaded, missingStationNotified);
        }

        private VirtualGhast copy(Vec3 nextPosition, float nextYaw, int nextIndex, int nextWaitTicks, int nextPauseTicks, boolean nextDocked, AutopilotPhase nextPhase, int nextPhaseTicks, int nextPassengerCount, boolean nextUnloaded, String nextMissingStationNotified) {
            return new VirtualGhast(dimension, nextPosition, bottomOffset, speed, nextYaw, previous, next, owner, groupName, nextMissingStationNotified, nextIndex, nextWaitTicks, nextPauseTicks, nextDocked, nextPhase, nextPhaseTicks, nextPassengerCount, nextUnloaded, loop, route);
        }

        private static Vec3 targetFor(GhastStationData.StationRef stationRef, double bottomOffset) {
            BlockPos pos = stationRef.pos();
            double y = pos.getY() + stationRef.dockingHeight() + bottomOffset;
            return new Vec3(pos.getX() + 0.5, y, pos.getZ() + 0.5);
        }

        private static Vec3 horizontalForward(float yaw) {
            double radians = Math.toRadians(yaw);
            return new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));
        }

        private static boolean departureSatisfied(ServerLevel level, GhastStationData.StationRef stationRef, RouteInstruction instruction, int elapsedTicks, int passengerCount) {
            return switch (instruction.departureCondition()) {
                case "wait_for_passengers" -> passengerCount >= instruction.passengerCount();
                case "wait_for_redstone_on" -> redstonePowered(level, stationRef);
                case "wait_for_redstone_off" -> !redstonePowered(level, stationRef);
                case "wait_seconds" -> elapsedTicks >= Math.max(0, instruction.waitSeconds()) * 20;
                default -> elapsedTicks >= 100;
            };
        }

        private static boolean redstonePowered(ServerLevel level, GhastStationData.StationRef stationRef) {
            return level != null && GhastStationBlock.isRedstonePowered(level, stationRef.pos());
        }

        private static int advance(int currentIndex, int routeSize, boolean loop) {
            if (routeSize <= 0) {
                return 0;
            }
            if (currentIndex >= routeSize - 1) {
                return loop ? 0 : routeSize - 1;
            }
            return currentIndex + 1;
        }

        private static float yawFrom(Vec3 movement) {
            return (float)(Math.atan2(movement.z, movement.x) * 180.0 / Math.PI) - 90.0F;
        }

        private static UUID readUuid(CompoundTag tag, String key) {
            String value = tag.getStringOr(key, "");
            if (value.isEmpty()) {
                return null;
            }
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }

    private static ResourceKey<Level> parseDimension(String dimensionId) {
        try {
            return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(dimensionId));
        } catch (RuntimeException exception) {
            return Level.OVERWORLD;
        }
    }

    private static Vec3 finitePosition(Vec3 position) {
        return new Vec3(
            finiteCoordinate(position.x),
            finiteCoordinate(position.y),
            finiteCoordinate(position.z)
        );
    }

    private static double finiteCoordinate(double value) {
        return Math.max(-MAX_COORDINATE, Math.min(MAX_COORDINATE, finiteDouble(value, 0.0)));
    }

    private static double sanitizeBottomOffset(double value) {
        return Math.min(MAX_BOTTOM_OFFSET, Math.max(0.0, finiteDouble(value, 0.0)));
    }

    private static double sanitizeSpeed(double value) {
        return Math.max(MIN_STORED_SPEED, Math.min(MAX_STORED_SPEED, finiteDouble(value, GhastAutopilot.autopilotCruiseSpeed())));
    }

    private static float finiteYaw(float yaw) {
        return Float.isFinite(yaw) ? yaw : 0.0F;
    }

    private static double finiteDouble(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static String sanitizedName(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > 48 ? trimmed.substring(0, 48) : trimmed;
    }

    private static String sanitizeDepartureCondition(String condition) {
        return switch (condition) {
            case "wait_for_passengers", "wait_for_redstone_on", "wait_for_redstone_off", "wait_seconds" -> condition;
            default -> "wait_seconds";
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
