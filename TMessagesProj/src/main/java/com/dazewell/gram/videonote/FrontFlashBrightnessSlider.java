package com.dazewell.gram.videonote;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

import xyz.nextalone.nagram.NaConfig;

/**
 * The round video camera's front flash brightness: a pill that grows up out of the flash button, as one shape with
 * the button row under it. The value is FlashViews' intensity, the screen brightness plus the white overlay's
 * opacity, and is remembered in NaConfig when a drag ends.
 *
 * The view sits over the flash cell of the row: its bottom 44dp are that cell, and touches there fall through to
 * the flash button. The host shows it while the front flash is armed; it fades with the row through
 * {@link #followAlphaOf} and gives up its top to the zoom control through {@link #stayBelow}. UI thread only.
 */
public final class FrontFlashBrightnessSlider extends View {

    // below this the screen goes too dim to light a face at all
    public static final float MIN = .3f, MAX = 1f;

    // the row's drawn height and corner radius (InstantCameraView.setButtonsBackground), shorter than the
    // view's frame by the drawable's 6dp padding
    private static final int ROW = 44, RADIUS = 21;
    // the shortest arm still worth dragging, and the fill's inset from the arm's edge
    private static final int MIN_ARM = 56, INSET = 5;
    // how far the arm reaches over the row's top edge, enough to cover the row's own stroke there
    private static final int OVERLAP = 2;

    private final Utilities.Callback<Float> onChange;
    private final AnimatedFloat shownProgress;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Path rowPath = new Path();
    private final RectF rect = new RectF();
    private BlurredBackgroundDrawable glass;
    private int fillColor = 0xffffffff;
    private float value;
    private View alphaSource;
    private com.dazewell.gram.ui.InstantZoomControlView zoomView;
    private float topLimit;
    private float hostAlpha = 1f;
    private boolean shown;
    private boolean dragging;

    public FrontFlashBrightnessSlider(Context context, Utilities.Callback<Float> onChange) {
        super(context);
        this.onChange = onChange;
        shownProgress = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        bgPaint.setColor(0x66000000);
        value = readStored();
        setContentDescription(LocaleController.getString(R.string.FrontFlashBrightness));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    // fall back to full brightness on an absent or out-of-range stored value
    private static float readStored() {
        final Float stored = NaConfig.INSTANCE.getVideoMessageFlashBrightness().Float();
        if (stored == null || Float.isNaN(stored)) return MAX;
        return Utilities.clamp(stored, MAX, MIN);
    }

    public float getValue() {
        return value;
    }

    // The row's own glass, made by the same factory, so the arm reads as part of it. Without it (the legacy
    // design, which has no row background) the arm is a dark pill of its own above the button.
    public void setGlassBackground(BlurredBackgroundDrawable background) {
        background.setPadding(0);
        background.setRadius(dp(RADIUS), dp(RADIUS), 0, 0);
        glass = background;
        invalidate();
    }

    public void setFillColor(int color) {
        fillColor = color;
        if (glass != null) glass.updateColors();
        invalidate();
    }

    public void setShown(boolean shown) {
        if (this.shown == shown) return;
        this.shown = shown;
        if (!shown && dragging) {
            // a flip or a stop mid-drag has already applied the value, so keep it
            dragging = false;
            save();
        }
        setImportantForAccessibility(shown ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO);
        invalidate();
    }

    // Fades with the given view (the flash button's row), so the host's fade animations need no hook each. Read on
    // every pre-draw, since an alpha animation on that view calls nothing else here.
    public void followAlphaOf(View source) {
        alphaSource = source;
        syncAlpha();
    }

    private void syncAlpha() {
        if (alphaSource == null) return;
        final float a = alphaSource.getAlpha();
        if (a != hostAlpha) {
            hostAlpha = a;
            setAlpha(a);
        }
    }

    // The zoom control spans the full width and moves with the keyboard, and on narrow screens or in its compact
    // row it reaches over this corner. The arm then stops short of it, and hides when what is left is too short
    // to drag.
    public void stayBelow(com.dazewell.gram.ui.InstantZoomControlView zoom) {
        zoomView = zoom;
        syncTop();
    }

    private void syncTop() {
        float top = 0;
        if (zoomView != null && zoomView.getMeasuredWidth() > 0) {
            final float occupied = zoomView.occupiedBottomLeftOf(getRight() - zoomView.getX());
            if (occupied >= 0) {
                top = Math.max(top, zoomView.getY() + occupied + dp(4) - getTop());
            }
        }
        if (top != topLimit) {
            topLimit = top;
            if (!fits() && dragging) {
                dragging = false;
                save();
            }
            invalidate();
        }
    }

    private float rowTop() {
        return getHeight() - dp(ROW);
    }

    private boolean fits() {
        return rowTop() - topLimit >= dp(MIN_ARM);
    }

    private final ViewTreeObserver.OnPreDrawListener preDrawListener = () -> {
        syncAlpha();
        syncTop();
        return true;
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDrawListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
        super.onDetachedFromWindow();
    }

    private boolean interactive() {
        return shown && hostAlpha > .5f && fits();
    }

    // where the fill's top sits for a value, between just over the row and just under the arm's top
    private float fillTopFor(float fraction, float armTop) {
        return AndroidUtilities.lerp(rowTop() - dp(INSET), armTop + dp(INSET), fraction);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float t = shownProgress.set(shown && fits());
        if (t <= 0) return;
        final float w = getWidth(), r = dp(RADIUS);
        final float rowTop = rowTop(), rowCenter = getHeight() - dp(ROW) / 2f;
        // grows up out of the button: at 0 the arm is all inside the row and nothing shows
        final float armTop = AndroidUtilities.lerp(rowTop + dp(OVERLAP), topLimit, t);
        final float fraction = (value - MIN) / (MAX - MIN);
        final float fillTop = fillTopFor(fraction, armTop);

        canvas.save();
        if (glass != null) {
            // everything the arm adds over the row, plus a strip over the row's top edge so the row's stroke
            // doesn't draw a seam across the join. the arm's own square bottom ends inside the row, out of sight
            clip.rewind();
            clip.addRect(0, armTop, w, rowCenter, Path.Direction.CW);
            rowPath.rewind();
            // the row's real right end: with the infinite button gone it is a lone cell with a rounded right cap.
            // both start 7dp in (margin 1 + padding 6), and the row's drawn width is 12dp under its own
            final float rowRight = alphaSource != null ? alphaSource.getWidth() - dp(12) : w + r * 2;
            rect.set(0, rowTop + dp(OVERLAP), Math.max(w, rowRight), getHeight());
            rowPath.addRoundRect(rect, r, r, Path.Direction.CW);
            clip.op(rowPath, Path.Op.DIFFERENCE);
            canvas.clipPath(clip);
            glass.setBounds(0, (int) armTop, (int) w, (int) rowCenter);
            glass.draw(canvas);
        } else {
            // no row background to join: a pill of its own, stopping short of the button's glyph
            rect.set(0, armTop, w, Math.max(armTop, rowTop - dp(4)));
            bgPaint.setAlpha((int) (0x66 * t));
            canvas.drawRoundRect(rect, r, r, bgPaint);
            canvas.clipRect(rect);
        }
        // the fill runs on past the clip, so its visible bottom is the flat edge the arm leaves the row at
        final float fr = (w - dp(INSET) * 2) / 2f;
        rect.set(dp(INSET), fillTop, w - dp(INSET), rowTop + fr * 2);
        final int color = glass != null ? ColorUtils.setAlphaComponent(fillColor, 0x55) : 0xffffffff;
        fillPaint.setColor(color);
        fillPaint.setAlpha((int) (Color.alpha(color) * t));
        canvas.drawRoundRect(rect, fr, fr, fillPaint);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // the row below belongs to the flash button
            if (!interactive() || event.getY() < topLimit || event.getY() >= rowTop()) return false;
            dragging = true;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            setFromY(event.getY());
            return true;
        }
        if (!dragging) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            setFromY(event.getY());
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            if (action == MotionEvent.ACTION_UP) setFromY(event.getY());
            save();
        }
        return true;
    }

    private void setFromY(float y) {
        final float bottom = fillTopFor(0, topLimit), top = fillTopFor(1, topLimit);
        final float fraction = bottom <= top ? 1f : Utilities.clamp((bottom - y) / (bottom - top), 1f, 0f);
        setValue(MIN + (MAX - MIN) * fraction);
    }

    private void setValue(float v) {
        v = Utilities.clamp(v, MAX, MIN);
        if (Math.abs(v - value) < .001f) return;
        value = v;
        invalidate();
        onChange.run(value);
    }

    private void save() {
        NaConfig.INSTANCE.getVideoMessageFlashBrightness().setConfigFloat(value);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, MIN * 100, MAX * 100, value * 100));
        if (!interactive()) return;
        if (value < MAX) info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (value > MIN) info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            if (!interactive()) return false;
            setValue(value + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? .1f : -.1f));
            save();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
