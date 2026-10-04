package dev.client.ore;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.serialization.MapCodec;
import dev.OresAndDrillsMod;
import dev.client.ore.speck.ArgbImage;
import dev.client.ore.speck.OreSpeckLayout;
import dev.world.level.levelgen.OreDepositOrePalette;
import dev.world.level.levelgen.OreVisualStages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Generates the ore deposit overlays from each ore's own texture: for every palette slot, several random
 * arrangements of the original specks ({@link #VARIANTS}), each in every visual stage. A block picks an
 * arrangement per face from its position, so neighboring deposits look different. Every ore sprite has a
 * colorless (zero saturation) twin used as the trace of the initial state once a deposit has been mined.
 * <p>
 * Everything is read through the active resource packs: the ore's block texture (including overlay-style
 * models), its drop item and the rock textures. Sprites are generated at the packs' resolution (16 or 32, see
 * {@link OreSpeckLibrary#resolutionFor}), and a resource reload regenerates all of them.
 * <p>
 * All slots are stitched up front with one fixed size, so when the server's palette arrives their pixels are
 * replaced in place without restitching the atlas.
 */
public record OreSpeckTextureSource() implements SpriteSource {
    private static final MapCodec<OreSpeckTextureSource> CODEC = MapCodec.unit(OreSpeckTextureSource::new);
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "ore_specks");
    public static final SpriteSourceType TYPE = new SpriteSourceType(CODEC);
    /** Arrangements per ore; every one costs {@code MAX_ORES x stage count x 2} pre-stitched sprites. */
    public static final int VARIANTS = 8;

    public static ResourceLocation oreLocation(int oreIndex, int variant, int stage) {
        return ResourceLocation.fromNamespaceAndPath(
                OresAndDrillsMod.MOD_ID,
                "block/generated/ore_specks/ore/" + oreIndex + "/" + variant + "/" + stage
        );
    }

    public static ResourceLocation traceLocation(int oreIndex, int variant, int stage) {
        return ResourceLocation.fromNamespaceAndPath(
                OresAndDrillsMod.MOD_ID,
                "block/generated/ore_specks/trace/" + oreIndex + "/" + variant + "/" + stage
        );
    }

    @Override
    public void run(ResourceManager resourceManager, Output output) {
        OreDepositOreColors.clearCache();
        OreSpeckLibrary.clearCache();
        int resolution = OreSpeckLibrary.resolutionFor(resourceManager);
        int generated = 0;
        for (int oreIndex = 0; oreIndex < OreDepositOrePalette.MAX_ORES; oreIndex++) {
            List<OreSpeckLayout> layouts = OreSpeckLibrary.layoutsFor(resourceManager, oreIdAt(oreIndex), null, VARIANTS, resolution);
            for (int variant = 0; variant < VARIANTS; variant++) {
                for (int stage = 0; stage < OreVisualStages.COUNT; stage++) {
                    double fill = OreVisualStages.fillFactor(stage);
                    addSprite(output, oreLocation(oreIndex, variant, stage), layouts.get(variant).renderOre(fill));
                    addSprite(output, traceLocation(oreIndex, variant, stage), layouts.get(variant).renderTrace(fill));
                    generated += 2;
                }
            }
        }
        OresAndDrillsMod.LOGGER.debug("Ore deposit: generated {} pre-stitched {}x{} speck sprites", generated, resolution, resolution);
    }

    @Nullable
    private static ResourceLocation oreIdAt(int oreIndex) {
        Block ore = OreDepositOrePalette.oreAt(oreIndex);
        return ore == null ? null : BuiltInRegistries.BLOCK.getKey(ore);
    }

    private static void addSprite(Output output, ResourceLocation location, ArgbImage image) {
        try {
            NativeImage nativeImage = OreSpeckLibrary.toNativeImage(image);
            output.add(location, loader -> new SpriteContents(
                    location,
                    new FrameSize(nativeImage.getWidth(), nativeImage.getHeight()),
                    nativeImage,
                    ResourceMetadata.EMPTY
            ));
        } catch (RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: failed to generate speck sprite {}", location, exception);
        }
    }

    /**
     * Regenerates the slots from the server-resolved ores and loot drops directly in the block atlas.
     * Returns false when called before the atlas/render thread is ready so the client tick handler can retry.
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

        TextureAtlasSprite firstSlot = atlas.getTextures().get(oreLocation(0, 0, 0));
        if (firstSlot == null) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: speck sprites are not stitched yet");
            return true;
        }
        // Same size the slots were stitched with during the last resource reload.
        int resolution = firstSlot.contents().width();

        OreDepositOreColors.clearCache();
        OreSpeckLibrary.clearCache();
        atlas.bind();
        int updated = 0;
        for (int oreIndex = 0; oreIndex < slotCount; oreIndex++) {
            List<OreSpeckLayout> layouts = OreSpeckLibrary.layoutsFor(
                    resourceManager, oreIdAt(oreIndex), OreDepositOrePalette.dropIdAt(oreIndex), VARIANTS, resolution
            );
            for (int variant = 0; variant < VARIANTS; variant++) {
                for (int stage = 0; stage < OreVisualStages.COUNT; stage++) {
                    double fill = OreVisualStages.fillFactor(stage);
                    updated += replaceSpritePixels(atlas, oreLocation(oreIndex, variant, stage), layouts.get(variant).renderOre(fill));
                    updated += replaceSpritePixels(atlas, traceLocation(oreIndex, variant, stage), layouts.get(variant).renderTrace(fill));
                }
            }
        }
        OresAndDrillsMod.LOGGER.debug("Ore deposit: regenerated {} speck sprites in place", updated);
        return true;
    }

    private static int replaceSpritePixels(TextureAtlas atlas, ResourceLocation location, ArgbImage image) {
        TextureAtlasSprite sprite = atlas.getTextures().get(location);
        if (sprite == null) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: missing pre-stitched speck sprite {}", location);
            return 0;
        }

        SpriteContents contents = sprite.contents();
        NativeImage original = contents.getOriginalImage();
        if (original.getWidth() != image.width() || original.getHeight() != image.height()) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: speck sprite {} has an unexpected size", location);
            return 0;
        }

        try (NativeImage replacement = OreSpeckLibrary.toNativeImage(image)) {
            int mipLevel = contents.byMipLevel.length - 1;
            for (int index = 1; index < contents.byMipLevel.length; index++) {
                contents.byMipLevel[index].close();
            }
            original.copyFrom(replacement);
            contents.byMipLevel = new NativeImage[] {original};
            contents.increaseMipLevel(mipLevel);
            sprite.uploadFirstFrame();
        }
        return 1;
    }

    @Override
    public SpriteSourceType type() {
        return TYPE;
    }
}
