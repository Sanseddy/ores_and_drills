package dev.client.ore;

import com.mojang.blaze3d.platform.NativeImage;
import dev.OresAndDrillsMod;
import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositOrePalette;
import dev.world.level.levelgen.OreDepositStonePalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class OreDepositBakedModel extends BakedModelWrapper<BakedModel> {
    private static final ModelProperty<OreDepositData.Visual> VISUAL_PROPERTY = new ModelProperty<>();
    private static final float OVERLAY_OFFSET = 1.0F / 2048.0F;
    /** Indexed directly by richness (0..3), from depleted to richest. */
    static final ResourceLocation[] UPPER_TEXTURES = {
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/ore_layer_0"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/ore_layer_1"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/ore_layer_2"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/ore_layer_3")
    };
    static final ResourceLocation[] DEPLETION_TEXTURES = {
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/depletion_layer_0"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/depletion_layer_1"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/depletion_layer_2"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/depletion_layer_3")
    };

    private final TextureAtlasSprite fallbackBaseSprite;
    /** Fixed pre-stitched slots are recolored in place when the synchronized server palette changes. */
    private final TextureAtlasSprite[][] upperSpritesByIndex;
    /** Used for an invalid palette index: the plain grayscale mask rather than a missing texture. */
    private final TextureAtlasSprite[] fallbackUpperSprites;
    private final TextureAtlasSprite[] depletionSprites;
    private final boolean exhausted;
    private final Map<BaseKey, BakedModel> baseModelCache = new ConcurrentHashMap<>();
    private final Map<TextureAtlasSprite, Integer> spriteBrightnessCache = new ConcurrentHashMap<>();
    private final Map<Key, List<BakedQuad>> cache = new ConcurrentHashMap<>();

    private OreDepositBakedModel(
            BakedModel originalModel,
            TextureAtlasSprite[][] upperSpritesByIndex,
            TextureAtlasSprite[] fallbackUpperSprites,
            TextureAtlasSprite[] depletionSprites,
            boolean exhausted
    ) {
        super(originalModel);
        this.fallbackBaseSprite = originalModel.getParticleIcon();
        this.upperSpritesByIndex = upperSpritesByIndex;
        this.fallbackUpperSprites = fallbackUpperSprites;
        this.depletionSprites = depletionSprites;
        this.exhausted = exhausted;
    }

    public static void replaceModels(ModelEvent.ModifyBakingResult event) {
        TextureAtlasSprite[][] upperSpritesByIndex = new TextureAtlasSprite[OreDepositOrePalette.MAX_ORES][UPPER_TEXTURES.length];
        for (int oreIndex = 0; oreIndex < upperSpritesByIndex.length; oreIndex++) {
            for (int richness = 0; richness < UPPER_TEXTURES.length; richness++) {
                upperSpritesByIndex[oreIndex][richness] = event.getTextureGetter().apply(
                        new Material(TextureAtlas.LOCATION_BLOCKS, OreTintedTextureSource.locationForSlot(oreIndex, richness))
                );
            }
        }

        TextureAtlasSprite[] fallbackUpperSprites = new TextureAtlasSprite[UPPER_TEXTURES.length];
        for (int richness = 0; richness < UPPER_TEXTURES.length; richness++) {
            fallbackUpperSprites[richness] = event.getTextureGetter().apply(new Material(TextureAtlas.LOCATION_BLOCKS, UPPER_TEXTURES[richness]));
        }

        TextureAtlasSprite[] depletionSprites = new TextureAtlasSprite[DEPLETION_TEXTURES.length];
        for (int richness = 0; richness < DEPLETION_TEXTURES.length; richness++) {
            depletionSprites[richness] = event.getTextureGetter().apply(new Material(TextureAtlas.LOCATION_BLOCKS, DEPLETION_TEXTURES[richness]));
        }

        int replaced = 0;
        OreDepositBakedModel sharedDepositModel = null;
        OreDepositBakedModel sharedExhaustedModel = null;
        ResourceLocation oreDepositId = ModBlocks.ORE_DEPOSIT.getId();
        ResourceLocation exhaustedDepositId = ModBlocks.EXHAUSTED_ORE_DEPOSIT.getId();
        for (Map.Entry<ModelResourceLocation, BakedModel> entry : event.getModels().entrySet()) {
            if (entry.getKey().id().equals(oreDepositId)) {
                if (sharedDepositModel == null) {
                    sharedDepositModel = new OreDepositBakedModel(
                            entry.getValue(), upperSpritesByIndex, fallbackUpperSprites, depletionSprites, false
                    );
                }
                entry.setValue(sharedDepositModel);
                replaced++;
            } else if (entry.getKey().id().equals(exhaustedDepositId)) {
                if (sharedExhaustedModel == null) {
                    sharedExhaustedModel = new OreDepositBakedModel(
                            entry.getValue(), upperSpritesByIndex, fallbackUpperSprites, depletionSprites, true
                    );
                }
                entry.setValue(sharedExhaustedModel);
                replaced++;
            }
        }
        OresAndDrillsMod.LOGGER.debug("Ore deposit model: replaced {} baked states", replaced);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return quadsFor(state, side, null, LayerPass.ALL);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData extraData, @Nullable RenderType renderType) {
        return quadsFor(state, side, extraData.get(VISUAL_PROPERTY), passFor(renderType));
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return fallbackBaseSprite;
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        OreDepositData.Visual visual = data.get(VISUAL_PROPERTY);
        return visual == null ? getParticleIcon() : baseModelFor(Math.max(0, visual.baseIndex())).getParticleIcon(ModelData.EMPTY);
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        OreDepositData.Visual visual = OreDepositClientVisuals.visualAt(level, pos);
        return visual == null ? modelData : ModelData.builder().with(VISUAL_PROPERTY, visual).build();
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        // The solid base is a depth pre-pass: selection outlines and other depth-tested effects must see
        // an ordinary opaque cube instead of treating Sable's physical block like x-ray glass. The full
        // visual is still submitted atomically through one translucent buffer in strict inner-to-outer
        // order (base, semi-transparent depletion, ore), so losing the pre-pass cannot make a visual
        // layer disappear. Both are vanilla chunk layers and therefore remain compatible with Sodium.
        return ChunkRenderTypeSet.of(RenderType.solid(), RenderType.translucent());
    }

    private static LayerPass passFor(@Nullable RenderType renderType) {
        // BlockRenderDispatcher#renderBreakingTexture deliberately asks the extended getQuads method
        // with a null render type. Return one dedicated shell outside the ore/depletion overlays:
        // rendering every visual layer duplicates the decal and breaks translucent blending, while
        // rendering it on the unshifted base leaves it hidden behind the outer layers' depth values.
        // The legacy three-argument getQuads method still returns ALL for item and other unlayered rendering.
        if (renderType == null) {
            return LayerPass.BREAKING;
        }
        if (renderType == RenderType.solid()) {
            return LayerPass.BASE;
        }
        if (renderType == RenderType.cutout()) {
            return LayerPass.BASE;
        }
        if (renderType == RenderType.translucent()) {
            return LayerPass.MAIN;
        }
        return LayerPass.NONE;
    }

    private List<BakedQuad> quadsFor(
            @Nullable BlockState state,
            @Nullable Direction side,
            @Nullable OreDepositData.Visual visual,
            LayerPass pass
    ) {
        Block renderedBlock = exhausted ? ModBlocks.EXHAUSTED_ORE_DEPOSIT.get() : ModBlocks.ORE_DEPOSIT.get();
        if (state == null || !state.is(renderedBlock)) {
            return originalModel.getQuads(state, side, RandomSource.create());
        }
        if (pass == LayerPass.NONE) {
            return List.of();
        }

        int base = visual != null ? visual.baseIndex() : 0;
        int oreIndex = visual != null ? visual.oreIndex() : -1;
        int richness = visual != null ? visual.richness() : 0;
        Key key = new Key(
                OreDepositStonePalette.clientRevision(),
                Math.max(0, base),
                oreIndex,
                Math.max(0, Math.min(UPPER_TEXTURES.length - 1, richness)),
                visual != null ? Math.max(0, Math.min(DEPLETION_TEXTURES.length - 1, visual.depletionRichness())) : 0,
                exhausted || visual != null && visual.depletionVisible(),
                pass,
                side
        );
        return cache.computeIfAbsent(key, this::buildQuads);
    }

    private List<BakedQuad> buildQuads(Key key) {
        Block baseBlock = OreDepositStonePalette.stoneAt(key.base());
        BlockState baseState = baseBlock.defaultBlockState();
        BakedModel baseModel = baseModelFor(key.base());
        List<BakedQuad> baseQuads = baseModel.getQuads(
                baseState,
                key.side(),
                RandomSource.create(key.base()),
                ModelData.EMPTY,
                null
        );
        if (baseQuads.isEmpty()) {
            return List.of();
        }

        if (key.pass() == LayerPass.BREAKING) {
            return offsetQuads(baseQuads, 3);
        }
        if (key.pass() == LayerPass.BASE) {
            return List.copyOf(baseQuads);
        }

        if (exhausted) {
            if (key.pass() == LayerPass.MAIN) {
                if (!key.depletionVisible()) {
                    return List.copyOf(baseQuads);
                }
                List<BakedQuad> quads = new ArrayList<>(baseQuads.size() * 2);
                quads.addAll(baseQuads);
                quads.addAll(depletionOverlayQuads(baseQuads, depletionSprites[key.depletionRichness()]));
                return List.copyOf(quads);
            }
            if (!key.depletionVisible()) {
                return List.copyOf(baseQuads);
            }

            List<BakedQuad> quads = new ArrayList<>(baseQuads.size() * 2);
            quads.addAll(baseQuads);
            quads.addAll(depletionOverlayQuads(baseQuads, depletionSprites[key.depletionRichness()]));
            return List.copyOf(quads);
        }

        TextureAtlasSprite upperSprite = upperSpriteFor(key.oreIndex(), key.richness());
        if (key.pass() == LayerPass.MAIN) {
            List<BakedQuad> quads = new ArrayList<>(baseQuads.size() * (key.depletionVisible() ? 3 : 2));
            quads.addAll(baseQuads);
            if (key.depletionVisible()) {
                quads.addAll(depletionOverlayQuads(baseQuads, depletionSprites[key.depletionRichness()]));
            }
            quads.addAll(overlayQuads(baseQuads, upperSprite, key.depletionVisible() ? 2 : 1));
            return List.copyOf(quads);
        }

        List<BakedQuad> quads = new ArrayList<>(baseQuads.size() * (key.depletionVisible() ? 3 : 2));
        quads.addAll(baseQuads);
        if (key.depletionVisible()) {
            quads.addAll(depletionOverlayQuads(baseQuads, depletionSprites[key.depletionRichness()]));
        }
        quads.addAll(overlayQuads(baseQuads, upperSprite, key.depletionVisible() ? 2 : 1));
        return List.copyOf(quads);
    }

    private List<BakedQuad> depletionOverlayQuads(List<BakedQuad> baseQuads, TextureAtlasSprite sprite) {
        int averageBrightness = Math.round((float)baseQuads.stream()
                .mapToInt(quad -> spriteBrightnessCache.computeIfAbsent(quad.getSprite(), OreDepositBakedModel::averageBrightness))
                .average()
                .orElse(128.0D));
        return overlayQuads(baseQuads, sprite, 1, DepletionOpacity.vertexAlpha(averageBrightness));
    }

    private static int averageBrightness(TextureAtlasSprite sprite) {
        NativeImage image = sprite.contents().getOriginalImage();
        long weightedBrightness = 0L;
        long alphaWeight = 0L;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int color = image.getPixelRGBA(x, y);
                int alpha = FastColor.ABGR32.alpha(color);
                if (alpha == 0) {
                    continue;
                }

                int brightness = (54 * FastColor.ABGR32.red(color)
                        + 183 * FastColor.ABGR32.green(color)
                        + 19 * FastColor.ABGR32.blue(color)
                        + 128) >> 8;
                weightedBrightness += (long)brightness * alpha;
                alphaWeight += alpha;
            }
        }
        return alphaWeight == 0L ? 128 : (int)((weightedBrightness + alphaWeight / 2L) / alphaWeight);
    }

    private static List<BakedQuad> overlayQuads(List<BakedQuad> baseQuads, TextureAtlasSprite sprite, int layer) {
        return overlayQuads(baseQuads, sprite, layer, 255);
    }

    private static List<BakedQuad> overlayQuads(
            List<BakedQuad> baseQuads,
            TextureAtlasSprite sprite,
            int layer,
            int vertexAlpha
    ) {
        List<BakedQuad> quads = new ArrayList<>(baseQuads.size());
        for (BakedQuad baseQuad : baseQuads) {
            quads.add(overlayQuad(baseQuad, sprite, layer, vertexAlpha));
        }
        return List.copyOf(quads);
    }

    /** One opaque-shape shell beyond the farthest visual overlay, used only by the breaking decal. */
    private static List<BakedQuad> offsetQuads(List<BakedQuad> baseQuads, int layer) {
        List<BakedQuad> quads = new ArrayList<>(baseQuads.size());
        for (BakedQuad baseQuad : baseQuads) {
            int[] vertices = Arrays.copyOf(baseQuad.getVertices(), baseQuad.getVertices().length);
            for (int vertex = 0; vertex < 4; vertex++) {
                offsetPosition(vertices, vertex * IQuadTransformer.STRIDE, baseQuad.getDirection(), layer);
            }
            quads.add(new BakedQuad(
                    vertices,
                    baseQuad.getTintIndex(),
                    baseQuad.getDirection(),
                    baseQuad.getSprite(),
                    baseQuad.isShade(),
                    baseQuad.hasAmbientOcclusion()
            ));
        }
        return List.copyOf(quads);
    }

    private TextureAtlasSprite upperSpriteFor(int oreIndex, int richness) {
        return oreIndex >= 0 && oreIndex < upperSpritesByIndex.length
                ? upperSpritesByIndex[oreIndex][richness]
                : fallbackUpperSprites[richness];
    }

    private BakedModel baseModelFor(int index) {
        BaseKey key = new BaseKey(OreDepositStonePalette.clientRevision(), index);
        return baseModelCache.computeIfAbsent(key, this::resolveBaseModel);
    }

    private BakedModel resolveBaseModel(BaseKey key) {
        Block stone = OreDepositStonePalette.stoneAt(key.index());
        if (stone == ModBlocks.ORE_DEPOSIT.get() || stone == ModBlocks.EXHAUSTED_ORE_DEPOSIT.get()) {
            return originalModel;
        }
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(stone.defaultBlockState());
    }

    static BakedQuad overlayQuad(BakedQuad baseQuad, TextureAtlasSprite overlaySprite, int layer) {
        return overlayQuad(baseQuad, overlaySprite, layer, 255);
    }

    private static BakedQuad overlayQuad(
            BakedQuad baseQuad,
            TextureAtlasSprite overlaySprite,
            int layer,
            int vertexAlpha
    ) {
        int[] vertices = Arrays.copyOf(baseQuad.getVertices(), baseQuad.getVertices().length);
        TextureAtlasSprite baseSprite = baseQuad.getSprite();
        Direction direction = baseQuad.getDirection();

        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * IQuadTransformer.STRIDE;
            remapUv(vertices, offset, baseSprite, overlaySprite);
            vertices[offset + IQuadTransformer.COLOR] = vertexAlpha << 24 | 0xFFFFFF;
            offsetPosition(vertices, offset, direction, layer);
        }

        return new BakedQuad(
                vertices,
                -1,
                direction,
                overlaySprite,
                baseQuad.isShade(),
                baseQuad.hasAmbientOcclusion()
        );
    }

    private static void remapUv(
            int[] vertices,
            int vertexOffset,
            TextureAtlasSprite baseSprite,
            TextureAtlasSprite overlaySprite
    ) {
        int uvOffset = vertexOffset + IQuadTransformer.UV0;
        float baseU = Float.intBitsToFloat(vertices[uvOffset]);
        float baseV = Float.intBitsToFloat(vertices[uvOffset + 1]);
        float relativeU = baseSprite.getUOffset(baseU);
        float relativeV = baseSprite.getVOffset(baseV);
        vertices[uvOffset] = Float.floatToRawIntBits(overlaySprite.getU(relativeU));
        vertices[uvOffset + 1] = Float.floatToRawIntBits(overlaySprite.getV(relativeV));
    }

    private static void offsetPosition(int[] vertices, int vertexOffset, Direction direction, int layer) {
        int positionOffset = vertexOffset + IQuadTransformer.POSITION;
        float x = Float.intBitsToFloat(vertices[positionOffset]);
        float y = Float.intBitsToFloat(vertices[positionOffset + 1]);
        float z = Float.intBitsToFloat(vertices[positionOffset + 2]);
        float offset = OVERLAY_OFFSET * layer;
        vertices[positionOffset] = Float.floatToRawIntBits(x + direction.getStepX() * offset);
        vertices[positionOffset + 1] = Float.floatToRawIntBits(y + direction.getStepY() * offset);
        vertices[positionOffset + 2] = Float.floatToRawIntBits(z + direction.getStepZ() * offset);
    }

    /**
     * The ore index selects a stable atlas slot. If another world assigns a different drop to that index,
     * the slot pixels change in place and cached quads keep pointing at the same valid sprite region.
     */
    private record Key(
            int stonePaletteRevision,
            int base,
            int oreIndex,
            int richness,
            int depletionRichness,
            boolean depletionVisible,
            LayerPass pass,
            @Nullable Direction side
    ) {
    }

    private enum LayerPass {
        ALL,
        BASE,
        BREAKING,
        MAIN,
        NONE
    }

    private record BaseKey(int paletteRevision, int index) {
    }
}
