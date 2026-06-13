package io.github.makaseloli.ghastfsd.content;

import io.github.makaseloli.ghastfsd.automation.AutopilotState;
import io.github.makaseloli.ghastfsd.network.GhastControlSync;
import io.github.makaseloli.ghastfsd.route.RouteData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class FsdTaskRemoverItem extends Item {
    public FsdTaskRemoverItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player.getVehicle() instanceof HappyGhast ghast) {
            return removeFromHappyGhast(player.getItemInHand(hand), player, ghast);
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof HappyGhast ghast)) {
            return InteractionResult.PASS;
        }
        return removeFromHappyGhast(stack, player, ghast);
    }

    private static InteractionResult removeFromHappyGhast(ItemStack stack, Player player, HappyGhast ghast) {
        HappyGhast carrier = ghast;
        if (ghast.level() instanceof ServerLevel level) {
            carrier = GhastCouplingAttachment.taskCarrier(level, ghast).orElse(ghast);
        }
        ItemStack task = FsdTaskAttachment.getTask(carrier);
        if (task.getItem() != GhastFsdContent.FSD_TASK) {
            return InteractionResult.PASS;
        }
        if (!ghast.level().isClientSide()) {
            AutopilotState state = AutopilotState.read(carrier);
            RouteData.setFocus(task, state.index);
            int focus = RouteData.focus(task) + 1;
            if (ghast.level() instanceof ServerLevel level) {
                FsdTaskNotifier.notifyTaskRemovedBy(level, carrier, task, player);
            }
            FsdTaskAttachment.removeTask(carrier);
            if (ghast.level() instanceof ServerLevel level) {
                GhastControlSync.syncChain(level, carrier);
            }
            if (!player.getInventory().add(task)) {
                player.drop(task, false);
            }
            player.sendOverlayMessage(Component.translatable("message.ghastfsd.task_removed", focus));
        }
        return InteractionResult.SUCCESS;
    }
}
