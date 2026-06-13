package io.github.makaseloli.ghastfsd.content;

import io.github.makaseloli.ghastfsd.ModUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.Set;

public final class GhastFsdContent {
    public static final Identifier GHAST_STATION_ID = ModUtils.id("ghast_station");
    public static final Identifier GHAST_STATION_BLOCK_ENTITY_ID = ModUtils.id("ghast_station");
    public static final Identifier FSD_TASK_ID = ModUtils.id("fsd_task");
    public static final Identifier FSD_TASK_REMOVER_ID = ModUtils.id("fsd_task_remover");
    public static final Identifier GHAST_COUPLING_LEAD_ID = ModUtils.id("ghast_coupling_lead");
    public static final Identifier ITEM_GROUP_ID = ModUtils.id("happy_ghast_automation");
    public static final ResourceKey<CreativeModeTab> ITEM_GROUP_KEY = ResourceKey.create(Registries.CREATIVE_MODE_TAB, ITEM_GROUP_ID);

    public static final GhastStationBlock GHAST_STATION = new GhastStationBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, GHAST_STATION_ID))
            .forceSolidOn()
            .instabreak()
            .sound(SoundType.DRIED_GHAST)
            .noOcclusion()
    );

    public static final BlockItem GHAST_STATION_ITEM = new BlockItem(
        GHAST_STATION,
        new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, GHAST_STATION_ID))
            .useBlockDescriptionPrefix()
    );

    public static final BlockEntityType<GhastStationBlockEntity> GHAST_STATION_BLOCK_ENTITY = createStationBlockEntityType();

    public static final FsdTaskItem FSD_TASK = new FsdTaskItem(
        new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, FSD_TASK_ID))
            .stacksTo(64)
    );

    public static final FsdTaskRemoverItem FSD_TASK_REMOVER = new FsdTaskRemoverItem(
        new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, FSD_TASK_REMOVER_ID))
            .stacksTo(1)
    );

    public static final GhastCouplingLeadItem GHAST_COUPLING_LEAD = new GhastCouplingLeadItem(
        new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, GHAST_COUPLING_LEAD_ID))
            .stacksTo(1)
    );

    public static final CreativeModeTab ITEM_GROUP = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
        .title(Component.translatable("itemGroup.ghastfsd.happy_ghast_automation"))
        .icon(() -> new ItemStack(GHAST_STATION_ITEM))
        .build();

    private GhastFsdContent() {}

    public static boolean isStation(Block block) {
        return block == GHAST_STATION;
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
            return constructor.newInstance(supplier, Set.of(GHAST_STATION));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to create ghast station block entity type", exception);
        }
    }
}
