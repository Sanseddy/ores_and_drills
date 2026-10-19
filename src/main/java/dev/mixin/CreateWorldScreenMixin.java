package dev.mixin;

import dev.config.OreWorldSettingsApplier;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every world creation starts from default ore settings. The constructor runs once per creation (unlike
 * {@code init}, which repeats after returning from the Ore Settings screen), so edits made during this
 * creation survive while the previous world's settings do not.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void oresAndDrills$resetOreSettings(CallbackInfo callbackInfo) {
        OreWorldSettingsApplier.resetForNewWorld();
    }
}
