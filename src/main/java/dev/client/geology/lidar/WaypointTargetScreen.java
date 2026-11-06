package dev.client.geology.lidar;

import dev.client.geology.VanillaUi;
import dev.client.geology.layout.Rect;
import dev.client.map.MapWaypoints;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Asks which installed map mod should receive a waypoint, then returns to the survey screen. */
public final class WaypointTargetScreen extends Screen {
    private static final int BUTTON_WIDTH = 180;
    private final Screen parent;
    private final MapWaypoints.Request request;
    private final List<MapWaypoints.Target> targets;

    WaypointTargetScreen(
            Screen parent, MapWaypoints.Request request, List<MapWaypoints.Target> targets) {
        super(LidarObjectActions.text("waypoint.choose"));
        this.parent = parent;
        this.request = request;
        this.targets = List.copyOf(targets);
    }

    private Rect panel() {
        int width = Math.min(this.width - 16, BUTTON_WIDTH + 24);
        int height = 44 + (targets.size() + 1) * 24;
        return new Rect((this.width - width) / 2, (this.height - height) / 2, width, height);
    }

    @Override
    protected void init() {
        Rect panel = panel();
        int buttonWidth = panel.width() - 24;
        int y = panel.y() + 34;
        for (MapWaypoints.Target target : targets) {
            addRenderableWidget(
                    Button.builder(
                                    target.label(),
                                    button -> {
                                        onClose();
                                        LidarObjectActions.place(target, request);
                                    })
                            .bounds(panel.x() + 12, y, buttonWidth, 20)
                            .build());
            y += 24;
        }
        addRenderableWidget(
                Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                        .bounds(panel.x() + 12, y, buttonWidth, 20)
                        .build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        Rect panel = panel();
        VanillaUi.panel(graphics, panel);
        graphics.drawString(
                font,
                title,
                panel.x() + (panel.width() - font.width(title)) / 2,
                panel.y() + 8,
                VanillaUi.TEXT,
                false);
        Component name = Component.literal(MapWaypoints.label(request));
        graphics.drawString(
                font,
                font.plainSubstrByWidth(name.getString(), panel.width() - 16),
                panel.x() + 8,
                panel.y() + 20,
                VanillaUi.TEXT_ACCENT,
                false);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
