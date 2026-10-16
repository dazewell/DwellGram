package com.dazewell.gram.stash;

import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.utils.AyuState;
import xyz.nextalone.nagram.NaConfig;

// #stash. The last step of Stash: once every copy is confirmed in Scheduled, count down and delete the
// originals unless the user cancels. Stash itself is only a shortcut (no-sender forward into this chat's
// Scheduled list, then delete), so the send side reuses ChatActivity's repost-as-copy dispatch and this
// class owns just the countdown and the delete.
public final class StashCountdown {

    public static final int SECONDS = 15;

    public static final int RESULT_DELETE = 1;
    public static final int RESULT_SCHEDULED = 2;
    public static final int RESULT_CANCEL = 3;
    // dismissed by something other than a button or the timer - in practice the fragment pausing
    public static final int RESULT_INTERRUPTED = 4;

    public interface Callback {
        void onResult(int result);
    }

    private StashCountdown() {
    }

    // The result is posted after the dialog has fully gone, so the caller sees the fragment's
    // visibleDialog already cleared and can re-run its own guards before acting.
    public static void show(BaseFragment fragment, int count, Theme.ResourcesProvider resourcesProvider, Callback callback) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final int[] result = {RESULT_INTERRUPTED};
        final int[] left = {SECONDS};
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.StashDeleteTitle));
        builder.setMessage(text(count, left[0]));
        builder.setPositiveButton(LocaleController.getString(R.string.StashDeleteNow), (d, w) -> result[0] = RESULT_DELETE);
        builder.setNeutralButton(LocaleController.getString(R.string.StashGoToScheduled), (d, w) -> result[0] = RESULT_SCHEDULED);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> result[0] = RESULT_CANCEL);
        AlertDialog dialog = builder.create();
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) {
                    return;
                }
                left[0]--;
                if (left[0] <= 0) {
                    result[0] = RESULT_DELETE;
                    dialog.dismiss();
                    return;
                }
                dialog.setMessage(text(count, left[0]));
                AndroidUtilities.runOnUIThread(this, 1000);
            }
        };
        if (fragment.showDialog(dialog, false, d -> {
            AndroidUtilities.cancelRunOnUIThread(tick);
            int r = result[0];
            AndroidUtilities.runOnUIThread(() -> callback.onResult(r));
        }) == null) {
            return;
        }
        // a stray back press or outside tap must not read as an answer: nothing would bring the countdown back.
        // After showDialog, whose setCanceledOnTouchOutside(true) turns cancelable back on.
        dialog.setCancelable(false);
        AndroidUtilities.runOnUIThread(tick, 1000);
    }

    // Same delete the stock confirmation runs with "keep locally" off, for everyone where the server
    // allows it - Telegram itself only deletes for you where it doesn't (another member's message).
    public static void deleteOriginals(int account, long dialogId, long mergeDialogId, int topicId, int mode, SparseArray<MessageObject>[] sources) {
        for (int slot = 1; slot >= 0; slot--) {
            if (sources[slot] == null || sources[slot].size() == 0) {
                continue;
            }
            ArrayList<Integer> ids = new ArrayList<>();
            for (int i = 0; i < sources[slot].size(); i++) {
                int id = sources[slot].keyAt(i);
                ids.add(id);
                AyuState.permitDeleteMessage(account, dialogId, id, false);
            }
            if (NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
                AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(AyuConstants.MESSAGES_DELETED_NOTIFICATION, dialogId, ids));
            }
            MessagesController.getInstance(account).deleteMessages(ids, null, null, slot == 1 && mergeDialogId != 0 ? mergeDialogId : dialogId, topicId, true, mode);
        }
    }

    private static CharSequence text(int count, int seconds) {
        return LocaleController.formatPluralString("StashDeleteCountdown", count, seconds);
    }
}
