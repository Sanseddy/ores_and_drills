package dev.registry;

import dev.OresAndDrillsMod;
import dev.drill.AdvancedMiningDrill;
import dev.drill.BurnerMiningDrill;
import dev.drill.ElectricMiningDrill;
import dev.drill.UltimateMiningDrill;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(Registries.MENU, OresAndDrillsMod.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<BurnerMiningDrill.Menu>> BURNER_MINING_DRILL =
            MENU_TYPES.register("burner_mining_drill", () -> new MenuType<>(BurnerMiningDrill.Menu::new, FeatureFlags.VANILLA_SET));

    public static final DeferredHolder<MenuType<?>, MenuType<ElectricMiningDrill.Menu>> ELECTRIC_MINING_DRILL =
            MENU_TYPES.register("electric_mining_drill", () -> new MenuType<>(ElectricMiningDrill.Menu::new, FeatureFlags.VANILLA_SET));

    public static final DeferredHolder<MenuType<?>, MenuType<AdvancedMiningDrill.Menu>> ADVANCED_MINING_DRILL =
            MENU_TYPES.register("advanced_mining_drill", () -> new MenuType<>(AdvancedMiningDrill.Menu::new, FeatureFlags.VANILLA_SET));

    public static final DeferredHolder<MenuType<?>, MenuType<UltimateMiningDrill.Menu>> ULTIMATE_MINING_DRILL =
            MENU_TYPES.register("ultimate_mining_drill", () -> new MenuType<>(UltimateMiningDrill.Menu::new, FeatureFlags.VANILLA_SET));

    private ModMenuTypes() {
    }
}
