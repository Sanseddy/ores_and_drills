package dev.client;

import dev.OresAndDrillsMod;
import dev.network.FluidTankClickPayload;
import dev.world.block.entity.AbstractDrillBlockEntity;
import dev.world.inventory.AbstractEnergyDrillMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;

public abstract class AbstractEnergyDrillScreen extends AbstractContainerScreen<AbstractEnergyDrillMenu> {
    private static final int FLUID_TILE_SIZE = 16;
    private static final int FLUID_TANK_INSET = 0;
    private static final int WATER_TINT = 0xFF3F76E4;
    private static final ResourceLocation DRILL_ICON_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            OresAndDrillsMod.MOD_ID, "textures/gui/drill_gui.png");
    private static final int DRILL_ICON_WIDTH = 46;
    private static final int DRILL_ICON_HEIGHT = 66;
    private static final int DRILL_ICON_SHEET_HEIGHT = DRILL_ICON_HEIGHT * AbstractDrillBlockEntity.GUI_ANIMATION_FRAME_COUNT;
    private static final String ENERGY_TOOLTIP_KEY = "tooltip.ores_and_drills.mining_drill.energy";
    private static final String MINING_PROGRESS_TOOLTIP_KEY = "tooltip.ores_and_drills.mining_drill.progress";
    private static final String PRODUCTIVITY_TOOLTIP_KEY = "tooltip.ores_and_drills.mining_drill.productivity";
    private static final String FLUID_EMPTY_TOOLTIP_KEY = "tooltip.ores_and_drills.mining_drill.fluid_empty";
    private static final String FLUID_AMOUNT_TOOLTIP_KEY = "tooltip.ores_and_drills.mining_drill.fluid_amount";

    private final ResourceLocation texture;
    private final ResourceLocation energyProgressTexture;
    private final int energyProgressX;
    private final int energyProgressY;
    private final int energyProgressWidth;
    private final int energyProgressHeight;
    private final ResourceLocation miningProgressTexture;
    private final int miningProgressX;
    private final int miningProgressY;
    private final int miningProgressWidth;
    private final int miningProgressHeight;
    private final boolean horizontalMiningProgress;
    private final ResourceLocation productivityProgressTexture;
    private final int productivityProgressX;
    private final int productivityProgressY;
    private final int productivityProgressWidth;
    private final int productivityProgressHeight;
    private final int firstFluidTankX;
    private final int firstFluidTankY;
    private final int secondFluidTankX;
    private final int secondFluidTankY;
    private final int fluidTankWidth;
    private final int fluidTankHeight;
    private final int textureWidth;
    private final int textureHeight;
    private final int playerInventoryLabelY;
    private final int drillIconX;
    private final int drillIconY;

    protected AbstractEnergyDrillScreen(AbstractEnergyDrillMenu menu, Inventory playerInventory, Component title,
                                         ResourceLocation texture, int guiWidth, int guiHeight,
                                         ResourceLocation energyProgressTexture, int energyProgressX, int energyProgressY, int energyProgressSize,
                                         ResourceLocation miningProgressTexture, int miningProgressX, int miningProgressY,
                                         int miningProgressWidth, int miningProgressHeight,
                                         int textureWidth, int textureHeight, int playerInventoryLabelY) {
        this(menu, playerInventory, title, texture, guiWidth, guiHeight,
                energyProgressTexture, energyProgressX, energyProgressY, energyProgressSize, energyProgressSize,
                miningProgressTexture, miningProgressX, miningProgressY, miningProgressWidth, miningProgressHeight,
                false, null, 0, 0, 0, 0,
                -1, -1, -1, -1, 0, 0,
                -1, -1,
                textureWidth, textureHeight, playerInventoryLabelY);
    }

    protected AbstractEnergyDrillScreen(AbstractEnergyDrillMenu menu, Inventory playerInventory, Component title,
                                         ResourceLocation texture, int guiWidth, int guiHeight,
                                         ResourceLocation energyProgressTexture, int energyProgressX, int energyProgressY,
                                         int energyProgressWidth, int energyProgressHeight,
                                         ResourceLocation miningProgressTexture, int miningProgressX, int miningProgressY,
                                         int miningProgressWidth, int miningProgressHeight,
                                         int textureWidth, int textureHeight, int playerInventoryLabelY,
                                         int drillIconX, int drillIconY) {
        this(menu, playerInventory, title, texture, guiWidth, guiHeight,
                energyProgressTexture, energyProgressX, energyProgressY, energyProgressWidth, energyProgressHeight,
                miningProgressTexture, miningProgressX, miningProgressY, miningProgressWidth, miningProgressHeight,
                false, null, 0, 0, 0, 0,
                -1, -1, -1, -1, 0, 0,
                drillIconX, drillIconY,
                textureWidth, textureHeight, playerInventoryLabelY);
    }

    protected AbstractEnergyDrillScreen(AbstractEnergyDrillMenu menu, Inventory playerInventory, Component title,
                                         ResourceLocation texture, int guiWidth, int guiHeight,
                                         ResourceLocation energyProgressTexture, int energyProgressX, int energyProgressY,
                                         int energyProgressWidth, int energyProgressHeight,
                                         ResourceLocation miningProgressTexture, int miningProgressX, int miningProgressY,
                                         int miningProgressWidth, int miningProgressHeight,
                                         boolean horizontalMiningProgress,
                                         ResourceLocation productivityProgressTexture, int productivityProgressX, int productivityProgressY,
                                         int productivityProgressWidth, int productivityProgressHeight,
                                         int firstFluidTankX, int firstFluidTankY, int secondFluidTankX, int secondFluidTankY,
                                         int fluidTankWidth, int fluidTankHeight,
                                         int drillIconX, int drillIconY,
                                         int textureWidth, int textureHeight, int playerInventoryLabelY) {
        super(menu, playerInventory, title);
        imageWidth = guiWidth;
        imageHeight = guiHeight;
        this.texture = texture;
        this.energyProgressTexture = energyProgressTexture;
        this.energyProgressX = energyProgressX;
        this.energyProgressY = energyProgressY;
        this.energyProgressWidth = energyProgressWidth;
        this.energyProgressHeight = energyProgressHeight;
        this.miningProgressTexture = miningProgressTexture;
        this.miningProgressX = miningProgressX;
        this.miningProgressY = miningProgressY;
        this.miningProgressWidth = miningProgressWidth;
        this.miningProgressHeight = miningProgressHeight;
        this.horizontalMiningProgress = horizontalMiningProgress;
        this.productivityProgressTexture = productivityProgressTexture;
        this.productivityProgressX = productivityProgressX;
        this.productivityProgressY = productivityProgressY;
        this.productivityProgressWidth = productivityProgressWidth;
        this.productivityProgressHeight = productivityProgressHeight;
        this.firstFluidTankX = firstFluidTankX;
        this.firstFluidTankY = firstFluidTankY;
        this.secondFluidTankX = secondFluidTankX;
        this.secondFluidTankY = secondFluidTankY;
        this.fluidTankWidth = fluidTankWidth;
        this.fluidTankHeight = fluidTankHeight;
        this.drillIconX = drillIconX;
        this.drillIconY = drillIconY;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.playerInventoryLabelY = playerInventoryLabelY;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderBarTooltip(guiGraphics, mouseX, mouseY);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tryFluidTankClick(mouseX, mouseY, firstFluidTankX, firstFluidTankY)
                || tryFluidTankClick(mouseX, mouseY, secondFluidTankX, secondFluidTankY)) {
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean tryFluidTankClick(double mouseX, double mouseY, int x, int y) {
        if (x < 0 || y < 0 || fluidTankWidth <= 0 || fluidTankHeight <= 0
                || !isHovering(x, y, fluidTankWidth, fluidTankHeight, mouseX, mouseY)
                || !canHoldFluid(menu.getCarried())) {
            return false;
        }

        PacketDistributor.sendToServer(new FluidTankClickPayload(menu.containerId));
        return true;
    }

    private static boolean canHoldFluid(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(Items.BUCKET) || stack.getCapability(Capabilities.FluidHandler.ITEM) != null);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(texture, leftPos, topPos, 0, 0, imageWidth, imageHeight, textureWidth, textureHeight);

        int energyHeight = menu.getEnergyProgressHeight(energyProgressHeight);
        if (energyHeight > 0) {
            blitVerticalProgress(guiGraphics, energyProgressTexture, energyProgressX, energyProgressY,
                    energyProgressWidth, energyProgressHeight, energyHeight);
        }

        renderFluidTank(guiGraphics, 0, firstFluidTankX, firstFluidTankY);
        renderFluidTank(guiGraphics, 1, secondFluidTankX, secondFluidTankY);

        if (horizontalMiningProgress) {
            int miningWidth = menu.getMiningProgressWidth(miningProgressWidth);
            if (miningWidth > 0) {
                guiGraphics.blit(
                        miningProgressTexture,
                        leftPos + miningProgressX,
                        topPos + miningProgressY,
                        0,
                        0,
                        miningWidth,
                        miningProgressHeight,
                        miningProgressWidth,
                        miningProgressHeight
                );
            }
        } else {
            int miningHeight = menu.getMiningProgressHeight(miningProgressHeight);
            if (miningHeight > 0) {
                blitVerticalProgress(guiGraphics, miningProgressTexture, miningProgressX, miningProgressY,
                        miningProgressWidth, miningProgressHeight, miningHeight);
            }
        }

        if (productivityProgressTexture != null) {
            int productivityWidth = menu.getProductivityProgressWidth(productivityProgressWidth);
            if (productivityWidth > 0) {
                guiGraphics.blit(
                        productivityProgressTexture,
                        leftPos + productivityProgressX,
                        topPos + productivityProgressY,
                        0,
                        0,
                        productivityWidth,
                        productivityProgressHeight,
                        productivityProgressWidth,
                        productivityProgressHeight
                );
            }
        }

        renderDrillIcon(guiGraphics);
    }

    private void renderDrillIcon(GuiGraphics guiGraphics) {
        if (drillIconX < 0 || drillIconY < 0) {
            return;
        }

        int frame = menu.getAnimationFrame();
        guiGraphics.blit(
                DRILL_ICON_TEXTURE,
                leftPos + drillIconX,
                topPos + drillIconY,
                0,
                frame * DRILL_ICON_HEIGHT,
                DRILL_ICON_WIDTH,
                DRILL_ICON_HEIGHT,
                DRILL_ICON_WIDTH,
                DRILL_ICON_SHEET_HEIGHT
        );
    }

    private void blitVerticalProgress(GuiGraphics guiGraphics, ResourceLocation progressTexture,
                                      int x, int y, int width, int height, int progressHeight) {
        int offset = height - progressHeight;
        guiGraphics.blit(
                progressTexture,
                leftPos + x,
                topPos + y + offset,
                0,
                offset,
                width,
                progressHeight,
                width,
                height
        );
    }

    private void renderFluidTank(GuiGraphics guiGraphics, int tank, int x, int y) {
        if (x < 0 || y < 0 || fluidTankWidth <= 0 || fluidTankHeight <= 0) {
            return;
        }

        int innerWidth = Math.max(0, fluidTankWidth - FLUID_TANK_INSET * 2);
        int innerHeight = Math.max(0, fluidTankHeight - FLUID_TANK_INSET * 2);
        int fluidHeight = menu.getFluidProgressHeight(tank, innerHeight);
        if (fluidHeight > 0) {
            int offset = innerHeight - fluidHeight;
            FluidStack fluid = menu.getFluidForRender(tank);
            if (!fluid.isEmpty()) {
                renderFluid(guiGraphics, fluid,
                        leftPos + x + FLUID_TANK_INSET,
                        topPos + y + FLUID_TANK_INSET + offset,
                        innerWidth,
                        fluidHeight);
            }
        }
    }

    private void renderFluid(GuiGraphics guiGraphics, FluidStack fluid, int x, int y, int width, int height) {
        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid.getFluid());
        ResourceLocation stillTexture = extensions.getStillTexture(fluid);
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(stillTexture);
        int tint = fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER) ? WATER_TINT : extensions.getTintColor(fluid);
        float alpha = ((tint >> 24) & 0xFF) / 255.0F;
        float red = ((tint >> 16) & 0xFF) / 255.0F;
        float green = ((tint >> 8) & 0xFF) / 255.0F;
        float blue = (tint & 0xFF) / 255.0F;
        if (alpha <= 0.0F) {
            alpha = 1.0F;
        }

        guiGraphics.setColor(red, green, blue, alpha);
        for (int drawY = 0; drawY < height; drawY += FLUID_TILE_SIZE) {
            int tileHeight = Math.min(FLUID_TILE_SIZE, height - drawY);
            for (int drawX = 0; drawX < width; drawX += FLUID_TILE_SIZE) {
                int tileWidth = Math.min(FLUID_TILE_SIZE, width - drawX);
                guiGraphics.blit(
                        x + drawX,
                        y + drawY,
                        0,
                        tileWidth,
                        tileHeight,
                        sprite
                );
            }
        }
        guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void renderBarTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (isHovering(energyProgressX, energyProgressY, energyProgressWidth, energyProgressHeight, mouseX, mouseY)) {
            guiGraphics.renderTooltip(
                    font,
                    plainTooltip(Component.translatable(ENERGY_TOOLTIP_KEY, menu.getEnergyStored(), menu.getEnergyCapacity())),
                    mouseX,
                    mouseY
            );
            return;
        }

        if (renderFluidTooltip(guiGraphics, mouseX, mouseY, 0, firstFluidTankX, firstFluidTankY)) {
            return;
        }

        if (renderFluidTooltip(guiGraphics, mouseX, mouseY, 1, secondFluidTankX, secondFluidTankY)) {
            return;
        }

        if (isHovering(miningProgressX, miningProgressY, miningProgressWidth, miningProgressHeight, mouseX, mouseY)) {
            guiGraphics.renderTooltip(
                    font,
                    plainTooltip(Component.translatable(MINING_PROGRESS_TOOLTIP_KEY, menu.getMiningProgressPercent())),
                    mouseX,
                    mouseY
            );
            return;
        }

        if (productivityProgressTexture != null
                && isHovering(productivityProgressX, productivityProgressY, productivityProgressWidth, productivityProgressHeight, mouseX, mouseY)) {
            guiGraphics.renderTooltip(
                    font,
                    plainTooltip(Component.translatable(PRODUCTIVITY_TOOLTIP_KEY, menu.getProductivityProgressPercent())),
                    mouseX,
                    mouseY
            );
        }
    }

    private boolean renderFluidTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY, int tank, int x, int y) {
        if (x < 0 || y < 0 || !isHovering(x, y, fluidTankWidth, fluidTankHeight, mouseX, mouseY)) {
            return false;
        }

        FluidStack fluid = menu.getFluidForRender(tank);
        Component name = fluid.isEmpty()
                ? plainTooltip(Component.translatable(FLUID_EMPTY_TOOLTIP_KEY))
                : fluid.getHoverName().copy().withStyle(style -> style.withItalic(false));
        guiGraphics.renderTooltip(
                font,
                java.util.List.of(
                        name,
                        plainTooltip(Component.translatable(FLUID_AMOUNT_TOOLTIP_KEY, menu.getFluidAmount(tank), menu.getFluidCapacity(tank)))
                ),
                java.util.Optional.empty(),
                mouseX,
                mouseY
        );
        return true;
    }

    private static MutableComponent plainTooltip(MutableComponent component) {
        return component.withStyle(style -> style.withItalic(false));
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(font, title, 8, 6, 0x404040, false);
        guiGraphics.drawString(font, playerInventoryTitle, 8, playerInventoryLabelY, 0x404040, false);
    }
}
