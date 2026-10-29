package com.dazewell.gram.helpers;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Range;
import android.util.SizeF;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

// Fixed zoom stops for the round video recorder, matching the lenses a logical multi-camera reports:
// the ultrawide floor where allowed, 1x, each telephoto, and twice the longest telephoto, plus 1.5x and 2x
// crops of the main sensor between them. Hardware facts, so cached per camera id for the process.
public final class RoundLensPresets {

    private static final float[] NONE = new float[0];
    private static final int MAX_PRESETS = 4;
    // the stock camera's in-sensor crops, which no Camera2 key reports: kept only where nothing lies within
    // about 30% (ln 1.3), as on the camera memo screen (CameraMemoZoom), so they fill a gap rather than crowd
    // a lens. They come after the lens cap, so at most six stops and no lens gives way to one
    private static final float[] CROPS = {1.5f, 2f};
    private static final float CROP_GAP = 0.26f;
    private static final HashMap<String, float[]> cache = new HashMap<>();

    private RoundLensPresets() {
    }

    // Below 1x a MediaTek logical camera's ultrawide smears a band of edge columns across the round video
    // stream, square or 4:3 alike (seen on an OPPO Find X9 Pro), so the zoom floor stays at 1x there.
    // Other chipsets are untested.
    public static boolean ultrawideAllowed() {
        if (Build.VERSION.SDK_INT >= 31) {
            return !"mediatek".equalsIgnoreCase(Build.SOC_MANUFACTURER);
        }
        return Build.HARDWARE == null || !Build.HARDWARE.toLowerCase(Locale.US).startsWith("mt");
    }

    // empty when the camera has a single lens, or the platform can't zoom by ratio
    public static float[] get(String cameraId) {
        if (cameraId == null) return NONE;
        synchronized (cache) {
            float[] presets = cache.get(cameraId);
            if (presets == null) {
                presets = compute(cameraId);
                cache.put(cameraId, presets);
            }
            return presets;
        }
    }

    public static String label(float ratio) {
        if (ratio >= 1f && Math.abs(ratio - Math.round(ratio)) < 0.05f) {
            return String.format(Locale.US, "%dx", Math.round(ratio));
        }
        return String.format(Locale.US, "%.1fx", ratio);
    }

    private static float[] compute(String cameraId) {
        if (Build.VERSION.SDK_INT < 30) return NONE;
        try {
            final CameraManager manager = (CameraManager) ApplicationLoader.applicationContext.getSystemService(Context.CAMERA_SERVICE);
            final CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
            final Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (range == null) return NONE;
            final float min = range.getLower(), max = range.getUpper();
            final ArrayList<Float> stops = new ArrayList<>();
            if (min < 0.95f && ultrawideAllowed()) {
                stops.add(Math.max(min, Math.round(min * 10f) / 10f));
            }
            stops.add(1f);
            final float base = equivalentFocal(c);
            float longest = 0f;
            final Set<String> physical = c.getPhysicalCameraIds();
            if (base > 0f) {
                for (String id : physical) {
                    final float eq = equivalentFocal(manager.getCameraCharacteristics(id));
                    // anything short of 1.5x is the main sensor or the ultrawide
                    final float ratio = eq / base;
                    if (ratio >= 1.5f && Math.round(ratio) <= max) {
                        addStop(stops, Math.round(ratio));
                        longest = Math.max(longest, Math.round(ratio));
                    }
                }
            }
            if (longest > 0f && longest * 2f <= max) {
                addStop(stops, longest * 2f);
            }
            if (stops.size() < 2) return NONE;
            stops.sort(Float::compare);
            final ArrayList<Float> all = new ArrayList<>(stops.subList(0, Math.min(MAX_PRESETS, stops.size())));
            for (float crop : CROPS) {
                if (crop >= min && crop <= max && !near(all, crop)) {
                    all.add(crop);
                }
            }
            all.sort(Float::compare);
            final float[] result = new float[all.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = all.get(i);
            }
            return result;
        } catch (Exception e) {
            FileLog.e(e);
            return NONE;
        }
    }

    private static void addStop(ArrayList<Float> stops, float ratio) {
        for (float s : stops) {
            if (label(s).equals(label(ratio))) return;
        }
        stops.add(ratio);
    }

    private static boolean near(ArrayList<Float> stops, float ratio) {
        for (float s : stops) {
            if (Math.abs(Math.log(s / ratio)) < CROP_GAP) return true;
        }
        return false;
    }

    // 35mm-equivalent focal length, so lenses on different sensor sizes compare
    private static float equivalentFocal(CameraCharacteristics c) {
        final float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        final SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focal == null || focal.length == 0 || sensor == null) return 0f;
        final double diagonal = Math.hypot(sensor.getWidth(), sensor.getHeight());
        return diagonal <= 0 ? 0f : (float) (focal[0] * 43.27 / diagonal);
    }
}
