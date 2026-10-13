package com.dazewell.gram.reschedule;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The "Order" row on the bulk Reschedule sheet. It only exists when the numbers on the selected
 * messages differ from their current time order; then it lets the user pick which of the two the
 * spread follows. Each option carries a one-line explanation, which is why this mirrors
 * NotificationProfilePicker rather than AlertsCreator.createSingleChoiceDialog (names only).
 */
public final class RescheduleOrderRow {

    /** The user's choice, read by the caller when the sheet confirms. Defaults to the picked order. */
    public static final class Choice {
        public boolean picked = true;
    }

    private RescheduleOrderRow() {
    }

    public static void add(Context context, LinearLayout container, Choice choice, Theme.ResourcesProvider resourcesProvider) {
        final TextView row = new TextView(context);
        row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        row.setTextColor(Theme.getColor(Theme.key_dialogTextBlue2, resourcesProvider));
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 8, 8));
        bind(row, choice);
        row.setOnClickListener(v -> showPicker(context, choice, resourcesProvider, () -> bind(row, choice)));
        container.addView(row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 0));
    }

    private static void bind(TextView row, Choice choice) {
        row.setText(formatString(R.string.RescheduleOrderRow,
                getString(choice.picked ? R.string.RescheduleOrderPicked : R.string.RescheduleOrderKeep)));
    }

    private static void showPicker(Context context, Choice choice, Theme.ResourcesProvider resourcesProvider, Runnable onChanged) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        for (int i = 0; i < 2; i++) {
            final boolean picked = i == 0;
            RadioColorCell cell = new RadioColorCell(context, resourcesProvider);
            cell.setPadding(dp(4), 0, dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground, resourcesProvider), Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider));
            cell.setTextAndText2AndValue(
                    getString(picked ? R.string.RescheduleOrderPicked : R.string.RescheduleOrderKeep),
                    getString(picked ? R.string.RescheduleOrderPickedInfo : R.string.RescheduleOrderKeepInfo),
                    choice.picked == picked);
            cell.setOnClickListener(v -> {
                builder.getDismissRunnable().run();
                choice.picked = picked;
                onChanged.run();
            });
            layout.addView(cell);
        }
        builder.setTitle(getString(R.string.RescheduleOrderTitle));
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Cancel), null);
        builder.show();
    }
}
