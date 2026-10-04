package dev.registry;

import dev.OresAndDrillsMod;
import dev.drill.AdvancedMiningDrill;
import dev.drill.BurnerMiningDrill;
import dev.drill.ElectricMiningDrill;
import dev.drill.UltimateMiningDrill;
import dev.world.block.DrillStructure;
import dev.world.block.ExhaustedOreDepositBlock;
import dev.world.block.OreDepositBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, OresAndDrillsMod.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(OresAndDrillsMod.MOD_ID);

    private static final BlockBehaviour.Properties DRILL_PROPERTIES = BlockBehaviour.Properties.ofFullCopy(Blocks.GLASS)
            .noOcclusion()
            .sound(SoundType.METAL)
            .pushReaction(PushReaction.BLOCK)
            .requiresCorrectToolForDrops()
            .strength(5.0F, 6.0F);

    private static final BlockBehaviour.Properties ORE_DEPOSIT_PROPERTIES = BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
            .sound(SoundType.STONE)
            .requiresCorrectToolForDrops()
            .strength(3.0F, 3.0F);

    public static final DeferredHolder<Block, OreDepositBlock> ORE_DEPOSIT = BLOCKS.register(
            "ore_deposit",
            () -> new OreDepositBlock(ORE_DEPOSIT_PROPERTIES)
    );

    public static final DeferredHolder<Block, ExhaustedOreDepositBlock> EXHAUSTED_ORE_DEPOSIT = BLOCKS.register(
            "exhausted_ore_deposit",
            () -> new ExhaustedOreDepositBlock(ORE_DEPOSIT_PROPERTIES)
    );

    /**
     * Administrative/test item. It intentionally is not added to a creative tab.
     */
    public static final DeferredItem<BlockItem> ORE_DEPOSIT_ITEM = ITEMS.register(
            "ore_deposit",
            () -> new BlockItem(ORE_DEPOSIT.get(), new Item.Properties())
    );

    public static final DeferredItem<BlockItem> EXHAUSTED_ORE_DEPOSIT_ITEM = ITEMS.register(
            "exhausted_ore_deposit",
            () -> new BlockItem(EXHAUSTED_ORE_DEPOSIT.get(), new Item.Properties())
    );

    // Burner Mining Drill (fuel-powered)

    public static final DeferredHolder<Block, BurnerMiningDrill.Block> BURNER_MINING_DRILL = BLOCKS.register(
            "burner_mining_drill",
            () -> new BurnerMiningDrill.Block(DRILL_PROPERTIES)
    );

    public static final DeferredHolder<Block, BurnerMiningDrill.PartBlock> BURNER_MINING_DRILL_PART = BLOCKS.register(
            "burner_mining_drill_part",
            () -> new BurnerMiningDrill.PartBlock(DRILL_PROPERTIES)
    );

    public static final DeferredItem<BurnerMiningDrill.Item> BURNER_MINING_DRILL_ITEM = ITEMS.register(
            "burner_mining_drill",
            () -> new BurnerMiningDrill.Item(BURNER_MINING_DRILL.get(), new Item.Properties())
    );

    public static final DrillStructure BURNER_STRUCTURE = new DrillStructure(
            BurnerMiningDrill.TIER.size(), BurnerMiningDrill.TIER.size(), BurnerMiningDrill.TIER.size() - 1, 0, 0,
            BURNER_MINING_DRILL, BURNER_MINING_DRILL_PART, BURNER_MINING_DRILL_ITEM,
            BurnerMiningDrill.PartBlock.OFFSET_X, BurnerMiningDrill.PartBlock.OFFSET_Y, BurnerMiningDrill.PartBlock.OFFSET_Z,
            0.0D,
            "/assets/ores_and_drills/geo/burner_mining_drill.geo.json",
            BurnerMiningDrill.TIER
    );

    // Electric Mining Drill (FE-powered)

    public static final DeferredHolder<Block, ElectricMiningDrill.Block> ELECTRIC_MINING_DRILL = BLOCKS.register(
            "electric_mining_drill",
            () -> new ElectricMiningDrill.Block(DRILL_PROPERTIES)
    );

    public static final DeferredHolder<Block, ElectricMiningDrill.PartBlock> ELECTRIC_MINING_DRILL_PART = BLOCKS.register(
            "electric_mining_drill_part",
            () -> new ElectricMiningDrill.PartBlock(DRILL_PROPERTIES)
    );

    public static final DeferredItem<ElectricMiningDrill.Item> ELECTRIC_MINING_DRILL_ITEM = ITEMS.register(
            "electric_mining_drill",
            () -> new ElectricMiningDrill.Item(ELECTRIC_MINING_DRILL.get(), new Item.Properties())
    );

    public static final DrillStructure ELECTRIC_STRUCTURE = new DrillStructure(
            ElectricMiningDrill.TIER.size(), ElectricMiningDrill.TIER.size(), ElectricMiningDrill.TIER.size() / 2, 0, 0,
            ELECTRIC_MINING_DRILL, ELECTRIC_MINING_DRILL_PART, ELECTRIC_MINING_DRILL_ITEM,
            ElectricMiningDrill.PartBlock.OFFSET_X, ElectricMiningDrill.PartBlock.OFFSET_Y, ElectricMiningDrill.PartBlock.OFFSET_Z,
            1.0D,
            "/assets/ores_and_drills/geo/electric_mining_drill.geo.json",
            ElectricMiningDrill.TIER
    );

    // Advanced Mining Drill (FE-powered)

    public static final DeferredHolder<Block, AdvancedMiningDrill.Block> ADVANCED_MINING_DRILL = BLOCKS.register(
            "advanced_mining_drill",
            () -> new AdvancedMiningDrill.Block(DRILL_PROPERTIES)
    );

    public static final DeferredHolder<Block, AdvancedMiningDrill.PartBlock> ADVANCED_MINING_DRILL_PART = BLOCKS.register(
            "advanced_mining_drill_part",
            () -> new AdvancedMiningDrill.PartBlock(DRILL_PROPERTIES)
    );

    public static final DeferredItem<AdvancedMiningDrill.Item> ADVANCED_MINING_DRILL_ITEM = ITEMS.register(
            "advanced_mining_drill",
            () -> new AdvancedMiningDrill.Item(ADVANCED_MINING_DRILL.get(), new Item.Properties())
    );

    public static final DrillStructure ADVANCED_STRUCTURE = new DrillStructure(
            AdvancedMiningDrill.TIER.size(), AdvancedMiningDrill.TIER.size(), AdvancedMiningDrill.TIER.size() / 2, 0, 0,
            ADVANCED_MINING_DRILL, ADVANCED_MINING_DRILL_PART, ADVANCED_MINING_DRILL_ITEM,
            AdvancedMiningDrill.PartBlock.OFFSET_X, AdvancedMiningDrill.PartBlock.OFFSET_Y, AdvancedMiningDrill.PartBlock.OFFSET_Z,
            2.0D,
            "/assets/ores_and_drills/geo/advanced_mining_drill.geo.json",
            AdvancedMiningDrill.TIER
    );

    // Ultimate Mining Drill (FE-powered)

    public static final DeferredHolder<Block, UltimateMiningDrill.Block> ULTIMATE_MINING_DRILL = BLOCKS.register(
            "ultimate_mining_drill",
            () -> new UltimateMiningDrill.Block(DRILL_PROPERTIES)
    );

    public static final DeferredHolder<Block, UltimateMiningDrill.PartBlock> ULTIMATE_MINING_DRILL_PART = BLOCKS.register(
            "ultimate_mining_drill_part",
            () -> new UltimateMiningDrill.PartBlock(DRILL_PROPERTIES)
    );

    public static final DeferredItem<UltimateMiningDrill.Item> ULTIMATE_MINING_DRILL_ITEM = ITEMS.register(
            "ultimate_mining_drill",
            () -> new UltimateMiningDrill.Item(ULTIMATE_MINING_DRILL.get(), new Item.Properties())
    );

    public static final DrillStructure ULTIMATE_STRUCTURE = new DrillStructure(
            UltimateMiningDrill.TIER.size(), UltimateMiningDrill.STRUCTURE_HEIGHT,
            UltimateMiningDrill.TIER.size() / 2, 0, 0,
            ULTIMATE_MINING_DRILL, ULTIMATE_MINING_DRILL_PART, ULTIMATE_MINING_DRILL_ITEM,
            UltimateMiningDrill.PartBlock.OFFSET_X, UltimateMiningDrill.PartBlock.OFFSET_Y, UltimateMiningDrill.PartBlock.OFFSET_Z,
            3.0D,
            "/assets/ores_and_drills/geo/ultimate_mining_drill.geo.json",
            UltimateMiningDrill.TIER
    );

    private ModBlocks() {
    }
}
