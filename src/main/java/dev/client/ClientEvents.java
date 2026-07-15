package dev.client;

import dev.FactoryExpansionMod;
import dev.client.ore.OreDepositBakedModel;
import dev.client.ore.OreTintedTextureSource;
import dev.drill.AdvancedMiningDrill;
import dev.drill.BurnerMiningDrill;
import dev.drill.ElectricMiningDrill;
import dev.drill.UltimateMiningDrill;
import dev.registry.ModBlockEntities;
import dev.registry.ModBlocks;
import dev.registry.ModMenuTypes;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

@EventBusSubscriber(modid = FactoryExpansionMod.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.BURNER_MINING_DRILL.get(), context -> new BurnerMiningDrill.Renderer());
        event.registerBlockEntityRenderer(ModBlockEntities.ELECTRIC_MINING_DRILL.get(), context -> new ElectricMiningDrill.Renderer());
        event.registerBlockEntityRenderer(ModBlockEntities.ADVANCED_MINING_DRILL.get(), context -> new AdvancedMiningDrill.Renderer());
        event.registerBlockEntityRenderer(ModBlockEntities.ULTIMATE_MINING_DRILL.get(), context -> new UltimateMiningDrill.Renderer());
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.BURNER_MINING_DRILL.get(), BurnerMiningDrill.Screen::new);
        event.register(ModMenuTypes.ELECTRIC_MINING_DRILL.get(), ElectricMiningDrill.Screen::new);
        event.register(ModMenuTypes.ADVANCED_MINING_DRILL.get(), AdvancedMiningDrill.Screen::new);
        event.register(ModMenuTypes.ULTIMATE_MINING_DRILL.get(), UltimateMiningDrill.Screen::new);
    }

    @SubscribeEvent
    public static void registerSpriteSourceTypes(RegisterSpriteSourceTypesEvent event) {
        event.register(OreTintedTextureSource.ID, OreTintedTextureSource.TYPE);
        FactoryExpansionMod.LOGGER.trace("Ore deposit: registered sprite source type {}", OreTintedTextureSource.ID);
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemBlockRenderTypes.setRenderLayer(ModBlocks.ORE_DEPOSIT.get(), RenderType.cutout()));
    }

    @SubscribeEvent
    public static void modifyBakedModels(ModelEvent.ModifyBakingResult event) {
        OreDepositBakedModel.replaceModels(event);
    }
}
