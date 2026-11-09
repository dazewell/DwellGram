package com.dazewell.gram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

import java.util.ArrayList;

public final class InputSatellites {

    private static final int FILL_MARGIN = 5;
    private static final int WARMUP_FRAMES = 3;
    private static final int RIGHT_OFFSET_DELTA = 4;

    private final ArrayList<GlassFill> fills = new ArrayList<>();
    private final ArrayList<View> rightColumnExtras = new ArrayList<>();
    private @Nullable BlurredBackgroundDrawableViewFactory factory;
    private @Nullable BlurredBackgroundColorProvider colorProvider;
    private @Nullable View enterView;
    private @Nullable ViewGroup rightColumn;
    private int publishedRightOffset;
    private int contentInset;

    public void configure(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        this.factory = factory;
        this.colorProvider = colorProvider;
    }

    /**
     * How far inside its slot each right-column control draws itself, in px. Everything published here is the
     * drawn control's edge rather than the slot's, so callers can inset off it once instead of guessing.
     */
    public void setContentInset(int inset) {
        contentInset = inset;
    }

    public void configureRightColumn(View enterView, ViewGroup rightColumn) {
        this.enterView = enterView;
        this.rightColumn = rightColumn;
    }

    public void trackRightColumn(View view) {
        if (view != null && !rightColumnExtras.contains(view)) {
            rightColumnExtras.add(view);
        }
    }

    public boolean updateRightOffset() {
        int width = 0;
        if (enterView != null && enterView.getVisibility() == View.VISIBLE && enterView.getAlpha() > 0.01f) {
            if (rightColumn != null && rightColumn.getVisibility() == View.VISIBLE) {
                for (int i = 0; i < rightColumn.getChildCount(); i++) {
                    width = Math.max(width, visualWidth(rightColumn.getChildAt(i)));
                }
            }
            for (int i = 0; i < rightColumnExtras.size(); i++) {
                width = Math.max(width, visualWidth(rightColumnExtras.get(i)));
            }
        }
        int offset = width == 0 ? 0 : width + contentInset;
        if (offset == publishedRightOffset || publishedRightOffset != 0 && offset != 0 && Math.abs(offset - publishedRightOffset) < dp(RIGHT_OFFSET_DELTA)) {
            return false;
        }
        publishedRightOffset = offset;
        return true;
    }

    public int getPublishedRightOffset() {
        return publishedRightOffset;
    }

    public void glass(View view) {
        glass(view, null);
    }

    public void glass(View view, @Nullable Condition condition) {
        if (view == null) {
            return;
        }
        for (int i = 0; i < fills.size(); i++) {
            if (fills.get(i).view == view) {
                return;
            }
        }
        fills.add(new GlassFill(view, condition));
    }

    public void drawFill(Canvas canvas, View view) {
        // NagramX (#interface-style): the MD3 island is the surface under these controls, so they draw as plain
        // icon buttons on it rather than on a glass disc of their own.
        if (factory == null || com.dazewell.gram.helpers.InterfaceStyleController.applyComposer()) {
            return;
        }
        for (int i = 0; i < fills.size(); i++) {
            GlassFill fill = fills.get(i);
            if (fill.view != view) {
                continue;
            }
            if (wants(fill)) {
                View invalidationTarget = view.getParent() instanceof View ? (View) view.getParent() : view;
                paintFill(canvas, fill, view, 255, invalidationTarget);
            }
            return;
        }
    }

    public void updateColors() {
        for (int i = 0; i < fills.size(); i++) {
            BlurredBackgroundDrawable drawable = fills.get(i).drawable;
            if (drawable != null) {
                drawable.updateColors();
            }
        }
    }

    private boolean wants(GlassFill fill) {
        return fill.view.getVisibility() == View.VISIBLE && (fill.condition == null || fill.condition.holds());
    }

    private int visualWidth(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.5f || view.getScaleX() < 0.5f) {
            return 0;
        }
        if (view instanceof ChatActivityEnterView.SendButton) {
            return ((ChatActivityEnterView.SendButton) view).getVisualWidth();
        }
        return Math.max(0, view.getWidth() - 2 * contentInset);
    }

    private void paintFill(Canvas canvas, GlassFill fill, View target, int alpha, View invalidationTarget) {
        if (target.getWidth() <= 0 || target.getHeight() <= 0) {
            return;
        }
        if (fill.drawable == null) {
            fill.drawable = factory.create(fill.view, colorProvider);
            fill.drawable.setPadding(dp(FILL_MARGIN));
            fill.warmup = WARMUP_FRAMES;
        }
        float radius = Math.max(0, (Math.min(target.getWidth(), target.getHeight()) - dp(FILL_MARGIN * 2)) / 2f);
        if (fill.radius != radius) {
            fill.radius = radius;
            fill.drawable.setRadius(radius);
        }
        fill.drawable.setBounds(0, 0, target.getWidth(), target.getHeight());
        fill.drawable.setAlpha(alpha);
        fill.drawable.draw(canvas);
        if (fill.warmup > 0) {
            fill.warmup--;
            invalidationTarget.postInvalidateOnAnimation();
        }
    }

    private static final class GlassFill {
        final View view;
        final @Nullable Condition condition;
        @Nullable BlurredBackgroundDrawable drawable;
        float radius = -1;
        int warmup;

        GlassFill(View view, @Nullable Condition condition) {
            this.view = view;
            this.condition = condition;
        }
    }

    public interface Condition {
        boolean holds();
    }
}
