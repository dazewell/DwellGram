package com.dazewell.gram.reschedule;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;

import java.util.Calendar;

/**
 * Hook for the "Reschedule selected" spreadsheet
 * ({@code AlertsCreator.createScheduleDatePickerDialog} in reschedule mode):
 * injects an interval [value][unit] picker row plus a live preview line under
 * the day/hour/minute pickers. The selected base time becomes the first
 * message's new schedule time; each following message (in ascending current
 * schedule order, or pick order via RescheduleOrderRow) is offset by {@code interval × index}.
 *
 * <p>Follows the injection convention of
 * {@link com.dazewell.gram.chattimezone.ChatTimeZoneScheduleHelper}: appends
 * children to the sheet's vertical {@code container} and exposes an updater.
 */
public final class RescheduleSpreadHelper {

    /**
     * Telegram only allows scheduling up to ~1 year ahead (the day picker maxes at 365).
     */
    private static final long MAX_SCHEDULE_DAYS = 365L;

    private static final int UNIT_MINUTES = 0;
    private static final int UNIT_HOURS = 1;
    private static final int UNIT_DAYS = 2;

    private RescheduleSpreadHelper() {
    }

    public interface IntervalControls {
        /**
         * The currently selected offset between consecutive messages, in seconds.
         */
        int getIntervalSeconds();

        /**
         * Whether the resulting spread fits within the 1-year scheduling limit.
         */
        boolean isValid();

        /**
         * Recompute the preview line and button state from the current picker values.
         */
        void update();
    }

    /**
     * Appends the interval picker row + preview line to {@code container} and
     * returns the controls. Should only be called when {@code messageCount >= 2}
     * (a single message has nothing to spread).
     *
     * @param buttonTextView the confirm button; disabled while the spread overflows the limit.
     */
    public static IntervalControls addIntervalControls(Context context, LinearLayout container,
                                                       int messageCount, int textColor,
                                                       NumberPicker dayPicker, NumberPicker hourPicker, NumberPicker minutePicker,
                                                       TextView buttonTextView, Theme.ResourcesProvider resourcesProvider) {
        return addIntervalControls(context, container, messageCount, messageCount, textColor,
                dayPicker, hourPicker, minutePicker, buttonTextView, resourcesProvider);
    }

    // NagramX: #repost-spread reuses this row but a slot is not a message - an album is several
    // messages that go out as one scheduled send. spanCount is the number of sends (slots), which
    // drives how far the spread reaches; displayCount is the message total shown in the preview
    // label. Reschedule passes the same value for both (one message = one slot).
    public static IntervalControls addIntervalControls(Context context, LinearLayout container,
                                                       int spanCount, int displayCount, int textColor,
                                                       NumberPicker dayPicker, NumberPicker hourPicker, NumberPicker minutePicker,
                                                       TextView buttonTextView, Theme.ResourcesProvider resourcesProvider) {
        final TextView caption = new TextView(context);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        caption.setTextColor(Theme.multAlpha(textColor, 0.75f));
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setText(getString(R.string.RescheduleInterval));
        container.addView(caption, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 6, 22, 0));

        final NumberPicker valuePicker = new NumberPicker(context, resourcesProvider);
        valuePicker.setTextColor(textColor);
        valuePicker.setItemCount(3);
        final NumberPicker unitPicker = new NumberPicker(context, resourcesProvider);
        unitPicker.setTextColor(textColor);
        unitPicker.setItemCount(3);

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(1.0f);
        container.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));

        // Unit picker: Minutes / Hours / Days. One minute is the floor, but Telegram's scheduler
        // is best-effort: sends can run minutes late, and a catch-up batch appears to go out in
        // queue-id order rather than by schedule_date. Short intervals therefore can't guarantee
        // the send order the user set up; the hint line below warns under 3 minutes.
        unitPicker.setMinValue(0);
        unitPicker.setMaxValue(2);
        unitPicker.setWrapSelectorWheel(false);
        unitPicker.setFormatter(value -> switch (value) {
            case UNIT_HOURS -> getString(R.string.RescheduleUnitHours);
            case UNIT_DAYS -> getString(R.string.RescheduleUnitDays);
            default -> getString(R.string.RescheduleUnitMinutes);
        });
        unitPicker.setValue(UNIT_MINUTES);

        // Value picker: range depends on the chosen unit.
        valuePicker.setWrapSelectorWheel(false);
        valuePicker.setFormatter(value -> String.valueOf(value));
        configureValuePicker(valuePicker, UNIT_MINUTES);

        row.addView(valuePicker, LayoutHelper.createLinear(0, 54 * 3, 0.4f));
        row.addView(unitPicker, LayoutHelper.createLinear(0, 54 * 3, 0.6f));

        final TextView preview = new TextView(context);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        preview.setGravity(Gravity.CENTER_HORIZONTAL);
        preview.setPadding(dp(22), 0, dp(22), 0);
        container.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 2));

        final TextView shortIntervalHint = new TextView(context);
        shortIntervalHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        shortIntervalHint.setGravity(Gravity.CENTER_HORIZONTAL);
        shortIntervalHint.setPadding(dp(22), 0, dp(22), 0);
        shortIntervalHint.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        shortIntervalHint.setText(getString(R.string.RescheduleShortIntervalHint));
        shortIntervalHint.setVisibility(android.view.View.GONE);
        container.addView(shortIntervalHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        final IntervalControlsImpl controls = new IntervalControlsImpl(
                spanCount, displayCount, textColor, preview, shortIntervalHint, buttonTextView,
                valuePicker, unitPicker, dayPicker, hourPicker, minutePicker);

        unitPicker.setOnValueChangedListener((picker, oldVal, newVal) -> {
            configureValuePicker(valuePicker, newVal);
            controls.update();
        });
        valuePicker.setOnValueChangedListener((picker, oldVal, newVal) -> controls.update());

        controls.update();
        return controls;
    }

    private static void configureValuePicker(NumberPicker valuePicker, int unit) {
        final int previous = valuePicker.getValue();
        switch (unit) {
            case UNIT_HOURS:
                valuePicker.setMinValue(1);
                valuePicker.setMaxValue(23);
                valuePicker.setValue(Math.max(1, Math.min(previous, 23)));
                break;
            case UNIT_DAYS:
                valuePicker.setMinValue(1);
                valuePicker.setMaxValue(30);
                valuePicker.setValue(Math.max(1, Math.min(previous, 30)));
                break;
            case UNIT_MINUTES:
            default:
                valuePicker.setMinValue(1);
                valuePicker.setMaxValue(59);
                valuePicker.setValue(Math.max(1, Math.min(previous, 59)));
                break;
        }
        valuePicker.invalidate();
    }

    private static final class IntervalControlsImpl implements IntervalControls {
        private final int spanCount;
        private final int displayCount;
        private final int textColor;
        private final TextView preview;
        private final TextView shortIntervalHint;
        private final TextView buttonTextView;
        private final NumberPicker valuePicker;
        private final NumberPicker unitPicker;
        private final NumberPicker dayPicker;
        private final NumberPicker hourPicker;
        private final NumberPicker minutePicker;
        private boolean valid = true;

        IntervalControlsImpl(int spanCount, int displayCount, int textColor,
                             TextView preview, TextView shortIntervalHint, TextView buttonTextView,
                             NumberPicker valuePicker, NumberPicker unitPicker,
                             NumberPicker dayPicker, NumberPicker hourPicker, NumberPicker minutePicker) {
            this.spanCount = spanCount;
            this.displayCount = displayCount;
            this.textColor = textColor;
            this.preview = preview;
            this.shortIntervalHint = shortIntervalHint;
            this.buttonTextView = buttonTextView;
            this.valuePicker = valuePicker;
            this.unitPicker = unitPicker;
            this.dayPicker = dayPicker;
            this.hourPicker = hourPicker;
            this.minutePicker = minutePicker;
        }

        @Override
        public int getIntervalSeconds() {
            final int value = valuePicker.getValue();
            switch (unitPicker.getValue()) {
                case UNIT_HOURS:
                    return value * 3600;
                case UNIT_DAYS:
                    return value * 86400;
                case UNIT_MINUTES:
                default:
                    return value * 60;
            }
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public void update() {
            final long now = System.currentTimeMillis();
            final Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.add(Calendar.DAY_OF_YEAR, dayPicker.getValue());
            c.set(Calendar.HOUR_OF_DAY, hourPicker.getValue());
            c.set(Calendar.MINUTE, minutePicker.getValue());
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            final long baseMs = c.getTimeInMillis();

            final int interval = getIntervalSeconds();
            final long lastMs = baseMs + (long) (spanCount - 1) * interval * 1000L;

            final Calendar limit = Calendar.getInstance();
            limit.setTimeInMillis(now);
            limit.add(Calendar.DAY_OF_YEAR, (int) MAX_SCHEDULE_DAYS);
            valid = lastMs <= limit.getTimeInMillis();

            shortIntervalHint.setVisibility(valid && interval < 180 ? android.view.View.VISIBLE : android.view.View.GONE);

            if (!valid) {
                preview.setText(getString(R.string.RescheduleSpreadOverflow));
                preview.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                buttonTextView.setEnabled(false);
                buttonTextView.setAlpha(0.5f);
                return;
            }

            final boolean crossesDay = !sameDay(baseMs, lastMs);
            final String text = formatPluralString("RescheduleSpreadMessages", displayCount)
                    + "  ·  " + formatMoment(baseMs, crossesDay)
                    + "  →  " + formatMoment(lastMs, crossesDay);
            preview.setText(text);
            preview.setTextColor(Theme.multAlpha(textColor, 0.75f));
            buttonTextView.setEnabled(true);
            buttonTextView.setAlpha(1f);
        }

        private static boolean sameDay(long a, long b) {
            final Calendar ca = Calendar.getInstance();
            ca.setTimeInMillis(a);
            final Calendar cb = Calendar.getInstance();
            cb.setTimeInMillis(b);
            return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                    && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
        }

        private static String formatMoment(long ms, boolean withDay) {
            final String time = LocaleController.getInstance().getFormatterDay().format(ms);
            if (withDay) {
                return LocaleController.getInstance().getFormatterScheduleDay().format(ms) + " " + time;
            }
            return time;
        }
    }
}
