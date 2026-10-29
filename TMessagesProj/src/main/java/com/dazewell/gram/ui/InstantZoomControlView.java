package com.dazewell.gram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
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
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.InstantCameraView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

/**
 * #video-zoom: the round video recorder's zoom, the camera memo's own (CameraMemoZoom): a row of stops under the
 * circle, tap one to jump, hold or slide to turn the row into a ruler. The camera flip is a button of its own, since
 * the hold's sideways swipe only works mid-recording and only with its gestures on; the recorder adds it
 * (flipButton) to the column of the composer's view-once and pause buttons, so the row stays centred.
 *
 * The view is the row's 56dp band. Only a stop or the open ruler take a touch; anything else falls through to the
 * recorder.
 */
@SuppressLint("ViewConstructor")
public class InstantZoomControlView extends FrameLayout {

    public static final int HEIGHT_DP = 56;
    private static final int FLIP_DP = 44;
    private static final int COLUMN_DP = 56; // the composer's view-once and pause buttons, centred 26dp in from the right edge
    private static final int COLUMN_GAP_DP = 24; // between those two, ChatActivityEnterView.ControlsView.onDraw

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
        zoom.setRowBand(AndroidUtilities.dp(HEIGHT_DP));
        zoom.setRulerMode(true);
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
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && !live()) {
            return false;
        }
        return super.dispatchTouchEvent(ev);
    }

    // faded out for the paused preview or the close, but still laid out: an unseen stop or flip mustn't fire. Under the
    // camera memo's cover, whose crop forwards into the recorder, the cover's own zoom is the one
    private boolean live() {
        return getAlpha() >= 0.01f && !CameraMemo.covers(getParent() instanceof View ? (View) getParent() : null);
    }

    public void setOnSwitchCamera(Runnable onSwitch) {
        flip.setOnClickListener(v -> {
            if (!live()) {
                return;
            }
            if (zoom.isDialOpen()) {
                zoom.dismiss(); // a flip would rebuild the row from the other camera's range under the open ruler
                return;
            }
            onSwitch.run();
        });
        flip.setClickable(getAlpha() >= 0.01f);
    }

    /** The flip button, for the recorder to add beside this view; it fades and slides with it. */
    public View flipButton() {
        return flip;
    }

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (flip == null) {
            return; // the super constructor, before it exists
        }
        flip.setAlpha(alpha);
        flip.setClickable(alpha >= 0.01f); // a faded one lets the touch through, as this view does
        flip.setImportantForAccessibility(alpha >= 0.01f ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    public void setTranslationX(float translationX) {
        super.setTranslationX(translationX);
        if (flip != null && getWidth() > 0) {
            flip.setTranslationX(translationX + getWidth() / 2f - AndroidUtilities.dp(26));
        }
    }

    /** Centres the flip on the view-once and pause column, one gap above the view-once line (in the recorder's centre-relative px). */
    public void setFlipLine(float onceLine) {
        flip.setTranslationY(onceLine - AndroidUtilities.dp(FLIP_DP + COLUMN_GAP_DP));
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
        // centred, clear of the view-once column on the right and as far from the left edge; compact also clears the
        // flash buttons on the left, so it sits off centre there
        final float side = AndroidUtilities.dp(COLUMN_DP + 8);
        zoom.setRowBounds(compact ? Math.max(side, leftReserve + AndroidUtilities.dp(8)) : side, side);
        flip.setTranslationX(getTranslationX() + w / 2f - AndroidUtilities.dp(26));
    }

    /**
     * The composer's view-once "(1)" centre line once a recording is locked, in host's coordinates, for the row to share.
     * ChatActivityEnterView.ControlsView.onDraw puts it there: with the lock drag done (yAdd capped at 57dp, :1557),
     * lockY = 72dp + (height - 194dp) - 57dp (:1564, :1577), the pause button 44dp tall from lockY and the once button
     * 24dp + 44dp above that (:1849), so 225dp up from that view's bottom. Re-check those lines on an upstream merge. The same line in a chat with no view-once, so the row doesn't move from chat to chat. NaN with no
     * composer to measure (a story reply, or before its controls are laid out).
     */
    public static float onceLine(View host, Object delegate) {
        if (!(delegate instanceof ChatActivity)) {
            return Float.NaN;
        }
        final ChatActivityEnterView enter = ((ChatActivity) delegate).getChatActivityEnterView();
        final View controls = enter != null ? enter.controlsView : null;
        if (controls == null || controls.getHeight() == 0 || !controls.isAttachedToWindow()) {
            return Float.NaN;
        }
        final int[] at = new int[2];
        controls.getLocationInWindow(at);
        final float bottom = at[1] + controls.getHeight();
        host.getLocationInWindow(at);
        return bottom - AndroidUtilities.dp(225) - at[1];
    }

    // The lowest point, in this view's own coordinates, that the row takes up left of x, so the
    // front flash brightness arm in the bottom-left corner can stop short of it. Negative when nothing reaches that far.
    // The open dial isn't counted: it's up for a moment, and the arm drawn over its left edge is the lesser cost.
    public float occupiedBottomLeftOf(float x) {
        float bottom = -1;
        if (zoom.getRowRect(rect) && rect.left < x) {
            bottom = rect.bottom;
        }
        return bottom;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
