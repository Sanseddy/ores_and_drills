package dev.world.level.levelgen;

import java.util.EnumMap;
import java.util.Map;

/** Registry of the four independent tier resolvers. */
public final class DepositResolvers {
    private static final Map<OreDepositTier, TierDepositResolver> RESOLVERS = createResolvers();

    private DepositResolvers() {
    }

    public static TierDepositResolver forTier(OreDepositTier tier) {
        TierDepositResolver resolver = RESOLVERS.get(tier);
        if (resolver == null) {
            throw new IllegalArgumentException("No deposit resolver for tier " + tier);
        }
        return resolver;
    }

    private static Map<OreDepositTier, TierDepositResolver> createResolvers() {
        EnumMap<OreDepositTier, TierDepositResolver> resolvers = new EnumMap<>(OreDepositTier.class);
        register(resolvers, new TinyDepositResolver());
        register(resolvers, new SmallDepositResolver());
        register(resolvers, new MediumDepositResolver());
        register(resolvers, new LargeDepositResolver());
        return Map.copyOf(resolvers);
    }

    private static void register(
            EnumMap<OreDepositTier, TierDepositResolver> resolvers,
            TierDepositResolver resolver
    ) {
        if (resolvers.put(resolver.tier(), resolver) != null) {
            throw new IllegalStateException("Duplicate resolver for " + resolver.tier());
        }
    }
}
