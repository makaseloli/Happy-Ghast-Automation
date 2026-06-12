package io.github.makaseloli.ghastfsd.automation;

import io.github.makaseloli.ghastfsd.content.GhastStationData;
import io.github.makaseloli.ghastfsd.content.FsdTaskNotifier;
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
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

final class VirtualGhastTracker {
    private static final double VIRTUAL_COUPLING_SPACING = 5.5;
    private static final int MAX_COUPLED_MEMBERS = 6;

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

    static void syncLoaded(UUID id, ResourceKey<Level> dimension, Vec3 position, float yaw, UUID previous, UUID next, UUID owner, String groupName, AutopilotState state, List<RouteInstruction> route) {
        Data data = data(stateServer());
        VirtualGhast virtual = data.ghasts.remove(id);
        String missingStationNotified = "";
        String storedGroupName = FsdTaskNotifier.sanitizeGroupName(groupName);
        if (virtual != null && virtual.dimension.identifier().equals(dimension.identifier())) {
            state.index = virtual.index;
            state.waitTicks = virtual.waitTicks;
            missingStationNotified = virtual.missingStationNotified;
        }
        data.ghasts.put(id, new VirtualGhast(dimension, position, yaw, previous, next, owner, storedGroupName, missingStationNotified, state.index, state.waitTicks, route));
        data.setDirty();
    }

    static void syncCoupled(UUID id, ResourceKey<Level> dimension, Vec3 position, float yaw, UUID previous, UUID next) {
        Data data = data(stateServer());
        data.ghasts.put(id, new VirtualGhast(dimension, position, yaw, previous, next, null, "", "", 0, 0, List.of()));
        data.setDirty();
    }

    static Vec3 virtualPosition(UUID id) {
        VirtualGhast virtual = data(stateServer()).ghasts.get(id);
        return virtual == null ? null : virtual.position;
    }

    static Vec3 followVirtualCoupling(UUID id) {
        Data data = data(stateServer());
        VirtualGhast virtual = data.ghasts.get(id);
        if (virtual == null || virtual.previous == null || !virtual.route.isEmpty()) {
            return virtual == null ? null : virtual.position;
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
                entry.setValue(entry.getValue().tick(server));
                changed = true;
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

    static final class Data extends SavedData {
        static final Codec<Data> CODEC = CompoundTag.CODEC.xmap(Data::fromTag, Data::toTag);
        final Map<UUID, VirtualGhast> ghasts = new HashMap<>();

        static Data fromTag(CompoundTag tag) {
            Data data = new Data();
            for (net.minecraft.nbt.Tag raw : tag.getListOrEmpty("ghasts")) {
                raw.asCompound().ifPresent(compound -> {
                    UUID id = UUID.fromString(compound.getStringOr("uuid", new UUID(0L, 0L).toString()));
                    data.ghasts.put(id, VirtualGhast.fromTag(compound));
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
        float yaw,
        UUID previous,
        UUID next,
        UUID owner,
        String groupName,
        String missingStationNotified,
        int index,
        int waitTicks,
        List<RouteInstruction> route
    ) {
        static VirtualGhast fromTag(CompoundTag tag) {
            ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(tag.getStringOr("dimension", Level.OVERWORLD.identifier().toString())));
            Vec3 position = new Vec3(tag.getDoubleOr("x", 0.0), tag.getDoubleOr("y", 0.0), tag.getDoubleOr("z", 0.0));
            float yaw = tag.getFloatOr("yaw", 0.0F);
            UUID previous = readUuid(tag, "previous");
            UUID next = readUuid(tag, "next");
            UUID owner = readUuid(tag, "owner");
            String groupName = FsdTaskNotifier.sanitizeGroupName(tag.getStringOr("group_name", ""));
            String missingStationNotified = tag.getStringOr("missing_station_notified", "");
            int index = tag.getIntOr("index", 0);
            int waitTicks = tag.getIntOr("wait_ticks", 0);
            java.util.ArrayList<RouteInstruction> route = new java.util.ArrayList<>();
            for (net.minecraft.nbt.Tag raw : tag.getListOrEmpty("route")) {
                raw.asCompound().ifPresent(cmd -> route.add(new RouteInstruction(
                    cmd.getStringOr("type", ""),
                    ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(cmd.getStringOr("dimension", Level.OVERWORLD.identifier().toString()))),
                    new BlockPos(cmd.getIntOr("px", 0), cmd.getIntOr("py", 0), cmd.getIntOr("pz", 0)),
                    cmd.getStringOr("station", cmd.getStringOr("label", "")),
                    cmd.getStringOr("condition", ""),
                    cmd.getIntOr("wait_seconds", 0),
                    cmd.getIntOr("passengers", 0),
                    cmd.getDoubleOr("value", 0.0),
                    cmd.getStringOr("label", "")
                )));
            }
            return new VirtualGhast(dimension, position, yaw, previous, next, owner, groupName, missingStationNotified, index, waitTicks, List.copyOf(route));
        }

        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", dimension.identifier().toString());
            tag.putDouble("x", position.x);
            tag.putDouble("y", position.y);
            tag.putDouble("z", position.z);
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
            ListTag routeTag = new ListTag();
            for (RouteInstruction instruction : route) {
                CompoundTag cmd = new CompoundTag();
                cmd.putString("type", instruction.type());
                cmd.putString("dimension", instruction.dimension().identifier().toString());
                cmd.putInt("px", 0);
                cmd.putInt("py", 0);
                cmd.putInt("pz", 0);
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
                return this;
            }
            int nextIndex = Math.floorMod(index, route.size());
            int nextWait = waitTicks;
            Vec3 nextPos = position;
            float nextYaw = yaw;
            String nextMissingStationNotified = missingStationNotified;
            RouteInstruction instruction = route.get(nextIndex);
            ServerLevel level = server.getLevel(dimension);
            GhastStationData.StationRef stationRef = level == null ? null : GhastStationData.get(level).findIn(level, instruction.stationName()).orElse(null);
            if (stationRef != null) {
                if (owner != null && !missingStationNotified.isBlank()) {
                    FsdTaskNotifier.notifyResumedAt(server, owner, FsdTaskNotifier.notificationName(groupName), position);
                    nextMissingStationNotified = "";
                }
                Vec3 target = targetFor(stationRef);
                Vec3 delta = target.subtract(position);
                if (delta.lengthSqr() <= 9.0) {
                    nextYaw = stationRef.direction().toYRot();
                    nextWait++;
                    if (!"fly_to_station".equals(instruction.type()) || nextWait >= Math.max(0, instruction.waitSeconds()) * 20) {
                        nextIndex = (nextIndex + 1) % route.size();
                        nextWait = 0;
                    }
                } else {
                    double speed = GhastAutopilot.autopilotCruiseSpeed();
                    nextPos = position.add(delta.normalize().scale(Math.min(speed, Math.sqrt(delta.lengthSqr()))));
                    nextYaw = yawFrom(delta);
                    nextWait = 0;
                }
            } else if (owner != null && !instruction.stationName().equals(missingStationNotified)) {
                FsdTaskNotifier.notifyStoppedAt(server, owner, FsdTaskNotifier.notificationName(groupName), position);
                nextMissingStationNotified = instruction.stationName();
            }
            return new VirtualGhast(dimension, nextPos, nextYaw, previous, next, owner, groupName, nextMissingStationNotified, nextIndex, nextWait, route);
        }

        VirtualGhast follow(VirtualGhast previousGhast) {
            Vec3 forward = horizontalForward(previousGhast.yaw);
            Vec3 nextPos = previousGhast.position.subtract(forward.scale(VIRTUAL_COUPLING_SPACING));
            return new VirtualGhast(dimension, nextPos, previousGhast.yaw, previous, next, owner, groupName, missingStationNotified, index, waitTicks, route);
        }

        private static Vec3 targetFor(GhastStationData.StationRef stationRef) {
            BlockPos pos = stationRef.pos();
            double y = pos.getY() + stationRef.dockingHeight();
            return new Vec3(pos.getX() + 0.5, y, pos.getZ() + 0.5);
        }

        private static Vec3 horizontalForward(float yaw) {
            double radians = Math.toRadians(yaw);
            return new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));
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
}
