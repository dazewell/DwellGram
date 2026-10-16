package com.dazewell.gram.stash;

import android.app.Activity;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.utils.AyuState;
import xyz.nextalone.nagram.NaConfig;

// #stash. The last step of Stash: once every copy is confirmed in Scheduled, the originals are deleted after
// SECONDS unless Undo is tapped, which instead deletes the copies, so nothing wrong is left queued to go out.
// Stash itself is only a shortcut (no-sender forward into this chat's Scheduled list, then delete), so the send
// side reuses ChatActivity's repost-as-copy dispatch and this class owns just the wait, the delete and the undo.
//
// The bar sits on LaunchActivity's content frame, above every fragment, so it stays up and tappable over the
// Scheduled screen while the user checks there (a BulletinWindow can't take touches). The delete runs on its own
// timer rather than as the bar's delayed action: Bulletin keeps one visible bulletin app-wide and any other one
// hides it, and an undo button's delayed action fires on that hide, so a stray toast would otherwise delete the
// originals early. A bar hidden early by something else comes back RESHOW_DELAY_MS later with the time left, long
// enough for that other toast to be read, so it can't take Undo away; if the bar can't be shown in the first place
// nothing is deleted.
public final class StashCountdown {

    public static final int SECONDS = 15;
    private static final long RESHOW_DELAY_MS = 2000;

    private StashCountdown() {
    }

    // sourceIds[0] are this dialog's ids, sourceIds[1] the migrated-from chat's. Everything is captured by value,
    // so the delete still runs if the chat is left during the wait.
    public static void show(Activity activity, int account, long dialogId, long mergeDialogId, int topicId, ArrayList<Integer>[] sourceIds, ArrayList<Integer> copyIds, Theme.ResourcesProvider resourcesProvider) {
        if (activity == null) {
            return;
        }
        int count = sourceIds[0].size() + sourceIds[1].size();
        final boolean[] settled = {false};
        Runnable delete = () -> {
            if (settled[0]) {
                return;
            }
            settled[0] = true;
            deleteOriginals(account, dialogId, mergeDialogId, topicId, sourceIds);
        };
        Runnable undo = () -> {
            if (settled[0]) {
                return;
            }
            settled[0] = true;
            AndroidUtilities.cancelRunOnUIThread(delete);
            deleteCopies(account, dialogId, topicId, copyIds);
        };
        long deadline = SystemClock.elapsedRealtime() + SECONDS * 1000L;
        if (showBar(activity, count, deadline, settled, undo, resourcesProvider)) {
            AndroidUtilities.runOnUIThread(delete, SECONDS * 1000L);
        }
    }

    private static boolean showBar(Activity activity, int count, long deadline, boolean[] settled, Runnable undo, Theme.ResourcesProvider resourcesProvider) {
        long left = deadline - SystemClock.elapsedRealtime();
        if (settled[0] || left < 1000 || activity.isFinishing() || !(activity instanceof LaunchActivity) || ((LaunchActivity) activity).frameLayout == null) {
            return false;
        }
        Bulletin bulletin = BulletinFactory.of(((LaunchActivity) activity).frameLayout, resourcesProvider)
                .createUndoBulletin(LocaleController.getString(R.string.StashDeleteTitle), LocaleController.formatPluralString("StashDeleteCountdown", count), undo, null);
        bulletin.setDuration((int) left);
        if (bulletin.getLayout() instanceof Bulletin.ButtonLayout && ((Bulletin.ButtonLayout) bulletin.getLayout()).timerView != null) {
            ((Bulletin.ButtonLayout) bulletin.getLayout()).timerView.timeLeft = left;
        }
        // Undo settles before the bar hides, and the bar's own timeout lands with under a second left, so
        // only a hide by something else brings it back
        bulletin.setOnHideListener(() -> AndroidUtilities.runOnUIThread(() -> showBar(activity, count, deadline, settled, undo, resourcesProvider), RESHOW_DELAY_MS));
        bulletin.show();
        return true;
    }

    // Same delete the stock confirmation runs with "keep locally" off, for everyone where the server allows it -
    // Telegram itself only deletes for you where it doesn't (another member's message).
    private static void deleteOriginals(int account, long dialogId, long mergeDialogId, int topicId, ArrayList<Integer>[] sourceIds) {
        for (int slot = 1; slot >= 0; slot--) {
            ArrayList<Integer> ids = sourceIds[slot];
            if (ids == null || ids.isEmpty()) {
                continue;
            }
            for (int i = 0; i < ids.size(); i++) {
                AyuState.permitDeleteMessage(account, dialogId, ids.get(i), false);
            }
            notifyAyu(account, dialogId, ids);
            MessagesController.getInstance(account).deleteMessages(ids, null, null, slot == 1 && mergeDialogId != 0 ? mergeDialogId : dialogId, topicId, true, ChatActivity.MODE_DEFAULT);
        }
    }

    private static void deleteCopies(int account, long dialogId, int topicId, ArrayList<Integer> copyIds) {
        if (copyIds == null || copyIds.isEmpty()) {
            return;
        }
        ArrayList<Integer> ids = new ArrayList<>(copyIds);
        for (int i = 0; i < ids.size(); i++) {
            AyuState.permitDeleteMessage(account, dialogId, ids.get(i), true);
        }
        notifyAyu(account, dialogId, ids);
        MessagesController.getInstance(account).deleteMessages(ids, null, null, dialogId, topicId, true, ChatActivity.MODE_SCHEDULED);
    }

    private static void notifyAyu(int account, long dialogId, ArrayList<Integer> ids) {
        if (NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(AyuConstants.MESSAGES_DELETED_NOTIFICATION, dialogId, ids));
        }
    }
}
