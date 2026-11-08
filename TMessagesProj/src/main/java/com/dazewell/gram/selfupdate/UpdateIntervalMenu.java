package com.dazewell.gram.selfupdate;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

import kotlin.Unit;
import tw.nekomimi.nekogram.ui.BottomBuilder;
import xyz.nextalone.nagram.NaConfig;

/**
 * The "Check Interval" row of the version menu, shared by SettingsActivity and ProfileActivity
 * so the two copies of that menu stay one line apart from upstream.
 */
public final class UpdateIntervalMenu {

    private UpdateIntervalMenu() {
    }

    public static void addTo(BottomBuilder builder, BaseFragment fragment) {
        var cell = builder.addItem(getString(R.string.UpdateIntervalNax) + " - " + (SelfUpdate.isAvailable() ? label(SelfUpdate.intervalMinutes()) : getString(R.string.Unavailable)), R.drawable.msg_contacts_time_solar, (it) -> {
            if (fragment.getParentActivity() == null) {
                return Unit.INSTANCE;
            }
            BottomBuilder picker = new BottomBuilder(fragment.getParentActivity());
            picker.addTitle(getString(R.string.UpdateIntervalNax));
            int current = SelfUpdate.intervalMinutes();
            for (int minutes : SelfUpdate.INTERVAL_MINUTES) {
                picker.addRadioItem(label(minutes), minutes == current, (radioButtonCell) -> {
                    NaConfig.INSTANCE.getUpdateCheckInterval().setConfigInt(minutes);
                    picker.doRadioCheck(radioButtonCell);
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(picker::dismiss, 300);
                    return Unit.INSTANCE;
                });
            }
            fragment.showDialog(picker.create());
            return Unit.INSTANCE;
        });
        cell.setEnabled(SelfUpdate.isAvailable());
    }

    private static String label(int minutes) {
        // Upstream's translated plurals, so the choices follow the app language.
        return minutes < 60
                ? org.telegram.messenger.LocaleController.formatPluralString("Minutes", minutes)
                : org.telegram.messenger.LocaleController.formatPluralString("Hours", minutes / 60);
    }
}
