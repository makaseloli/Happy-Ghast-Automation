package io.github.makaseloli.ghastfsd.network;

import io.github.makaseloli.ghastfsd.ModUtils;
import io.github.makaseloli.ghastfsd.content.FsdTaskAttachment;
import io.github.makaseloli.ghastfsd.content.FsdTaskNotifier;
import io.github.makaseloli.ghastfsd.content.GhastControlState;
import io.github.makaseloli.ghastfsd.content.GhastCouplingAttachment;
import io.github.makaseloli.ghastfsd.content.GhastFsdContent;
import io.github.makaseloli.ghastfsd.content.GhastStationBlockEntity;
import io.github.makaseloli.ghastfsd.content.GhastStationData;
import io.github.makaseloli.ghastfsd.content.GhastStationBlock;
import io.github.makaseloli.ghastfsd.route.RouteData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class GhastFsdPayloads {
    private GhastFsdPayloads() {
    }

    public record SaveTaskRoutePayload(InteractionHand hand, CompoundTag routeRoot) implements CustomPacketPayload {
        public static final Type<SaveTaskRoutePayload> TYPE = new Type<>(ModUtils.id("save_task_route"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveTaskRoutePayload> STREAM_CODEC = StreamCodec.ofMember(
            SaveTaskRoutePayload::write,
            SaveTaskRoutePayload::read
        );

        private static SaveTaskRoutePayload read(RegistryFriendlyByteBuf buf) {
            return new SaveTaskRoutePayload(buf.readEnum(InteractionHand.class), buf.readNbt());
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeEnum(hand);
            buf.writeNbt(routeRoot);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestTaskEditorPayload(InteractionHand hand) implements CustomPacketPayload {
        public static final Type<RequestTaskEditorPayload> TYPE = new Type<>(ModUtils.id("request_task_editor"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestTaskEditorPayload> STREAM_CODEC = StreamCodec.ofMember(
            RequestTaskEditorPayload::write,
            RequestTaskEditorPayload::read
        );

        private static RequestTaskEditorPayload read(RegistryFriendlyByteBuf buf) {
            return new RequestTaskEditorPayload(buf.readEnum(InteractionHand.class));
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeEnum(hand);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record StationOption(String name, String dimension, BlockPos pos) {
        private static StationOption read(RegistryFriendlyByteBuf buf) {
            return new StationOption(buf.readUtf(64), buf.readUtf(128), buf.readBlockPos());
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(name, 64);
            buf.writeUtf(dimension, 128);
            buf.writeBlockPos(pos);
        }
    }

    public record OpenTaskEditorPayload(InteractionHand hand, CompoundTag routeRoot, List<StationOption> stations) implements CustomPacketPayload {
        public static final Type<OpenTaskEditorPayload> TYPE = new Type<>(ModUtils.id("open_task_editor"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenTaskEditorPayload> STREAM_CODEC = StreamCodec.ofMember(
            OpenTaskEditorPayload::write,
            OpenTaskEditorPayload::read
        );

        private static OpenTaskEditorPayload read(RegistryFriendlyByteBuf buf) {
            InteractionHand hand = buf.readEnum(InteractionHand.class);
            CompoundTag routeRoot = buf.readNbt();
            int count = buf.readVarInt();
            ArrayList<StationOption> stations = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                stations.add(StationOption.read(buf));
            }
            return new OpenTaskEditorPayload(hand, routeRoot, stations);
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeEnum(hand);
            buf.writeNbt(routeRoot);
            buf.writeVarInt(stations.size());
            for (StationOption station : stations) {
                station.write(buf);
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestStationEditorPayload(BlockPos pos) implements CustomPacketPayload {
        public static final Type<RequestStationEditorPayload> TYPE = new Type<>(ModUtils.id("request_station_editor"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestStationEditorPayload> STREAM_CODEC = StreamCodec.ofMember(
            RequestStationEditorPayload::write,
            RequestStationEditorPayload::read
        );

        private static RequestStationEditorPayload read(RegistryFriendlyByteBuf buf) {
            return new RequestStationEditorPayload(buf.readBlockPos());
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(pos);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenStationEditorPayload(BlockPos pos, String name, int dockingHeight, String stationDirection, String arrivalInstrument, int arrivalNote, String groupName) implements CustomPacketPayload {
        public static final Type<OpenStationEditorPayload> TYPE = new Type<>(ModUtils.id("open_station_editor"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenStationEditorPayload> STREAM_CODEC = StreamCodec.ofMember(
            OpenStationEditorPayload::write,
            OpenStationEditorPayload::read
        );

        private static OpenStationEditorPayload read(RegistryFriendlyByteBuf buf) {
            return new OpenStationEditorPayload(buf.readBlockPos(), buf.readUtf(64), buf.readVarInt(), buf.readUtf(16), buf.readUtf(32), buf.readVarInt(), buf.readUtf(64));
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(pos);
            buf.writeUtf(name, 64);
            buf.writeVarInt(dockingHeight);
            buf.writeUtf(stationDirection, 16);
            buf.writeUtf(arrivalInstrument, 32);
            buf.writeVarInt(arrivalNote);
            buf.writeUtf(groupName, 64);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SaveStationPayload(BlockPos pos, String name, int dockingHeight, String stationDirection, String arrivalInstrument, int arrivalNote, String groupName) implements CustomPacketPayload {
        public static final Type<SaveStationPayload> TYPE = new Type<>(ModUtils.id("save_station"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveStationPayload> STREAM_CODEC = StreamCodec.ofMember(
            SaveStationPayload::write,
            SaveStationPayload::read
        );

        private static SaveStationPayload read(RegistryFriendlyByteBuf buf) {
            return new SaveStationPayload(buf.readBlockPos(), buf.readUtf(64), buf.readVarInt(), buf.readUtf(16), buf.readUtf(32), buf.readVarInt(), buf.readUtf(64));
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(pos);
            buf.writeUtf(name, 64);
            buf.writeVarInt(dockingHeight);
            buf.writeUtf(stationDirection, 16);
            buf.writeUtf(arrivalInstrument, 32);
            buf.writeVarInt(arrivalNote);
            buf.writeUtf(groupName, 64);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GhastControlStatePayload(int entityId, boolean hasTask, String couplingNext, String couplingPrevious) implements CustomPacketPayload {
        public static final Type<GhastControlStatePayload> TYPE = new Type<>(ModUtils.id("ghast_control_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GhastControlStatePayload> STREAM_CODEC = StreamCodec.ofMember(
            GhastControlStatePayload::write,
            GhastControlStatePayload::read
        );

        private static GhastControlStatePayload read(RegistryFriendlyByteBuf buf) {
            return new GhastControlStatePayload(buf.readVarInt(), buf.readBoolean(), buf.readUtf(36), buf.readUtf(36));
        }

        public static GhastControlStatePayload from(GhastControlState state) {
            return new GhastControlStatePayload(state.entityId(), state.hasTask(), state.couplingNext(), state.couplingPrevious());
        }

        public GhastControlState state() {
            return new GhastControlState(entityId, hasTask, couplingNext, couplingPrevious);
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(entityId);
            buf.writeBoolean(hasTask);
            buf.writeUtf(couplingNext == null ? "" : couplingNext, 36);
            buf.writeUtf(couplingPrevious == null ? "" : couplingPrevious, 36);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void handleSaveTaskRoute(SaveTaskRoutePayload payload, ServerPlayer player) {
        ItemStack stack = player.getItemInHand(payload.hand());
        if (stack.getItem() == GhastFsdContent.FSD_TASK) {
            RouteData.replaceRouteRoot(stack, payload.routeRoot() == null ? new CompoundTag() : payload.routeRoot());
            player.sendOverlayMessage(Component.translatable("message.ghastfsd.route_saved", RouteData.count(stack)));
        }
    }

    public static void openTaskEditor(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() != GhastFsdContent.FSD_TASK) {
            return;
        }
        if (player.level() instanceof ServerLevel serverLevel) {
            player.connection.send(new ClientboundCustomPayloadPacket(new OpenTaskEditorPayload(hand, RouteData.copyRouteRoot(stack), stationOptions(serverLevel))));
        }
    }

    public static void handleRequestTaskEditor(RequestTaskEditorPayload payload, ServerPlayer player) {
        openTaskEditor(player, payload.hand());
    }

    public static void handleRequestStationEditor(RequestStationEditorPayload payload, ServerPlayer player) {
        if (player.level() instanceof ServerLevel serverLevel && isEditableStation(serverLevel, payload.pos(), player)) {
            String name = "";
            int dockingHeight = GhastStationBlockEntity.DEFAULT_DOCKING_HEIGHT;
            String stationDirection = Direction.NORTH.getSerializedName();
            String arrivalInstrument = GhastStationBlockEntity.parseInstrument("").getSerializedName();
            int arrivalNote = GhastStationBlockEntity.DEFAULT_NOTE;
            String groupName = dockedGroupName(serverLevel, payload.pos());
            if (serverLevel.getBlockEntity(payload.pos()) instanceof GhastStationBlockEntity station) {
                name = station.stationName();
                dockingHeight = station.dockingHeight();
                stationDirection = station.stationDirection().getSerializedName();
                arrivalInstrument = station.arrivalInstrument().getSerializedName();
                arrivalNote = station.arrivalNote();
            }
            player.connection.send(new ClientboundCustomPayloadPacket(new OpenStationEditorPayload(payload.pos(), name, dockingHeight, stationDirection, arrivalInstrument, arrivalNote, groupName)));
        }
    }

    public static void handleSaveStation(SaveStationPayload payload, ServerPlayer player) {
        if (player.level() instanceof ServerLevel serverLevel && isEditableStation(serverLevel, payload.pos(), player)) {
            GhastStationData data = GhastStationData.get(serverLevel);
            String name = GhastStationData.sanitizeName(payload.name());
            if (!data.canRename(serverLevel.dimension(), payload.pos(), name)) {
                player.sendOverlayMessage(Component.translatable("error.ghastfsd.station_name_duplicate", name));
                return;
            }
            if (serverLevel.getBlockEntity(payload.pos()) instanceof GhastStationBlockEntity station) {
                station.setStationName(name);
                station.setDockingHeight(payload.dockingHeight());
                station.setStationDirection(payload.stationDirection());
                station.setArrivalInstrument(payload.arrivalInstrument());
                station.setArrivalNote(payload.arrivalNote());
                data.update(serverLevel.dimension(), payload.pos(), station);
                applyDockedGroupName(serverLevel, payload.pos(), payload.groupName());
                player.sendOverlayMessage(Component.translatable("message.ghastfsd.station_saved", name));
            }
        }
    }

    private static String dockedGroupName(ServerLevel level, BlockPos pos) {
        for (HappyGhast ghast : level.getEntitiesOfClass(HappyGhast.class, GhastStationBlock.arrivalBox(pos), HappyGhast::isAlive)) {
            HappyGhast carrier = GhastCouplingAttachment.taskCarrier(level, ghast).orElse(ghast);
            ItemStack task = FsdTaskAttachment.getTask(carrier);
            if (task.getItem() == GhastFsdContent.FSD_TASK) {
                return FsdTaskNotifier.groupName(task);
            }
        }
        return "";
    }

    private static void applyDockedGroupName(ServerLevel level, BlockPos pos, String name) {
        for (HappyGhast ghast : level.getEntitiesOfClass(HappyGhast.class, GhastStationBlock.arrivalBox(pos), HappyGhast::isAlive)) {
            HappyGhast carrier = GhastCouplingAttachment.taskCarrier(level, ghast).orElse(ghast);
            ItemStack task = FsdTaskAttachment.getTask(carrier);
            if (task.getItem() == GhastFsdContent.FSD_TASK) {
                FsdTaskNotifier.setGroupName(task, name);
                FsdTaskAttachment.setTask(carrier, task);
                GhastControlSync.syncChain(level, carrier);
            }
        }
    }

    private static boolean isEditableStation(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (player.distanceToSqr(pos.getCenter()) > 64.0) {
            return false;
        }
        return GhastFsdContent.isStation(level.getBlockState(pos).getBlock());
    }

    public static String stationName(ServerLevel level, ResourceKey<Level> dimension, BlockPos pos) {
        if (level.dimension().identifier().equals(dimension.identifier()) && level.getBlockEntity(pos) instanceof GhastStationBlockEntity station) {
            return station.stationName();
        }
        return GhastStationData.get(level).getIndexedName(dimension, pos);
    }

    private static List<StationOption> stationOptions(ServerLevel level) {
        return GhastStationData.get(level).stations().stream()
            .map(ref -> new StationOption(ref.name(), ref.dimension().identifier().toString(), ref.pos()))
            .toList();
    }
}
