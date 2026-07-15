package dev.config;

import dev.OresAndDrillsMod;
import dev.world.level.levelgen.DepositTerrainValidator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OreSettingsPresetManager implements ResourceManagerReloadListener {
    private static final String DIRECTORY = "ore_settings_presets";
    private static final OreSettingsPresetManager INSTANCE = new OreSettingsPresetManager();
    private static final Settings NEUTRAL = new Settings(true, 1.0F, 1.0F, 1.0F);

    private static volatile Map<ResourceLocation, Preset> presets = Map.of();
    private static volatile Preset activePreset = Preset.EMPTY;
    private static volatile ResourceLocation activePresetId;
    private static volatile int generation;

    private OreSettingsPresetManager() {
    }

    public static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        Map<ResourceLocation, Preset> loaded = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> resources = resourceManager.listResources(
                DIRECTORY,
                location -> location.getPath().endsWith(".json")
        );

        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation file = entry.getKey();
            ResourceLocation presetId = presetId(file);
            if (presetId == null) {
                continue;
            }

            try (Reader reader = entry.getValue().openAsReader()) {
                loaded.put(presetId, parsePreset(GsonHelper.parse(reader)));
            } catch (IOException | JsonParseException | IllegalArgumentException exception) {
                OresAndDrillsMod.LOGGER.warn("Ore settings: failed to load preset {} from {}", presetId, file, exception);
            }
        }

        presets = Map.copyOf(loaded);
        selectActivePreset(configuredPresetId());

        if (activePresetId == null) {
            OresAndDrillsMod.LOGGER.warn(
                    "Ore settings: active preset {} was not found; using neutral settings",
                    configuredPresetId()
            );
        } else {
            OresAndDrillsMod.LOGGER.info("Ore settings: loaded {} datapack preset(s), active preset is {}", loaded.size(), activePresetId);
        }
    }

    public static void refreshActivePreset() {
        selectActivePreset(configuredPresetId());
    }

    private static String configuredPresetId() {
        return OreDepositConfig.SPEC.isLoaded()
                ? OreDepositConfig.ACTIVE_PRESET.get()
                : OreDepositConfig.ACTIVE_PRESET.getDefault();
    }

    private static void selectActivePreset(String configuredValue) {
        ResourceLocation configuredId = ResourceLocation.tryParse(configuredValue);
        Preset selected = configuredId == null ? null : presets.get(configuredId);
        activePresetId = selected == null ? null : configuredId;
        activePreset = selected == null ? Preset.EMPTY : selected;
        generation++;
        DepositTerrainValidator.clearCache();
    }

    public static Settings resolve(Block block) {
        return activePreset.resolve(block);
    }

    public static ResourceLocation activePresetId() {
        return activePresetId;
    }

    /** Changes whenever a datapack reload or config selection replaces the active preset. */
    public static int generation() {
        return generation;
    }

    public static Map<ResourceLocation, Preset> presets() {
        return presets;
    }

    private static ResourceLocation presetId(ResourceLocation file) {
        String prefix = DIRECTORY + "/";
        String path = file.getPath();
        if (!path.startsWith(prefix) || !path.endsWith(".json")) {
            return null;
        }

        String presetPath = path.substring(prefix.length(), path.length() - ".json".length());
        return presetPath.isEmpty() ? null : ResourceLocation.fromNamespaceAndPath(file.getNamespace(), presetPath);
    }

    private static Preset parsePreset(JsonObject root) {
        Patch defaults = root.has("defaults")
                ? parsePatch(GsonHelper.getAsJsonObject(root, "defaults"))
                : Patch.EMPTY;
        List<Rule> rules = new ArrayList<>();
        if (root.has("ores")) {
            JsonArray array = GsonHelper.getAsJsonArray(root, "ores");
            for (JsonElement element : array) {
                JsonObject object = GsonHelper.convertToJsonObject(element, "ore rule");
                List<Selector> selectors = parseSelectors(object);
                if (selectors.isEmpty()) {
                    throw new JsonParseException("Ore preset rule requires 'target' or 'targets'");
                }
                rules.add(new Rule(selectors, parsePatch(object)));
            }
        }
        return new Preset(defaults, List.copyOf(rules));
    }

    private static List<Selector> parseSelectors(JsonObject object) {
        List<Selector> selectors = new ArrayList<>();
        if (object.has("target")) {
            selectors.add(parseSelector(GsonHelper.getAsString(object, "target")));
        }
        if (object.has("targets")) {
            for (JsonElement element : GsonHelper.getAsJsonArray(object, "targets")) {
                selectors.add(parseSelector(GsonHelper.convertToString(element, "target")));
            }
        }
        return List.copyOf(selectors);
    }

    private static Selector parseSelector(String value) {
        boolean tag = value.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(tag ? value.substring(1) : value);
        if (id == null) {
            throw new JsonParseException("Invalid ore selector: " + value);
        }
        return new Selector(id, tag);
    }

    private static Patch parsePatch(JsonObject object) {
        return new Patch(
                optionalBoolean(object, "enabled"),
                optionalMultiplier(object, "frequency"),
                optionalMultiplier(object, "size"),
                optionalMultiplier(object, "richness")
        );
    }

    private static Boolean optionalBoolean(JsonObject object, String key) {
        return object.has(key) ? GsonHelper.getAsBoolean(object, key) : null;
    }

    private static Float optionalMultiplier(JsonObject object, String key) {
        return object.has(key) ? Mth.clamp(GsonHelper.getAsFloat(object, key), 0.1F, 6.0F) : null;
    }

    public record Settings(boolean enabled, float frequency, float size, float richness) {
        private Settings apply(Patch patch) {
            return new Settings(
                    patch.enabled() != null ? patch.enabled() : enabled,
                    patch.frequency() != null ? patch.frequency() : frequency,
                    patch.size() != null ? patch.size() : size,
                    patch.richness() != null ? patch.richness() : richness
            );
        }

        public OreOverrides.OreOverride multipliers() {
            return new OreOverrides.OreOverride(frequency, size, richness);
        }
    }

    public record Preset(Patch defaults, List<Rule> rules) {
        private static final Preset EMPTY = new Preset(Patch.EMPTY, List.of());

        private Settings resolve(Block block) {
            Settings result = NEUTRAL.apply(defaults);
            for (Rule rule : rules) {
                if (rule.matchesTag(block)) {
                    result = result.apply(rule.settings());
                }
            }
            for (Rule rule : rules) {
                if (rule.matchesBlock(block)) {
                    result = result.apply(rule.settings());
                }
            }
            return result;
        }
    }

    public record Rule(List<Selector> targets, Patch settings) {
        private boolean matchesTag(Block block) {
            for (Selector target : targets) {
                if (target.tag() && block.defaultBlockState().is(TagKey.create(Registries.BLOCK, target.id()))) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesBlock(Block block) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            if (blockId == null) {
                return false;
            }
            for (Selector target : targets) {
                if (!target.tag() && target.id().equals(blockId)) {
                    return true;
                }
            }
            return false;
        }
    }

    public record Selector(ResourceLocation id, boolean tag) {
    }

    public record Patch(Boolean enabled, Float frequency, Float size, Float richness) {
        private static final Patch EMPTY = new Patch(null, null, null, null);
    }
}
