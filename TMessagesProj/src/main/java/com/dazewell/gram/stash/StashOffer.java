package com.dazewell.gram.stash;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

// #stash. The last step of Stash: once every copy is confirmed in Scheduled, the same 15 s offer Repost as Copy
// makes - the originals are still here, Delete opens Telegram's own delete confirmation, and letting it run out
// deletes nothing. Stash itself is only a shortcut (no-sender forward into this chat's Scheduled list, then
// delete), so the send side reuses ChatActivity's repost-as-copy dispatch and this class owns just the bar.
//
// Unlike the repost bar, this one follows the user while they check: it rides whichever screen of the same account
// is on top, so it takes that screen's own placement (above a chat's composer, say), and a watcher moves it when
// the top screen changes. A bar pushed off by another bulletin comes back RESHOW_DELAY_MS later, long enough
// for that one to be read; one the user dismisses stays gone. On a screen of another account it waits unseen, so
// Delete never runs against the wrong account. The bar carries Telegram's countdown circle, as the undo bars do.
public final class StashOffer {

    private static final int SECONDS = 15;
    private static final long RESHOW_DELAY_MS = 2000;
    private static final long WATCH_MS = 300;

    // the one offer up; a new stash retires it, or the two bars would keep pushing each other off. UI thread only
    private static StashOffer active;

    private final int account;
    private final int count;
    private final long deadline;
    private final Utilities.Callback<BaseFragment> onDelete;
    private boolean settled;
    private Bulletin bulletin;
    private BaseFragment host;
    // with no bar up, when the watcher may put it back; Long.MAX_VALUE while a hide is still being classified
    private long reshowAt;

    private StashOffer(int account, int count, Utilities.Callback<BaseFragment> onDelete) {
        this.account = account;
        this.count = count;
        this.deadline = SystemClock.elapsedRealtime() + SECONDS * 1000L;
        this.onDelete = onDelete;
    }

    // onDelete gets the screen the bar was tapped on, to host the confirmation.
    public static void show(int account, int count, Utilities.Callback<BaseFragment> onDelete) {
        if (active != null) {
            active.retire();
        }
        active = new StashOffer(account, count, onDelete);
        active.showBar();
        active.watch();
    }

    private void retire() {
        settled = true;
        if (bulletin != null) {
            Bulletin retired = bulletin;
            bulletin = null;
            retired.hide(false, 0);
        }
    }

    private long left() {
        return deadline - SystemClock.elapsedRealtime();
    }

    private void showBar() {
        BaseFragment top = LaunchActivity.getSafeLastFragment();
        if (settled || left() < 1000 || top == null || top.getCurrentAccount() != account || top.getParentActivity() == null || top.getParentActivity().isFinishing()) {
            return;
        }
        // built as BulletinFactory.createUndoBulletin builds its single-line bar, so the timer sits where the icon is
        BulletinFactory factory = BulletinFactory.of(top);
        Bulletin.LottieLayout layout = new Bulletin.LottieLayout(factory.getContext(), top.getResourceProvider());
        layout.textView.setText(LocaleController.formatPluralString("StashDeleteHint", count));
        layout.textView.setSingleLine(false);
        layout.textView.setMaxLines(2);
        layout.setTimer();
        layout.timerView.timeLeft = left();
        layout.setButton(new Bulletin.UndoButton(factory.getContext(), true, top.getResourceProvider()).setText(LocaleController.getString(R.string.Delete)).setUndoAction(() -> {
            settled = true;
            onDelete.run(top);
        }));
        Bulletin shown = factory.create(layout, (int) left());
        // a tap settles before the bar hides, the bar's own timeout lands with under a second left, and a bar the
        // watcher moved is no longer the current one. Any other hide is told apart a tick later: hide() clears the
        // visible bulletin at once and one that displaced ours takes its place, so another bulletin up means ours
        // was pushed off and comes back, none means the user dismissed it and the offer ends.
        shown.setOnHideListener(() -> {
            if (bulletin != shown) {
                return;
            }
            bulletin = null;
            reshowAt = Long.MAX_VALUE;
            AndroidUtilities.runOnUIThread(() -> {
                Bulletin visible = Bulletin.getVisibleBulletin();
                if (visible != null && visible != shown) {
                    reshowAt = SystemClock.elapsedRealtime() + RESHOW_DELAY_MS;
                } else {
                    settled = true;
                }
            });
        });
        bulletin = shown;
        host = top;
        shown.show();
        // show() quietly does nothing while the screen has no layout container yet (being created or removed)
        if (!shown.isShowing()) {
            bulletin = null;
            reshowAt = 0;
        }
    }

    private void watch() {
        AndroidUtilities.runOnUIThread(() -> {
            if (settled || left() < 1000) {
                // every way an offer ends passes here, so a finished one doesn't keep its chat screen alive
                if (active == this) {
                    active = null;
                }
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
}
