package io.github.makaseloli.ghastfsd.client;

import io.github.makaseloli.ghastfsd.content.GhastFsdContent;
import io.github.makaseloli.ghastfsd.network.GhastControlSync;
import io.github.makaseloli.ghastfsd.network.GhastFsdPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public class ModClient implements ClientModInitializer {
    private static boolean attackWasDown;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(ModClient::openTaskEditorFromAttackKey);
        ClientPlayNetworking.registerGlobalReceiver(GhastFsdPayloads.OpenTaskEditorPayload.TYPE, (payload, context) ->
            GhastFsdScreens.openTaskEditor(payload.hand(), payload.routeRoot(), payload.stations())
        );
        ClientPlayNetworking.registerGlobalReceiver(GhastFsdPayloads.OpenStationEditorPayload.TYPE, (payload, context) ->
            GhastFsdScreens.openStationEditor(payload.pos(), payload.name(), payload.dockingHeight(), payload.stationDirection(), payload.arrivalInstrument(), payload.arrivalNote(), payload.groupName())
        );
        ClientPlayNetworking.registerGlobalReceiver(GhastFsdPayloads.GhastControlStatePayload.TYPE, (payload, context) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null) {
                GhastControlSync.apply(minecraft.level, payload.state());
            }
        });
    }

    private static void openTaskEditorFromAttackKey(Minecraft minecraft) {
        if (minecraft.gui.screen() != null || minecraft.player == null || minecraft.getConnection() == null) {
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
