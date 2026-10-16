package com.dazewell.gram.stash;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
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
// The bar rides whichever screen is on top, so it gets that screen's own bulletin placement (above a chat's
// composer, say) and stays tappable; a watcher moves it when the top screen changes, so it follows the user into
// Scheduled and back while they check. The delete runs on its own timer rather than as the bar's delayed action:
// Bulletin keeps one visible bulletin app-wide and any other one hides it, and an undo button's delayed action
// fires on that hide, so a stray toast would otherwise delete the originals early. A bar hidden early by
// something else comes back RESHOW_DELAY_MS later with the time left, long enough for that other toast to be
// read, so it can't take Undo away; if the bar can't be shown in the first place nothing is deleted.
public final class StashCountdown {

    public static final int SECONDS = 15;
    private static final long RESHOW_DELAY_MS = 2000;
    private static final long WATCH_MS = 300;

    private final int count;
    private final long deadline;
    private final Runnable undo;
    private boolean settled;
    private Bulletin bulletin;
    private BaseFragment host;
    // with no bar up, when the watcher may put it back
    private long reshowAt;

    private StashCountdown(int count, long deadline, Runnable undo) {
        this.count = count;
        this.deadline = deadline;
        this.undo = undo;
    }

    // sourceIds[0] are this dialog's ids, sourceIds[1] the migrated-from chat's. Everything is captured by value,
    // so the delete still runs if the chat is left during the wait.
    public static void show(int account, long dialogId, long mergeDialogId, int topicId, ArrayList<Integer>[] sourceIds, ArrayList<Integer> copyIds) {
        int count = sourceIds[0].size() + sourceIds[1].size();
        final StashCountdown[] bar = new StashCountdown[1];
        Runnable undo = () -> {
            if (bar[0].settled) {
                return;
            }
            bar[0].settled = true;
            deleteCopies(account, dialogId, topicId, copyIds);
        };
        bar[0] = new StashCountdown(count, SystemClock.elapsedRealtime() + SECONDS * 1000L, undo);
        if (!bar[0].showBar()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (bar[0].settled) {
                return;
            }
            bar[0].settled = true;
            deleteOriginals(account, dialogId, mergeDialogId, topicId, sourceIds);
        }, SECONDS * 1000L);
        bar[0].watch();
    }

    private long left() {
        return deadline - SystemClock.elapsedRealtime();
    }

    private boolean showBar() {
        BaseFragment top = LaunchActivity.getSafeLastFragment();
        if (settled || left() < 1000 || top == null || top.getParentActivity() == null || top.getParentActivity().isFinishing()) {
            return false;
        }
        Bulletin shown = BulletinFactory.of(top)
                .createUndoBulletin(LocaleController.getString(R.string.StashDeleteTitle), LocaleController.formatPluralString("StashDeleteCountdown", count), undo, null);
        shown.setDuration((int) left());
        if (shown.getLayout() instanceof Bulletin.ButtonLayout && ((Bulletin.ButtonLayout) shown.getLayout()).timerView != null) {
            ((Bulletin.ButtonLayout) shown.getLayout()).timerView.timeLeft = left();
        }
        // Undo settles before the bar hides, the bar's own timeout lands with under a second left, and a bar
        // the watcher moved is no longer the current one, so only a hide by something else brings it back
        shown.setOnHideListener(() -> {
            if (bulletin == shown) {
                bulletin = null;
                reshowAt = SystemClock.elapsedRealtime() + RESHOW_DELAY_MS;
            }
        });
        bulletin = shown;
        host = top;
        shown.show();
        // show() quietly does nothing while the screen has no layout container yet (being created or removed);
        // leave the watcher to try again rather than count a bar nobody can see
        if (!shown.isShowing()) {
            bulletin = null;
            reshowAt = 0;
            return false;
        }
        return true;
    }

    private void watch() {
        AndroidUtilities.runOnUIThread(() -> {
            if (settled || left() < 1000) {
                return;
            }
            if (bulletin == null) {
                if (SystemClock.elapsedRealtime() >= reshowAt) {
                    showBar();
                }
            } else if (LaunchActivity.getSafeLastFragment() != host) {
                Bulletin moved = bulletin;
                bulletin = null;
                moved.hide(false, 0);
                showBar();
            }
            watch();
        }, WATCH_MS);
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
