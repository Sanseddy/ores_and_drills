package dev.client.worldcreation;

import dev.OresAndDrillsMod;
import dev.config.OreDepositConfig;
import dev.config.OreOverrides;
import dev.config.OreWorldSettingsApplier;
import dev.world.level.levelgen.OreSpawnDimensions;
import dev.world.level.levelgen.OreTags;
import dev.world.level.levelgen.OreUnifier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
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

    private final Screen parent;

    private OreOverrideList oreOverrideList;

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
        oreOverrideList = addRenderableWidget(new OreOverrideList(
                this.minecraft,
                this.width,
                listHeight,
                listY,
                canonicalOres,
                configuredOverrides(canonicalOres),
                configuredDisabled(canonicalOres)
        ));

        addRenderableWidget(Button.builder(Component.translatable("gui.ores_and_drills.ore_settings.done"), button -> onDone())
                .bounds(this.width / 2 - 155, this.height - 28, 150, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ores_and_drills.ore_settings.cancel"), button -> onClose())
                .bounds(this.width / 2 + 5, this.height - 28, 150, 20)
                .build());
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
            OresAndDrillsMod.LOGGER.warn("Ore deposits: failed to scan ore worldgen data for the settings screen", exception);
        }
    }

    private static List<Block> canonicalOreBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (Block block : OreTags.oreBlocks()) {
            if (OreUnifier.isCanonicalSource(block)) {
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

    private static Set<ResourceLocation> configuredDisabled(List<Block> blocks) {
        Set<ResourceLocation> disabled = new LinkedHashSet<>();
        List<? extends String> entries;
        try {
            entries = OreDepositConfig.DISABLED_DEPOSIT_ORES.get();
        } catch (IllegalStateException exception) {
            entries = OreDepositConfig.DISABLED_DEPOSIT_ORES.getDefault();
        }

        for (Block block : blocks) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id != null && isDisabledBy(entries, block, id)) {
                disabled.add(id);
            }
        }
        return disabled;
    }

    private static boolean isDisabledBy(List<? extends String> entries, Block block, ResourceLocation blockId) {
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }

            boolean isTag = raw.charAt(0) == '#';
            ResourceLocation id = ResourceLocation.tryParse(isTag ? raw.substring(1) : raw);
            if (id == null) {
                continue;
            }

            if (isTag) {
                if (block.defaultBlockState().is(TagKey.create(Registries.BLOCK, id))) {
                    return true;
                }
            } else if (blockId.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private void onDone() {
        Set<ResourceLocation> disabled = oreOverrideList.collectDisabled();
        List<String> overrides = oreOverrideList.collectOverrides();
        OreWorldSettingsApplier.applyOreWorldSettings(disabled, overrides);
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
