package dev.world.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreDepositMiningSpeedTest {
    @Test
    void oneRemainingOreUsesOrdinaryOreSpeed() {
        assertEquals(1.0F, OreDepositMiningSpeed.progressMultiplier(1));
    }

    @Test
    void multipleRemainingOresUseHalfTheirAmountAsDurationMultiplier() {
        assertEquals(1.0F / 50.0F, OreDepositMiningSpeed.progressMultiplier(100), 0.000001F);
        assertEquals(1.0F / 49.5F, OreDepositMiningSpeed.progressMultiplier(99), 0.000001F);
        assertEquals(1.0F, OreDepositMiningSpeed.progressMultiplier(2), 0.000001F);
        assertTrue(OreDepositMiningSpeed.progressMultiplier(99) > OreDepositMiningSpeed.progressMultiplier(100));
    }

    @Test
    void invalidRemainingAmountsUseSingleOreSpeed() {
        assertEquals(1.0F, OreDepositMiningSpeed.progressMultiplier(0));
        assertEquals(1.0F, OreDepositMiningSpeed.progressMultiplier(-10));
    }
}
