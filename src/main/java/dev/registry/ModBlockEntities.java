package dev.registry;

import dev.OresAndDrillsMod;
import dev.drill.AdvancedMiningDrill;
import dev.drill.BurnerMiningDrill;
import dev.drill.ElectricMiningDrill;
import dev.drill.UltimateMiningDrill;
import dev.world.block.entity.DrillPartBlockEntity;
import dev.world.geology.exploration.ExplorationDrillBlockEntity;
import dev.world.geology.station.SeismicStationBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, OresAndDrillsMod.MOD_ID);

    public static final DeferredHolder<
                    BlockEntityType<?>, BlockEntityType<BurnerMiningDrill.BlockEntity>>
            BURNER_MINING_DRILL =
                    BLOCK_ENTITY_TYPES.register(
                            "burner_mining_drill",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    BurnerMiningDrill.BlockEntity::new,
                                                    ModBlocks.BURNER_MINING_DRILL.get())
                                            .build(null));

    public static final DeferredHolder<
                    BlockEntityType<?>, BlockEntityType<ElectricMiningDrill.BlockEntity>>
            ELECTRIC_MINING_DRILL =
                    BLOCK_ENTITY_TYPES.register(
                            "electric_mining_drill",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    ElectricMiningDrill.BlockEntity::new,
                                                    ModBlocks.ELECTRIC_MINING_DRILL.get())
                                            .build(null));

    public static final DeferredHolder<
                    BlockEntityType<?>, BlockEntityType<AdvancedMiningDrill.BlockEntity>>
            ADVANCED_MINING_DRILL =
                    BLOCK_ENTITY_TYPES.register(
                            "advanced_mining_drill",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    AdvancedMiningDrill.BlockEntity::new,
                                                    ModBlocks.ADVANCED_MINING_DRILL.get())
                                            .build(null));

    public static final DeferredHolder<
                    BlockEntityType<?>, BlockEntityType<UltimateMiningDrill.BlockEntity>>
            ULTIMATE_MINING_DRILL =
                    BLOCK_ENTITY_TYPES.register(
                            "ultimate_mining_drill",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    UltimateMiningDrill.BlockEntity::new,
                                                    ModBlocks.ULTIMATE_MINING_DRILL.get())
                                            .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DrillPartBlockEntity>>
            DRILL_PART =
                    BLOCK_ENTITY_TYPES.register(
                            "drill_part",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    DrillPartBlockEntity::new,
                                                    ModBlocks.BURNER_MINING_DRILL_PART.get(),
                                                    ModBlocks.ELECTRIC_MINING_DRILL_PART.get(),
                                                    ModBlocks.ADVANCED_MINING_DRILL_PART.get(),
                                                    ModBlocks.ULTIMATE_MINING_DRILL_PART.get())
                                            .build(null));

    public static final DeferredHolder<
                    BlockEntityType<?>, BlockEntityType<SeismicStationBlockEntity>>
            SEISMIC_STATION =
                    BLOCK_ENTITY_TYPES.register(
                            "seismic_survey_station",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    SeismicStationBlockEntity::new,
                                                    ModBlocks.SEISMIC_STATION.get())
                                            .build(null));

    public static final DeferredHolder<
                    BlockEntityType<?>, BlockEntityType<ExplorationDrillBlockEntity>>
            EXPLORATION_DRILL =
                    BLOCK_ENTITY_TYPES.register(
                            "exploration_drill",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    ExplorationDrillBlockEntity::new,
                                                    ModBlocks.EXPLORATION_DRILL.get())
                                            .build(null));

    private ModBlockEntities() {}
}
