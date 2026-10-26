package dev;

import dev.config.OreDepositConfig;
import dev.config.OreWorldSettingsApplier;
import dev.command.OreDepositLocateCommand;
import dev.command.OreDepositLocateService;
import dev.config.OreSettingsPresetManager;
import dev.network.ModNetworking;
import dev.network.OreDepositPaletteSync;
import dev.registry.ModBlockEntities;
import dev.registry.ModAttachments;
import dev.registry.ModBlocks;
import dev.registry.ModCapabilities;
import dev.registry.ModCreativeTabs;
import dev.registry.ModMenuTypes;
import dev.registry.ModSounds;
import dev.registry.ModWorldgen;
import dev.world.block.OreDepositExplosionHandler;
import dev.world.block.OreDepositMiningSpeed;
import dev.world.level.levelgen.DepositTerrainValidator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(OresAndDrillsMod.MOD_ID)
public final class OresAndDrillsMod {
    public static final String MOD_ID = "ores_and_drills";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public OresAndDrillsMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, OreDepositConfig.SPEC);

        ModAttachments.ATTACHMENTS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModMenuTypes.MENU_TYPES.register(modEventBus);
        ModSounds.SOUND_EVENTS.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModWorldgen.FEATURES.register(modEventBus);
        ModWorldgen.BIOME_MODIFIER_SERIALIZERS.register(modEventBus);
        dev.recipe.DrillMiningCondition.registerDefaults();
        dev.registry.ModRecipes.TYPES.register(modEventBus);
        dev.registry.ModRecipes.SERIALIZERS.register(modEventBus);

        modEventBus.addListener(ModCapabilities::register);
        modEventBus.addListener(ModNetworking::register);
        modEventBus.addListener(OreWorldSettingsApplier::onConfigLoading);
        modEventBus.addListener(dev.config.ConfigReloader::onConfigReloading);
        NeoForge.EVENT_BUS.addListener(OreDepositLocateCommand::register);
        NeoForge.EVENT_BUS.addListener(OreDepositLocateService::onServerTick);
        NeoForge.EVENT_BUS.addListener(OreDepositLocateService::onServerStopped);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent event) ->
                dev.world.level.levelgen.SmallDepositDiagnostics.logAll());
        NeoForge.EVENT_BUS.addListener(OreSettingsPresetManager::addReloadListener);
        NeoForge.EVENT_BUS.addListener(OreDepositExplosionHandler::onDetonate);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, OreDepositMiningSpeed::onBreakSpeed);
        NeoForge.EVENT_BUS.addListener(OreDepositPaletteSync::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(DepositTerrainValidator::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(dev.recipe.DrillMiningRecipes::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, dev.recipe.FluidLockedOreDrops::onBlockDrops);
        NeoForge.EVENT_BUS.addListener(dev.world.level.levelgen.OreDepositFeature::onCompactChunkLoad);
    }
}
