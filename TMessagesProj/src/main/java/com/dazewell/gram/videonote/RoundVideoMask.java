package com.dazewell.gram.videonote;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Build;
import android.util.Log;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.video.MediaCodecVideoConvertor;
import org.telegram.ui.Components.AnimatedFileNative;
import org.telegram.ui.Components.voip.AnimatedFileInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

import xyz.nextalone.nagram.NaConfig;

// Re-encodes a round video on its way to Downloads so everything outside the circle is black,
// whatever the sender's client baked into the square frame's corners. Telegram's cached copy is
// never touched: the masked file is a temp in cache/round_mask/<uuid>/ and release() deletes it
// once the save has copied it. Any failure hands back the original, so a save is never empty.
public final class RoundVideoMask {

    private static final String TAG = "RoundVideoMask";
    private static final String DIR = "round_mask";
    // An encode keeps writing its output, so only a folder nothing has touched for this long is
    // left over from a save that threw between prepare() and release().
    private static final long STALE_MS = 60 * 60 * 1000L;
    private static final int FALLBACK_BITRATE = 1_500_000;
    private static final int FALLBACK_FRAMERATE = 30;

    // MediaCodecVideoConvertor configures its codecs on the thread that calls convertVideo, so this marks
    // "the encode running on this thread is a round-video save" without adding a field to upstream's params.
    private static final ThreadLocal<Boolean> SPEED_HINT = new ThreadLocal<>();

    // NAX_SMOKE probe state, one save at a time (MediaLoader saves serially). Stages: 0 awaitNewImage,
    // 1 drawImage, 2 swapBuffers, 3 encoder dequeueOutput, 4 decoder dequeueOutput, 5 decoder input.
    // Counts: 0 frames drawn, 1 encoder try-again, 2 decoder try-again, 3 frame callbacks on main, 4 frame callbacks.
    private static volatile boolean probeActive;
    private static final long[] PROBE_NS = new long[6];
    private static final int[] PROBE_N = new int[5];

    public static long probeT() {
        return probeActive ? System.nanoTime() : 0;
    }

    public static void probeAdd(int stage, long t0) {
        if (t0 != 0) {
            PROBE_NS[stage] += System.nanoTime() - t0;
        }
    }

    public static void probeCount(int k) {
        if (probeActive) {
            PROBE_N[k]++;
        }
    }

    public static void probeFrameCallback() {
        if (probeActive) {
            PROBE_N[4]++;
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                PROBE_N[3]++;
            }
        }
    }

    private static String probeDump() {
        StringBuilder sb = new StringBuilder();
        String[] names = {"awaitMs", "drawMs", "swapMs", "encDeqMs", "decDeqMs", "decInMs"};
        for (int i = 0; i < PROBE_NS.length; i++) {
            sb.append(' ').append(names[i]).append('=').append(PROBE_NS[i] / 1_000_000);
        }
        sb.append(" frames=").append(PROBE_N[0]).append(" encTryAgain=").append(PROBE_N[1]).append(" decTryAgain=").append(PROBE_N[2])
                .append(" cbOnMain=").append(PROBE_N[3]).append(" cbTotal=").append(PROBE_N[4]);
        return sb.toString();
    }

    public interface Cancel {
        boolean isCancelled();
    }

    private RoundVideoMask() {
    }

    // The save loop only runs this path on API 29+; older devices take the legacy copy unmasked,
    // which is why the setting row is hidden there.
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    // Runs on the save thread and blocks for the length of the encode. Returns the masked temp,
    // or source unchanged when the feature is off, this isn't a round video, or anything fails.
    public static File prepare(File source, MessageObject message, Cancel cancel) {
        Log.i(TAG, "NAX_SMOKE_round-video-black-corners BEGIN build=" + org.telegram.messenger.BuildVars.BUILD_VERSION_STRING + " app=" + ApplicationLoader.applicationContext.getPackageName() + " round=" + (message != null && message.isRoundVideo()));
        if (!isSupported()
                || !NaConfig.INSTANCE.getBlackRoundVideoCorners().Bool()
                || message == null || !message.isRoundVideo()
                || source == null || !source.exists()) {
            return source;
        }
        File workDir = null;
        try {
            File baseDir = new File(ApplicationLoader.applicationContext.getCacheDir(), DIR);
            sweep(baseDir);
            final long probeStart = android.os.SystemClock.elapsedRealtime();
            String path = source.getAbsolutePath();
            int[] params = new int[AnimatedFileInfo.PARAM_NUM_COUNT];
            AnimatedFileNative.getVideoInfo(path, params, 0);
            int width = params[AnimatedFileInfo.PARAM_NUM_WIDTH];
            int height = params[AnimatedFileInfo.PARAM_NUM_HEIGHT];
            long durationUs = params[AnimatedFileInfo.PARAM_NUM_DURATION] * 1000L;
            if (params[AnimatedFileInfo.PARAM_NUM_SUPPORTED_VIDEO_CODEC] == 0 || width <= 0 || height <= 0 || durationUs <= 0) {
                Log.w(TAG, "unsupported source, saving unmasked");
                return source;
            }
            int rotation = params[AnimatedFileInfo.PARAM_NUM_ROTATION];
            // Same as MediaController.convertVideo: the decoder surface applies the rotation, so the
            // output is the rotated size and the muxer writes rotation 0.
            int outWidth = width;
            int outHeight = height;
            if (rotation == 90 || rotation == 270) {
                outWidth = height;
                outHeight = width;
            }
            int bitrate = MediaController.getVideoBitrate(path);
            if (bitrate <= 0) {
                bitrate = params[AnimatedFileInfo.PARAM_NUM_BITRATE];
            }
            if (bitrate <= 0) {
                bitrate = FALLBACK_BITRATE;
            }
            int framerate = params[AnimatedFileInfo.PARAM_NUM_FRAMERATE];
            if (framerate <= 0) {
                framerate = FALLBACK_FRAMERATE;
            }

            workDir = new File(baseDir, UUID.randomUUID().toString());
            if (!workDir.mkdirs()) {
                Log.w(TAG, "cannot create temp dir, saving unmasked");
                return source;
            }
            final long probeInfoMs = android.os.SystemClock.elapsedRealtime() - probeStart;
            File mask = new File(workDir, "mask.png");
            writeMask(mask, outWidth, outHeight);
            final long probeMaskMs = android.os.SystemClock.elapsedRealtime() - probeStart - probeInfoMs;
            String probeEncoder = "?";
            try {
                android.media.MediaCodec probeCodec = android.media.MediaCodec.createEncoderByType("video/avc");
                probeEncoder = probeCodec.getName();
                probeCodec.release();
            } catch (Throwable ignore) {
            }
            Log.i(TAG, "NAX_SMOKE_round-video-black-corners INFO " + width + "x" + height + " rot=" + rotation + " fps=" + framerate + " bitrate=" + bitrate
                    + " durMs=" + (durationUs / 1000) + " srcBytes=" + source.length() + " hasAudio=" + params[AnimatedFileInfo.PARAM_NUM_HAS_AUDIO]
                    + " aacAudio=" + params[AnimatedFileInfo.PARAM_NUM_SUPPORTED_AUDIO_CODEC] + " encoder=" + probeEncoder + " infoMs=" + probeInfoMs + " maskMs=" + probeMaskMs);
            final int[] probeRestarts = new int[1];
            final float[] probeLastProgress = new float[1];
            final long[] probeFirstWriteMs = {-1};
            // Same file name as the source so nothing downstream that reads it can tell the difference.
            File output = new File(workDir, source.getName());

            VideoEditedInfo info = new VideoEditedInfo();
            info.paintPath = mask.getAbsolutePath();
            MediaController.VideoConvertorListener listener = new MediaController.VideoConvertorListener() {
                @Override
                public boolean checkConversionCanceled() {
                    return cancel != null && cancel.isCancelled();
                }

                @Override
                public void didWriteData(long availableSize, float progress) {
                    if (probeFirstWriteMs[0] < 0) {
                        probeFirstWriteMs[0] = android.os.SystemClock.elapsedRealtime() - probeStart;
                    }
                    // A drop back toward zero means the convertor started the whole encode again.
                    if (progress + 0.3f < probeLastProgress[0]) {
                        probeRestarts[0]++;
                    }
                    probeLastProgress[0] = progress;
                }
            };
            final long probeConvertStart = android.os.SystemClock.elapsedRealtime();
            // needCompress must be true: false sends an AVC source down readAndWriteTracks, which copies
            // the stream without drawing the paint layer and still reports success.
            MediaCodecVideoConvertor.ConvertVideoParams convertParams = MediaCodecVideoConvertor.ConvertVideoParams.of(
                    path, output, 0,
                    rotation, false,
                    width, height,
                    outWidth, outHeight,
                    framerate, bitrate, bitrate,
                    -1, -1, -1,
                    true, durationUs,
                    listener,
                    info
            );
            boolean error;
            SPEED_HINT.set(Boolean.TRUE);
            java.util.Arrays.fill(PROBE_NS, 0);
            java.util.Arrays.fill(PROBE_N, 0);
            probeActive = true;
            try {
                error = new MediaCodecVideoConvertor().convertVideo(convertParams);
            } finally {
                probeActive = false;
                SPEED_HINT.remove();
            }
            Log.i(TAG, "NAX_SMOKE_round-video-black-corners STAGES" + probeDump());
            final long probeConvertMs = android.os.SystemClock.elapsedRealtime() - probeConvertStart;
            final long probeCheckStart = android.os.SystemClock.elapsedRealtime();
            final boolean probePlayable = output.exists() && output.length() > 0 && isPlayable(output);
            Log.i(TAG, "NAX_SMOKE_round-video-black-corners CONVERT error=" + error + " convertMs=" + probeConvertMs + " firstWriteMs=" + probeFirstWriteMs[0]
                    + " restarts=" + probeRestarts[0] + " lastProgress=" + probeLastProgress[0] + " outBytes=" + output.length()
                    + " playable=" + probePlayable + " checkMs=" + (android.os.SystemClock.elapsedRealtime() - probeCheckStart));
            // The convertor logs a finishMovie() failure without reporting an error, which can leave a
            // non-empty file nothing can play, so the output has to parse as a video before it replaces the original.
            if (error || listener.checkConversionCanceled() || !output.exists() || output.length() == 0 || !isPlayable(output)) {
                Log.w(TAG, "encode did not finish (error=" + error + "), saving unmasked");
                Log.i(TAG, "NAX_SMOKE_round-video-black-corners END fallback");
                deleteRecursive(workDir);
                return source;
            }
            mask.delete();
            Log.i(TAG, "NAX_SMOKE_round-video-black-corners END masked totalMs=" + (android.os.SystemClock.elapsedRealtime() - probeStart));
            return output;
        } catch (Throwable t) {
            Log.e(TAG, "mask failed, saving unmasked", t);
            if (workDir != null) {
                deleteRecursive(workDir);
            }
            return source;
        }
    }

    // Without these the codecs default to real-time priority and, on MediaTek at least, encode at
    // playback speed. Values are media3 Transformer's (DefaultEncoderFactory / DefaultDecoderFactory):
    // 1000 instead of Integer.MAX_VALUE because some chipsets throw near the overflow limit, and the
    // decoder only gets a priority on the two chipsets whose configure fails without one.
    public static void applySpeedHint(android.media.MediaFormat format, boolean encoder) {
        if (Build.VERSION.SDK_INT < 25 || !Boolean.TRUE.equals(SPEED_HINT.get())) {
            return;
        }
        if (encoder) {
            format.setInteger(android.media.MediaFormat.KEY_PRIORITY, 1);
            format.setInteger(android.media.MediaFormat.KEY_OPERATING_RATE, 1000);
        } else {
            if (Build.VERSION.SDK_INT >= 31 && ("s5e8835".equals(Build.SOC_MODEL) || "SA8155P".equals(Build.SOC_MODEL))) {
                format.setInteger(android.media.MediaFormat.KEY_PRIORITY, 1);
            }
            format.setInteger(android.media.MediaFormat.KEY_OPERATING_RATE, 10000);
        }
    }

    // Deletes a file prepare() made, and its folder. Anything else (the original cache file) is left alone.
    public static void release(File file) {
        if (file == null) {
            return;
        }
        try {
            File workDir = file.getParentFile();
            File baseDir = workDir == null ? null : workDir.getParentFile();
            if (baseDir != null && DIR.equals(baseDir.getName())
                    && baseDir.getParentFile() != null
                    && baseDir.getParentFile().equals(ApplicationLoader.applicationContext.getCacheDir())) {
                deleteRecursive(workDir);
            }
        } catch (Throwable t) {
            Log.e(TAG, "release failed", t);
        }
    }

    // Opaque black with a transparent, antialiased inscribed circle. TextureRenderer draws it over
    // every frame with premultiplied alpha, so the circle shows the video and the rest turns black.
    private static void writeMask(File target, int width, int height) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.BLACK);
            Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            canvas.drawCircle(width / 2f, height / 2f, Math.min(width, height) / 2f, clear);
            try (FileOutputStream out = new FileOutputStream(target)) {
                // TextureRenderer skips a paint layer it can't decode and still encodes, so a bad PNG
                // has to fail here or the save ships an unmasked re-encode.
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IllegalStateException("mask png not written");
                }
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static boolean isPlayable(File file) {
        int[] params = new int[AnimatedFileInfo.PARAM_NUM_COUNT];
        AnimatedFileNative.getVideoInfo(file.getAbsolutePath(), params, 0);
        return params[AnimatedFileInfo.PARAM_NUM_SUPPORTED_VIDEO_CODEC] != 0
                && params[AnimatedFileInfo.PARAM_NUM_WIDTH] > 0
                && params[AnimatedFileInfo.PARAM_NUM_DURATION] > 0;
    }

    private static void sweep(File baseDir) {
        File[] dirs = baseDir.listFiles();
        if (dirs == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File dir : dirs) {
            if (now - newestModified(dir) > STALE_MS) {
                deleteRecursive(dir);
            }
        }
    }

    private static long newestModified(File file) {
        long newest = file.lastModified();
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                newest = Math.max(newest, child.lastModified());
            }
        }
        return newest;
    }

    private static void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }
}
