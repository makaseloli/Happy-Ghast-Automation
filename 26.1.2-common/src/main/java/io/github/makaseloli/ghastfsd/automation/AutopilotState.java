package io.github.makaseloli.ghastfsd.automation;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.item.component.CustomData;

public final class AutopilotState {
    private static final String ROOT = "ghastfsd_autopilot";

    public int index;
    int waitTicks;
    int pauseTicks;
    boolean docked;

    public static AutopilotState read(HappyGhast ghast) {
        AutopilotState state = new AutopilotState();
        CustomData data = ghast.get(DataComponents.CUSTOM_DATA);
        CompoundTag root = data == null ? new CompoundTag() : data.copyTag().getCompoundOrEmpty(ROOT);
        state.index = root.getIntOr("index", 0);
        state.waitTicks = root.getIntOr("wait_ticks", 0);
        state.pauseTicks = root.getIntOr("pause_ticks", 0);
        state.docked = root.getBooleanOr("docked", false);
        return state;
    }

    public static void reset(HappyGhast ghast, int index) {
        AutopilotState state = new AutopilotState();
        state.index = Math.max(0, index);
        state.write(ghast);
    }

    void write(HappyGhast ghast) {
        CustomData current = ghast.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = current == null ? new CompoundTag() : current.copyTag();
        CompoundTag root = new CompoundTag();
        root.putInt("index", index);
        root.putInt("wait_ticks", waitTicks);
        root.putInt("pause_ticks", pauseTicks);
        root.putBoolean("docked", docked);
        tag.put(ROOT, root);
        ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
}
