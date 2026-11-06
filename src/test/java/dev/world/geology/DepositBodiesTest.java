package dev.world.geology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DepositBodiesTest {
    private static List<SubsurfaceBody> bodies(int tier, double remaining) {
        return DepositBodies.bodies(
                11L,
                tier,
                0,
                0,
                0,
                20,
                15,
                5,
                SurveyFixtures.METAL_ORE,
                RockCatalog.STONE,
                remaining);
    }

    @Test
    void mediumAndLargeDepositsGetAWiderMineralisedHalo() {
        for (int tier = 2; tier <= 3; tier++) {
            List<SubsurfaceBody> bodies = bodies(tier, 1.0D);
            assertEquals(2, bodies.size());
            SubsurfaceBody core = bodies.get(0);
            SubsurfaceBody halo = bodies.get(1);
            assertEquals(SubsurfaceBody.Kind.ORE_BODY, core.kind());
            assertEquals(SubsurfaceBody.Kind.MINERALIZED_HALO, halo.kind());
            assertTrue(halo.radiusX() > core.radiusX() * 2.5D);
            assertTrue(halo.thickness() > core.thickness());
            assertTrue(halo.reflectivity() > 0 && halo.reflectivity() < core.reflectivity());
            // Alteration absorbs: the halo has the lowest Q.
            assertTrue(halo.attenuationRatio() > core.attenuationRatio());
        }
    }

    @Test
    void tinyDepositsHaveNoHalo() {
        List<SubsurfaceBody> bodies = bodies(0, 1.0D);
        assertEquals(1, bodies.size());
        assertEquals(SubsurfaceBody.Kind.ORE_BODY, bodies.getFirst().kind());
    }

    @Test
    void miningLowersTheCoreContrastButKeepsTheHalo() {
        SubsurfaceBody full = bodies(3, 1.0D).get(0);
        SubsurfaceBody partial = bodies(3, 0.4D).get(0);
        assertTrue(partial.reflectivity() < full.reflectivity());
        List<SubsurfaceBody> exhausted = bodies(3, 0.0D);
        assertEquals(1, exhausted.size());
        assertEquals(SubsurfaceBody.Kind.MINERALIZED_HALO, exhausted.getFirst().kind());
    }

    @Test
    void orientationIsDeterministic() {
        assertEquals(bodies(3, 1).get(0).angle(), bodies(3, 1).get(0).angle());
    }
}
