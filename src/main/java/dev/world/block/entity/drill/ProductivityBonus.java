package dev.world.block.entity.drill;

/**
 * Factorio's productivity model for a mining drill.
 *
 * <p>The productivity bar fills continuously while the drill works: every finished mining cycle
 * adds the drill's productivity to it, and the part of the current cycle already done is shown on
 * top. Whenever the bar reaches 100% at the end of a cycle, the drill outputs one extra copy of
 * that whole cycle's result for free; bonus ore is never taken from the deposit. Productivity above
 * 100% grants several bonus copies per cycle, and the cycle only finishes when the output can hold
 * the result together with its bonus.
 *
 * <p>Progress is kept in fixed point ({@link #FULL_BAR} = one bonus cycle) so it stays exact across
 * saves and fits in a menu data slot.
 */
public final class ProductivityBonus {
    /** Bar units in one bonus cycle: 1/100 of a percent. */
    public static final int FULL_BAR = 10_000;

    /** Factorio 2.0 caps productivity at +300%. */
    public static final int MAX_PERCENT = 300;

    private final int percent;
    private int progress;

    public ProductivityBonus(int percent) {
        if (percent < 0 || percent > MAX_PERCENT) {
            throw new IllegalArgumentException(
                    "Productivity must be within 0.." + MAX_PERCENT + "%: " + percent);
        }
        this.percent = percent;
    }

    public int percent() {
        return percent;
    }

    public boolean isActive() {
        return percent > 0;
    }

    /** Bar progress carried over from finished cycles, in {@link #FULL_BAR} units. */
    public int progress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = Math.floorMod(progress, FULL_BAR);
    }

    /** Bonus copies of the result that the cycle in progress will add when it finishes. */
    public int pendingBonusCycles() {
        return pendingBonusCycles(progress, percent);
    }

    /** Result multiplier for the cycle in progress: the regular result plus its bonus copies. */
    public int cycleOutputMultiplier() {
        return 1 + pendingBonusCycles();
    }

    /**
     * Advances the bar by one finished cycle and returns how many bonus copies of the result that
     * cycle earned.
     */
    public int completeCycle() {
        long total = (long) progress + percentToBar(percent);
        progress = (int) (total % FULL_BAR);
        return (int) (total / FULL_BAR);
    }

    public static int pendingBonusCycles(int progress, int percent) {
        if (percent <= 0) {
            return 0;
        }
        return (int) (((long) Math.max(0, progress) + percentToBar(percent)) / FULL_BAR);
    }

    /**
     * Bar fill shown to the player, in {@link #FULL_BAR} units: carried progress plus the
     * productivity earned by the part of the current cycle already mined. A bar that will overflow
     * at the end of this cycle stays full until the bonus is paid out.
     */
    public static int visibleProgress(
            int progress, int percent, int miningProgress, int miningDuration) {
        if (percent <= 0) {
            return 0;
        }

        long carried = Math.max(0, progress);
        long current = 0;
        if (miningProgress > 0 && miningDuration > 0) {
            current =
                    percentToBar(percent)
                            * Math.min(miningProgress, miningDuration)
                            / miningDuration;
        }
        return (int) Math.min(FULL_BAR, carried + current);
    }

    public static int visiblePercent(
            int progress, int percent, int miningProgress, int miningDuration) {
        return visibleProgress(progress, percent, miningProgress, miningDuration) * 100 / FULL_BAR;
    }

    private static long percentToBar(int percent) {
        return (long) percent * FULL_BAR / 100;
    }
}
