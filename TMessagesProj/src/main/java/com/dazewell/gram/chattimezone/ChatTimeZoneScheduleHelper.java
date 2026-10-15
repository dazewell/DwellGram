package com.dazewell.gram.chattimezone;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Hook for the schedule-message date picker
 * ({@code AlertsCreator.createScheduleDatePickerDialog}) when the dialog has a
 * custom time zone configured (see {@link ChatTimeZoneController}).
 *
 * <p>Two things are installed under the day/hour/minute wheels:
 * <ul>
 *   <li>a two-segment "My time / Peer's time" tab above the wheels that switches
 *       the wheels' reference frame while editing the same absolute instant, and
 *   <li>a readout line below the wheels that always shows the opposite (counterparty)
 *       side, e.g. "Alex's time: Wed, Jul 24 03:30".
 * </ul>
 *
 * <p>The stock sheet bakes device-local semantics into
 * {@code AlertsCreator.checkScheduleDate}, the day formatter and the confirm read.
 * While the "Peer's time" tab is active the wheels hold PEER wall-clock, so this
 * helper owns validation (stock {@code checkScheduleDate} is bypassed) and the day
 * formatter. {@link Controls#snapToLocal()} converts the wheels back to device-local
 * before any stock code reads them, so the scheduled instant is identical no matter
 * which tab is active on confirm.
 *
 * <p>The tab only appears for a real, differing zone on a non-reschedule sheet.
 * Reschedule sheets keep the previous behaviour: local-only wheels plus the readout.
 */
public final class ChatTimeZoneScheduleHelper {

    private ChatTimeZoneScheduleHelper() {}

    /**
     * Builds the tab and/or readout for the schedule sheet.
     *
     * @param container  the vertical sheet container
     * @param wheelsRow  the horizontal row holding the wheels (the tab is
     *                   inserted directly above it)
     * @param type       the button-label type stock {@code checkScheduleDate} uses
     * @param button     the confirm button, or {@code null} when its label must not
     *                   be rewritten (reschedule)
     * @return a {@link Controls} handle, or {@code null} when the dialog has no
     *         configured zone or the zone matches the device's (caller keeps stock
     *         behaviour).
     */
    @Nullable
    public static Controls install(Context context, LinearLayout container, LinearLayout wheelsRow,
                                   int currentAccount, long dialogId, boolean isReschedule, int type,
                                   int textColor, int accentColor,
                                   NumberPicker dayPicker, NumberPicker hourPicker, NumberPicker minutePicker,
                                   @Nullable TextView button) {
        final TimeZone tz = ChatTimeZoneController.getForDialog(currentAccount, dialogId);
        if (tz == null || ChatTimeZoneRenderer.sameAsLocal(tz)) {
            return null;
        }
        String name = ChatTimeZoneController.getDialogName(currentAccount, dialogId);
        if (TextUtils.isEmpty(name)) {
            name = LocaleController.getString(R.string.ChatTimeZone);
        }
        return new Controls(context, container, wheelsRow, tz, name, isReschedule, type,
                textColor, accentColor, dayPicker, hourPicker, minutePicker, button);
    }

    /**
     * Live handle over the tab + readout. The caller keeps stock scheduling math
     * except where these methods take over (peer mode only).
     */
    public static final class Controls {

        // Matches the schedule sheet's stock day-picker max (AlertsCreator sets dayPicker max to 365).
        private static final int LOCAL_MAX_DAY = 365;

        private final TimeZone tz;
        private final String peerName;
        private final boolean isReschedule;
        private final int type;
        private final int textColor;
        private final int accentColor;
        private final NumberPicker dayPicker, hourPicker, minutePicker;
        @Nullable private final TextView button;

        private final TextView readout;
        private final NumberPicker.Formatter localDayFormatter;
        private final NumberPicker.Formatter peerDayFormatter;

        // Two-segment tab (absent in reschedule mode).
        @Nullable private TextView segMyTime;
        @Nullable private TextView segPeerTime;

        private boolean peerMode; // false = My time (wheels device-local), true = Peer's time (wheels peer wall-clock)
        @Nullable private Runnable onModeChanged;

        private Controls(Context context, LinearLayout container, LinearLayout wheelsRow,
                         TimeZone tz, String peerName, boolean isReschedule, int type,
                         int textColor, int accentColor,
                         NumberPicker dayPicker, NumberPicker hourPicker, NumberPicker minutePicker,
                         @Nullable TextView button) {
            this.tz = tz;
            this.peerName = peerName;
            this.isReschedule = isReschedule;
            this.type = type;
            this.textColor = textColor;
            this.accentColor = accentColor;
            this.dayPicker = dayPicker;
            this.hourPicker = hourPicker;
            this.minutePicker = minutePicker;
            this.button = button;

            this.localDayFormatter = buildDayFormatter(null);
            this.peerDayFormatter = buildDayFormatter(tz);

            if (!isReschedule) {
                final LinearLayout tab = buildTab(context);
                container.addView(tab, container.indexOfChild(wheelsRow),
                        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 2, 22, 10));
            }

            readout = new TextView(context);
            readout.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            readout.setTextColor(Theme.multAlpha(textColor, 0.75f));
            readout.setGravity(Gravity.CENTER_HORIZONTAL);
            readout.setSingleLine(true);
            readout.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            container.addView(readout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 4));

            updateTabUi();
            renderReadout();
        }

        public boolean isPeerMode() {
            return peerMode;
        }

        /** The zone the wheels are in: the peer's on the "Peer's time" tab, {@code null} (device) otherwise. */
        @Nullable
        public TimeZone getActiveZone() {
            return peerMode ? tz : null;
        }

        /** Runs after the user switches tabs, once the wheels hold the same instant in the new zone. */
        public void setOnModeChanged(@Nullable Runnable onModeChanged) {
            this.onModeChanged = onModeChanged;
        }

        /** The instant the wheels are on, read in whichever zone the active tab puts them in. */
        public long getSelectedInstant() {
            return peerMode ? instantFromPeerWheels() : instantFromLocalWheels();
        }

        /**
         * Re-renders the readout, and in peer mode also runs the peer-zone
         * validation the caller skipped. Call after a wheel value may have changed.
         */
        public void onPickerChanged(@Nullable TextView button) {
            if (peerMode) {
                peerValidate(button);
            }
            renderReadout();
        }

        /**
         * The default-schedule slider writes device-local values straight onto the
         * wheels. In peer mode those must be re-expressed as peer wall-clock (same
         * instant) so the frame stays consistent; in local mode this is just a readout.
         */
        public void onDefaultScheduleChanged(@Nullable TextView button) {
            if (peerMode) {
                long instant = instantFromLocalWheels();
                resetMins();
                setWheelsFromInstant(instant, tz);
                peerValidate(button);
            }
            renderReadout();
        }

        /**
         * Converts the wheels from peer wall-clock back to device-local (same
         * instant), restores the stock day formatter and clears peer mode. No-op
         * when already local. Called before the confirm handler reads the wheels so
         * stock scheduling math stays device-local.
         */
        public void snapToLocal() {
            if (!peerMode) {
                return;
            }
            long instant = instantFromPeerWheels();
            peerMode = false;
            dayPicker.setFormatter(localDayFormatter);
            resetMins();
            dayPicker.setMaxValue(LOCAL_MAX_DAY); // restore the stock max in case peer mode raised it
            setWheelsFromInstant(instant, null);
            updateTabUi();
        }

        // ---------- tab ----------

        private LinearLayout buildTab(Context context) {
            final LinearLayout tab = new LinearLayout(context);
            tab.setOrientation(LinearLayout.HORIZONTAL);
            tab.setWeightSum(2f);
            final GradientDrawable track = new GradientDrawable();
            track.setCornerRadius(dp(10));
            track.setColor(Theme.multAlpha(textColor, 0.08f));
            tab.setBackground(track);
            tab.setPadding(dp(3), dp(3), dp(3), dp(3));

            segMyTime = buildSegment(context, LocaleController.getString(R.string.ChatTimeZoneScheduleMyTime));
            segPeerTime = buildSegment(context, LocaleController.getString(R.string.ChatTimeZoneSchedulePeerTime));
            segMyTime.setOnClickListener(v -> selectPeerMode(false));
            segPeerTime.setOnClickListener(v -> selectPeerMode(true));

            tab.addView(segMyTime, LayoutHelper.createLinear(0, 30, 1f));
            tab.addView(segPeerTime, LayoutHelper.createLinear(0, 30, 1f));
            return tab;
        }

        private TextView buildSegment(Context context, String text) {
            final TextView tv = new TextView(context);
            tv.setText(text);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            return tv;
        }

        private void updateTabUi() {
            if (segMyTime == null || segPeerTime == null) {
                return;
            }
            styleSegment(segMyTime, !peerMode);
            styleSegment(segPeerTime, peerMode);
        }

        private void styleSegment(TextView seg, boolean selected) {
            if (selected) {
                final GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(8));
                bg.setColor(Theme.multAlpha(accentColor, 0.18f));
                seg.setBackground(bg);
                seg.setTextColor(accentColor);
                seg.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
            } else {
                seg.setBackground(null);
                seg.setTextColor(Theme.multAlpha(textColor, 0.6f));
                seg.setTypeface(android.graphics.Typeface.DEFAULT);
            }
        }

        private void selectPeerMode(boolean toPeer) {
            if (toPeer == peerMode) {
                return;
            }
            if (toPeer) {
                enterPeerMode();
            } else {
                snapToLocal();
                // Leaving peer mode hands the wheels back to stock scheduling math.
                AlertsCreator.checkScheduleDate(button, null, 0, 0, type, dayPicker, hourPicker, minutePicker);
                renderReadout();
            }
            if (onModeChanged != null) {
                onModeChanged.run();
            }
        }

        private void enterPeerMode() {
            long instant = instantFromLocalWheels();
            peerMode = true;
            dayPicker.setFormatter(peerDayFormatter);
            resetMins();
            // Bound the day wheel to the peer-zone offset of the sheet's max instant so the current
            // instant is always representable (a zone ahead can push it to day 366) without silent clamping.
            dayPicker.setMaxValue(peerMaxDayOffset());
            setWheelsFromInstant(instant, tz);
            peerValidate(button);
            updateTabUi();
            renderReadout();
        }

        /** Peer-zone day offset of the latest schedulable instant (local today + max days at 23:59). */
        private int peerMaxDayOffset() {
            Calendar max = Calendar.getInstance();
            max.setTimeInMillis(System.currentTimeMillis());
            max.add(Calendar.DAY_OF_YEAR, LOCAL_MAX_DAY);
            max.set(Calendar.HOUR_OF_DAY, 23);
            max.set(Calendar.MINUTE, 59);
            max.set(Calendar.SECOND, 0);
            max.set(Calendar.MILLISECOND, 0);
            final ZoneId zid = tz.toZoneId();
            LocalDate peerToday = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zid).toLocalDate();
            LocalDate peerMax = Instant.ofEpochMilli(max.getTimeInMillis()).atZone(zid).toLocalDate();
            return (int) ChronoUnit.DAYS.between(peerToday, peerMax);
        }

        // ---------- wheel <-> instant conversion ----------

        private void resetMins() {
            dayPicker.setMinValue(0);
            hourPicker.setMinValue(0);
            minutePicker.setMinValue(0);
        }

        private long instantFromLocalWheels() {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(System.currentTimeMillis());
            c.add(Calendar.DAY_OF_YEAR, dayPicker.getValue());
            c.set(Calendar.HOUR_OF_DAY, hourPicker.getValue());
            c.set(Calendar.MINUTE, minutePicker.getValue());
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }

        private long instantFromPeerWheels() {
            Calendar c = Calendar.getInstance(tz);
            c.setTimeInMillis(System.currentTimeMillis());
            c.add(Calendar.DAY_OF_YEAR, dayPicker.getValue());
            c.set(Calendar.HOUR_OF_DAY, hourPicker.getValue());
            c.set(Calendar.MINUTE, minutePicker.getValue());
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }

        /** Sets the wheels to represent {@code instant} in {@code zone} (device zone when null). */
        private void setWheelsFromInstant(long instant, @Nullable TimeZone zone) {
            final TimeZone z = zone != null ? zone : TimeZone.getDefault();
            final ZoneId zid = z.toZoneId();
            Calendar c = Calendar.getInstance(z);
            c.setTimeInMillis(instant);
            LocalDate today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zid).toLocalDate();
            LocalDate target = Instant.ofEpochMilli(instant).atZone(zid).toLocalDate();
            int dayOffset = (int) ChronoUnit.DAYS.between(today, target);
            if (dayOffset < 0) {
                dayOffset = 0;
            }
            dayPicker.setValue(dayOffset);
            hourPicker.setValue(c.get(Calendar.HOUR_OF_DAY));
            minutePicker.setValue(c.get(Calendar.MINUTE));
        }

        // ---------- peer-mode validation (mirror of checkScheduleDate in the peer zone) ----------

        private void peerValidate(@Nullable TextView button) {
            final long systemTime = System.currentTimeMillis();
            final long minDate = 60000L; // matches checkScheduleDate's default (minDateSeconds == 0 on this sheet)

            Calendar cal = Calendar.getInstance(tz);
            cal.setTimeInMillis(systemTime + minDate);
            final int minHour = cal.get(Calendar.HOUR_OF_DAY);
            final int minMinute = cal.get(Calendar.MINUTE);

            dayPicker.setMinValue(0);
            int day = dayPicker.getValue();
            hourPicker.setMinValue(day == 0 ? minHour : 0);
            int hour = hourPicker.getValue();
            minutePicker.setMinValue(day == 0 && hour == minHour ? minMinute : 0);
            int minute = minutePicker.getValue();

            if (button != null) {
                Calendar sel = Calendar.getInstance(tz);
                sel.setTimeInMillis(systemTime);
                sel.add(Calendar.DAY_OF_YEAR, day);
                sel.set(Calendar.HOUR_OF_DAY, hour);
                sel.set(Calendar.MINUTE, minute);
                sel.set(Calendar.SECOND, 0);
                sel.set(Calendar.MILLISECOND, 0);
                setButtonLabel(button, sel.getTimeInMillis(), systemTime);
            }
        }

        /**
         * Renders the confirm-button label from the resulting absolute instant using
         * the DEVICE-local day offset and year, so the label is identical to what
         * stock {@code checkScheduleDate} would show for the same instant.
         */
        private void setButtonLabel(TextView button, long instant, long systemTime) {
            LocalDate today = Instant.ofEpochMilli(systemTime).atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDate target = Instant.ofEpochMilli(instant).atZone(ZoneId.systemDefault()).toLocalDate();
            int localDayOffset = (int) ChronoUnit.DAYS.between(today, target);
            int nowYear = today.getYear();
            int selYear = target.getYear();
            int num;
            if (localDayOffset == 0) {
                num = 0;
            } else if (nowYear == selYear) {
                num = 1;
            } else {
                num = 2;
            }
            num += type * 3;
            button.setText(LocaleController.getInstance().getFormatterScheduleSend(num).format(instant));
        }

        // ---------- readout ----------

        private void renderReadout() {
            // The readout always shows the opposite side of whatever the wheels edit.
            final long instant = peerMode ? instantFromPeerWheels() : instantFromLocalWheels();
            final Calendar edited = peerMode ? Calendar.getInstance(tz) : Calendar.getInstance();
            edited.setTimeInMillis(instant);
            final Calendar opposite = peerMode ? Calendar.getInstance() : Calendar.getInstance(tz);
            opposite.setTimeInMillis(instant);

            final boolean withDate = ChatTimeZoneRenderer.compareDay(opposite, edited) != 0;
            final String value = ChatTimeZoneRenderer.formatSide(opposite, null, withDate);

            final String full = peerMode
                    ? LocaleController.formatString(R.string.ChatTimeZoneScheduleYourTime, value)
                    : LocaleController.formatString(R.string.ChatTimeZoneScheduleTime, peerName, value);

            SpannableStringBuilder ssb = new SpannableStringBuilder(full);
            if (withDate) {
                // Accent the counterparty date+time so a different day stands out (the old "+1d" hint).
                int start = full.indexOf(value);
                if (start >= 0) {
                    ssb.setSpan(new ForegroundColorSpan(accentColor), start, start + value.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            readout.setText(ssb);
        }

        // ---------- day formatter ----------

        /**
         * The wheel's day formatter for a given zone (device zone when {@code zone} is null). The
         * month lives in its own wheel ({@code SchedulePickerShortcuts}), so both tabs share its labels.
         */
        private NumberPicker.Formatter buildDayFormatter(@Nullable TimeZone zone) {
            return com.dazewell.gram.schedulepicker.SchedulePickerShortcuts.dayFormatter(zone);
        }
    }
}
