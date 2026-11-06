package dev.client.geology.layout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The console at every scaled screen size a 1920×1080 or smaller window produces with GUI scales
 * 1–6, with ordinary and with very long labels.
 */
class StationLayoutTest {
    static final int[][] SCREENS = {
        {320, 180},
        {320, 240},
        {384, 216},
        {427, 240},
        {480, 270},
        {640, 360},
        {683, 384},
        {960, 540},
        {1920, 1080}
    };
    static final int[][] TAB_WIDTHS = {{18, 34, 28}, {120, 160, 140}, {400, 400, 400}};
    static final int[] SIDEBARS = {150, 260, 900};

    @Test
    void areasStayInsideTheScreenAndNeverOverlap() {
        for (int[] screen : SCREENS) {
            for (int[] tabs : TAB_WIDTHS) {
                for (int sidebar : SIDEBARS) {
                    StationLayout.Result layout =
                            StationLayout.compute(screen[0], screen[1], tabs, sidebar, 9);
                    String context =
                            screen[0] + "x" + screen[1] + " tabs " + tabs[0] + " side " + sidebar;
                    Rect screenRect = new Rect(0, 0, screen[0], screen[1]);
                    assertTrue(screenRect.encloses(layout.panel()), context);
                    List<Rect> areas = new ArrayList<>();
                    areas.add(layout.title());
                    areas.addAll(layout.tabs());
                    areas.add(layout.view());
                    areas.add(layout.sidebar());
                    areas.add(layout.status());
                    for (int i = 0; i < areas.size(); i++) {
                        assertTrue(layout.panel().encloses(areas.get(i)), context + " area " + i);
                        for (int j = i + 1; j < areas.size(); j++) {
                            assertFalse(
                                    areas.get(i).overlaps(areas.get(j)),
                                    context + " areas " + i + " and " + j + " overlap");
                        }
                    }
                    for (Rect tab : layout.tabs()) {
                        assertTrue(layout.header().encloses(tab), context);
                        assertTrue(tab.width() > 0, context);
                    }
                    assertTrue(
                            layout.view().width() >= StationLayout.MINIMUM_SIDEBAR - 10, context);
                    assertTrue(layout.view().height() >= 100, context);
                    assertTrue(
                            layout.sidebar().width() >= StationLayout.MINIMUM_SIDEBAR * 3 / 4,
                            context);
                }
            }
        }
    }

    @Test
    void largeScreensGetARoomyConsole() {
        StationLayout.Result layout =
                StationLayout.compute(960, 540, new int[] {18, 34, 28}, 150, 9);
        assertTrue(layout.panel().width() >= 600);
        assertTrue(layout.view().width() >= 400);
        assertTrue(layout.view().height() >= 300);
    }

    @Test
    void sidebarFollowsItsLabelsWithinLimits() {
        int narrow =
                StationLayout.compute(640, 360, new int[] {18, 34, 28}, 120, 9).sidebar().width();
        int wide =
                StationLayout.compute(640, 360, new int[] {18, 34, 28}, 240, 9).sidebar().width();
        int huge =
                StationLayout.compute(640, 360, new int[] {18, 34, 28}, 2000, 9).sidebar().width();
        assertTrue(wide > narrow);
        assertTrue(huge <= 640 * 45 / 100);
    }
}
