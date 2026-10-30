package com.dazewell.gram.helpers;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;

import xyz.nextalone.nagram.NaConfig;

public final class ChatListHeaderGlare {
    private final Paint lightPaint = createPaint(0x33FFFFFF);
    private final Paint darkPaint = createPaint(0x1AFFFFFF);

    private static Paint createPaint(int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, 1, 0x00FFFFFF, color, Shader.TileMode.CLAMP));
        return paint;
    }

    public void draw(Canvas canvas, Rect bounds, boolean isThemeLight, boolean top) {
        if (!top || bounds.isEmpty() || !canvas.isHardwareAccelerated()
                || !LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)
                || !NaConfig.INSTANCE.getStrokeOnViews().Bool()) {
            return;
        }
        canvas.save();
        canvas.clipRect(bounds);
        canvas.translate(bounds.left, bounds.bottom - AndroidUtilities.dpf2(1.5f));
        canvas.scale(1, AndroidUtilities.dpf2(1.5f));
        canvas.drawRect(0, 0, bounds.width(), 1, isThemeLight ? lightPaint : darkPaint);
        canvas.restore();
    }
}
