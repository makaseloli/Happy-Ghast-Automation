package io.github.makaseloli.ghastfsd.content;

import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import java.util.function.Consumer;

public class GhastCouplingLeadItem extends Item {
    private static final String SELECTED_GHAST = "selected_ghast";

    public GhastCouplingLeadItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (target instanceof HappyGhast ghast) {
            return useOnHappyGhast(stack, player, ghast);
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult useOnHappyGhast(ItemStack stack, Player player, HappyGhast target) {
        if (stack.getItem() != GhastFsdContent.GHAST_COUPLING_LEAD) {
            return InteractionResult.PASS;
        }
        if (!(target.level() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        if (!GhastCouplingAttachment.canCouple(target)) {
            player.sendOverlayMessage(Component.translatable("error.ghastfsd.coupling_invalid"));
            return InteractionResult.FAIL;
        }
        UUID selectedId = selected(stack);
        if (selectedId == null || selectedId.equals(target.getUUID())) {
            select(stack, target);
            player.sendOverlayMessage(Component.translatable("message.ghastfsd.coupling_selected"));
            return InteractionResult.SUCCESS;
        }
        Entity selectedEntity = level.getEntity(selectedId);
        if (!(selectedEntity instanceof HappyGhast selected) || !GhastCouplingAttachment.canCouple(selected)) {
            select(stack, target);
            player.sendOverlayMessage(Component.translatable("message.ghastfsd.coupling_selected"));
            return InteractionResult.SUCCESS;
        }
        if (!GhastCouplingAttachment.link(level, selected, target)) {
            player.sendOverlayMessage(Component.translatable("error.ghastfsd.coupling_failed"));
            return InteractionResult.FAIL;
        }
        clearSelection(stack);
        player.sendOverlayMessage(Component.translatable("message.ghastfsd.coupling_linked", GhastCouplingAttachment.chainLength(level, selected)));
        return InteractionResult.SUCCESS;
    }

    public static InteractionResult cutWithShears(ItemStack stack, Player player, HappyGhast target) {
        if (stack.getItem() != Items.SHEARS) {
            return InteractionResult.PASS;
        }
        if (target.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!GhastCouplingAttachment.cutAt(target)) {
            return InteractionResult.PASS;
        }
        player.sendOverlayMessage(Component.translatable("message.ghastfsd.coupling_cut"));
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && selected(stack) != null) {
            clearSelection(stack);
            player.sendOverlayMessage(Component.translatable("message.ghastfsd.coupling_cleared"));
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.ghastfsd.coupling_lead").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.ghastfsd.coupling_lead_shears").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static UUID selected(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        String value = data.copyTag().getStringOr(SELECTED_GHAST, "");
        if (value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void select(ItemStack stack, HappyGhast ghast) {
        CustomData current = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = current == null ? new CompoundTag() : current.copyTag();
        tag.putString(SELECTED_GHAST, ghast.getUUID().toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void clearSelection(ItemStack stack) {
        CustomData current = stack.get(DataComponents.CUSTOM_DATA);
        if (current == null) {
            return;
        }
        CompoundTag tag = current.copyTag();
        tag.remove(SELECTED_GHAST);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
}
