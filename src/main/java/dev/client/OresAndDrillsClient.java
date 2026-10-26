package dev.client;

import dev.OresAndDrillsMod;
import dev.client.config.OresAndDrillsSettingsScreen;
import dev.client.drill.DrillMiningParticles;
import dev.client.drill.DrillWorkSounds;
import dev.world.block.entity.drill.DrillClientEffects;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client entry point. Registering the config screen in the mod constructor runs before Configured
 * builds its generic factories, so Configured sees ours and leaves it in place.
 */
@Mod(value = OresAndDrillsMod.MOD_ID, dist = Dist.CLIENT)
public final class OresAndDrillsClient {
    public OresAndDrillsClient(ModContainer container) {
        container.registerExtensionPoint(
                IConfigScreenFactory.class,
                (IConfigScreenFactory) (mod, parent) -> new OresAndDrillsSettingsScreen(parent));
        DrillClientEffects.setHandler(
                drill -> {
                    DrillMiningParticles.tick(drill);
                    DrillWorkSounds.tick(drill);
                });
    }
}
