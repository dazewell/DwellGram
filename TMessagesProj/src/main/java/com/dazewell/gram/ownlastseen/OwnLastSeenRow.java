package com.dazewell.gram.ownlastseen;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

/**
 * Puts your own last seen under the Ayu Mode switch while Ghost Mode is on. Both menus that carry
 * that switch (the chats ⋮ menu and the Settings tab long-press) call this right after adding it.
 *
 * The value comes from the server, not from what this app sent: the self user's status is only
 * written locally from TL_updateUserStatus (MessagesController's update handler), never from our
 * own account.updateStatus, so the cache alone is often stale. A users.getUsers(self) on open asks
 * the server for the status it currently holds for us. That request does not itself mark us
 * online -- Ghost Mode only rewrites account.updateStatus and lets every other request through
 * (AyuGhostUtils.interceptRequest), so if ordinary requests flipped the status Ghost Mode could
 * not work at all.
 */
public final class OwnLastSeenRow {

    private OwnLastSeenRow() {}

    public static void decorate(ActionBarMenuSubItem item, BaseFragment fragment) {
        if (item == null || fragment == null || !NekoConfig.isGhostModeActive() || !NaConfig.INSTANCE.getShowOwnLastSeen().Bool()) {
            return;
        }
        int account = fragment.getCurrentAccount();
        long selfId = UserConfig.getInstance(account).getClientUserId();
        TLRPC.User cached = MessagesController.getInstance(account).getUser(selfId);
        // Shown at once so the row has its final height before the popup lays out; only the
        // text changes when the server answers.
        item.setSubtext(cached != null && cached.status != null
                ? format(account, cached)
                : LocaleController.getString(R.string.Loading));

        TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
        req.id.add(new TLRPC.TL_inputUserSelf());
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            TLRPC.User self = null;
            if (err == null && res instanceof Vector) {
                for (Object o : ((Vector) res).objects) {
                    if (o instanceof TLRPC.User && ((TLRPC.User) o).id == selfId) {
                        self = (TLRPC.User) o;
                        break;
                    }
                }
            }
            final TLRPC.User fresh = self;
            AndroidUtilities.runOnUIThread(() -> {
                if (fresh != null && fresh.status != null) {
                    item.setSubtext(format(account, fresh));
                } else if (cached == null || cached.status == null) {
                    item.setSubtext(null);
                }
            });
        });
    }

    private static String format(int account, TLRPC.User self) {
        return LocaleController.formatUserStatus(account, self);
    }
}
