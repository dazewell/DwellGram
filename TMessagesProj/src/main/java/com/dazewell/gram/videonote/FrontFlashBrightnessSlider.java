package com.dazewell.gram.videonote;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
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
 * The round video camera's front flash brightness: a pill of its own that grows up from just above the flash
 * button, apart from the button row. The level is a lit capsule that starts as a circle at the pill's bottom and
 * stretches up it. The value is FlashViews' intensity, the screen brightness plus the white overlay's opacity, and
 * is remembered in NaConfig when a drag ends.
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
    // the pill is as wide as the flash cell; the touch target takes a little slop on the right
    private static final int ARM = 44, SLOP = 6;
    // the space between the pill and the row, so the row's glass and its shadow never show through the pill
    private static final int GAP = 6;
    // the shortest capsule travel still worth dragging
    private static final int MIN_ARM = 56;
    // the level capsule's inset in the pill, which also makes its lowest state a circle
    private static final int CAPSULE_INSET = 4;

    private final Utilities.Callback<Float> onChange;
    private final AnimatedFloat shownProgress;
    private final AnimatedFloat pressedProgress;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capsulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private BlurredBackgroundDrawable glass;
    private int fillColor = 0xffffffff;
    private float value;
    private float dragStartY, dragStartValue;
    private boolean atEnd;
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
        pressedProgress = new AnimatedFloat(this, 0, 100, CubicBezierInterpolator.EASE_OUT);
        bgPaint.setColor(0x66000000);
        value = readStored();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            setStateDescription(percent());
        }
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

    // The row's own glass, made by the same factory, so the pill matches it. Without it (the legacy design, which
    // has no row background) the pill is dark.
    public void setGlassBackground(BlurredBackgroundDrawable background) {
        background.setPadding(0);
        background.setRadius(dp(RADIUS));
        glass = background;
        invalidate();
    }

    public void setFillColor(int color) {
        fillColor = color;
        invalidate();
    }

    public void setShown(boolean shown) {
        if (this.shown == shown) return;
        this.shown = shown;
        if (!shown && dragging) {
            // a flip or a stop mid-drag has already applied the value, so keep it
            endDrag();
        }
        updateAccessibility();
        invalidate();
    }

    // TalkBack only finds the slider while it is actually drawn
    private void updateAccessibility() {
        setImportantForAccessibility(shown && fits() ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO);
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
    // row it reaches over this corner. The pill then stops short of it, and hides when what is left is too short
    // to drag.
    public void stayBelow(com.dazewell.gram.ui.InstantZoomControlView zoom) {
        zoomView = zoom;
        syncTop();
    }

    private void syncTop() {
        float top = 0;
        if (zoomView != null && zoomView.getMeasuredWidth() > 0) {
            final float occupied = zoomView.occupiedBottomLeftOf(getLeft() + dp(ARM + SLOP) - zoomView.getX());
            if (occupied >= 0) {
                top = Math.max(top, zoomView.getY() + occupied + dp(4) - getTop());
            }
        }
        if (top != topLimit) {
            topLimit = top;
            if (!fits() && dragging) {
                endDrag();
            }
            updateAccessibility();
            invalidate();
        }
    }

    private float rowTop() {
        return getHeight() - dp(ROW);
    }

    // measured as the capsule's travel
    private boolean fits() {
        return capsuleTopFor(0, topLimit) - capsuleTopFor(1, topLimit) >= dp(MIN_ARM);
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

    private float pillBottom() {
        return rowTop() - dp(GAP);
    }

    private float capsuleBottom() {
        return pillBottom() - dp(CAPSULE_INSET);
    }

    // its top for a value: a circle at the lowest, just under the pill's top at the highest
    private float capsuleTopFor(float fraction, float pillTop) {
        final float lowest = capsuleBottom() - dp(ARM - CAPSULE_INSET * 2);
        return Math.min(lowest, AndroidUtilities.lerp(lowest, pillTop + dp(CAPSULE_INSET), fraction));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final boolean target = shown && fits();
        if (shownProgress.getTargetValue() != (target ? 1f : 0f)) {
            // collapsing back is quicker than growing out of it
            shownProgress.setDuration(target ? 320 : 220);
        }
        final float t = shownProgress.set(target);
        if (t <= 0) return;
        final float aw = dp(ARM), r = dp(RADIUS);
        final float bottom = pillBottom();
        // grows up from a circle just above the button, fading in as it does
        final float pillTop = Math.min(bottom - aw, AndroidUtilities.lerp(bottom - aw, topLimit, t));
        final boolean fading = t < 1f;
        if (fading) {
            canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), (int) (255 * t));
        }

        if (glass != null) {
            glass.setBounds(0, (int) pillTop, (int) aw, (int) bottom);
            glass.draw(canvas);
        } else {
            rect.set(0, pillTop, aw, bottom);
            canvas.drawRoundRect(rect, r, r, bgPaint);
        }

        // The level: a capsule with round ends, inset in the pill. On glass it takes the glyphs' color, at an alpha
        // low enough that it reads as a lit track rather than a button.
        final float inset = dp(CAPSULE_INSET);
        final float top = AndroidUtilities.lerp(capsuleTopFor(0, topLimit), capsuleTopFor((value - MIN) / (MAX - MIN), topLimit), t);
        rect.set(inset, top, aw - inset, capsuleBottom());
        final float p = pressedProgress.set(dragging);
        final int alpha = glass != null ? (int) AndroidUtilities.lerp(0x33, 0x44, p) : (int) AndroidUtilities.lerp(0x38, 0x48, p);
        capsulePaint.setColor(ColorUtils.setAlphaComponent(glass != null ? fillColor : 0xffffffff, alpha));
        final float cr = (aw - inset * 2) / 2f;
        canvas.drawRoundRect(rect, cr, cr, capsulePaint);

        if (fading) {
            canvas.restore();
        }
    }

    private String percent() {
        return Math.round(value * 100) + "%";
    }

    // touch exploration over the flash button must find the button, not this view lying over it
    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        if (event.getY() >= rowTop() || event.getX() > dp(ARM + SLOP)) return false;
        return super.dispatchHoverEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // the row below belongs to the flash button
            if (!interactive() || event.getY() < topLimit || event.getY() >= rowTop() || event.getX() > dp(ARM + SLOP)) return false;
            dragging = true;
            // the pressed tint eases in quickly and out a little slower
            pressedProgress.setDuration(100);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            // relative, so a tap never jumps the brightness mid-recording
            dragStartY = event.getY();
            dragStartValue = value;
            atEnd = value <= MIN || value >= MAX;
            invalidate();
            return true;
        }
        if (!dragging) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            dragTo(event.getY());
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            endDrag();
        }
        return true;
    }

    private void dragTo(float y) {
        final float length = capsuleTopFor(0, topLimit) - capsuleTopFor(1, topLimit);
        if (length <= 0) return;
        final float wanted = dragStartValue + (dragStartY - y) / length * (MAX - MIN);
        final float clamped = Utilities.clamp(wanted, MAX, MIN);
        setValue(clamped);
        if (clamped != wanted) {
            // pinned at an end: re-anchor, so coming back moves it straight away
            dragStartY = y;
            dragStartValue = value;
        }
        final boolean end = value <= MIN || value >= MAX;
        if (end && !atEnd) {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }
        atEnd = end;
    }

    // ends a drag however it ends: the finger lifting, the flash going off, or the zoom control taking the room
    private void endDrag() {
        dragging = false;
        pressedProgress.setDuration(200);
        save();
        invalidate();
    }

    private void setValue(float v) {
        v = Utilities.clamp(v, MAX, MIN);
        // skip tiny steps, but always let an exact end through so the end tick and the a11y actions see it
        if (v == value || (Math.abs(v - value) < .001f && v > MIN && v < MAX)) return;
        value = v;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            setStateDescription(percent());
        }
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
