package dev.world.level.levelgen;

import dev.registry.ModBlockTags;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Ore-catalog helpers for deterministic deposit candidates. */
public final class DepositCandidateResolver {
    private static final String MATERIAL_ORE_TAG_PREFIX = "ores/";
    private static final ResourceLocation INTERNAL_DEPOSIT_BLOCK = ResourceLocation.fromNamespaceAndPath(
            dev.OresAndDrillsMod.MOD_ID, "ore_deposit"
    );

    private DepositCandidateResolver() {
    }

    public static boolean isOreCatalogLoaded() {
        return BuiltInRegistries.BLOCK.getTag(ModBlockTags.ORES).isPresent();
    }

    public static boolean isTaggedOre(ResourceLocation id) {
        return !INTERNAL_DEPOSIT_BLOCK.equals(id)
                && BuiltInRegistries.BLOCK.containsKey(id)
                && OreTags.isOre(BuiltInRegistries.BLOCK.get(id).defaultBlockState());
    }

    /** Accepts both convention-tagged ores and blocks owned by a live data-driven rule. */
    public static boolean isKnownOre(ServerLevel level, ResourceLocation id) {
        if (INTERNAL_DEPOSIT_BLOCK.equals(id) || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return false;
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        return OreTags.isOre(block.defaultBlockState())
                || DataDrivenOreDepositBiomeModifier.explicitlyConfigured(level, block);
    }

    /** Current server-tag catalog used by validation, command parsing and suggestions. */
    public static List<ResourceLocation> oreCatalog() {
        List<ResourceLocation> ores = new ArrayList<>();
        for (Block block : OreTags.oreBlocks()) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id != null && !INTERNAL_DEPOSIT_BLOCK.equals(id)) {
                ores.add(id);
            }
        }
        ores.sort(Comparator.comparing(ResourceLocation::toString));
        return List.copyOf(ores);
    }

    /** Live catalog including direct ids selected by datapack/KubeJS rules without ore tags. */
    public static List<ResourceLocation> oreCatalog(ServerLevel level) {
        Set<ResourceLocation> ores = new LinkedHashSet<>(oreCatalog());
        ores.addAll(DataDrivenOreDepositBiomeModifier.configuredOreCatalog(level));
        return ores.stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();
    }

    /**
     * Returns one populated convention tag per installed ore material. This is rebuilt from the
     * live block-tag registry for every command suggestion request, so mod and datapack reloads are
     * reflected without maintaining a second catalog.
     */
    public static List<ResourceLocation> oreTagCatalog() {
        Map<String, ResourceLocation> preferredByMaterial = new HashMap<>();
        BuiltInRegistries.BLOCK.getTags().forEach(pair -> {
            ResourceLocation tagId = pair.getFirst().location();
            if (!isMaterialOreTag(tagId) || oreForTag(tagId).isEmpty()) {
                return;
            }

            String material = tagId.getPath().substring(MATERIAL_ORE_TAG_PREFIX.length());
            preferredByMaterial.merge(material, tagId, DepositCandidateResolver::preferConventionTag);
        });

        return preferredByMaterial.values().stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();
    }

    /**
     * Command suggestions prefer material tags. A canonical ore that is not represented by any
     * usable material tag falls back to its block id, so imperfectly tagged mod ores remain locatable.
     */
    public static List<String> oreSuggestionCatalog(ServerLevel level) {
        List<ResourceLocation> materialTags = oreTagCatalog();
        Set<ResourceLocation> representedOres = new LinkedHashSet<>();
        for (ResourceLocation tagId : materialTags) {
            oreForTag(tagId).ifPresent(representedOres::add);
        }

        Set<ResourceLocation> fallbackOreIds = new LinkedHashSet<>();
        for (ResourceLocation oreId : oreCatalog(level)) {
            Block ore = BuiltInRegistries.BLOCK.get(oreId);
            Block canonical = OreUnifier.canonicalMaterialBlockFor(ore).orElse(ore);
            ResourceLocation canonicalId = BuiltInRegistries.BLOCK.getKey(canonical);
            if (canonicalId != null && !representedOres.contains(canonicalId)) {
                fallbackOreIds.add(canonicalId);
            }
        }

        List<String> suggestions = new ArrayList<>(materialTags.size() + fallbackOreIds.size());
        materialTags.stream()
                .map(tag -> "#" + tag)
                .sorted()
                .forEach(suggestions::add);
        fallbackOreIds.stream()
                .map(ResourceLocation::toString)
                .sorted()
                .forEach(suggestions::add);
        return List.copyOf(suggestions);
    }

    /** Resolves a populated material tag to the same canonical physical ore used by worldgen. */
    public static Optional<ResourceLocation> oreForTag(ResourceLocation tagId) {
        if (!isMaterialOreTag(tagId)) {
            return Optional.empty();
        }

        TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
        List<ResourceLocation> canonicalOres = new ArrayList<>();
        for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
            Block block = holder.value();
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            if (blockId == null || INTERNAL_DEPOSIT_BLOCK.equals(blockId) || !OreTags.isOre(block.defaultBlockState())) {
                continue;
            }

            Block canonical = OreUnifier.canonicalMaterialBlockFor(block).orElse(block);
            ResourceLocation canonicalId = BuiltInRegistries.BLOCK.getKey(canonical);
            if (canonicalId != null && !canonicalOres.contains(canonicalId)) {
                canonicalOres.add(canonicalId);
            }
        }

        // A material tag must describe exactly one unified material. Ambiguous aggregate or malformed
        // tags are deliberately not offered and cannot silently locate an arbitrary ore.
        return canonicalOres.size() == 1 ? Optional.of(canonicalOres.getFirst()) : Optional.empty();
    }

    private static boolean isMaterialOreTag(ResourceLocation tagId) {
        String namespace = tagId.getNamespace();
        return (namespace.equals("c") || namespace.equals("neoforge") || namespace.equals("forge"))
                && tagId.getPath().startsWith(MATERIAL_ORE_TAG_PREFIX)
                && tagId.getPath().length() > MATERIAL_ORE_TAG_PREFIX.length();
    }

    private static ResourceLocation preferConventionTag(ResourceLocation first, ResourceLocation second) {
        int firstPriority = conventionNamespacePriority(first.getNamespace());
        int secondPriority = conventionNamespacePriority(second.getNamespace());
        if (firstPriority != secondPriority) {
            return firstPriority < secondPriority ? first : second;
        }
        return first.toString().compareTo(second.toString()) <= 0 ? first : second;
    }

    private static int conventionNamespacePriority(String namespace) {
        return switch (namespace) {
            case "c" -> 0;
            case "neoforge" -> 1;
            case "forge" -> 2;
            default -> 3;
        };
    }
}
