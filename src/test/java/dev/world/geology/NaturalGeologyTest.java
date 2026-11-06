package dev.world.geology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class NaturalGeologyTest {
    private static List<SubsurfaceBody> area(long seed, double minX, double minZ, double size) {
        return NaturalGeology.bodies(
                seed,
                NaturalGeology.Setting.OVERWORLD,
                minX,
                minZ,
                minX + size,
                minZ + size,
                -59,
                50);
    }

    @Test
    void sameSeedAndAreaGiveTheSameStructures() {
        assertEquals(area(5L, -1000, -1000, 2000), area(5L, -1000, -1000, 2000));
        assertNotEquals(area(5L, -1000, -1000, 2000), area(6L, -1000, -1000, 2000));
    }

    @Test
    void overlappingQueriesAgreeOnSharedProvinces() {
        List<SubsurfaceBody> wide = area(5L, -1000, -1000, 2000);
        for (SubsurfaceBody body : area(5L, 0, 0, 200)) {
            assertTrue(wide.contains(body), "structure depends on the query area");
        }
    }

    @Test
    void heterogeneitiesCoverSeveralKindsAndBothPolarities() {
        List<SubsurfaceBody> bodies = area(77L, -3000, -3000, 6000);
        EnumSet<SubsurfaceBody.Kind> kinds = EnumSet.noneOf(SubsurfaceBody.Kind.class);
        boolean positive = false;
        boolean negative = false;
        for (SubsurfaceBody body : bodies) {
            kinds.add(body.kind());
            positive |= body.reflectivity() > 0.0D;
            negative |= body.reflectivity() < 0.0D;
        }
        assertTrue(kinds.contains(SubsurfaceBody.Kind.DENSE_MASSIF));
        assertTrue(kinds.contains(SubsurfaceBody.Kind.LOW_VELOCITY_LENS));
        assertTrue(kinds.contains(SubsurfaceBody.Kind.FRACTURED_ZONE));
        assertTrue(kinds.contains(SubsurfaceBody.Kind.LITHOLOGIC_CONTACT));
        assertTrue(positive && negative);
        assertTrue(!kinds.contains(SubsurfaceBody.Kind.ORE_BODY));
    }

    @Test
    void theEndHasNoLayeredBackground() {
        assertTrue(NaturalGeology.horizons(NaturalGeology.Setting.END, 0).isEmpty());
        assertEquals(2, NaturalGeology.horizons(NaturalGeology.Setting.OVERWORLD, -64).size());
    }
}
