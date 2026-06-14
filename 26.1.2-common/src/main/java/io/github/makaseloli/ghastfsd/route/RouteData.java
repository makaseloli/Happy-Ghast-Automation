package io.github.makaseloli.ghastfsd.route;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

public final class RouteData {
    public static final int MAX_ROUTE_INSTRUCTIONS = 64;
    private static final String ROOT = "ghastfsd";
    private static final String COUNT = "count";
    private static final int MAX_STATION_NAME_LENGTH = 48;
    private static final int MIN_WAIT_SECONDS = 0;
    private static final int MAX_WAIT_SECONDS = 3600;
    private static final int MIN_PASSENGER_COUNT = 1;
    private static final int MAX_PASSENGER_COUNT = 4;

    private RouteData() {}

    public static int count(ItemStack stack) {
        return routeRoot(stack).getIntOr(COUNT, 0);
    }

    public static int focus(ItemStack stack) {
        return Math.max(0, routeRoot(stack).getIntOr("focus", 0));
    }

    public static boolean loop(ItemStack stack) {
        return routeRoot(stack).getBooleanOr("loop", true);
    }

    public static void setFocus(ItemStack stack, int focus) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag root = tag.getCompoundOrEmpty(ROOT);
            root.putInt("focus", Math.max(0, focus));
            tag.put(ROOT, root);
        });
    }

    public static List<RouteInstruction> read(ItemStack stack) {
        return readRouteRoot(routeRoot(stack));
    }

    public static List<RouteInstruction> readRouteRoot(CompoundTag root) {
        int count = Math.min(MAX_ROUTE_INSTRUCTIONS, Math.max(0, root.getIntOr(COUNT, 0)));
        List<RouteInstruction> route = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CompoundTag tag = root.getCompoundOrEmpty("cmd" + i);
            String type = tag.getStringOr("type", "");
            if (!"fly_to_station".equals(type)) {
                continue;
            }
            ResourceKey<Level> dimension = parseDimension(tag.getStringOr("dimension", Level.OVERWORLD.identifier().toString()));
            BlockPos pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
            String stationName = sanitizeName(tag.getStringOr("station", tag.getStringOr("label", "")));
            route.add(new RouteInstruction(
                type,
                dimension,
                pos,
                stationName,
                sanitizeDepartureCondition(tag.getStringOr("condition", "wait_seconds")),
                clamp(tag.getIntOr("wait_seconds", 5), MIN_WAIT_SECONDS, MAX_WAIT_SECONDS),
                clamp(tag.getIntOr("passengers", 1), MIN_PASSENGER_COUNT, MAX_PASSENGER_COUNT),
                tag.getDoubleOr("value", 0.35),
                sanitizeName(tag.getStringOr("label", stationName))
            ));
        }
        return route;
    }

    public static CompoundTag sanitizeRouteRoot(CompoundTag root) {
        if (root == null) {
            return new CompoundTag();
        }
        return writeRoute(readRouteRoot(root), root.getBooleanOr("loop", true), Math.max(0, root.getIntOr("focus", 0)));
    }

    public static CompoundTag copyRouteRoot(ItemStack stack) {
        return routeRoot(stack).copy();
    }

    public static CompoundTag writeRoute(List<RouteInstruction> route, boolean loop, int focus) {
        CompoundTag root = new CompoundTag();
        int index = 0;
        for (RouteInstruction instruction : route) {
            if (index >= MAX_ROUTE_INSTRUCTIONS) {
                break;
            }
            CompoundTag cmd = new CompoundTag();
            cmd.putString("type", instruction.type());
            cmd.putString("dimension", instruction.dimension().identifier().toString());
            cmd.putInt("x", instruction.pos().getX());
            cmd.putInt("y", instruction.pos().getY());
            cmd.putInt("z", instruction.pos().getZ());
            cmd.putString("station", sanitizeName(instruction.stationName()));
            cmd.putString("condition", sanitizeDepartureCondition(instruction.departureCondition()));
            cmd.putInt("wait_seconds", clamp(instruction.waitSeconds(), MIN_WAIT_SECONDS, MAX_WAIT_SECONDS));
            cmd.putInt("passengers", clamp(instruction.passengerCount(), MIN_PASSENGER_COUNT, MAX_PASSENGER_COUNT));
            cmd.putDouble("value", instruction.value());
            cmd.putString("label", sanitizeName(instruction.label()));
            root.put("cmd" + index, cmd);
            index++;
        }
        root.putInt(COUNT, index);
        root.putBoolean("loop", loop);
        root.putInt("focus", Math.max(0, Math.min(Math.max(0, index - 1), focus)));
        return root;
    }

    public static void replaceRouteRoot(ItemStack stack, CompoundTag root) {
        CompoundTag sanitized = sanitizeRouteRoot(root);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (sanitized.getIntOr(COUNT, 0) <= 0) {
                tag.remove(ROOT);
            } else {
                tag.put(ROOT, sanitized);
            }
        });
    }

    public static void clear(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(ROOT));
    }

    private static CompoundTag routeRoot(ItemStack stack) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return data.copyTag().getCompoundOrEmpty(ROOT);
    }

    private static ResourceKey<Level> parseDimension(String dimensionId) {
        try {
            return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(dimensionId));
        } catch (RuntimeException exception) {
            return Level.OVERWORLD;
        }
    }

    private static String sanitizeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.length() > MAX_STATION_NAME_LENGTH ? trimmed.substring(0, MAX_STATION_NAME_LENGTH) : trimmed;
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
