package dev.world.block.entity.drill;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreScannerSpiralTest {
    @Test
    void threeByThreeStartsAtCenterAndSpiralsClockwise() {
        assertEquals(List.of(
                new SpiralColumnOrder.Offset(1, 1),
                new SpiralColumnOrder.Offset(2, 1),
                new SpiralColumnOrder.Offset(2, 2),
                new SpiralColumnOrder.Offset(1, 2),
                new SpiralColumnOrder.Offset(0, 2),
                new SpiralColumnOrder.Offset(0, 1),
                new SpiralColumnOrder.Offset(0, 0),
                new SpiralColumnOrder.Offset(1, 0),
                new SpiralColumnOrder.Offset(2, 0)
        ), SpiralColumnOrder.between(0, 3));
    }

    @Test
    void evenTwoByTwoVisitsTheFourCentralCellsFirst() {
        assertEquals(List.of(
                new SpiralColumnOrder.Offset(0, 0),
                new SpiralColumnOrder.Offset(1, 0),
                new SpiralColumnOrder.Offset(1, 1),
                new SpiralColumnOrder.Offset(0, 1)
        ), SpiralColumnOrder.between(0, 2));
    }

    @Test
    void expandedMiningAreaContainsEveryColumnExactlyOnce() {
        List<SpiralColumnOrder.Offset> columns = SpiralColumnOrder.between(-4, 9);
        assertEquals(13 * 13, columns.size());
        assertEquals(columns.size(), new HashSet<>(columns).size());
        assertTrue(columns.stream().allMatch(offset ->
                offset.x() >= -4 && offset.x() < 9 && offset.z() >= -4 && offset.z() < 9));
    }
}
