package com.dazewell.gram.search;

import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.NumberPicker;

import java.text.DateFormat;
import java.util.Calendar;

/** Search-session state, owned exclusively by the chat's UI thread. */
public final class DmSearchFilters {
    public interface Listener {
        void changed(TLRPC.User sender, DmSearchCriteria dates, boolean clearType);
    }

    private Calendar from;
    private Calendar to;
    private TLRPC.User sender;

    public boolean active() {
        return from != null || to != null || sender != null;
    }

    public DmSearchCriteria immutableCriteria() {
        return new DmSearchCriteria(boundary(from, false), boundary(to, true));
    }

    public TLRPC.User sender() {
        return sender;
    }

    public void addOptions(ItemOptions options, BaseFragment fragment, Theme.ResourcesProvider theme,
                           TLRPC.User self, TLRPC.User peer, boolean hasType, Listener listener) {
        options.addGap();
        options.add(0, text(R.string.DmSearchDateRangeMenu), () -> showDates(fragment, theme, listener));
        if (from != null || to != null) options.putCheck();
        options.add(0, text(R.string.DmSearchSenderMenu), () -> {
            CharSequence[] names = {text(R.string.DmSearchAnyone), text(R.string.DmSearchMe), UserObject.getUserName(peer)};
            fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), theme)
                    .setTitle(text(R.string.DmSearchSender))
                    .setItems(names, (dialog, index) -> {
                        sender = index == 1 ? self : index == 2 ? peer : null;
                        publish(listener, false);
                    }).setNegativeButton(text(R.string.Cancel), null).create());
        });
        if (sender != null) options.putCheck();
        if (active() || hasType) {
            options.add(0, text(R.string.DmSearchClearFilters), () -> {
                from = to = null;
                sender = null;
                publish(listener, true);
            });
        }
    }

    private void publish(Listener listener, boolean clearType) {
        listener.changed(sender, immutableCriteria(), clearType);
    }

    private void showDates(BaseFragment fragment, Theme.ResourcesProvider theme, Listener listener) {
        Calendar[] draft = {copy(from), copy(to)};
        AlertDialog[] picker = new AlertDialog[1];
        LinearLayout layout = new LinearLayout(fragment.getParentActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView[] rows = new TextView[2];
        TextView error = new TextView(fragment.getParentActivity());
        error.setText(text(R.string.DmSearchInvertedDates));
        error.setTextColor(Theme.getColor(Theme.key_text_RedRegular, theme));
        error.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        Runnable[] update = new Runnable[1];
        AlertDialog[] dialog = new AlertDialog[1];
        for (int i = 0; i < 2; i++) {
            final int index = i;
            TextView row = rows[i] = new TextView(fragment.getParentActivity());
            row.setTextSize(16);
            row.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
            row.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, theme));
            row.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(18), AndroidUtilities.dp(24), AndroidUtilities.dp(18));
            layout.addView(row);
            row.setOnClickListener(view -> {
                Calendar selected = draft[index] == null ? Calendar.getInstance() : draft[index];
                int year = Calendar.getInstance().get(Calendar.YEAR);
                picker[0] = AlertsCreator.createDatePickerDialog(fragment.getParentActivity(),
                        2013 - year, 0, 0, selected.get(Calendar.DAY_OF_MONTH), selected.get(Calendar.MONTH),
                        selected.get(Calendar.YEAR), text(index == 0 ? R.string.DmSearchFrom : R.string.DmSearchTo),
                        false, (y, m, d) -> {
                            if (!DmSearchDates.isValidDate(y, m, d)) return;
                            Calendar value = Calendar.getInstance();
                            value.clear();
                            value.set(y, m, d);
                            draft[index] = value;
                            update[0].run();
                        }).create();
                picker[0].show();
                configureDateWheels(picker[0].getContainerView());
            });
        }
        layout.addView(error);
        dialog[0] = new AlertDialog.Builder(fragment.getParentActivity(), theme)
                .setTitle(text(R.string.DmSearchDateRange)).setView(layout)
                .setPositiveButton(text(R.string.DmSearchApply), (d, which) -> {
                    from = draft[0];
                    to = draft[1];
                    publish(listener, false);
                }).setNegativeButton(text(R.string.Cancel), null)
                .setNeutralButton(text(R.string.Clear), (d, which) -> {
                    from = to = null;
                    publish(listener, false);
                }).create();
        update[0] = () -> {
            for (int i = 0; i < 2; i++) {
                rows[i].setText(text(i == 0 ? R.string.DmSearchFrom : R.string.DmSearchTo) + ": "
                        + (draft[i] == null ? text(R.string.DmSearchAnyDate) : DateFormat.getDateInstance().format(draft[i].getTime())));
            }
            boolean valid = draft[0] == null || draft[1] == null || !draft[0].after(draft[1]);
            error.setVisibility(valid ? android.view.View.GONE : android.view.View.VISIBLE);
            if (dialog[0].getButton(DialogInterface.BUTTON_POSITIVE) != null)
                dialog[0].getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(valid);
        };
        fragment.showDialog(dialog[0], dismissed -> {
            if (picker[0] != null) picker[0].dismiss();
        });
        update[0].run();
    }

    private static void configureDateWheels(View view) {
        NumberPicker[] fields = new NumberPicker[3];
        collectDateWheels(view, fields);
        NumberPicker day = fields[0], month = fields[1], year = fields[2];
        if (day == null || month == null || year == null) return;
        // The shared dialog restores values without notifying listeners, leaving January's day maximum.
        // Keep day/month wrapping while recomputing the valid range after restoration and every change.
        day.setAllItemsCount(28);
        day.setWrapSelectorWheel(true);
        month.setAllItemsCount(12);
        month.setWrapSelectorWheel(true);
        year.setWrapSelectorWheel(false);
        Runnable refresh = () -> day.setMaxValue(DmSearchDates.daysInMonth(year.getValue(), month.getValue()));
        month.setOnValueChangedListener((wheel, oldValue, newValue) -> refresh.run());
        year.setOnValueChangedListener((wheel, oldValue, newValue) -> refresh.run());
        refresh.run();
    }

    private static void collectDateWheels(View view, NumberPicker[] fields) {
        if (view instanceof NumberPicker) {
            NumberPicker wheel = (NumberPicker) view;
            int min = wheel.getMinValue(), max = wheel.getMaxValue();
            // The reused dialog exposes no handles; these complete ranges identify its three fields.
            if (min == 1 && max >= 28 && max <= 31) fields[0] = wheel;
            else if (min == 0 && max == 11) fields[1] = wheel;
            else if (min == 2013 && max >= min) fields[2] = wheel;
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectDateWheels(group.getChildAt(i), fields);
        }
    }

    private static Calendar copy(Calendar value) {
        return value == null ? null : (Calendar) value.clone();
    }

    static int boundary(Calendar date, boolean end) {
        return DmSearchDates.boundary(date, end);
    }

    private static String text(int id) {
        return LocaleController.getString(id);
    }
}
