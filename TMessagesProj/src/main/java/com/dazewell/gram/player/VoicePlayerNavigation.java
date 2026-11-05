package com.dazewell.gram.player;

import android.os.Bundle;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;

public final class VoicePlayerNavigation {

    private VoicePlayerNavigation() {
    }

    public static void open(BaseFragment fragment, MessageObject messageObject) {
        long dialogId = messageObject.getDialogId();
        long topicId = messageObject.getTopicId();
        if (fragment instanceof ChatActivity chat
                && chat.getCurrentAccount() == messageObject.currentAccount
                && chat.getDialogId() == dialogId
                && chat.getChatMode() == 0
                && !chat.isThreadChat()
                && chat.getTopicId() == topicId) {
            chat.scrollToMessageId(messageObject.getId(), 0, false, 0, true, 0);
        } else {
            Bundle args = new Bundle();
            if (DialogObject.isEncryptedDialog(dialogId)) {
                args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
            } else if (DialogObject.isUserDialog(dialogId)) {
                args.putLong("user_id", dialogId);
            } else {
                args.putLong("chat_id", -dialogId);
            }
            if (topicId != 0) {
                args.putLong("topic_id", topicId);
            }
            args.putInt("message_id", messageObject.getId());
            fragment.presentFragment(new ChatActivity(args), fragment instanceof ChatActivity);
        }
    }
}
