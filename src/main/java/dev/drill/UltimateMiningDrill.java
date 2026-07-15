package dev.drill;

import dev.OresAndDrillsMod;
import dev.registry.ModBlockEntities;
import dev.registry.ModBlocks;
import dev.registry.ModMenuTypes;
import dev.world.block.AbstractDrillBlock;
import dev.world.block.AbstractDrillPartBlock;
import dev.world.block.DrillStructure;
import dev.world.block.EnergyProfile;
import dev.world.block.MiningDrillTier;
import dev.world.block.entity.AbstractEnergyDrillBlockEntity;
import dev.world.inventory.AbstractEnergyDrillMenu;
import dev.world.item.AbstractDrillBlockItem;
import dev.client.AbstractEnergyDrillScreen;
import java.util.function.Consumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public final class UltimateMiningDrill {
    public static final MiningDrillTier TIER = new MiningDrillTier(7, 40, 64, new ItemStack(Items.NETHERITE_PICKAXE));
    public static final EnergyProfile ENERGY = new EnergyProfile(128_000, 80);

    private static final Component MENU_TITLE = Component.translatable("container.ores_and_drills.ultimate_mining_drill");

    private UltimateMiningDrill() {
    }

    private static InteractionResult openMenu(Level level, BlockPos origin, Player player) {
        if (!level.isClientSide) {
            net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(origin);
            if (!(blockEntity instanceof BlockEntity drillBlockEntity)) {
                return InteractionResult.CONSUME;
            }

            player.openMenu(new SimpleMenuProvider(
                    (containerId, playerInventory, menuPlayer) -> new Menu(
                            containerId,
                            playerInventory,
                            ContainerLevelAccess.create(level, origin),
                            drillBlockEntity.getInventory(),
                            drillBlockEntity.getPreviewContainer(),
                            drillBlockEntity
                    ),
                    MENU_TITLE
            ));
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    public static class Block extends AbstractDrillBlock {
        public static final MapCodec<Block> CODEC = simpleCodec(Block::new);

        public Block(Properties properties) {
            super(properties, ModBlocks.ULTIMATE_STRUCTURE, UltimateMiningDrill::openMenu);
        }

        @Override
        protected MapCodec<? extends BaseEntityBlock> codec() {
            return CODEC;
        }

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new BlockEntity(pos, state);
        }

        @Override
        public <T extends net.minecraft.world.level.block.entity.BlockEntity> BlockEntityTicker<T> getTicker(
                Level level, BlockState state, BlockEntityType<T> blockEntityType) {
            return createTickerHelper(blockEntityType, ModBlockEntities.ULTIMATE_MINING_DRILL.get(), BlockEntity::tick);
        }
    }

    public static class PartBlock extends AbstractDrillPartBlock {
        public static final IntegerProperty OFFSET_X = IntegerProperty.create("offset_x", 0, TIER.size() - 1);
        public static final IntegerProperty OFFSET_Y = IntegerProperty.create("offset_y", 0, TIER.size() - 1);
        public static final IntegerProperty OFFSET_Z = IntegerProperty.create("offset_z", 0, TIER.size() - 1);

        public PartBlock(Properties properties) {
            super(properties, ModBlocks.ULTIMATE_STRUCTURE, UltimateMiningDrill::openMenu);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
            builder.add(AbstractDrillBlock.FACING, OFFSET_X, OFFSET_Y, OFFSET_Z);
        }
    }

    public static class BlockEntity extends AbstractEnergyDrillBlockEntity {
        public BlockEntity(BlockPos pos, BlockState state) {
            super(ModBlockEntities.ULTIMATE_MINING_DRILL.get(), pos, state, TIER, ENERGY, ModBlocks.ULTIMATE_STRUCTURE, 70, 20_000);
        }

        public static void tick(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity) {
            tickEnergy(level, pos, state, blockEntity, ModBlocks.ULTIMATE_MINING_DRILL.get());
        }
    }

    public static class Menu extends AbstractEnergyDrillMenu {
        public Menu(int containerId, Inventory playerInventory) {
            super(ModMenuTypes.ULTIMATE_MINING_DRILL.get(), containerId, playerInventory,
                    TIER.size(), 2, 4, 134, 92, 134, 18, 18, 18, false, 8, 92, 124, 182);
        }

        public Menu(int containerId, Inventory playerInventory, ContainerLevelAccess access,
                    Container outputContainer, Container previewContainer, AbstractEnergyDrillBlockEntity blockEntity) {
            super(ModMenuTypes.ULTIMATE_MINING_DRILL.get(), containerId, playerInventory, access,
                    outputContainer, previewContainer, blockEntity,
                    2, 4, 134, 92, 134, 18, 18, 18, false, 8, 92, 124, 182);
        }

        @Override
        protected net.minecraft.world.level.block.Block blockForValidation() {
            return ModBlocks.ULTIMATE_MINING_DRILL.get();
        }

        @Override
        protected DrillStructure structureForValidation() {
            return ModBlocks.ULTIMATE_STRUCTURE;
        }
    }

    public static class Item extends AbstractDrillBlockItem {
        public Item(net.minecraft.world.level.block.Block block, net.minecraft.world.item.Item.Properties properties) {
            super(block, properties);
        }

        @Override
        public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
            consumer.accept(new GeoRenderProvider() {
                private ItemRenderer renderer;

                @Override
                public ItemRenderer getGeoItemRenderer() {
                    if (renderer == null) {
                        renderer = new ItemRenderer();
                    }

                    return renderer;
                }
            });
        }
    }

    public static class Screen extends AbstractEnergyDrillScreen {
        private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
                OresAndDrillsMod.MOD_ID, "textures/gui/electric_drill_gui_2.png");
        private static final ResourceLocation ENERGY_PROGRESS_TEXTURE = ResourceLocation.fromNamespaceAndPath(
                OresAndDrillsMod.MOD_ID, "textures/gui/energy_bar_2.png");
        private static final ResourceLocation MINING_PROGRESS_TEXTURE = ResourceLocation.fromNamespaceAndPath(
                OresAndDrillsMod.MOD_ID, "textures/gui/progress_2.png");
        private static final ResourceLocation PRODUCTIVITY_PROGRESS_TEXTURE = ResourceLocation.fromNamespaceAndPath(
                OresAndDrillsMod.MOD_ID, "textures/gui/productivity.png");

        public Screen(AbstractEnergyDrillMenu menu, Inventory playerInventory, Component title) {
            super(menu, playerInventory, title,
                    TEXTURE, 176, 206,
                    ENERGY_PROGRESS_TEXTURE, 10, 18, 12, 70,
                    MINING_PROGRESS_TEXTURE, 64, 94, 64, 3,
                    true, PRODUCTIVITY_PROGRESS_TEXTURE, 64, 101, 64, 3,
                    28, 18, 46, 18, 14, 90,
                    73, 21,
                    256, 256, 113);
        }
    }

    public static class Model extends GeoModel<BlockEntity> {
        @Override
        public ResourceLocation getModelResource(BlockEntity animatable) {
            return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "geo/ultimate_mining_drill.geo.json");
        }

        @Override
        public ResourceLocation getTextureResource(BlockEntity animatable) {
            return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "textures/block/burner_mining_drill.png");
        }

        @Override
        public ResourceLocation getAnimationResource(BlockEntity animatable) {
            return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "animations/ultimate_mining_drill.animation.json");
        }
    }

    public static class Renderer extends GeoBlockRenderer<BlockEntity> {
        private static final double CENTER_FORWARD_OFFSET = 0D;
        private static final double CENTER_LEFT_OFFSET = 0D;
        private static final float MODEL_YAW_DEGREES = 180.0F;

        public Renderer() {
            super(new Model());
        }

        @Override
        public RenderType getRenderType(BlockEntity animatable, ResourceLocation texture, MultiBufferSource bufferSource, float partialTick) {
            return RenderType.entityTranslucent(texture);
        }

        @Override
        public void render(BlockEntity animatable, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
                           int packedLight, int packedOverlay) {
            Direction facing = animatable.getBlockState().getValue(AbstractDrillBlock.FACING);
            Direction right = facing.getClockWise();

            double offsetX = facing.getStepX() * CENTER_FORWARD_OFFSET - right.getStepX() * CENTER_LEFT_OFFSET;
            double offsetZ = facing.getStepZ() * CENTER_FORWARD_OFFSET - right.getStepZ() * CENTER_LEFT_OFFSET;

            poseStack.pushPose();
            poseStack.translate(offsetX, 0.0D, offsetZ);
            super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
            poseStack.popPose();
        }

        @Override
        public void preRender(PoseStack poseStack, BlockEntity animatable, BakedGeoModel model, MultiBufferSource bufferSource,
                              VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
            super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
            poseStack.mulPose(Axis.YP.rotationDegrees(MODEL_YAW_DEGREES));
        }
    }

    public static class ItemModel extends GeoModel<Item> {
        @Override
        public ResourceLocation getModelResource(Item animatable) {
            return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "geo/ultimate_mining_drill.geo.json");
        }

        @Override
        public ResourceLocation getTextureResource(Item animatable) {
            return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "textures/block/burner_mining_drill.png");
        }

        @Override
        public ResourceLocation getAnimationResource(Item animatable) {
            return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "animations/ultimate_mining_drill.animation.json");
        }
    }

    public static class ItemRenderer extends GeoItemRenderer<Item> {
        private static final float MODEL_YAW_DEGREES = 180.0F;

        public ItemRenderer() {
            super(new ItemModel());
        }

        @Override
        public void preRender(PoseStack poseStack, Item animatable, BakedGeoModel model, MultiBufferSource bufferSource,
                              VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
            super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
            poseStack.mulPose(Axis.YP.rotationDegrees(MODEL_YAW_DEGREES));
        }
    }
}
