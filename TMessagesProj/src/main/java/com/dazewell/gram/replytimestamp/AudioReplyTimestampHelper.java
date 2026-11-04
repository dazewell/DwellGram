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
                || playingMessage.getChatMode() != replyMessage.getChatMode()
                || MessageObject.getQuickReplyId(account, playingMessage.messageOwner) !=
                MessageObject.getQuickReplyId(account, replyMessage.messageOwner)
                || !playingMessage.isVoice() && !playingMessage.isMusic()
                || !mediaController.isPlayingMessage(replyMessage)) {
            return;
        }
        // A paused seek updates the player before the whole-second progress timer resumes.
        long progressMs = mediaController.getProgressMs(playingMessage);
        int progressSeconds = progressMs >= 0 ? (int) (progressMs / 1000L) : playingMessage.audioProgressSec;
        int cursor = Math.max(0, enterView.getCursorPosition());
        CharSequence fieldText = enterView.getFieldText();
        String separator = cursor > 0 && fieldText != null && cursor <= fieldText.length()
                && !Character.isWhitespace(fieldText.charAt(cursor - 1)) ? " " : "";
        enterView.replaceWithText(cursor, 0,
                separator + AndroidUtilities.formatDuration(progressSeconds, false) + " ", false);
    }
}
