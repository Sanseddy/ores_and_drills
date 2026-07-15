package dev.world.level.levelgen;

/** Cached base-terrain suitability result for one exact tier candidate. */
public record ValidationResult(
        boolean viable,
        int validSamples,
        int totalSamples,
        double stoneCoverage,
        int validSections,
        int totalSections
) {
    public ValidationResult {
        if (validSamples < 0 || totalSamples < 0 || validSamples > totalSamples) {
            throw new IllegalArgumentException("Invalid deposit validation sample counts");
        }
        if (validSections < 0 || totalSections < 0 || validSections > totalSections) {
            throw new IllegalArgumentException("Invalid deposit validation section counts");
        }
        if (stoneCoverage < 0.0D || stoneCoverage > 1.0D) {
            throw new IllegalArgumentException("Stone coverage must be in [0, 1]");
        }
    }
}
