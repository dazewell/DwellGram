package com.dazewell.gram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

/**
 * Zoom control for the round video message recorder: a long precision slider with a paired -/+ rocker
 * and a camera-flip button to their left, all kept off the track ends. Has two layouts blended by a
 * single fraction: roomy (slider row on top, buttons centered below) and compact for when the soft
 * keyboard eats the vertical space (one row, buttons to the right of a shorter slider). The view is
 * always 104dp tall; the rows move inside it. The flip button stays even when the camera has no zoom.
 */
public class InstantZoomControlView extends View {

    private int chipColor;
    private int glyphColor;

    public interface Delegate {
        void didSetZoom(float zoom);
        void onButtonDown(int direction);
        void onButtonUp(int direction, boolean cancelled);
        void onSwitchCamera();
    }

    private final Drawable minusDrawable;
    private final Drawable plusDrawable;
    private final Drawable switchDrawable;
    private final Drawable knobDrawable;
    private final Drawable pressedKnobDrawable;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();

    // one glass chip per button, from the same factory the flash button's background comes from. null
    // until the recorder hands them over (and stays null wherever there's no blur host), in which case
    // the buttons fall back to a flat message-panel circle
    private BlurredBackgroundDrawable minusChip, plusChip, switchChip;
    private float chipRadius = -1f;

    private Delegate delegate;
    private float zoom;

    // 0 = roomy two-row layout, 1 = compact single row; animated on keyboard open/close
    private float compact;
    private boolean compactTarget;
    private ValueAnimator compactAnimator;

    // geometry recomputed each draw from width + compact fraction
    private float trackLeft, trackRight, trackY;
    private float minusCx, plusCx, switchCx, buttonCy, buttonRadius, glyphHalf, switchGlyphHalf;

    // the slider + -/+ rocker hide when the current camera has no zoom range; the flip button stays
    private boolean zoomEnabled = true;

    // fixed lens stops drawn as a segmented glass strip above the track, with a highlight that follows the knob
    private float[] presetFractions;
    private String[] presetLabels;
    private final android.text.TextPaint presetPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int presetPointer = -1;
    private int presetPressed = -1;
    private float presetSegment;
    private float presetShift;
    private ValueAnimator presetShiftAnimator;
    private BlurredBackgroundDrawable stripChip;
    private float stripChipRadius = -1f;
    private final RectF stripRect = new RectF();

    private boolean knobPressed;
    private float knobOffsetX;
    private boolean trackPressed;
    private float trackDownX;

    private int buttonPointerId = -1;
    private int buttonDirection;
    private int switchPointerId = -1;

    private final class ButtonAccent {
        float scale = 1f;
        float fill;
        float ring;
        boolean held;
        private ValueAnimator animator;

        void animateTo(float toScale, float toFill, float toRing, long duration) {
            if (animator != null) {
                animator.cancel();
            }
            final float fromScale = scale, fromFill = fill, fromRing = ring;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(duration);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            animator.addUpdateListener(a -> {
                final float t = (float) a.getAnimatedValue();
                scale = fromScale + (toScale - fromScale) * t;
                fill = fromFill + (toFill - fromFill) * t;
                ring = fromRing + (toRing - fromRing) * t;
                invalidate();
            });
            animator.start();
        }
    }

    private final ButtonAccent minusAccent = new ButtonAccent();
    private final ButtonAccent plusAccent = new ButtonAccent();
    private final ButtonAccent switchAccent = new ButtonAccent();

    public InstantZoomControlView(Context context, int chipBackgroundColor, int glyphColor) {
        super(context);
        minusDrawable = context.getResources().getDrawable(R.drawable.zoom_minus).mutate();
        plusDrawable = context.getResources().getDrawable(R.drawable.zoom_plus).mutate();
        switchDrawable = context.getResources().getDrawable(R.drawable.camera_revert1).mutate();
        knobDrawable = context.getResources().getDrawable(R.drawable.zoom_round);
        pressedKnobDrawable = context.getResources().getDrawable(R.drawable.zoom_round_b);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(AndroidUtilities.dpf2(1.5f));
        presetPaint.setTextSize(AndroidUtilities.dp(13));
        presetPaint.setTextAlign(Paint.Align.CENTER);
        presetPaint.setTypeface(AndroidUtilities.bold());
        updateColors(chipBackgroundColor, glyphColor);
    }

    // fractions are slider positions (0..1) for the matching labels; null or fewer than two hides them
    public void setPresets(float[] fractions, String[] labels) {
        if (fractions == null || labels == null || fractions.length < 2 || fractions.length != labels.length) {
            fractions = null;
            labels = null;
        }
        presetFractions = fractions;
        presetLabels = labels;
        presetPointer = -1;
        presetPressed = -1;
        if (labels != null) {
            float widest = 0f;
            for (String label : labels) {
                widest = Math.max(widest, presetPaint.measureText(label));
            }
            presetSegment = widest + AndroidUtilities.dp(28);
        }
        animatePresetShift(fractions != null ? 1f : 0f);
        invalidate();
    }

    // in the roomy layout the rows drop while the strip is up (track 18dp, buttons 8dp), so the strip gets
    // its own band above the pressed knob instead of the view growing
    private void animatePresetShift(float target) {
        if (presetShiftAnimator != null) {
            presetShiftAnimator.cancel();
            presetShiftAnimator = null;
        }
        if (presetShift == target) {
            return;
        }
        if (getAlpha() <= 0f || !isAttachedToWindow()) {
            presetShift = target;
            return;
        }
        presetShiftAnimator = ValueAnimator.ofFloat(presetShift, target);
        presetShiftAnimator.setDuration(150);
        presetShiftAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        presetShiftAnimator.addUpdateListener(a -> {
            presetShift = (float) a.getAnimatedValue();
            invalidate();
        });
        presetShiftAnimator.start();
    }

    private boolean presetsShown() {
        return zoomEnabled && presetFractions != null && compact < 1f;
    }

    public boolean hasPresets() {
        return presetFractions != null;
    }

    private float stripHeight() {
        return AndroidUtilities.dp(28);
    }

    // 2dp under the view's top edge in the roomy layout, where the track sits at 42dp while the strip is up
    private float stripTop() {
        return trackY - AndroidUtilities.dp(40);
    }

    // up to six stops with the crops, so on a narrow screen or a large font the segments shrink to keep
    // the strip 16dp inside each edge
    private float segment() {
        final int room = getMeasuredWidth() - AndroidUtilities.dp(32);
        return room > 0 ? Math.min(presetSegment, room / (float) presetFractions.length) : presetSegment;
    }

    private float stripLeft() {
        return (getMeasuredWidth() - segment() * presetFractions.length) / 2f;
    }

    // where the knob sits in segment units: whole numbers on a stop, fractional in between, so the
    // highlight slides along with a drag or a tap's eased jump
    private float presetSelection() {
        final float[] f = presetFractions;
        final int n = f.length;
        if (zoom <= f[0]) return 0f;
        if (zoom >= f[n - 1]) return n - 1;
        for (int i = 0; i < n - 1; i++) {
            if (zoom <= f[i + 1]) {
                return i + (zoom - f[i]) / Math.max(0.0001f, f[i + 1] - f[i]);
            }
        }
        return n - 1;
    }

    // the strip's hit box reaches a few dp past its edges; a hit anywhere inside picks that segment
    private int findPreset(float x, float y, float slop) {
        if (!presetsShown()) {
            return -1;
        }
        final float top = stripTop(), left = stripLeft();
        // slop reaches past the top and sides but barely below, where the knob's own touch zone starts
        if (y < top - slop || y > top + stripHeight() + Math.min(slop, AndroidUtilities.dp(2)) || x < left - slop || x > left + segment() * presetFractions.length + slop) {
            return -1;
        }
        return Math.max(0, Math.min(presetFractions.length - 1, (int) ((x - left) / segment())));
    }

    // re-read the recorder's colors so the chips follow a live theme flip (e.g. battery-saver dark
    // mode) instead of keeping the colors captured when the recorder was first built. The recorder owns
    // the glyph color so the zoom chips and its flash/infinite row always share one tint
    public void updateColors(int chipBackgroundColor, int glyphColor) {
        // only used when there's no glass chip to draw: a flat message-panel circle, the color the flash
        // button's background falls back to when blur is off
        chipColor = ColorUtils.setAlphaComponent(chipBackgroundColor, 0xFF);
        this.glyphColor = glyphColor;
        minusDrawable.setColorFilter(glyphColor, PorterDuff.Mode.SRC_IN);
        plusDrawable.setColorFilter(glyphColor, PorterDuff.Mode.SRC_IN);
        switchDrawable.setColorFilter(glyphColor, PorterDuff.Mode.SRC_IN);
        invalidate();
    }

    // hands the buttons the recorder's glass background so they match the flash button instead of sitting
    // on flat circles. one drawable per button since each samples the backdrop under its own spot; called
    // once, because every create() adds a position subscription that can't be taken back
    public void setButtonsBackground(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        if (minusChip != null) {
            return;
        }
        minusChip = factory.create(this, colorProvider);
        plusChip = factory.create(this, colorProvider);
        switchChip = factory.create(this, colorProvider);
        stripChip = factory.create(this, colorProvider);
        chipRadius = -1f;
        stripChipRadius = -1f;
        invalidate();
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setZoom(float value, boolean notify) {
        value = value < 0 ? 0 : Math.min(value, 1f);
        if (value == zoom) {
            return;
        }
        zoom = value;
        if (notify && delegate != null) {
            delegate.didSetZoom(zoom);
        }
        invalidate();
    }

    public boolean isTouch() {
        return knobPressed || trackPressed;
    }

    public boolean isDragging() {
        return knobPressed;
    }

    public boolean isCompact() {
        return compactTarget;
    }

    // The lowest point, in this view's own coordinates, that the track, knob or buttons take up left of x, so a
    // control in the bottom-left corner (the front flash brightness slider) can stop short of them. Negative
    // when nothing reaches that far left. The lens strip always sits above the track, so the track bounds it.
    // Measured against the touch zones in onTouchEvent: the rail from trackLeft, the knob 22dp either side.
    public float occupiedBottomLeftOf(float x) {
        updateGeometry();
        final float grab = AndroidUtilities.dp(22);
        float bottom = -1;
        final float knobX = travelLeft() + travelWidth() * zoom;
        if (zoomEnabled && Math.min(trackLeft, knobX - grab) < x) {
            bottom = trackY + AndroidUtilities.dp(24);
        }
        final float buttonReach = AndroidUtilities.dp(28);
        final float leftmostButton = zoomEnabled ? Math.min(switchCx, minusCx) : switchCx;
        if (leftmostButton - buttonReach < x) {
            bottom = Math.max(bottom, buttonCy + buttonReach);
        }
        return bottom;
    }

    public boolean isZoomEnabled() {
        return zoomEnabled;
    }

    // the slider + -/+ rocker draw and respond only when the current camera can zoom; the flip button
    // stays regardless, so switching cameras still works on a fixed-focus (no-zoom) camera
    public void setZoomEnabled(boolean enabled) {
        if (zoomEnabled == enabled) {
            return;
        }
        zoomEnabled = enabled;
        if (!enabled) {
            knobPressed = false;
            trackPressed = false;
            presetPointer = -1;
            presetPressed = -1;
            if (buttonPointerId != -1) {
                releaseButton(true);
            }
        }
        invalidate();
    }

    // decides the layout from the free space between the camera circle and the record controls;
    // ~14dp hysteresis so the keyboard slide animation doesn't flap it back and forth. two rows fit
    // from ~124dp of gap, so roomy is the main keyboard case and compact only kicks in when it's tight.
    public void setAvailableGap(float gap) {
        final boolean target = compactTarget ? gap < AndroidUtilities.dp(126) : gap < AndroidUtilities.dp(112);
        if (target == compactTarget) {
            return;
        }
        compactTarget = target;
        if (compactAnimator != null) {
            compactAnimator.cancel();
            compactAnimator = null;
        }
        if (getAlpha() <= 0f) {
            compact = target ? 1f : 0f;
            invalidate();
            return;
        }
        compactAnimator = ValueAnimator.ofFloat(compact, target ? 1f : 0f);
        compactAnimator.setDuration(150);
        compactAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        compactAnimator.addUpdateListener(a -> {
            compact = (float) a.getAnimatedValue();
            invalidate();
        });
        compactAnimator.start();
    }

    // called by the recorder when a hold actually starts zooming, to switch the button into its held look
    public void setButtonHeld(int direction) {
        final ButtonAccent accent = accent(direction);
        accent.held = true;
        accent.animateTo(1.15f, 1f, 1f, 120);
    }

    private ButtonAccent accent(int direction) {
        return direction < 0 ? minusAccent : plusAccent;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void updateGeometry() {
        final float w = getMeasuredWidth();
        // roomy centers on the full width: the slider sits a row above the recorder's view-once "(1)"
        // button and the rocker is a centered pair, so nothing reaches the right edge the button lives on.
        // compact keeps a right column clear of that button (the recorder draws it hard against the right
        // edge, centered at width - 26dp) since its -/+ dock on the right of the single row.
        final float compactW = w - AndroidUtilities.dp(56);
        // roomy without the lens strip: slider row on top (centerline 24dp), 48dp rocker pair centered at
        // 68dp, 12dp apart. trim the line ~20dp so it doesn't run edge to edge; roomyLeft keeps it centered.
        final float roomyWidth = Math.min(w - AndroidUtilities.dp(64), AndroidUtilities.dp(300)) - AndroidUtilities.dp(20);
        final float roomyLeft = (w - roomyWidth) / 2f;
        // compact: one row at 52dp, 20dp side margins, [slider] 16dp [flip] 10dp [-] 10dp [+], 40dp buttons
        final float compactPlusCx = compactW - AndroidUtilities.dp(20 + 20);
        final float compactMinusCx = compactPlusCx - AndroidUtilities.dp(50);
        // flip button sits one slot left of the rocker in both layouts
        final float compactSwitchCx = compactMinusCx - AndroidUtilities.dp(50);
        trackLeft = lerp(roomyLeft, AndroidUtilities.dp(20), compact);
        // in compact the slider stops short of the flip button, not the minus button
        trackRight = lerp(roomyLeft + roomyWidth, compactSwitchCx - AndroidUtilities.dp(20 + 16), compact);
        // the lens strip pushes the roomy rows down (track 24 to 42dp, buttons 68 to 76dp) instead of growing
        // the view: the pressed knob clears the strip above and the buttons below, which end 4dp inside it
        trackY = lerp(lerp(AndroidUtilities.dp(24), AndroidUtilities.dp(42), presetShift), AndroidUtilities.dp(52), compact);
        // roomy centers the [flip][-][+] trio (60dp apart) so the group stays under the circle's center
        switchCx = lerp(w / 2f - AndroidUtilities.dp(60), compactSwitchCx, compact);
        minusCx = lerp(w / 2f, compactMinusCx, compact);
        plusCx = lerp(w / 2f + AndroidUtilities.dp(60), compactPlusCx, compact);
        // roomy row sits at 68dp (was 76): pulling it up shortens the two-row block so it still fits
        // above the input island when a reply's top view eats into the space below the camera circle.
        // the lens strip takes it back to 76dp; the recorder gives that taller block a wider roomy gate
        buttonCy = lerp(lerp(AndroidUtilities.dp(68), AndroidUtilities.dp(76), presetShift), AndroidUtilities.dp(52), compact);
        buttonRadius = lerp(AndroidUtilities.dp(24), AndroidUtilities.dp(20), compact);
        glyphHalf = lerp(AndroidUtilities.dp(11), AndroidUtilities.dp(9), compact);
        switchGlyphHalf = lerp(AndroidUtilities.dp(13), AndroidUtilities.dp(11), compact);
    }

    // inset the knob travel by its own radius so it reaches right up to each track end without spilling past it
    private float knobInset() {
        return knobDrawable.getIntrinsicWidth() / 2f;
    }

    private float travelLeft() {
        return trackLeft + knobInset();
    }

    private float travelWidth() {
        return Math.max(1f, trackRight - trackLeft - knobInset() * 2f);
    }

    private boolean insideButton(float x, float y, int direction) {
        final float cx = direction < 0 ? minusCx : plusCx;
        final float radius = AndroidUtilities.dp(28);
        return x >= cx - radius && x <= cx + radius && y >= buttonCy - radius && y <= buttonCy + radius;
    }

    private boolean insideSwitch(float x, float y) {
        final float radius = AndroidUtilities.dp(28);
        return x >= switchCx - radius && x <= switchCx + radius && y >= buttonCy - radius && y <= buttonCy + radius;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        updateGeometry();
        final int action = event.getActionMasked();
        if (buttonPointerId != -1) {
            final int index = event.findPointerIndex(buttonPointerId);
            if (action == MotionEvent.ACTION_CANCEL || index == -1) {
                releaseButton(true);
            } else if (action == MotionEvent.ACTION_MOVE) {
                final float radius = AndroidUtilities.dp(40);
                final float cx = buttonDirection < 0 ? minusCx : plusCx;
                if (Math.abs(event.getX(index) - cx) > radius || Math.abs(event.getY(index) - buttonCy) > radius) {
                    releaseButton(true);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == buttonPointerId) {
                releaseButton(!insideButton(event.getX(index), event.getY(index), buttonDirection));
            }
            return true;
        }
        if (switchPointerId != -1) {
            final int index = event.findPointerIndex(switchPointerId);
            if (action == MotionEvent.ACTION_CANCEL || index == -1) {
                cancelSwitch();
            } else if (action == MotionEvent.ACTION_MOVE) {
                final float radius = AndroidUtilities.dp(40);
                if (Math.abs(event.getX(index) - switchCx) > radius || Math.abs(event.getY(index) - buttonCy) > radius) {
                    cancelSwitch();
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == switchPointerId) {
                final boolean inside = insideSwitch(event.getX(index), event.getY(index));
                cancelSwitch();
                if (inside && delegate != null) {
                    delegate.onSwitchCamera();
                }
            }
            return true;
        }
        if (presetPointer != -1) {
            final int index = event.findPointerIndex(presetPointer);
            if (action == MotionEvent.ACTION_CANCEL || index == -1) {
                presetPointer = -1;
                presetPressed = -1;
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == presetPointer) {
                final int pressed = presetPressed;
                final boolean inside = pressed != -1 && findPreset(event.getX(index), event.getY(index), AndroidUtilities.dp(16)) == pressed;
                presetPointer = -1;
                presetPressed = -1;
                if (inside) {
                    if (!tw.nekomimi.nekogram.NekoConfig.disableVibration.Bool()) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                    // same route as a tap on the rail: the recorder eases the knob over to it
                    zoom = presetFractions[pressed];
                    if (delegate != null) {
                        delegate.didSetZoom(zoom);
                    }
                }
                invalidate();
            }
            return true;
        }
        final float x = event.getX();
        final float y = event.getY();
        final float knobX = travelLeft() + travelWidth() * zoom;
        if (action == MotionEvent.ACTION_DOWN) {
            final int preset = findPreset(x, y, AndroidUtilities.dp(6));
            if (preset != -1) {
                presetPointer = event.getPointerId(0);
                presetPressed = preset;
                invalidate();
                return true;
            }
            // the flip / -/+ buttons share a row and their 28dp hit boxes overlap; the nearest present
            // center wins. the flip button is always live; the rocker responds only when zoom is enabled.
            if (Math.abs(y - buttonCy) <= AndroidUtilities.dp(28)) {
                int hit = 0; // 2 = flip, -1 = minus, 1 = plus
                float best = AndroidUtilities.dp(28);
                float d = Math.abs(x - switchCx);
                if (d < best) { best = d; hit = 2; }
                if (zoomEnabled) {
                    d = Math.abs(x - minusCx);
                    if (d < best) { best = d; hit = -1; }
                    d = Math.abs(x - plusCx);
                    if (d < best) { best = d; hit = 1; }
                }
                if (hit == 2) {
                    switchPointerId = event.getPointerId(0);
                    switchAccent.fill = 1f;
                    switchAccent.animateTo(0.92f, 1f, 0f, 80);
                    return true;
                }
                if (hit != 0) {
                    buttonPointerId = event.getPointerId(0);
                    buttonDirection = hit;
                    final ButtonAccent accent = accent(hit);
                    accent.held = false;
                    accent.fill = 1f;
                    accent.animateTo(0.92f, 1f, 0f, 80);
                    if (delegate != null) {
                        delegate.onButtonDown(hit);
                    }
                    return true;
                }
            }
            if (zoomEnabled) {
                if (Math.abs(x - knobX) <= AndroidUtilities.dp(22) && Math.abs(y - trackY) <= AndroidUtilities.dp(24)) {
                    knobPressed = true;
                    knobOffsetX = knobX - x;
                    invalidate();
                    return true;
                }
                if (x >= trackLeft && x <= trackRight && Math.abs(y - trackY) <= AndroidUtilities.dp(24)) {
                    trackPressed = true;
                    trackDownX = x;
                    return true;
                }
            }
            return false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (knobPressed) {
                setZoomFromTouch(x + knobOffsetX);
                return true;
            }
            return trackPressed;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (trackPressed && action == MotionEvent.ACTION_UP && Math.abs(x - trackDownX) <= AndroidUtilities.dp(10)) {
                // a tap on the rail: report the target and let the recorder animate the jump
                setZoomFromTouch(x);
            }
            final boolean handled = knobPressed || trackPressed;
            knobPressed = false;
            trackPressed = false;
            invalidate();
            return handled;
        }
        return knobPressed || trackPressed;
    }

    private void setZoomFromTouch(float x) {
        zoom = Math.max(0f, Math.min(1f, (x - travelLeft()) / travelWidth()));
        if (delegate != null) {
            delegate.didSetZoom(zoom);
        }
        invalidate();
    }

    private void releaseButton(boolean cancelled) {
        buttonPointerId = -1;
        final ButtonAccent accent = accent(buttonDirection);
        accent.animateTo(1f, 0f, 0f, accent.held ? 180 : 150);
        accent.held = false;
        if (delegate != null) {
            delegate.onButtonUp(buttonDirection, cancelled);
        }
    }

    private void cancelSwitch() {
        switchPointerId = -1;
        switchAccent.animateTo(1f, 0f, 0f, 150);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        updateGeometry();
        if (zoomEnabled) {
            final float half = AndroidUtilities.dpf2(2f);
            // the line spans only the knob's center travel, so at the ends the dot caps it and no stub shows past it
            final float lineLeft = travelLeft();
            final float lineRight = travelLeft() + travelWidth();
            final float knobX = lineLeft + (lineRight - lineLeft) * zoom;

            trackPaint.setColor(0x4DFFFFFF);
            trackRect.set(lineLeft, trackY - half, lineRight, trackY + half);
            canvas.drawRoundRect(trackRect, half, half, trackPaint);
            trackPaint.setColor(0xFFFFFFFF);
            trackRect.set(lineLeft, trackY - half, knobX, trackY + half);
            canvas.drawRoundRect(trackRect, half, half, trackPaint);

            if (presetsShown()) {
                drawPresetStrip(canvas);
            }

            final Drawable knob = knobPressed ? pressedKnobDrawable : knobDrawable;
            final int knobHalf = knob.getIntrinsicWidth() / 2;
            knob.setBounds((int) knobX - knobHalf, (int) trackY - knobHalf, (int) knobX + knobHalf, (int) trackY + knobHalf);
            knob.draw(canvas);

            drawButton(canvas, minusDrawable, minusAccent, minusChip, minusCx, glyphHalf);
            drawButton(canvas, plusDrawable, plusAccent, plusChip, plusCx, glyphHalf);
        }
        drawButton(canvas, switchDrawable, switchAccent, switchChip, switchCx, switchGlyphHalf);
    }

    // one glass capsule split into segments, the StoryModeTabs look: a highlight lerped between the two
    // segments the knob sits between. fades with the compact layout and the track's drop
    private void drawPresetStrip(Canvas canvas) {
        final int n = presetFractions.length;
        final float alpha = (1f - compact) * presetShift;
        if (alpha <= 0f) {
            return;
        }
        final float top = stripTop(), left = stripLeft(), h = stripHeight();
        final int l = Math.round(left), t = Math.round(top), r = Math.round(left + segment() * n), b = Math.round(top + h);
        final int radius = Math.round(h / 2f);
        final int save = alpha < 1f ? canvas.saveLayerAlpha(l - radius, t - radius, r + radius, b + radius, (int) (0xFF * alpha)) : canvas.save();
        if (stripChip != null) {
            stripChip.setBounds(l, t, r, b);
            if (stripChipRadius != radius) {
                stripChipRadius = radius;
                stripChip.setRadius(radius);
            }
            stripChip.draw(canvas);
        } else {
            stripRect.set(l, t, r, b);
            chipPaint.setColor(chipColor);
            canvas.drawRoundRect(stripRect, radius, radius, chipPaint);
            ringPaint.setColor(ColorUtils.setAlphaComponent(glyphColor, 0x22));
            canvas.drawRoundRect(stripRect, radius, radius, ringPaint);
        }
        final float selection = presetSelection();
        final float inset = AndroidUtilities.dp(3);
        final float hx = left + segment() * selection;
        stripRect.set(hx + inset, top + inset, hx + segment() - inset, top + h - inset);
        chipPaint.setColor(ColorUtils.setAlphaComponent(glyphColor, presetPressed != -1 ? 0x44 : 0x2E));
        canvas.drawRoundRect(stripRect, radius - inset, radius - inset, chipPaint);
        final float baseline = top + h / 2f - (presetPaint.descent() + presetPaint.ascent()) / 2f;
        for (int i = 0; i < n; i++) {
            final float near = Math.max(0f, 1f - Math.abs(selection - i));
            presetPaint.setColor(ColorUtils.setAlphaComponent(glyphColor, (int) (0x99 + (0xFF - 0x99) * near)));
            canvas.drawText(presetLabels[i], left + segment() * (i + 0.5f), baseline, presetPaint);
        }
        canvas.restoreToCount(save);
    }

    private void drawButton(Canvas canvas, Drawable glyph, ButtonAccent accent, BlurredBackgroundDrawable chip, float cx, float glyphHalfPx) {
        final int radius = Math.round(buttonRadius);
        // the chip's bounds are integers, so everything else centers on the same rounded point or the glyph
        // ends up a pixel off the circle it sits in
        final int icx = Math.round(cx), icy = Math.round(buttonCy);
        // the press bounce runs through the canvas instead of the chip's own radius: re-cutting the glass
        // drawable's geometry every frame would rebuild its render node (and, with blur off, its nine-patch)
        canvas.save();
        if (accent.scale != 1f) {
            canvas.scale(accent.scale, accent.scale, icx, icy);
        }
        if (chip != null) {
            chip.setBounds(icx - radius, icy - radius, icx + radius, icy + radius);
            if (chipRadius != radius) {
                chipRadius = radius;
                minusChip.setRadius(radius);
                plusChip.setRadius(radius);
                switchChip.setRadius(radius);
            }
            chip.draw(canvas);
        } else {
            // no glass to sit on (the stories recorder never hands one over), so keep the flat circle plus
            // the faint rim that stops it vanishing into a backdrop of the same color
            chipPaint.setColor(chipColor);
            canvas.drawCircle(icx, icy, radius, chipPaint);
            ringPaint.setColor(ColorUtils.setAlphaComponent(glyphColor, 0x22));
            canvas.drawCircle(icx, icy, radius, ringPaint);
        }
        if (accent.fill > 0f) {
            chipPaint.setColor(ColorUtils.setAlphaComponent(glyphColor, (int) (0x1F * accent.fill)));
            canvas.drawCircle(icx, icy, radius, chipPaint);
        }
        if (accent.ring > 0f) {
            ringPaint.setColor(ColorUtils.setAlphaComponent(glyphColor, (int) (0x99 * accent.ring)));
            canvas.drawCircle(icx, icy, radius, ringPaint);
        }
        final int gh = Math.round(glyphHalfPx);
        glyph.setBounds(icx - gh, icy - gh, icx + gh, icy + gh);
        glyph.draw(canvas);
        canvas.restore();
    }
}
