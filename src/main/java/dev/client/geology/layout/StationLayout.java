package dev.client.geology.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Areas of the station console, derived from the screen size and the measured widths of its labels.
 * The console fills most of the screen at any GUI scale; the side column is as wide as its labels
 * need within limits, and the view takes the rest. No area overlaps another.
 */
public final class StationLayout {
    public static final int MAXIMUM_WIDTH = 640;
    public static final int MAXIMUM_HEIGHT = 400;
    public static final int MINIMUM_SIDEBAR = 104;
    public static final int MINIMUM_VIEW = 120;
    public static final int PADDING = 5;
    public static final int GAP = 4;
    public static final int TAB_PADDING = 12;

    private StationLayout() {}

    /**
     * @param title the panel's title area, between its left edge and the tabs
     * @param view map, 3D view or data list
     * @param sidebar controls, scrolled when they do not fit
     * @param status one line of state and hints
     */
    public record Result(
            Rect panel,
            Rect header,
            Rect title,
            List<Rect> tabs,
            Rect view,
            Rect sidebar,
            Rect status) {}

    /**
     * @param tabWidths measured label widths of the tabs
     * @param preferredSidebar width the side column's widest label asks for
     */
    public static Result compute(
            int screenWidth,
            int screenHeight,
            int[] tabWidths,
            int preferredSidebar,
            int lineHeight) {
        int margin = screenWidth >= 400 && screenHeight >= 260 ? 8 : 2;
        int panelWidth = Math.max(0, Math.min(screenWidth - margin * 2, MAXIMUM_WIDTH));
        int panelHeight = Math.max(0, Math.min(screenHeight - margin * 2, MAXIMUM_HEIGHT));
        Rect panel =
                new Rect(
                        (screenWidth - panelWidth) / 2,
                        (screenHeight - panelHeight) / 2,
                        panelWidth,
                        panelHeight);
        Rect inner = panel.inset(PADDING);

        int headerHeight = lineHeight + 8;
        Rect header = new Rect(inner.x(), inner.y(), inner.width(), headerHeight);
        List<Rect> tabs = tabs(header, tabWidths);
        int tabsLeft = tabs.isEmpty() ? header.right() : tabs.getFirst().x();
        Rect title = new Rect(header.x(), header.y(), tabsLeft - GAP - header.x(), headerHeight);

        int statusHeight = lineHeight + 6;
        Rect status =
                new Rect(inner.x(), inner.bottom() - statusHeight, inner.width(), statusHeight);
        int contentTop = header.bottom() + GAP;
        int contentHeight = status.y() - GAP - contentTop;
        int contentWidth = inner.width();

        int sidebarWidth =
                Math.max(MINIMUM_SIDEBAR, Math.min(preferredSidebar, contentWidth * 45 / 100));
        if (contentWidth - GAP - sidebarWidth < MINIMUM_VIEW) {
            sidebarWidth = Math.max(MINIMUM_SIDEBAR * 3 / 4, contentWidth - GAP - MINIMUM_VIEW);
        }
        sidebarWidth = Math.min(sidebarWidth, Math.max(0, contentWidth - GAP));
        Rect view =
                new Rect(inner.x(), contentTop, contentWidth - GAP - sidebarWidth, contentHeight);
        Rect sidebar = new Rect(view.right() + GAP, contentTop, sidebarWidth, contentHeight);
        return new Result(panel, header, title, List.copyOf(tabs), view, sidebar, status);
    }

    /** Tabs right-aligned in the header; they shrink evenly when the labels do not fit. */
    static List<Rect> tabs(Rect header, int[] tabWidths) {
        List<Rect> tabs = new ArrayList<>(tabWidths.length);
        if (tabWidths.length == 0) {
            return tabs;
        }
        int available = header.width() * 3 / 4;
        int[] widths = new int[tabWidths.length];
        int total = 2 * (tabWidths.length - 1);
        for (int i = 0; i < widths.length; i++) {
            widths[i] = Math.max(24, tabWidths[i] + TAB_PADDING);
            total += widths[i];
        }
        if (total > available) {
            int each = Math.max(1, (available - 2 * (tabWidths.length - 1)) / tabWidths.length);
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.min(widths[i], each);
            }
            total = 2 * (tabWidths.length - 1);
            for (int width : widths) {
                total += width;
            }
        }
        int x = header.right() - total;
        for (int width : widths) {
            tabs.add(new Rect(x, header.y(), width, header.height()));
            x += width + 2;
        }
        return tabs;
    }
}
