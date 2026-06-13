package io.github.makaseloli.ghastfsd.content;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class FsdTaskAttachment {
    private static final String ROOT = "ghastfsd_fsd_task";
    private static final String STACK_DATA = "stack_data";

    private FsdTaskAttachment() {}

    public static boolean hasTask(HappyGhast ghast) {
        if (ghast instanceof GhastFsdTaskCarrier carrier && carrier.ghastfsd$hasSyncedTask()) {
            return true;
        }
        CustomData data = ghast.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(ROOT);
    }

    public static ItemStack getTask(HappyGhast ghast) {
        CustomData data = ghast.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return ItemStack.EMPTY;
        }
        CompoundTag root = data.copyTag().getCompoundOrEmpty(ROOT);
        if (root.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack task = new ItemStack(GhastFsdContent.FSD_TASK);
        CompoundTag stackData = root.getCompoundOrEmpty(STACK_DATA);
        if (!stackData.isEmpty()) {
            task.set(DataComponents.CUSTOM_DATA, CustomData.of(stackData));
        }
        return task;
    }

    public static void setTask(HappyGhast ghast, ItemStack task) {
        CustomData current = ghast.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = current == null ? new CompoundTag() : current.copyTag();
        CompoundTag root = new CompoundTag();
        CustomData stackData = task.get(DataComponents.CUSTOM_DATA);
        if (stackData != null && !stackData.isEmpty()) {
            root.put(STACK_DATA, stackData.copyTag());
        }
        tag.put(ROOT, root);
        ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        syncTaskFlag(ghast, true);
    }

    public static ItemStack removeTask(HappyGhast ghast) {
        ItemStack task = getTask(ghast);
        CustomData current = ghast.get(DataComponents.CUSTOM_DATA);
        if (current != null) {
            CompoundTag tag = current.copyTag();
            tag.remove(ROOT);
            ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        syncTaskFlag(ghast, false);
        return task;
    }

    public static void syncTaskFlag(HappyGhast ghast, boolean hasTask) {
        if (ghast instanceof GhastFsdTaskCarrier carrier && carrier.ghastfsd$hasSyncedTask() != hasTask) {
            carrier.ghastfsd$setSyncedTask(hasTask);
        }
    }
}
