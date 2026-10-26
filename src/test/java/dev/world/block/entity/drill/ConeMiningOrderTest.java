package dev.world.block.entity.drill;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConeMiningOrderTest {
    private static final int DEPTH = 9;

    @Test
    void funnelStartsAsTheSingleTopCentreBlock() {
        List<ConeMiningOrder.Cell> cells = ConeMiningOrder.of(7, 6, DEPTH);

        assertEquals(new ConeMiningOrder.Cell(3, 3, 0, 0), cells.getFirst());
        assertEquals(1, cells.stream().filter(cell -> cell.stage() == 0).count());
    }

    @Test
    void evenBodyStartsWithItsFourCentreBlocks() {
        List<ConeMiningOrder.Cell> stageZero = ConeMiningOrder.of(2, 0, DEPTH).stream()
                .filter(cell -> cell.stage() == 0)
                .toList();

        assertEquals(4, stageZero.size());
        assertTrue(stageZero.stream().allMatch(cell -> cell.depth() == 0));
    }

    @Test
    void nextStageWidensAndDeepensAroundTheCentre() {
        List<ConeMiningOrder.Cell> stageOne = ConeMiningOrder.of(7, 6, DEPTH).stream()
                .filter(cell -> cell.stage() == 1)
                .toList();

        // The four neighbours at the surface and the centre column a couple of blocks down.
        assertTrue(stageOne.contains(new ConeMiningOrder.Cell(4, 3, 0, 1)));
        assertTrue(stageOne.contains(new ConeMiningOrder.Cell(3, 2, 0, 1)));
        assertTrue(stageOne.contains(new ConeMiningOrder.Cell(3, 3, 1, 1)));
        assertTrue(stageOne.contains(new ConeMiningOrder.Cell(3, 3, 2, 1)));
        assertTrue(stageOne.stream().noneMatch(cell -> cell.x() == 4 && cell.z() == 4 && cell.depth() == 0));
    }

    @Test
    void stagesNeverDecreaseAlongTheOrder() {
        int previous = 0;
        for (ConeMiningOrder.Cell cell : ConeMiningOrder.of(7, 6, DEPTH)) {
            assertTrue(cell.stage() >= previous, "stage dropped at " + cell);
            previous = cell.stage();
        }
    }

    @Test
    void stageCoversItsWholeFunnelOfRanks() {
        for (ConeMiningOrder.Cell cell : ConeMiningOrder.of(5, 4, DEPTH)) {
            double rank = ConeMiningOrder.rank(cell.x(), cell.z(), cell.depth(), 5, DEPTH);
            assertTrue(rank <= cell.stage() + 1.0E-6D && rank > cell.stage() - 1.0D - 1.0E-6D, "wrong stage for " + cell);
        }
    }

    @Test
    void coneUnderTheBodyIsDugBeforeTheWiderArea() {
        List<ConeMiningOrder.Cell> cells = ConeMiningOrder.of(7, 6, DEPTH);
        int bottomOfCentre = indexOf(cells, 3, 3, DEPTH - 1);
        int surfaceCorner = indexOf(cells, -6, -6, 0);

        assertTrue(bottomOfCentre < surfaceCorner);
    }

    @Test
    void everyBlockOfTheAreaIsVisitedExactlyOnce() {
        List<ConeMiningOrder.Cell> cells = ConeMiningOrder.of(3, 1, DEPTH);

        assertEquals(5 * 5 * DEPTH, cells.size());
        assertEquals(cells.size(), new HashSet<>(cells).size());
    }

    private static int indexOf(List<ConeMiningOrder.Cell> cells, int x, int z, int depth) {
        for (int i = 0; i < cells.size(); i++) {
            ConeMiningOrder.Cell cell = cells.get(i);
            if (cell.x() == x && cell.z() == z && cell.depth() == depth) {
                return i;
            }
        }
        throw new AssertionError("missing cell " + x + "," + z + "," + depth);
    }
}
