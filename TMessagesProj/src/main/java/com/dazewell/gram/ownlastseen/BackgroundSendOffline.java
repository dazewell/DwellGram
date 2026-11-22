package com.dazewell.gram.ownlastseen;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

/**
 * A message that reaches the server marks us online there. In the foreground the status timer
 * (MessagesController.updateTimerProc) settles that, but in the background it sends offline once
 * per pause and then trusts offlineSent, so a send landing after that leaves us online until the
 * app is next opened and left. Memos are the usual case: a video memo's upload finishes after the
 * app has gone back, and a text memo never brings the main screen up at all.
 */
public final class BackgroundSendOffline {

    // Same pause AyuGhostUtils leaves before its offline-after-send packet
    private static final long DELAY_MS = 1000;

    private BackgroundSendOffline() {}

    /** ConnectionsManager.sendRequestInternal. */
    public static RequestDelegate wrap(int account, TLObject request, RequestDelegate onComplete) {
        // A null delegate stays null: the caller then falls through to onCompleteTimestamp or processUpdates
        if (onComplete == null || !(request instanceof TLRPC.TL_messages_sendMessage
                || request instanceof TLRPC.TL_messages_sendMedia
                || request instanceof TLRPC.TL_messages_sendMultiMedia)) {
            return onComplete;
        }
        return (response, error) -> {
            onComplete.run(response, error);
            if (error == null) {
                Utilities.stageQueue.postRunnable(() -> {
                    if (!keptOnline(account)) {
                        TL_account.updateStatus req = new TL_account.updateStatus();
                        req.offline = true;
                        ConnectionsManager.getInstance(account).sendRequest(req, null);
                    }
                }, DELAY_MS);
            }
        };
    }

    // The timer's own test for sending online, read on the stage queue where the timer reads it
    private static boolean keptOnline(int account) {
        return !MessagesController.getInstance(account).ignoreSetOnline
                && ConnectionsManager.getInstance(account).getPauseTime() == 0
                && ApplicationLoader.isScreenOn
                && !ApplicationLoader.mainInterfacePausedStageQueue;
    }
}
