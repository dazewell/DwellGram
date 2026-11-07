package com.dazewell.gram.search;

/** Immutable Telegram search bounds, in Unix seconds. Zero leaves a bound open. */
public final class DmSearchCriteria {
    public static final DmSearchCriteria EMPTY = new DmSearchCriteria(0, 0);

    public final int minDate;
    public final int maxDate;

    public DmSearchCriteria(int minDate, int maxDate) {
        this.minDate = Math.max(0, minDate);
        this.maxDate = Math.max(0, maxDate);
        if (this.maxDate != 0 && this.minDate > this.maxDate) {
            throw new IllegalArgumentException("Inverted search bounds");
        }
    }
}
