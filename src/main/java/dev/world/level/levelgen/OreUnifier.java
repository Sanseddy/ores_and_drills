package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.config.OreDepositConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class OreUnifier {
    private static final String ORE_TAG_PREFIX = "ores/";
    private static final Map<Block, String> OBSERVED_BASE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, String> MATERIAL_KEYS = new ConcurrentHashMap<>();
    private static Map<String, MaterialGroup> groups;
    private static List<String> modPriorities;

    private OreUnifier() {
    }

    /**
     * @param observedBaseKey the block/tag this ore's own worldgen feature actually replaces
     *                        (e.g. "stone", "deepslate"), read from its real {@code RuleTest} target
     *                        rather than guessed from its name. Recorded for reuse whenever this same
     *                        ore later turns up as a unification candidate for a different mod's ore.
     */
    public static Optional<Block> canonicalFor(Block sourceOre, String observedBaseKey) {
        if (observedBaseKey != null) {
            OBSERVED_BASE.put(sourceOre, observedBaseKey);
        }
        return canonicalFor(sourceOre);
    }

    public static Optional<Block> canonicalFor(Block sourceOre) {
        ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(sourceOre);
        if (sourceId == null) {
            return Optional.empty();
        }

        MaterialGroup group = groups().get(materialKey(sourceOre, sourceId));
        if (group == null) {
            return Optional.of(sourceOre);
        }

        if (!group.hasDuplicateNamespaces()) {
            return Optional.of(sourceOre);
        }

        if (group.isCanonicalNamespace(sourceId.getNamespace())) {
            return Optional.ofNullable(group.sameBaseOrDefault(sourceOre, sourceId));
        }

        return Optional.empty();
    }

    /**
     * Resolves the physical block that represents this material, regardless of whether the
     * supplied block is itself allowed to launch worldgen. Data-driven rules use this to turn
     * a common tag such as {@code #c:ores/uranium} into one canonical generated material.
     */
    public static Optional<Block> unifiedBlockFor(Block sourceOre) {
        ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(sourceOre);
        if (sourceId == null) {
            return Optional.empty();
        }
        MaterialGroup group = groups().get(materialKey(sourceOre, sourceId));
        if (group == null || !group.hasDuplicateNamespaces()) {
            return Optional.of(sourceOre);
        }
        return Optional.ofNullable(group.sameBaseOrDefault(sourceOre, sourceId));
    }

    /** One physical representative per material, used by data-driven deposit rules. */
    public static Optional<Block> canonicalMaterialBlockFor(Block sourceOre) {
        ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(sourceOre);
        if (sourceId == null) {
            return Optional.empty();
        }
        MaterialGroup group = groups().get(materialKey(sourceOre, sourceId));
        if (group == null) {
            return Optional.of(sourceOre);
        }
        return Optional.ofNullable(group.defaultBlock());
    }

    /**
     * The member of {@code ore}'s material that vanilla generation puts into {@code host}: the deepslate
     * ore in deepslate and tuff, the stone ore in stone, granite, diorite and andesite, and likewise for
     * netherrack and end stone. The ore's own mod is preferred. Ores of other rock families (a modded
     * rock with its own ore) and materials without such a variant are returned unchanged.
     */
    public static Block variantForHost(Block ore, Block host) {
        String hostBase = vanillaHostBase(host);
        ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(ore);
        if (hostBase == null || oreId == null) {
            return ore;
        }
        String oreBase = resolvedBaseKey(ore, oreId);
        if (oreBase.equals(hostBase) || !isVanillaHostBase(oreBase)) {
            return ore;
        }
        MaterialGroup group = groups().get(materialKey(ore, oreId));
        return group == null ? ore : group.variantFor(oreId.getNamespace(), hostBase).orElse(ore);
    }

    /**
     * The stone member of a deepslate ore's material (iron ore for deepslate iron ore), the ore's own mod
     * preferred; any other ore, or a deepslate ore without a stone variant, is returned unchanged.
     */
    public static Block stoneVariantOfDeepslate(Block ore) {
        ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(ore);
        if (oreId == null || !resolvedBaseKey(ore, oreId).equals("deepslate")) {
            return ore;
        }
        MaterialGroup group = groups().get(materialKey(ore, oreId));
        return group == null ? ore : group.variantFor(oreId.getNamespace(), "stone").orElse(ore);
    }

    private static String vanillaHostBase(Block host) {
        BlockState state = host.defaultBlockState();
        if (state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) {
            return "deepslate";
        }
        if (state.is(BlockTags.STONE_ORE_REPLACEABLES)) {
            return "stone";
        }
        if (state.is(BlockTags.BASE_STONE_NETHER)) {
            return "nether";
        }
        return state.is(Blocks.END_STONE) ? "end" : null;
    }

    private static boolean isVanillaHostBase(String base) {
        return switch (base) {
            case "stone", "deepslate", "nether", "end" -> true;
            default -> false;
        };
    }

    public static boolean isCanonicalSource(Block sourceOre) {
        return canonicalFor(sourceOre).isPresent();
    }

    /** The mod-priority rank used to pick canonical ores (lower sorts first); for a settings-UI sort order that matches unification precedence. */
    public static int modPriorityRank(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? Integer.MAX_VALUE / 2 : priorityIndex(modPriorities(), id.getNamespace());
    }

    /** Tags and datapacks were reloaded; rebuild material membership and priorities on demand. */
    public static void clearCache() {
        groups = null;
        modPriorities = null;
        OBSERVED_BASE.clear();
        MATERIAL_KEYS.clear();
    }

    private static List<String> modPriorities() {
        if (modPriorities == null) {
            modPriorities = loadModPriorities();
        }
        return modPriorities;
    }

    private static Map<String, MaterialGroup> groups() {
        if (groups == null) {
            groups = buildGroups();
        }
        return groups;
    }

    private static Map<String, MaterialGroup> buildGroups() {
        List<String> priorities = modPriorities();
        Map<String, List<OreCandidate>> candidatesByMaterial = new HashMap<>();

        for (Block block : OreTags.oreBlocks()) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            if (blockId == null || blockId.getNamespace().equals(OresAndDrillsMod.MOD_ID)) {
                continue;
            }

            String material = materialKey(block, blockId);
            candidatesByMaterial.computeIfAbsent(material, ignored -> new ArrayList<>())
                    .add(new OreCandidate(blockId, block, priorityIndex(priorities, blockId.getNamespace())));
        }

        Map<String, MaterialGroup> result = new HashMap<>();
        for (Map.Entry<String, List<OreCandidate>> entry : candidatesByMaterial.entrySet()) {
            result.put(entry.getKey(), new MaterialGroup(entry.getValue()));
        }

        OresAndDrillsMod.LOGGER.debug("Ore deposits: built ore unification groups for {} materials", result.size());
        return Map.copyOf(result);
    }

    /** Almost Unified's mod_priorities, when present, are applied before the configured list. */
    public static boolean hasAlmostUnifiedPriorities() {
        return Files.exists(almostUnifiedConfig());
    }

    private static Path almostUnifiedConfig() {
        return FMLPaths.CONFIGDIR.get().resolve("almostunified").resolve("unify.json");
    }

    private static List<String> loadModPriorities() {
        List<String> priorities = new ArrayList<>();
        Path almostUnifiedConfig = almostUnifiedConfig();
        if (Files.exists(almostUnifiedConfig)) {
            try (Reader reader = Files.newBufferedReader(almostUnifiedConfig)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonElement modPriorities = root.get("mod_priorities");
                if (modPriorities != null && modPriorities.isJsonArray()) {
                    for (JsonElement element : modPriorities.getAsJsonArray()) {
                        if (element.isJsonPrimitive()) {
                            priorities.add(element.getAsString());
                        }
                    }
                    OresAndDrillsMod.LOGGER.debug("Ore deposits: loaded Almost Unified mod_priorities from {}", almostUnifiedConfig);
                }
            } catch (RuntimeException | IOException exception) {
                OresAndDrillsMod.LOGGER.debug("Ore deposits: failed to read Almost Unified priorities from {}", almostUnifiedConfig, exception);
            }
        }

        for (String fallback : configuredModPriorities()) {
            if (!priorities.contains(fallback)) {
                priorities.add(fallback);
            }
        }
        return List.copyOf(priorities);
    }

    private static List<? extends String> configuredModPriorities() {
        try {
            return OreDepositConfig.MOD_PRIORITIES.get();
        } catch (IllegalStateException exception) {
            return OreDepositConfig.MOD_PRIORITIES.getDefault();
        }
    }

    private static int priorityIndex(List<String> modPriorities, String namespace) {
        int index = modPriorities.indexOf(namespace);
        return index >= 0 ? index : Integer.MAX_VALUE / 2;
    }

    private static String materialKey(Block block, ResourceLocation oreId) {
        return materialFromTags(block).orElseGet(() -> materialKeyFromName(oreId));
    }

    /** Public entry point for grouping ore blocks by material (e.g. for a settings UI), reusing the same tag-first/name-fallback resolution as unification. */
    public static String materialKeyFor(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? "" : materialKey(block, id);
    }

    /** Cached {@link #materialKeyFor(Block)} by block id for hot index lookups; reset when tags reload. */
    public static String materialKeyFor(ResourceLocation oreId) {
        return MATERIAL_KEYS.computeIfAbsent(oreId, id -> BuiltInRegistries.BLOCK.containsKey(id)
                ? materialKeyFor(BuiltInRegistries.BLOCK.get(id))
                : "");
    }

    /** {@code c:ores/<material>} of the block, or of its item when only the item is tagged. */
    private static Optional<String> materialFromTags(Block block) {
        return OreTags.oreTagIds(block)
                .filter(location -> location.getPath().startsWith(ORE_TAG_PREFIX)
                        && location.getPath().length() > ORE_TAG_PREFIX.length())
                .map(location -> location.getPath().substring(ORE_TAG_PREFIX.length()).toLowerCase(Locale.ROOT))
                .findFirst();
    }

    private static String materialKeyFromName(ResourceLocation oreId) {
        String path = oreId.getPath().toLowerCase(Locale.ROOT);
        path = stripSuffix(path, "_ore");
        path = stripSuffix(path, "_ores");
        path = stripPrefix(path, "deepslate_");
        path = stripPrefix(path, "nether_");
        path = stripPrefix(path, "end_");
        path = stripPrefix(path, "raw_");
        path = stripPrefix(path, "poor_");
        path = stripPrefix(path, "dense_");
        path = stripPrefix(path, "ore_");

        if (path.contains("uranium") || path.contains("uraninite") || path.contains("uran")) {
            return "uranium";
        }

        int separator = path.lastIndexOf('_');
        if (separator >= 0 && separator < path.length() - 1 && isOreBasePrefix(path.substring(0, separator))) {
            path = path.substring(separator + 1);
        }

        return path;
    }

    private static String resolvedBaseKey(Block block, ResourceLocation blockId) {
        String observed = OBSERVED_BASE.get(block);
        return observed != null ? observed : baseKeyFromName(blockId);
    }

    private static String baseKeyFromName(ResourceLocation oreId) {
        String path = oreId.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("deepslate")) {
            return "deepslate";
        }
        if (path.contains("nether") || path.contains("netherrack")) {
            return "nether";
        }
        if (path.contains("end")) {
            return "end";
        }
        return "stone";
    }

    private static boolean isOreBasePrefix(String prefix) {
        return switch (prefix) {
            case "stone", "deepslate", "nether", "netherrack", "end", "end_stone", "granite", "diorite", "andesite", "tuff",
                 "limestone", "scoria", "asurine", "veridium", "crimsite", "ochrum", "radrock" -> true;
            default -> false;
        };
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private static String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private record OreCandidate(ResourceLocation id, Block block, int priority) {
    }

    private static final class MaterialGroup {
        private final List<OreCandidate> candidates;
        private final OreCandidate defaultCandidate;
        private final Set<String> namespaces;

        private MaterialGroup(List<OreCandidate> candidates) {
            this.candidates = List.copyOf(candidates);
            this.defaultCandidate = chooseDefaultCandidate(this.candidates);
            this.namespaces = new HashSet<>();
            for (OreCandidate candidate : candidates) {
                namespaces.add(candidate.id().getNamespace());
            }
        }

        private boolean hasDuplicateNamespaces() {
            return namespaces.size() > 1;
        }

        private boolean isCanonicalNamespace(String namespace) {
            return defaultCandidate != null && defaultCandidate.id().getNamespace().equals(namespace);
        }

        private Block sameBaseOrDefault(Block sourceBlock, ResourceLocation sourceId) {
            if (defaultCandidate == null) {
                return null;
            }

            String sourceBase = resolvedBaseKey(sourceBlock, sourceId);
            return candidates.stream()
                    .filter(candidate -> candidate.id().getNamespace().equals(defaultCandidate.id().getNamespace()))
                    .filter(candidate -> resolvedBaseKey(candidate.block(), candidate.id()).equals(sourceBase))
                    .min(candidateComparator())
                    .orElse(defaultCandidate)
                    .block();
        }

        /** The {@code base} member from {@code namespace} if it has one, otherwise the best other mod's. */
        private Optional<Block> variantFor(String namespace, String base) {
            return candidates.stream()
                    .filter(candidate -> resolvedBaseKey(candidate.block(), candidate.id()).equals(base))
                    .min(Comparator.comparing((OreCandidate candidate) -> !candidate.id().getNamespace().equals(namespace))
                            .thenComparing(candidateComparator()))
                    .map(OreCandidate::block);
        }

        private Block defaultBlock() {
            return defaultCandidate == null ? null : defaultCandidate.block();
        }

        private static OreCandidate chooseDefaultCandidate(List<OreCandidate> candidates) {
            return candidates.stream()
                    .min(candidateComparator())
                    .orElse(null);
        }

        private static Comparator<OreCandidate> candidateComparator() {
            return Comparator.comparingInt(OreCandidate::priority)
                    .thenComparingInt(candidate -> basePreference(
                            resolvedBaseKey(candidate.block(), candidate.id())
                    ))
                    .thenComparing(candidate -> candidate.id().toString());
        }

        private static int basePreference(String base) {
            return switch (base) {
                case "stone" -> 0;
                case "deepslate" -> 1;
                default -> 2;
            };
        }
    }
}
