package org.hyzionstudios.mysticessentials.modules.tutorial.scene;

import java.util.List;

import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialModuleConfig.CameraPlayback;

/**
 * Turns a scene's camera {@link MachinimaSceneDocument.Keyframe keyframes} into a
 * time-sampled camera path: given a time in seconds it returns the interpolated
 * world position and look angles for the server to push via {@code SetServerCamera}.
 *
 * <p>Position uses a Catmull-Rom spline through the keyframes (or straight
 * segments when {@code smoothPath} is off); yaw is interpolated along the
 * shortest angular path and pitch linearly. Angles come out in <b>radians</b>,
 * matching the camera protocol (verified against the builtin top-down demo,
 * which looks straight down with {@code pitch = -PI/2}). The orientation knobs on
 * {@link CameraPlayback} (pitch source, in/offset) are baked in here per keyframe
 * before interpolation.</p>
 */
public final class CameraTimeline {

    /** A sampled camera pose: world position and look angles (radians). */
    public record Sample(double x, double y, double z, float yaw, float pitch) {
    }

    private final double[] frame;
    private final double[] px;
    private final double[] py;
    private final double[] pz;
    private final float[] yaw;
    private final float[] pitch;
    private final double fps;
    private final boolean smoothPath;
    private final double durationSeconds;

    public CameraTimeline(List<MachinimaSceneDocument.Keyframe> keyframes, CameraPlayback config) {
        int n = keyframes.size();
        this.frame = new double[n];
        this.px = new double[n];
        this.py = new double[n];
        this.pz = new double[n];
        this.yaw = new float[n];
        this.pitch = new float[n];
        this.fps = config.framesPerSecond <= 0 ? 60.0 : config.framesPerSecond;
        this.smoothPath = config.smoothPath;

        double yawOffset = Math.toRadians(config.yawOffsetDegrees);
        double pitchOffset = Math.toRadians(config.pitchOffsetDegrees);
        for (int i = 0; i < n; i++) {
            MachinimaSceneDocument.Keyframe k = keyframes.get(i);
            frame[i] = k.frame();
            px[i] = k.px();
            py[i] = k.py();
            pz[i] = k.pz();

            double rawYaw = k.rotY();
            double rawPitch = config.pitchFromLook && Math.abs(k.rotX()) < 1e-4 ? k.lookX() : k.rotX();
            if (config.invertYaw) {
                rawYaw = -rawYaw;
            }
            if (config.invertPitch) {
                rawPitch = -rawPitch;
            }
            yaw[i] = (float) (rawYaw + yawOffset);
            pitch[i] = (float) (rawPitch + pitchOffset);
        }

        double span = n == 0 ? 0 : frame[n - 1] - frame[0];
        this.durationSeconds = span <= 0 ? 0 : span / fps;
    }

    public boolean isEmpty() {
        return frame.length == 0;
    }

    /** Play time (seconds) from the first to the last keyframe. */
    public double durationSeconds() {
        return durationSeconds;
    }

    /**
     * Samples the path at {@code timeSeconds} (clamped to the timeline). A
     * single-keyframe scene returns that keyframe's static pose.
     */
    public Sample sample(double timeSeconds) {
        int n = frame.length;
        if (n == 1) {
            return new Sample(px[0], py[0], pz[0], yaw[0], pitch[0]);
        }
        double targetFrame = frame[0] + timeSeconds * fps;
        if (targetFrame <= frame[0]) {
            return new Sample(px[0], py[0], pz[0], yaw[0], pitch[0]);
        }
        if (targetFrame >= frame[n - 1]) {
            return new Sample(px[n - 1], py[n - 1], pz[n - 1], yaw[n - 1], pitch[n - 1]);
        }
        int i = segmentIndex(targetFrame);
        double segSpan = frame[i + 1] - frame[i];
        double u = segSpan <= 0 ? 0 : (targetFrame - frame[i]) / segSpan;

        double x;
        double y;
        double z;
        if (smoothPath) {
            int i0 = Math.max(0, i - 1);
            int i2 = i + 1;
            int i3 = Math.min(n - 1, i + 2);
            x = catmullRom(px[i0], px[i], px[i2], px[i3], u);
            y = catmullRom(py[i0], py[i], py[i2], py[i3], u);
            z = catmullRom(pz[i0], pz[i], pz[i2], pz[i3], u);
        } else {
            x = lerp(px[i], px[i + 1], u);
            y = lerp(py[i], py[i + 1], u);
            z = lerp(pz[i], pz[i + 1], u);
        }
        float sYaw = (float) lerpAngle(yaw[i], yaw[i + 1], u);
        float sPitch = (float) lerp(pitch[i], pitch[i + 1], u);
        return new Sample(x, y, z, sYaw, sPitch);
    }

    private int segmentIndex(double targetFrame) {
        for (int i = 0; i < frame.length - 1; i++) {
            if (targetFrame >= frame[i] && targetFrame <= frame[i + 1]) {
                return i;
            }
        }
        return frame.length - 2;
    }

    private static double lerp(double a, double b, double u) {
        return a + (b - a) * u;
    }

    /** Interpolates two angles (radians) along the shortest path. */
    private static double lerpAngle(double a, double b, double u) {
        double delta = b - a;
        while (delta > Math.PI) {
            delta -= 2 * Math.PI;
        }
        while (delta < -Math.PI) {
            delta += 2 * Math.PI;
        }
        return a + delta * u;
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double u) {
        double u2 = u * u;
        double u3 = u2 * u;
        return 0.5 * ((2 * p1)
                + (-p0 + p2) * u
                + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * u3);
    }
}
