package com.dazewell.gram.composer;

import android.icu.text.BreakIterator;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import java.util.Locale;

/** Visual navigation with grapheme and inline-image boundaries for composer gestures. */
final class ComposerCursorNavigation {
    private final boolean[] boundaries;
    private float desiredX = Float.NaN;

    ComposerCursorNavigation(CharSequence text) {
        String value = text.toString();
        boundaries = new boolean[value.length() + 1];
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.getDefault());
        iterator.setText(value);
        for (int i = iterator.first(); i != BreakIterator.DONE; i = iterator.next()) {
            boundaries[i] = true;
        }
        if (text instanceof Spanned) {
            Spanned spans = (Spanned) text;
            for (ReplacementSpan span : spans.getSpans(0, text.length(), ReplacementSpan.class)) {
                int start = Math.max(0, spans.getSpanStart(span));
                int end = Math.min(text.length(), spans.getSpanEnd(span));
                for (int i = start + 1; i < end; i++) {
                    boundaries[i] = false;
                }
            }
        }
        boundaries[0] = boundaries[text.length()] = true;
    }

    int move(Layout layout, int focus, int direction) {
        focus = Math.max(0, Math.min(boundaries.length - 1, focus));
        if (direction == 0 || direction == 1) {
            desiredX = Float.NaN;
            int next = focus;
            // Layout knows the visual order at bidi runs and wrapped line edges.
            for (int remaining = boundaries.length; remaining > 0; remaining--) {
                int candidate = direction == 0 ? layout.getOffsetToLeftOf(next) : layout.getOffsetToRightOf(next);
                if (candidate == next || candidate < 0 || candidate >= boundaries.length) {
                    return focus;
                }
                if (boundaries[candidate]) {
                    return candidate;
                }
                next = candidate;
            }
            return focus;
        }
        int line = layout.getLineForOffset(focus);
        int target = line + (direction == 2 ? -1 : 1);
        if (target < 0 || target >= layout.getLineCount()) {
            return focus;
        }
        if (Float.isNaN(desiredX)) {
            desiredX = layout.getPrimaryHorizontal(focus);
        }
        int candidate = layout.getOffsetForHorizontal(target, desiredX);
        if (boundaries[candidate]) {
            return candidate;
        }
        int left = candidate, right = candidate;
        while (left > 0 && !boundaries[left]) left--;
        while (right + 1 < boundaries.length && !boundaries[right]) right++;
        boolean leftOnLine = layout.getLineForOffset(left) == target;
        boolean rightOnLine = layout.getLineForOffset(right) == target;
        if (!leftOnLine && !rightOnLine) return focus;
        if (!leftOnLine) return right;
        if (!rightOnLine) return left;
        return Math.abs(layout.getPrimaryHorizontal(left) - desiredX)
                <= Math.abs(layout.getPrimaryHorizontal(right) - desiredX) ? left : right;
    }
}
