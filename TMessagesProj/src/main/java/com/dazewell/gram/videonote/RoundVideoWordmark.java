package com.dazewell.gram.videonote;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.util.Locale;

/**
 * #round-video-wordmark: the app's name, curved along the bottom-right corner of every recorded round video, in place
 * of the "TELEGRAM" that upstream bakes in from R.raw.round_blur_overlay_text.
 *
 * Returns a bitmap of the same size and layout as that PNG, so the recorders scale it and take its alpha exactly as
 * before. The name is R.string.NagramX, which build.gradle sets per variant, so a rename only touches the build file.
 *
 * Every number below was measured off the stock PNG (248 px square, the bottom-right 372/1536 of the frame), not
 * picked by eye: the arc is centred on the frame's centre, the baseline sits on the outer edge with letter tops facing
 * inwards, and the text is centred on the corner's diagonal.
 */
public final class RoundVideoWordmark {

    private static final int SIZE = 248;
    // Frame centre relative to the square's top-left corner: (768 - 1164) / 372 of its side, on both axes.
    private static final float CENTRE = -264f;
    private static final float BASELINE_RADIUS = 568f;
    private static final float TEXT_SIZE = 37f;
    private static final float LETTER_SPACING = 0.29f;
    // A longer name tightens to this before the type shrinks.
    private static final float MIN_LETTER_SPACING = 0.15f;
    // What "TELEGRAM" covers, trailing spacing included. Past this a name runs off the corner square.
    private static final float MAX_SWEEP_DEGREES = 28f;

    private RoundVideoWordmark() {
    }

    public static Bitmap create() {
        try {
            return draw(ApplicationLoader.applicationContext.getString(R.string.NagramX).toUpperCase(Locale.ROOT));
        } catch (Throwable e) {
            // Losing the wordmark beats losing the recording: fall back to upstream's art.
            FileLog.e(e);
            return AndroidUtilities.getBitmapFromRaw(R.raw.round_blur_overlay_text);
        }
    }

    private static Bitmap draw(String text) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        // Fixed face, not bold(), which follows the font setting: the numbers above were fitted to this one.
        paint.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        paint.setTextSize(TEXT_SIZE);
        paint.setLetterSpacing(LETTER_SPACING);

        float maxLength = (float) Math.toRadians(MAX_SWEEP_DEGREES) * BASELINE_RADIUS;
        float width = paint.measureText(text);
        if (width > maxLength) {
            paint.setLetterSpacing(MIN_LETTER_SPACING);
            width = paint.measureText(text);
        }
        if (width > maxLength) {
            // Letter spacing is in ems, so this scales the whole run.
            paint.setTextSize(TEXT_SIZE * maxLength / width);
            width = paint.measureText(text);
        }

        // Counter-clockwise, so the run reads bottom-left to top-right with the letter tops towards the centre.
        Path path = new Path();
        RectF oval = new RectF(CENTRE - BASELINE_RADIUS, CENTRE - BASELINE_RADIUS, CENTRE + BASELINE_RADIUS, CENTRE + BASELINE_RADIUS);
        path.addArc(oval, 45f + MAX_SWEEP_DEGREES / 2f, -MAX_SWEEP_DEGREES);

        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        new Canvas(bitmap).drawTextOnPath(text, path, Math.max(0f, (maxLength - width) / 2f), 0f, paint);
        return bitmap;
    }
}
