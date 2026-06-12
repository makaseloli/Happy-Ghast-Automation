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
    private static final String ROOT = "ghastfsd";
    private static final String COUNT = "count";

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
        int count = Math.max(0, root.getIntOr(COUNT, 0));
        List<RouteInstruction> route = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CompoundTag tag = root.getCompoundOrEmpty("cmd" + i);
            String type = tag.getStringOr("type", "");
            if (!"fly_to_station".equals(type)) {
                continue;
            }
            ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(tag.getStringOr("dimension", Level.OVERWORLD.identifier().toString())));
            BlockPos pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
            String stationName = tag.getStringOr("station", tag.getStringOr("label", ""));
            route.add(new RouteInstruction(
                type,
                dimension,
                pos,
                stationName,
                tag.getStringOr("condition", "wait_seconds"),
                tag.getIntOr("wait_seconds", 5),
                tag.getIntOr("passengers", 1),
                tag.getDoubleOr("value", 0.35),
                tag.getStringOr("label", stationName)
            ));
        }
        return route;
    }

    public static CompoundTag copyRouteRoot(ItemStack stack) {
        return routeRoot(stack).copy();
    }

    public static CompoundTag writeRoute(List<RouteInstruction> route, boolean loop, int focus) {
        CompoundTag root = new CompoundTag();
        int index = 0;
        for (RouteInstruction instruction : route) {
            CompoundTag cmd = new CompoundTag();
            cmd.putString("type", instruction.type());
            cmd.putString("dimension", instruction.dimension().identifier().toString());
            cmd.putInt("x", 0);
            cmd.putInt("y", 0);
            cmd.putInt("z", 0);
            cmd.putString("station", instruction.stationName());
            cmd.putString("condition", instruction.departureCondition());
            cmd.putInt("wait_seconds", instruction.waitSeconds());
            cmd.putInt("passengers", instruction.passengerCount());
            cmd.putDouble("value", instruction.value());
            cmd.putString("label", instruction.label());
            root.put("cmd" + index, cmd);
            index++;
        }
        root.putInt(COUNT, index);
        root.putBoolean("loop", loop);
        root.putInt("focus", Math.max(0, Math.min(Math.max(0, index - 1), focus)));
        return root;
    }

    public static void replaceRouteRoot(ItemStack stack, CompoundTag root) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (root.getIntOr(COUNT, 0) <= 0) {
                tag.remove(ROOT);
            } else {
                tag.put(ROOT, root.copy());
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
}
