package dev.world.geology.ore;

import dev.OresAndDrillsMod;
import dev.world.geology.RockPhysics;
import dev.world.geology.RockProperties;
import dev.world.level.levelgen.OreGenerationWeights;
import dev.world.level.levelgen.OreSpawnDimensions;
import dev.world.level.levelgen.OreTags;
import dev.world.level.levelgen.OreUnifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ores of the running game and their conditional signatures, built from what the game itself
 * records: ore tags, item tags of the products, mining tier and hardness, and the generation data
 * {@link OreGenerationWeights} collected from the actual ore features (vanilla, mods, datapacks).
 *
 * <p>Minerals are computed once over all ores so that an ore reads the same in every dimension; the
 * prevalence used as a prior is per dimension. Ores without any recorded generation still appear,
 * as rare possibilities with a neutral depth. Datapack and API rock physics entries for an ore
 * block override its computed mineral.
 *
 * <p>Reads tags, so it is built on the server thread; callers hand the resulting immutable lists to
 * workers.
 */
public final class OreSignatureTable {
    private static final TagKey<Block> NEEDS_NETHERITE_TOOL =
            TagKey.create(
                    Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath("c", "needs_netherite_tool"));

    private static volatile Snapshot snapshot;
    private static volatile int tagsEpoch;

    private OreSignatureTable() {}

    private record Snapshot(
            long token,
            Map<String, RockProperties> mineralByMaterial,
            Map<String, String> materialByOre,
            Map<OreSpawnDimensions.SpawnDimension, List<OreSignature>> byDimension,
            Map<OreSpawnDimensions.SpawnDimension, List<OreSignature>> forAnalysis) {}

    /** Forgets the table; called when tags or rock physics data reload. */
    public static void invalidate() {
        tagsEpoch++;
        snapshot = null;
    }

    /** Signatures of the ores that can occur in {@code dimension}, most common first. */
    public static List<OreSignature> forDimension(ResourceLocation dimension) {
        return current().byDimension().getOrDefault(spawnDimension(dimension), List.of());
    }

    /**
     * The hypotheses identification weighs in {@code dimension}: its own ores plus every other
     * known ore as a rare possibility, since a datapack can place any ore anywhere.
     */
    public static List<OreSignature> forAnalysis(ResourceLocation dimension) {
        return current().forAnalysis().getOrDefault(spawnDimension(dimension), List.of());
    }

    /** Mineral of an ore block, or a neutral one for a block that is not an ore. */
    public static RockProperties mineral(Block block) {
        Snapshot current = current();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        String material = current.materialByOre().get(id.toString());
        if (material == null) {
            material = materialOf(block);
        }
        RockProperties mineral = current.mineralByMaterial().get(material);
        return mineral != null ? mineral : neutral();
    }

    public static RockProperties mineral(ResourceLocation oreId) {
        Block block = BuiltInRegistries.BLOCK.getOptional(oreId).orElse(null);
        return block == null ? neutral() : mineral(block);
    }

    /** What an ore nothing is known about reads as: a mid-hardness ore of no recognised class. */
    public static RockProperties neutral() {
        return OreSignatureMath.mineral(
                OreTraits.Category.OTHER, 0.5D, OreSignatureMath.UNKNOWN_DEPTH);
    }

    public static OreSpawnDimensions.SpawnDimension spawnDimension(ResourceLocation dimension) {
        if (dimension.getNamespace().equals("minecraft")) {
            return switch (dimension.getPath()) {
                case "the_nether" -> OreSpawnDimensions.SpawnDimension.NETHER;
                case "the_end" -> OreSpawnDimensions.SpawnDimension.END;
                default -> OreSpawnDimensions.SpawnDimension.OVERWORLD;
            };
        }
        return OreSpawnDimensions.SpawnDimension.UNKNOWN;
    }

    private static Snapshot current() {
        long token = token();
        Snapshot current = snapshot;
        if (current != null && current.token() == token) {
            return current;
        }
        synchronized (OreSignatureTable.class) {
            current = snapshot;
            if (current == null || current.token() != token) {
                current = build(token);
                snapshot = current;
            }
            return current;
        }
    }

    private static long token() {
        return (long) OreGenerationWeights.dataEpoch() * 31L + tagsEpoch;
    }

    private static Snapshot build(long token) {
        // One representative per material: the generated ore where generation is recorded, else
        // the first ore block by id.
        Map<String, Generation> generation = new HashMap<>();
        Map<OreSpawnDimensions.SpawnDimension, Map<String, Generation>> perDimension =
                new EnumMap<>(OreSpawnDimensions.SpawnDimension.class);
        for (OreSpawnDimensions.SpawnDimension dimension :
                OreSpawnDimensions.SpawnDimension.values()) {
            Map<String, Generation> entries = new TreeMap<>();
            for (OreGenerationWeights.OreGenerationData data :
                    OreGenerationWeights.dataFor(dimension)) {
                if (data.materialKey() == null
                        || data.materialKey().isEmpty()
                        || !(data.originalFrequency() > 0.0D)) {
                    continue;
                }
                Generation entry =
                        new Generation(
                                data.oreId(),
                                data.originalFrequency(),
                                data.minY(),
                                data.maxY(),
                                dimension);
                entries.merge(data.materialKey(), entry, Generation::stronger);
                generation.merge(data.materialKey(), entry, Generation::stronger);
            }
            perDimension.put(dimension, entries);
        }

        Map<String, ResourceLocation> representative = new TreeMap<>();
        Map<String, String> materialByOre = new HashMap<>();
        for (Block block : OreTags.oreBlocks()) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            String material = materialOf(block);
            materialByOre.put(id.toString(), material);
            representative.merge(
                    material, id, (a, b) -> a.toString().compareTo(b.toString()) <= 0 ? a : b);
        }
        for (Map.Entry<String, Generation> entry : generation.entrySet()) {
            ResourceLocation oreId = entry.getValue().oreId();
            if (oreId != null && BuiltInRegistries.BLOCK.containsKey(oreId)) {
                representative.put(entry.getKey(), oreId);
                materialByOre.put(oreId.toString(), entry.getKey());
            }
        }

        // Each dimension's rock column as its own ore generation spans it, so depths are relative
        // to the world they form in without assuming any dimension's build height.
        Map<OreSpawnDimensions.SpawnDimension, int[]> spans =
                new EnumMap<>(OreSpawnDimensions.SpawnDimension.class);
        perDimension.forEach(
                (dimension, entries) -> {
                    for (Generation entry : entries.values()) {
                        spans.merge(
                                dimension,
                                new int[] {entry.minY(), entry.maxY()},
                                (a, b) -> new int[] {Math.min(a[0], b[0]), Math.max(a[1], b[1])});
                    }
                });

        List<String> materials = new ArrayList<>(representative.keySet());
        List<OreTraits> traits = new ArrayList<>(materials.size());
        double[] depth = new double[materials.size()];
        for (int i = 0; i < materials.size(); i++) {
            String material = materials.get(i);
            Generation known = generation.get(material);
            traits.add(traits(material, representative.get(material), known));
            depth[i] =
                    known == null
                            ? OreSignatureMath.UNKNOWN_DEPTH
                            : OreSignatureMath.depthIndex(
                                    (known.minY() + known.maxY()) * 0.5D,
                                    spans.get(known.dimension())[0],
                                    spans.get(known.dimension())[1]);
        }
        RockProperties[] computed = OreSignatureMath.minerals(traits, depth);
        Map<String, RockProperties> mineralByMaterial = new HashMap<>();
        Map<String, OreTraits> traitsByMaterial = new HashMap<>();
        for (int i = 0; i < materials.size(); i++) {
            Block block = BuiltInRegistries.BLOCK.get(representative.get(materials.get(i)));
            RockProperties mineral =
                    RockPhysics.explicitFor(block.defaultBlockState()).orElse(computed[i]);
            mineralByMaterial.put(materials.get(i), mineral);
            traitsByMaterial.put(materials.get(i), traits.get(i));
        }

        Map<OreSpawnDimensions.SpawnDimension, List<OreSignature>> byDimension =
                new EnumMap<>(OreSpawnDimensions.SpawnDimension.class);
        Map<OreSpawnDimensions.SpawnDimension, List<OreSignature>> forAnalysis =
                new EnumMap<>(OreSpawnDimensions.SpawnDimension.class);
        for (OreSpawnDimensions.SpawnDimension dimension :
                OreSpawnDimensions.SpawnDimension.values()) {
            Map<String, Generation> local = perDimension.get(dimension);
            List<OreTraits> members = new ArrayList<>();
            List<OreTraits> foreign = new ArrayList<>();
            for (String material : materials) {
                Generation here = local.get(material);
                if (here != null) {
                    OreTraits base = traitsByMaterial.get(material);
                    members.add(
                            new OreTraits(
                                    material,
                                    base.oreId(),
                                    base.category(),
                                    base.toolTier(),
                                    base.hardness(),
                                    here.minY(),
                                    here.maxY(),
                                    here.frequency()));
                } else if (!generation.containsKey(material)) {
                    // Generated nowhere that the game recorded: possible anywhere, but rare.
                    members.add(traitsByMaterial.get(material));
                } else {
                    // Generated in another dimension: as unexpected here as an unrecorded ore.
                    OreTraits base = traitsByMaterial.get(material);
                    foreign.add(
                            new OreTraits(
                                    material,
                                    base.oreId(),
                                    base.category(),
                                    base.toolTier(),
                                    base.hardness(),
                                    0,
                                    -1,
                                    Double.NaN));
                }
            }
            byDimension.put(dimension, signatures(members, mineralByMaterial));
            List<OreTraits> all = new ArrayList<>(members);
            all.addAll(foreign);
            forAnalysis.put(dimension, signatures(all, mineralByMaterial));
        }
        if (OresAndDrillsMod.LOGGER.isDebugEnabled()) {
            for (int i = 0; i < materials.size(); i++) {
                OreTraits ore = traits.get(i);
                OresAndDrillsMod.LOGGER.debug(
                        "Ore signature {}: {} {} tier {} hardness {} depth {} -> {}",
                        ore.materialKey(),
                        ore.oreId(),
                        ore.category(),
                        ore.toolTier(),
                        ore.hardness(),
                        depth[i],
                        mineralByMaterial.get(ore.materialKey()));
            }
        }
        return new Snapshot(
                token,
                Map.copyOf(mineralByMaterial),
                Map.copyOf(materialByOre),
                Map.copyOf(byDimension),
                Map.copyOf(forAnalysis));
    }

    private static List<OreSignature> signatures(
            List<OreTraits> members, Map<String, RockProperties> mineralByMaterial) {
        double[] prevalence = OreSignatureMath.prevalences(members);
        List<OreSignature> signatures = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            signatures.add(
                    new OreSignature(
                            members.get(i),
                            mineralByMaterial.get(members.get(i).materialKey()),
                            prevalence[i]));
        }
        signatures.sort(
                Comparator.comparingDouble((OreSignature s) -> -s.prevalence())
                        .thenComparing(OreSignature::oreId));
        return List.copyOf(signatures);
    }

    private record Generation(
            ResourceLocation oreId,
            double frequency,
            int minY,
            int maxY,
            OreSpawnDimensions.SpawnDimension dimension) {
        Generation stronger(Generation other) {
            if (other.frequency() != frequency()) {
                return other.frequency() > frequency() ? other : this;
            }
            return dimension.ordinal() <= other.dimension().ordinal() ? this : other;
        }
    }

    private static OreTraits traits(String material, ResourceLocation oreId, Generation known) {
        Block block = BuiltInRegistries.BLOCK.get(oreId);
        BlockState state = block.defaultBlockState();
        int tier = 0;
        if (state.is(NEEDS_NETHERITE_TOOL) || state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
            tier = 3;
        } else if (state.is(BlockTags.NEEDS_IRON_TOOL)) {
            tier = 2;
        } else if (state.is(BlockTags.NEEDS_STONE_TOOL)) {
            tier = 1;
        }
        return new OreTraits(
                material,
                oreId.toString(),
                category(material),
                tier,
                block.defaultDestroyTime(),
                known == null ? 0 : known.minY(),
                known == null ? -1 : known.maxY(),
                known == null ? Double.NaN : known.frequency());
    }

    /**
     * Class of a material from the common tags of its products. A material like "netherite_scrap"
     * is also looked up without its trailing qualifier.
     */
    static OreTraits.Category category(String material) {
        for (String name : names(material)) {
            if (hasItems("raw_materials/" + name) || hasItems("ingots/" + name)) {
                return OreTraits.Category.METALLIC;
            }
            if (hasItems("gems/" + name)) {
                return OreTraits.Category.GEM;
            }
            if (hasItems("dusts/" + name)) {
                return OreTraits.Category.DUST;
            }
        }
        return OreTraits.Category.OTHER;
    }

    private static List<String> names(String material) {
        List<String> names = new ArrayList<>(2);
        names.add(material);
        int underscore = material.lastIndexOf('_');
        if (underscore > 0) {
            names.add(material.substring(0, underscore));
        }
        return names;
    }

    private static boolean hasItems(String path) {
        TagKey<Item> tag =
                TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", path));
        return BuiltInRegistries.ITEM.getTag(tag).map(set -> set.size() > 0).orElse(false);
    }

    private static String materialOf(Block block) {
        String material = OreUnifier.materialKeyFor(block);
        if (!material.isEmpty()) {
            return material;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id.getPath().replace("deepslate_", "").replace("_ore", "").toLowerCase(Locale.ROOT);
    }
}
