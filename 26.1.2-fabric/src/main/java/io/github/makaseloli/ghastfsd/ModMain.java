package io.github.makaseloli.ghastfsd;

import io.github.makaseloli.ghastfsd.automation.GhastAutopilot;
import io.github.makaseloli.ghastfsd.content.FsdTaskItem;
import io.github.makaseloli.ghastfsd.content.GhastFsdContent;
import io.github.makaseloli.ghastfsd.network.GhastFsdPayloads;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.item.ItemStack;

public class ModMain implements ModInitializer {
    @Override
    public void onInitialize() {
        Constants.LOGGER.debug(Constants.INITIALIZING, ModUtils.id("26.1.2-fabric"));
        Registry.register(BuiltInRegistries.BLOCK, GhastFsdContent.GHAST_STATION_ID, GhastFsdContent.GHAST_STATION);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, GhastFsdContent.GHAST_STATION_BLOCK_ENTITY_ID, GhastFsdContent.GHAST_STATION_BLOCK_ENTITY);
        Registry.register(BuiltInRegistries.ITEM, GhastFsdContent.GHAST_STATION_ID, GhastFsdContent.GHAST_STATION_ITEM);
        Registry.register(BuiltInRegistries.ITEM, GhastFsdContent.FSD_TASK_ID, GhastFsdContent.FSD_TASK);
        Registry.register(BuiltInRegistries.ITEM, GhastFsdContent.FSD_TASK_REMOVER_ID, GhastFsdContent.FSD_TASK_REMOVER);
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, GhastFsdContent.ITEM_GROUP_KEY, GhastFsdContent.ITEM_GROUP);
        PayloadTypeRegistry.serverboundPlay().register(GhastFsdPayloads.SaveTaskRoutePayload.TYPE, GhastFsdPayloads.SaveTaskRoutePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(GhastFsdPayloads.RequestTaskEditorPayload.TYPE, GhastFsdPayloads.RequestTaskEditorPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(GhastFsdPayloads.RequestStationEditorPayload.TYPE, GhastFsdPayloads.RequestStationEditorPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(GhastFsdPayloads.SaveStationPayload.TYPE, GhastFsdPayloads.SaveStationPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(GhastFsdPayloads.OpenTaskEditorPayload.TYPE, GhastFsdPayloads.OpenTaskEditorPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(GhastFsdPayloads.OpenStationEditorPayload.TYPE, GhastFsdPayloads.OpenStationEditorPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(GhastFsdPayloads.SaveTaskRoutePayload.TYPE, (payload, context) -> GhastFsdPayloads.handleSaveTaskRoute(payload, context.player()));
        ServerPlayNetworking.registerGlobalReceiver(GhastFsdPayloads.RequestTaskEditorPayload.TYPE, (payload, context) -> GhastFsdPayloads.handleRequestTaskEditor(payload, context.player()));
        ServerPlayNetworking.registerGlobalReceiver(GhastFsdPayloads.RequestStationEditorPayload.TYPE, (payload, context) -> GhastFsdPayloads.handleRequestStationEditor(payload, context.player()));
        ServerPlayNetworking.registerGlobalReceiver(GhastFsdPayloads.SaveStationPayload.TYPE, (payload, context) -> GhastFsdPayloads.handleSaveStation(payload, context.player()));
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (entity instanceof HappyGhast ghast && player.getItemInHand(hand).getItem() == GhastFsdContent.FSD_TASK) {
                if (!world.isClientSide()) {
                    FsdTaskItem.attachToHappyGhast(player.getItemInHand(hand), player, ghast);
                }
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        CreativeModeTabEvents.modifyOutputEvent(GhastFsdContent.ITEM_GROUP_KEY).register(output -> {
            output.accept(new ItemStack(GhastFsdContent.GHAST_STATION_ITEM));
            output.accept(new ItemStack(GhastFsdContent.FSD_TASK));
            output.accept(new ItemStack(GhastFsdContent.FSD_TASK_REMOVER));
        });
        ServerTickEvents.END_SERVER_TICK.register(GhastAutopilot::tickServer);
    }
}
