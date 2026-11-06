package dev.client.geology.lidar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class LidarCameraTest {
    @Test
    void theTargetProjectsToTheMiddleOfTheView() {
        LidarCamera camera = new LidarCamera();
        camera.reset(100.0D, -20.0D);
        camera.rotate(130.0D, -40.0D);
        Vector3f target = camera.target();
        Matrix4f view = camera.view();
        Matrix4f projection = camera.projection(320, 200, 200.0D);
        float[] p = LidarCamera.project(view, projection, target.x, target.y, target.z, 320, 200);
        assertEquals(160.0F, p[0], 0.01F);
        assertEquals(100.0F, p[1], 0.01F);
    }

    @Test
    void pointsBehindTheCameraAreNotProjected() {
        LidarCamera camera = new LidarCamera();
        camera.reset(50.0D, 0.0D);
        Vector3f eye = camera.eye();
        Vector3f target = camera.target();
        Vector3f behind = new Vector3f(eye).mul(2.0F).sub(target);
        Matrix4f view = camera.view();
        Matrix4f projection = camera.projection(100, 100, 50.0D);
        assertNull(LidarCamera.project(view, projection, behind.x, behind.y, behind.z, 100, 100));
    }

    @Test
    void zoomMovesCloserAndPitchStaysInRange() {
        LidarCamera camera = new LidarCamera();
        camera.reset(100.0D, 0.0D);
        double before = camera.distance();
        camera.zoom(3.0D);
        assertTrue(camera.distance() < before);
        camera.rotate(0.0D, 100_000.0D);
        assertEquals(LidarCamera.MAXIMUM_PITCH, camera.pitch(), 1.0E-9D);
        camera.rotate(0.0D, -100_000.0D);
        assertEquals(LidarCamera.MINIMUM_PITCH, camera.pitch(), 1.0E-9D);
    }

    @Test
    void panningMovesTheTargetAcrossTheScreen() {
        LidarCamera camera = new LidarCamera();
        camera.reset(100.0D, 0.0D);
        Vector3f before = camera.target();
        camera.pan(50.0D, 0.0D, 200);
        Matrix4f view = camera.view();
        Matrix4f projection = camera.projection(200, 200, 100.0D);
        float[] old = LidarCamera.project(view, projection, before.x, before.y, before.z, 200, 200);
        // Dragging right moves the scene right: the old target now sits right of the centre.
        assertTrue(old[0] > 100.0F + 40.0F, "x " + old[0]);
        assertEquals(100.0F, old[1], 0.5F);
    }

    @Test
    void focusingCentresOnTheObject() {
        LidarCamera camera = new LidarCamera();
        camera.reset(500.0D, 0.0D);
        camera.focus(30.0D, -12.0D, 7.0D, 10.0D);
        assertEquals(new Vector3f(30.0F, -12.0F, 7.0F), camera.target());
        assertTrue(camera.distance() < LidarCamera.fitDistance(500.0D));
    }
}
