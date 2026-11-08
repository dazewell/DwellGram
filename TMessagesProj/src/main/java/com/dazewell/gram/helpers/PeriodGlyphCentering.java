package com.dazewell.gram.helpers;

import android.graphics.Paint;
import android.graphics.Rect;

import org.telegram.ui.Stories.recorder.CaptionContainerView;

/**
 * Centres the digit of a {@link CaptionContainerView.PeriodDrawable} on its ring by its visible ink.
 *
 * The drawable's text centres its line box: the advance width horizontally and the font's
 * top..bottom (it lays out with font padding) vertically. The num.otf "1" has uneven side bearings
 * and sits high in that box, so it reads off-centre in the round composer button. Upstream nudges
 * the same font by hand in ChatMessageCell; measuring the glyph keeps it right at any text size.
 */
public final class PeriodGlyphCentering {

    private PeriodGlyphCentering() {
    }

    public static void centerInk(CaptionContainerView.PeriodDrawable drawable, String text) {
        if (drawable == null || text == null || text.isEmpty()) {
            return;
        }
        final Paint paint = drawable.textDrawable.getPaint();
        final Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        if (ink.isEmpty()) {
            return;
        }
        final Paint.FontMetrics fm = paint.getFontMetrics();
        final float advance = paint.measureText(text);
        drawable.textOffsetX = advance / 2f - (ink.left + ink.right) / 2f;
        drawable.textOffsetY = (fm.top + fm.bottom) / 2f - (ink.top + ink.bottom) / 2f;
    }
}
