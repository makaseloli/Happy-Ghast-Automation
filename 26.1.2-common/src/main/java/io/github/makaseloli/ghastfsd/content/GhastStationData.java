package io.github.makaseloli.ghastfsd.content;

import com.mojang.serialization.Codec;
import io.github.makaseloli.ghastfsd.ModUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class GhastStationData extends SavedData {
    private static final Codec<GhastStationData> CODEC = CompoundTag.CODEC.xmap(GhastStationData::fromTag, GhastStationData::toTag);
    public static final SavedDataType<GhastStationData> TYPE = new SavedDataType<>(
        ModUtils.id("station_index"),
        GhastStationData::new,
        CODEC,
        DataFixTypes.SAVED_DATA_MAP_DATA
    );

    private final Map<String, StationRef> byName = new HashMap<>();
    private final Map<String, String> byPosition = new HashMap<>();

    public GhastStationData() {
    }

    public static GhastStationData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<StationRef> find(String name) {
        return Optional.ofNullable(byName.get(sanitizeName(name)));
    }

    public Optional<StationRef> findIn(ServerLevel level, String name) {
        Optional<StationRef> ref = find(name);
        return ref.filter(stationRef -> stationRef.dimension().identifier().equals(level.dimension().identifier()));
    }

    public List<StationRef> stations() {
        ArrayList<StationRef> stations = new ArrayList<>(byName.values());
        stations.sort(Comparator.comparing(StationRef::name, String.CASE_INSENSITIVE_ORDER));
        return stations;
    }

    public boolean canRename(ResourceKey<Level> dimension, BlockPos pos, String requestedName) {
        String name = sanitizeName(requestedName);
        if (name.isBlank()) {
            return true;
        }
        StationRef existing = byName.get(name);
        return existing == null || existing.samePlace(dimension, pos);
    }

    public void remove(ResourceKey<Level> dimension, BlockPos pos) {
        String positionKey = key(dimension, pos);
        String oldName = byPosition.remove(positionKey);
        if (oldName != null) {
            byName.remove(oldName);
            setDirty();
        }
    }

    public void update(ResourceKey<Level> dimension, BlockPos pos, String requestedName) {
        String positionKey = key(dimension, pos);
        String oldName = byPosition.remove(positionKey);
        if (oldName != null) {
            byName.remove(oldName);
        }
        String name = sanitizeName(requestedName);
        if (!name.isBlank()) {
            StationRef ref = new StationRef(name, dimension, pos, GhastStationBlockEntity.DEFAULT_DOCKING_HEIGHT, Direction.NORTH);
            byName.put(name, ref);
            byPosition.put(positionKey, name);
        }
        setDirty();
    }

    public void update(ResourceKey<Level> dimension, BlockPos pos, GhastStationBlockEntity station) {
        String positionKey = key(dimension, pos);
        String oldName = byPosition.remove(positionKey);
        if (oldName != null) {
            byName.remove(oldName);
        }
        String name = sanitizeName(station.stationName());
        if (!name.isBlank()) {
            StationRef ref = new StationRef(name, dimension, pos, station.dockingHeight(), station.stationDirection());
            byName.put(name, ref);
            byPosition.put(positionKey, name);
        }
        setDirty();
    }

    public String getIndexedName(ResourceKey<Level> dimension, BlockPos pos) {
        return byPosition.getOrDefault(key(dimension, pos), "");
    }

    public static String sanitizeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.length() > 48 ? trimmed.substring(0, 48) : trimmed;
    }

    private static GhastStationData fromTag(CompoundTag tag) {
        GhastStationData data = new GhastStationData();
        for (net.minecraft.nbt.Tag raw : tag.getListOrEmpty("stations")) {
            raw.asCompound().ifPresent(entry -> {
                String name = sanitizeName(entry.getStringOr("name", ""));
                String dimensionId = entry.getStringOr("dimension", Level.OVERWORLD.identifier().toString());
                BlockPos pos = new BlockPos(entry.getIntOr("x", 0), entry.getIntOr("y", 0), entry.getIntOr("z", 0));
                if (!name.isBlank()) {
                    ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(dimensionId));
                    int dockingHeight = GhastStationBlockEntity.clamp(
                        entry.getIntOr("docking_height", GhastStationBlockEntity.DEFAULT_DOCKING_HEIGHT),
                        GhastStationBlockEntity.MIN_DOCKING_HEIGHT,
                        GhastStationBlockEntity.MAX_DOCKING_HEIGHT
                    );
                    Direction direction = GhastStationBlockEntity.parseDirection(entry.getStringOr("station_direction", Direction.NORTH.getSerializedName()), Direction.NORTH);
                    data.byName.put(name, new StationRef(name, dimension, pos, dockingHeight, direction));
                    data.byPosition.put(key(dimension, pos), name);
                }
            });
        }
        return data;
    }

    private CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (StationRef ref : stations()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("name", ref.name());
            entry.putString("dimension", ref.dimension().identifier().toString());
            entry.putInt("x", ref.pos().getX());
            entry.putInt("y", ref.pos().getY());
            entry.putInt("z", ref.pos().getZ());
            entry.putInt("docking_height", ref.dockingHeight());
            entry.putString("station_direction", ref.direction().getSerializedName());
            list.add(entry);
        }
        tag.put("stations", list);
        return tag;
    }

    private static String key(ResourceKey<Level> dimension, BlockPos pos) {
        return dimension.identifier() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public record StationRef(String name, ResourceKey<Level> dimension, BlockPos pos, int dockingHeight, Direction direction) {
        boolean samePlace(ResourceKey<Level> otherDimension, BlockPos otherPos) {
            return dimension.identifier().equals(otherDimension.identifier()) && pos.equals(otherPos);
        }
    }
}
