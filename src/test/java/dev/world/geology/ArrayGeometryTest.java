package dev.world.geology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArrayGeometryTest {
    private static final double L = ArrayGeometry.CORRELATION_LENGTH;

    @Test
    void clusteredReceiversCountAsOne() {
        double[] xs = {0, 2, 4, 1};
        double[] zs = {0, 1, 3, 2};
        double[] w = {1, 1, 1, 1};
        assertEquals(1.0D, ArrayGeometry.effectiveReceivers(xs, zs, w, L), 0.1D);
    }

    @Test
    void spreadReceiversCountFully() {
        double[] xs = {0, 200, -200, 0};
        double[] zs = {200, 0, 0, -200};
        double[] w = {1, 1, 1, 1};
        assertEquals(4.0D, ArrayGeometry.effectiveReceivers(xs, zs, w, L), 1.0E-3D);
    }

    @Test
    void coverageRewardsDifferentDirections() {
        double[] w = {1, 1, 1, 1};
        double around =
                ArrayGeometry.azimuthalCoverage(
                        0, 0, new double[] {100, 0, -100, 0}, new double[] {0, 100, 0, -100}, w);
        double oneSide =
                ArrayGeometry.azimuthalCoverage(
                        0, 0, new double[] {100, 100, 110, 90}, new double[] {0, 10, -5, 4}, w);
        assertEquals(0.75D, around, 1.0E-9D);
        assertTrue(oneSide < 0.1D, "one-sided coverage " + oneSide);
        assertEquals(
                0.0D,
                ArrayGeometry.azimuthalCoverage(
                        0, 0, new double[] {50}, new double[] {0}, new double[] {1}));
    }

    @Test
    void moveoutOffsetsConstrainVelocity() {
        double prior = ArrayGeometry.PRIOR_VELOCITY_ERROR;
        double zeroOffset =
                ArrayGeometry.velocityRelativeError(
                        new double[] {0}, new double[] {1}, 64, 5500, 50, prior);
        assertEquals(prior, zeroOffset, 1.0E-12D);
        double near =
                ArrayGeometry.velocityRelativeError(
                        new double[] {0, 40, 50}, new double[] {1, 1, 1}, 64, 5500, 50, prior);
        double wide =
                ArrayGeometry.velocityRelativeError(
                        new double[] {0, 120, 180, 240},
                        new double[] {1, 1, 1, 1},
                        64,
                        5500,
                        50,
                        prior);
        assertTrue(near < prior);
        assertTrue(wide < near, wide + " vs " + near);
    }

    @Test
    void lateralResolutionImprovesWithFocusing() {
        double alone = ArrayGeometry.lateralSigma(80, 0, 0, 10);
        double clustered = ArrayGeometry.lateralSigma(80, 1, 0.05, 10);
        double spread = ArrayGeometry.lateralSigma(80, 4, 0.75, 10);
        assertTrue(alone > clustered);
        assertTrue(clustered > spread);
    }

    @Test
    void illuminationFollowsTheMidpoint() {
        double atMidpoint = ArrayGeometry.illumination(0, 0, 200, 0, 100, 0);
        double behindSource = ArrayGeometry.illumination(0, 0, 200, 0, -300, 0);
        assertEquals(1.0D, atMidpoint, 1.0E-12D);
        assertTrue(behindSource < 0.1D);
    }
}
