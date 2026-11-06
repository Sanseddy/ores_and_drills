package dev.client.geology.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnLayoutTest {
    /** Heights of the station's side column: text, slider, buttons, bars, legend, target text. */
    static final int[] SIDEBAR = {30, 20, 20, 20, 16, 16, 16, 10, 20, 20, 20, 17, 0, 90};

    @Test
    void itemsStackWithoutOverlapAndVisibleOnesFitEntirely() {
        for (int height : new int[] {60, 120, 200, 400}) {
            for (int scroll = -50; scroll < 600; scroll += 37) {
                Rect area = new Rect(10, 20, 120, height);
                ColumnLayout.Result result = ColumnLayout.compute(area, SIDEBAR, 3, scroll);
                assertTrue(result.scroll() >= 0 && result.scroll() <= result.maxScroll());
                List<Rect> items = result.items();
                for (int i = 0; i < items.size(); i++) {
                    if (result.visible()[i]) {
                        assertTrue(area.encloses(items.get(i)));
                    }
                    for (int j = i + 1; j < items.size(); j++) {
                        assertFalse(items.get(i).overlaps(items.get(j)));
                    }
                }
            }
        }
    }

    @Test
    void scrollingReachesTheLastItem() {
        Rect area = new Rect(0, 0, 100, 80);
        ColumnLayout.Result result = ColumnLayout.compute(area, SIDEBAR, 3, Integer.MAX_VALUE);
        assertTrue(result.visible()[SIDEBAR.length - 1] || SIDEBAR[SIDEBAR.length - 1] > 80);
        assertEquals(result.contentHeight() - 80, result.scroll());
    }

    @Test
    void emptyItemsAreNeverVisible() {
        ColumnLayout.Result result = ColumnLayout.compute(new Rect(0, 0, 100, 500), SIDEBAR, 3, 0);
        assertFalse(result.visible()[12]);
    }

    @Test
    void rowsSplitEvenlyInsideTheArea() {
        Rect area = new Rect(5, 5, 101, 20);
        List<Rect> cells = ColumnLayout.row(area, 2, 3);
        assertEquals(2, cells.size());
        assertTrue(area.encloses(cells.get(0)));
        assertTrue(area.encloses(cells.get(1)));
        assertFalse(cells.get(0).overlaps(cells.get(1)));
        assertEquals(area.right(), cells.get(1).right());
    }
}
