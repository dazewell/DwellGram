package com.dazewell.gram.videonote;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

/**
 * Whether a memo's recipient is online, and how many messages from them are unread, for the two memos to show. UI thread
 * only. One instance per memo, keyed by its own account, so nothing is shared between accounts.
 */
public class MemoRecipientStatus implements NotificationCenter.NotificationCenterDelegate {

    // A plain status expiry posts no event when it runs out, so online is re-checked on a timer as well
    private static final long RECHECK_MS = 30_000;

    private final int account;
    private final long userId;
    private final Runnable onChange;
    private final Runnable recheck = this::recheck;

    private boolean started;
    private int generation;
    private boolean reading;
    private boolean readAgain;

    public boolean online;
    public int unread;
    public boolean unreadMark;
    public boolean muted;

    public MemoRecipientStatus(int account, long userId, Runnable onChange) {
        this.account = account;
        this.userId = userId;
        this.onChange = onChange;
    }

    /** Saved Messages has neither, and a chat behind its own lock gives nothing away. */
    public static boolean applies(int account, long userId) {
        return userId > 0 && userId != UserConfig.getInstance(account).getClientUserId()
                && !(com.dazewell.gram.chatlock.ChatLockController.isLocked(account, userId)
                && !com.dazewell.gram.chatlock.ChatLockController.isUnlocked(account, userId));
    }

    public void start() {
        if (started || !applies(account, userId)) {
            return;
        }
        started = true;
        reading = false;
        readAgain = false;
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.updateInterfaces);
        refreshOnline();
        readUnread();
        AndroidUtilities.runOnUIThread(recheck, RECHECK_MS);
        onChange.run();
    }

    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        generation++;
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.updateInterfaces);
        AndroidUtilities.cancelRunOnUIThread(recheck);
    }

    public boolean hasUnread() {
        return unread > 0 || unreadMark;
    }

    private void recheck() {
        if (!started) {
            return;
        }
        refreshOnline();
        // Also catches up a count whose storage write landed after the event that announced it
        readUnread();
        AndroidUtilities.runOnUIThread(recheck, RECHECK_MS);
        onChange.run();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (!started || id != NotificationCenter.updateInterfaces || args.length == 0 || !(args[0] instanceof Integer)) {
            return;
        }
        int mask = (Integer) args[0];
        if ((mask & MessagesController.UPDATE_MASK_STATUS) != 0) {
            refreshOnline();
            onChange.run();
        }
        if ((mask & MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE) != 0) {
            readUnread();
        }
    }

    private void refreshOnline() {
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.User user = controller.getUser(userId);
        boolean[] isOnline = new boolean[1];
        if (user != null) {
            LocaleController.formatUserStatus(account, user, isOnline);
        }
        online = isOnline[0];
        muted = controller.isDialogMuted(userId);
    }

    // Read from storage, not dialogs_dict: a memo opened cold from the launcher never loads the chat list. The storage
    // write is committed before the update that announces it is posted, so a read queued from that update sees it.
    private void readUnread() {
        if (reading) {
            readAgain = true;
            return;
        }
        reading = true;
        int gen = generation;
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            int count = 0;
            boolean mark = false;
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized("SELECT unread_count, flags FROM dialogs WHERE did = " + userId);
                if (cursor.next()) {
                    count = Math.max(0, cursor.intValue(0));
                    mark = (cursor.intValue(1) & 1) != 0;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            int readCount = count;
            boolean readMark = mark;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) {
                    return; // stopped meanwhile
                }
                reading = false;
                unread = readCount;
                unreadMark = readMark;
                if (readAgain) {
                    readAgain = false;
                    readUnread();
                }
                onChange.run();
            });
        });
    }
}
