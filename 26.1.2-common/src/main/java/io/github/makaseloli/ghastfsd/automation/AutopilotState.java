package io.github.makaseloli.ghastfsd.automation;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.item.component.CustomData;

public final class AutopilotState {
    private static final String ROOT = "ghastfsd_autopilot";
    private static final int MAX_STORED_WAIT_TICKS = 20 * 60 * 60;
    private static final int MAX_STORED_PAUSE_TICKS = 20 * 60;

    public int index;
    int waitTicks;
    int pauseTicks;
    boolean docked;
    AutopilotPhase phase = AutopilotPhase.CRUISE;
    int phaseTicks;

    public static AutopilotState read(HappyGhast ghast) {
        AutopilotState state = new AutopilotState();
        CustomData data = ghast.get(DataComponents.CUSTOM_DATA);
        CompoundTag root = data == null ? new CompoundTag() : data.copyTag().getCompoundOrEmpty(ROOT);
        state.index = Math.max(0, root.getIntOr("index", 0));
        state.waitTicks = clamp(root.getIntOr("wait_ticks", 0), 0, MAX_STORED_WAIT_TICKS);
        state.pauseTicks = clamp(root.getIntOr("pause_ticks", 0), 0, MAX_STORED_PAUSE_TICKS);
        state.docked = root.getBooleanOr("docked", false);
        state.phase = AutopilotPhase.parse(root.getStringOr("phase", AutopilotPhase.CRUISE.name()));
        state.phaseTicks = clamp(root.getIntOr("phase_ticks", 0), 0, MAX_STORED_PAUSE_TICKS);
        return state;
    }

    public static void reset(HappyGhast ghast, int index) {
        AutopilotState state = new AutopilotState();
        state.index = Math.max(0, index);
        state.write(ghast);
    }

    void write(HappyGhast ghast) {
        index = Math.max(0, index);
        waitTicks = clamp(waitTicks, 0, MAX_STORED_WAIT_TICKS);
        pauseTicks = clamp(pauseTicks, 0, MAX_STORED_PAUSE_TICKS);
        phaseTicks = clamp(phaseTicks, 0, MAX_STORED_PAUSE_TICKS);
        CustomData current = ghast.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = current == null ? new CompoundTag() : current.copyTag();
        CompoundTag root = new CompoundTag();
        root.putInt("index", index);
        root.putInt("wait_ticks", waitTicks);
        root.putInt("pause_ticks", pauseTicks);
        root.putBoolean("docked", docked);
        root.putString("phase", phase.name());
        root.putInt("phase_ticks", phaseTicks);
        tag.put(ROOT, root);
        ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    void transitionTo(AutopilotPhase nextPhase) {
        if (phase != nextPhase) {
            phase = nextPhase;
            phaseTicks = 0;
        }
    }

    void resetNavigation() {
        docked = false;
        phase = AutopilotPhase.CRUISE;
        phaseTicks = 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
