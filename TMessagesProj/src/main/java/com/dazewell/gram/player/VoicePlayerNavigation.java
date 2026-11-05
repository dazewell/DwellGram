package com.dazewell.gram.player;

import android.os.Bundle;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityInterface;

public final class VoicePlayerNavigation {

    private VoicePlayerNavigation() {
    }

    public static void open(BaseFragment fragment, MessageObject messageObject) {
        long dialogId = 0;
        ChatActivityInterface chatActivity = null;
        if (fragment instanceof ChatActivityInterface) {
            chatActivity = (ChatActivityInterface) fragment;
            dialogId = chatActivity.getDialogId();
        }
        if (chatActivity != null && messageObject.getDialogId() == dialogId) {
            chatActivity.scrollToMessageId(messageObject.getId(), 0, false, 0, true, 0);
        } else {
            dialogId = messageObject.getDialogId();
            Bundle args = new Bundle();
            if (DialogObject.isEncryptedDialog(dialogId)) {
                args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
            } else if (DialogObject.isUserDialog(dialogId)) {
                args.putLong("user_id", dialogId);
            } else {
                args.putLong("chat_id", -dialogId);
            }
            args.putInt("message_id", messageObject.getId());
            fragment.presentFragment(new ChatActivity(args), fragment instanceof ChatActivity);
        }
    }
}
