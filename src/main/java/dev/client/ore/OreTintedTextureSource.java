package dev.client.ore;

import dev.OresAndDrillsMod;
import dev.world.level.levelgen.OreDepositOrePalette;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Bakes a genuinely multi-colored ore-layer overlay per (palette slot x richness stage) instead of relying on
 * a single flat tintindex color: each grayscale mask pixel is recolored by its own luminance through a
 * gradient built from {@link OreDepositOreColors#paletteForOre}, so darker shading gets the ore's own darker
 * tones and highlights get its own brighter tones, instead of one hue scaled up/down.
 */
public record OreTintedTextureSource() implements SpriteSource {
    private static final MapCodec<OreTintedTextureSource> CODEC = MapCodec.unit(OreTintedTextureSource::new);
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "ore_tint");
    public static final SpriteSourceType TYPE = new SpriteSourceType(CODEC);

    /** Fixed slots exist before joining a server, so their pixels can be replaced without restitching. */
    public static ResourceLocation locationForSlot(int oreIndex, int richness) {
        return ResourceLocation.fromNamespaceAndPath(
                OresAndDrillsMod.MOD_ID,
                "block/generated/ore_tint/slot/" + oreIndex + "/" + richness
        );
    }

    @Override
    public void run(ResourceManager resourceManager, Output output) {
        OreDepositOreColors.clearCache();
        OresAndDrillsMod.LOGGER.debug(
                "Ore deposit: generating {} pre-stitched tint slots",
                OreDepositOrePalette.MAX_ORES
        );
        int generated = 0;
        for (int richness = 0; richness < OreDepositBakedModel.UPPER_TEXTURES.length; richness++) {
            NativeImage mask = readMask(resourceManager, richness);
            if (mask == null) {
                continue;
            }

            try {
                for (int oreIndex = 0; oreIndex < OreDepositOrePalette.MAX_ORES; oreIndex++) {
                    Block ore = OreDepositOrePalette.oreAt(oreIndex);
                    ResourceLocation oreId = ore == null
                            ? null
                            : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(ore);
                    int[] palette = oreId == null
                            ? OreDepositOreColors.fallbackPalette()
                            : OreDepositOreColors.paletteForOre(resourceManager, oreId);
                    addTintedSprite(output, mask, palette, oreIndex, richness);
                    generated++;
                }
            } finally {
                mask.close();
            }
        }
        OresAndDrillsMod.LOGGER.debug("Ore deposit: generated {} tinted textures", generated);
    }

    private static NativeImage readMask(ResourceManager resourceManager, int richness) {
        ResourceLocation maskId = OreDepositBakedModel.UPPER_TEXTURES[richness];
        ResourceLocation texturePath = ResourceLocation.fromNamespaceAndPath(maskId.getNamespace(), "textures/" + maskId.getPath() + ".png");
        Optional<Resource> resource = resourceManager.getResource(texturePath);
        if (resource.isEmpty()) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: missing ore-layer mask {} for tinted texture generation", texturePath);
            return null;
        }

        try (InputStream stream = resource.get().open()) {
            return NativeImage.read(stream);
        } catch (IOException exception) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: failed to read ore-layer mask {}", texturePath, exception);
            return null;
        }
    }

    private static void addTintedSprite(Output output, NativeImage mask, int[] palette, int oreIndex, int richness) {
        try {
            NativeImage tinted = tintedCopy(mask, palette);
            ResourceLocation location = locationForSlot(oreIndex, richness);
            output.add(location, loader -> new SpriteContents(location, new FrameSize(tinted.getWidth(), tinted.getHeight()), tinted, ResourceMetadata.EMPTY));
        } catch (RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: failed to generate tinted texture for slot {} richness {}", oreIndex, richness, exception);
        }
    }

    /**
     * Applies the server-resolved drop palette directly to existing block-atlas sprites. Returns false when
     * called before the atlas/render thread is ready so the client tick handler can retry next tick.
     */
    public static boolean applySyncedPalette() {
        if (!RenderSystem.isOnRenderThread()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlas atlas = minecraft.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        ResourceManager resourceManager = minecraft.getResourceManager();
        int slotCount = Math.min(OreDepositOrePalette.clientSize(), OreDepositOrePalette.MAX_ORES);
        if (slotCount == 0) {
            return true;
        }

        OreDepositOreColors.clearCache();
        atlas.bind();
        int updated = 0;
        for (int richness = 0; richness < OreDepositBakedModel.UPPER_TEXTURES.length; richness++) {
            try (NativeImage mask = readMask(resourceManager, richness)) {
                if (mask == null) {
                    continue;
                }

                for (int oreIndex = 0; oreIndex < slotCount; oreIndex++) {
                    ResourceLocation dropId = OreDepositOrePalette.dropIdAt(oreIndex);
                    int[] palette = OreDepositOreColors.paletteForDrop(resourceManager, dropId);
                    TextureAtlasSprite sprite = atlas.getTextures().get(locationForSlot(oreIndex, richness));
                    if (sprite == null) {
                        OresAndDrillsMod.LOGGER.debug(
                                "Ore deposit: missing pre-stitched tint slot {} richness {}",
                                oreIndex,
                                richness
                        );
                        continue;
                    }

                    try (NativeImage tinted = tintedCopy(mask, palette)) {
                        replaceSpritePixels(sprite, tinted);
                        updated++;
                    }
                }
            }
        }
        OresAndDrillsMod.LOGGER.debug("Ore deposit: recolored {} atlas sprites in place", updated);
        return true;
    }

    private static NativeImage tintedCopy(NativeImage mask, int[] palette) {
        NativeImage tinted = new NativeImage(mask.getWidth(), mask.getHeight(), false);
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                int abgr = mask.getPixelRGBA(x, y);
                int alpha = (abgr >> 24) & 0xFF;
                if (alpha == 0) {
                    tinted.setPixelRGBA(x, y, 0);
                    continue;
                }

                int red = abgr & 0xFF;
                int green = (abgr >> 8) & 0xFF;
                int blue = (abgr >> 16) & 0xFF;
                int luminance = Math.max(red, Math.max(green, blue));
                int color = gradientColor(palette, luminance);
                int outRed = (color >> 16) & 0xFF;
                int outGreen = (color >> 8) & 0xFF;
                int outBlue = color & 0xFF;
                tinted.setPixelRGBA(x, y, (alpha << 24) | (outBlue << 16) | (outGreen << 8) | outRed);
            }
        }
        return tinted;
    }

    private static void replaceSpritePixels(TextureAtlasSprite sprite, NativeImage tinted) {
        SpriteContents contents = sprite.contents();
        NativeImage original = contents.getOriginalImage();
        if (original.getWidth() != tinted.getWidth() || original.getHeight() != tinted.getHeight()) {
            throw new IllegalArgumentException("Tinted image size does not match pre-stitched sprite size");
        }

        int mipLevel = contents.byMipLevel.length - 1;
        for (int index = 1; index < contents.byMipLevel.length; index++) {
            contents.byMipLevel[index].close();
        }
        original.copyFrom(tinted);
        contents.byMipLevel = new NativeImage[] {original};
        contents.increaseMipLevel(mipLevel);
        sprite.uploadFirstFrame();
    }

    /** {@code palette} is sorted dark to light; luminance 0 maps to the darkest entry, 255 to the brightest, with linear interpolation in between. */
    private static int gradientColor(int[] palette, int luminance) {
        if (palette.length == 1) {
            return palette[0];
        }

        double position = (luminance / 255.0D) * (palette.length - 1);
        int lowerIndex = (int)Math.floor(position);
        int upperIndex = Math.min(palette.length - 1, lowerIndex + 1);
        double fraction = position - lowerIndex;

        int lower = palette[lowerIndex];
        int upper = palette[upperIndex];
        int red = lerpChannel(lower, upper, fraction, 16);
        int green = lerpChannel(lower, upper, fraction, 8);
        int blue = lerpChannel(lower, upper, fraction, 0);
        return (red << 16) | (green << 8) | blue;
    }

    private static int lerpChannel(int lower, int upper, double fraction, int shift) {
        int lowerChannel = (lower >> shift) & 0xFF;
        int upperChannel = (upper >> shift) & 0xFF;
        return (int)Math.round(lowerChannel + (upperChannel - lowerChannel) * fraction);
    }

    @Override
    public SpriteSourceType type() {
        return TYPE;
    }
}
