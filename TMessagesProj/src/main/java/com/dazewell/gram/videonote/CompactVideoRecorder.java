package com.dazewell.gram.videonote;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The round video recorder's compact presentation: the live circle shrunk to COMPACT_DP and docked at the bottom
 * left, above the recording bar, with the chat behind it left touchable. A recording opens compact only when the
 * camera-choice popup started it with Compact Recording on; the host passes that in at the fresh open and this
 * keeps it until the recorder is hidden for good, so pause, resume and an infinite rollover all keep it. Any other
 * recording opens full and can be collapsed from the same toggle.
 *
 * The camera surface is never resized. The presentation is a factor, 1 compact and 0 full, folded into the
 * transforms the host already animates: scale() multiplies the open/close scale, offsetX() adds to its
 * translationX, and blendY() mixes the docked position into its translationY. visibleBounds() is the one place the
 * resulting on-screen circle is computed, for hit tests, the arc, the hint and the send transition.
 *
 * A 48dp button beside the circle expands it to the stock recorder and collapses it back without touching the
 * capture. Expanding changes this recording only, never the saved setting. UI thread only.
 */
public final class CompactVideoRecorder {

    public interface Host {
        // the host's open animation has settled and its camera is still its own, so the transforms are ours to set
        boolean canChangePresentation();

        // apply scale(), offsetX() and blendY() to the circle now
        void applyPresentation();

        // compact on or off: drop any gesture in flight and tell the chat, which owns the scrim
        void onPresentationChanged();

        // the dock moved under a settled circle (bar, insets or rotation): drop any gesture in flight
        void onDockMoved();
    }

    public static final int COMPACT_DP = 112;
    private static final int EDGE_DP = 12;
    // a 48dp target around a 44dp disc, the size of the flip button it stacks on
    private static final int TOGGLE_DP = 48;
    private static final int FLIP_DP = 44;
    private static final int INSET_DP = (TOGGLE_DP - FLIP_DP) / 2;
    // InstantZoomControlView's spacing between the flip button and the view-once button below it
    private static final int COLUMN_GAP_DP = 24;
    private static final long TOGGLE_MS = 220;

    private final FrameLayout host;
    private final View circle;
    private final Host callback;
    private final ImageView toggle;
    private View[] expandedOnly = new View[0];
    private View flip;
    private boolean glass;

    // the toggle is offered: every recording from its fresh open until end()
    private boolean available;
    // this recording has been compact, opened so or collapsed since; it fades on send. Cleared only by end()
    private boolean session;
    private boolean expanded;
    private float factor;
    private ValueAnimator animator;
    private float lastDockX = Float.NaN, lastDockY = Float.NaN;
    private int lastSize = -1;

    public CompactVideoRecorder(FrameLayout host, View circle, Host callback) {
        this.host = host;
        this.circle = circle;
        this.callback = callback;

        toggle = new ImageView(host.getContext());
        toggle.setScaleType(ImageView.ScaleType.CENTER);
        toggle.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        toggle.setVisibility(View.GONE);
        toggle.setOnClickListener(v -> setExpanded(!expanded));
        host.addView(toggle, LayoutHelper.createFrame(TOGGLE_DP, TOGGLE_DP, Gravity.LEFT | Gravity.TOP));
        updateToggleIcon();

        // padding (the recording bar), insets and the host re-picking the circle's size all arrive as relayouts,
        // which don't otherwise reach the transforms
        host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            layoutToggle(); // also expanded, which refresh() leaves alone
            refresh();
        });
    }

    // Re-applies the presentation if the dock or the circle's size moved under a settled circle. The host also
    // calls it once its open animation ends, since that animation's end values were fixed when it started.
    public void refresh() {
        if (!available || factor == 0f || !callback.canChangePresentation()) {
            return;
        }
        final float x = dockCenterX(), y = dockCenterY();
        final int size = circle.getLayoutParams() != null ? circle.getLayoutParams().width : 0;
        if (x != lastDockX || y != lastDockY || size != lastSize) {
            lastDockX = x;
            lastDockY = y;
            lastSize = size;
            callback.onDockMoved();
            callback.applyPresentation();
        }
    }

    // For the host's open animation: settle on the current dock and size once it is done.
    public Animator.AnimatorListener settleAfterOpen() {
        return new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                lastSize = -1;
                host.post(CompactVideoRecorder.this::refresh);
            }
        };
    }

    // The host's flip button. Expanded, the toggle joins its column, one step above it: the flip's own step up
    // from the view-once button, FLIP_DP plus COLUMN_GAP_DP.
    public void stackAbove(View flip) {
        this.flip = flip;
    }

    // The same look as the flip button: its glyph tint, and its flat circle until the glass arrives.
    public void updateColors(int panelColor, int glyphColor) {
        toggle.setColorFilter(new PorterDuffColorFilter(glyphColor, PorterDuff.Mode.SRC_IN));
        if (!glass) {
            final android.graphics.drawable.ShapeDrawable flat = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.OvalShape());
            flat.getPaint().setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(panelColor, 0xFF));
            toggle.setBackground(new android.graphics.drawable.InsetDrawable(flat, dp(INSET_DP)));
        }
    }

    // The recorder's glass, as the flip button and the flash row wear it. Once: every create() subscribes for good.
    public void setGlass(org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory factory,
                         org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider colorProvider) {
        if (glass) {
            return;
        }
        glass = true;
        final org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable drawable = factory.create(toggle, colorProvider);
        drawable.setPadding(dp(INSET_DP));
        drawable.setRadius(dp(FLIP_DP / 2f));
        toggle.setBackground(drawable);
    }

    // The camera controls that only the full recorder shows: flash, zoom, flip, infinite and the brightness arm.
    public void hideWhenCompact(View... views) {
        expandedOnly = views;
        updateControls();
    }

    // A fresh camera open. compact is the composer's snapshot of the popup choice; anything else opens full,
    // and can still be collapsed from its toggle, as infinite recording can be turned on mid-recording. offered is
    // false for a host without the chat's dock padding and guards (a story reply): the recorder stays stock there.
    public void begin(boolean compact, boolean offered) {
        cancelAnimator();
        compact &= offered;
        available = offered;
        session = compact;
        expanded = !compact;
        factor = compact ? 1f : 0f;
        lastDockX = lastDockY = Float.NaN;
        lastSize = -1;
        toggle.setVisibility(offered ? View.VISIBLE : View.GONE);
        toggle.setAlpha(0f);
        updateToggleIcon();
        updateControls();
    }

    // The recorder is hidden for good: sent, discarded or torn down.
    public void end() {
        if (!available && factor == 0f) {
            return;
        }
        cancelAnimator();
        available = false;
        session = false;
        expanded = false;
        factor = 0f;
        toggle.setVisibility(View.GONE);
        updateControls();
    }

    public boolean isSession() {
        return session;
    }

    public boolean isCompact() {
        return available && !expanded;
    }

    public float scale() {
        final int size = circle.getLayoutParams() != null ? circle.getLayoutParams().width : 0;
        if (factor == 0f || size <= 0) {
            return 1f;
        }
        final float target = Math.min(1f, dp(COMPACT_DP) / (float) size);
        return 1f + (target - 1f) * factor;
    }

    public float offsetX() {
        if (factor == 0f) {
            return 0f;
        }
        return factor * (dockCenterX() - layoutCenterX());
    }

    // stockY is the host's own translationY for the circle; animY its open/close slide, which the dock keeps
    public float blendY(float stockY, float animY) {
        if (factor == 0f) {
            return stockY;
        }
        final float docked = dockCenterY() - layoutCenterY() + animY;
        return stockY + (docked - stockY) * factor;
    }

    // Where the circle really is on screen, in the host's coordinates, with every transform the host and this
    // apply, whatever the pivot. The host keeps it at the centre while docked (setVisibility, then
    // applyPresentation after a size change); the stock send transition moves it, and never runs for a compact
    // recording.
    public static void visibleBounds(View circle, RectF out) {
        final float w = circle.getMeasuredWidth(), h = circle.getMeasuredHeight();
        final float px = circle.getPivotX(), py = circle.getPivotY();
        final float sx = circle.getScaleX(), sy = circle.getScaleY();
        final float left = circle.getX() + px - px * sx;
        final float top = circle.getY() + py - py * sy;
        out.set(left, top, left + w * sx, top + h * sy);
    }

    // The host's dispatchTouchEvent asks this first. While compact, a touch that starts away from the circle and
    // the toggle is refused before the host can claim it, so the chat behind gets it whole. Once accepted, the
    // rest of that touch is the host's as usual.
    public boolean acceptsDown(MotionEvent ev) {
        if (ev.getActionMasked() != MotionEvent.ACTION_DOWN || !isCompact()) {
            return true;
        }
        visibleBounds(circle, AndroidUtilities.rectTmp);
        // the recording arc's 8dp ring is part of the target
        AndroidUtilities.rectTmp.inset(-dp(8), -dp(8));
        if (AndroidUtilities.rectTmp.contains(ev.getX(), ev.getY())) {
            return true;
        }
        if (toggle.getVisibility() == View.VISIBLE) {
            final float x = toggle.getX(), y = toggle.getY();
            return ev.getX() >= x && ev.getX() < x + toggle.getWidth() && ev.getY() >= y && ev.getY() < y + toggle.getHeight();
        }
        return false;
    }

    // Follows the circle's fades, which the host and the chat animate on it directly.
    public void onCircleAlpha(float alpha) {
        if (available) {
            toggle.setAlpha(alpha);
        }
    }

    // Puts the toggle by the circle as it is drawn now. The host calls this whenever it moves the circle.
    // Docked, it sits to the circle's right, where the screen is free. Expanded, it stacks on the flip button in
    // the right-hand column of camera controls. In between it slides with the factor.
    public void layoutToggle() {
        if (!available) {
            return;
        }
        visibleBounds(circle, AndroidUtilities.rectTmp);
        final float size = dp(TOGGLE_DP);
        final float maxX = host.getMeasuredWidth() - size - dp(4);
        final float maxY = host.getMeasuredHeight() - host.getPaddingBottom() - size;
        final float dockedX = AndroidUtilities.rectTmp.right + dp(EDGE_DP);
        final float dockedY = AndroidUtilities.rectTmp.centerY() - size / 2f;
        float expandedX, expandedY;
        if (flip != null && flip.getWidth() > 0) {
            // in the flip button's column, one step above it, the step the flip itself sits above view-once
            expandedX = flip.getX() + flip.getWidth() / 2f - size / 2f;
            expandedY = flip.getY() + flip.getHeight() / 2f - dp(FLIP_DP + COLUMN_GAP_DP) - size / 2f;
        } else {
            // before the column is laid out: up-left of the centre, clear of the ring (R + 8dp + half its stroke)
            // by a gap, and no higher than circleTop - 16dp, under the hold-to-send label
            final float r = AndroidUtilities.rectTmp.width() / 2f;
            final float d = r + dp(8) + dp(2) + dp(4) + size / 2f;
            final float dy = Math.max(0f, Math.min(d * 0.7071f, r - dp(8)));
            final float dx = (float) Math.sqrt(d * d - dy * dy);
            expandedX = AndroidUtilities.rectTmp.centerX() - dx - size / 2f;
            expandedY = AndroidUtilities.rectTmp.centerY() - dy - size / 2f;
        }
        final float x = expandedX + (dockedX - expandedX) * factor;
        final float y = expandedY + (dockedY - expandedY) * factor;
        toggle.setTranslationX(Math.max(dp(4), Math.min(x, maxX)));
        toggle.setTranslationY(Math.max(0, Math.min(y, maxY)));
    }

    private void setExpanded(boolean expand) {
        if (!available || expanded == expand || !callback.canChangePresentation()) {
            return;
        }
        expanded = expand;
        if (!expand) {
            session = true;
        }
        updateToggleIcon();
        updateControls();
        callback.onPresentationChanged();
        cancelAnimator();
        animator = ValueAnimator.ofFloat(factor, expand ? 0f : 1f);
        animator.addUpdateListener(a -> {
            factor = (float) a.getAnimatedValue();
            if (callback.canChangePresentation()) {
                callback.applyPresentation();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation == animator) {
                    animator = null;
                }
            }
        });
        animator.setDuration(TOGGLE_MS);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.start();
    }

    private void cancelAnimator() {
        if (animator != null) {
            final ValueAnimator a = animator;
            animator = null;
            a.cancel();
        }
    }

    private void updateControls() {
        final int visibility = isCompact() ? View.INVISIBLE : View.VISIBLE;
        for (View v : expandedOnly) {
            if (v != null) {
                v.setVisibility(visibility);
            }
        }
    }

    private void updateToggleIcon() {
        toggle.setImageResource(expanded ? R.drawable.baseline_fullscreen_exit_24 : R.drawable.baseline_fullscreen_24);
        toggle.setContentDescription(LocaleController.getString(expanded ? R.string.AccDescrCompactRecordingCollapse : R.string.AccDescrCompactRecordingExpand));
    }

    private float layoutCenterX() {
        return circle.getWidth() > 0 ? circle.getLeft() + circle.getWidth() / 2f : host.getMeasuredWidth() / 2f;
    }

    private float layoutCenterY() {
        return circle.getHeight() > 0 ? circle.getTop() + circle.getHeight() / 2f : (host.getMeasuredHeight() - host.getPaddingBottom()) / 2f;
    }

    // EDGE_DP in from the left edge and above the recording bar, which the host keeps as its bottom padding
    // (that padding already ends 12dp above the bar). Kept clear of the system bars, a cutout and the action bar.
    private float dockCenterX() {
        final float half = dp(COMPACT_DP) / 2f;
        final float x = safeInsets().left + dp(EDGE_DP) + half;
        return Math.min(x, Math.max(half, host.getMeasuredWidth() - half));
    }

    private float dockCenterY() {
        final float half = dp(COMPACT_DP) / 2f;
        final int[] location = new int[2];
        host.getLocationInWindow(location);
        final float top = Math.max(safeInsets().top, AndroidUtilities.statusBarHeight) + ActionBar.getCurrentActionBarHeight() - location[1] + dp(8);
        final float y = host.getMeasuredHeight() - host.getPaddingBottom() - half;
        return Math.min(Math.max(y, top + half), Math.max(top + half, host.getMeasuredHeight() - half));
    }

    private androidx.core.graphics.Insets safeInsets() {
        final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(host);
        if (insets == null) {
            return androidx.core.graphics.Insets.NONE;
        }
        return insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
    }
}
