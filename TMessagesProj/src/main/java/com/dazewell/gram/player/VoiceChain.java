package com.dazewell.gram.player;

import org.telegram.messenger.MessageObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import xyz.nextalone.nagram.NaConfig;

/**
 * "Back-to-back only" auto-play: the voice/round playlist stops at the first chat message
 * that is not itself queued, so playback never jumps over a text, photo or reply.
 *
 * Main thread only: both entry points run from ChatActivity and from
 * MediaController.didReceivedNotification.
 */
public final class VoiceChain {

    // The playlist trim() last produced, identified by its tail, and whether a message arriving
    // in that chat may still extend it. A different tail means someone else set the playlist
    // (shared media, search) and filterNew leaves it alone.
    private static WeakReference<MessageObject> tail;
    private static boolean open;
    private static int account;
    private static long dialogId;

    private VoiceChain() {
    }

    public static int mode() {
        int mode = NaConfig.INSTANCE.getAutoPlayNextVoice().Int();
        return mode == NaConfig.AUTO_PLAY_NEXT_BACK_TO_BACK || mode == NaConfig.AUTO_PLAY_NEXT_NEVER
                ? mode : NaConfig.AUTO_PLAY_NEXT_ALWAYS;
    }

    public static boolean isNever() {
        return mode() == NaConfig.AUTO_PLAY_NEXT_NEVER;
    }

    /**
     * Cuts playlist (start first, then later messages in chat order) at the first real message
     * after start that is not the next playlist entry. chatMessages is ChatActivity.messages,
     * newest at index 0. forwardEndReached says nothing newer exists beyond what is loaded.
     */
    public static ArrayList<MessageObject> trim(ArrayList<MessageObject> chatMessages, ArrayList<MessageObject> playlist, long skipDialogId, boolean forwardEndReached) {
        if (mode() != NaConfig.AUTO_PLAY_NEXT_BACK_TO_BACK || playlist.isEmpty()) {
            return playlist;
        }
        MessageObject start = playlist.get(0);
        int startIndex = chatMessages.indexOf(start);
        int kept = 1;
        boolean broken = startIndex < 0;
        for (int i = startIndex - 1; !broken && i >= 0; i--) {
            MessageObject m = chatMessages.get(i);
            if (isSynthetic(m) || m.getDialogId() == skipDialogId && start.getDialogId() != skipDialogId) {
                continue;
            }
            if (kept < playlist.size() && playlist.get(kept) == m) {
                kept++;
            } else {
                broken = true;
            }
        }
        ArrayList<MessageObject> result = kept < playlist.size() ? new ArrayList<>(playlist.subList(0, kept)) : playlist;
        tail = new WeakReference<>(result.get(result.size() - 1));
        open = !broken && forwardEndReached;
        account = start.currentAccount;
        dialogId = start.getDialogId();
        return result;
    }

    /**
     * Filters messages that just arrived in a chat before MediaController appends the qualifying
     * ones to the playing playlist. queues tells which ones it would append; the first arrival
     * it would not append closes the chain for good.
     */
    public static ArrayList<MessageObject> filterNew(int currentAccount, long did, ArrayList<MessageObject> arrived, ArrayList<MessageObject> playlist, Queues queues) {
        if (mode() != NaConfig.AUTO_PLAY_NEXT_BACK_TO_BACK || playlist == null || playlist.isEmpty()) {
            return arrived;
        }
        MessageObject last = playlist.get(playlist.size() - 1);
        if (tail == null || tail.get() != last || account != currentAccount || dialogId != did) {
            return arrived;
        }
        ArrayList<MessageObject> result = new ArrayList<>();
        for (int i = 0; open && i < arrived.size(); i++) {
            MessageObject m = arrived.get(i);
            if (queues.test(m)) {
                result.add(m);
            } else if (!isSynthetic(m)) {
                open = false;
            }
        }
        if (!result.isEmpty()) {
            tail = new WeakReference<>(result.get(result.size() - 1));
        }
        return result;
    }

    public interface Queues {
        boolean test(MessageObject m);
    }

    private static boolean isSynthetic(MessageObject m) {
        return m.isDateObject || m.type == MessageObject.TYPE_DATE || m.type == MessageObject.TYPE_LOADING || m.isSponsored();
    }
}
