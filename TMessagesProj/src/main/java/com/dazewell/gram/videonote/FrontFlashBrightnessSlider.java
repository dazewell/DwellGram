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
    // the arm is as wide as the flash cell; the view is wider by the fillet at the join, and the touch target by
    // a little slop on the right
    private static final int ARM = 44, FILLET = 14, SLOP = 6;
    // the shortest arm still worth dragging, and the fill's stub at the lowest brightness
    private static final int MIN_ARM = 56, MIN_FILL = 8;
    // how far the arm reaches over the row's top edge, enough to cover the row's own stroke there
    private static final int OVERLAP = 2;

    private final Utilities.Callback<Float> onChange;
    private final AnimatedFloat shownProgress;
    private final AnimatedFloat readoutProgress;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.text.TextPaint textPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Path rowPath = new Path();
    private final Path wedge = new Path();
    private final RectF rect = new RectF();
    private final float[] topRadii = new float[8];
    private BlurredBackgroundDrawable glass, filletGlass;
    private int fillColor = 0xffffffff;
    private float value;
    private float dragStartY, dragStartValue;
    private boolean atEnd;
    private boolean readoutShown;
    private final Runnable hideReadout = () -> {
        readoutShown = false;
        invalidate();
    };
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
        readoutProgress = new AnimatedFloat(this, 0, 100, CubicBezierInterpolator.EASE_OUT);
        bgPaint.setColor(0x66000000);
        textPaint.setTextSize(dp(12));
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextAlign(Paint.Align.CENTER);
        final float r = dp(RADIUS);
        topRadii[0] = topRadii[1] = topRadii[2] = topRadii[3] = r;
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

    // The row's own glass, made by the same factory, so the arm reads as part of it. Without it (the legacy
    // design, which has no row background) the arm is a dark pill of its own above the button.
    // The fillet in the concave corner of the join gets its own drawable, since each one samples the backdrop
    // under its own bounds.
    public void setGlassBackground(BlurredBackgroundDrawable background, BlurredBackgroundDrawable fillet) {
        // uniform corners keep the outline a round rect, which RenderNode clips to before API 33 too; the bottom
        // ones fall below the clip anyway (onDraw)
        background.setPadding(0);
        background.setRadius(dp(RADIUS));
        fillet.setPadding(0);
        fillet.setRadius(dp(RADIUS));
        glass = background;
        filletGlass = fillet;
        invalidate();
    }

    public void setFillColor(int color) {
        fillColor = color;
        textPaint.setColor(color);
        invalidate();
    }

    public void setShown(boolean shown) {
        if (this.shown == shown) return;
        this.shown = shown;
        if (!shown && dragging) {
            // a flip or a stop mid-drag has already applied the value, so keep it
            endDrag();
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
        removeCallbacks(hideReadout);
        readoutShown = false;
        super.onDetachedFromWindow();
    }

    private boolean interactive() {
        return shown && hostAlpha > .5f && fits();
    }

    // the flat line the fill rises from: the row's top edge, or just above the glyph without a row background
    private float trackBottom() {
        return glass != null ? rowTop() : rowTop() - dp(4);
    }

    // where the fill's top sits for a value: an 8dp stub at the lowest, the arm's top at the highest
    private float fillTopFor(float fraction, float armTop) {
        return AndroidUtilities.lerp(trackBottom() - dp(MIN_FILL), armTop, fraction);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final boolean target = shown && fits();
        if (shownProgress.getTargetValue() != (target ? 1f : 0f)) {
            // collapsing back into the button is quicker than growing out of it
            shownProgress.setDuration(target ? 320 : 220);
        }
        final float t = shownProgress.set(target);
        if (t <= 0) return;
        final float aw = dp(ARM), r = dp(RADIUS);
        final float rowTop = rowTop(), rowCenter = getHeight() - dp(ROW) / 2f;
        // grows up out of the button: at 0 the arm is all inside the row and nothing shows
        final float armTop = AndroidUtilities.lerp(rowTop + dp(OVERLAP), topLimit, t);
        final float fillTop = fillTopFor((value - MIN) / (MAX - MIN), armTop);
        final float bottom = trackBottom();

        canvas.save();
        if (glass != null) {
            // the row's real right end: with the infinite button gone it is a lone cell with a rounded right cap.
            // both start 7dp in (margin 1 + padding 6), and the row's drawn width is 12dp under its own
            final float rowRight = Math.max(aw, alphaSource != null ? alphaSource.getWidth() - dp(12) : aw + r * 2);
            rowPath.rewind();
            rect.set(0, rowTop + dp(OVERLAP), rowRight, getHeight());
            rowPath.addRoundRect(rect, r, r, Path.Direction.CW);

            // the concave corner where the arm meets a row that carries on to the right, rounded as it grows
            final float f = rowRight > aw + dp(1) ? dp(FILLET) * t : 0;
            if (f > 1) {
                wedge.rewind();
                // starts a little inside the arm, drawn over it next, so no anti-aliased gap opens along the join
                wedge.addRect(aw - dp(1.5f), rowTop - f, aw + f, rowTop + dp(OVERLAP), Path.Direction.CW);
                clip.rewind();
                clip.addCircle(aw + f, rowTop - f, f, Path.Direction.CW);
                wedge.op(clip, Path.Op.DIFFERENCE);
                wedge.op(rowPath, Path.Op.DIFFERENCE);
                canvas.save();
                canvas.clipPath(wedge);
                // big enough that its own rounded corners and strokes stay well outside the wedge
                filletGlass.setBounds((int) (aw - dp(48)), (int) (rowTop - dp(64)), (int) (aw + dp(64)), getHeight() + dp(48));
                filletGlass.draw(canvas);
                canvas.restore();
            }

            // everything the arm adds over the row, plus a strip over the row's top edge so the row's stroke
            // doesn't draw a seam across the join. the arm's own bottom ends inside the row, out of sight
            clip.rewind();
            rect.set(0, armTop, aw, rowCenter);
            clip.addRoundRect(rect, topRadii, Path.Direction.CW);
            clip.op(rowPath, Path.Op.DIFFERENCE);
            canvas.clipPath(clip);
            glass.setBounds(0, (int) armTop, (int) aw, getHeight());
            glass.draw(canvas);
        } else {
            // no row background to join: a pill of its own, stopping short of the button's glyph
            rect.set(0, armTop, aw, Math.max(armTop, bottom));
            bgPaint.setAlpha((int) (0x66 * t));
            canvas.drawRoundRect(rect, r, r, bgPaint);
            clip.rewind();
            clip.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.clipPath(clip);
        }
        // edge to edge with a flat top, so the arm's outline is the only shape; a thin line marks the level
        canvas.clipRect(0, 0, aw, bottom + (glass != null ? dp(OVERLAP) : 0));
        final int fill = glass != null ? ColorUtils.setAlphaComponent(fillColor, 0x3D) : 0x40ffffff;
        fillPaint.setColor(fill);
        fillPaint.setAlpha((int) (Color.alpha(fill) * t));
        canvas.drawRect(0, fillTop, aw, getHeight(), fillPaint);
        final int line = glass != null ? ColorUtils.setAlphaComponent(fillColor, 0x80) : 0x80ffffff;
        linePaint.setColor(line);
        linePaint.setAlpha((int) (Color.alpha(line) * t));
        canvas.drawRect(0, fillTop, aw, fillTop + dp(1.5f), linePaint);
        canvas.restore();

        final float readout = readoutProgress.set(readoutShown) * t;
        if (readout > 0) {
            final int color = glass != null ? fillColor : 0xffffffff;
            textPaint.setColor(color);
            textPaint.setAlpha((int) (Color.alpha(color) * readout));
            canvas.drawText(percent(), aw / 2f, armTop + dp(8) - textPaint.ascent(), textPaint);
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
            // the row below belongs to the flash button, and the fillet is only decoration
            if (!interactive() || event.getY() < topLimit || event.getY() >= rowTop() || event.getX() > dp(ARM + SLOP)) return false;
            dragging = true;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            // relative, so a tap never jumps the brightness mid-recording
            dragStartY = event.getY();
            dragStartValue = value;
            atEnd = value <= MIN || value >= MAX;
            removeCallbacks(hideReadout);
            readoutShown = true;
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
        final float length = fillTopFor(0, topLimit) - fillTopFor(1, topLimit);
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
        save();
        removeCallbacks(hideReadout);
        postDelayed(hideReadout, 500);
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
