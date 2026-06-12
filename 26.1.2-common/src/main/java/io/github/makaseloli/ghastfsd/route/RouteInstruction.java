package io.github.makaseloli.ghastfsd.route;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record RouteInstruction(
    String type,
    ResourceKey<Level> dimension,
    BlockPos pos,
    String stationName,
    String departureCondition,
    int waitSeconds,
    int passengerCount,
    double value,
    String label
) {
    public static RouteInstruction station(String stationName, String condition, int waitSeconds, int passengerCount) {
        return new RouteInstruction("fly_to_station", Level.OVERWORLD, BlockPos.ZERO, stationName, condition, waitSeconds, passengerCount, 0.0, stationName);
    }

    public static RouteInstruction migratedStation(ResourceKey<Level> dimension, BlockPos pos, String stationName, String condition, int waitSeconds, int passengerCount, String label) {
        return new RouteInstruction("fly_to_station", dimension, pos, stationName, condition, waitSeconds, passengerCount, 0.0, label);
    }

    public boolean matchesDimension(ResourceKey<Level> current) {
        return dimension.identifier().equals(current.identifier());
    }
}
