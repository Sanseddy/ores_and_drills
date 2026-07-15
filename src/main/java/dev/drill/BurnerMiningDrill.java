package dev.drill;

import dev.OresAndDrillsMod;
import dev.registry.ModBlockEntities;
import dev.registry.ModBlocks;
import dev.registry.ModMenuTypes;
import dev.world.block.AbstractDrillBlock;
import dev.world.block.AbstractDrillPartBlock;
import dev.world.inventory.DrillMenuValidity;
import dev.world.block.MiningDrillTier;
import dev.world.block.entity.AbstractDrillBlockEntity;
import dev.world.block.entity.drill.FuelBurner;
import dev.world.item.AbstractDrillBlockItem;
import dev.util.ProgressMath;
import java.util.function.Consumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
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

public final class BurnerMiningDrill {
    public static final MiningDrillTier TIER = new MiningDrillTier(2, 60, 2, new ItemStack(Items.STONE_PICKAXE));

    private static final Component MENU_TITLE = Component.translatable("container.ores_and_drills.burner_mining_drill");

    private BurnerMiningDrill() {
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
            super(properties, ModBlocks.BURNER_STRUCTURE, BurnerMiningDrill::openMenu);
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
            return createTickerHelper(blockEntityType, ModBlockEntities.BURNER_MINING_DRILL.get(), BlockEntity::tick);
        }
    }

    public static class PartBlock extends AbstractDrillPartBlock {
        public static final IntegerProperty OFFSET_X = IntegerProperty.create("offset_x", 0, TIER.size() - 1);
        public static final IntegerProperty OFFSET_Y = IntegerProperty.create("offset_y", 0, TIER.size() - 1);
        public static final IntegerProperty OFFSET_Z = IntegerProperty.create("offset_z", 0, TIER.size() - 1);

        public PartBlock(Properties properties) {
            super(properties, ModBlocks.BURNER_STRUCTURE, BurnerMiningDrill::openMenu);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
            builder.add(AbstractDrillBlock.FACING, OFFSET_X, OFFSET_Y, OFFSET_Z);
        }
    }

    public static class BlockEntity extends AbstractDrillBlockEntity {
        private static final int FUEL_SLOT = 0;
        private static final int OUTPUT_SLOT = 1;

        private final FuelBurner fuelBurner = new FuelBurner();

        public BlockEntity(BlockPos pos, BlockState blockState) {
            super(ModBlockEntities.BURNER_MINING_DRILL.get(), pos, blockState, TIER, ModBlocks.BURNER_STRUCTURE, 2, OUTPUT_SLOT);
        }

        @Override
        protected boolean canPlaceItem(int slot, ItemStack stack) {
            return slot == FUEL_SLOT && AbstractFurnaceBlockEntity.isFuel(stack);
        }

        public int getFuelTime() {
            return fuelBurner.fuelTime();
        }

        public void setFuelTime(int fuelTime) {
            fuelBurner.setFuelTime(fuelTime);
        }

        public int getFuelDuration() {
            return fuelBurner.fuelDuration();
        }

        public void setFuelDuration(int fuelDuration) {
            fuelBurner.setFuelDuration(fuelDuration);
        }

        public static void tick(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity) {
            if (level.isClientSide || !state.is(ModBlocks.BURNER_MINING_DRILL.get())) {
                return;
            }

            ServerLevel serverLevel = (ServerLevel) level;
            Direction facing = state.getValue(AbstractDrillBlock.FACING);
            ScanResult scan = scanAndPreview(serverLevel, pos, facing, blockEntity);
            boolean canMine = !scan.mineable().isEmpty();
            boolean outputReady = canOutputTargets(blockEntity, scan.mineable());
            boolean changed = scan.changed();
            FuelBurner fuelBurner = blockEntity.fuelBurner;

            if (!fuelBurner.isBurning() && canMine && outputReady) {
                changed |= fuelBurner.consume(blockEntity.inventory.getItem(FUEL_SLOT));
            } else if (!fuelBurner.isBurning()) {
                changed |= fuelBurner.clearIfIdle();
            }

            boolean wasBurning = fuelBurner.isBurning();
            boolean poweredAndMining = wasBurning && canMine && outputReady;
            if (poweredAndMining) {
                fuelBurner.tickDown();
            }

            changed |= advanceMining(blockEntity, level, poweredAndMining, scan.mineable());
            updateActiveAndCheckStructure(blockEntity, level, pos, facing, poweredAndMining);

            if (changed) {
                blockEntity.setChanged();
            }
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            if (!tag.contains("Inventory", Tag.TAG_COMPOUND) && tag.contains("Fuel", Tag.TAG_LIST)) {
                inventory.fromTag(tag.getList("Fuel", Tag.TAG_COMPOUND), registries);
            }
            fuelBurner.load(tag);
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            fuelBurner.save(tag);
        }
    }

    public static class Menu extends AbstractContainerMenu {
        private static final int FUEL_SLOT = 0;
        private static final int OUTPUT_SLOT = 1;
        private static final int PREVIEW_START = 2;
        private static final int PREVIEW_END = 8;
        private static final int PLAYER_INVENTORY_START = 8;
        private static final int PLAYER_INVENTORY_END = 35;
        private static final int HOTBAR_START = 35;
        private static final int HOTBAR_END = 44;

        private final ContainerLevelAccess access;
        private final int[] clientData = new int[5];

        public Menu(int containerId, Inventory playerInventory) {
            this(containerId, playerInventory, ContainerLevelAccess.NULL, new SimpleContainer(2), new SimpleContainer(6), null);
        }

        public Menu(int containerId, Inventory playerInventory, ContainerLevelAccess access,
                    Container fuelContainer, Container previewContainer, BlockEntity blockEntity) {
            super(ModMenuTypes.BURNER_MINING_DRILL.get(), containerId);
            this.access = access;

            addSlot(new FuelSlot(fuelContainer, 0, 26, 74));
            addSlot(new OutputSlot(fuelContainer, 1, 134, 74));
            addSlot(new PreviewSlot(previewContainer, 0, 134, 18));
            addSlot(new PreviewSlot(previewContainer, 1, 152, 18));
            addSlot(new PreviewSlot(previewContainer, 2, 134, 36));
            addSlot(new PreviewSlot(previewContainer, 3, 152, 36));
            addSlot(new PreviewSlot(previewContainer, 4, 134, 54));
            addSlot(new PreviewSlot(previewContainer, 5, 152, 54));
            addBurnerDrillDataSlots(blockEntity);

            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 9; column++) {
                    addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, 106 + row * 18));
                }
            }

            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column, 8 + column * 18, 164));
            }
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            ItemStack result = ItemStack.EMPTY;
            if (index >= PREVIEW_START && index < PREVIEW_END) {
                return result;
            }

            Slot slot = slots.get(index);

            if (slot.hasItem()) {
                ItemStack stack = slot.getItem();
                result = stack.copy();

                if (index == FUEL_SLOT) {
                    if (!moveItemStackTo(stack, PLAYER_INVENTORY_START, HOTBAR_END, true)) {
                        return ItemStack.EMPTY;
                    }
                } else if (index == OUTPUT_SLOT) {
                    if (!moveItemStackTo(stack, PLAYER_INVENTORY_START, HOTBAR_END, true)) {
                        return ItemStack.EMPTY;
                    }
                } else if (AbstractFurnaceBlockEntity.isFuel(stack)) {
                    if (!moveItemStackTo(stack, FUEL_SLOT, FUEL_SLOT + 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (index < HOTBAR_START) {
                    if (!moveItemStackTo(stack, HOTBAR_START, HOTBAR_END, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (!moveItemStackTo(stack, PLAYER_INVENTORY_START, PLAYER_INVENTORY_END, false)) {
                    return ItemStack.EMPTY;
                }

                if (stack.isEmpty()) {
                    slot.setByPlayer(ItemStack.EMPTY);
                } else {
                    slot.setChanged();
                }
            }

            return result;
        }

        @Override
        public boolean stillValid(Player player) {
            return DrillMenuValidity.stillValid(access, player, ModBlocks.BURNER_MINING_DRILL.get(), ModBlocks.BURNER_STRUCTURE);
        }

        public int getFuelProgressHeight() {
            return ProgressMath.scaled(getFuelTime(), getFuelDuration(), 14);
        }

        public int getMiningProgressHeight() {
            return ProgressMath.scaled(getMiningProgress(), getMiningDuration(), 72);
        }

        public int getMiningProgressPercent() {
            return ProgressMath.percent(getMiningProgress(), getMiningDuration());
        }

        private int getFuelTime() {
            return clientData[0];
        }

        private int getFuelDuration() {
            return clientData[1];
        }

        private int getMiningProgress() {
            return clientData[2];
        }

        private int getMiningDuration() {
            return clientData[3];
        }

        public int getAnimationFrame() {
            return clientData[4];
        }

        private void addBurnerDrillDataSlots(BlockEntity blockEntity) {
            if (blockEntity == null) {
                addDataSlot(DataSlot.shared(clientData, 0));
                addDataSlot(DataSlot.shared(clientData, 1));
                addDataSlot(DataSlot.shared(clientData, 2));
                addDataSlot(DataSlot.shared(clientData, 3));
                addDataSlot(DataSlot.shared(clientData, 4));
                return;
            }

            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return blockEntity.getFuelTime();
                }

                @Override
                public void set(int value) {
                    blockEntity.setFuelTime(value);
                    clientData[0] = value;
                }
            });
            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return blockEntity.getFuelDuration();
                }

                @Override
                public void set(int value) {
                    blockEntity.setFuelDuration(value);
                    clientData[1] = value;
                }
            });
            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return blockEntity.getMiningProgress();
                }

                @Override
                public void set(int value) {
                    blockEntity.setMiningProgress(value);
                    clientData[2] = value;
                }
            });
            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return blockEntity.getMiningDuration();
                }

                @Override
                public void set(int value) {
                    clientData[3] = value;
                }
            });
            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return blockEntity.getGuiAnimationFrame();
                }

                @Override
                public void set(int value) {
                    clientData[4] = value;
                }
            });
        }

        private static class FuelSlot extends Slot {
            FuelSlot(Container container, int slot, int x, int y) {
                super(container, slot, x, y);
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return AbstractFurnaceBlockEntity.isFuel(stack);
            }
        }

        private static class OutputSlot extends Slot {
            OutputSlot(Container container, int slot, int x, int y) {
                super(container, slot, x, y);
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        }

        private static class PreviewSlot extends Slot {
            PreviewSlot(Container container, int slot, int x, int y) {
                super(container, slot, x, y);
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return false;
            }

            @Override
            public boolean isHighlightable() {
                return false;
            }
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

    public static class Screen extends AbstractContainerScreen<Menu> {
        private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
                OresAndDrillsMod.MOD_ID, "textures/gui/burner_drill_gui.png");
        private static final ResourceLocation FUEL_PROGRESS_TEXTURE = ResourceLocation.fromNamespaceAndPath(
                OresAndDrillsMod.MOD_ID, "textures/gui/burner_drill_fuel_progress.png");
        private static final ResourceLocation MINING_PROGRESS_TEXTURE = ResourceLocation.fromNamespaceAndPath(
                OresAndDrillsMod.MOD_ID, "textures/gui/progress_1.png");
        private static final ResourceLocation DRILL_ICON_TEXTURE = ResourceLocation.fromNamespaceAndPath(
                OresAndDrillsMod.MOD_ID, "textures/gui/drill_gui.png");
        private static final int GUI_WIDTH = 176;
        private static final int GUI_HEIGHT = 188;
        private static final int FUEL_PROGRESS_X = 26;
        private static final int FUEL_PROGRESS_Y = 57;
        private static final int FUEL_PROGRESS_SIZE = 14;
        private static final int MINING_PROGRESS_X = 124;
        private static final int MINING_PROGRESS_Y = 18;
        private static final int MINING_PROGRESS_WIDTH = 3;
        private static final int MINING_PROGRESS_HEIGHT = 72;
        private static final int DRILL_ICON_X = 65;
        private static final int DRILL_ICON_Y = 21;
        private static final int DRILL_ICON_WIDTH = 46;
        private static final int DRILL_ICON_HEIGHT = 66;
        private static final int DRILL_ICON_SHEET_HEIGHT = DRILL_ICON_HEIGHT * AbstractDrillBlockEntity.GUI_ANIMATION_FRAME_COUNT;
        private static final int TEXTURE_WIDTH = 256;
        private static final int TEXTURE_HEIGHT = 256;
        private static final String MINING_PROGRESS_TOOLTIP_KEY = "tooltip.ores_and_drills.mining_drill.progress";

        public Screen(Menu menu, Inventory playerInventory, Component title) {
            super(menu, playerInventory, title);
            imageWidth = GUI_WIDTH;
            imageHeight = GUI_HEIGHT;
        }

        @Override
        public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            if (isHovering(MINING_PROGRESS_X, MINING_PROGRESS_Y, MINING_PROGRESS_WIDTH, MINING_PROGRESS_HEIGHT, mouseX, mouseY)) {
                guiGraphics.renderTooltip(
                        font,
                        Component.translatable(MINING_PROGRESS_TOOLTIP_KEY, menu.getMiningProgressPercent())
                                .withStyle(style -> style.withItalic(false)),
                        mouseX,
                        mouseY
                );
            }
            renderTooltip(guiGraphics, mouseX, mouseY);
        }

        @Override
        protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
            guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, TEXTURE_WIDTH, TEXTURE_HEIGHT);
            int fuelProgressHeight = menu.getFuelProgressHeight();
            if (fuelProgressHeight > 0) {
                int offset = FUEL_PROGRESS_SIZE - fuelProgressHeight;
                guiGraphics.blit(
                        FUEL_PROGRESS_TEXTURE,
                        leftPos + FUEL_PROGRESS_X,
                        topPos + FUEL_PROGRESS_Y + offset,
                        0,
                        offset,
                        FUEL_PROGRESS_SIZE,
                        fuelProgressHeight,
                        FUEL_PROGRESS_SIZE,
                        FUEL_PROGRESS_SIZE
                );
            }

            int miningProgressHeight = menu.getMiningProgressHeight();
            if (miningProgressHeight > 0) {
                int offset = MINING_PROGRESS_HEIGHT - miningProgressHeight;
                guiGraphics.blit(
                        MINING_PROGRESS_TEXTURE,
                        leftPos + MINING_PROGRESS_X,
                        topPos + MINING_PROGRESS_Y + offset,
                        0,
                        offset,
                        MINING_PROGRESS_WIDTH,
                        miningProgressHeight,
                        MINING_PROGRESS_WIDTH,
                        MINING_PROGRESS_HEIGHT
                );
            }

            int frame = menu.getAnimationFrame();
            guiGraphics.blit(
                    DRILL_ICON_TEXTURE,
                    leftPos + DRILL_ICON_X,
                    topPos + DRILL_ICON_Y,
                    0,
                    frame * DRILL_ICON_HEIGHT,
                    DRILL_ICON_WIDTH,
                    DRILL_ICON_HEIGHT,
                    DRILL_ICON_WIDTH,
                    DRILL_ICON_SHEET_HEIGHT
            );
        }

        @Override
        protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
            guiGraphics.drawString(font, title, 8, 6, 0x404040, false);
            guiGraphics.drawString(font, playerInventoryTitle, 8, 95, 0x404040, false);
        }
    }

    public static class Model extends GeoModel<BlockEntity> {
        @Override
        public ResourceLocation getModelResource(BlockEntity animatable) {
            return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "geo/burner_mining_drill.geo.json");
        }

        @Override
        public ResourceLocation getTextureResource(BlockEntity animatable) {
            return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "textures/block/burner_mining_drill.png");
        }

        @Override
        public ResourceLocation getAnimationResource(BlockEntity animatable) {
            return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "animations/burner_mining_drill.animation.json");
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
            return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "geo/burner_mining_drill.geo.json");
        }

        @Override
        public ResourceLocation getTextureResource(Item animatable) {
            return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "textures/block/burner_mining_drill.png");
        }

        @Override
        public ResourceLocation getAnimationResource(Item animatable) {
            return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "animations/burner_mining_drill.animation.json");
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
