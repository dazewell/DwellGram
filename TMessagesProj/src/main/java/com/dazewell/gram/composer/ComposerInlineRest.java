package com.dazewell.gram.composer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.dazewell.gram.helpers.InterfaceStyleController;
import com.dazewell.gram.ui.ComposerToolbarLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.recyclerview.ChatListItemAnimator;

import me.vkryl.android.animator.FactorAnimator;

/**
 * The MD3 composer at rest: with the field empty and no keyboard or panel up, the field moves into the tools row
 * between the leading and trailing zones, so the island, or the docked panel, is one row. Opening the keyboard grows
 * it back to two rows, the field rising out of the row while the buttons stay put. The send column sits at the end
 * of the tools row in both states, so it never moves either.
 * <p>
 * Nothing here runs a clock of its own. The flip changes the field's margins, the enter view's height animation
 * eases the island to its new height, and every offset below is that animation's remaining share, so the field,
 * the tools inset and the island top cannot drift apart.
 */
public final class ComposerInlineRest {

    // The field never rests narrower than this; a row that would squeeze it further keeps two rows.
    private static final int MIN_FIELD = 96;
    // The text sits this far inside the field, measured from the button cell beside it.
    private static final int TEXT_INSET = 12;
    // The fold's own pace, close to the reference recording's: the stock 250ms that line wraps use was over before
    // the eye caught the field moving. Ease-out, so it starts with the keyboard and settles gently.
    private static final long FOLD_DURATION = 450;
    // A morph whose height never animated (nothing to animate between) still has to let go of its offsets.
    private static final long MORPH_TIMEOUT = FOLD_DURATION + 150;

    private final ComposerToolbarLayout toolbar;
    private boolean compact;
    private boolean held;
    private boolean morphing;
    private boolean pendingFinish;
    private int foldId;
    private boolean awaitingTarget;
    private float staleTarget;
    private float morphFromHeight;
    private float morphFromProgress;
    private float progress = 1f;
    private float startDelta;
    private float bottomDelta;
    private float remaining;
    @Nullable
    private View morphView;
    private final Runnable morphTimeout = this::finishMorph;

    private ComposerInlineRest(ComposerToolbarLayout toolbar, Runnable onGeometryChanged) {
        this.toolbar = toolbar;
        toolbar.setOnRestGeometryChanged(onGeometryChanged);
    }

    /**
     * The MD3 composer in a chat gets one, floating or docked; Liquid Glass and the enter views outside a chat keep
     * their layout. The docked panel's top follows the island's, so it folds with it while its bottom stays put.
     */
    @Nullable
    public static ComposerInlineRest createIfEnabled(@Nullable ComposerToolbarLayout toolbar, boolean inChat, Runnable onGeometryChanged) {
        if (toolbar == null || !inChat || !InterfaceStyleController.applyComposer()) {
            return null;
        }
        return new ComposerInlineRest(toolbar, onGeometryChanged);
    }

    public static boolean morphing(@Nullable ChatActivityEnterView enterView) {
        final ComposerInlineRest rest = enterView != null ? enterView.getComposerInlineRest() : null;
        return rest != null && rest.morphing;
    }

    public boolean isCompact() {
        return compact;
    }

    /** 1 with two rows, 0 with one, eased between them while the island morphs. */
    public float progress() {
        return progress;
    }

    /**
     * False while a record, review or bot panel has replaced the tools row's controls. Mirrors the enter view's
     * toolbarReplacementVisible: both are written only by its isRecordingStateChanged, in the same call.
     */
    public boolean toolsRowShown() {
        return toolbar.controlsVisible();
    }

    /** The field's start edge at rest, in px from the toolbar's start, or -1 when the leading zone is empty. */
    public int restFieldStart() {
        final int leading = toolbar.leadingExtent();
        return leading > 0 ? leading : -1;
    }

    /** The field's end edge at rest, in px from the toolbar's end. */
    public int restFieldEnd() {
        return Math.max(0, toolbar.trailingExtent());
    }

    /** The send column's bottom margin, in dp, centring it on the tools row it sits in. */
    public int primaryColumnBottomDp(int columnHeightDp) {
        return Math.max(0, (toolbar.rowHeightDp() - columnHeightDp) / 2);
    }

    /**
     * Sets the enter view's height animation up for the change it is about to animate: the fold's pace while a fold is
     * under way, the stock one for everything else. Read at each animateTo, so a fold re-aimed midway keeps its pace.
     */
    public void tuneHeightAnimator(FactorAnimator animator) {
        animator.setDuration(morphing ? FOLD_DURATION : ChatListItemAnimator.DEFAULT_DURATION);
        animator.setInterpolator(morphing ? CubicBezierInterpolator.EASE_OUT : ChatListItemAnimator.DEFAULT_INTERPOLATOR);
    }

    public boolean isMorphing() {
        return morphing;
    }

    /** Counts flips, so a reader holding state for one fold can tell a reversal from the fold it started on. */
    public int foldId() {
        return foldId;
    }

    /** How far the running fold has got, 0 to 1; 1 while none runs. */
    public float foldFraction() {
        return morphing ? 1f - remaining : 1f;
    }

    public float fieldOffsetX() {
        if (!morphing) {
            return 0;
        }
        final float x = startDelta * remaining;
        return LocaleController.isRTL ? -x : x;
    }

    public float fieldOffsetY() {
        return morphing ? bottomDelta * remaining : 0;
    }

    /**
     * Decides between one row and two and lays the field out for it. {@code openStart} is the field's start inset
     * with two rows, which is also its end inset there, since the send column has moved down to the tools row.
     * {@code openBottom} is its bottom margin with two rows, the tools row's height plus its gap.
     */
    public void layoutField(View field, @Nullable View richPreview, boolean wantsRest, int openStart, int sendReserve,
                            int openBottom, float currentHeight, float currentTarget) {
        toolbar.setSendReserve(sendReserve);
        // While a panel replaces the tools row the field is GONE, so a flip would change no height and only run a
        // phantom morph on the timeout. Hold the fold; the enter view asks again once the row comes back, and that
        // first answer snaps, since the field was never drawn where a morph would start it.
        final boolean shown = toolsRowShown();
        final boolean resumed = held && shown;
        if (!shown && !held) {
            // A fold still running when the row goes would keep easing a row nobody sees; land it now.
            finishMorph();
        }
        held = !shown;
        final boolean rest = shown ? wantsRest && fits(field) && !toolbar.hasMiddleLeading() : compact;
        final ViewGroup.LayoutParams params = field.getLayoutParams();
        boolean flipped = false;
        float fromStart = 0, fromBottom = 0;
        if (rest != compact) {
            compact = rest;
            toolbar.setRestCompact(rest);
            if (params instanceof ViewGroup.MarginLayoutParams && currentHeight > 0 && !resumed) {
                // Where the field is drawn right now, so a flip that reverses a running morph starts from there.
                final ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                fromStart = margins.getMarginStart() + (morphing ? startDelta * remaining : 0);
                fromBottom = margins.bottomMargin - (morphing ? bottomDelta * remaining : 0);
                flipped = true;
            } else {
                finishMorph();
            }
        }

        final int start = rest && restFieldStart() > 0 ? restFieldStart() + dp(TEXT_INSET) : openStart;
        final int end = rest ? restFieldEnd() + dp(TEXT_INSET) : openStart;
        final int bottom = rest ? restBottom(field) : openBottom;
        if (flipped) {
            foldId++;
            startDelta = fromStart - start;
            bottomDelta = bottom - fromBottom;
            morphFromHeight = currentHeight;
            staleTarget = currentTarget;
            awaitingTarget = true;
            morphFromProgress = progress;
            morphing = true;
            remaining = 1f;
            setMorphView(field);
        }
        applyMargins(field, start, end, bottom);
        if (richPreview != null) {
            applyMargins(richPreview, start, end, -1);
        }
        if (!morphing) {
            progress = compact ? 0f : 1f;
            toolbar.setMiddleAlpha(compact ? 0f : 1f);
        }
    }

    /**
     * Called from the enter view's height animation. The field was laid out at its new place the moment the flip
     * landed; this walks it there from the old one by the share of the height change still to come.
     */
    public void onHeightFactor(float factor, float toFactor) {
        if (!morphing) {
            return;
        }
        // Until the flip's re-measure aims the animation at the new height, its target is the old one; measuring
        // against that would step the field the wrong way for a tick. The timeout covers a target that never moves.
        if (awaitingTarget) {
            if (toFactor == staleTarget) {
                return;
            }
            awaitingTarget = false;
        }
        final float span = toFactor - morphFromHeight;
        remaining = span == 0 ? 0 : Math.max(0, Math.min(1, (toFactor - factor) / span));
        final float target = compact ? 0f : 1f;
        progress = target - (target - morphFromProgress) * remaining;
        // The scrolling zone comes back in the second half, once the field has left the row it reappears in.
        toolbar.setMiddleAlpha(compact ? 0f : Math.max(0, (progress - 0.5f) * 2f));
        if (remaining <= 0) {
            // Not finished yet: this frame's island height still has to go out, and the history pin reads morphing()
            // while it does. settleIfDone() lets go once it has.
            pendingFinish = true;
        }
    }

    /** Called once the frame's island height has gone out; ends a morph whose last frame that was. */
    public void settleIfDone() {
        if (pendingFinish) {
            finishMorph();
        }
    }

    private void finishMorph() {
        final boolean wasMorphing = morphing;
        morphing = false;
        pendingFinish = false;
        remaining = 0;
        startDelta = bottomDelta = 0;
        progress = compact ? 0f : 1f;
        toolbar.setMiddleAlpha(compact ? 0f : 1f);
        setMorphView(null);
        if (wasMorphing) {
            toolbar.invalidate();
            final View parent = toolbar.getParent() instanceof View ? (View) toolbar.getParent() : null;
            if (parent != null) {
                parent.invalidate();
            }
        }
    }

    private void setMorphView(@Nullable View view) {
        if (morphView != null) {
            morphView.removeCallbacks(morphTimeout);
        }
        morphView = view;
        if (view != null) {
            view.postDelayed(morphTimeout, MORPH_TIMEOUT);
        }
    }

    // Until the toolbar has been laid out there is nothing to measure against, so assume the row fits: a chat then
    // opens already in one row, and only a row that turns out too narrow pays for a visible morph.
    private boolean fits(View field) {
        final View row = field.getParent() instanceof View ? (View) field.getParent() : null;
        final int leading = toolbar.leadingExtent();
        final int trailing = toolbar.trailingExtent();
        if (row == null || row.getWidth() <= 0 || leading < 0 || trailing < 0) {
            return true;
        }
        return row.getWidth() - leading - trailing >= dp(MIN_FIELD);
    }

    // Centred on the tools row the field shares; the field is only ever one line here.
    private int restBottom(View field) {
        final int fieldHeight = field.getMeasuredHeight() > 0 ? field.getMeasuredHeight() : dp(ChatActivityEnterView.DEFAULT_HEIGHT);
        return Math.max(0, (dp(toolbar.rowHeightDp()) - fieldHeight) / 2);
    }

    private static void applyMargins(View view, int start, int end, int bottom) {
        if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params.getMarginStart() != start || params.getMarginEnd() != end || bottom >= 0 && params.bottomMargin != bottom) {
            params.setMarginStart(start);
            params.setMarginEnd(end);
            if (bottom >= 0) {
                params.bottomMargin = bottom;
            }
            view.setLayoutParams(params);
        }
    }
}
