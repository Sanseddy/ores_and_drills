package dev.client.geology.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * A vertical stack of items in a fixed area that scrolls when the items are taller than the area.
 * Items keep their natural height; an item counts as visible only when it fits entirely, so a
 * button is never drawn half outside its column.
 */
public final class ColumnLayout {
    private ColumnLayout() {}

    /**
     * @param items each item's rectangle after scrolling (may lie outside the area)
     * @param visible whether each item lies entirely inside the area
     * @param scroll the clamped scroll offset actually applied
     */
    public record Result(
            List<Rect> items, boolean[] visible, int contentHeight, int scroll, int maxScroll) {}

    public static Result compute(Rect area, int[] heights, int gap, int requestedScroll) {
        int content = 0;
        for (int i = 0; i < heights.length; i++) {
            content += Math.max(0, heights[i]) + (i > 0 ? gap : 0);
        }
        int maxScroll = Math.max(0, content - area.height());
        int scroll = Math.max(0, Math.min(maxScroll, requestedScroll));
        List<Rect> items = new ArrayList<>(heights.length);
        boolean[] visible = new boolean[heights.length];
        int y = area.y() - scroll;
        for (int i = 0; i < heights.length; i++) {
            if (i > 0) {
                y += gap;
            }
            Rect item = new Rect(area.x(), y, area.width(), Math.max(0, heights[i]));
            items.add(item);
            visible[i] = item.height() > 0 && area.encloses(item);
            y += item.height();
        }
        return new Result(List.copyOf(items), visible, content, scroll, maxScroll);
    }

    /** Splits a row into {@code count} equal cells separated by {@code gap}. */
    public static List<Rect> row(Rect area, int count, int gap) {
        List<Rect> cells = new ArrayList<>(count);
        int width = Math.max(0, (area.width() - gap * (count - 1)) / Math.max(1, count));
        for (int i = 0; i < count; i++) {
            int x = area.x() + i * (width + gap);
            int cellWidth = i == count - 1 ? area.right() - x : width;
            cells.add(new Rect(x, area.y(), cellWidth, area.height()));
        }
        return cells;
    }
}
