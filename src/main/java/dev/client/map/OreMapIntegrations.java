package dev.client.map;

import dev.OresAndDrillsMod;
import dev.compat.ftbchunks.FtbChunksOreLayer;
import dev.compat.journeymap.OreMapJourneyMapPlugin;
import dev.compat.xaero.XaeroMinimapOreLayer;
import dev.compat.xaero.XaeroWorldMapOreLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/**
 * Entry point for every map integration. Each compat class is touched only after its mod is confirmed
 * present, so none of the optional map APIs is ever class-loaded without its mod.
 */
public final class OreMapIntegrations {
    private static final ResourceLocation TOGGLE_ICON = ResourceLocation.fromNamespaceAndPath(
            OresAndDrillsMod.MOD_ID, "textures/gui/ore_map/ore_map_toggle.png");
    private static boolean xaeroWorldMap;
    private static boolean xaeroMinimap;
    private static boolean journeyMap;
    private static boolean ftbChunks;

    private OreMapIntegrations() {
    }

    public static void init() {
        ModList mods = ModList.get();
        xaeroWorldMap = mods.isLoaded("xaeroworldmap");
        xaeroMinimap = mods.isLoaded("xaerominimap") || mods.isLoaded("xaerominimapfair");
        journeyMap = mods.isLoaded("journeymap");
        ftbChunks = mods.isLoaded("ftbchunks");
        if (ftbChunks) {
            try {
                FtbChunksOreLayer.register();
            } catch (LinkageError error) {
                ftbChunks = disable("FTB Chunks", error);
            }
        }
    }

    public static void tick() {
        OreMapDiscoveryClient.tick();
        if (xaeroWorldMap) {
            try {
                XaeroWorldMapOreLayer.ensureRegistered();
            } catch (LinkageError error) {
                xaeroWorldMap = disable("Xaero's World Map", error);
            }
        }
        if (xaeroMinimap) {
            try {
                XaeroMinimapOreLayer.ensureRegistered();
            } catch (LinkageError error) {
                xaeroMinimap = disable("Xaero's Minimap", error);
            }
        }
        if (journeyMap) {
            try {
                OreMapJourneyMapPlugin.tick();
            } catch (LinkageError error) {
                journeyMap = disable("JourneyMap", error);
            }
        }
    }

    /** An installed map release whose API differs from the one compiled against must not take the game down. */
    private static boolean disable(String mapMod, LinkageError error) {
        OresAndDrillsMod.LOGGER.warn("Ore deposits cannot be shown on this {} version", mapMod, error);
        return false;
    }

    public static void renderScreen(Screen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        ToggleButton button = toggleButton(screen);
        if (button == null) {
            return;
        }
        boolean overButton = button.contains(mouseX, mouseY);
        if (xaeroWorldMap && !overButton && XaeroWorldMapOreLayer.isMapScreen(screen)) {
            XaeroWorldMapOreLayer.renderTooltip(graphics, mouseX, mouseY);
        }
        button.render(graphics, overButton);
        if (overButton) {
            graphics.renderTooltip(Minecraft.getInstance().font, Component.translatable(
                    OreMapMarkers.isEnabled() ? "gui.ores_and_drills.ore_map.hide" : "gui.ores_and_drills.ore_map.show"
            ), mouseX, mouseY);
        }
    }

    public static boolean click(Screen screen, double mouseX, double mouseY, int mouseButton) {
        ToggleButton button = toggleButton(screen);
        if (mouseButton != 0 || button == null || !button.contains(mouseX, mouseY)) {
            return false;
        }
        OreMapMarkers.toggle();
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        return true;
    }

    /** JourneyMap gets a native toolbar button instead; Xaero and FTB have no API for one. */
    private static ToggleButton toggleButton(Screen screen) {
        if (xaeroWorldMap && XaeroWorldMapOreLayer.isMapScreen(screen)) {
            // Top of Xaero's right-hand button column, which ends with zoom in at height - 180.
            return new ToggleButton(screen.width - 20, screen.height - 200, 20, true);
        }
        if (ftbChunks && FtbChunksOreLayer.isLargeMap(screen)) {
            // Below FTB's left-hand column (claims, waypoints, info, dimension, death points).
            return new ToggleButton(1, 91, 16, false);
        }
        return null;
    }

    private record ToggleButton(int x, int y, int size, boolean framed) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
        }

        void render(GuiGraphics graphics, boolean hovered) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 400.0F);
            if (framed) {
                graphics.fill(x, y, x + size, y + size, hovered ? 0xC0505050 : 0x90000000);
            } else if (hovered) {
                graphics.fill(x, y, x + size, y + size, 0x40FFFFFF);
            }
            int iconX = x + (size - 16) / 2;
            int iconY = y + (size - 16) / 2;
            boolean enabled = OreMapMarkers.isEnabled();
            if (!enabled) {
                graphics.setColor(0.45F, 0.45F, 0.45F, 1.0F);
            }
            graphics.blit(TOGGLE_ICON, iconX, iconY, 0.0F, 0.0F, 16, 16, 16, 16);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            if (!enabled) {
                // A red strike-through, the usual "layer hidden" cue.
                for (int step = 0; step < 14; step++) {
                    graphics.fill(iconX + 1 + step, iconY + 14 - step, iconX + 3 + step, iconY + 15 - step, 0xFFE03C31);
                }
            }
            graphics.pose().popPose();
        }
    }
}
