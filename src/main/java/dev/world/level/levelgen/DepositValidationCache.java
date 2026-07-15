package dev.world.level.levelgen;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded geology-prediction cache. The dimension is part of the key as deposit ids are dimension-local. */
public final class DepositValidationCache {
    private static final int MAX_ENTRIES = 16_384;
    private static final Map<DepositValidationKey, ValidationResult> RESULTS =
            new LinkedHashMap<>(256, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<DepositValidationKey, ValidationResult> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private DepositValidationCache() {
    }

    @Nullable
    public static synchronized ValidationResult get(DepositValidationKey key) {
        return RESULTS.get(key);
    }

    public static synchronized void put(
            DepositValidationKey key,
            ValidationResult result
    ) {
        RESULTS.put(key, result);
    }

    public static synchronized void clear() {
        RESULTS.clear();
    }

}
