package dev.compat.emi;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import net.neoforged.fml.ModList;

/**
 * EMI Ores integration; EMI Ores classes stay in {@link EmiOresDeposits} so they load only when it
 * is installed.
 */
@EmiEntrypoint
public final class EmiOresEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        if (ModList.get().isLoaded("emi_ores")) {
            EmiOresDeposits.register(registry);
        }
    }
}
