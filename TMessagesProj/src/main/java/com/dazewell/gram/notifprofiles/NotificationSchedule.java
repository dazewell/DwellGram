package com.dazewell.gram.notifprofiles;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

// NagramX: the rule list behind a chat's notification schedule. Pure and stateless (no Android imports, `now` and the
// time zone are parameters) so the DST and midnight maths can be run off-device. A rule is a day-of-week mask, a start
// and an end in minutes from local midnight, and a profile. A window belongs to its START day: an end that is not after
// the start crosses midnight (end == start is a full 24 hours). Instants are built from the day's calendar fields in
// the given zone, never as midnight + minutes, so wall-clock time holds across a DST change.
public final class NotificationSchedule {

    public static final int MAX_RULES = 10;
    public static final int ALL_DAYS = 0x7F;

    public static final class Rule {
        // bit 0 = Monday ... bit 6 = Sunday
        public final int days;
        public final int start;
        public final int end;
        public final int profile;

        public Rule(int days, int start, int end, int profile) {
            this.days = days;
            this.start = start;
            this.end = end;
            this.profile = profile;
        }
    }

    private interface Visitor {
        void window(long startMs, long endMs, int index);
    }

    private NotificationSchedule() {
    }

    // Tolerant: a malformed or empty rule is dropped, ranges are checked, and the list is capped, never throws.
    public static List<Rule> parse(String value) {
        List<Rule> rules = new ArrayList<>();
        if (value == null || value.isEmpty()) return rules;
        for (String part : value.split(";")) {
            String[] f = part.split(",");
            if (f.length != 4) continue;
            try {
                int days = Integer.parseInt(f[0].trim());
                int start = Integer.parseInt(f[1].trim());
                int end = Integer.parseInt(f[2].trim());
                int profile = Integer.parseInt(f[3].trim());
                if (days <= 0 || days > ALL_DAYS || start < 0 || start > 1439 || end < 0 || end > 1439
                        || profile < NotificationProfiles.LOUD || profile > NotificationProfiles.PASSIVE) {
                    continue;
                }
                // schedules only drive the profiles kept locally (Loud, Passive); a rule saved under an older numbering reads
                // as whatever its number means now, or is dropped above when it is out of range
                rules.add(new Rule(days, start, end, NotificationProfiles.clamp(profile)));
            } catch (NumberFormatException ignore) {
            }
            if (rules.size() >= MAX_RULES) break;
        }
        return rules;
    }

    public static String serialize(List<Rule> rules) {
        StringBuilder sb = new StringBuilder();
        for (Rule r : rules) {
            if (sb.length() > 0) sb.append(';');
            sb.append(r.days).append(',').append(r.start).append(',').append(r.end).append(',').append(r.profile);
        }
        return sb.toString();
    }

    // Index of the winning rule at `now`: the window that started most recently, a tie going to the later rule in the
    // list; -1 when no window is open.
    public static int activeRule(List<Rule> rules, long now, TimeZone zone) {
        final long[] bestStart = {Long.MIN_VALUE};
        final int[] best = {-1};
        forEachWindow(rules, now, -1, 0, zone, (startMs, endMs, index) -> {
            if (startMs <= now && now < endMs && (startMs > bestStart[0] || (startMs == bestStart[0] && index > best[0]))) {
                bestStart[0] = startMs;
                best[0] = index;
            }
        });
        return best[0];
    }

    // First instant after `t` at which any rule's window opens or closes; Long.MAX_VALUE when there are no rules.
    // Only windows on a rule's enabled start days count, so a Mon-Fri rule has no Saturday boundary.
    public static long nextBoundaryAfter(List<Rule> rules, long t, TimeZone zone) {
        final long[] next = {Long.MAX_VALUE};
        forEachWindow(rules, t, -1, 8, zone, (startMs, endMs, index) -> {
            if (startMs > t && startMs < next[0]) next[0] = startMs;
            if (endMs > t && endMs < next[0]) next[0] = endMs;
        });
        return next[0];
    }

    // The one place the calendar maths lives: every window whose start day is within [anchor day + fromDay,
    // anchor day + toDay].
    private static void forEachWindow(List<Rule> rules, long anchorMs, int fromDay, int toDay, TimeZone zone, Visitor visitor) {
        if (rules.isEmpty()) return;
        Calendar cal = Calendar.getInstance(zone);
        cal.setTimeInMillis(anchorMs);
        cal.set(Calendar.HOUR_OF_DAY, 12);
        cal.add(Calendar.DAY_OF_YEAR, fromDay);
        int count = toDay - fromDay + 1;
        int[] year = new int[count];
        int[] month = new int[count];
        int[] day = new int[count];
        int[] bit = new int[count];
        for (int i = 0; i < count; i++) {
            year[i] = cal.get(Calendar.YEAR);
            month[i] = cal.get(Calendar.MONTH);
            day[i] = cal.get(Calendar.DAY_OF_MONTH);
            // Calendar.SUNDAY is 1, so Monday (2) -> bit 0 and Sunday (1) -> bit 6
            bit[i] = 1 << ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        for (int i = 0; i < count; i++) {
            for (int r = 0; r < rules.size(); r++) {
                Rule rule = rules.get(r);
                if ((rule.days & bit[i]) == 0) continue;
                long startMs = instant(cal, zone, year[i], month[i], day[i], rule.start);
                long endMs = instant(cal, zone, year[i], month[i], rule.end > rule.start ? day[i] : day[i] + 1, rule.end);
                visitor.window(startMs, endMs, r);
            }
        }
    }

    private static long instant(Calendar cal, TimeZone zone, int year, int month, int day, int minutes) {
        cal.setTimeZone(zone);
        cal.clear();
        cal.set(year, month, day, minutes / 60, minutes % 60, 0);
        return cal.getTimeInMillis();
    }
}
