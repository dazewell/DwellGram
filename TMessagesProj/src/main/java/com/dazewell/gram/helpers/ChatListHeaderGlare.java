package com.dazewell.gram.helpers;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

import xyz.nextalone.nagram.NaConfig;

public final class ChatListHeaderGlare {
    private final Paint lightPaint = createPaint(0x33FFFFFF);
    private final Paint darkPaint = createPaint(0x1AFFFFFF);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BlurredBackgroundDrawable border;

    public ChatListHeaderGlare(View view, BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colors) {
        border = factory.create(view, colors);
        border.setPadding(0);
        border.setStrokeWidth(0, 0);
        border.setShadowAlpha(0f);
        border.setRadius(0);
        border.setThickness(AndroidUtilities.dp(5));
        border.setIntensity(0.4f);
        fadePaint.setShader(new LinearGradient(0, 0, 0, AndroidUtilities.dpf2(3),
                0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    private static Paint createPaint(int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, 1, 0x00FFFFFF, color, Shader.TileMode.CLAMP));
        return paint;
    }

    public void draw(Canvas canvas, float y, Rect bounds, boolean isThemeLight, boolean top) {
        if (!top || bounds.isEmpty() || !canvas.isHardwareAccelerated()
                || !LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) {
            return;
        }

        // Keep the full pane's geometry so the clip reveals its bottom edge, not a second glass pill.
        border.setBounds(bounds);
        border.setSourceOffset(0, y);
        border.updateColors();
        final float edgeTop = Math.max(bounds.top, bounds.bottom - AndroidUtilities.dpf2(5));
        final int save = canvas.save();
        canvas.clipRect(bounds.left, edgeTop, bounds.right, bounds.bottom);
        // Mask only the refracted strip, leaving the frosted header beneath it untouched.
        canvas.saveLayer(bounds.left, edgeTop, bounds.right, bounds.bottom, null);
        border.draw(canvas);
        canvas.translate(0, edgeTop);
        canvas.drawRect(bounds.left, 0, bounds.right, bounds.bottom - edgeTop, fadePaint);
        canvas.restoreToCount(save);

        if (!NaConfig.INSTANCE.getStrokeOnViews().Bool()) {
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
