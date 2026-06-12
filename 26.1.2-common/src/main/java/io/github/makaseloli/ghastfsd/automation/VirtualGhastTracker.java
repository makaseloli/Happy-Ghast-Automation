package io.github.makaseloli.ghastfsd.automation;

import io.github.makaseloli.ghastfsd.content.GhastStationData;
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

    static void syncLoaded(UUID id, ResourceKey<Level> dimension, Vec3 position, AutopilotState state, List<RouteInstruction> route) {
        Data data = data(stateServer());
        VirtualGhast virtual = data.ghasts.remove(id);
        if (virtual != null && virtual.dimension.identifier().equals(dimension.identifier())) {
            state.index = virtual.index;
            state.waitTicks = virtual.waitTicks;
        }
        data.ghasts.put(id, new VirtualGhast(dimension, position, state.index, state.waitTicks, route));
        data.setDirty();
    }

    static Vec3 virtualPosition(UUID id) {
        VirtualGhast virtual = data(stateServer()).ghasts.get(id);
        return virtual == null ? null : virtual.position;
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
        boolean changed = false;
        for (Map.Entry<UUID, VirtualGhast> entry : data.ghasts.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                entry.setValue(entry.getValue().tick(server));
                changed = true;
            }
        }
        if (changed) {
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
        int index,
        int waitTicks,
        List<RouteInstruction> route
    ) {
        static VirtualGhast fromTag(CompoundTag tag) {
            ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(tag.getStringOr("dimension", Level.OVERWORLD.identifier().toString())));
            Vec3 position = new Vec3(tag.getDoubleOr("x", 0.0), tag.getDoubleOr("y", 0.0), tag.getDoubleOr("z", 0.0));
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
            return new VirtualGhast(dimension, position, index, waitTicks, List.copyOf(route));
        }

        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", dimension.identifier().toString());
            tag.putDouble("x", position.x);
            tag.putDouble("y", position.y);
            tag.putDouble("z", position.z);
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
            RouteInstruction instruction = route.get(nextIndex);
            ServerLevel level = server.getLevel(dimension);
            GhastStationData.StationRef stationRef = level == null ? null : GhastStationData.get(level).findIn(level, instruction.stationName()).orElse(null);
            if (stationRef != null) {
                Vec3 target = targetFor(stationRef);
                Vec3 delta = target.subtract(position);
                if (delta.lengthSqr() <= 9.0) {
                    nextWait++;
                    if (!"fly_to_station".equals(instruction.type()) || nextWait >= Math.max(0, instruction.waitSeconds()) * 20) {
                        nextIndex = (nextIndex + 1) % route.size();
                        nextWait = 0;
                    }
                } else {
                    double speed = GhastAutopilot.autopilotCruiseSpeed();
                    nextPos = position.add(delta.normalize().scale(Math.min(speed, Math.sqrt(delta.lengthSqr()))));
                    nextWait = 0;
                }
            }
            return new VirtualGhast(dimension, nextPos, nextIndex, nextWait, route);
        }

        private static Vec3 targetFor(GhastStationData.StationRef stationRef) {
            BlockPos pos = stationRef.pos();
            double y = pos.getY() + stationRef.dockingHeight();
            return new Vec3(pos.getX() + 0.5, y, pos.getZ() + 0.5);
        }
    }
}
