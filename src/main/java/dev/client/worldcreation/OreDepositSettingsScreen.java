package dev.client.worldcreation;

import dev.OresAndDrillsMod;
import dev.config.OreGenerationMode;
import dev.config.OreOverrides;
import dev.config.OreWorldDisabledOres;
import dev.config.OreWorldSettingsApplier;
import dev.world.level.levelgen.OreSpawnDimensions;
import dev.world.level.levelgen.OreTags;
import dev.world.level.levelgen.OreUnifier;
import dev.world.level.levelgen.ReplaceOreFeaturesBiomeModifier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Factorio-style ore generation settings editor, opened from a button injected onto CreateWorldScreen. */
public final class OreDepositSettingsScreen extends Screen {
    private static final int MARGIN = 20;
    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_GAP = 5;
    private static final int CORNER_MARGIN = 8;

    private final Screen parent;

    private OreOverrideList oreOverrideList;
    /** Unsaved edits kept while the mod priorities screen is open; returning rebuilds every row. */
    private PendingEdits pending;

    private record PendingEdits(Set<ResourceLocation> disabled, Map<ResourceLocation, OreOverrides.OreOverride> overrides,
                                Set<String> dimensionDisabled) {
    }

    /** Survives init() rebuilds (resize, mod priorities screen) like the list's pending edits. */
    private boolean vanillaLikeGeneration = OreGenerationMode.vanillaLikeGeneration();

    public OreDepositSettingsScreen(Screen parent) {
        super(Component.translatable("gui.ores_and_drills.ore_settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int listY = MARGIN + 32;
        int listHeight = Math.max(60, this.height - listY - 36);
        scanOreSpawnDimensions();
        List<Block> canonicalOres = canonicalOreBlocks();
        // Edits are stored per ore member, so they still apply when a priority change picks another
        // member of the same material as its canonical row.
        oreOverrideList = addRenderableWidget(new OreOverrideList(
                this.minecraft,
                this.width,
                listHeight,
                listY,
                canonicalOres,
                pending != null ? pending.overrides() : configuredOverrides(canonicalOres),
                pending != null ? pending.disabled() : configuredDisabled(canonicalOres),
                pending != null ? pending.dimensionDisabled() : null
        ));

        int rowY = this.height - 28;
        Checkbox vanillaLikeCheckbox = Checkbox.builder(
                        Component.translatable("gui.ores_and_drills.ore_settings.vanilla_like"), this.font)
                .selected(vanillaLikeGeneration)
                .onValueChange((checkbox, value) -> vanillaLikeGeneration = value)
                .tooltip(Tooltip.create(Component.translatable("gui.ores_and_drills.ore_settings.vanilla_like.tooltip")))
                .build();
        // Bottom-right corner, vertically centred on the button row.
        int checkboxX = this.width - CORNER_MARGIN - vanillaLikeCheckbox.getWidth();
        vanillaLikeCheckbox.setPosition(checkboxX, rowY + (20 - vanillaLikeCheckbox.getHeight()) / 2);

        // On a narrow window the centred row would run under the checkbox; shift it left just enough.
        int rowX = Math.max(CORNER_MARGIN,
                Math.min(this.width / 2 - 155, checkboxX - BUTTON_GAP - (BUTTON_WIDTH * 3 + BUTTON_GAP * 2)));
        addRenderableWidget(Button.builder(Component.translatable("gui.ores_and_drills.mod_priorities.title"), button -> openModPriorities())
                .bounds(rowX, rowY, BUTTON_WIDTH, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ores_and_drills.ore_settings.done"), button -> onDone())
                .bounds(rowX + BUTTON_WIDTH + BUTTON_GAP, rowY, BUTTON_WIDTH, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ores_and_drills.ore_settings.cancel"), button -> onClose())
                .bounds(rowX + (BUTTON_WIDTH + BUTTON_GAP) * 2, rowY, BUTTON_WIDTH, 20)
                .build());
        addRenderableWidget(vanillaLikeCheckbox);
    }

    private void openModPriorities() {
        capturePending();
        this.minecraft.setScreen(new ModPrioritiesScreen(this));
    }

    @Override
    public void resize(net.minecraft.client.Minecraft minecraft, int width, int height) {
        // Resizing re-runs init(); without this the rebuilt list would drop unsaved edits.
        capturePending();
        super.resize(minecraft, width, height);
    }

    private void capturePending() {
        if (oreOverrideList == null) return;
        Map<ResourceLocation, OreOverrides.OreOverride> overrides = new LinkedHashMap<>();
        for (String entry : oreOverrideList.collectOverrides()) {
            String[] parts = entry.split("\\|");
            ResourceLocation id = parts.length == 4 ? ResourceLocation.tryParse(parts[0]) : null;
            if (id == null) continue;
            try {
                overrides.put(id, new OreOverrides.OreOverride(
                        Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Float.parseFloat(parts[3])));
            } catch (NumberFormatException ignored) {
                // collectOverrides only writes numbers; skip anything unexpected.
            }
        }
        pending = new PendingEdits(oreOverrideList.collectDisabled(), overrides,
                new LinkedHashSet<>(oreOverrideList.collectDimensionOverrides()));
    }

    /**
     * This walks every biome's (simulated) generation settings and reflects into arbitrary third-party
     * {@code FeatureConfiguration} classes to find ore data — inherently fragile, since some mods back a
     * feature's size with a lazy supplier tied to their own config (e.g. Mekanism's {@code CachedIntValue}),
     * which throws if read before that config is loaded (as it isn't yet, here on the create-world screen).
     * Inner call sites already guard against that specific case, but this outer guard exists so that no
     * unanticipated exception from any mod's worldgen data can ever crash the game just from opening this
     * screen — worst case, the per-ore dimension/rarity hints are simply unavailable for this session.
     */
    private void scanOreSpawnDimensions() {
        if (!(parent instanceof CreateWorldScreen createWorldScreen)) {
            return;
        }

        try {
            WorldCreationContext settings = createWorldScreen.getUiState().getSettings();
            OreSpawnDimensions.scanOriginalPlacedFeatures(
                    settings.worldgenLoadContext(),
                    settings.selectedDimensions().bake(settings.datapackDimensions()).dimensions()
            );
        } catch (RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug("Ore deposits: failed to scan ore worldgen data for the settings screen", exception);
        }
    }

    private static List<Block> canonicalOreBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (Block block : OreTags.oreBlocks()) {
            // Ignored ores are left to their own worldgen, so the mod has no settings for them.
            if (OreUnifier.isCanonicalSource(block) && !ReplaceOreFeaturesBiomeModifier.isOreIgnored(block)) {
                blocks.add(block);
            }
        }
        blocks.sort(Comparator.comparing(OreUnifier::materialKeyFor));
        return blocks;
    }

    private static Map<ResourceLocation, OreOverrides.OreOverride> configuredOverrides(List<Block> blocks) {
        Map<ResourceLocation, OreOverrides.OreOverride> overrides = new LinkedHashMap<>();
        for (Block block : blocks) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) {
                continue;
            }

            OreOverrides.lookupConfigured(block).ifPresent(override -> overrides.put(id, override));
        }
        return overrides;
    }

    /** Ores already switched off for the world being created (empty for a fresh world). */
    private static Set<ResourceLocation> configuredDisabled(List<Block> blocks) {
        Set<ResourceLocation> disabled = new LinkedHashSet<>();
        for (Block block : blocks) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id != null && OreWorldDisabledOres.isDisabled(block)) {
                disabled.add(id);
            }
        }
        return disabled;
    }

    private void onDone() {
        Set<ResourceLocation> disabled = oreOverrideList.collectDisabled();
        List<String> overrides = oreOverrideList.collectOverrides();
        List<String> dimensionOverrides = oreOverrideList.collectDimensionOverrides();
        OreWorldSettingsApplier.applyOreWorldSettings(disabled, overrides, dimensionOverrides, vanillaLikeGeneration);
        this.minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // The world-creation blur and widget backgrounds render below this final foreground pass.
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 300.0F);
        int headerHeight = MARGIN + 32;
        int titleY = (headerHeight - this.font.lineHeight) / 2;
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, titleY, 0xFFFFFF);
        guiGraphics.pose().popPose();
    }
}
