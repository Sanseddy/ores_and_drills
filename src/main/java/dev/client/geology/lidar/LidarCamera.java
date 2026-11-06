package dev.client.geology.lidar;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Free orbit camera of the LiDAR view. Display space is centred on the equipment: X east, Y up, Z
 * south, in blocks, heights already multiplied by the view's vertical exaggeration. The camera
 * looks at a target point from a distance along its yaw and pitch; dragging turns it around the
 * target, the wheel moves it closer, panning moves the target across the screen plane — sideways,
 * up and down — and it can be centred on any point. Pitch may go below the horizon to look at a
 * structure from underneath.
 */
public final class LidarCamera {
    public static final float FOV_Y = (float) Math.toRadians(50.0D);
    public static final double MINIMUM_PITCH = Math.toRadians(-85.0D);
    public static final double MAXIMUM_PITCH = Math.toRadians(89.0D);
    public static final double DEFAULT_YAW = Math.toRadians(35.0D);
    public static final double DEFAULT_PITCH = Math.toRadians(28.0D);

    private double yaw = DEFAULT_YAW;
    private double pitch = DEFAULT_PITCH;
    private double distance = 100.0D;
    private double minimumDistance = 2.0D;
    private double maximumDistance = 10_000.0D;
    private final Vector3f target = new Vector3f();
    private boolean placed;

    /** Frames a scene of horizontal radius {@code radius} whose middle lies at {@code centreY}. */
    public void reset(double radius, double centreY) {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        target.set(0.0F, (float) centreY, 0.0F);
        distance = fitDistance(radius);
        maximumDistance = Math.max(64.0D, radius * 8.0D);
        minimumDistance = Math.max(1.0D, Math.min(4.0D, radius * 0.01D));
        placed = true;
    }

    /** Distance at which a sphere of {@code radius} fills the vertical field of view. */
    public static double fitDistance(double radius) {
        return Math.max(4.0D, radius) / Math.sin(FOV_Y * 0.5D) * 1.05D;
    }

    public boolean placed() {
        return placed;
    }

    public double yaw() {
        return yaw;
    }

    public double pitch() {
        return pitch;
    }

    public double distance() {
        return distance;
    }

    public Vector3f target() {
        return new Vector3f(target);
    }

    /** Turns by a mouse drag in screen pixels. */
    public void rotate(double deltaX, double deltaY) {
        yaw = wrap(yaw - deltaX * 0.008D);
        pitch = clamp(pitch + deltaY * 0.008D, MINIMUM_PITCH, MAXIMUM_PITCH);
    }

    /** Moves the target by a drag across a view {@code viewHeight} pixels tall. */
    public void pan(double deltaX, double deltaY, int viewHeight) {
        double perPixel = 2.0D * distance * Math.tan(FOV_Y * 0.5D) / Math.max(1, viewHeight);
        Vector3f right = right();
        Vector3f up = up();
        target.add(right.mul((float) (-deltaX * perPixel)));
        target.add(up.mul((float) (deltaY * perPixel)));
    }

    /** Moves the target straight up or down by a vertical drag across the view. */
    public void lift(double deltaY, int viewHeight) {
        double perPixel = 2.0D * distance * Math.tan(FOV_Y * 0.5D) / Math.max(1, viewHeight);
        target.y += (float) (deltaY * perPixel);
    }

    /** Wheel zoom: each notch brings the camera 15 % closer. */
    public void zoom(double steps) {
        distance = clamp(distance * Math.pow(0.85D, steps), minimumDistance, maximumDistance);
    }

    /** Centres on a point and frames an object of {@code size} blocks. */
    public void focus(double x, double y, double z, double size) {
        target.set((float) x, (float) y, (float) z);
        distance =
                clamp(Math.max(fitDistance(size * 0.75D), 12.0D), minimumDistance, maximumDistance);
    }

    public Vector3f eye() {
        double cosPitch = Math.cos(pitch);
        return new Vector3f(
                (float) (target.x + distance * cosPitch * Math.sin(yaw)),
                (float) (target.y + distance * Math.sin(pitch)),
                (float) (target.z + distance * cosPitch * Math.cos(yaw)));
    }

    private Vector3f forward() {
        return new Vector3f(target).sub(eye()).normalize();
    }

    private Vector3f right() {
        Vector3f right = forward().cross(0.0F, 1.0F, 0.0F, new Vector3f());
        if (right.lengthSquared() < 1.0E-8F) {
            right.set((float) Math.cos(yaw), 0.0F, (float) -Math.sin(yaw));
        }
        return right.normalize();
    }

    private Vector3f up() {
        return right().cross(forward(), new Vector3f()).normalize();
    }

    public Matrix4f view() {
        return new Matrix4f().lookAt(eye(), new Vector3f(target), new Vector3f(0.0F, 1.0F, 0.0F));
    }

    /** Perspective of a view {@code width} × {@code height}; clip planes follow the distance. */
    public Matrix4f projection(int width, int height, double sceneRadius) {
        float aspect = width / (float) Math.max(1, height);
        float near = (float) Math.max(0.05D, distance * 0.01D);
        float far = (float) (distance + Math.max(256.0D, sceneRadius * 4.0D) + distance);
        return new Matrix4f().perspective(FOV_Y, aspect, near, far);
    }

    /**
     * Screen position (pixels from the view's top left) and view depth of a display-space point, or
     * null when it lies behind the camera.
     */
    public static float[] project(
            Matrix4f view, Matrix4f projection, float x, float y, float z, int width, int height) {
        Vector4f clip = new Vector4f(x, y, z, 1.0F).mul(view).mul(projection);
        if (clip.w <= 1.0E-4F) {
            return null;
        }
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        return new float[] {(ndcX * 0.5F + 0.5F) * width, (0.5F - ndcY * 0.5F) * height, clip.w};
    }

    static double wrap(double angle) {
        double turn = Math.PI * 2.0D;
        double result = angle % turn;
        return result < 0.0D ? result + turn : result;
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
