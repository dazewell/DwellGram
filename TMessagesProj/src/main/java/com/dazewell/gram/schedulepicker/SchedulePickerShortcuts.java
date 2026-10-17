package com.dazewell.gram.schedulepicker;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.dazewell.gram.chattimezone.ChatTimeZoneScheduleHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;

import tw.nekomimi.nekogram.helpers.ScheduleTimeHelper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Faster ways around the schedule sheet's day / hour / minute wheels
 * ({@code AlertsCreator.createScheduleDatePickerDialog}):
 * <ul>
 *   <li>a month wheel left of the day wheel, which moves the selected date by whole months and
 *       keeps the day of month and the time,
 *   <li>{@code :00 :30 :59} pills right of the minute wheel, and
 *   <li>a row of pills above the wheels for the upcoming midnight, noon, other round hours and the
 *       next top of the hour, over the next 24 hours.
 * </ul>
 * Everything is computed in the zone the wheels are in: the peer's on the "Peer's time" tab, the
 * device's otherwise. Every write resets the wheel minimums first (stock validation raises them on
 * Today, and {@link NumberPicker#setValue} clamps to them) and then calls the sheet's own wheel
 * listener, so validation, the confirm label, the tz readout and Remember run exactly as for a flick.
 */
public final class SchedulePickerShortcuts {

    private static final int[] ANCHOR_HOURS = {0, 6, 9, 12, 18, 21};
    private static final int[] MINUTE_PILLS = {0, 30, 59};
    // checkScheduleDate's floor when the sheet passes no lead of its own.
    private static final long DEFAULT_LEAD_MS = 60_000L;
    private static final long WINDOW_MS = 24 * 60 * 60_000L;
    // Below this the sheet (tab, wheels, slider, Remember, event chip, button) has no room for
    // another row; it does not scroll, so an extra row would clip the title instead.
    private static final int MIN_HEIGHT_FOR_TIME_PILLS_DP = 680;

    private final AlertsCreator.ScheduleDatePickerColors colors;
    @Nullable private final ChatTimeZoneScheduleHelper.Controls tz;
    private final long leadMs;
    private final NumberPicker dayPicker, hourPicker, minutePicker, monthPicker;
    private final NumberPicker.OnValueChangeListener onChanged;

    @Nullable private final LinearLayout timePillsRow;
    private final ArrayList<TextView> timePills = new ArrayList<>();
    private final ArrayList<Long> timePillInstants = new ArrayList<>();
    private final TextView[] minutePills = new TextView[MINUTE_PILLS.length];

    // Day of month the month wheel aims for, so stepping Jan 31 -> Feb -> Mar lands on Mar 31.
    private int anchorDayOfMonth;
    private boolean writing;
    private boolean monthWrite;

    private SchedulePickerShortcuts(Context context, LinearLayout container, LinearLayout wheelsRow,
                                    @Nullable ChatTimeZoneScheduleHelper.Controls tz, boolean showTimePills,
                                    long minLeadSeconds, AlertsCreator.ScheduleDatePickerColors colors,
                                    Theme.ResourcesProvider resourcesProvider,
                                    NumberPicker dayPicker, NumberPicker hourPicker, NumberPicker minutePicker,
                                    NumberPicker.OnValueChangeListener onChanged) {
        this.colors = colors;
        this.tz = tz;
        this.leadMs = Math.max(minLeadSeconds * 1000L, DEFAULT_LEAD_MS);
        this.dayPicker = dayPicker;
        this.hourPicker = hourPicker;
        this.minutePicker = minutePicker;
        this.onChanged = onChanged;

        monthPicker = new NumberPicker(context, 16, resourcesProvider) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // The container's onMeasure sizes the three stock wheels for portrait/landscape
                // before measuring this row; follow the day wheel so all four stay the same height.
                if (getItemsCount() != dayPicker.getItemsCount()) {
                    setItemCount(dayPicker.getItemsCount());
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dayPicker.getLayoutParams().height, MeasureSpec.EXACTLY));
            }

            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                // The wheel's 16dp text rounds its selection lines a pixel or two off the 18dp
                // wheels beside it, so the lines step at the seam. Draw them where the day wheel does.
                copySelectionDividersFrom(dayPicker);
                super.onDraw(canvas);
            }

            @Override
            protected CharSequence getContentDescription(int value) {
                return monthFor(value).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale()));
            }
        };
        monthPicker.setTextColor(colors.textColor);
        monthPicker.setWrapSelectorWheel(false);
        monthPicker.setMinValue(0);
        monthPicker.setMaxValue(maxMonthIndex());
        monthPicker.setFormatter(this::monthLabel);
        monthPicker.setOnValueChangedListener((picker, oldVal, newVal) -> onMonthPicked(newVal));

        // The month moves out of the day labels into its own wheel, which frees the width it needs.
        dayPicker.setFormatter(dayFormatter(null));
        dayPicker.setContentDescriptionCallback(this::dayDescription);
        dayPicker.setTextOffset(0);
        hourPicker.setTextOffset(dp(6));
        minutePicker.setTextOffset(-dp(6));
        setWeight(dayPicker, 0.30f);
        setWeight(hourPicker, 0.18f);
        setWeight(minutePicker, 0.18f);
        wheelsRow.addView(monthPicker, 0, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 0.18f));
        wheelsRow.addView(buildMinutePills(context), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 0.16f, Gravity.CENTER_VERTICAL));

        if (showTimePills) {
            final HorizontalScrollView scroll = new HorizontalScrollView(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    if (!roomForTimePills()) {
                        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0);
                        return;
                    }
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                }
            };
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setPadding(0, 0, 0, dp(6));
            scroll.setClipToPadding(false);
            timePillsRow = new LinearLayout(context);
            timePillsRow.setOrientation(LinearLayout.HORIZONTAL);
            scroll.addView(timePillsRow, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START));
            // Inserted at the wheels' index, which puts it under the tz tab when there is one.
            container.addView(scroll, container.indexOfChild(wheelsRow), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            timePillsRow = null;
        }

        // Fires once per settled value change, programmatic ones included (NumberPicker.setValueInternal),
        // so it also sees writes from stock validation, the delay slider and the tz tab.
        final NumberPicker.OnScrollListener settled = (view, state) -> {
            if (state != NumberPicker.OnScrollListener.SCROLL_STATE_IDLE || writing) {
                return;
            }
            if (view == dayPicker) {
                syncMonth();
            }
            refresh();
        };
        dayPicker.setOnScrollListener(settled);
        hourPicker.setOnScrollListener(settled);
        minutePicker.setOnScrollListener(settled);

        if (tz != null) {
            tz.setOnModeChanged(() -> {
                // A new formatter instance drops the wheel's label cache, which an unchanged value
                // and max would otherwise keep from the other zone.
                monthPicker.setFormatter(this::monthLabel);
                syncMonth();
                rebuildTimePills();
                refresh();
            });
        }

        syncMonth();
        rebuildTimePills();
        refresh();
    }

    /**
     * @param tz            the sheet's tz-tab handle, or {@code null} when the chat has no differing zone
     * @param showTimePills false on sheets whose own rows already use the height (bulk reschedule, spread)
     * @param onChanged     the sheet's wheel listener; called after every write, as a flick would
     */
    public static void install(Context context, LinearLayout container, LinearLayout wheelsRow,
                               @Nullable ChatTimeZoneScheduleHelper.Controls tz, boolean showTimePills,
                               long minLeadSeconds, AlertsCreator.ScheduleDatePickerColors colors,
                               Theme.ResourcesProvider resourcesProvider,
                               NumberPicker dayPicker, NumberPicker hourPicker, NumberPicker minutePicker,
                               NumberPicker.OnValueChangeListener onChanged) {
        new SchedulePickerShortcuts(context, container, wheelsRow, tz, showTimePills, minLeadSeconds,
                colors, resourcesProvider, dayPicker, hourPicker, minutePicker, onChanged);
    }

    /**
     * The day wheel's labels once the month has its own wheel: "Today", then "Fri 16". Reads the
     * given zone's wall-clock date ({@code FastDateFormat.format(Calendar)} uses the calendar's own
     * fields); device zone when {@code zone} is null.
     */
    public static NumberPicker.Formatter dayFormatter(@Nullable TimeZone zone) {
        final TimeZone z = zone != null ? zone : TimeZone.getDefault();
        return value -> {
            if (value == 0) {
                return LocaleController.getString(R.string.MessageScheduleToday);
            }
            final Calendar c = Calendar.getInstance(z);
            c.add(Calendar.DAY_OF_YEAR, value);
            return LocaleController.getInstance().getFormatterWeek().format(c) + " " + c.get(Calendar.DAY_OF_MONTH);
        };
    }

    // ---------- zone and selection ----------

    private TimeZone zone() {
        final TimeZone active = tz != null ? tz.getActiveZone() : null;
        return active != null ? active : TimeZone.getDefault();
    }

    private long selectedInstant() {
        return tz != null
                ? tz.getSelectedInstant()
                : ScheduleTimeHelper.getTargetTimeFromPickers(dayPicker, hourPicker, minutePicker);
    }

    private LocalDate today(ZoneId zid) {
        return Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zid).toLocalDate();
    }

    /** Writes {@code instant} onto the wheels in the active zone and runs the sheet's listener. */
    private void apply(long instant) {
        final ZoneId zid = zone().toZoneId();
        final ZonedDateTime t = Instant.ofEpochMilli(instant).atZone(zid);
        int dayOffset = (int) ChronoUnit.DAYS.between(today(zid), t.toLocalDate());
        dayOffset = Math.max(0, Math.min(dayOffset, dayPicker.getMaxValue()));
        writing = true;
        try {
            dayPicker.setMinValue(0);
            hourPicker.setMinValue(0);
            minutePicker.setMinValue(0);
            dayPicker.setValue(dayOffset);
            hourPicker.setValue(t.getHour());
            minutePicker.setValue(t.getMinute());
        } finally {
            writing = false;
        }
        onChanged.onValueChange(minutePicker, 0, 0);
        syncMonth();
        refresh();
    }

    // ---------- month wheel ----------

    private YearMonth monthFor(int index) {
        return YearMonth.from(today(zone().toZoneId())).plusMonths(index);
    }

    private int maxMonthIndex() {
        final LocalDate today = today(zone().toZoneId());
        return (int) ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(today.plusDays(dayPicker.getMaxValue())));
    }

    private String monthLabel(int index) {
        final YearMonth ym = monthFor(index);
        final boolean thisYear = ym.getYear() == today(zone().toZoneId()).getYear();
        return ym.format(DateTimeFormatter.ofPattern(thisYear ? "MMM" : "MMM ''yy", locale()));
    }

    private void onMonthPicked(int index) {
        final LocalDate today = today(zone().toZoneId());
        final YearMonth ym = monthFor(index);
        LocalDate target = ym.atDay(Math.min(Math.max(anchorDayOfMonth, 1), ym.lengthOfMonth()));
        if (target.isBefore(today)) {
            target = today;
        }
        final int offset = (int) Math.min(ChronoUnit.DAYS.between(today, target), dayPicker.getMaxValue());
        monthWrite = true;
        try {
            dayPicker.setValue(offset);
            // Stock validation re-raises the hour/minute floors if this landed on Today.
            onChanged.onValueChange(dayPicker, 0, 0);
        } finally {
            monthWrite = false;
        }
        refresh();
    }

    private void syncMonth() {
        if (monthWrite) {
            return;
        }
        final LocalDate selected = today(zone().toZoneId()).plusDays(dayPicker.getValue());
        anchorDayOfMonth = selected.getDayOfMonth();
        monthPicker.setMaxValue(maxMonthIndex());
        monthPicker.setValue((int) ChronoUnit.MONTHS.between(YearMonth.from(today(zone().toZoneId())), YearMonth.from(selected)));
    }

    private CharSequence dayDescription(int value) {
        final Calendar c = Calendar.getInstance(zone());
        c.add(Calendar.DAY_OF_YEAR, value);
        return LocaleController.getInstance().getFormatterWeek().format(c) + ", "
                + LocaleController.getInstance().getFormatterScheduleYear().format(c);
    }

    // ---------- minute pills ----------

    private LinearLayout buildMinutePills(Context context) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        for (int i = 0; i < MINUTE_PILLS.length; i++) {
            final int minute = MINUTE_PILLS[i];
            final TextView pill = buildPill(context, String.format(Locale.US, ":%02d", minute), 13, 10, 5);
            pill.setContentDescription(LocaleController.formatString(R.string.ScheduleMinutePillDescr, String.format(Locale.US, "%02d", minute)));
            pill.setOnClickListener(v -> onMinutePicked(minute));
            minutePills[i] = pill;
            column.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, i == 0 ? 0 : 6, 0, 0));
        }
        return column;
    }

    private void onMinutePicked(int minute) {
        ZonedDateTime t = Instant.ofEpochMilli(selectedInstant()).atZone(zone().toZoneId()).withMinute(minute);
        // ":00" late in the hour means the next one, not a time stock validation would push forward.
        if (t.toInstant().toEpochMilli() <= System.currentTimeMillis() + leadMs) {
            t = t.plusHours(1);
        }
        apply(t.toInstant().toEpochMilli());
    }

    // ---------- upcoming-time pills ----------

    private boolean roomForTimePills() {
        return AndroidUtilities.displaySize.x <= AndroidUtilities.displaySize.y
                && AndroidUtilities.displaySize.y >= dp(MIN_HEIGHT_FOR_TIME_PILLS_DP);
    }

    private void rebuildTimePills() {
        if (timePillsRow == null) {
            return;
        }
        final Context context = timePillsRow.getContext();
        timePillsRow.removeAllViews();
        timePills.clear();
        timePillInstants.clear();

        final ZoneId zid = zone().toZoneId();
        final long now = System.currentTimeMillis();
        final long earliest = now + leadMs;
        final long latest = now + WINDOW_MS;
        final LocalDate today = today(zid);

        // Keyed by instant: the next top of the hour often is one of the anchors.
        final TreeMap<Long, ZonedDateTime> points = new TreeMap<>();
        final ZonedDateTime nextHour = Instant.ofEpochMilli(earliest).atZone(zid).truncatedTo(ChronoUnit.HOURS).plusHours(1);
        points.put(nextHour.toInstant().toEpochMilli(), nextHour);
        for (int day = 0; day <= 2; day++) {
            for (int hour : ANCHOR_HOURS) {
                // ZonedDateTime.of moves a time inside a DST gap forward, as a wall clock would.
                final ZonedDateTime t = ZonedDateTime.of(today.plusDays(day).atTime(hour, 0), zid);
                final long ms = t.toInstant().toEpochMilli();
                if (ms > earliest && ms <= latest) {
                    points.put(ms, t);
                }
            }
        }

        final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern(LocaleController.is24HourFormat ? "HH:mm" : "h:mm a", locale());
        int i = 0;
        for (ZonedDateTime t : points.values()) {
            final long ms = t.toInstant().toEpochMilli();
            final TextView pill = buildPill(context, timeLabel(t, today, timeFormat), 14, 14, 8);
            pill.setOnClickListener(v -> onTimePicked(ms));
            timePills.add(pill);
            timePillInstants.add(ms);
            // Absolute margins, as in PrivacyProfileDurationSheet: the row's children are never
            // reversed, so a start margin would flip sides in RTL and break the line with the title.
            timePillsRow.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, i == 0 ? 22 : 8, 0, 0, 0));
            i++;
        }
        if (i > 0) {
            ((LinearLayout.LayoutParams) timePillsRow.getChildAt(i - 1).getLayoutParams()).rightMargin = dp(22);
        }
    }

    private String timeLabel(ZonedDateTime t, LocalDate today, DateTimeFormatter timeFormat) {
        final boolean onHour = t.getMinute() == 0;
        if (onHour && t.getHour() == 0) {
            // Midnight is the date boundary itself; the confirm button carries the date.
            return LocaleController.getString(R.string.ScheduleMidnight);
        }
        final boolean tomorrow = !t.toLocalDate().equals(today);
        if (onHour && t.getHour() == 12) {
            return LocaleController.getString(tomorrow ? R.string.ScheduleTomorrowNoon : R.string.ScheduleNoon);
        }
        final String time = t.format(timeFormat);
        return tomorrow ? LocaleController.formatString(R.string.ScheduleTomorrowAt, time) : time;
    }

    private void onTimePicked(long instant) {
        // The sheet may have sat open past this point; rebuild rather than let validation nudge it.
        if (instant <= System.currentTimeMillis() + leadMs) {
            rebuildTimePills();
            refresh();
            return;
        }
        apply(instant);
    }

    // ---------- styling ----------

    private TextView buildPill(Context context, String text, int textSizeDp, int padH, int padV) {
        final TextView pill = new TextView(context);
        pill.setText(text);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSizeDp);
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        pill.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        stylePill(pill, false);
        return pill;
    }

    private void stylePill(TextView pill, boolean selected) {
        // refresh() runs on every settled wheel event; rebuilding the drawable would cut the ripple.
        if (pill.getBackground() != null && pill.isSelected() == selected) {
            return;
        }
        // setSelected so TalkBack announces the state, not just the fill colour.
        pill.setSelected(selected);
        pill.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(999),
                selected ? colors.buttonBackgroundColor : Theme.multAlpha(colors.textColor, 0.08f),
                selected ? colors.buttonBackgroundPressedColor : Theme.multAlpha(colors.textColor, 0.16f)));
        pill.setTextColor(selected ? colors.buttonTextColor : colors.textColor);
    }

    private void refresh() {
        final long selected = selectedInstant();
        for (int i = 0; i < timePills.size(); i++) {
            stylePill(timePills.get(i), timePillInstants.get(i) == selected);
        }
        final int minute = minutePicker.getValue();
        for (int i = 0; i < minutePills.length; i++) {
            stylePill(minutePills[i], MINUTE_PILLS[i] == minute);
        }
    }

    private static void setWeight(View view, float weight) {
        ((LinearLayout.LayoutParams) view.getLayoutParams()).weight = weight;
    }

    private static Locale locale() {
        final Locale l = LocaleController.getInstance().getCurrentLocale();
        return l != null ? l : Locale.getDefault();
    }
}
