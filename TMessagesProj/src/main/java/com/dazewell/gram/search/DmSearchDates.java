package com.dazewell.gram.search;

import java.util.Calendar;

public final class DmSearchDates {
    private DmSearchDates() {}

    public static int boundary(Calendar date, boolean end) {
        if (date == null) return 0;
        Calendar day = (Calendar) date.clone();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        // Advancing the local calendar preserves short and long daylight-saving days.
        if (end) day.add(Calendar.DAY_OF_MONTH, 1);
        long seconds = day.getTimeInMillis() / 1000L - (end ? 1 : 0);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, seconds));
    }
}
