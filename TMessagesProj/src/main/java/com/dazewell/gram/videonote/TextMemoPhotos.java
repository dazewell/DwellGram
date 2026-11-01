package com.dazewell.gram.videonote;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.RadialProgressView;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Photos and videos for the text memo, picked with the system picker so no storage permission is asked and the app lock
 * is never involved. A picked uri is only readable while the memo lives, so each one is copied to the cache and the memo
 * holds plain file paths from then on. The copy is our own loop rather than MediaController.copyFileToCache, which has no
 * way to report progress or stop. Media is never part of the chat's draft: leaving without sending drops it.
 */
final class TextMemoPhotos {

    // Photos and videos together
    static final int MAX = 10;
    // A video is copied whole before it is sent, so a very large one is refused rather than filling the cache
    private static final long MAX_VIDEO_BYTES = 1024L * 1024 * 1024;
    private static final long PROGRESS_INTERVAL_MS = 100;
    // Its own queue: a gigabyte pulled from a cloud provider would hold the app-wide Utilities.globalQueue for minutes
    private static final DispatchQueue copyQueue = new DispatchQueue("textMemoCopy");

    /** One picked item, from the picker's result until its copy resolves. Touched from the UI thread and the copy thread. */
    static final class Pick {
        final Uri uri;
        volatile boolean cancelled;
        // The ring in the strip's placeholder, when it is on screen; UI thread only
        RadialProgressView ring;
        // 0..1 when the provider reports a size, else -1; UI thread only
        float progress = -1;
        // Cleared when the memo is torn down, so a copy stuck in a provider's read doesn't keep the activity reachable
        volatile Listener listener;

        Pick(Uri uri) {
            this.uri = uri;
        }
    }

    interface Listener {
        /** On the UI thread, throttled. */
        void onProgress(Pick pick, float progress);

        /** On the UI thread, once per pick that started copying; null when it was cancelled, refused or failed. */
        void onDone(Pick pick, String path);
    }

    private TextMemoPhotos() {
    }

    static Intent pickIntent() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return intent;
    }

    /** What the picker returned, in pick order. */
    static List<Uri> urisOf(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    if (clip.getItemAt(i).getUri() != null) {
                        uris.add(clip.getItemAt(i).getUri());
                    }
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        return uris;
    }

    /** By the copy's extension, which keeps the picked file's own name. */
    static boolean isVideo(String path) {
        int dot = path == null ? -1 : path.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substring(dot + 1).toLowerCase(Locale.US));
        return mime != null && mime.startsWith("video/");
    }

    /** Copies the picks to the cache one after another, in order, off the UI thread. A cancelled pick is skipped or stopped at its next chunk. */
    static void copyAsync(List<Pick> picks, Listener listener) {
        for (Pick pick : picks) {
            pick.listener = listener;
        }
        copyQueue.postRunnable(() -> {
            for (Pick pick : picks) {
                if (pick.cancelled) {
                    continue;
                }
                String path = null;
                try {
                    path = copy(pick);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                String done = path;
                Listener target = pick.listener;
                if (target != null) {
                    AndroidUtilities.runOnUIThread(() -> target.onDone(pick, done));
                } else if (done != null) {
                    // Cancelled just after it finished: nobody will send it
                    discard(java.util.Collections.singletonList(done));
                }
            }
        });
    }

    private static String copy(Pick pick) throws Exception {
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        String type = resolver.getType(pick.uri);
        String name = FileLoader.fixFileName(MediaController.getFileName(pick.uri));
        // A provider may report no type, or a generic one, for a file that is plainly a video by its name; sending goes by the name
        boolean video = type != null && type.startsWith("video/") || isVideo(name);
        long size = size(resolver, pick.uri);
        if (video && size > MAX_VIDEO_BYTES) {
            return null;
        }
        if (TextUtils.isEmpty(name)) {
            name = "memo_" + System.currentTimeMillis() + (video ? ".mp4" : ".jpg");
        } else if (video && !isVideo(name)) {
            // The video flag is re-derived from the extension when sending; named before the collision check so nothing is renamed over
            name += ".mp4";
        }
        File dir = AndroidUtilities.getSharingDirectory();
        dir.mkdirs();
        if (AndroidUtilities.isInternalUri(Uri.fromFile(dir))) {
            return null;
        }
        File out = new File(dir, name);
        for (int count = 1; out.exists(); count++) {
            int dot = name.lastIndexOf('.');
            out = new File(dir, dot > 0 ? name.substring(0, dot) + " (" + count + ")" + name.substring(dot) : name + " (" + count + ")");
        }
        boolean finished = false;
        InputStream in = null;
        FileOutputStream output = null;
        try {
            in = resolver.openInputStream(pick.uri);
            if (in == null || isInternalFd(in)) {
                return null;
            }
            output = new FileOutputStream(out);
            byte[] buffer = new byte[128 * 1024];
            long total = 0;
            long lastReport = 0;
            int len;
            while ((len = in.read(buffer)) != -1) {
                if (pick.cancelled) {
                    return null;
                }
                output.write(buffer, 0, len);
                total += len;
                if (video && total > MAX_VIDEO_BYTES) {
                    // A provider that didn't report its size
                    return null;
                }
                long now = System.currentTimeMillis();
                if (video && size > 0 && now - lastReport >= PROGRESS_INTERVAL_MS) {
                    lastReport = now;
                    float progress = Math.min(1f, total / (float) size);
                    Listener target = pick.listener;
                    if (target != null) {
                        AndroidUtilities.runOnUIThread(() -> target.onProgress(pick, progress));
                    }
                }
            }
            output.flush();
            finished = !pick.cancelled;
        } finally {
            closeQuietly(in);
            closeQuietly(output);
            if (!finished) {
                out.delete();
            }
        }
        if (!finished) {
            return null;
        }
        return out.getAbsolutePath();
    }

    // The picked uri may point at one of the app's own private files; refuse those, as MediaController.copyFileToCache does
    @SuppressLint("DiscouragedPrivateApi")
    private static boolean isInternalFd(InputStream in) {
        if (in instanceof FileInputStream) {
            try {
                Method getInt = FileDescriptor.class.getDeclaredMethod("getInt$");
                int fd = (Integer) getInt.invoke(((FileInputStream) in).getFD());
                return AndroidUtilities.isInternalUri(fd);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return false;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Throwable ignore) {
        }
    }

    // -1 when the provider doesn't say
    private static long size(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return -1;
    }

    /** An album when there are several; the text rides on the first item as its caption. */
    static void send(AccountInstance account, List<String> paths, String caption, long dialogId) {
        ArrayList<SendMessagesHelper.SendingMediaInfo> infos = new ArrayList<>();
        for (String path : paths) {
            if (!new File(path).exists()) {
                continue;
            }
            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            info.path = path;
            info.isVideo = isVideo(path);
            infos.add(info);
        }
        if (infos.isEmpty()) {
            // Every copy is gone: the text still goes
            if (!TextUtils.isEmpty(caption)) {
                sendText(account, caption, dialogId);
            }
            return;
        }
        // A caption has its own, shorter limit than a message
        boolean captionFits = caption == null || caption.length() <= MessagesController.getInstance(account.getCurrentAccount()).getCaptionMaxLengthLimit();
        infos.get(0).caption = captionFits ? caption : null;
        SendMessagesHelper.prepareSendingMedia(account, infos, dialogId, null, null, null, null, false, infos.size() > 1, null, true, 0, 0, 0, false, null, null, 0, false, 0, 0, null);
        if (!captionFits) {
            sendText(account, caption, dialogId);
        }
    }

    /**
     * SendMessagesHelper.prepareSendingText for a memo, with the same queue hops and splitting, but each part exempt
     * from Ghost Hold: a memo is written to go out now. A copy, so keep it in step with upstream's when that changes.
     */
    static void sendText(AccountInstance account, CharSequence text, long dialogId) {
        account.getMessagesStorage().getStorageQueue().postRunnable(() -> Utilities.stageQueue.postRunnable(() -> AndroidUtilities.runOnUIThread(() -> {
            CharSequence textFinal = SendMessagesHelper.getTrimmedString(text);
            if (textFinal == null || textFinal.length() == 0) {
                return;
            }
            int limit = account.getMessagesController().getMaxMessageLength();
            int count = (int) Math.ceil(textFinal.length() / (float) limit);
            for (int a = 0; a < count; a++) {
                CharSequence[] part = new CharSequence[] { textFinal.subSequence(a * limit, Math.min((a + 1) * limit, textFinal.length())) };
                ArrayList<TLRPC.MessageEntity> entities = account.getMediaDataController().getEntities(part, true);
                SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(part[0].toString(), dialogId, null, null, null, true, null, null, null, true, 0, 0, null, false);
                params.entities = entities;
                com.dazewell.gram.ghosthold.GhostHoldController.exempt(params);
                account.getSendMessagesHelper().sendMessage(params);
            }
        })));
    }

    static void discard(List<String> paths) {
        for (String path : paths) {
            try {
                new File(path).delete();
            } catch (Throwable ignore) {
            }
        }
    }
}
