package io.github.makaseloli.ghastfsd.content;

import com.mojang.serialization.MapCodec;
import io.github.makaseloli.ghastfsd.network.GhastFsdPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;

public class GhastStationBlock extends BaseEntityBlock implements EntityBlock, SimpleWaterloggedBlock {
    public static final MapCodec<GhastStationBlock> CODEC = simpleCodec(GhastStationBlock::new);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final int STATION_CHECK_INTERVAL_TICKS = 10;

    public GhastStationBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
            .setValue(WATERLOGGED, false));
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
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction direction = context.getPlayer() == null ? Direction.NORTH : context.getPlayer().getDirection();
        FluidState fluidState = context.getLevel().getFluidState(context.getClickedPos());
        return defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, direction)
            .setValue(WATERLOGGED, fluidState.is(Fluids.WATER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(HorizontalDirectionalBlock.FACING, WATERLOGGED);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(HorizontalDirectionalBlock.FACING, rotation.rotate(state.getValue(HorizontalDirectionalBlock.FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(HorizontalDirectionalBlock.FACING)));
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess scheduledTickAccess, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        if (state.getValue(WATERLOGGED)) {
            scheduledTickAccess.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, level, scheduledTickAccess, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        if (!SimpleWaterloggedBlock.super.placeLiquid(level, pos, state, fluidState)) {
            return false;
        }
        if (!level.isClientSide() && fluidState.is(Fluids.WATER)) {
            level.playSound(null, pos, SoundEvents.DRIED_GHAST_PLACE_IN_WATER, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return true;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        level.playSound(
            null,
            pos,
            state.getValue(WATERLOGGED) ? SoundEvents.DRIED_GHAST_PLACE_IN_WATER : SoundEvents.DRIED_GHAST_PLACE,
            SoundSource.BLOCKS,
            1.0F,
            1.0F
        );
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide() || !state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return;
        }
        if (!oldState.is(state.getBlock()) || !oldState.hasProperty(HorizontalDirectionalBlock.FACING)
            || oldState.getValue(HorizontalDirectionalBlock.FACING) != state.getValue(HorizontalDirectionalBlock.FACING)) {
            syncStationDirection(level, pos, state);
        }
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (level.isClientSide() || blockEntityType != GhastFsdContent.GHAST_STATION_BLOCK_ENTITY) {
            return null;
        }
        return (tickLevel, tickPos, tickState, blockEntity) -> {
            if ((tickLevel.getGameTime() + Math.floorMod(tickPos.asLong(), STATION_CHECK_INTERVAL_TICKS)) % STATION_CHECK_INTERVAL_TICKS != 0) {
                return;
            }
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
        return !level.getEntitiesOfClass(HappyGhast.class, box, HappyGhast::isAlive).isEmpty();
    }

    public static void notifyComparator(Level level, BlockPos pos) {
        if (!level.isClientSide()) {
            level.updateNeighbourForOutputSignal(pos, GhastFsdContent.GHAST_STATION);
        }
    }

    public static AABB arrivalBox(BlockPos pos) {
        return GhastStationGeometry.arrivalBox(pos);
    }

    public static boolean isRedstonePowered(Level level, BlockPos pos) {
        return level.hasNeighborSignal(pos);
    }

    private static void syncStationIndex(Level level, BlockPos pos, GhastStationBlockEntity station) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        station.syncDirectionFromBlockState(level.getBlockState(pos));
        String name = GhastStationData.sanitizeName(station.stationName());
        if (name.isBlank()) {
            return;
        }
        GhastStationData data = GhastStationData.get(serverLevel);
        GhastStationData.StationRef indexed = data.find(name).orElse(null);
        if (indexed == null
            || !indexed.samePlace(serverLevel.dimension(), pos)
            || indexed.dockingHeight() != station.dockingHeight()
            || indexed.direction() != station.stationDirection()) {
            data.update(serverLevel.dimension(), pos, station);
        }
    }

    private static void syncStationDirection(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof GhastStationBlockEntity station)) {
            return;
        }
        station.syncDirectionFromBlockState(state);
        syncStationIndex(serverLevel, pos, station);
    }
}
