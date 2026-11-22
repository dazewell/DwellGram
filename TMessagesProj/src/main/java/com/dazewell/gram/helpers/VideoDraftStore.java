package com.dazewell.gram.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AutoDeleteMediaTask;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;

import java.io.File;
import java.util.Map;

// NagramX (#video-draft-guard): durable record of a finished-but-unsent round-video draft, so a preview the
// user recorded survives the process boundary a passcode-lock-Immediately rebuild forces (it tears the chat
// composer down; the old in-memory preview is dropped and the mp4 is orphaned on disk). One record per
// (dialog, topic) slot, keyed by a minted draft id so persist / restore / clear all match the exact draft
// rather than the slot. The file is kept sweepable by 24h expiry and reference-locked while a live record
// points at it; no path here ever deletes a file except an explicit user discard and the expiry sweep --
// orphaning is always preferred to losing footage.
public final class VideoDraftStore {

    private VideoDraftStore() {
    }

    private static final String PREFIX = "roundvideodrafts_";
    private static final long EXPIRY_MS = 24L * 60 * 60 * 1000;

    // The stored draft. id, trim (startTime/endTime as absolute ms, matching the timeline delegate and
    // send(4)) and duration cross the process boundary so a restore reproduces the exact cut and a
    // clear/supersede matches by identity. createdAt drives the 24h expiry and is deliberately NOT part of
    // content equality below, so re-persisting an unchanged draft (a restore re-admission, a drag that ended
    // where it began) is a true no-op instead of resetting the age. size is intentionally NOT stored -- it is
    // derived from file.length() at restore/adopt, authoritative and immutable once the file is finalized.
    public static final class Entry {
        public final long id;
        public final String path;
        public final int duration;
        public final long startTime;
        public final long endTime;
        public final boolean voiceOnce;
        public final boolean spoiler;
        public final boolean brandedCapture;
        public final long createdAt;

        public Entry(long id, String path, int duration, long startTime, long endTime, boolean voiceOnce, long createdAt, boolean spoiler, boolean brandedCapture) {
            this.id = id;
            this.path = path;
            this.duration = duration;
            this.startTime = startTime;
            this.endTime = endTime;
            this.voiceOnce = voiceOnce && !spoiler;
            this.spoiler = spoiler;
            this.brandedCapture = brandedCapture;
            this.createdAt = createdAt;
        }

        // Everything that defines the draft EXCEPT createdAt. Two entries with equal content but different
        // createdAt are "the same draft, unchanged" -- the CAS treats that as a no-op write.
        boolean contentEquals(Entry o) {
            return o != null && id == o.id && duration == o.duration && startTime == o.startTime
                    && endTime == o.endTime && voiceOnce == o.voiceOnce && spoiler == o.spoiler && brandedCapture == o.brandedCapture
                    && (path == null ? o.path == null : path.equals(o.path));
        }

        @NonNull
        @Override
        public String toString() {
            return "@" + id + "\n" + path + "\n" + duration + "\n" + startTime + "\n" + endTime
                    + "\n" + (voiceOnce ? 1 : 0) + "\n" + createdAt + "\n" + (spoiler ? 1 : 0) + "\n" + (brandedCapture ? 1 : 0);
        }

        // Tolerant, mirroring MediaDataController.DraftVoice.fromString: an older or malformed record degrades
        // to "nothing restores", never an exception on chat open. A missing / unparseable / zero id yields
        // null rather than a default id -- a default would let an id-match clear or supersede fire on the
        // wrong draft after a cold start.
        static Entry fromString(String s) {
            try {
                if (s == null || !s.startsWith("@")) {
                    return null;
                }
                String[] p = s.substring(1).split("\n", -1);
                if (p.length < 7) {
                    return null;
                }
                long id = Long.parseLong(p[0]);
                String path = p[1];
                if (id == 0 || path.isEmpty()) {
                    return null;
                }
                return new Entry(id, path, Integer.parseInt(p[2]), Long.parseLong(p[3]),
                        Long.parseLong(p[4]), Integer.parseInt(p[5]) != 0, Long.parseLong(p[6]),
                        p.length > 7 && "1".equals(p[7]), p.length > 8 && "1".equals(p[8]));
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFIX + account, Context.MODE_PRIVATE);
    }

    // dialogId and topicId are both 64-bit; joined losslessly with a separator that can't appear in either,
    // so two distinct (dialog, topic) pairs never collide onto one slot. NOT Objects.hash -- that folds 128
    // bits to 32 and a collision would surface one chat's draft in another, or supersede another chat's record.
    private static String key(long dialogId, long topicId) {
        return dialogId + "_" + topicId;
    }

    // 0 is the "no id" sentinel used by fromString and by VideoEditedInfo's default field, so never mint it.
    public static long newId() {
        long id = Utilities.random.nextLong();
        return id == 0 ? 1 : id;
    }

    // Create or supersede. Compare-and-set on (id, content):
    //   - slot empty            -> write + lockFile
    //   - same id, same content -> no-op (a duplicate / leaked admission writes nothing)
    //   - same id, diff content -> trim update: rewrite, same file, no lock change
    //   - different id          -> a newer recording supersedes: rewrite + lock new + unlock the old file, so the
    //                              orphaned old file stays sweepable. Unlock is not a delete; this path removes
    //                              no file -- supersede-delete is deferred to the 24h expiry.
    public static void save(int account, long dialogId, long topicId, long id, String path, int duration, long startTime, long endTime, boolean voiceOnce, boolean spoiler, boolean brandedCapture) {
        if (path == null || id == 0) {
            return;
        }
        String k = key(dialogId, topicId);
        SharedPreferences p = prefs(account);
        Entry cur = Entry.fromString(p.getString(k, null));
        // NagramX (#video-draft-guard): a same-id trim update must keep the ORIGINAL createdAt so the 24h expiry
        // tracks recording time, not the last edit -- otherwise trimming a draft repeatedly could keep it alive
        // past the day the FEATURES copy promises. Only a genuinely new / superseding record starts the clock.
        long createdAt = (cur != null && cur.id == id) ? cur.createdAt : System.currentTimeMillis();
        Entry next = new Entry(id, path, duration, startTime, endTime, voiceOnce, createdAt, spoiler, brandedCapture);
        if (cur != null && cur.id == id) {
            if (cur.contentEquals(next)) {
                return;
            }
            // NagramX (#video-draft-guard): commit(), not apply(), on the record-WRITE paths. We persist from
            // onPause (the live finalize is a send(3)) and at drag-end, AFTER the framework's pause/stop flush
            // barrier has already run -- an apply() write would sit queued while backgrounded and be lost to a
            // process kill in exactly the window this feature exists for. A synchronous ~1-5ms write to a small
            // per-account prefs file is the accepted cost; it will trip StrictMode in debug, which is fine.
            p.edit().putString(k, next.toString()).commit();
            return;
        }
        boolean written = p.edit().putString(k, next.toString()).commit();
        // NagramX (#video-draft-guard): locked unconditionally on purpose. On a failed write this clip is still the
        // one live in the preview, so gating the lock on the write would leave it sweepable exactly when disk
        // pressure made that write fail. A failed-write orphan (locked but untracked) clears on process restart.
        AutoDeleteMediaTask.lockFile(path);
        // NagramX (#video-draft-guard): only unlock the superseded file, and only once the superseding write has
        // actually landed. If the write failed, prefs still point at the old record, so unlocking its file would let
        // the sweep delete the clip the store still references -- and a failed commit() and an aggressive sweep both
        // happen under disk pressure, so they correlate rather than being independently rare. On failure we keep the
        // old file locked (an extra orphan is the accepted over-retention direction) and leave the record restorable.
        // The path check stays too: never unlock a path we just locked, in case a new recording reused it.
        if (written && cur != null && !cur.path.equals(path)) {
            AutoDeleteMediaTask.unlockFile(cur.path);
        }
    }

    // Update-only trim re-persist. Never creates, never supersedes: writes only when a record with THIS
    // id already occupies the slot and its content differs. A slot that is empty (persist mode-gated off) or
    // holds a different id (already superseded) is a no-op, so this trigger needs no separate mode gate.
    public static void updateTrim(int account, long dialogId, long topicId, long id, String path, int duration, long startTime, long endTime, boolean voiceOnce, boolean spoiler, boolean brandedCapture) {
        if (path == null || id == 0) {
            return;
        }
        String k = key(dialogId, topicId);
        SharedPreferences p = prefs(account);
        Entry cur = Entry.fromString(p.getString(k, null));
        if (cur == null || cur.id != id) {
            return;
        }
        // NagramX (#video-draft-guard): keep the original createdAt (same reason as save's same-id branch) so a
        // trim never restarts the expiry clock.
        Entry next = new Entry(id, path, duration, startTime, endTime, voiceOnce, cur.createdAt, spoiler, brandedCapture);
        if (cur.contentEquals(next)) {
            return;
        }
        // NagramX (#video-draft-guard): commit(), not apply() -- this trim re-persist runs from onPause
        // (persistVideoTrimIfBound), after the pause/stop flush barrier, same durability reasoning as save's
        // write paths. A queued apply() here would be lost to a kill while backgrounded.
        p.edit().putString(k, next.toString()).commit();
    }

    // Self-validating read: a record whose id didn't parse, whose file is gone/empty, or older than 24h is
    // dropped on read (its file unlocked) so a caller never restores a preview backed by a missing or stale
    // file, and a present-but-unparseable value can't sit in prefs forever.
    public static Entry load(int account, long dialogId, long topicId) {
        String k = key(dialogId, topicId);
        SharedPreferences p = prefs(account);
        Entry e = Entry.fromString(p.getString(k, null));
        if (e == null) {
            if (p.contains(k)) {
                p.edit().remove(k).apply();
            }
            return null;
        }
        File f = new File(e.path);
        if (System.currentTimeMillis() - e.createdAt > EXPIRY_MS || !f.exists() || f.length() == 0) {
            AutoDeleteMediaTask.unlockFile(e.path);
            p.edit().remove(k).apply();
            return null;
        }
        return e;
    }

    // A send consumed the draft: drop the record only if THIS id still owns the slot (a stale send must not
    // clear a newer draft), and do NOT unlock/delete the file -- the outgoing-message machinery owns it now.
    public static void clearOnSend(int account, long dialogId, long topicId, long id) {
        removeIfId(account, dialogId, topicId, id, false);
    }

    // The user discarded the preview: drop the record (if this id still owns it), unlock and delete the file.
    // cancel() can't be relied on to delete it -- on a rebuilt InstantCameraView it bails at its
    // textureView == null guard before reaching its own delete.
    public static void discard(int account, long dialogId, long topicId, long id) {
        removeIfId(account, dialogId, topicId, id, true);
    }

    // 24h sweep of one account's records, run on chat open. Unlocks + deletes each expired file so an expired
    // draft (or one whose file already vanished) doesn't leave a permanently locked, invisible orphan.
    public static void sweepExpired(int account) {
        SharedPreferences p = prefs(account);
        Map<String, ?> all = p.getAll();
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> en : all.entrySet()) {
            Object v = en.getValue();
            Entry e = Entry.fromString(v instanceof String ? (String) v : null);
            if (e != null && now - e.createdAt <= EXPIRY_MS) {
                continue;
            }
            if (e != null) {
                unlockAndDelete(e.path);
            }
            if (editor == null) {
                editor = p.edit();
            }
            editor.remove(en.getKey());
        }
        if (editor != null) {
            editor.apply();
        }
    }

    private static void removeIfId(int account, long dialogId, long topicId, long id, boolean unlock) {
        if (id == 0) {
            return;
        }
        String k = key(dialogId, topicId);
        SharedPreferences p = prefs(account);
        Entry cur = Entry.fromString(p.getString(k, null));
        if (cur == null || cur.id != id) {
            return;
        }
        if (unlock) {
            unlockAndDelete(cur.path);
        }
        p.edit().remove(k).apply();
    }

    private static void unlockAndDelete(String path) {
        if (path == null) {
            return;
        }
        try {
            AutoDeleteMediaTask.unlockFile(path);
            new File(path).delete();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // The round-video VideoEditedInfo, single-sourced for the InstantCameraView send path (adopt) and the
    // enter-view preview (setVideoDraft) so the two can't drift. Mirrors what handleStopRecording builds for a
    // live finalize (roundVideo, 360x360, framerate 25) but restores the persisted TRIM (start/end absolute
    // ms) and the carried id, so a restored draft sends the same cut the user made, not the whole clip.
    // file/key/iv stay null -- the normal "upload not started yet" state; the send path re-uploads from disk.
    public static VideoEditedInfo buildInfo(Entry e, long size) {
        VideoEditedInfo info = new VideoEditedInfo();
        info.roundVideo = true;
        info.naxDraftSpoiler = e.spoiler;
        info.naxBrandedCapture = e.brandedCapture;
        info.naxDraftId = e.id;
        info.startTime = e.startTime;
        info.endTime = e.endTime;
        info.estimatedSize = Math.max(1, size);
        info.framerate = 25;
        info.resultWidth = info.originalWidth = 360;
        info.resultHeight = info.originalHeight = 360;
        info.originalPath = e.path;
        info.estimatedDuration = e.duration;
        return info;
    }
}
