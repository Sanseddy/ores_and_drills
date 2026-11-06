package dev.client.geology.layout;

/** An integer screen rectangle; width and height are never negative. */
public record Rect(int x, int y, int width, int height) {
    public static final Rect EMPTY = new Rect(0, 0, 0, 0);

    public Rect {
        width = Math.max(0, width);
        height = Math.max(0, height);
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public int centerX() {
        return x + width / 2;
    }

    public int centerY() {
        return y + height / 2;
    }

    public boolean isEmpty() {
        return width == 0 || height == 0;
    }

    public boolean contains(double px, double py) {
        return px >= x && px < right() && py >= y && py < bottom();
    }

    /** Whether {@code other} lies completely inside this rectangle. */
    public boolean encloses(Rect other) {
        return other.x >= x
                && other.y >= y
                && other.right() <= right()
                && other.bottom() <= bottom();
    }

    /** Whether the two rectangles share any area; touching edges do not count. */
    public boolean overlaps(Rect other) {
        if (isEmpty() || other.isEmpty()) {
            return false;
        }
        return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
    }

    public Rect inset(int amount) {
        return new Rect(x + amount, y + amount, width - amount * 2, height - amount * 2);
    }

    public Rect withHeight(int newHeight) {
        return new Rect(x, y, width, newHeight);
    }

    public Rect offset(int dx, int dy) {
        return new Rect(x + dx, y + dy, width, height);
    }
}
