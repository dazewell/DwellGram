package com.dazewell.gram.search;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

public final class DmSearchDates {
    private DmSearchDates() {}

    public static int daysInMonth(int year, int zeroBasedMonth) {
        if (year <= 0 || zeroBasedMonth < Calendar.JANUARY || zeroBasedMonth > Calendar.DECEMBER) return 0;
        // Validate date tuples independently of local midnight gaps and the current day of month.
        Calendar month = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        month.clear();
        month.set(year, zeroBasedMonth, 1);
        return month.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    public static boolean isValidDate(int year, int month, int day) {
        return day > 0 && day <= daysInMonth(year, month);
    }

    public static int boundary(Calendar date, boolean end) {
        if (date == null) return 0;
        Calendar day = (Calendar) date.clone();
        // Advance the civil date at noon so a midnight gap cannot carry its normalized hour forward.
        day.set(Calendar.HOUR_OF_DAY, 12);
        if (end) day.add(Calendar.DAY_OF_MONTH, 1);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        // messages.search excludes both bounds: include midnight and the day's last second.
        long seconds = day.getTimeInMillis() / 1000L - (end ? 0 : 1);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, seconds));
    }
}
