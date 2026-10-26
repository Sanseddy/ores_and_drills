package dev.registry;

import dev.OresAndDrillsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, OresAndDrillsMod.MOD_ID);

    /** All drill tiers, in progression order. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> DRILLS =
            CREATIVE_MODE_TABS.register(
                    "drills",
                    () ->
                            CreativeModeTab.builder()
                                    .title(
                                            Component.translatable(
                                                    "itemGroup.ores_and_drills.drills"))
                                    .icon(
                                            () ->
                                                    ModBlocks.ELECTRIC_MINING_DRILL_ITEM
                                                            .get()
                                                            .getDefaultInstance())
                                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                                    .displayItems(
                                            (parameters, output) -> {
                                                output.accept(
                                                        ModBlocks.BURNER_MINING_DRILL_ITEM.get());
                                                output.accept(
                                                        ModBlocks.ELECTRIC_MINING_DRILL_ITEM.get());
                                                output.accept(
                                                        ModBlocks.ADVANCED_MINING_DRILL_ITEM.get());
                                                output.accept(
                                                        ModBlocks.ULTIMATE_MINING_DRILL_ITEM.get());
                                            })
                                    .build());

    private ModCreativeTabs() {}
}
