package com.dazewell.gram.composer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.dazewell.gram.helpers.InterfaceStyleController;
import com.dazewell.gram.ui.ComposerToolbarLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.ChatActivityEnterView;

/**
 * The floating MD3 composer at rest: with the field empty and no keyboard or panel up, the field moves into the
 * tools row between the leading and trailing zones, so the island is one row. Opening the keyboard grows it back
 * to two rows, the field rising out of the row while the buttons stay put. The send column sits at the end of the
 * tools row in both states, so it never moves either.
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
    // A morph whose height never animated (nothing to animate between) still has to let go of its offsets.
    private static final long MORPH_TIMEOUT = 400;

    private final ComposerToolbarLayout toolbar;
    private boolean compact;
    private boolean morphing;
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

    /** Only the floating MD3 composer in a chat folds; Docked, Liquid Glass and the enter views outside a chat keep two rows. */
    @Nullable
    public static ComposerInlineRest createIfEnabled(@Nullable ComposerToolbarLayout toolbar, boolean inChat, Runnable onGeometryChanged) {
        if (toolbar == null || !inChat || !InterfaceStyleController.applyComposer() || InterfaceStyleController.dockedComposer()) {
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
                            int openBottom, float currentHeight) {
        toolbar.setSendReserve(sendReserve);
        final boolean rest = wantsRest && fits(field) && !toolbar.hasMiddleLeading();
        final ViewGroup.LayoutParams params = field.getLayoutParams();
        boolean flipped = false;
        float fromStart = 0, fromBottom = 0;
        if (rest != compact) {
            compact = rest;
            toolbar.setRestCompact(rest);
            if (params instanceof ViewGroup.MarginLayoutParams && currentHeight > 0) {
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
            startDelta = fromStart - start;
            bottomDelta = bottom - fromBottom;
            morphFromHeight = currentHeight;
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
        final float span = toFactor - morphFromHeight;
        remaining = span == 0 ? 0 : Math.max(0, Math.min(1, (toFactor - factor) / span));
        final float target = compact ? 0f : 1f;
        progress = target - (target - morphFromProgress) * remaining;
        // The scrolling zone comes back in the second half, once the field has left the row it reappears in.
        toolbar.setMiddleAlpha(compact ? 0f : Math.max(0, (progress - 0.5f) * 2f));
        if (remaining <= 0) {
            finishMorph();
        }
    }

    private void finishMorph() {
        final boolean wasMorphing = morphing;
        morphing = false;
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
