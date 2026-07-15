package dev.client.ore;

import dev.FactoryExpansionMod;
import dev.world.level.levelgen.OreDepositOrePalette;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Bakes a genuinely multi-colored ore-layer overlay per (ore block x richness stage) instead of relying on
 * a single flat tintindex color: each grayscale mask pixel is recolored by its own luminance through a
 * gradient built from {@link OreDepositOreColors#paletteForOre}, so darker shading gets the ore's own darker
 * tones and highlights get its own brighter tones, instead of one hue scaled up/down.
 */
public record OreTintedTextureSource() implements SpriteSource {
    private static final MapCodec<OreTintedTextureSource> CODEC = MapCodec.unit(OreTintedTextureSource::new);
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(FactoryExpansionMod.MOD_ID, "ore_tint");
    public static final SpriteSourceType TYPE = new SpriteSourceType(CODEC);

    /** Keyed by the ore's own block id (stable) rather than a per-world index — see the class doc on {@link OreDepositOreColors}. */
    public static ResourceLocation locationFor(ResourceLocation oreBlockId, int richness) {
        return ResourceLocation.fromNamespaceAndPath(
                FactoryExpansionMod.MOD_ID,
                "block/generated/ore_tint/" + oreBlockId.getNamespace() + "/" + oreBlockId.getPath() + "/" + richness
        );
    }

    @Override
    public void run(ResourceManager resourceManager, Output output) {
        OreDepositOreColors.clearCache();
        List<ResourceLocation> oreIds = OreDepositOrePalette.availableIds();
        FactoryExpansionMod.LOGGER.trace("Ore deposit: generating tinted textures for {} ore(s)", oreIds.size());
        int generated = 0;
        for (int richness = 0; richness < OreDepositBakedModel.UPPER_TEXTURES.length; richness++) {
            NativeImage mask = readMask(resourceManager, richness);
            if (mask == null) {
                continue;
            }

            try {
                for (ResourceLocation oreId : oreIds) {
                    addTintedSprite(resourceManager, output, mask, oreId, richness);
                    generated++;
                }
            } finally {
                mask.close();
            }
        }
        FactoryExpansionMod.LOGGER.trace("Ore deposit: generated {} tinted textures", generated);
    }

    private static NativeImage readMask(ResourceManager resourceManager, int richness) {
        ResourceLocation maskId = OreDepositBakedModel.UPPER_TEXTURES[richness];
        ResourceLocation texturePath = ResourceLocation.fromNamespaceAndPath(maskId.getNamespace(), "textures/" + maskId.getPath() + ".png");
        Optional<Resource> resource = resourceManager.getResource(texturePath);
        if (resource.isEmpty()) {
            FactoryExpansionMod.LOGGER.warn("Ore deposit: missing ore-layer mask {} for tinted texture generation", texturePath);
            return null;
        }

        try (InputStream stream = resource.get().open()) {
            return NativeImage.read(stream);
        } catch (IOException exception) {
            FactoryExpansionMod.LOGGER.warn("Ore deposit: failed to read ore-layer mask {}", texturePath, exception);
            return null;
        }
    }

    private static void addTintedSprite(ResourceManager resourceManager, Output output, NativeImage mask, ResourceLocation oreId, int richness) {
        try {
            int[] palette = OreDepositOreColors.paletteForOre(resourceManager, oreId);
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

            ResourceLocation location = locationFor(oreId, richness);
            output.add(location, loader -> new SpriteContents(location, new FrameSize(tinted.getWidth(), tinted.getHeight()), tinted, ResourceMetadata.EMPTY));
        } catch (RuntimeException exception) {
            FactoryExpansionMod.LOGGER.warn("Ore deposit: failed to generate tinted texture for ore {} richness {}", oreId, richness, exception);
        }
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
