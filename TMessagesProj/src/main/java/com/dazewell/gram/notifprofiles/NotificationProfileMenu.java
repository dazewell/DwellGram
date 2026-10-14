package com.dazewell.gram.notifprofiles;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

// NagramX: the chat header menu entry that shows a chat's notification profile and opens the picker, so changing it
// no longer takes four taps through the Notifications screen.
public final class NotificationProfileMenu {

    private NotificationProfileMenu() {
    }

    public static ActionBarMenuItem.Item add(ActionBarMenuItem headerItem, int id, int account, long dialogId) {
        int profile = NotificationProfiles.effective(account, dialogId);
        return headerItem.lazilyAddSubItem(id, iconRes(profile), text(account, dialogId, profile));
    }

    // The profile can also change from the Notifications screen, so this runs again whenever the menu opens.
    public static void refresh(ActionBarMenuItem.Item item, int account, long dialogId, boolean visible) {
        if (item == null) return;
        item.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        int profile = NotificationProfiles.effective(account, dialogId);
        item.setText(text(account, dialogId, profile));
        item.setIcon(iconRes(profile));
    }

    public static void open(BaseFragment fragment, int account, long dialogId, ActionBarMenuItem.Item item, ActionBarMenuItem bell) {
        if (fragment.getParentActivity() == null) return;
        fragment.showDialog(NotificationProfilePicker.create(fragment.getParentActivity(), account, dialogId, fragment.getResourceProvider(), () -> {
            refresh(item, account, dialogId, true);
            syncBell(bell, account, dialogId);
        }));
    }

    // The header bell: one tap cycles Loud -> Passive -> Muted and says what the new profile does, a long press opens
    // the picker. It starts hidden; updateBell() owns its visibility because a forum chat switches topics in place.
    public static ActionBarMenuItem addBell(BaseFragment fragment, ActionBarMenu menu, int id, int account, long dialogId) {
        ActionBarMenuItem bell = menu.addItem(id, iconRes(NotificationProfiles.effective(account, dialogId)), fragment.getResourceProvider());
        bell.setVisibility(android.view.View.GONE);
        bell.setOnClickListener(v -> cycle(fragment, bell, account, dialogId));
        bell.setOnLongClickListener(v -> {
            if (fragment.getParentActivity() != null) {
                fragment.showDialog(NotificationProfilePicker.create(fragment.getParentActivity(), account, dialogId, fragment.getResourceProvider(), () -> syncBell(bell, account, dialogId)));
            }
            return true;
        });
        return bell;
    }

    // Shown only where the Alerts menu item is (not on topics, which are keyed by the raw dialog id, and not while the user is
    // out of the chat). It stays while Telegram's mute is on, since Muted is one of its states; a mute flip from Telegram's
    // own menu calls this again, which is what moves the icon.
    public static void updateBell(ActionBarMenuItem bell, int account, long dialogId, boolean topic, boolean inChat) {
        if (bell == null) return;
        boolean visible = !topic && inChat;
        bell.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        if (visible) {
            syncBell(bell, account, dialogId);
        }
    }

    // Icon and description only, never visibility: the picker is also reachable from the Alerts item while the bell is hidden.
    private static void syncBell(ActionBarMenuItem bell, int account, long dialogId) {
        if (bell == null) return;
        int profile = NotificationProfiles.effective(account, dialogId);
        bell.setContentDescription(text(account, dialogId, profile));
        // A small clock in the corner while a schedule window, not the user's own choice, decides. The header tints the
        // whole icon one colour, so the bell shrinks to make room for it instead of the two overlapping.
        if (NotificationProfiles.isScheduleDeciding(account, dialogId)) {
            Drawable bellIcon = ContextCompat.getDrawable(bell.getContext(), iconRes(profile));
            Drawable clock = ContextCompat.getDrawable(bell.getContext(), R.drawable.baseline_schedule_24);
            if (bellIcon != null && clock != null) {
                LayerDrawable layers = new LayerDrawable(new Drawable[]{bellIcon.mutate(), clock.mutate()});
                // the 4dp end/top inset makes the box 24dp, so the 12dp clock only meets the bell's empty corner
                layers.setLayerInsetRelative(0, 0, AndroidUtilities.dp(4), AndroidUtilities.dp(4), 0);
                layers.setLayerSize(0, AndroidUtilities.dp(20), AndroidUtilities.dp(20));
                layers.setLayerGravity(0, Gravity.BOTTOM | Gravity.START);
                layers.setLayerSize(1, AndroidUtilities.dp(12), AndroidUtilities.dp(12));
                layers.setLayerGravity(1, Gravity.TOP | Gravity.END);
                bell.setIcon(layers);
                return;
            }
        }
        bell.setIcon(iconRes(profile));
    }

    private static void cycle(BaseFragment fragment, ActionBarMenuItem bell, int account, long dialogId) {
        int profile = (NotificationProfiles.effective(account, dialogId) + 1) % 3;
        NotificationProfiles.set(account, dialogId, profile);
        NotificationProfiles.onChanged(account, dialogId);
        syncBell(bell, account, dialogId);
        if (BulletinFactory.canShowBulletin(fragment)) {
            int raw = profile == NotificationProfiles.MUTED ? R.raw.ic_mute : profile == NotificationProfiles.PASSIVE ? R.raw.sound_off : R.raw.ic_unmute;
            BulletinFactory.of(fragment).createSimpleBulletin(raw, text(profile), LocaleController.getString(NotificationProfiles.infoRes(profile))).show();
        }
    }

    private static CharSequence text(int profile) {
        return LocaleController.formatString(R.string.NaxNotifProfileMenu, LocaleController.getString(NotificationProfiles.labelRes(profile)));
    }

    // "(until 4:00 PM)" while a timer runs, "(scheduled)" while a schedule window, not the user's own choice, is what decides.
    private static CharSequence text(int account, long dialogId, int profile) {
        long timerUntil = NotificationProfiles.timerUntil(account, dialogId);
        if (timerUntil > 0) {
            return LocaleController.formatString(R.string.NaxNotifProfileMenuUntil, LocaleController.getString(NotificationProfiles.labelRes(profile)), NotificationTimerDialog.untilText(timerUntil));
        }
        if (!NotificationProfiles.isScheduleDeciding(account, dialogId)) return text(profile);
        return LocaleController.formatString(R.string.NaxNotifProfileMenuScheduled, LocaleController.getString(NotificationProfiles.labelRes(profile)));
    }

    private static int iconRes(int profile) {
        switch (profile) {
            // Muted takes Telegram's mute icon; Passive moves to the crossed-out speaker
            case NotificationProfiles.MUTED:
                return R.drawable.msg_mute;
            case NotificationProfiles.PASSIVE:
                return R.drawable.msg_silent;
            default:
                return R.drawable.msg_unmute;
        }
    }
}
