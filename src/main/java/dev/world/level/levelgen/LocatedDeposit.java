package dev.world.level.levelgen;

/** Nearest exact ore+tier match returned by the locator. */
public record LocatedDeposit(
        DepositCandidate candidate,
        DepositLocateStatus status,
        int distance,
        double stoneCoverage
) {
    public boolean confirmed() {
        return status == DepositLocateStatus.CONFIRMED;
    }
}
