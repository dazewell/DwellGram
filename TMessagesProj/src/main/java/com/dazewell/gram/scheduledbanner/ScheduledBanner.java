package com.dazewell.gram.scheduledbanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dazewell.gram.ghosthold.GhostHoldController;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ChatActivityTopPanelLayout;

import java.util.ArrayList;
import java.util.Locale;

/** One open chat owns the row, its callbacks and its storage-query generation. */
public final class ScheduledBanner {
    private static final int FIRST_SENTINEL = 0x7FFFFFFD;
    private final int account;
    private final long dialogId;
    private long topicId;
    private final ChatActivityTopPanelLayout panel;
    private final Theme.ResourcesProvider resourcesProvider;
    private final LinearLayout row;
    private final ImageView icon;
    private final TextView text;
    private final ArrayList<Integer> dates = new ArrayList<>();
    private boolean resumed;
    private int generation;
    private int iconColor;
    private boolean iconColorSet;
    private final Runnable tick = this::render;
    private Query query;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingListener = (preferences, key) -> {
        if (xyz.nextalone.nagram.NaConfig.INSTANCE.getScheduledMessageBanner().key.equals(key)) {
            AndroidUtilities.runOnUIThread(() -> {
                if (resumed) refresh();
            });
        }
    };

    private static final class Query {
        volatile boolean cancelled;
        Runnable runnable;
    }

    public ScheduledBanner(Context context, ChatActivityTopPanelLayout panel, int account, long dialogId,
                           long topicId, Theme.ResourcesProvider resourcesProvider, Runnable open) {
        this.account = account;
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.panel = panel;
        this.resourcesProvider = resourcesProvider;
        row = new LinearLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                int color = Theme.getColor(Theme.key_chat_topPanelTitle, ScheduledBanner.this.resourcesProvider);
                if (text.getCurrentTextColor() != color) text.setTextColor(color);
                if (!iconColorSet || iconColor != color) {
                    iconColor = color;
                    iconColorSet = true;
                    icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                }
                super.dispatchDraw(canvas);
            }
            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName("android.widget.Button");
            }
        };
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(2), AndroidUtilities.dp(16), AndroidUtilities.dp(2));
        row.setMinimumHeight(AndroidUtilities.dp(32));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 1));
        row.setOnClickListener(v -> open.run());
        row.setFocusable(true);
        icon = new ImageView(context);
        icon.setImageResource(R.drawable.baseline_schedule_24);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(AndroidUtilities.dp(20), AndroidUtilities.dp(20));
        row.addView(icon, iconParams);
        text = new TextView(context);
        text.setTextSize(14);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMarginStart(AndroidUtilities.dp(12));
        row.addView(text, textParams);
        panel.addView(row, 0, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.setPriority(row, 6);
    }

    public void setTopic(long topicId) {
        if (this.topicId == topicId) return;
        this.topicId = topicId;
        clear();
        refresh();
    }

    public void resume() {
        if (!resumed) {
            resumed = true;
            tw.nekomimi.nekogram.NekoConfig.getPreferences().registerOnSharedPreferenceChangeListener(settingListener);
        }
        refresh();
    }

    public void pause() {
        if (resumed) {
            resumed = false;
            tw.nekomimi.nekogram.NekoConfig.getPreferences().unregisterOnSharedPreferenceChangeListener(settingListener);
        }
        cancelWork();
    }

    private boolean enabled() {
        return xyz.nextalone.nagram.NaConfig.INSTANCE.getScheduledMessageBanner().Bool();
    }

    private void cancelWork() {
        generation++;
        AndroidUtilities.cancelRunOnUIThread(tick);
        if (query != null) {
            query.cancelled = true;
            MessagesStorage.getInstance(account).getStorageQueue().cancelRunnable(query.runnable);
            query = null;
        }
    }

    private void clear() {
        cancelWork();
        dates.clear();
        panel.setViewVisible(row, false, false);
    }

    public void destroy() {
        pause();
        panel.removeView(row);
    }

    /** Called on the UI thread after a matching scheduled update, including the post-commit update. */
    public void refresh() {
        if (!enabled()) {
            clear();
            return;
        }
        if (!resumed) return;
        cancelWork();
        final int queryGeneration = generation;
        final long queryTopicId = topicId;
        MessagesStorage storage = MessagesStorage.getInstance(account);
        Query currentQuery = new Query();
        query = currentQuery;
        currentQuery.runnable = () -> {
            if (currentQuery.cancelled || !enabled()) return;
            ArrayList<Integer> found = new ArrayList<>();
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                        "SELECT data, mid, date FROM scheduled_messages_v2 WHERE uid = %d AND (mid >= 0 OR send_state != 2) ORDER BY date ASC", dialogId));
                while (!currentQuery.cancelled && enabled() && cursor.next()) {
                    int date = cursor.intValue(2);
                    if (date <= 0 || date >= FIRST_SENTINEL) continue;
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) continue;
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) message.readAttachPath(data, UserConfig.getInstance(account).clientUserId);
                    data.reuse();
                    if (message == null || GhostHoldController.isHeldMessage(message)) continue;
                    message.id = cursor.intValue(1);
                    message.date = date;
                    message.dialog_id = dialogId;
                    // This is the same predicate the scheduled list uses in ForumUtilities.
                    if (queryTopicId != 0 && MessageObject.getTopicId(account, message, true) != queryTopicId) continue;
                    found.add(date);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) cursor.dispose();
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!resumed || queryGeneration != generation || currentQuery.cancelled) return;
                if (!enabled()) {
                    clear();
                    return;
                }
                query = null;
                dates.clear();
                dates.addAll(found);
                render();
            });
        };
        storage.getStorageQueue().postRunnable(currentQuery.runnable);
    }

    private void render() {
        AndroidUtilities.cancelRunOnUIThread(tick);
        if (!enabled()) {
            clear();
            return;
        }
        if (!resumed) return;
        long now = ConnectionsManager.getInstance(account).getCurrentTimeMillis();
        while (!dates.isEmpty() && dates.get(0) * 1000L <= now) dates.remove(0);
        if (dates.isEmpty()) {
            panel.setViewVisible(row, false);
            return;
        }
        long remaining = dates.get(0) * 1000L - now;
        if (remaining > 300000) {
            panel.setViewVisible(row, false);
            AndroidUtilities.runOnUIThread(tick, remaining - 300000);
            return;
        }
        int count = 0;
        long nextEntryDelay = Long.MAX_VALUE;
        for (int date : dates) {
            long until = date * 1000L - now;
            if (until <= 300000) {
                count++;
            } else {
                nextEntryDelay = until - 300000;
                break;
            }
        }
        String next;
        long delay;
        if (remaining < 60000) {
            next = LocaleController.getString(R.string.ScheduleCountdownUnderMinute);
            delay = remaining;
        } else {
            int minutes = (int) ((remaining + 59999) / 60000);
            next = LocaleController.formatPluralString("Minutes", minutes);
            delay = remaining - (minutes - 1L) * 60000;
            if (minutes == 1) delay = 1;
        }
        delay = Math.min(delay, nextEntryDelay);
        String label = LocaleController.formatPluralString("ScheduledBannerCount", count, next);
        text.setText(label);
        row.setContentDescription(LocaleController.formatString(R.string.ScheduledBannerAccessibility, label));
        panel.setViewVisible(row, true);
        AndroidUtilities.runOnUIThread(tick, Math.max(1, delay));
    }
}
