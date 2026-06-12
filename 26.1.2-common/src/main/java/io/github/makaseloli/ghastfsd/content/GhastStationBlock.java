package io.github.makaseloli.ghastfsd.content;

import com.mojang.serialization.MapCodec;
import io.github.makaseloli.ghastfsd.network.GhastFsdPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;

public class GhastStationBlock extends BaseEntityBlock implements EntityBlock {
    public static final MapCodec<GhastStationBlock> CODEC = simpleCodec(GhastStationBlock::new);
    public static final double ARRIVAL_RADIUS = 8.0;
    public static final double ARRIVAL_HEIGHT = 8.0;

    public GhastStationBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GhastStationBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (level.isClientSide() || blockEntityType != GhastFsdContent.GHAST_STATION_BLOCK_ENTITY) {
            return null;
        }
        return (tickLevel, tickPos, tickState, blockEntity) -> {
            if (blockEntity instanceof GhastStationBlockEntity station) {
                syncStationIndex(tickLevel, tickPos, station);
                if (station.updateComparatorOccupied(hasHappyGhast(tickLevel, tickPos))) {
                    notifyComparator(tickLevel, tickPos);
                }
            }
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            GhastStationData.get(serverLevel).remove(serverLevel.dimension(), pos);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            GhastFsdPayloads.handleRequestStationEditor(new GhastFsdPayloads.RequestStationEditorPayload(pos), serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (stack.getItem() == GhastFsdContent.FSD_TASK) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            GhastFsdPayloads.handleRequestStationEditor(new GhastFsdPayloads.RequestStationEditorPayload(pos), serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return hasHappyGhast(level, pos) ? 15 : 0;
    }

    public static boolean hasHappyGhast(Level level, BlockPos pos) {
        AABB box = arrivalBox(pos);
        if (level instanceof ServerLevel serverLevel) {
            return !serverLevel.getEntities(EntityType.HAPPY_GHAST, ghast -> ghast.isAlive() && box.intersects(ghast.getBoundingBox())).isEmpty();
        }
        return !level.getEntitiesOfClass(HappyGhast.class, box, HappyGhast::isAlive).isEmpty();
    }

    public static void notifyComparator(Level level, BlockPos pos) {
        if (!level.isClientSide()) {
            level.updateNeighbourForOutputSignal(pos, GhastFsdContent.GHAST_STATION);
        }
    }

    public static AABB arrivalBox(BlockPos pos) {
        return new AABB(
            pos.getX() - ARRIVAL_RADIUS,
            pos.getY(),
            pos.getZ() - ARRIVAL_RADIUS,
            pos.getX() + 1.0 + ARRIVAL_RADIUS,
            pos.getY() + GhastStationBlockEntity.MAX_DOCKING_HEIGHT + ARRIVAL_HEIGHT,
            pos.getZ() + 1.0 + ARRIVAL_RADIUS
        );
    }

    public static boolean isRedstonePowered(Level level, BlockPos pos) {
        return level.hasNeighborSignal(pos);
    }

    private static void syncStationIndex(Level level, BlockPos pos, GhastStationBlockEntity station) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        String name = GhastStationData.sanitizeName(station.stationName());
        if (name.isBlank()) {
            return;
        }
        GhastStationData data = GhastStationData.get(serverLevel);
        GhastStationData.StationRef indexed = data.find(name).orElse(null);
        if (indexed == null || !indexed.samePlace(serverLevel.dimension(), pos) || indexed.dockingHeight() != station.dockingHeight()) {
            data.update(serverLevel.dimension(), pos, station);
        }
    }
}
