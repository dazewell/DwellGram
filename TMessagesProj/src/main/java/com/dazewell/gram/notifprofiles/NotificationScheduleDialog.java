package com.dazewell.gram.notifprofiles;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Business.OpeningHoursActivity;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;

// NagramX: the rule list behind the picker's "Schedule…" row, and the rule editor it opens. Plain dialogs built from
// existing cells; every change rewrites the whole list through NotificationProfiles.setRules and rebuilds the shade.
public final class NotificationScheduleDialog {

    private NotificationScheduleDialog() {
    }

    public static void show(Activity activity, int account, long dialogId, Theme.ResourcesProvider rp, Runnable onClosed) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        Runnable[] refresh = new Runnable[1];
        Utilities.Callback<List<NotificationSchedule.Rule>> commit = rules -> {
            NotificationProfiles.setRules(account, dialogId, rules);
            NotificationProfiles.onChanged(account, dialogId);
            refresh[0].run();
        };
        refresh[0] = () -> {
            layout.removeAllViews();
            List<NotificationSchedule.Rule> rules = NotificationProfiles.rules(account, dialogId);

            TextSettingsCell outside = new TextSettingsCell(activity, rp);
            outside.setBackground(Theme.getSelectorDrawable(false));
            outside.setTextAndValue(LocaleController.getString(R.string.NaxNotifScheduleOutside), LocaleController.getString(NotificationProfiles.labelRes(NotificationProfiles.get(account, dialogId))), false);
            outside.setOnClickListener(v -> {
                // the stored base, so only the profiles kept locally; Muted is Telegram's mute and has no base value
                int[] choices = {NotificationProfiles.LOUD, NotificationProfiles.PASSIVE};
                String[] names = new String[choices.length];
                for (int i = 0; i < choices.length; i++) names[i] = LocaleController.getString(NotificationProfiles.labelRes(choices[i]));
                new AlertDialog.Builder(activity, rp)
                        .setTitle(LocaleController.getString(R.string.NaxNotifScheduleOutside))
                        .setItems(names, (d, which) -> {
                            NotificationProfiles.setBase(account, dialogId, choices[which]);
                            NotificationProfiles.onChanged(account, dialogId);
                            refresh[0].run();
                        }).show();
            });
            layout.addView(outside);

            for (int i = 0; i < rules.size(); i++) {
                final int index = i;
                NotificationSchedule.Rule rule = rules.get(i);
                TextDetailSettingsCell cell = new TextDetailSettingsCell(activity);
                cell.setBackground(Theme.getSelectorDrawable(false));
                cell.setTextAndValue(summary(rule), LocaleController.getString(NotificationProfiles.labelRes(rule.profile)), false);
                // the cell has no resources-provider constructor, so a chat theme's colors are applied here
                cell.getTextView().setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
                cell.getValueTextView().setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, rp));
                cell.setOnClickListener(v -> edit(activity, rp, rule, saved -> {
                    List<NotificationSchedule.Rule> next = new ArrayList<>(rules);
                    next.set(index, saved);
                    commit.run(next);
                }, () -> {
                    List<NotificationSchedule.Rule> next = new ArrayList<>(rules);
                    next.remove(index);
                    commit.run(next);
                }));
                layout.addView(cell);
            }

            if (rules.size() < NotificationSchedule.MAX_RULES) {
                TextSettingsCell add = new TextSettingsCell(activity, rp);
                add.setBackground(Theme.getSelectorDrawable(false));
                add.setText(LocaleController.getString(R.string.NaxNotifScheduleAdd), false);
                add.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, rp));
                add.setOnClickListener(v -> edit(activity, rp, null, saved -> {
                    List<NotificationSchedule.Rule> next = new ArrayList<>(rules);
                    next.add(saved);
                    commit.run(next);
                }, null));
                layout.addView(add);
            }
        };
        refresh[0].run();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle(LocaleController.getString(R.string.NaxNotifScheduleTitle));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Done), null);
        builder.setOnDismissListener(d -> {
            if (onClosed != null) onClosed.run();
        });
        builder.show();
    }

    // A new rule starts as a nightly Passive window the user then adjusts. Muted isn't offered: it is Telegram's server-side
    // mute, and nothing runs at a window boundary to switch it.
    private static void edit(Activity activity, Theme.ResourcesProvider rp, NotificationSchedule.Rule rule,
                             Utilities.Callback<NotificationSchedule.Rule> onSave, Runnable onDelete) {
        final int[] days = {rule != null ? rule.days : NotificationSchedule.ALL_DAYS};
        final int[] start = {rule != null ? rule.start : 22 * 60};
        final int[] end = {rule != null ? rule.end : 7 * 60};
        final int[] profile = {rule != null ? rule.profile : NotificationProfiles.PASSIVE};

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);

        LinearLayout chips = new LinearLayout(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] chip = new TextView[7];
        AlertDialog[] dialog = new AlertDialog[1];
        Runnable styleChips = () -> {
            for (int i = 0; i < 7; i++) {
                boolean on = (days[0] & (1 << i)) != 0;
                chip[i].setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(8), Theme.getColor(on ? Theme.key_featuredStickers_addButton : Theme.key_graySection, rp)));
                chip[i].setTextColor(Theme.getColor(on ? Theme.key_featuredStickers_buttonText : Theme.key_dialogTextBlack, rp));
                chip[i].setSelected(on);
            }
            if (dialog[0] != null) {
                View save = dialog[0].getButton(AlertDialog.BUTTON_POSITIVE);
                if (save != null) {
                    save.setEnabled(days[0] != 0);
                    save.setAlpha(days[0] != 0 ? 1f : 0.5f);
                }
            }
        };
        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            TextView t = new TextView(activity);
            t.setText(dayName(i));
            t.setTextSize(12);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
            t.setOnClickListener(v -> {
                days[0] ^= bit;
                styleChips.run();
            });
            chip[i] = t;
            chips.addView(t, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 2, 0, 2, 0));
        }
        layout.addView(chips, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 4, 14, 8));

        TextSettingsCell startCell = new TextSettingsCell(activity, rp);
        TextSettingsCell endCell = new TextSettingsCell(activity, rp);
        Runnable times = () -> {
            startCell.setTextAndValue(LocaleController.getString(R.string.NaxNotifScheduleStart), OpeningHoursActivity.Period.timeToString(start[0], false), false);
            endCell.setTextAndValue(LocaleController.getString(R.string.NaxNotifScheduleEnd), endText(start[0], end[0]), false);
        };
        times.run();
        startCell.setBackground(Theme.getSelectorDrawable(false));
        endCell.setBackground(Theme.getSelectorDrawable(false));
        startCell.setOnClickListener(v -> pickTime(activity, R.string.NaxNotifScheduleStart, start, times));
        endCell.setOnClickListener(v -> pickTime(activity, R.string.NaxNotifScheduleEnd, end, times));
        layout.addView(startCell);
        layout.addView(endCell);

        int[] order = {NotificationProfiles.LOUD, NotificationProfiles.PASSIVE};
        RadioColorCell[] radios = new RadioColorCell[order.length];
        for (int i = 0; i < order.length; i++) {
            final int p = order[i];
            radios[i] = NotificationProfilePicker.radioCell(activity, rp, p, profile[0] == p);
            radios[i].setOnClickListener(v -> {
                profile[0] = p;
                for (int j = 0; j < order.length; j++) radios[j].setChecked(order[j] == p, true);
            });
            layout.addView(radios[i]);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle(LocaleController.getString(R.string.NaxNotifScheduleRule));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            if (days[0] != 0) onSave.run(new NotificationSchedule.Rule(days[0], start[0], end[0], profile[0]));
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (onDelete != null) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete), (d, w) -> onDelete.run());
        }
        dialog[0] = builder.show();
        styleChips.run();
    }

    private static void pickTime(Activity activity, int titleRes, int[] value, Runnable done) {
        // the helper shows its own sheet and reports the pick when that sheet is dismissed
        AlertsCreator.createTimePickerDialog(activity, LocaleController.getString(titleRes), value[0], 0, 1439, minutes -> {
            value[0] = Math.max(0, Math.min(1439, minutes));
            done.run();
        });
    }

    // An end that is not after the start belongs to the next day (equal times make a full 24 hours).
    private static String endText(int start, int end) {
        String text = OpeningHoursActivity.Period.timeToString(end, false);
        return end <= start ? LocaleController.formatString(R.string.BusinessHoursNextDayPicker, text) : text;
    }

    private static String dayName(int index) {
        String name = DayOfWeek.of(index + 1).getDisplayName(TextStyle.SHORT, LocaleController.getInstance().getCurrentLocale());
        return name.isEmpty() ? name : name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    // "Mon–Fri · 22:00 – Next day, 07:00": runs of three or more days collapse to a range.
    private static String summary(NotificationSchedule.Rule rule) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < 7) {
            if ((rule.days & (1 << i)) == 0) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < 7 && (rule.days & (1 << (j + 1))) != 0) j++;
            if (sb.length() > 0) sb.append(", ");
            if (j - i >= 2) {
                sb.append(dayName(i)).append('–').append(dayName(j));
            } else {
                for (int k = i; k <= j; k++) {
                    if (k > i) sb.append(", ");
                    sb.append(dayName(k));
                }
            }
            i = j + 1;
        }
        return sb + " · " + OpeningHoursActivity.Period.timeToString(rule.start, false) + " – " + endText(rule.start, rule.end);
    }
}
