package io.github.makaseloli.ghastfsd.content;

import io.github.makaseloli.ghastfsd.automation.GhastAutopilot;
import io.github.makaseloli.ghastfsd.route.RouteData;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

public class FsdTaskItem extends Item {
    public FsdTaskItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getVehicle() instanceof HappyGhast ghast) {
            return attachToHappyGhast(stack, player, ghast);
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack itemStack, Player player, LivingEntity target, InteractionHand hand) {
        if (target instanceof HappyGhast) {
            return attachToHappyGhast(itemStack, player, target);
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult attachToHappyGhast(ItemStack itemStack, Player player, LivingEntity target) {
        if (!(target instanceof HappyGhast) || itemStack.getItem() != GhastFsdContent.FSD_TASK) {
            return InteractionResult.PASS;
        }
        HappyGhast ghast = (HappyGhast) target;
        if (ghast.isBaby() || FsdTaskAttachment.hasTask(ghast)) {
            return InteractionResult.FAIL;
        }
        if (target.level() instanceof ServerLevel level && GhastCouplingAttachment.hasChainTask(level, ghast)) {
            return InteractionResult.FAIL;
        }
        int routeCount = RouteData.count(itemStack);
        if (routeCount <= 0) {
            if (!target.level().isClientSide()) {
                player.sendOverlayMessage(Component.translatable("error.ghastfsd.task_route_empty"));
            }
            return InteractionResult.FAIL;
        }
        int focus = RouteData.focus(itemStack) + 1;
        if (!target.level().isClientSide()) {
            HappyGhast carrier = target.level() instanceof ServerLevel level ? GhastCouplingAttachment.chainHead(level, ghast) : ghast;
            ItemStack installed = itemStack.copy();
            installed.setCount(1);
            FsdTaskNotifier.rememberInstaller(installed, player);
            FsdTaskAttachment.setTask(carrier, installed);
            GhastAutopilot.initializeAttachedTask(carrier, RouteData.focus(installed));
            if (!player.isCreative()) {
                itemStack.shrink(1);
            }
            player.sendOverlayMessage(Component.translatable("message.ghastfsd.task_attached", routeCount, focus));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.ghastfsd.route_count", RouteData.count(stack)).withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.ghastfsd.task_focus", RouteData.focus(stack) + 1).withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable(RouteData.loop(stack) ? "tooltip.ghastfsd.loop_enabled" : "tooltip.ghastfsd.loop_disabled").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.accept(Component.translatable("tooltip.ghastfsd.task_controls").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.accept(Component.translatable("tooltip.ghastfsd.station_to_station").withStyle(ChatFormatting.DARK_GRAY));
    }
}
