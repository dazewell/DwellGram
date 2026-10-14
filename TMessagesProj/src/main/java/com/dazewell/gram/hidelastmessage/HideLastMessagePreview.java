package com.dazewell.gram.hidelastmessage;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ChatAvatarContainer;

import java.util.ArrayList;

/**
 * Renders the chat-list text for a dialog hidden by {@link HideLastMessageController},
 * according to its placeholder mode. Main thread only (called from
 * {@code DialogCell.buildLayout}); every lookup is a cache read on the cell's own account.
 */
public final class HideLastMessagePreview {

    private HideLastMessagePreview() {}

    /** Mode labels, indexed by the {@code HideLastMessageController.MODE_*} constants. */
    public static final int[] MODE_LABELS = {
            R.string.HideLastMessageModeText,
            R.string.HideLastMessageModeStatus,
            R.string.HideLastMessageModeUnread,
            R.string.HideLastMessageModeType,
    };

    /** One-line explanations shown under each mode in the picker. */
    public static final int[] MODE_DESCRIPTIONS = {
            R.string.HideLastMessageModeTextInfo,
            R.string.HideLastMessageModeStatusInfo,
            R.string.HideLastMessageModeUnreadInfo,
            R.string.HideLastMessageModeTypeInfo,
    };

    /** The value shown on the Chat privacy sheet's placeholder row. */
    @NonNull
    public static String describe(int account, long dialogId) {
        int mode = HideLastMessageController.getMode(account, dialogId);
        if (mode == HideLastMessageController.MODE_TEXT) {
            return HideLastMessageController.getPlaceholder(account, dialogId);
        }
        return LocaleController.getString(MODE_LABELS[mode]);
    }

    /** The text that replaces the last-message preview of a hidden dialog. */
    @NonNull
    public static CharSequence resolve(int account, long dialogId, @Nullable MessageObject message, int unreadCount) {
        CharSequence text = null;
        try {
            switch (HideLastMessageController.getMode(account, dialogId)) {
                case HideLastMessageController.MODE_STATUS:
                    text = status(account, dialogId);
                    break;
                case HideLastMessageController.MODE_UNREAD:
                    int unread = unreadMessages(account, dialogId, unreadCount);
                    text = unread > 0
                            ? LocaleController.formatPluralString("NewMessages", unread)
                            : LocaleController.getString(R.string.HideLastMessageNoUnread);
                    break;
                case HideLastMessageController.MODE_TYPE:
                    text = type(message);
                    break;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return TextUtils.isEmpty(text) ? HideLastMessageController.getPlaceholder(account, dialogId) : text;
    }

    /**
     * Folded into DialogCell's redraw key, which otherwise skips buildLayout unless the
     * message, read state or draft changed. 0 when the dialog is not hidden.
     */
    public static int drawnHash(int account, long dialogId, @Nullable MessageObject message, int unreadCount) {
        if (!HideLastMessageController.isHidden(account, dialogId)) return 0;
        return resolve(account, dialogId, message, unreadCount).toString().hashCode();
    }

    /**
     * Unread messages for the preview. A forum (or managed monoforum) row's own count is
     * unread topics (DialogCell's badge, via TopicsController.getForumUnreadCount), so
     * total the topics' messages instead.
     */
    public static int unreadMessages(int account, long dialogId, int cellUnreadCount) {
        if (dialogId >= 0 || DialogObject.isEncryptedDialog(dialogId)) return cellUnreadCount;
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Chat chat = controller.getChat(-dialogId);
        if (chat == null || !(chat.forum || chat.monoforum && ChatObject.canManageMonoForum(account, chat))) {
            return cellUnreadCount;
        }
        ArrayList<TLRPC.TL_forumTopic> topics = controller.getTopicsController().getTopics(chat.id);
        if (topics == null) return cellUnreadCount;
        int total = 0;
        for (int i = 0; i < topics.size(); i++) {
            total += Math.max(0, topics.get(i).unread_count);
        }
        return total;
    }

    /** The chat header subtitle, as ChatAvatarContainer.updateSubtitle builds it. */
    @Nullable
    private static CharSequence status(int account, long dialogId) {
        MessagesController controller = MessagesController.getInstance(account);
        long userId = 0;
        if (DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.EncryptedChat encryptedChat = controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            if (encryptedChat != null) userId = encryptedChat.user_id;
        } else if (DialogObject.isUserDialog(dialogId)) {
            userId = dialogId;
        } else {
            TLRPC.Chat chat = controller.getChat(-dialogId);
            if (chat == null) return null;
            TLRPC.ChatFull info = controller.getChatFull(chat.id);
            if (info == null && chat.megagroup && chat.participants_count <= 0) {
                return null; // the header's lowercase "loading"; the placeholder reads better
            }
            if (info == null && chat.participants_count > 0 && ChatObject.isChannel(chat)) {
                // the header says "loading" until ChatFull arrives; the chat itself already knows its size
                if (chat.megagroup) {
                    return LocaleController.formatPluralString("Members", chat.participants_count);
                }
                // short form ("12.3K subscribers"), as getChatSubtitle prints it once ChatFull is loaded
                int[] rounded = new int[1];
                String shortNumber = LocaleController.formatShortNumber(chat.participants_count, rounded);
                return LocaleController.formatPluralString("Subscribers", rounded[0]).replace(String.format("%d", rounded[0]), shortNumber);
            }
            return ChatAvatarContainer.getChatSubtitle(chat, info, 0);
        }
        TLRPC.User user = controller.getUser(userId);
        if (user == null) return null;
        // Mirrors the user branch of ChatAvatarContainer.updateSubtitle (ChatAvatarContainer.java:1574-1599).
        if (UserObject.isReplyUser(user) || user.id == UserObject.VERIFY) {
            return null;
        } else if (user.id == UserConfig.getInstance(account).getClientUserId()) {
            return LocaleController.getString(R.string.ChatYourSelf);
        } else if (user.id == 333000 || user.id == 777000 || user.id == 42777) {
            return LocaleController.getString(R.string.ServiceNotifications);
        } else if (MessagesController.isSupportUser(user)) {
            return LocaleController.getString(R.string.SupportStatus);
        } else if (user.bot && user.bot_active_users != 0) {
            return LocaleController.formatPluralStringComma("BotUsers", user.bot_active_users, ',');
        } else if (user.bot) {
            return LocaleController.getString(R.string.Bot);
        }
        CharSequence status = LocaleController.formatUserStatus(account, user, null, null);
        return com.dazewell.gram.chattimezone.ChatTimeZoneRenderer.augmentLastSeen(status, account, user);
    }

    /**
     * The kind of the last message, never its content. Deliberately not MessageObject's own
     * media string, which carries file names, sticker emoji and invoice/game text.
     */
    @Nullable
    private static CharSequence type(@Nullable MessageObject message) {
        if (message == null || message.messageOwner == null) return null;
        int res;
        if (message.getGroupId() != 0) {
            res = R.string.Album;
        } else if (message.isRoundVideo()) {
            res = R.string.AttachRound;
        } else if (message.isVoice()) {
            res = R.string.AttachAudio;
        } else if (message.isGif()) {
            res = R.string.AttachGif;
        } else if (message.isVideo()) {
            res = R.string.AttachVideo;
        } else if (message.isPhoto()) {
            res = R.string.AttachPhoto;
        } else if (message.isSticker() || message.isAnimatedSticker() || message.isAnimatedEmoji()) {
            res = R.string.AttachSticker;
        } else if (message.isMusic()) {
            res = R.string.AttachMusic;
        } else if (message.isPoll()) {
            res = R.string.Poll;
        } else if (message.isLiveLocation()) {
            res = R.string.AttachLiveLocation;
        } else if (message.isLocation()) {
            res = R.string.AttachLocation;
        } else if (MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaContact) {
            res = R.string.AttachContact;
        } else if (message.isDocument()) {
            res = R.string.AttachDocument;
        } else {
            res = R.string.Message;
        }
        return LocaleController.getString(res);
    }
}
