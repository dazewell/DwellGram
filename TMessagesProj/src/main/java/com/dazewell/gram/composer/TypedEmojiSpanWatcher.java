package com.dazewell.gram.composer;

import android.os.Build;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ReplacementSpan;
import android.widget.TextView;

import org.telegram.messenger.Emoji;
import org.telegram.messenger.EmojiData;
import org.telegram.messenger.SharedConfig;

import java.util.ArrayList;

/**
 * Gives keyboard-typed emoji the same span the emoji picker would.
 *
 * Upstream only runs Emoji.replaceEmoji on an insertion longer than one char, and the photo
 * caption field never runs it on typing at all. A single-char emoji such as U+26A1 then stays
 * plain text and is drawn by the system font, while the same emoji sent with U+FE0F gets the
 * selected emoji pack, so one message can show two different lightning bolts.
 *
 * Only ranges touched by the insertion are spanned. A plain emoji span there is replaced when the
 * typed text extended its sequence; premium emoji and other replacement spans are left alone.
 */
public final class TypedEmojiSpanWatcher implements TextWatcher {

    private final TextView view;
    private int insertStart = -1;
    private int insertEnd = -1;

    public TypedEmojiSpanWatcher(TextView view) {
        this.view = view;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (count > 0) {
            insertStart = start;
            insertEnd = start + count;
        } else {
            insertStart = -1;
        }
    }

    @Override
    public void afterTextChanged(Editable s) {
        final int from = insertStart;
        final int to = insertEnd;
        insertStart = -1;
        if (from < 0 || to > s.length() || !mayContainEmoji(s, from, to)) {
            return;
        }
        // Same cap replaceEmoji applies, so a long emoji run renders no differently from upstream.
        int remaining = Integer.MAX_VALUE;
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 29) {
            int limit = SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_HIGH ? 100 : 50;
            remaining = limit - s.getSpans(0, s.length(), Emoji.EmojiSpan.class).length;
        }
        ArrayList<Emoji.EmojiSpanRange> ranges = Emoji.parseEmojis(s);
        for (int i = 0; i < ranges.size(); i++) {
            Emoji.EmojiSpanRange range = ranges.get(i);
            if (range.end <= from || range.start >= to) {
                continue;
            }
            ArrayList<Emoji.EmojiSpan> stale = staleEmojiSpans(s, range);
            if (stale == null || remaining + stale.size() <= 0) {
                continue;
            }
            Emoji.EmojiDrawable drawable = Emoji.getEmojiDrawable(range.code);
            if (drawable == null) {
                continue;
            }
            for (Emoji.EmojiSpan old : stale) {
                s.removeSpan(old);
            }
            remaining += stale.size();
            Emoji.EmojiSpan span = new Emoji.EmojiSpan(drawable, DynamicDrawableSpan.ALIGN_BOTTOM, view.getPaint().getFontMetricsInt());
            span.emoji = range.code == null ? null : range.code.toString();
            s.setSpan(span, range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            remaining--;
        }
    }

    /**
     * The plain emoji spans to replace so the range can be spanned, or null to leave it alone.
     * Null when a span already matches the range, or when any other replacement (premium emoji,
     * a rich-editor button) overlaps it: nesting an image span inside one draws it twice. A
     * plain span that no longer matches is stale, e.g. a thumbs-up a skin tone was typed after.
     */
    private static ArrayList<Emoji.EmojiSpan> staleEmojiSpans(Spanned s, Emoji.EmojiSpanRange range) {
        ArrayList<Emoji.EmojiSpan> stale = new ArrayList<>();
        ReplacementSpan[] spans = s.getSpans(range.start, range.end, ReplacementSpan.class);
        for (ReplacementSpan span : spans) {
            int start = s.getSpanStart(span);
            int end = s.getSpanEnd(span);
            if (start >= range.end || end <= range.start) {
                continue;
            }
            if (!(span instanceof Emoji.EmojiSpan)) {
                return null;
            }
            Emoji.EmojiSpan emojiSpan = (Emoji.EmojiSpan) span;
            if (start == range.start && end == range.end && TextUtils.equals(emojiSpan.emoji, range.code)) {
                return null;
            }
            stale.add(emojiSpan);
        }
        return stale;
    }

    // Cheap pre-check so plain typing never pays for a whole-text emoji parse.
    private static boolean mayContainEmoji(CharSequence s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            // Same single-char test parseEmojis uses, so CJK or Hangul composing never triggers a parse.
            if (c >= 0xD83C && c <= 0xD83E || c == 0xFE0F || c == 0x20E3 || EmojiData.dataCharsMap.containsKey(c)) {
                return true;
            }
        }
        return false;
    }
}
