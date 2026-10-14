package com.dazewell.gram.scheduledheader;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.view.View;
import android.widget.TextView;

import com.dazewell.gram.hidelastmessage.HideLastMessagePreview;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;

/**
 * The chat's unread count, drawn in the header's title pill while its scheduled view is open. That view never counts
 * as the opened chat, so whatever arrives meanwhile stays unread and would otherwise go unseen. UI thread only; every
 * lookup goes through the fragment's own account, which the header's currentAccount need not match.
 */
public final class ScheduledUnreadPill {

    private ScheduledUnreadPill() {
    }

    /**
     * Turns the header's time-zone pill into the unread pill, once, while the header is built. A header's chat mode
     * never changes, so nothing here is undone.
     */
    public static void install(TextView pill, ChatActivity fragment, Runnable refresh) {
        if (fragment == null || fragment.getChatMode() != ChatActivity.MODE_SCHEDULED) return;
        pill.setOnClickListener(null);
        pill.setClickable(false); // setOnClickListener(null) leaves it clickable, which would still announce a tap
        pill.setPadding(dp(6), dp(2), dp(6), dp(2));
        // The time-zone pill is pinned to the width of "00:00"; a count wants a circle that grows with its digits
        pill.setMinWidth(dp(18));
        pill.setMinHeight(dp(18));

        int account = fragment.getCurrentAccount();
        long dialogId = fragment.getDialogId();
        NotificationCenter.NotificationCenterDelegate observer = (id, a, args) -> {
            if (id == NotificationCenter.updateInterfaces) {
                if ((((Integer) args[0]) & MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE) != 0) refresh.run();
            } else if (id == NotificationCenter.topicsDidLoaded) {
                // Topic counts are written after the read mask posts, so a forum only settles here
                if (args.length > 0 && args[0] instanceof Long && (Long) args[0] == -dialogId) refresh.run();
            } else if (id == NotificationCenter.notificationsSettingsUpdated) {
                refresh.run(); // mute changes the pill colour and posts nothing else
            }
        };
        pill.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                NotificationCenter center = NotificationCenter.getInstance(account);
                center.addObserver(observer, NotificationCenter.updateInterfaces);
                center.addObserver(observer, NotificationCenter.topicsDidLoaded);
                center.addObserver(observer, NotificationCenter.notificationsSettingsUpdated);
                // Covered by another screen, the header detached and heard nothing; the minute tick is too slow to catch up
                refresh.run();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                NotificationCenter center = NotificationCenter.getInstance(account);
                center.removeObserver(observer, NotificationCenter.updateInterfaces);
                center.removeObserver(observer, NotificationCenter.topicsDidLoaded);
                center.removeObserver(observer, NotificationCenter.notificationsSettingsUpdated);
            }
        });
    }

    /**
     * Fills the pill for the current count and returns whether it shows. It runs on every subtitle update and every
     * minute, so it only lays out again when the text actually changes.
     */
    public static boolean apply(TextView pill, ChatActivity fragment) {
        int account = fragment.getCurrentAccount();
        long dialogId = fragment.getDialogId();
        if (dialogId == UserConfig.getInstance(account).getClientUserId()) return false;
        MessagesController controller = MessagesController.getInstance(account);
        long topicId = fragment.getTopicId();
        int unread;
        boolean unreadMark;
        if (topicId != 0 && dialogId < 0) {
            TLRPC.TL_forumTopic topic = controller.getTopicsController().findTopic(-dialogId, topicId);
            unread = topic != null ? topic.unread_count : 0;
            unreadMark = false;
        } else {
            TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            if (dialog == null) return false; // opened before the chat list loaded; the next update fills it in
            // A forum row counts unread topics; the pill counts messages, as its description says
            unread = HideLastMessagePreview.unreadMessages(account, dialogId, dialog.unread_count);
            unreadMark = dialog.unread_mark;
        }
        unread = Math.max(0, unread);
        if (unread == 0 && !unreadMark) return false;

        // Marked unread with nothing to count is the chat list's bare dot
        String text = unread > 99 ? "99+" : unread > 0 ? String.valueOf(unread) : "";
        if (!text.contentEquals(pill.getText())) {
            pill.setText(text);
            pill.requestLayout();
        }
        // Outside the text check: the dot's "" matches the pill's initial text, and "99+" covers many counts.
        // setContentDescription already ignores an equal value.
        pill.setContentDescription(unread > 0 ? LocaleController.formatPluralString("NewMessages", unread) : LocaleController.getString(R.string.UnreadMessages));
        Theme.ResourcesProvider resources = fragment.getResourceProvider();
        int bg = Theme.getColor(controller.isDialogMuted(dialogId, topicId) ? Theme.key_chats_unreadCounterMuted : Theme.key_chats_unreadCounter, resources);
        Drawable current = pill.getBackground();
        if (!(current instanceof ShapeDrawable) || ((ShapeDrawable) current).getPaint().getColor() != bg) {
            pill.setBackground(Theme.createRoundRectDrawable(dp(9), bg));
        }
        int fg = Theme.getColor(Theme.key_chats_unreadCounterText, resources);
        if (pill.getCurrentTextColor() != fg) pill.setTextColor(fg);
        return true;
    }
}
