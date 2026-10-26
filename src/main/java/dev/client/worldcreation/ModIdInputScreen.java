package dev.client.worldcreation;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

/**
 * Asks for one mod id for the priority list. Ids that are malformed or already listed can't be
 * confirmed; mods that aren't installed are allowed (packs may add them later) with a warning.
 * Installed ore mods that aren't listed yet are offered as one-click picks.
 */
final class ModIdInputScreen extends Screen {
    private static final String KEY = "gui.ores_and_drills.mod_priorities.";

    /** NeoForge mod id format. */
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");

    private static final int FIELD_WIDTH = 220;
    private static final int PICK_WIDTH = 106;
    private static final int MAX_PICK_ROWS = 6;

    private final Screen parent;
    private final String initial;
    private final Set<String> taken;
    private final List<String> suggestions;
    private final Consumer<String> onDone;
    private EditBox field;
    private Button done;
    private Component status = Component.empty();
    private int statusColor;

    ModIdInputScreen(
            Screen parent,
            Component title,
            String initial,
            Set<String> taken,
            List<String> suggestions,
            Consumer<String> onDone) {
        super(title);
        this.parent = parent;
        this.initial = initial;
        this.taken = taken;
        this.suggestions = suggestions;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - FIELD_WIDTH / 2;
        int top = this.height / 2 - 70;
        String text = field == null ? initial : field.getValue();
        field =
                addRenderableWidget(
                        new EditBox(
                                this.font,
                                left,
                                top,
                                FIELD_WIDTH,
                                20,
                                Component.translatable(KEY + "mod_id")));
        field.setMaxLength(64);
        field.setValue(text);
        field.setResponder(value -> validate());
        setInitialFocus(field);

        // Quick picks: two columns under the field, capped so the dialog never runs off screen.
        int rows = Math.max(0, Math.min(MAX_PICK_ROWS, (this.height - 36 - (top + 44)) / 22));
        int shown = Math.min(suggestions.size(), rows * 2);
        for (int index = 0; index < shown; index++) {
            String mod = suggestions.get(index);
            int x = this.width / 2 - PICK_WIDTH - 2 + (index % 2) * (PICK_WIDTH + 4);
            int y = top + 44 + (index / 2) * 22;
            String label = ModPrioritiesScreen.modName(mod).getString();
            if (this.font.width(label) > PICK_WIDTH - 8) {
                label =
                        this.font.plainSubstrByWidth(label, PICK_WIDTH - 8 - this.font.width("…"))
                                + "…";
            }
            addRenderableWidget(
                    Button.builder(Component.literal(label), button -> field.setValue(mod))
                            .bounds(x, y, PICK_WIDTH, 20)
                            .build());
        }

        int buttonY = this.height - 28;
        done =
                addRenderableWidget(
                        Button.builder(
                                        Component.translatable(
                                                "gui.ores_and_drills.ore_settings.done"),
                                        button -> {
                                            onDone.accept(field.getValue().trim());
                                            this.minecraft.setScreen(parent);
                                        })
                                .bounds(this.width / 2 - 105, buttonY, 100, 20)
                                .build());
        addRenderableWidget(
                Button.builder(
                                Component.translatable("gui.ores_and_drills.ore_settings.cancel"),
                                button -> onClose())
                        .bounds(this.width / 2 + 5, buttonY, 100, 20)
                        .build());
        validate();
    }

    private void validate() {
        String mod = field.getValue().trim();
        boolean valid = MOD_ID.matcher(mod).matches() && !taken.contains(mod);
        if (mod.isEmpty()) {
            status = Component.empty();
        } else if (!MOD_ID.matcher(mod).matches()) {
            status = Component.translatable(KEY + "invalid");
            statusColor = 0xFF5555;
        } else if (taken.contains(mod)) {
            status = Component.translatable(KEY + "duplicate");
            statusColor = 0xFF5555;
        } else if (!ModList.get().isLoaded(mod)) {
            status = Component.translatable(KEY + "not_installed_warning");
            statusColor = 0xFFD080;
        } else {
            status = ModPrioritiesScreen.modName(mod);
            statusColor = 0x80FF80;
        }
        field.setTextColor(mod.isEmpty() || valid ? 0xE0E0E0 : 0xFF5555);
        if (done != null) done.active = valid;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter confirms like "Done" when the id is acceptable.
        if ((keyCode == 257 || keyCode == 335) && done.active) {
            done.onPress();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int top = this.height / 2 - 70;
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, top - 16, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, status, this.width / 2, top + 26, statusColor);
        if (!suggestions.isEmpty()) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.translatable(KEY + "suggestions"),
                    this.width / 2,
                    top + 36 - this.font.lineHeight / 2,
                    0xA0A0A0);
        }
    }
}
