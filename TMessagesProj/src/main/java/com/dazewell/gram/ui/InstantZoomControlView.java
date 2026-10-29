package com.dazewell.gram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.graphics.ColorUtils;

import com.dazewell.gram.videonote.CameraMemo;
import com.dazewell.gram.videonote.CameraMemoZoom;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.InstantCameraView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

/**
 * #video-zoom: the round video recorder's zoom, the camera memo's own (CameraMemoZoom): a row of stops under the
 * circle, tap one to jump, hold or slide to open a dial that rises over the circle's bottom. The camera flip sits
 * at the row's end, since the hold's sideways swipe only works mid-recording and only with its gestures on.
 *
 * The view is the row's 56dp band plus the dial's reach above it. Only a stop, the flip button or the open dial
 * take a touch; everything else here falls through to the recorder, so the circle's hold and pinch still work
 * over the part of the circle this view overlaps.
 */
@SuppressLint("ViewConstructor")
public class InstantZoomControlView extends FrameLayout {

    public static final int ROW_DP = 56;
    public static final int REACH_DP = 150;
    public static final int HEIGHT_DP = ROW_DP + REACH_DP;
    private static final int FLIP_DP = 44;
    private static final int EDGE_DP = 16;
    private static final int ONCE_DP = 56; // the composer's view-once "(1)", centred 26dp in from the right edge

    private final CameraMemoZoom zoom;
    private final ImageView flip;
    private boolean glass;
    private boolean compact;
    private float leftReserve;
    private boolean relayoutPending;
    private final RectF rect = new RectF();

    public InstantZoomControlView(Context context, InstantCameraView camera, int chipBackgroundColor, int glyphColor) {
        super(context);
        flip = new ImageView(context);
        flip.setScaleType(ImageView.ScaleType.CENTER);
        flip.setImageResource(R.drawable.camera_revert1);
        flip.setContentDescription(LocaleController.getString(R.string.AccDescrSwitchCamera));
        ScaleStateListAnimator.apply(flip);
        addView(flip, LayoutHelper.createFrame(FLIP_DP, FLIP_DP, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, (ROW_DP - FLIP_DP) / 2f));

        // above the flip button, so a stop or the open dial gets the touch first
        zoom = new CameraMemoZoom(context, new CameraMemoZoom.Host() {
            @Override
            public float[] zoom() {
                return camera.getCoverZoom();
            }

            @Override
            public float[] stops() {
                return camera.getCoverZoomStops();
            }

            @Override
            public void setZoom(float ratio, boolean animate) {
                camera.setCoverZoom(ratio, animate);
            }
        });
        zoom.setRowBand(AndroidUtilities.dp(ROW_DP));
        zoom.setDialReach(AndroidUtilities.dp(REACH_DP));
        zoom.setPassOutsideDial(true);
        // a layout change asked for mid-gesture (the keyboard, recording starting) waits for the finger to lift,
        // so the stop under it doesn't slide away
        zoom.setOnGestureEnd(() -> {
            if (relayoutPending) {
                relayoutPending = false;
                layoutRow();
            }
        });
        addView(zoom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updateColors(chipBackgroundColor, glyphColor);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // faded out for the paused preview or the close, but still laid out: an unseen stop or flip mustn't fire.
            // Under the camera memo's cover, whose crop forwards into the recorder, the cover's own zoom is the one
            if (getAlpha() < 0.01f || CameraMemo.covers(getParent() instanceof View ? (View) getParent() : null)) {
                return false;
            }
            // a touch that closes the open dial is declined by it; over the flip a disabled button still takes it, so
            // that touch only closes the dial (a flip would rebuild the row from the other camera's range mid-turn)
            flip.setEnabled(!zoom.isDialOpen());
        }
        return super.dispatchTouchEvent(ev);
    }

    public void setOnSwitchCamera(Runnable onSwitch) {
        flip.setOnClickListener(v -> onSwitch.run());
    }

    // re-read the recorder's colors so the row follows a live theme flip; the recorder owns the glyph color so
    // these and its flash row always share one tint
    public void updateColors(int chipBackgroundColor, int glyphColor) {
        flip.setColorFilter(new PorterDuffColorFilter(glyphColor, PorterDuff.Mode.SRC_IN));
        zoom.setRowColors(ColorUtils.setAlphaComponent(glyphColor, 0x99), glyphColor, ColorUtils.setAlphaComponent(glyphColor, 0x2E), false);
        if (!glass) {
            // no glass to sit on: the flat message-panel color the flash button's background falls back to
            final int flat = ColorUtils.setAlphaComponent(chipBackgroundColor, 0xFF);
            flip.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(FLIP_DP), flat));
            zoom.setRowBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(20), flat));
        }
    }

    // the recorder's glass, the same the flash row sits on; called once, because every create() adds a position
    // subscription that can't be taken back
    public void setButtonsBackground(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        if (glass) {
            return;
        }
        glass = true;
        final BlurredBackgroundDrawable flipGlass = factory.create(flip, colorProvider);
        flipGlass.setRadius(AndroidUtilities.dp(FLIP_DP / 2f));
        flip.setBackground(flipGlass);
        final BlurredBackgroundDrawable rowGlass = factory.create(zoom, colorProvider);
        rowGlass.setRadius(AndroidUtilities.dp(20));
        zoom.setRowBackground(rowGlass);
    }

    /** Re-reads the camera's zoom. Camera1 (wholeSteps) turns the dial by whole x, as dazewell chose for it. */
    public void sync(boolean wholeSteps) {
        zoom.setWholeSteps(wholeSteps);
        zoom.sync(true);
    }

    public boolean hasRow() {
        return zoom.hasRow();
    }

    public boolean isDialOpen() {
        return zoom.isDialOpen();
    }

    public boolean isTouching() {
        return zoom.isTouching();
    }

    /** Pinch, a close or a pause has the zoom now: the dial goes and the gesture on it is dropped. */
    public void dismiss() {
        zoom.dismiss();
    }

    public boolean isCompact() {
        return compact;
    }

    /**
     * Roomy puts the row above the recorder's flash row; compact, when the keyboard leaves no room for that, puts
     * it on the flash row's own line, between the flash buttons (leftReserve, px from the left edge) and the
     * view-once column on the right.
     */
    public void setPlacement(boolean compact, float leftReserve) {
        if (this.compact == compact && this.leftReserve == leftReserve) {
            return;
        }
        this.compact = compact;
        this.leftReserve = leftReserve;
        if (zoom.isTouching()) {
            relayoutPending = true;
        } else {
            layoutRow();
        }
    }

    /** The row's centre, measured from this view's centre: what the recorder's placement adds to its translation. */
    public static float rowCenterOffset() {
        return AndroidUtilities.dp(HEIGHT_DP) / 2f - AndroidUtilities.dp(ROW_DP) / 2f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutRow();
    }

    private void layoutRow() {
        final int w = getMeasuredWidth();
        if (w <= 0) {
            return;
        }
        // the flip keeps clear of the view-once column in both layouts; compact also clears the flash buttons
        final float flipLeft = w - AndroidUtilities.dp(ONCE_DP + FLIP_DP);
        zoom.setRowBounds(compact ? leftReserve + AndroidUtilities.dp(8) : AndroidUtilities.dp(EDGE_DP), w - flipLeft + AndroidUtilities.dp(8));
        flip.setTranslationX(flipLeft);
    }

    // The lowest point, in this view's own coordinates, that the row or the flip button takes up left of x, so the
    // front flash brightness arm in the bottom-left corner can stop short of it. Negative when nothing reaches that far.
    // The open dial isn't counted: it's up for a moment, and the arm drawn over its left edge is the lesser cost.
    public float occupiedBottomLeftOf(float x) {
        float bottom = -1;
        if (zoom.getRowRect(rect) && rect.left < x) {
            bottom = rect.bottom;
        }
        if (flip.getLeft() + flip.getTranslationX() < x) {
            bottom = Math.max(bottom, flip.getBottom());
        }
        return bottom;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
