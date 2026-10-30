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
    private final Paint lightPaint = createPaint(0x4DFFFFFF);
    private final Paint darkPaint = createPaint(0x28FFFFFF);

    private static Paint createPaint(int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, 1, color, 0x00FFFFFF, Shader.TileMode.CLAMP));
        return paint;
    }

    public void draw(Canvas canvas, Rect bounds, boolean isThemeLight, boolean top) {
        if (!top || bounds.isEmpty() || !canvas.isHardwareAccelerated()
                || !LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)
                || !NaConfig.INSTANCE.getStrokeOnViews().Bool()) {
            reportSmoke(false, canvas, bounds, top);
            return;
        }

        canvas.save();
        canvas.clipRect(bounds);
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(1, AndroidUtilities.dpf2(2.5f));
        canvas.drawRect(0, 0, bounds.width(), 1, isThemeLight ? lightPaint : darkPaint);
        canvas.restore();
        reportSmoke(true, canvas, bounds, top);
    }

    private boolean smokeReported;

    public ChatListHeaderGlare() {
        android.util.Log.i("HeaderGlareSmoke", "NAX_SMOKE_glass-header-glare BEGIN scenario=chat-list-header instance="
                + System.identityHashCode(this) + " build=" + org.telegram.messenger.BuildConfig.BUILD_VERSION_STRING
                + " package=" + org.telegram.messenger.BuildConfig.APPLICATION_ID);
    }

    private void reportSmoke(boolean drawn, Canvas canvas, Rect bounds, boolean top) {
        if (smokeReported) {
            return;
        }
        smokeReported = true;
        android.util.Log.i("HeaderGlareSmoke", "NAX_SMOKE_glass-header-glare "
                + (drawn ? "EXPECTED drawn" : "COMPETING skipped") + " instance=" + System.identityHashCode(this)
                + " top=" + top + " hardware=" + canvas.isHardwareAccelerated() + " empty=" + bounds.isEmpty()
                + " glass=" + LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)
                + " glare=" + NaConfig.INSTANCE.getStrokeOnViews().Bool());
        android.util.Log.i("HeaderGlareSmoke", "NAX_SMOKE_glass-header-glare END instance=" + System.identityHashCode(this));
    }
}
