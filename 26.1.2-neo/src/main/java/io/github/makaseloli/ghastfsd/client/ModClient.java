package io.github.makaseloli.ghastfsd.client;

import io.github.makaseloli.ghastfsd.Constants;
import io.github.makaseloli.ghastfsd.content.GhastFsdContent;
import io.github.makaseloli.ghastfsd.network.GhastFsdPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

@EventBusSubscriber(modid = Constants.MODID, value = Dist.CLIENT)
public class ModClient {
    private static boolean attackWasDown;

    @SubscribeEvent
    public static void registerClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(GhastFsdPayloads.OpenTaskEditorPayload.TYPE, (payload, context) ->
            GhastFsdScreens.openTaskEditor(payload.hand(), payload.routeRoot(), payload.stations())
        );
        event.register(GhastFsdPayloads.OpenStationEditorPayload.TYPE, (payload, context) ->
            GhastFsdScreens.openStationEditor(payload.pos(), payload.name(), payload.dockingHeight(), payload.arrivalInstrument(), payload.arrivalNote())
        );
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null || minecraft.getConnection() == null) {
            attackWasDown = minecraft.options.keyAttack.isDown();
            return;
        }
        InteractionHand hand = taskHand(minecraft);
        boolean attackDown = minecraft.options.keyAttack.isDown();
        if (hand == null) {
            attackWasDown = attackDown;
            return;
        }
        if (attackDown && !attackWasDown) {
            minecraft.getConnection().send(new ServerboundCustomPayloadPacket(new GhastFsdPayloads.RequestTaskEditorPayload(hand)));
        }
        attackWasDown = attackDown;
    }

    private static InteractionHand taskHand(Minecraft minecraft) {
        ItemStack main = minecraft.player.getMainHandItem();
        if (main.getItem() == GhastFsdContent.FSD_TASK) {
            return InteractionHand.MAIN_HAND;
        }
        ItemStack off = minecraft.player.getOffhandItem();
        return off.getItem() == GhastFsdContent.FSD_TASK ? InteractionHand.OFF_HAND : null;
    }
}
