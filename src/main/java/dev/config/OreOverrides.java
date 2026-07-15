package dev.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Resolves per-world frequency/size/richness multipliers persisted in {@code level.dat}. */
public final class OreOverrides {
    public static final OreOverride DEFAULT = new OreOverride(1.0F, 1.0F, 1.0F);
    private static volatile List<String> worldSource = List.of();
    private static volatile List<String> cachedSource = List.of();
    private static volatile List<Entry> cachedEntries = List.of();
    /** Per-block memoization of {@link #lookupConfigured}, reset whenever the raw config list changes.
     * Selection weighting re-resolves every ore's override on every deposit-attempt slot, so without this
     * it re-scans the whole override list (tag matching included) per ore per attempt. */
    private static volatile ConcurrentMap<Block, Optional<OreOverride>> blockCache = new ConcurrentHashMap<>();
    private static volatile int generation;

    private OreOverrides() {
    }

    /** Bumped whenever the raw override config list actually changes; lets other caches key off it. */
    public static int generation() {
        parsedEntries();
        return generation;
    }

    /** Replaces the active world's raw entries; called while a world is created or its level.dat is read. */
    public static void setWorldOverrides(List<String> entries) {
        List<String> sanitized = entries == null ? List.of() : List.copyOf(entries);
        if (sanitized.equals(worldSource)) {
            return;
        }
        synchronized (OreOverrides.class) {
            if (sanitized.equals(worldSource)) {
                return;
            }
            worldSource = sanitized;
            cachedSource = List.of();
            cachedEntries = List.of();
            blockCache = new ConcurrentHashMap<>();
            generation++;
        }
    }

    /** Snapshot used by level-data serialization when a new world is first saved. */
    public static List<String> worldOverrides() {
        return worldSource;
    }

    public static OreOverride lookup(Block block) {
        return lookupConfigured(block).orElse(DEFAULT);
    }

    public static Optional<OreOverride> lookupConfigured(Block block) {
        parsedEntries();
        return blockCache.computeIfAbsent(block, OreOverrides::resolveConfigured);
    }

    private static Optional<OreOverride> resolveConfigured(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) {
            return Optional.empty();
        }

        String idString = id.toString();
        OreOverride byId = null;
        OreOverride byTag = null;
        for (Entry entry : parsedEntries()) {
            if (entry.isTag()) {
                if (byTag == null && block.defaultBlockState().is(TagKey.create(Registries.BLOCK, entry.tagOrBlockId()))) {
                    byTag = entry.override();
                }
            } else if (entry.tagOrBlockId().toString().equals(idString)) {
                byId = entry.override();
            }
        }

        return Optional.ofNullable(byId != null ? byId : byTag);
    }

    private static List<Entry> parsedEntries() {
        List<String> source = List.copyOf(configuredOverrides());
        if (source.equals(cachedSource)) {
            return cachedEntries;
        }

        synchronized (OreOverrides.class) {
            if (source.equals(cachedSource)) {
                return cachedEntries;
            }
            List<Entry> entries = parseEntries(source);
            cachedSource = source;
            cachedEntries = List.copyOf(entries);
            blockCache = new ConcurrentHashMap<>();
            generation++;
            return cachedEntries;
        }
    }

    private static List<Entry> parseEntries(List<String> source) {
        List<Entry> entries = new ArrayList<>();
        for (String raw : source) {
            Entry entry = parse(raw);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static List<String> configuredOverrides() {
        return worldSource;
    }

    private static Entry parse(String raw) {
        String[] parts = OreDepositConfig.splitOverride(raw);
        if (parts.length != 4) {
            return null;
        }

        boolean isTag = parts[0].startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(isTag ? parts[0].substring(1) : parts[0]);
        if (id == null) {
            return null;
        }

        try {
            float frequency = clampMultiplier(Float.parseFloat(parts[1]));
            float size = clampMultiplier(Float.parseFloat(parts[2]));
            float richness = clampMultiplier(Float.parseFloat(parts[3]));
            return new Entry(id, isTag, new OreOverride(frequency, size, richness));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record Entry(ResourceLocation tagOrBlockId, boolean isTag, OreOverride override) {
    }

    private static float clampMultiplier(float value) {
        return Float.isFinite(value) ? Math.max(0.1F, Math.min(6.0F, value)) : 1.0F;
    }

    public record OreOverride(float frequency, float size, float richness) {
    }
}
