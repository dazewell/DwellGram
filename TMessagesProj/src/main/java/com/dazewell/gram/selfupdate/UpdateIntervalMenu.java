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
        var cell = builder.addItem(getString(R.string.UpdateIntervalNax) + " - " + label(SelfUpdate.intervalMinutes()), R.drawable.msg_contacts_time_solar, (it) -> {
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
        switch (minutes) {
            case 15:
                return getString(R.string.UpdateInterval15mNax);
            case 60:
                return getString(R.string.UpdateInterval1hNax);
            case 180:
                return getString(R.string.UpdateInterval3hNax);
            case 360:
                return getString(R.string.UpdateInterval6hNax);
            default:
                return getString(R.string.UpdateInterval24hNax);
        }
    }
}
