package com.dazewell.gram.videonote;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;

import xyz.nextalone.nagram.NaConfig;

/**
 * A small vertical slider over the round video camera's flash button that sets how bright the front flash is.
 * The value is FlashViews' intensity: the screen brightness and the white overlay's opacity. It is remembered
 * in NaConfig when a drag ends. The host shows it while the front flash is armed and mirrors its button row's
 * fade through {@link #followAlphaOf}. UI thread only.
 */
public final class FrontFlashBrightnessSlider extends View {

    // below this the screen goes too dim to light a face at all
    public static final float MIN = .3f, MAX = 1f;

    private final Utilities.Callback<Float> onChange;
    private final AnimatedFloat shownProgress;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable icon;
    private final RectF rect = new RectF();
    private float value;
    private static final int PAD = 6, WIDTH = 26, MIN_TRACK = 44;

    private View alphaSource;
    private com.dazewell.gram.ui.InstantZoomControlView zoomView;
    private float trackTop = dp(PAD);
    private float hostAlpha = 1f;
    private boolean shown;
    private boolean dragging;

    public FrontFlashBrightnessSlider(Context context, Utilities.Callback<Float> onChange) {
        super(context);
        this.onChange = onChange;
        shownProgress = new AnimatedFloat(this, 0, 220, CubicBezierInterpolator.EASE_OUT_QUINT);
        trackPaint.setColor(0x66000000);
        fillPaint.setColor(0xffffffff);
        icon = context.getResources().getDrawable(R.drawable.msg_brightness_high).mutate();
        icon.setColorFilter(new PorterDuffColorFilter(0xff1b1b1b, PorterDuff.Mode.SRC_IN));
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
    // row its track reaches over this corner. The slider gives up its top to stay below it, and hides when what
    // is left is too short to drag.
    public void stayBelow(com.dazewell.gram.ui.InstantZoomControlView zoom) {
        zoomView = zoom;
        syncTop();
    }

    private void syncTop() {
        float top = dp(PAD);
        if (zoomView != null && zoomView.getMeasuredWidth() > 0) {
            final float occupied = zoomView.occupiedBottomLeftOf(getRight() - zoomView.getX());
            if (occupied >= 0) {
                top = Math.max(top, zoomView.getY() + occupied + dp(4) - getTop());
            }
        }
        if (top != trackTop) {
            trackTop = top;
            if (!fits() && dragging) {
                dragging = false;
                save();
            }
            invalidate();
        }
    }

    private boolean fits() {
        return getHeight() - dp(PAD) - trackTop >= dp(MIN_TRACK);
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

    @Override
    protected void onDraw(Canvas canvas) {
        final float t = shownProgress.set(shown && fits());
        if (t <= 0) return;
        final float w = dp(WIDTH);
        final float cx = getWidth() / 2f;
        rect.set(cx - w / 2f, trackTop, cx + w / 2f, getHeight() - dp(PAD));
        // grows up out of the flash button below it
        canvas.save();
        canvas.scale(1f, .6f + .4f * t, cx, rect.bottom);
        trackPaint.setAlpha((int) (0x66 * t));
        canvas.drawRoundRect(rect, w / 2f, w / 2f, trackPaint);
        // the fill never shrinks below the circle the icon sits in, so the icon always has something under it
        final float fraction = (value - MIN) / (MAX - MIN);
        final float fillTop = rect.bottom - w - (rect.height() - w) * fraction;
        fillPaint.setAlpha((int) (0xff * t));
        canvas.save();
        canvas.clipRect(rect.left, fillTop, rect.right, rect.bottom);
        canvas.drawRoundRect(rect, w / 2f, w / 2f, fillPaint);
        canvas.restore();
        final int is = dp(16);
        final int il = (int) (cx - is / 2f), it = (int) (rect.bottom - w / 2f - is / 2f);
        icon.setBounds(il, it, il + is, it + is);
        icon.setAlpha((int) (0xff * t));
        icon.draw(canvas);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!interactive() || event.getY() < trackTop) return false;
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
        final float w = dp(WIDTH);
        final float top = trackTop, bottom = getHeight() - dp(PAD) - w;
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
