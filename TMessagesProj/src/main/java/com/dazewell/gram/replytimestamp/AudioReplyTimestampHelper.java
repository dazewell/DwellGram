package com.dazewell.gram.replytimestamp;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Components.ChatActivityEnterView;

public final class AudioReplyTimestampHelper {

    private AudioReplyTimestampHelper() {
    }

    public static void insert(ChatActivityEnterView enterView, int account, MessageObject replyMessage) {
        if (enterView == null || replyMessage == null) {
            return;
        }
        MediaController mediaController = MediaController.getInstance();
        MessageObject playingMessage = mediaController.getPlayingMessageObject();
        if (playingMessage == null || playingMessage.currentAccount != account || replyMessage.currentAccount != account
                || playingMessage.getDialogId() != replyMessage.getDialogId()
                || playingMessage.getId() != replyMessage.getId() || playingMessage.eventId != replyMessage.eventId
                || !playingMessage.isVoice() && !playingMessage.isMusic()
                || !mediaController.isPlayingMessage(replyMessage)) {
            return;
        }
        int cursor = Math.max(0, enterView.getCursorPosition());
        CharSequence fieldText = enterView.getFieldText();
        String separator = cursor > 0 && fieldText != null && cursor <= fieldText.length()
                && !Character.isWhitespace(fieldText.charAt(cursor - 1)) ? " " : "";
        enterView.replaceWithText(cursor, 0,
                separator + AndroidUtilities.formatDuration(playingMessage.audioProgressSec, false) + " ", false);
    }
}
