package com.dazewell.gram.ownlastseen;

import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;

import com.dazewell.gram.ui.components.PopupRowDivider;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * The refresh button at the trailing end of the Ayu Mode row. Sending a message under Ghost Mode
 * goes online and relies on a later offline packet; when that one is lost the server keeps showing
 * us online. One tap fixes whichever state the server reports:
 * <ul>
 *   <li>online: send offline again;</li>
 *   <li>offline: go online for a moment, then offline, so last seen reads "just now".</li>
 * </ul>
 * The offline step only goes out once the online one has been answered, so the two cannot arrive
 * in the wrong order. Toggling Ghost Mode off and on would not do this: the interceptor reads the
 * config later, on the stage queue (ConnectionsManager.sendRequestInternal), by which time Ghost
 * is back on and the online packet gets rewritten to offline. {@link OnlinePulse} is exempted from
 * that rewrite instead.
 */
public final class StatusRefresh {

    private StatusRefresh() {}

    /**
     * An online packet the user asked for. Same constructor and wire format as its parent;
     * AyuGhostUtils.interceptRequest recognises the type and leaves {@code offline} alone.
     */
    public static final class OnlinePulse extends TL_account.updateStatus {
        OnlinePulse() {
            offline = false;
        }
    }

    static void attach(ActionBarMenuSubItem item, BaseFragment fragment, int account, long selfId) {
        item.setRightIcon(R.drawable.msg_reset_solar, v -> {
            if (!v.isEnabled()) {
                return;
            }
            // The menu stays open: the last seen line under the row is the feedback.
            setBusy(v, true);
            item.setSubtext(LocaleController.getString(R.string.Loading));
            OwnLastSeenRow.fetchSelf(account, selfId, self -> {
                if (self == null) {
                    finish(item, v, account, selfId);
                } else if (isOnline(account, self)) {
                    sendStatus(account, offline(), () -> finish(item, v, account, selfId));
                } else {
                    sendStatus(account, new OnlinePulse(), () -> {
                        // Ghost switched off meanwhile: going offline now would undo that.
                        if (NekoConfig.isGhostModeActive()) {
                            sendStatus(account, offline(), () -> finish(item, v, account, selfId));
                        } else {
                            finish(item, v, account, selfId);
                        }
                    });
                }
            });
        });
        ImageView button = item.getRightIcon();
        if (button == null) {
            return;
        }
        button.setContentDescription(LocaleController.getString(R.string.GhostModeStatusRefresh));
        // Long press shows this as a tooltip; minSdk 27, so no fallback is needed.
        button.setTooltipText(LocaleController.getString(R.string.GhostModeStatusRefreshHint));
        // Sits just outside the dp(40) zone ActionBarMenuSubItem gives a right icon with a listener.
        PopupRowDivider.addTo(item, fragment.getResourceProvider(), 40f);
        clearOfDivider(item);
    }

    private static boolean isOnline(int account, TLRPC.User self) {
        return self.status instanceof TLRPC.TL_userStatusOnline
                && self.status.expires > ConnectionsManager.getInstance(account).getCurrentTime();
    }

    private static TL_account.updateStatus offline() {
        TL_account.updateStatus req = new TL_account.updateStatus();
        req.offline = true;
        return req;
    }

    /** Runs {@code then} on the UI thread whether or not the request succeeded. */
    private static void sendStatus(int account, TL_account.updateStatus req, Runnable then) {
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(then));
    }

    private static void finish(ActionBarMenuSubItem item, ImageView button, int account, long selfId) {
        OwnLastSeenRow.fetchSelf(account, selfId, self -> {
            item.setSubtext(self != null && self.status != null ? LocaleController.formatUserStatus(account, self) : null);
            setBusy(button, false);
        });
    }

    private static void setBusy(android.view.View button, boolean busy) {
        button.setEnabled(!busy);
        button.setAlpha(busy ? 0.5f : 1f);
    }

    /**
     * setRightIcon() stops the label 32dp short of the edge, which is inside the divider: push the
     * label and the last seen line clear so they ellipsize before the line instead of under it.
     */
    private static void clearOfDivider(ActionBarMenuSubItem item) {
        clearOfDivider(item.getTextView());
        // decorate() sets the subtext before attaching, so the view exists by now.
        if (item.subtextView != null) {
            clearOfDivider(item.subtextView);
        }
    }

    private static void clearOfDivider(android.view.View view) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (LocaleController.isRTL) {
            lp.leftMargin = AndroidUtilities.dp(52);
        } else {
            lp.rightMargin = AndroidUtilities.dp(52);
        }
        view.setLayoutParams(lp);
    }
}
