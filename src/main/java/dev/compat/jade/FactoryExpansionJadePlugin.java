package dev.compat.jade;

import dev.OresAndDrillsMod;
import dev.registry.ModBlocks;
import dev.world.block.AbstractDrillBlock;
import dev.world.block.AbstractDrillPartBlock;
import dev.world.block.OreDepositBlock;
import dev.world.block.entity.AbstractDrillBlockEntity;
import dev.world.block.entity.AbstractEnergyDrillBlockEntity;
import dev.world.block.entity.DrillStatusProvider;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.Accessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.JadeIds;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;
import snownee.jade.api.view.EnergyView;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ViewGroup;
import snownee.jade.util.JadeForgeUtils;

import java.util.ArrayList;
import java.util.List;

@WailaPlugin(OresAndDrillsMod.MOD_ID)
public class FactoryExpansionJadePlugin implements IWailaPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
            OresAndDrillsMod.MOD_ID,
            "mining_drill"
    );
    private static final String ACTIVE_KEY = "Active";
    private static final String MINING_PROGRESS_KEY = "MiningProgress";
    private static final String MINING_DURATION_KEY = "MiningDuration";
    private static final String PRODUCTIVITY_PERCENT_KEY = "ProductivityPercent";
    private static final String PRODUCTIVITY_PROGRESS_KEY = "ProductivityProgress";

    private static final ResourceLocation ORE_DEPOSIT_UID = ResourceLocation.fromNamespaceAndPath(
            OresAndDrillsMod.MOD_ID,
            "ore_deposit"
    );
    private static final String ORE_BLOCK_KEY = "OreBlock";
    private static final String ORE_REMAINING_KEY = "OreRemaining";
    private static final String ORE_INITIAL_KEY = "OreInitial";

    private static final MiningDrillProvider PROVIDER = new MiningDrillProvider();
    private static final OreDepositProvider ORE_DEPOSIT_PROVIDER = new OreDepositProvider();
    private static final DrillPartEnergyProvider DRILL_PART_ENERGY_PROVIDER = new DrillPartEnergyProvider();
    private static final DrillPartFluidProvider DRILL_PART_FLUID_PROVIDER = new DrillPartFluidProvider();

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(PROVIDER, AbstractDrillBlock.class);
        registration.registerBlockDataProvider(PROVIDER, AbstractDrillPartBlock.class);
        registration.registerEnergyStorage(DRILL_PART_ENERGY_PROVIDER, AbstractDrillPartBlock.class);
        registration.registerFluidStorage(DRILL_PART_FLUID_PROVIDER, AbstractDrillPartBlock.class);
        registration.registerBlockDataProvider(ORE_DEPOSIT_PROVIDER, OreDepositBlock.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(PROVIDER, AbstractDrillBlock.class);
        registration.registerBlockComponent(PROVIDER, AbstractDrillPartBlock.class);
        registration.registerBlockIcon(PROVIDER, AbstractDrillPartBlock.class);
        registration.registerBlockComponent(ORE_DEPOSIT_PROVIDER, OreDepositBlock.class);
        registration.usePickedResult(ModBlocks.ORE_DEPOSIT.get());
    }

    private static class MiningDrillProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        @Override
        public IElement getIcon(BlockAccessor accessor, IPluginConfig config, IElement currentIcon) {
            if (accessor.getBlock() instanceof AbstractDrillPartBlock drillPart) {
                return IElementHelper.get().item(drillPart.structure().cloneItemStack());
            }
            return currentIcon;
        }

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
            // The real harvest requirement belongs to the ore stored in the chunk attachment, not to
            // the shared ore_deposit block state. Remove Jade's generic (and therefore misleading)
            // indicator before replacing it with the attachment-aware one below.
            tooltip.remove(JadeIds.MC_HARVEST_TOOL);

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
                ItemStack requiredTool = minimumTool(oreState);
                appendHarvestIndicator(tooltip, accessor, oreState, requiredTool);
            }
        }

        /**
         * The ore_deposit block itself has no per-position tool-tier metadata (that lives in the chunk
         * attachment), so Jade's own generic harvestability indicator can't reflect the real requirement
         * and always shows the loosest tier. This checks the actual stored ore's tags directly instead.
         */
        private static ItemStack minimumTool(BlockState oreState) {
            if (oreState.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
                return new ItemStack(Items.DIAMOND_PICKAXE);
            }
            if (oreState.is(BlockTags.NEEDS_IRON_TOOL)) {
                return new ItemStack(Items.IRON_PICKAXE);
            }
            if (oreState.is(BlockTags.NEEDS_STONE_TOOL)) {
                return new ItemStack(Items.STONE_PICKAXE);
            }
            return new ItemStack(Items.WOODEN_PICKAXE);
        }

        private static void appendHarvestIndicator(
                ITooltip tooltip,
                BlockAccessor accessor,
                BlockState oreState,
                ItemStack requiredTool
        ) {
            IElementHelper helper = IElementHelper.get();
            List<IElement> indicator = new ArrayList<>();
            int verticalOffset = -3;

            indicator.add(helper.item(requiredTool, 0.75F)
                    .translate(new Vec2(-1.0F, verticalOffset))
                    .size(new Vec2(10.0F, 0.0F))
                    .message(null));

            boolean canHarvest = oreState.canHarvestBlock(
                    accessor.getLevel(),
                    accessor.getPosition(),
                    accessor.getPlayer()
            );
            Component mark = Component.literal(canHarvest ? "\u2714" : "\u2715")
                    .withStyle(canHarvest ? ChatFormatting.GREEN : ChatFormatting.RED);
            indicator.add(helper.text(mark)
                    .scale(0.75F)
                    .zOffset(800)
                    .size(Vec2.ZERO)
                    .translate(new Vec2(-3.0F, 6.25F + verticalOffset))
                    .message(null));

            indicator.forEach(element -> element.align(IElement.Align.RIGHT));
            tooltip.append(0, indicator);
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

    /**
     * A drill part has no block entity of its own, while Jade's built-in providers inspect the block
     * currently under the crosshair. Forward the controller's storage through the exact data format
     * consumed by Jade's normal universal energy widget instead of maintaining a second FE tooltip.
     */
    private static class DrillPartEnergyProvider implements IServerExtensionProvider<CompoundTag> {
        @Override
        public List<ViewGroup<CompoundTag>> getGroups(Accessor<?> accessor) {
            AbstractEnergyDrillBlockEntity drill = resolveEnergyDrill(accessor);
            if (drill == null) {
                return List.of();
            }

            ViewGroup<CompoundTag> group = new ViewGroup<>(List.of(EnergyView.of(
                    drill.getEnergyStorage().getEnergyStored(),
                    drill.getEnergyStorage().getMaxEnergyStored()
            )));
            group.getExtraData().putString("Unit", "FE");
            return List.of(group);
        }

        @Override
        public boolean shouldRequestData(Accessor<?> accessor) {
            return resolveEnergyDrill(accessor) != null;
        }

        @Override
        public ResourceLocation getUid() {
            return JadeIds.UNIVERSAL_ENERGY_STORAGE_DEFAULT;
        }
    }

    /**
     * Uses Jade's own fluid serializer, so all standard Jade settings and rendering stay intact while
     * the data source is the controller rather than the part that was targeted.
     */
    private static class DrillPartFluidProvider implements IServerExtensionProvider<CompoundTag> {
        @Override
        public List<ViewGroup<CompoundTag>> getGroups(Accessor<?> accessor) {
            AbstractEnergyDrillBlockEntity drill = resolveEnergyDrill(accessor);
            return drill == null ? List.of() : JadeForgeUtils.fromFluidHandler(drill.getFluidHandler());
        }

        @Override
        public boolean shouldRequestData(Accessor<?> accessor) {
            return resolveEnergyDrill(accessor) != null;
        }

        @Override
        public ResourceLocation getUid() {
            return JadeIds.UNIVERSAL_FLUID_STORAGE_DEFAULT;
        }
    }

    private static AbstractEnergyDrillBlockEntity resolveEnergyDrill(Accessor<?> accessor) {
        return accessor instanceof BlockAccessor blockAccessor
                && resolveOrigin(blockAccessor) instanceof AbstractEnergyDrillBlockEntity drill
                ? drill
                : null;
    }

    private static BlockEntity resolveOrigin(BlockAccessor accessor) {
        BlockEntity blockEntity = accessor.getBlockEntity();
        if (blockEntity instanceof AbstractDrillBlockEntity) {
            return blockEntity;
        }

        BlockState state = accessor.getBlockState();
        if (state.getBlock() instanceof AbstractDrillPartBlock drillPart
                && state.hasProperty(AbstractDrillBlock.FACING)) {
            var origin = drillPart.structure().originFromPart(accessor.getPosition(), state);
            BlockState originState = accessor.getLevel().getBlockState(origin);
            if (drillPart.structure().isMainBlock(originState)) {
                return accessor.getLevel().getBlockEntity(origin);
            }
        }

        return null;
    }
}
