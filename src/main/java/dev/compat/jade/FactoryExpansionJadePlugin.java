package dev.compat.jade;

import dev.FactoryExpansionMod;
import dev.world.block.AbstractDrillBlock;
import dev.world.block.AbstractDrillPartBlock;
import dev.world.block.OreDepositBlock;
import dev.world.block.entity.AbstractDrillBlockEntity;
import dev.world.block.entity.AbstractEnergyDrillBlockEntity;
import dev.world.block.entity.DrillStatusProvider;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.api.ui.IElementHelper;

@WailaPlugin(FactoryExpansionMod.MOD_ID)
public class FactoryExpansionJadePlugin implements IWailaPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
            FactoryExpansionMod.MOD_ID,
            "mining_drill"
    );
    private static final String ACTIVE_KEY = "Active";
    private static final String MINING_PROGRESS_KEY = "MiningProgress";
    private static final String MINING_DURATION_KEY = "MiningDuration";
    private static final String ENERGY_STORED_KEY = "EnergyStored";
    private static final String ENERGY_CAPACITY_KEY = "EnergyCapacity";
    private static final String PRODUCTIVITY_PERCENT_KEY = "ProductivityPercent";
    private static final String PRODUCTIVITY_PROGRESS_KEY = "ProductivityProgress";

    private static final ResourceLocation ORE_DEPOSIT_UID = ResourceLocation.fromNamespaceAndPath(
            FactoryExpansionMod.MOD_ID,
            "ore_deposit"
    );
    private static final String ORE_BLOCK_KEY = "OreBlock";
    private static final String ORE_REMAINING_KEY = "OreRemaining";
    private static final String ORE_INITIAL_KEY = "OreInitial";

    private static final MiningDrillProvider PROVIDER = new MiningDrillProvider();
    private static final OreDepositProvider ORE_DEPOSIT_PROVIDER = new OreDepositProvider();

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(PROVIDER, AbstractDrillBlock.class);
        registration.registerBlockDataProvider(PROVIDER, AbstractDrillPartBlock.class);
        registration.registerBlockDataProvider(ORE_DEPOSIT_PROVIDER, OreDepositBlock.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(PROVIDER, AbstractDrillBlock.class);
        registration.registerBlockComponent(PROVIDER, AbstractDrillPartBlock.class);
        registration.registerBlockComponent(ORE_DEPOSIT_PROVIDER, OreDepositBlock.class);
    }

    private static class MiningDrillProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (!data.contains(MINING_DURATION_KEY)) {
                return;
            }

            boolean active = data.getBoolean(ACTIVE_KEY);
            tooltip.add(Component.translatable(
                    "tooltip.ores_and_drills.mining_drill.state",
                    Component.translatable(active
                            ? "tooltip.ores_and_drills.mining_drill.active"
                            : "tooltip.ores_and_drills.mining_drill.paused")
            ));

            int miningProgress = data.getInt(MINING_PROGRESS_KEY);
            int miningDuration = data.getInt(MINING_DURATION_KEY);
            tooltip.add(Component.translatable(
                    "tooltip.ores_and_drills.mining_drill.progress",
                    miningDuration > 0 ? miningProgress * 100 / miningDuration : 0
            ));

            if (accessor.getBlockState().getBlock() instanceof AbstractDrillPartBlock && data.contains(ENERGY_CAPACITY_KEY)) {
                int energyStored = data.getInt(ENERGY_STORED_KEY);
                int energyCapacity = data.getInt(ENERGY_CAPACITY_KEY);
                if (energyCapacity > 0) {
                    IElementHelper helper = IElementHelper.get();
                    tooltip.add(helper.progress(
                            Math.min(1.0F, (float) energyStored / energyCapacity),
                            Component.translatable("tooltip.ores_and_drills.mining_drill.energy", energyStored, energyCapacity),
                            helper.progressStyle().color(0xFFFF0000, 0xFF330000).textColor(0xFFFFFFFF),
                            BoxStyle.getNestedBox(),
                            true
                    ));
                }
            }

            if (data.getInt(PRODUCTIVITY_PERCENT_KEY) > 0) {
                tooltip.add(Component.translatable(
                        "tooltip.ores_and_drills.mining_drill.productivity",
                        data.getInt(PRODUCTIVITY_PROGRESS_KEY)
                ));
            }
        }

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
            BlockEntity origin = resolveOrigin(accessor);
            DrillStatusProvider status = origin instanceof DrillStatusProvider provider ? provider : null;
            if (status == null) {
                return;
            }

            tag.putBoolean(ACTIVE_KEY, status.isActive());
            tag.putInt(MINING_PROGRESS_KEY, status.getMiningProgress());
            tag.putInt(MINING_DURATION_KEY, status.getMiningDuration());

            if (origin instanceof AbstractEnergyDrillBlockEntity energyDrill) {
                tag.putInt(ENERGY_STORED_KEY, energyDrill.getEnergyStored());
                tag.putInt(ENERGY_CAPACITY_KEY, energyDrill.getEnergyCapacity());
                tag.putInt(PRODUCTIVITY_PERCENT_KEY, energyDrill.getProductivityPercent());
                tag.putInt(PRODUCTIVITY_PROGRESS_KEY, energyDrill.getVisibleProductivityProgress());
            }
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }
    }

    private static class OreDepositProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (!data.contains(ORE_BLOCK_KEY)) {
                return;
            }

            ResourceLocation oreBlockId = ResourceLocation.tryParse(data.getString(ORE_BLOCK_KEY));
            Block oreBlock = oreBlockId != null ? BuiltInRegistries.BLOCK.get(oreBlockId) : null;
            if (oreBlock == null || oreBlock == Blocks.AIR) {
                return;
            }

            ItemStack stack = new ItemStack(oreBlock.asItem());
            if (stack.isEmpty()) {
                return;
            }

            IElementHelper helper = IElementHelper.get();
            tooltip.add(helper.item(stack));
            tooltip.append(stack.getHoverName());

            int remainingOre = data.getInt(ORE_REMAINING_KEY);
            int initialOre = data.getInt(ORE_INITIAL_KEY);
            if (remainingOre > 0 && initialOre > 0) {
                tooltip.add(Component.translatable(
                        "tooltip.ores_and_drills.ore_deposit.amount",
                        remainingOre,
                        initialOre
                ));
            }

            BlockState oreState = oreBlock.defaultBlockState();
            if (oreState.requiresCorrectToolForDrops()) {
                tooltip.add(Component.translatable(
                        "tooltip.ores_and_drills.ore_deposit.requires_tool",
                        requiredToolName(oreState)
                ));
            }
        }

        /**
         * The ore_deposit block itself has no per-position tool-tier metadata (that lives in the chunk
         * attachment), so Jade's own generic harvestability indicator can't reflect the real requirement
         * and always shows the loosest tier. This checks the actual stored ore's tags directly instead.
         */
        private static Component requiredToolName(BlockState oreState) {
            if (oreState.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
                return Component.translatable("item.minecraft.diamond_pickaxe");
            }
            if (oreState.is(BlockTags.NEEDS_IRON_TOOL)) {
                return Component.translatable("item.minecraft.iron_pickaxe");
            }
            if (oreState.is(BlockTags.NEEDS_STONE_TOOL)) {
                return Component.translatable("item.minecraft.stone_pickaxe");
            }
            return Component.translatable("tooltip.ores_and_drills.ore_deposit.special_tool");
        }

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
            if (!(accessor.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }

            ResourceLocation oreBlockId = OreDepositData.oreBlockIdAt(serverLevel, accessor.getPosition());
            if (oreBlockId != null) {
                tag.putString(ORE_BLOCK_KEY, oreBlockId.toString());
            }

            OreDepositData.DepositStats stats = OreDepositData.statsAt(serverLevel, accessor.getPosition());
            if (!stats.isEmpty()) {
                tag.putInt(ORE_REMAINING_KEY, stats.remainingOre());
                tag.putInt(ORE_INITIAL_KEY, stats.initialOre());
            }
        }

        @Override
        public ResourceLocation getUid() {
            return ORE_DEPOSIT_UID;
        }
    }

    private static BlockEntity resolveOrigin(BlockAccessor accessor) {
        BlockEntity blockEntity = accessor.getBlockEntity();
        if (blockEntity instanceof AbstractDrillBlockEntity) {
            return blockEntity;
        }

        if (accessor.getBlockState().getBlock() instanceof AbstractDrillPartBlock part) {
            return accessor.getLevel().getBlockEntity(part.structure().originFromPart(accessor.getPosition(), accessor.getBlockState()));
        }

        return null;
    }
}
