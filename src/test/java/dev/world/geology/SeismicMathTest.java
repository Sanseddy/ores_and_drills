package dev.world.geology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeismicMathTest {
    @Test
    void impedanceIsDensityTimesVelocity() {
        RockProperties rock = new RockProperties(2.5D, 4000.0D, 100.0D);
        assertEquals(10_000.0D, rock.impedance(), 1.0E-9D);
    }

    @Test
    void reflectionCoefficientFollowsNormalIncidenceFormula() {
        assertEquals(0.0D, SeismicMath.reflectionCoefficient(RockCatalog.STONE, RockCatalog.STONE));
        double down = SeismicMath.reflectionCoefficient(RockCatalog.STONE, RockCatalog.DEEPSLATE);
        double up = SeismicMath.reflectionCoefficient(RockCatalog.DEEPSLATE, RockCatalog.STONE);
        double z1 = RockCatalog.STONE.impedance();
        double z2 = RockCatalog.DEEPSLATE.impedance();
        assertEquals((z2 - z1) / (z2 + z1), down, 1.0E-12D);
        assertEquals(-down, up, 1.0E-12D);
        // Rock to air reflects almost everything with reversed polarity.
        assertTrue(SeismicMath.reflectionCoefficient(RockCatalog.STONE, RockCatalog.AIR) < -0.99D);
    }

    @Test
    void densityAloneDoesNotDecideContrast() {
        RockProperties host = RockCatalog.STONE;
        // Laboratory values of two real ore minerals.
        RockProperties cinnabar = new RockProperties(8.10D, 3200.0D, 60.0D);
        RockProperties magnetite = new RockProperties(5.20D, 7400.0D, 120.0D);
        assertTrue(cinnabar.density() > magnetite.density());
        double cinnabarRock =
                SeismicMath.reflectionCoefficient(
                        host, host.mixedWith(cinnabar, DepositBodies.CORE_GRADE));
        double magnetiteRock =
                SeismicMath.reflectionCoefficient(
                        host, host.mixedWith(magnetite, DepositBodies.CORE_GRADE));
        // The slow, very dense mineral raises impedance less than the fast, lighter one.
        assertTrue(cinnabarRock < magnetiteRock, cinnabarRock + " vs " + magnetiteRock);
        // Coal is lighter and slower than its host: a negative reflector.
        assertTrue(
                SeismicMath.reflectionCoefficient(
                                host,
                                host.mixedWith(new RockProperties(1.35D, 2200.0D, 30.0D), 0.35D))
                        < 0.0D);
    }

    @Test
    void mixingUsesTimeAverageForVelocity() {
        RockProperties a = new RockProperties(2.0D, 2000.0D, 50.0D);
        RockProperties b = new RockProperties(4.0D, 6000.0D, 150.0D);
        RockProperties half = a.mixedWith(b, 0.5D);
        assertEquals(3.0D, half.density(), 1.0E-12D);
        assertEquals(1.0D / (0.5D / 2000.0D + 0.5D / 6000.0D), half.velocity(), 1.0E-9D);
        assertEquals(a, a.mixedWith(b, 0.0D));
    }

    @Test
    void fresnelZoneAndThinBedBehaveMonotonically() {
        double lambda = SeismicMath.wavelength(5500.0D, 20.0D);
        assertEquals(275.0D, lambda, 1.0E-9D);
        assertTrue(
                SeismicMath.fresnelRadius(lambda, 100.0D)
                        > SeismicMath.fresnelRadius(lambda, 50.0D));
        assertTrue(
                SeismicMath.thinBedFactor(2.0D, lambda) < SeismicMath.thinBedFactor(8.0D, lambda));
        assertEquals(1.0D, SeismicMath.thinBedFactor(lambda, lambda));
    }

    @Test
    void footprintSaturatesForWideBodiesAndScalesWithAreaForSmallOnes() {
        assertEquals(1.0D, SeismicMath.footprintWeight(0, 0, 1.0E6D, 1.0E6D, 0, 50.0D), 1.0E-6D);
        double small = SeismicMath.footprintWeight(0, 0, 5.0D, 5.0D, 0, 100.0D);
        double larger = SeismicMath.footprintWeight(0, 0, 10.0D, 10.0D, 0, 100.0D);
        assertEquals(4.0D, larger / small, 0.1D);
        assertTrue(SeismicMath.footprintWeight(200, 0, 10, 10, 0, 30) < small);
    }

    @Test
    void absorptionAndNoiseGrowWithDepth() {
        assertTrue(
                SeismicMath.absorption(50, 100, 5500, 40)
                        < SeismicMath.absorption(50, 20, 5500, 40));
        assertTrue(
                SeismicMath.noiseSigma(0.005, 120, 1, 1) > SeismicMath.noiseSigma(0.005, 20, 1, 1));
        assertEquals(
                SeismicMath.noiseSigma(0.005, 60, 1, 1) / 2.0D,
                SeismicMath.noiseSigma(0.005, 60, 1, 4),
                1.0E-12D);
    }
}
