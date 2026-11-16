package dev.registry;

import dev.world.assembly.AssemblyBlock;
import dev.world.assembly.AssemblyBlockEntity;
import dev.world.assembly.AssemblyMenu;
import java.util.List;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/** Assembly content uses the existing deferred registers; registration stays in this package. */
public final class ModAssembly {
    public static final DeferredHolder<Block, AssemblyBlock> TABLE =
            ModBlocks.BLOCKS.register(
                    "drill_assembly_table",
                    () ->
                            new AssemblyBlock(
                                    BlockBehaviour.Properties.of()
                                            .mapColor(MapColor.METAL)
                                            .strength(3.5F)
                                            .sound(SoundType.METAL)
                                            .pushReaction(
                                                    net.minecraft.world.level.material.PushReaction
                                                            .BLOCK)
                                            .requiresCorrectToolForDrops()
                                            .noOcclusion()));
    public static final DeferredItem<BlockItem> TABLE_ITEM =
            ModBlocks.ITEMS.register(
                    "drill_assembly_table",
                    () ->
                            new dev.world.assembly.AssemblyTableItem(
                                    TABLE.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AssemblyBlockEntity>>
            BLOCK_ENTITY =
                    ModBlockEntities.BLOCK_ENTITY_TYPES.register(
                            "drill_assembly_table",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    AssemblyBlockEntity::new, TABLE.get())
                                            .build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<AssemblyMenu>> MENU =
            ModMenuTypes.MENU_TYPES.register(
                    "drill_assembly_table",
                    () -> new MenuType<>(AssemblyMenu::new, FeatureFlags.VANILLA_SET));

    public static final DeferredItem<Item> IRON_ROD = component("iron_rod");
    public static final DeferredItem<Item> FRAME = component("drill_frame");
    public static final DeferredItem<Item> DRIVE = component("mechanical_drive");
    public static final DeferredItem<Item> HEAD = component("drill_head");
    public static final DeferredItem<Item> MOTOR = component("electric_motor");
    public static final DeferredItem<Item> REINFORCED_HEAD = component("reinforced_drill_head");
    public static final DeferredItem<Item> MANIFOLD = component("fluid_manifold");
    public static final DeferredItem<Item> CONTROL = component("precision_control");
    public static final List<DeferredItem<Item>> COMPONENTS =
            List.of(IRON_ROD, FRAME, DRIVE, HEAD, MOTOR, REINFORCED_HEAD, MANIFOLD, CONTROL);

    private static DeferredItem<Item> component(String name) {
        return ModBlocks.ITEMS.registerSimpleItem(name);
    }

    public static void initialize() {}

    private ModAssembly() {}
}
