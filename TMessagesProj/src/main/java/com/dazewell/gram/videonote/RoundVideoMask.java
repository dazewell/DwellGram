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
            File mask = new File(workDir, "mask.png");
            writeMask(mask, outWidth, outHeight);
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
                }
            };
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
            boolean error = new MediaCodecVideoConvertor().convertVideo(convertParams);
            if (error || listener.checkConversionCanceled() || !output.exists() || output.length() == 0) {
                Log.w(TAG, "encode did not finish (error=" + error + "), saving unmasked");
                deleteRecursive(workDir);
                return source;
            }
            mask.delete();
            return output;
        } catch (Throwable t) {
            Log.e(TAG, "mask failed, saving unmasked", t);
            if (workDir != null) {
                deleteRecursive(workDir);
            }
            return source;
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
