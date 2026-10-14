package com.dazewell.gram.notifprofiles;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.collection.LongSparseArray;
import androidx.core.app.NotificationCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

// NagramX: per-chat notification profiles. Loud is "no override" (today's path, byte-identical), Muted is Telegram's own
// mute (synced to the server, never stored here), Passive sits in the shade only. The assignment is
// one int per chat in the account's notifications prefs, next to an optional schedule and override string (see
// NotificationSchedule), so logout wipes it all, like the cover and Show on Watch state. Keyed by raw dialogId because a notification batch's DialogKey carries whichever topic pushed first.
// Passive posts on one fork-owned channel per account, not per chat: moving a chat between profiles
// only changes which existing channel its notification posts on, so no channel is ever recreated. The channel ids
// are fixed and versioned (never start with "<account>channel", which logout and upstream cleanup delete); a change
// to a preset's channel settings has to bump CHANNEL_VERSION because Android ignores edits to an existing channel.
public final class NotificationProfiles {

    public static final int LOUD = 0;
    // The numeric order is the order the bell cycles and the picker lists them: Loud, Passive, Muted. Muted is
    // Telegram's mute, read live and never stored. Values stored under an earlier numbering are not migrated; they
    // read as whatever the new number means, or as Loud (see clamp). A schedule rule out of range is dropped instead.
    public static final int PASSIVE = 1;
    public static final int MUTED = 2;

    private static final String KEY = "nax_np_";
    // Strings, so they can't collide with the int key above (a dialog id never starts with a letter).
    private static final String SCHEDULE_KEY = "nax_np_s_";
    private static final String OVERRIDE_KEY = "nax_np_o_";
    // "<profile>:<setAtMs>:<untilMs>": a timed pick that outranks the schedule until it runs out.
    private static final String TIMER_KEY = "nax_np_t_";
    public static final long MAX_TIMER_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String CHANNEL_VERSION = "v1";

    private NotificationProfiles() {
    }

    private static SharedPreferences prefs(int account) {
        return MessagesController.getNotificationsSettings(account);
    }

    // For stored values only: Passive is the one profile kept locally, anything else is Loud.
    static int clamp(int value) {
        return value == PASSIVE ? PASSIVE : LOUD;
    }

    // Telegram's own mute for the whole chat, forever or until a time, including the chat-type default being off.
    public static boolean isMuted(int account, long dialogId) {
        return dialogId != 0 && MessagesController.getInstance(account).isDialogMuted(dialogId, 0);
    }

    // When a timed Telegram mute ends, in ms, else 0. notify2 3 is upstream's "muted until notifyuntil" state.
    private static long muteUntil(int account, long dialogId) {
        try {
            String key = NotificationsController.getSharedPrefKey(dialogId, 0);
            SharedPreferences p = prefs(account);
            if (p.getInt("notify2_" + key, -1) != 3) return 0;
            return p.getInt("notifyuntil_" + key, 0) * 1000L;
        } catch (ClassCastException e) {
            return 0;
        }
    }

    // An absent, stale or unparseable value reads as Loud, never throws.
    public static int get(int account, long dialogId) {
        if (dialogId == 0) return LOUD;
        try {
            return clamp(prefs(account).getInt(KEY + dialogId, LOUD));
        } catch (ClassCastException e) {
            return LOUD;
        }
    }

    // Loud removes the key, so a Loud chat stores nothing. On a chat with a schedule the choice is an override instead
    // (profile + the time it was set) and the stored base value is left alone; it lapses at the next schedule boundary.
    // Any explicit pick also ends a running timer, which would otherwise keep outranking it.
    // Muted goes to Telegram and leaves the local profile alone, so it comes back when the chat is unmuted (here or in
    // Telegram's own mute menu). Loud and Passive unmute first.
    public static void set(int account, long dialogId, int profile) {
        if (dialogId == 0) return;
        clearTimer(account, dialogId);
        if (profile == MUTED) {
            NotificationsController.getInstance(account).muteDialog(dialogId, 0, true);
            return;
        }
        unmute(account, dialogId);
        if (rules(account, dialogId).isEmpty()) {
            setBase(account, dialogId, profile);
            return;
        }
        prefs(account).edit().putString(OVERRIDE_KEY + dialogId, clamp(profile) + ":" + System.currentTimeMillis()).apply();
    }

    // Applies the profile until the given time (capped at MAX_TIMER_MS from now), on top of any schedule. The schedule
    // and the stored base are left alone and take over again once the time passes; an earlier manual pick goes too, or
    // it would come back when the timer ends. Expiry is lazy: nothing fires when it passes, effective() just stops
    // returning it. False when the time was already past, so nothing was stored.
    // Muted for a time is Telegram's mute-until, as its own "Mute for..." sets it. A Loud or Passive timer unmutes first
    // and does not mute again when it ends.
    public static boolean setTimer(int account, long dialogId, int profile, long untilMs) {
        if (dialogId == 0) return false;
        long now = System.currentTimeMillis();
        untilMs = Math.min(untilMs, now + MAX_TIMER_MS);
        if (untilMs <= now) return false;
        if (profile == MUTED) {
            clearTimer(account, dialogId);
            NotificationsController.getInstance(account).muteUntil(dialogId, 0, (int) ((untilMs - now) / 1000));
            return true;
        }
        unmute(account, dialogId);
        prefs(account).edit().remove(OVERRIDE_KEY + dialogId).putString(TIMER_KEY + dialogId, clamp(profile) + ":" + now + ":" + untilMs).apply();
        return true;
    }

    public static void clearTimer(int account, long dialogId) {
        if (dialogId == 0) return;
        prefs(account).edit().remove(TIMER_KEY + dialogId).apply();
    }

    // The picker's Cancel timer: a timed Telegram mute is ended by unmuting, our own timer by dropping it.
    public static void cancelTimer(int account, long dialogId) {
        if (dialogId == 0) return;
        if (isMuted(account, dialogId) && muteUntil(account, dialogId) > 0) {
            unmute(account, dialogId);
        } else {
            clearTimer(account, dialogId);
        }
    }

    private static void unmute(int account, long dialogId) {
        if (isMuted(account, dialogId)) {
            NotificationsController.getInstance(account).muteDialog(dialogId, 0, false);
        }
    }

    // When the running timer ends, else 0: Telegram's mute-until while muted, otherwise our own timer.
    public static long timerUntil(int account, long dialogId) {
        if (isMuted(account, dialogId)) return muteUntil(account, dialogId);
        long[] timer = liveTimer(account, dialogId, System.currentTimeMillis());
        return timer == null ? 0 : timer[1];
    }

    // {profile, until} while the timer is in force, else null. A value stamped in the future (clock moved back), already
    // past, longer than the cap or unparseable counts as no timer.
    private static long[] liveTimer(int account, long dialogId, long now) {
        if (dialogId == 0) return null;
        String value;
        try {
            value = prefs(account).getString(TIMER_KEY + dialogId, null);
        } catch (ClassCastException e) {
            return null;
        }
        if (value == null) return null;
        try {
            String[] parts = value.split(":");
            if (parts.length != 3) return null;
            long setAt = Long.parseLong(parts[1]);
            long until = Long.parseLong(parts[2]);
            if (setAt > now || until <= now || until - setAt > MAX_TIMER_MS) return null;
            return new long[]{clamp(Integer.parseInt(parts[0])), until};
        } catch (RuntimeException e) {
            return null;
        }
    }

    // The stored profile itself: all there is on a chat without a schedule, and what applies outside every window on
    // one with a schedule.
    public static void setBase(int account, long dialogId, int profile) {
        if (dialogId == 0) return;
        SharedPreferences.Editor editor = prefs(account).edit();
        profile = clamp(profile);
        if (profile == LOUD) {
            editor.remove(KEY + dialogId);
        } else {
            editor.putInt(KEY + dialogId, profile);
        }
        editor.apply();
    }

    // The chat's schedule rules; absent or unparseable reads as none.
    public static List<NotificationSchedule.Rule> rules(int account, long dialogId) {
        if (dialogId == 0) return new ArrayList<>();
        try {
            return NotificationSchedule.parse(prefs(account).getString(SCHEDULE_KEY + dialogId, null));
        } catch (ClassCastException e) {
            return new ArrayList<>();
        }
    }

    // Saving a schedule means the schedule is in charge now, so the override goes in the same edit.
    public static void setRules(int account, long dialogId, List<NotificationSchedule.Rule> rules) {
        if (dialogId == 0) return;
        SharedPreferences.Editor editor = prefs(account).edit();
        editor.remove(OVERRIDE_KEY + dialogId);
        if (rules.isEmpty()) {
            editor.remove(SCHEDULE_KEY + dialogId);
        } else {
            editor.putString(SCHEDULE_KEY + dialogId, NotificationSchedule.serialize(rules));
        }
        editor.apply();
    }

    // The override's profile while it is still in force, else -1. In force until a boundary passes after it was set;
    // one stamped in the future (clock moved back) or unparseable counts as lapsed.
    private static int liveOverride(int account, long dialogId, List<NotificationSchedule.Rule> rules, long now, TimeZone zone) {
        String value;
        try {
            value = prefs(account).getString(OVERRIDE_KEY + dialogId, null);
        } catch (ClassCastException e) {
            return -1;
        }
        if (value == null) return -1;
        try {
            int colon = value.indexOf(':');
            int profile = Integer.parseInt(value.substring(0, colon));
            long setAt = Long.parseLong(value.substring(colon + 1));
            if (setAt > now || NotificationSchedule.nextBoundaryAfter(rules, setAt, zone) <= now) return -1;
            return clamp(profile);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // What the chat actually does right now: Telegram's mute, else a running timer, else a live override, else the open
    // schedule window, else the stored base. The timer is checked before the schedule so it also works on a chat with no
    // schedule.
    public static int effective(int account, long dialogId) {
        if (isMuted(account, dialogId)) return MUTED;
        long[] timer = liveTimer(account, dialogId, System.currentTimeMillis());
        if (timer != null) return (int) timer[0];
        List<NotificationSchedule.Rule> rules = rules(account, dialogId);
        if (rules.isEmpty()) return get(account, dialogId);
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        int override = liveOverride(account, dialogId, rules, now, zone);
        if (override >= 0) return override;
        int active = NotificationSchedule.activeRule(rules, now, zone);
        return active >= 0 ? clamp(rules.get(active).profile) : get(account, dialogId);
    }

    // True while a schedule window, not the user's own choice, decides the profile.
    public static boolean isScheduleDeciding(int account, long dialogId) {
        if (isMuted(account, dialogId)) return false;
        if (liveTimer(account, dialogId, System.currentTimeMillis()) != null) return false;
        List<NotificationSchedule.Rule> rules = rules(account, dialogId);
        if (rules.isEmpty()) return false;
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        return liveOverride(account, dialogId, rules, now, zone) < 0 && NotificationSchedule.activeRule(rules, now, zone) >= 0;
    }

    public static int labelRes(int profile) {
        switch (profile) {
            case MUTED:
                return R.string.NaxNotifProfileMuted;
            case PASSIVE:
                return R.string.NaxNotifProfilePassive;
            default:
                return R.string.NaxNotifProfileLoud;
        }
    }

    public static int infoRes(int profile) {
        switch (profile) {
            case MUTED:
                return R.string.NaxNotifProfileMutedInfo;
            case PASSIVE:
                return R.string.NaxNotifProfilePassiveInfo;
            default:
                return R.string.NaxNotifProfileLoudInfo;
        }
    }

    // One snapshot per notification rebuild, taken in the preflight: only Passive dialogs are present, so the
    // summary decision and the per-chat children agree even if the user changes a profile mid-rebuild. Covered chats are
    // included; their cover posts through the same apply().
    public static LongSparseArray<Integer> collect(int account, LongSparseArray<ArrayList<MessageObject>> byDialog) {
        LongSparseArray<Integer> result = new LongSparseArray<>();
        if (Build.VERSION.SDK_INT < 26) return result;
        for (int i = 0; i < byDialog.size(); i++) {
            long did = byDialog.keyAt(i);
            // a muted chat that still posts (a mention, say) keeps Telegram's usual handling, so only Passive is collected
            if (effective(account, did) == PASSIVE) {
                result.put(did, PASSIVE);
            }
        }
        return result;
    }

    public static int of(LongSparseArray<Integer> profiles, long dialogId) {
        Integer profile = profiles.get(dialogId);
        return profile == null ? LOUD : profile;
    }

    public static boolean isPassive(LongSparseArray<Integer> profiles, long dialogId) {
        Integer profile = profiles.get(dialogId);
        return profile != null && profile == PASSIVE;
    }

    // Passive chats never join the notification group, so they don't count toward whether a summary is needed.
    public static int passiveCount(LongSparseArray<Integer> profiles) {
        int count = 0;
        for (int i = 0; i < profiles.size(); i++) {
            Integer profile = profiles.valueAt(i);
            if (profile != null && profile == PASSIVE) count++;
        }
        return count;
    }

    // Called after the user picks a profile: rebuild so an already-posted notification moves to its new channel now
    // (the rebuild is not "notify about last", so nothing alerts), and drop popups already queued for a Passive chat.
    public static void onChanged(int account, long dialogId) {
        NotificationsController controller = NotificationsController.getInstance(account);
        if (effective(account, dialogId) == PASSIVE) {
            AndroidUtilities.runOnUIThread(() -> {
                boolean changed = false;
                for (int i = controller.popupMessages.size() - 1; i >= 0; i--) {
                    MessageObject popup = controller.popupMessages.get(i);
                    if (popup != null && popup.getDialogId() == dialogId) {
                        controller.popupMessages.remove(i);
                        changed = true;
                    }
                }
                if (changed) {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pushMessagesUpdated);
                }
            });
        }
        controller.showNotifications();
    }

    // Passive chats never open the in-app popup.
    public static boolean blocksPopup(int account, long dialogId) {
        return dialogId != 0 && effective(account, dialogId) == PASSIVE;
    }

    // Reroutes one per-chat notification onto its profile's channel; only Passive has one now. alert and isInApp went
    // unused with Quiet but stay, so the two hooks in NotificationsController and the cover don't move.
    public static void apply(int account, NotificationCompat.Builder builder, Integer profile, boolean alert, boolean isInApp) {
        if (profile == null || Build.VERSION.SDK_INT < 26) return;
        try {
            if (profile == PASSIVE) {
                builder.setChannelId(passiveChannel(account));
                builder.setGroup("nax_np_passive_" + account);
                builder.setVisibility(NotificationCompat.VISIBILITY_SECRET);
                builder.setPriority(NotificationCompat.PRIORITY_MIN);
                builder.setOnlyAlertOnce(true);
                builder.setLocalOnly(true);
            }
        } catch (Exception e) {
            FileLog.e("nax notification profile apply failed", e);
        }
    }

    private static String channelId(int account, String tier) {
        return "nax_np_" + CHANNEL_VERSION + "_" + account + "_" + tier;
    }

    private static String channelName(int account, String label) {
        if (UserConfig.getActivatedAccountsCount() > 1) {
            String name = UserObject.getFirstName(UserConfig.getInstance(account).getCurrentUser());
            if (name != null && name.length() > 0) {
                return label + " · " + name;
            }
        }
        return label;
    }

    private static String passiveChannel(int account) {
        String id = channelId(account, "passive");
        ensureChannel(id, channelName(account, LocaleController.getString(R.string.NaxNotifProfilePassive)), NotificationManager.IMPORTANCE_MIN, false, true);
        return id;
    }

    private static void ensureChannel(String id, String name, int importance, boolean vibrate, boolean secret) {
        NotificationManager manager = (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(id) != null) return;
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        channel.enableLights(false);
        channel.enableVibration(vibrate);
        channel.setSound(null, null);
        if (secret) {
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        }
        try {
            manager.createNotificationChannel(channel);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
