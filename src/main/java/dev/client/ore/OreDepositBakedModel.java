package dev.client.ore;

import dev.OresAndDrillsMod;
import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositOrePalette;
import dev.world.level.levelgen.OreDepositStonePalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class OreDepositBakedModel extends BakedModelWrapper<BakedModel> {
    private static final ModelProperty<OreDepositData.Visual> VISUAL_PROPERTY = new ModelProperty<>();
    private static final FaceBakery FACE_BAKERY = new FaceBakery();
    private static final Vector3f CUBE_FROM = new Vector3f(0.0F, 0.0F, 0.0F);
    private static final Vector3f CUBE_TO = new Vector3f(16.0F, 16.0F, 16.0F);
    private static final float[] FULL_FACE_UV = {0.0F, 0.0F, 16.0F, 16.0F};
    /**
     * Indexed directly by richness (0..7), from depleted to richest.
     */
    static final ResourceLocation[] UPPER_TEXTURES = {
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/ore_layer_0"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/ore_layer_1"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/ore_layer_2"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/ore_layer_3"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/ore_layer_4"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/ore_layer_5"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/ore_layer_6"),
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "block/ore_layer_7")
    };

    private final TextureAtlasSprite fallbackBaseSprite;
    /** Fixed pre-stitched slots are recolored in place when the synchronized server palette changes. */
    private final TextureAtlasSprite[][] upperSpritesByIndex;
    /** Used for an invalid palette index: the plain grayscale mask rather than a missing texture. */
    private final TextureAtlasSprite[] fallbackUpperSprites;
    private final Map<BaseKey, TextureAtlasSprite> baseSpriteCache = new ConcurrentHashMap<>();
    private final Map<Key, List<BakedQuad>> cache = new ConcurrentHashMap<>();

    private OreDepositBakedModel(
            BakedModel originalModel,
            TextureAtlasSprite[][] upperSpritesByIndex,
            TextureAtlasSprite[] fallbackUpperSprites
    ) {
        super(originalModel);
        this.fallbackBaseSprite = originalModel.getParticleIcon();
        this.upperSpritesByIndex = upperSpritesByIndex;
        this.fallbackUpperSprites = fallbackUpperSprites;
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

        int replaced = 0;
        OreDepositBakedModel sharedModel = null;
        ResourceLocation oreDepositId = ModBlocks.ORE_DEPOSIT.getId();
        for (Map.Entry<ModelResourceLocation, BakedModel> entry : event.getModels().entrySet()) {
            if (!entry.getKey().id().equals(oreDepositId)) {
                continue;
            }

            if (sharedModel == null) {
                sharedModel = new OreDepositBakedModel(entry.getValue(), upperSpritesByIndex, fallbackUpperSprites);
            }
            entry.setValue(sharedModel);
            replaced++;
        }
        OresAndDrillsMod.LOGGER.trace("Ore deposit model: replaced {} baked states", replaced);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return quadsFor(state, side, null);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData extraData, @Nullable RenderType renderType) {
        return quadsFor(state, side, extraData.get(VISUAL_PROPERTY));
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return fallbackBaseSprite;
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        return getParticleIcon();
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        OreDepositData.Visual visual = OreDepositClientVisuals.visualAt(level, pos);
        return visual == null ? modelData : ModelData.builder().with(VISUAL_PROPERTY, visual).build();
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        return ChunkRenderTypeSet.of(RenderType.cutout());
    }

    private List<BakedQuad> quadsFor(@Nullable BlockState state, @Nullable Direction side, @Nullable OreDepositData.Visual visual) {
        if (state == null || !state.is(ModBlocks.ORE_DEPOSIT.get())) {
            return originalModel.getQuads(state, side, RandomSource.create());
        }

        int base = visual != null ? visual.baseIndex() : 0;
        int oreIndex = visual != null ? visual.oreIndex() : -1;
        int richness = visual != null ? visual.richness() : 0;
        Key key = new Key(
                OreDepositStonePalette.clientRevision(),
                Math.max(0, base),
                oreIndex,
                Math.max(0, Math.min(UPPER_TEXTURES.length - 1, richness)),
                side
        );
        return cache.computeIfAbsent(key, this::buildQuads);
    }

    private List<BakedQuad> buildQuads(Key key) {
        if (key.side() == null) {
            return List.of();
        }

        TextureAtlasSprite baseSprite = baseSpriteFor(key.base());
        TextureAtlasSprite upperSprite = upperSpriteFor(key.oreIndex(), key.richness());
        List<BakedQuad> quads = new ArrayList<>();
        quads.add(bakeFace(key.side(), baseSprite, -1, 0.0F));
        quads.add(bakeFace(key.side(), upperSprite, -1, 0.0F));
        return List.copyOf(quads);
    }

    private TextureAtlasSprite upperSpriteFor(int oreIndex, int richness) {
        return oreIndex >= 0 && oreIndex < upperSpritesByIndex.length
                ? upperSpritesByIndex[oreIndex][richness]
                : fallbackUpperSprites[richness];
    }

    private TextureAtlasSprite baseSpriteFor(int index) {
        BaseKey key = new BaseKey(OreDepositStonePalette.clientRevision(), index);
        return baseSpriteCache.computeIfAbsent(key, this::resolveBaseSprite);
    }

    private TextureAtlasSprite resolveBaseSprite(BaseKey key) {
        Block stone = OreDepositStonePalette.stoneAt(key.index());
        BakedModel stoneModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(stone.defaultBlockState());
        TextureAtlasSprite sprite = stoneModel.getParticleIcon();
        return sprite != null ? sprite : fallbackBaseSprite;
    }

    private static BakedQuad bakeFace(Direction direction, TextureAtlasSprite sprite, int tintIndex, float expand) {
        BlockFaceUV uv = new BlockFaceUV(FULL_FACE_UV.clone(), 0);
        BlockElementFace face = new BlockElementFace(direction, tintIndex, "", uv);
        return FACE_BAKERY.bakeQuad(CUBE_FROM, CUBE_TO, face, sprite, direction, BlockModelRotation.X0_Y0, null, true);
    }

    /**
     * The ore index selects a stable atlas slot. If another world assigns a different drop to that index,
     * the slot pixels change in place and cached quads keep pointing at the same valid sprite region.
     */
    private record Key(int stonePaletteRevision, int base, int oreIndex, int richness, @Nullable Direction side) {
    }

    private record BaseKey(int paletteRevision, int index) {
    }
}
