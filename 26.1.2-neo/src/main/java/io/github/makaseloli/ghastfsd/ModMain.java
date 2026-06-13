package io.github.makaseloli.ghastfsd;

import io.github.makaseloli.ghastfsd.automation.GhastAutopilot;
import io.github.makaseloli.ghastfsd.content.FsdTaskAttachment;
import io.github.makaseloli.ghastfsd.content.FsdTaskItem;
import io.github.makaseloli.ghastfsd.content.FsdTaskNotifier;
import io.github.makaseloli.ghastfsd.content.GhastCouplingLeadItem;
import io.github.makaseloli.ghastfsd.content.GhastCouplingAttachment;
import io.github.makaseloli.ghastfsd.content.GhastFsdContent;
import io.github.makaseloli.ghastfsd.content.GhastStationBlockEntity;
import io.github.makaseloli.ghastfsd.network.GhastFsdPayloads;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(Constants.MODID)
public class ModMain {
    public ModMain(IEventBus modEventBus, ModContainer modContainer) {
        Constants.LOGGER.debug(Constants.INITIALIZING, ModUtils.id("26.1.2-neo"));
        modEventBus.addListener(this::register);
        modEventBus.addListener(this::buildCreativeTabs);
        modEventBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::entityInteract);
        NeoForge.EVENT_BUS.addListener(this::playerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::livingDeath);
    }

    private void register(RegisterEvent event) {
        if (GhastFsdContent.GHAST_STATION_BLOCK_ENTITY == null) {
            GhastFsdContent.setStationBlockEntityType(createStationBlockEntityType());
        }
        event.register(Registries.BLOCK, GhastFsdContent.GHAST_STATION_ID, () -> GhastFsdContent.GHAST_STATION);
        event.register(Registries.BLOCK_ENTITY_TYPE, GhastFsdContent.GHAST_STATION_BLOCK_ENTITY_ID, () -> GhastFsdContent.GHAST_STATION_BLOCK_ENTITY);
        event.register(Registries.ITEM, GhastFsdContent.GHAST_STATION_ID, () -> GhastFsdContent.GHAST_STATION_ITEM);
        event.register(Registries.ITEM, GhastFsdContent.FSD_TASK_ID, () -> GhastFsdContent.FSD_TASK);
        event.register(Registries.ITEM, GhastFsdContent.FSD_TASK_REMOVER_ID, () -> GhastFsdContent.FSD_TASK_REMOVER);
        event.register(Registries.ITEM, GhastFsdContent.GHAST_COUPLING_LEAD_ID, () -> GhastFsdContent.GHAST_COUPLING_LEAD);
        event.register(Registries.CREATIVE_MODE_TAB, GhastFsdContent.ITEM_GROUP_ID, () -> GhastFsdContent.ITEM_GROUP);
    }

    private void buildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == GhastFsdContent.ITEM_GROUP_KEY) {
            event.accept(new ItemStack(GhastFsdContent.GHAST_STATION_ITEM));
            event.accept(new ItemStack(GhastFsdContent.FSD_TASK));
            event.accept(new ItemStack(GhastFsdContent.FSD_TASK_REMOVER));
            event.accept(new ItemStack(GhastFsdContent.GHAST_COUPLING_LEAD));
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(GhastFsdPayloads.SaveTaskRoutePayload.TYPE, GhastFsdPayloads.SaveTaskRoutePayload.STREAM_CODEC, (payload, context) -> GhastFsdPayloads.handleSaveTaskRoute(payload, (net.minecraft.server.level.ServerPlayer) context.player()));
        registrar.playToServer(GhastFsdPayloads.RequestTaskEditorPayload.TYPE, GhastFsdPayloads.RequestTaskEditorPayload.STREAM_CODEC, (payload, context) -> GhastFsdPayloads.handleRequestTaskEditor(payload, (net.minecraft.server.level.ServerPlayer) context.player()));
        registrar.playToServer(GhastFsdPayloads.RequestStationEditorPayload.TYPE, GhastFsdPayloads.RequestStationEditorPayload.STREAM_CODEC, (payload, context) -> GhastFsdPayloads.handleRequestStationEditor(payload, (net.minecraft.server.level.ServerPlayer) context.player()));
        registrar.playToServer(GhastFsdPayloads.SaveStationPayload.TYPE, GhastFsdPayloads.SaveStationPayload.STREAM_CODEC, (payload, context) -> GhastFsdPayloads.handleSaveStation(payload, (net.minecraft.server.level.ServerPlayer) context.player()));
        registrar.playToClient(GhastFsdPayloads.OpenTaskEditorPayload.TYPE, GhastFsdPayloads.OpenTaskEditorPayload.STREAM_CODEC);
        registrar.playToClient(GhastFsdPayloads.OpenStationEditorPayload.TYPE, GhastFsdPayloads.OpenStationEditorPayload.STREAM_CODEC);
        registrar.playToClient(GhastFsdPayloads.GhastControlStatePayload.TYPE, GhastFsdPayloads.GhastControlStatePayload.STREAM_CODEC);
    }

    private void serverTick(ServerTickEvent.Post event) {
        GhastAutopilot.tickServer(event.getServer());
    }

    private void entityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget() instanceof HappyGhast ghast
            && event.getEntity().getItemInHand(event.getHand()).getItem() == GhastFsdContent.FSD_TASK) {
            if (!event.getLevel().isClientSide()) {
                FsdTaskItem.attachToHappyGhast(event.getEntity().getItemInHand(event.getHand()), event.getEntity(), ghast);
            }
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
        if (event.getTarget() instanceof HappyGhast ghast
            && event.getEntity().getItemInHand(event.getHand()).getItem() == GhastFsdContent.GHAST_COUPLING_LEAD) {
            if (!event.getLevel().isClientSide()) {
                GhastCouplingLeadItem.useOnHappyGhast(event.getEntity().getItemInHand(event.getHand()), event.getEntity(), ghast);
            }
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
        if (event.getTarget() instanceof HappyGhast ghast
            && GhastCouplingLeadItem.cutWithShears(event.getEntity().getItemInHand(event.getHand()), event.getEntity(), ghast) == InteractionResult.SUCCESS) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    private void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FsdTaskNotifier.deliverPending(player);
        }
    }

    private void livingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof HappyGhast ghast && ghast.level() instanceof ServerLevel level) {
            HappyGhast carrier = GhastCouplingAttachment.taskCarrier(level, ghast).orElse(ghast);
            ItemStack task = FsdTaskAttachment.getTask(carrier);
            if (task.getItem() == GhastFsdContent.FSD_TASK) {
                FsdTaskNotifier.notifyGhastKilled(level.getServer(), ghast, task);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockEntityType<GhastStationBlockEntity> createStationBlockEntityType() {
        try {
            Class<?> supplierType = Class.forName("net.minecraft.world.level.block.entity.BlockEntityType$BlockEntitySupplier");
            Object supplier = Proxy.newProxyInstance(
                supplierType.getClassLoader(),
                new Class<?>[] { supplierType },
                (proxy, method, args) -> new GhastStationBlockEntity(
                    (net.minecraft.core.BlockPos) args[0],
                    (net.minecraft.world.level.block.state.BlockState) args[1]
                )
            );
            Constructor<BlockEntityType> constructor = BlockEntityType.class.getDeclaredConstructor(supplierType, Set.class);
            constructor.setAccessible(true);
            return constructor.newInstance(supplier, Set.of(GhastFsdContent.GHAST_STATION));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to create ghast station block entity type", exception);
        }
    }
}
