package dev;

import dev.config.OreDepositConfig;
import dev.config.OreWorldSettingsApplier;
import dev.command.OreLocateCommand;
import dev.config.OreSettingsPresetManager;
import dev.network.ModNetworking;
import dev.network.OreDepositPaletteSync;
import dev.registry.ModBlockEntities;
import dev.registry.ModAttachments;
import dev.registry.ModBlocks;
import dev.registry.ModCapabilities;
import dev.registry.ModMenuTypes;
import dev.registry.ModWorldgen;
import dev.world.block.OreDepositBreakHandler;
import dev.world.block.OreDepositExplosionHandler;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(FactoryExpansionMod.MOD_ID)
public final class FactoryExpansionMod {
    public static final String MOD_ID = "ores_and_drills";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public FactoryExpansionMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, OreDepositConfig.SPEC);

        ModAttachments.ATTACHMENTS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModMenuTypes.MENU_TYPES.register(modEventBus);
        ModWorldgen.FEATURES.register(modEventBus);
        ModWorldgen.BIOME_MODIFIER_SERIALIZERS.register(modEventBus);

        modEventBus.addListener(this::addCreativeTabItems);
        modEventBus.addListener(ModCapabilities::register);
        modEventBus.addListener(ModNetworking::register);
        modEventBus.addListener(OreWorldSettingsApplier::onConfigLoading);
        NeoForge.EVENT_BUS.addListener(OreLocateCommand::register);
        NeoForge.EVENT_BUS.addListener(OreSettingsPresetManager::addReloadListener);
        NeoForge.EVENT_BUS.addListener(OreDepositBreakHandler::onBreak);
        NeoForge.EVENT_BUS.addListener(OreDepositExplosionHandler::onDetonate);
        NeoForge.EVENT_BUS.addListener(OreDepositPaletteSync::onDatapackSync);
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(ModBlocks.BURNER_MINING_DRILL_ITEM.get());
            event.accept(ModBlocks.ELECTRIC_MINING_DRILL_ITEM.get());
            event.accept(ModBlocks.ADVANCED_MINING_DRILL_ITEM.get());
            event.accept(ModBlocks.ULTIMATE_MINING_DRILL_ITEM.get());
        }
    }
}
