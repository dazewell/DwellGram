package com.dazewell.gram.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.core.math.MathUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * The attach sheet's Rounded-navigation highlight (#interface-style, S6 follow-up).
 *
 * <p>{@code ChatAttachAlert}'s tab bar is a horizontal {@code RecyclerView} of variable-width
 * buttons with no {@code ViewPagerFixed} behind it (unlike the real bottom nav or Statistics, which
 * reuse {@code MainTabsLayout}/{@code ViewPagerFixed} directly), so there is nothing to literally
 * reuse for the geometry side. What this mirrors instead: the same highlight look
 * {@code MainTabsLayout.drawRoundedSelector()} draws (18% {@code key_glass_tabSelected}, a stadium),
 * and the same slide {@code MainTabsActivity.updateRoundedSelector()} drives - 540ms,
 * {@code CubicBezierInterpolator.EASE_OUT_QUINT}, the exact duration and easing
 * {@code ViewPagerFixed.scrollToPosition()} uses for the real nav's tap-driven page change.
 *
 * <p>Holds no reference to the selected button across a frame: the row can scroll or the adapter can
 * rebind a view during the 540ms, so every frame re-resolves "the selected button" through
 * {@link TabLocator#findSelected()} instead of caching it.
 */
public class AttachRoundedSelector {

    public interface TabLocator {
        /** The RecyclerView child currently reflecting the selected tab, or null if none is. */
        View findSelected();

        /** The open-sheet animation's icon/label scale (0..1) for this button; 1 outside that animation. */
        float attachScaleOf(View button);
    }

    private static final long DURATION_MS = 540;

    private final TabLocator locator;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private ValueAnimator animator;
    private boolean sliding;
    private float curCenterX, curWidth;

    public AttachRoundedSelector(TabLocator locator, Theme.ResourcesProvider resourcesProvider) {
        this.locator = locator;
        this.resourcesProvider = resourcesProvider;
    }

    /**
     * Call once per tab switch, after the caller has already updated every button's checked state
     * (so {@link TabLocator#findSelected()} reflects the new selection), passing whichever button was
     * selected before this switch (or null if none was) and whether the switch should animate.
     */
    public void onSelectionChanged(View previouslySelected, boolean animated, View invalidateTarget) {
        // Snapshot before cancel(), which synchronously fires onAnimationEnd and would otherwise
        // clear `sliding` before this method reads it - losing the in-flight position a second tap
        // mid-slide needs to continue from, and snapping the highlight back instead.
        final boolean wasSliding = sliding;
        final float snapshotCenterX = curCenterX, snapshotWidth = curWidth;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }

        final View target = locator.findSelected();
        if (!animated || previouslySelected == null || target == null || previouslySelected == target) {
            sliding = false;
            invalidateTarget.invalidate();
            return;
        }

        final float fromCenterX = wasSliding ? snapshotCenterX : previouslySelected.getX() + previouslySelected.getWidth() / 2f;
        final float fromWidth = wasSliding ? snapshotWidth : previouslySelected.getWidth();
        sliding = true;

        final ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
        anim.setDuration(DURATION_MS);
        anim.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        anim.addUpdateListener(a -> {
            final View liveTarget = locator.findSelected();
            if (liveTarget == null) {
                return;
            }
            final float t = (float) a.getAnimatedValue();
            curCenterX = AndroidUtilities.lerp(fromCenterX, liveTarget.getX() + liveTarget.getWidth() / 2f, t);
            curWidth = AndroidUtilities.lerp(fromWidth, liveTarget.getWidth(), t);
            invalidateTarget.invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                sliding = false;
                animator = null;
                invalidateTarget.invalidate();
            }
        });
        animator = anim;
        anim.start();
    }

    /** Draws the highlight behind the selected button; call before the RecyclerView draws its children. */
    public void draw(Canvas canvas) {
        final View target = locator.findSelected();
        if (target == null) {
            return;
        }

        final float centerX, width;
        if (sliding) {
            centerX = curCenterX;
            width = curWidth;
        } else {
            centerX = target.getX() + target.getWidth() / 2f;
            width = target.getWidth();
        }
        final float height = target.getHeight();
        final float r = Math.min(width, height) / 2f;
        final float alpha = MathUtils.clamp(target.getAlpha(), 0, 1);
        final float scale = MathUtils.clamp(locator.attachScaleOf(target), 0, 1);

        paint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider), 0.18f * alpha));
        rect.set(centerX - width / 2f, target.getY(), centerX + width / 2f, target.getY() + height);

        canvas.save();
        canvas.scale(scale, scale, rect.centerX(), rect.centerY());
        canvas.drawRoundRect(rect, r, r, paint);
        canvas.restore();
    }
}
