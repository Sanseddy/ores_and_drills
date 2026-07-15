package dev.mixin;

import dev.client.worldcreation.OreDepositSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/** Adds the "Ore Settings" button to the World tab of vanilla's world-creation screen, right below the seed field. */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class CreateWorldScreenWorldTabMixin {
    @Shadow
    @Final
    CreateWorldScreen this$0;

    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/worldselection/SwitchGrid;builder(I)Lnet/minecraft/client/gui/screens/worldselection/SwitchGrid$Builder;"
            ),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void factoryExpansion$addOreSettingsButton(
            CreateWorldScreen createWorldScreen,
            CallbackInfo callbackInfo,
            GridLayout.RowHelper rowHelper,
            CycleButton<WorldCreationUiState.WorldTypeEntry> worldTypeButton
    ) {
        Button oreSettingsButton = Button.builder(
                Component.translatable("gui.ores_and_drills.ore_settings.open_button"),
                button -> Minecraft.getInstance().setScreen(new OreDepositSettingsScreen(this.this$0))
        ).width(308).build();
        rowHelper.addChild(oreSettingsButton, 2);
    }
}
