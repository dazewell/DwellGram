package com.dazewell.gram.videonote;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * #camera-memo-zoom: the camera memo's zoom, as a stock camera has it. A row of stops along the viewfinder's bottom edge,
 * tap one to jump there; hold one, or slide along the row, and a dial rises in its place to reach any value in between.
 * Laid out over the viewfinder exactly. It draws nothing and takes no touch outside the row until the dial is open.
 *
 * All the zoom state is the recorder's (InstantCameraView.getCoverZoom); this only holds what the finger is doing.
 * Synced on the cover's tick, which also catches a flip: a new camera's range and stops rebuild the row, close the dial
 * and drop the drag in progress, so it can't carry on through the old camera's ruler.
 */
@SuppressLint("ViewConstructor")
final class CameraMemoZoom extends View {

    interface Host {
        /** {min, max, current} zoom ratios, or null while there's nothing to zoom. */
        float[] zoom();

        /** The camera's lens stops, or null/empty when it reports none. */
        float[] stops();

        void setZoom(float ratio, boolean animate);
    }

    private static final int ORANGE = 0xffff9500;
    private static final int DISC = 0x99000000;
    private static final int CHIP = 0x66000000;
    private static final long HOLD_MS = 300;
    private static final long DIAL_ANIM_MS = 180;
    private static final long DIAL_LINGER_MS = 1_200;
    private static final long TAP_SETTLE_MS = 450; // the tap's own animation is at most 350 ms
    private static final float DRAG_DP_PER_DOUBLING = 120;
    private static final float DEG_PER_DOUBLING = 40; // the ruler's own spacing: about two doublings either side show
    private static final float DETENT_DP = 8;
    private static final float DETENT_EXIT_DP = 14;
    private static final float SAME = 0.02f; // |ln| below which two ratios read as the same stop
    // Ruler labels besides the lens stops, as a camera's dial marks them
    private static final float[] MAJORS = {1f, 1.5f, 2f, 3f, 5f, 10f, 20f, 50f, 100f};
    private static final float[] CROPS = {1.5f, 2f};
    private static final float CROP_GAP = 0.26f; // about ln 1.3: a crop stop only where nothing is within 30% of it

    private final Host host;
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path discPath = new Path();
    private final RectF discRect = new RectF();
    private final int touchSlop;
    private final Stops a11y;

    private boolean available;
    private float min;
    private float max;
    private float current = 1f;
    private float[] stops = new float[0];
    private float[] majors = new float[0];
    private float rowBand; // the viewfinder's strip below the crop, where the row sits when it fits

    // The dial: 0 closed, 1 open
    private float dial;
    private boolean dialOpen;
    private ValueAnimator dialAnimator;

    private int downStop = -1;
    private float downX;
    private float downY;
    private boolean dragging;
    private boolean dragAbandoned; // a flip mid-drag: the rest of this gesture is ignored
    private float dragStartX;
    private float dragStartLog;
    private int detent = -1;
    private boolean atEnd;
    private float tapTarget;
    private long tapAt;

    private final Runnable holdOpens = () -> {
        if (downStop >= 0 && !dragging) {
            haptic(HapticFeedbackConstants.LONG_PRESS);
            beginDrag(downX);
        }
    };
    private final Runnable dialCloses = () -> setDialOpen(false);

    CameraMemoZoom(Context context, Host host) {
        super(context);
        this.host = host;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        labelPaint.setTypeface(medium);
        labelPaint.setTextSize(sp(14));
        labelPaint.setColor(0xd9ffffff);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFontFeatureSettings("tnum");
        labelPaint.setShadowLayer(AndroidUtilities.dp(1), 0, 0, 0x99000000); // over the live picture, as bright as the text
        activePaint.set(labelPaint);
        activePaint.setColor(ORANGE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        a11y = new Stops();
        ViewCompat.setAccessibilityDelegate(this, a11y);
        setVisibility(GONE);
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }

    /** The height of the viewfinder's strip below the crop, in px. */
    void setRowBand(float band) {
        if (rowBand != band) {
            rowBand = band;
            invalidate();
        }
    }

    /** True when a touch at this point, in this view's coordinates, is the zoom's and not the crop's. */
    boolean claims(float x, float y) {
        if (getVisibility() != VISIBLE || x < 0 || y < 0 || x > getWidth() || y > getHeight()) {
            return false;
        }
        return dialOpen || stopAt(x, y) >= 0;
    }

    /** On the cover's tick: what the recorder zooms now, and whether the zoom may show at all. */
    void sync(boolean show) {
        float[] zoom = show ? host.zoom() : null;
        float[] newStops = zoom != null ? buildStops(zoom[0], zoom[1], host.stops()) : null;
        if (newStops == null || newStops.length < 2) {
            if (available) {
                available = false;
                reset();
                setVisibility(GONE);
            }
            return;
        }
        boolean rebuilt = !available || Math.abs(zoom[0] / min - 1f) > 0.01f || Math.abs(zoom[1] / max - 1f) > 0.01f
                || !Arrays.equals(stops, newStops);
        if (rebuilt) {
            if (available) { // a flip
                if (dragging) {
                    dragAbandoned = true;
                }
                dragging = false;
                downStop = -1;
                removeCallbacks(holdOpens);
                setDialOpen(false);
            }
            available = true;
            min = zoom[0];
            max = zoom[1];
            stops = newStops;
            majors = buildMajors();
            a11y.invalidateRoot();
        }
        if (!dragging && SystemClock.elapsedRealtime() - tapAt > TAP_SETTLE_MS) {
            current = zoom[2];
        }
        setVisibility(VISIBLE);
        invalidate();
    }

    private void reset() {
        removeCallbacks(holdOpens);
        removeCallbacks(dialCloses);
        if (dialAnimator != null) {
            dialAnimator.cancel();
            dialAnimator = null;
        }
        dialOpen = false;
        dial = 0f;
        dragging = false;
        downStop = -1;
    }

    // The camera's lens stops as they are. Without any, 1x, 2x and the top of the range up to 4x. Then 1.5x and 2x
    // (35 and 50 mm on the usual 24 mm main camera) wherever no stop is already near: the main sensor's crops a stock camera offers, which no Camera2
    // key reports.
    private static float[] buildStops(float min, float max, float[] lens) {
        ArrayList<Float> list = new ArrayList<>();
        if (lens != null && lens.length >= 2) {
            for (float s : lens) {
                add(list, Math.max(min, Math.min(max, s)));
            }
        } else {
            add(list, Math.max(min, 1f));
            if (max >= 2f) {
                add(list, 2f);
            }
            add(list, Math.min(max, 4f));
        }
        for (float crop : CROPS) {
            if (crop >= min && crop <= max && !near(list, crop)) {
                list.add(crop);
            }
        }
        float[] result = new float[list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = list.get(i);
        }
        Arrays.sort(result);
        return result;
    }

    private static void add(ArrayList<Float> list, float ratio) {
        for (float s : list) {
            if (Math.abs(Math.log(s / ratio)) < SAME) {
                return;
            }
        }
        list.add(ratio);
    }

    private static boolean near(ArrayList<Float> list, float ratio) {
        for (float s : list) {
            if (Math.abs(Math.log(s / ratio)) < CROP_GAP) {
                return true;
            }
        }
        return false;
    }

    private float[] buildMajors() {
        ArrayList<Float> list = new ArrayList<>();
        for (float s : stops) {
            add(list, s);
        }
        for (float m : MAJORS) {
            if (m >= min * 0.999f && m <= max * 1.001f) {
                add(list, m);
            }
        }
        float[] result = new float[list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    // Whole numbers bare, the rest to one decimal, as the stops read on a camera
    static String format(float ratio) {
        float tenths = Math.round(ratio * 10f) / 10f; // rounded first, so 1.96 reads 2, not 2.0
        if (tenths >= 10f || tenths == Math.round(tenths)) {
            return String.valueOf(Math.round(tenths));
        }
        return String.format(Locale.US, "%.1f", tenths);
    }

    private static float log2(float value) {
        return (float) (Math.log(value) / Math.log(2));
    }

    // The stop whose lens is in use: the highest one at or below the zoom, the way a camera's own buttons follow it
    private int activeStop() {
        float value = SystemClock.elapsedRealtime() - tapAt <= TAP_SETTLE_MS ? tapTarget : current;
        int active = 0;
        for (int i = 0; i < stops.length; i++) {
            if (stops[i] <= value * (1f + SAME)) {
                active = i;
            }
        }
        return active;
    }

    private float displayed() {
        return SystemClock.elapsedRealtime() - tapAt <= TAP_SETTLE_MS ? tapTarget : current;
    }

    private float rowCenterY() {
        float half = AndroidUtilities.dp(24);
        return rowBand >= half * 2 ? getHeight() - rowBand / 2f : getHeight() - AndroidUtilities.dp(12) - half;
    }

    private float spacing() {
        return Math.min(AndroidUtilities.dp(56), getWidth() / (float) Math.max(1, stops.length));
    }

    private float stopX(int index) {
        int n = stops.length;
        int slot = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? n - 1 - index : index;
        return getWidth() / 2f + (slot - (n - 1) / 2f) * spacing();
    }

    private int stopAt(float x, float y) {
        if (!available || dial > 0f || Math.abs(y - rowCenterY()) > AndroidUtilities.dp(24)) {
            return -1;
        }
        float half = spacing() / 2f;
        for (int i = 0; i < stops.length; i++) {
            if (Math.abs(x - stopX(i)) <= half) {
                return i;
            }
        }
        return -1;
    }

    private float radius() {
        return Math.min(AndroidUtilities.dp(150), Math.min(getHeight() * 0.4f, getWidth() / 2f));
    }

    private boolean inDial(float x, float y) {
        float dx = x - getWidth() / 2f;
        float dy = y - getHeight();
        float r = radius();
        return dy <= 0 && dx * dx + dy * dy <= r * r;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragAbandoned = false;
                if (!available) {
                    return false;
                }
                if (dialOpen) {
                    removeCallbacks(dialCloses);
                    if (inDial(x, y)) {
                        beginDrag(x);
                    } else {
                        setDialOpen(false);
                        downStop = -1;
                    }
                    return true;
                }
                downStop = stopAt(x, y);
                if (downStop < 0) {
                    return false;
                }
                downX = x;
                downY = y;
                postDelayed(holdOpens, HOLD_MS);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragAbandoned) {
                    return true;
                }
                if (dragging) {
                    drag(x);
                } else if (downStop >= 0 && Math.abs(x - downX) > touchSlop && Math.abs(x - downX) > Math.abs(y - downY)) {
                    removeCallbacks(holdOpens); // sliding along the row opens the dial as a hold does
                    beginDrag(downX);
                    drag(x);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // One finger's gesture. A second one ends it where it is, or a later move could read the wrong finger
                // against the first one's start and jump the zoom.
                removeCallbacks(holdOpens);
                if (dragging) {
                    dragging = false;
                    postDelayed(dialCloses, DIAL_LINGER_MS);
                }
                downStop = -1;
                dragAbandoned = true;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(holdOpens);
                boolean up = event.getActionMasked() == MotionEvent.ACTION_UP;
                if (dragging) {
                    dragging = false;
                    postDelayed(dialCloses, DIAL_LINGER_MS);
                } else if (up && !dragAbandoned && downStop >= 0 && downStop < stops.length) {
                    onStopTapped(downStop);
                }
                downStop = -1;
                dragAbandoned = false;
                return true;
            }
        }
        return true;
    }

    private void onStopTapped(int index) {
        float stop = stops[index];
        if (Math.abs(Math.log(displayed() / stop)) < SAME * 0.5f) {
            return;
        }
        haptic(HapticFeedbackConstants.KEYBOARD_TAP);
        tapTarget = stop;
        tapAt = SystemClock.elapsedRealtime();
        current = stop;
        host.setZoom(stop, true);
        a11y.invalidateRoot();
        invalidate();
    }

    private void beginDrag(float x) {
        removeCallbacks(dialCloses);
        dragging = true;
        dragStartX = x;
        tapAt = 0;
        dragStartLog = log2(current);
        detent = nearestStop(dragStartLog, AndroidUtilities.dp(DETENT_DP));
        atEnd = false;
        setDialOpen(true);
    }

    private int nearestStop(float log, float withinPx) {
        float perDoubling = AndroidUtilities.dp(DRAG_DP_PER_DOUBLING);
        for (int i = 0; i < stops.length; i++) {
            if (Math.abs(log - log2(stops[i])) * perDoubling <= withinPx) {
                return i;
            }
        }
        return -1;
    }

    private void drag(float x) {
        float perDoubling = AndroidUtilities.dp(DRAG_DP_PER_DOUBLING);
        float direction = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -1f : 1f;
        float lo = log2(min);
        float hi = log2(max);
        float log = dragStartLog + (dragStartX - x) / perDoubling * direction;
        boolean end = log <= lo || log >= hi;
        log = Math.max(lo, Math.min(hi, log));
        // Stops hold the dial a little, with more pull to leave than to enter, so it doesn't flutter on the edge. Not
        // at the range's ends: a stop just short of one (4x on a 4.2x camera) would hold the dial off it for good.
        if (end) {
            detent = -1;
        } else if (detent >= 0 && Math.abs(log - log2(stops[detent])) * perDoubling <= AndroidUtilities.dp(DETENT_EXIT_DP)) {
            log = log2(stops[detent]);
        } else {
            int next = nearestStop(log, AndroidUtilities.dp(DETENT_DP));
            if (next >= 0 && next != detent) {
                haptic(HapticFeedbackConstants.CLOCK_TICK);
            }
            detent = next;
            if (next >= 0) {
                log = log2(stops[next]);
            }
        }
        if (end && !atEnd && detent < 0) {
            haptic(HapticFeedbackConstants.CLOCK_TICK);
        }
        atEnd = end;
        float ratio = (float) Math.pow(2, log);
        if (Math.abs(ratio / current - 1f) > 0.001f) {
            current = ratio;
            host.setZoom(ratio, false);
            invalidate();
        }
    }

    private void setDialOpen(boolean open) {
        if (dialOpen == open) {
            return;
        }
        dialOpen = open;
        if (!open) {
            removeCallbacks(dialCloses);
        }
        if (dialAnimator != null) {
            dialAnimator.cancel();
        }
        dialAnimator = ValueAnimator.ofFloat(dial, open ? 1f : 0f);
        dialAnimator.addUpdateListener(a -> {
            dial = (float) a.getAnimatedValue();
            invalidate();
        });
        dialAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation == dialAnimator) {
                    dialAnimator = null;
                }
            }
        });
        dialAnimator.setDuration(DIAL_ANIM_MS);
        dialAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        dialAnimator.start();
        a11y.invalidateRoot();
    }

    private void haptic(int feedback) {
        try {
            if (!NekoConfig.disableVibration.Bool()) {
                performHapticFeedback(feedback);
            }
        } catch (Exception ignore) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        reset();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!available) {
            return;
        }
        if (dial < 1f) {
            drawRow(canvas, 1f - dial);
        }
        if (dial > 0f) {
            drawDial(canvas, dial);
        }
    }

    private void drawRow(Canvas canvas, float alpha) {
        float cy = rowCenterY();
        int active = activeStop();
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2f;
        for (int i = 0; i < stops.length; i++) {
            float x = stopX(i);
            if (i == active) {
                fillPaint.setColor(CHIP);
                fillPaint.setAlpha((int) (Color.alpha(CHIP) * alpha));
                canvas.drawCircle(x, cy, AndroidUtilities.dp(18), fillPaint);
                activePaint.setAlpha((int) (255 * alpha));
                canvas.drawText(format(displayed()) + "×", x, baseline, activePaint);
            } else {
                labelPaint.setAlpha((int) (0xd9 * alpha));
                canvas.drawText(format(stops[i]), x, baseline, labelPaint);
            }
        }
    }

    private void drawDial(Canvas canvas, float progress) {
        float cx = getWidth() / 2f;
        float cy = getHeight();
        float r = radius();
        canvas.save();
        float scale = 0.85f + 0.15f * progress;
        canvas.scale(scale, scale, cx, cy);
        int alpha = (int) (255 * progress);

        discRect.set(cx - r, cy - r, cx + r, cy + r);
        discPath.reset();
        discPath.arcTo(discRect, 180f, 180f, true);
        discPath.close();
        fillPaint.setColor(DISC);
        fillPaint.setAlpha(Color.alpha(DISC) * alpha / 255);
        canvas.drawPath(discPath, fillPaint);

        float direction = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -1f : 1f;
        float now = log2(current);
        float lo = log2(min);
        float hi = log2(max);
        float rim = r - AndroidUtilities.dp(6);
        tickPaint.setColor(0xffffffff);

        // Minor ticks every tenth of a doubling, skipped where a major one stands
        tickPaint.setStrokeWidth(AndroidUtilities.dp(1.5f));
        for (int k = (int) Math.ceil(lo * 10); k <= Math.floor(hi * 10); k++) {
            float log = k / 10f;
            if (nearMajor(log)) {
                continue;
            }
            drawTick(canvas, cx, cy, rim, AndroidUtilities.dp(6), angle(log, now, direction), 0x99, alpha);
        }
        tickPaint.setStrokeWidth(AndroidUtilities.dp(2));
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float labelRadius = rim - AndroidUtilities.dp(28);
        for (float m : majors) {
            float a = angle(log2(m), now, direction);
            if (Math.abs(a + 90f) > 92f) {
                continue;
            }
            drawTick(canvas, cx, cy, rim, AndroidUtilities.dp(12), a, 0xff, alpha);
            if (Math.abs(a + 90f) < 16f || Math.abs(a + 90f) > 80f) {
                continue; // the current value has the top; the ends would sit on the viewfinder's edge
            }
            double rad = Math.toRadians(a);
            float x = cx + labelRadius * (float) Math.cos(rad);
            float y = cy + labelRadius * (float) Math.sin(rad) - (metrics.ascent + metrics.descent) / 2f;
            labelPaint.setAlpha(0xd9 * alpha / 255);
            canvas.drawText(format(m), x, y, labelPaint);
        }

        // The index: fixed at the top, the scale turns under it
        tickPaint.setColor(ORANGE);
        tickPaint.setAlpha(alpha);
        tickPaint.setStrokeWidth(AndroidUtilities.dp(2.5f));
        canvas.drawLine(cx, cy - rim, cx, cy - rim + AndroidUtilities.dp(16), tickPaint);
        float textSize = activePaint.getTextSize();
        activePaint.setTextSize(sp(17));
        activePaint.setAlpha(alpha);
        canvas.drawText(format(current) + "×", cx, cy - labelRadius - (activePaint.ascent() + activePaint.descent()) / 2f, activePaint);
        activePaint.setTextSize(textSize);
        canvas.restore();
    }

    private boolean nearMajor(float log) {
        for (float m : majors) {
            if (Math.abs(log - log2(m)) < 0.04f) {
                return true;
            }
        }
        return false;
    }

    // Straight up is the current value; higher values lie clockwise (counter-clockwise in RTL)
    private static float angle(float log, float now, float direction) {
        return -90f + (log - now) * DEG_PER_DOUBLING * direction;
    }

    private void drawTick(Canvas canvas, float cx, float cy, float rim, float length, float angle, int ink, int alpha) {
        if (Math.abs(angle + 90f) > 92f) {
            return;
        }
        double rad = Math.toRadians(angle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        tickPaint.setColor(0xffffffff);
        tickPaint.setAlpha(ink * alpha / 255);
        canvas.drawLine(cx + rim * cos, cy + rim * sin, cx + (rim - length) * cos, cy + (rim - length) * sin, tickPaint);
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent event) {
        return a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    // Each stop is its own button for TalkBack; the dial is a drag and has no node of its own
    private final class Stops extends ExploreByTouchHelper {

        Stops() {
            super(CameraMemoZoom.this);
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            int stop = stopAt(x, y);
            return stop >= 0 ? stop : INVALID_ID;
        }

        @Override
        protected void getVisibleVirtualViews(List<Integer> ids) {
            if (!available || dial > 0f) {
                return;
            }
            for (int i = 0; i < stops.length; i++) {
                ids.add(i);
            }
        }

        @Override
        protected void onPopulateNodeForVirtualView(int id, @NonNull AccessibilityNodeInfoCompat node) {
            float stop = id < stops.length ? stops[id] : 1f;
            node.setClassName("android.widget.Button");
            node.setContentDescription(LocaleController.formatString(R.string.CameraMemoZoomStop, format(stop) + "×"));
            node.setSelected(id == activeStop());
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
            int x = (int) stopX(Math.min(id, Math.max(0, stops.length - 1)));
            int y = (int) rowCenterY();
            int half = (int) (spacing() / 2f);
            node.setBoundsInParent(new Rect(x - half, y - AndroidUtilities.dp(24), x + half, y + AndroidUtilities.dp(24)));
        }

        @Override
        protected boolean onPerformActionForVirtualView(int id, int action, Bundle arguments) {
            if (action != AccessibilityNodeInfo.ACTION_CLICK || id < 0 || id >= stops.length) {
                return false;
            }
            onStopTapped(id);
            return true;
        }
    }
}
