package com.dazewell.gram.schedulecountdown;

import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.Components.BulletinFactory;

/**
 * After something is scheduled, a bulletin saying how long until it sends. An album, a multi-pick or a forwarded set
 * reaches the send funnel once per message (media from its own queue, with gaps while each file is prepared), so calls
 * are collected per account and one bulletin counts to the earliest of them.
 *
 * The funnel calls in from any thread; all state below is only touched on the UI thread.
 */
public final class ScheduleCountdown {

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

    public static void onScheduled(int account, int scheduleDate, int count) {
        if (scheduleDate <= 0 || scheduleDate >= FIRST_SENTINEL || count <= 0) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> collect(account, scheduleDate, count));
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
        BulletinFactory.global().createSimpleBulletinWithIconSize(R.raw.timer_toast, text, 24).show();
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
