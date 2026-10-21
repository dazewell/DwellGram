package com.dazewell.gram.schedulecountdown;

import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import com.dazewell.gram.bulletinstack.BulletinStack;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * After something is scheduled, a bulletin saying how long until it sends, counted to the date the server stored.
 * A new send is armed by its local id at the insert and counted when the server's reply for that id comes back; an
 * album, a multi-pick or a forwarded set confirms once per message, so confirmations are collected per account and
 * one bulletin counts to the earliest of them.
 *
 * Arming and confirming come in from any thread under the {@code armed} lock; everything else is UI-thread only.
 */
public final class ScheduleCountdown {

    private static final String TAG = "ScheduleCountdown";
    // An armed send never confirmed (it failed, or was cancelled) is dropped after this; a slow upload has to fit inside.
    private static final long ARM_TTL_MS = 6 * 60 * 60 * 1000L;
    // (account, local id) -> when it was armed. Local ids are never reused, except by a resend of that same message.
    private static final HashMap<Long, Long> armed = new HashMap<>();
    private static final long SETTLE_MS = 600;
    // A burst whose media preparation outlasts SETTLE_MS shows again when it settles; skip that if nothing earlier came in.
    private static final long REPEAT_WINDOW_MS = 5000;
    // 0x7FFFFFFE is "send when online"; from 0x7FFFFFFD up are fork sentinels for held rows. Neither is a date.
    private static final int FIRST_SENTINEL = 0x7FFFFFFD;

    private static final SparseArray<Pending> pending = new SparseArray<>();
    private static final SparseArray<Shown> shown = new SparseArray<>();

    private static final class Pending {
        int earliest;
        int count;
        Runnable show;
    }

    private static final class Shown {
        int earliest;
        int count;
        long at;
    }

    private ScheduleCountdown() {
    }

    /** A new scheduled send was inserted locally. Nothing shows until {@link #confirmed} brings the server's date. */
    public static void arm(int account, int scheduleDate, int localId) {
        if (scheduleDate <= 0 || scheduleDate >= FIRST_SENTINEL) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (armed) {
            Iterator<Long> it = armed.values().iterator();
            while (it.hasNext()) {
                if (now - it.next() > ARM_TTL_MS) it.remove();
            }
            armed.put(key(account, localId), now);
        }
    }

    public static void arm(int account, int scheduleDate, ArrayList<MessageObject> messages) {
        for (int i = 0; i < messages.size(); i++) {
            arm(account, scheduleDate, messages.get(i).getId());
        }
    }

    /**
     * The server's reply for local id {@code localId}. {@code serverDate} is the scheduled message's date as the server
     * stored it, or 0 when the reply says it was not scheduled after all (sent straight away), which only disarms.
     */
    public static void confirmed(int account, int localId, int serverDate) {
        Long armedAt;
        synchronized (armed) {
            armedAt = armed.remove(key(account, localId));
        }
        if (armedAt != null) {
            onScheduled(account, serverDate, 1);
        }
    }

    private static long key(int account, int localId) {
        return ((long) account << 32) | (localId & 0xFFFFFFFFL);
    }

    private static void onScheduled(int account, int scheduleDate, int count) {
        if (scheduleDate <= 0 || scheduleDate >= FIRST_SENTINEL || count <= 0) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> collect(account, scheduleDate, count));
    }

    /**
     * A reschedule the server accepted. Counts to the date its reply carries for {@code messageId} rather than the one
     * we asked for, so the bulletin can only show what the server stored. Reads the reply before processUpdates gets it.
     */
    public static void onRescheduled(int account, int messageId, TLObject response) {
        int date = 0;
        if (response instanceof TLRPC.Updates) {
            TLRPC.Updates updates = (TLRPC.Updates) response;
            date = serverDate(updates.update, messageId);
            if (updates.updates != null) {
                for (int i = 0; date == 0 && i < updates.updates.size(); i++) {
                    date = serverDate(updates.updates.get(i), messageId);
                }
            }
        }
        if (date == 0) {
            Log.w(TAG, "reschedule reply had no date for the edited message: " + (response == null ? "null" : response.getClass().getSimpleName()));
            return;
        }
        onScheduled(account, date, 1);
    }

    /**
     * A reschedule the server refused as not modified. Only the date (and repeat period) was sent, so "not modified" is
     * the server saying it already stores {@code scheduleDate}; counting to it is still counting to the server's date.
     */
    public static void onRescheduleUnchanged(int account, int scheduleDate) {
        onScheduled(account, scheduleDate, 1);
    }

    private static int serverDate(TLRPC.Update update, int messageId) {
        TLRPC.Message message = null;
        // A scheduled edit comes back as a new scheduled message; the edit types are accepted in case that changes.
        if (update instanceof TL_update.TL_updateNewScheduledMessage) {
            message = ((TL_update.TL_updateNewScheduledMessage) update).message;
        } else if (update instanceof TL_update.TL_updateEditMessage) {
            message = ((TL_update.TL_updateEditMessage) update).message;
        } else if (update instanceof TL_update.TL_updateEditChannelMessage) {
            message = ((TL_update.TL_updateEditChannelMessage) update).message;
        }
        return message != null && message.id == messageId ? message.date : 0;
    }

    /** "2 hours and 5 minutes" until a date the caller already confirmed with the server, or null when it isn't a date. */
    public static String describeIn(int account, int date) {
        if (date <= 0 || date >= FIRST_SENTINEL) {
            return null;
        }
        return formatIn(date - ConnectionsManager.getInstance(account).getCurrentTime());
    }

    private static void collect(int account, int scheduleDate, int count) {
        Pending p = pending.get(account);
        if (p == null) {
            p = new Pending();
            p.earliest = scheduleDate;
            p.show = () -> show(account);
            pending.put(account, p);
        } else {
            p.earliest = Math.min(p.earliest, scheduleDate);
            AndroidUtilities.cancelRunOnUIThread(p.show);
        }
        p.count += count;
        AndroidUtilities.runOnUIThread(p.show, SETTLE_MS);
    }

    private static void show(int account) {
        Pending p = pending.get(account);
        pending.remove(account);
        if (p == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Shown last = shown.get(account);
        boolean sameBurst = last != null && now - last.at < REPEAT_WINDOW_MS;
        if (sameBurst && p.earliest >= last.earliest) {
            // Slide the window, so a slow album stays one bulletin however long it takes.
            last.at = now;
            last.count += p.count;
            return;
        }
        // The bulletin is drawn over whatever screen is up, which belongs to the selected account.
        if (account != UserConfig.selectedAccount) {
            return;
        }
        if (last == null) {
            last = new Shown();
            shown.put(account, last);
        }
        last.count = (sameBurst ? last.count : 0) + p.count;
        last.earliest = p.earliest;
        last.at = now;

        int seconds = p.earliest - ConnectionsManager.getInstance(account).getCurrentTime();
        String in = formatIn(seconds);
        String text = last.count > 1
                ? LocaleController.formatString(R.string.ScheduleCountdownFirst, in)
                : LocaleController.formatString(R.string.ScheduleCountdownOne, in);
        // kept, so a Stash bar (or another kept bar) and this one stack rather than push each other off
        BulletinStack.keep(BulletinFactory.global().createSimpleBulletinWithIconSize(R.raw.timer_toast, text, 24)).show();
    }

    /** "3 days, 11 hours and 2 minutes", to the nearest minute, leaving out units that are zero. */
    static String formatIn(int seconds) {
        int minutes = Math.round(seconds / 60f);
        if (minutes < 1) {
            return LocaleController.getString(R.string.ScheduleCountdownUnderMinute);
        }
        int days = minutes / (24 * 60);
        int hours = minutes / 60 % 24;
        int mins = minutes % 60;
        String[] parts = new String[3];
        int n = 0;
        if (days > 0) parts[n++] = LocaleController.formatPluralString("Days", days);
        if (hours > 0) parts[n++] = LocaleController.formatPluralString("Hours", hours);
        if (mins > 0) parts[n++] = LocaleController.formatPluralString("Minutes", mins);
        if (n == 1) {
            return parts[0];
        } else if (n == 2) {
            return LocaleController.formatString(R.string.ScheduleCountdownJoin2, parts[0], parts[1]);
        }
        return LocaleController.formatString(R.string.ScheduleCountdownJoin3, parts[0], parts[1], parts[2]);
    }
}
