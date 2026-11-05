package com.dazewell.gram.player;

import android.os.Bundle;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Components.Forum.ForumUtilities;

public final class VoicePlayerNavigation {

    private VoicePlayerNavigation() {
    }

    public static void open(BaseFragment fragment, MessageObject messageObject) {
        int account = messageObject.currentAccount;
        long dialogId = messageObject.getDialogId();
        long topicId = messageObject.getTopicId();
        int messageId = messageObject.getId();
        LaunchActivity launchActivity = LaunchActivity.instance;
        boolean useCurrentLayout = account != UserConfig.selectedAccount;
        if (useCurrentLayout) {
            if (launchActivity == null) {
                return;
            }
            launchActivity.switchToAccount(account, true);
        }
        if (fragment == null || fragment.getCurrentAccount() != account) {
            useCurrentLayout = true;
        }
        if (!useCurrentLayout && fragment instanceof ChatActivity chat
                && chat.getCurrentAccount() == account
                && chat.getDialogId() == dialogId
                && chat.getChatMode() == 0
                && (chat.getTopicId() == 0
                        ? !chat.isThreadChat()
                        : chat.getTopicId() == topicId)) {
            chat.scrollToMessageId(messageId, 0, false, 0, true, 0);
        } else {
            Bundle args = new Bundle();
            if (DialogObject.isEncryptedDialog(dialogId)) {
                args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
            } else if (DialogObject.isUserDialog(dialogId)) {
                args.putLong("user_id", dialogId);
            } else {
                args.putLong("chat_id", -dialogId);
            }
            args.putInt("message_id", messageId);
            ChatActivity chat = new ChatActivity(args);
            chat.setCurrentAccount(account);
            ForumUtilities.applyTopic(chat, MessagesStorage.TopicKey.of(dialogId, topicId));
            if (useCurrentLayout) {
                if (launchActivity == null) {
                    return;
                }
                launchActivity.presentFragment(chat, false, false);
            } else {
                fragment.presentFragment(chat, fragment instanceof ChatActivity);
            }
        }
    }
}
