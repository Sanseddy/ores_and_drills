package dev.world.block.entity.drill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductivityBonusTest {
    @Test
    void twentyPercentPaysOneBonusCycleEveryFiveCycles() {
        ProductivityBonus bonus = new ProductivityBonus(20);
        int[] earned = new int[10];
        for (int cycle = 0; cycle < earned.length; cycle++) {
            earned[cycle] = bonus.completeCycle();
        }

        assertEquals(0, earned[0]);
        assertEquals(0, earned[3]);
        assertEquals(1, earned[4]);
        assertEquals(1, earned[9]);
        assertEquals(0, bonus.progress());
    }

    @Test
    void seventyPercentCarriesTheRemainderOverToTheNextCycle() {
        ProductivityBonus bonus = new ProductivityBonus(70);

        assertEquals(0, bonus.completeCycle());
        assertEquals(7_000, bonus.progress());
        assertEquals(1, bonus.pendingBonusCycles());
        assertEquals(2, bonus.cycleOutputMultiplier());
        assertEquals(1, bonus.completeCycle());
        assertEquals(4_000, bonus.progress());
    }

    @Test
    void productivityAboveOneHundredPercentPaysSeveralBonusCycles() {
        ProductivityBonus bonus = new ProductivityBonus(250);

        assertEquals(3, bonus.cycleOutputMultiplier());
        assertEquals(2, bonus.completeCycle());
        assertEquals(5_000, bonus.progress());
        assertEquals(3, bonus.completeCycle());
        assertEquals(0, bonus.progress());
    }

    @Test
    void longRunAverageMatchesProductivityExactly() {
        ProductivityBonus bonus = new ProductivityBonus(70);
        int bonusCycles = 0;
        for (int cycle = 0; cycle < 1_000; cycle++) {
            bonusCycles += bonus.completeCycle();
        }

        assertEquals(700, bonusCycles);
    }

    @Test
    void visibleBarFillsDuringTheCycleAndStaysFullUntilPayout() {
        assertEquals(0, ProductivityBonus.visiblePercent(0, 20, 0, 40));
        assertEquals(10, ProductivityBonus.visiblePercent(0, 20, 20, 40));
        assertEquals(60, ProductivityBonus.visiblePercent(4_000, 20, 40, 40));
        assertEquals(100, ProductivityBonus.visiblePercent(7_000, 70, 3, 4));
        assertEquals(0, ProductivityBonus.visiblePercent(5_000, 0, 3, 4));
    }

    @Test
    void storedProgressIsKeptBelowOneFullBar() {
        ProductivityBonus bonus = new ProductivityBonus(20);
        bonus.setProgress(12_500);

        assertEquals(2_500, bonus.progress());
    }

    @Test
    void productivityOutsideFactorioLimitsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProductivityBonus(-1));
        assertThrows(IllegalArgumentException.class, () -> new ProductivityBonus(ProductivityBonus.MAX_PERCENT + 1));
    }
}
