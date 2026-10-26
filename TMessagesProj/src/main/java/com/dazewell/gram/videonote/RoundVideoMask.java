package com.dazewell.gram.videonote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.google.common.collect.ImmutableList;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Components.AnimatedFileNative;
import org.telegram.ui.Components.voip.AnimatedFileInfo;

import java.io.File;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import xyz.nextalone.nagram.NaConfig;

// Re-encodes a round video on its way to Downloads so everything outside the circle is black,
// whatever the sender's client baked into the square frame's corners. Telegram's cached copy is
// never touched: the masked file is a temp in cache/round_mask/<uuid>/ and release() deletes it
// once the save has copied it. Any failure hands back the original, so a save is never empty.
//
// media3 Transformer rather than MediaCodecVideoConvertor: the convertor's one-frame-at-a-time loop
// idles in dequeue timeouts on MediaTek codecs and encoded at about real time; Transformer keeps the
// codecs busy and needs no hook in upstream's video files.
@OptIn(markerClass = UnstableApi.class)
public final class RoundVideoMask {

    private static final String TAG = "RoundVideoMask";
    private static final String DIR = "round_mask";
    // An encode keeps writing its output, so only a folder nothing has touched for this long is
    // left over from a save that threw between prepare() and release().
    private static final long STALE_MS = 60 * 60 * 1000L;
    private static final int FALLBACK_BITRATE = 1_500_000;
    // Transformer's own watchdog catches a stalled export; this only bounds one that crawls.
    private static final long MIN_TIMEOUT_MS = 60_000L;
    private static final long CANCEL_WAIT_MS = 30_000L;
    private static final long POLL_MS = 200L;

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
            String path = source.getAbsolutePath();
            int[] params = new int[AnimatedFileInfo.PARAM_NUM_COUNT];
            AnimatedFileNative.getVideoInfo(path, params, 0);
            int width = params[AnimatedFileInfo.PARAM_NUM_WIDTH];
            int height = params[AnimatedFileInfo.PARAM_NUM_HEIGHT];
            long durationMs = params[AnimatedFileInfo.PARAM_NUM_DURATION];
            if (params[AnimatedFileInfo.PARAM_NUM_SUPPORTED_VIDEO_CODEC] == 0 || width <= 0 || height <= 0 || durationMs <= 0) {
                Log.w(TAG, "unsupported source, saving unmasked");
                return source;
            }
            int rotation = params[AnimatedFileInfo.PARAM_NUM_ROTATION];
            // Transformer hands effects the frame in display orientation, so the mask is the rotated size.
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

            workDir = new File(baseDir, UUID.randomUUID().toString());
            if (!workDir.mkdirs()) {
                Log.w(TAG, "cannot create temp dir, saving unmasked");
                return source;
            }
            // Same file name as the source so nothing downstream that reads it can tell the difference.
            File output = new File(workDir, source.getName());

            long timeoutMs = Math.max(MIN_TIMEOUT_MS, durationMs * 3);
            int result = export(source, output, createMask(outWidth, outHeight), bitrate, timeoutMs, cancel);
            boolean playable = result == EXPORT_DONE && output.exists() && output.length() > 0 && isPlayable(output);
            if (!playable) {
                Log.w(TAG, "export did not finish (result=" + result + "), saving unmasked");
                // An export whose cancel never returned may still be writing here; the sweep gets it later.
                if (result != EXPORT_ABANDONED) {
                    deleteRecursive(workDir);
                }
                return source;
            }
            return output;
        } catch (Throwable t) {
            Log.e(TAG, "mask failed, saving unmasked", t);
            if (workDir != null) {
                deleteRecursive(workDir);
            }
            return source;
        }
    }

    private static final int EXPORT_DONE = 0;
    private static final int EXPORT_FAILED = 1;
    private static final int EXPORT_CANCELLED = 2;
    // Cancel was posted but did not return in time, so the export may still be running.
    private static final int EXPORT_ABANDONED = 3;

    // Transformer must be built, started and cancelled on its own looper and reports there, so it gets
    // a HandlerThread and the save thread waits on latches. Teardown only happens once a terminal state
    // is confirmed: a listener callback, or a cancel() that returned (cancel fires no callback).
    private static int export(File source, File output, Bitmap mask, int bitrate, long timeoutMs, Cancel cancel) throws InterruptedException {
        Context context = ApplicationLoader.applicationContext;
        HandlerThread thread = new HandlerThread("RoundVideoMask");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Transformer> transformerRef = new AtomicReference<>();
        boolean terminal = false;
        try {
            handler.post(() -> {
                try {
                    Transformer transformer = new Transformer.Builder(context)
                            .setLooper(thread.getLooper())
                            // On by default on API 35+, and this is private chat media.
                            .setUsePlatformDiagnostics(false)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .setEncoderFactory(new DefaultEncoderFactory.Builder(context)
                                    .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                                    .build())
                            .addListener(new Transformer.Listener() {
                                @Override
                                public void onCompleted(Composition composition, ExportResult exportResult) {
                                    completed.set(true);
                                    finished.countDown();
                                }

                                @Override
                                public void onError(Composition composition, ExportResult exportResult, ExportException exportException) {
                                    Log.e(TAG, "export error", exportException);
                                    finished.countDown();
                                }
                            })
                            .build();
                    transformerRef.set(transformer);
                    EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                            .setEffects(new Effects(Collections.emptyList(), ImmutableList.of(
                                    new OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(mask))))))
                            .build();
                    transformer.start(item, output.getAbsolutePath());
                } catch (Throwable t) {
                    // An uncaught throw on this thread would take the app down with it.
                    Log.e(TAG, "export start failed", t);
                    finished.countDown();
                }
            });

            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            boolean done = false;
            boolean interrupted = false;
            while (!done) {
                try {
                    done = finished.await(POLL_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // Skipping the cancel would leave the export writing into a folder about to be deleted.
                    interrupted = true;
                    break;
                }
                if (!done && ((cancel != null && cancel.isCancelled()) || SystemClock.elapsedRealtime() > deadline)) {
                    break;
                }
            }
            if (done) {
                terminal = true;
                return completed.get() ? EXPORT_DONE : EXPORT_FAILED;
            }

            CountDownLatch cancelled = new CountDownLatch(1);
            // Only a cancel() that returned proves the export stopped; one that threw is treated as abandoned.
            AtomicBoolean cancelReturned = new AtomicBoolean();
            handler.post(() -> {
                try {
                    Transformer transformer = transformerRef.get();
                    if (transformer != null) {
                        transformer.cancel();
                    }
                    cancelReturned.set(true);
                } catch (Throwable t) {
                    Log.e(TAG, "export cancel failed", t);
                } finally {
                    cancelled.countDown();
                }
            });
            try {
                terminal = cancelled.await(CANCEL_WAIT_MS, TimeUnit.MILLISECONDS) && cancelReturned.get();
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return terminal ? EXPORT_CANCELLED : EXPORT_ABANDONED;
        } finally {
            thread.quitSafely();
            // A still-running export may be reading the mask on its GL thread; leave that one to the GC.
            if (terminal) {
                mask.recycle();
            }
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

    // Opaque black with a transparent, antialiased inscribed circle. OverlayEffect blends it as
    // overlay * alpha + video * (1 - alpha), so the circle shows the video and the rest turns black.
    private static Bitmap createMask(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        canvas.drawCircle(width / 2f, height / 2f, Math.min(width, height) / 2f, clear);
        return bitmap;
    }

    // A finished export can still leave a file nothing can play, so the output has to parse as a
    // video before it replaces the original.
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
