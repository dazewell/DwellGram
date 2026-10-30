package org.telegram.ui.Components.chat;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.inset.InAppKeyboardInsetView;
import org.telegram.ui.Components.inset.WindowInsetsProvider;

public class ChatInputViewsContainer extends FrameLayout {
    public static final int INPUT_BUBBLE_RADIUS = 22;
    public static final int INPUT_KEYBOARD_RADIUS = 29;

    public static final int INPUT_BUBBLE_BOTTOM = 9;
    // NagramX: a bottom inset can shorten the pill, but never past one normal composer row,
    // or the toolbar's inset would eat the text line itself.
    private static final int INPUT_BUBBLE_MIN_HEIGHT = org.telegram.ui.Components.ChatActivityEnterView.DEFAULT_HEIGHT;

    private WindowInsetsProvider windowInsetsProvider;

    private final View fadeView;
    private final FrameLayout inputIslandBubbleContainer;
    private final FrameLayout inAppKeyboardBubbleContainer;
    // NagramX: only ChatActivity's composer opts in, so other hosts of this container keep the glass radii under MD3.
    private final boolean flatComposer;
    // NagramX: set only by ChatActivity when the MD3 Composer is on; it then paints this container's island.
    public com.dazewell.gram.composer.ComposerMd3Surface md3Surface;
    // NagramX (#glass-composer-fold): set only by ChatActivity without md3Surface; the glass pill folds with its composer.
    public org.telegram.ui.Components.ChatActivityEnterView glassFoldHost;
    private final int[] glassFoldEdges = new int[4];
    // NagramX (#composer-keyboard-gap): set only by ChatActivity, so DialogsActivity keeps the stock lift.
    public boolean keyboardGapEnabled;

    public ChatInputViewsContainer(@NonNull Context context) {
        this(context, false);
    }

    public ChatInputViewsContainer(@NonNull Context context, boolean flatComposer) {
        super(context);
        this.flatComposer = flatComposer;

        inputIslandBubbleContainer = new FrameLayout(context);
        addView(inputIslandBubbleContainer,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        inAppKeyboardBubbleContainer = new FrameLayout(context) {
            @Override
            public void addView(View child, int width, int height) {
                super.addView(child, width, height);
                checkViewsPositions();
            }
        };
        addView(inAppKeyboardBubbleContainer,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        fadeView = new View(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (backgroundWithFadeDrawable != null) {
                    backgroundWithFadeDrawable.draw(canvas);
                }
                super.dispatchDraw(canvas);
            }
        };
    }

    public View getFadeView() {
        return fadeView;
    }

    public void setWindowInsetsProvider(WindowInsetsProvider windowInsetsProvider) {
        this.windowInsetsProvider = windowInsetsProvider;
    }



    public boolean drawInputBackground = true;
    public BlurredBackgroundDrawable blurredBackgroundDrawable;
    private BlurredBackgroundDrawable underKeyboardBackgroundDrawable;
    public void setInputIslandBubbleDrawable(BlurredBackgroundDrawable drawable) {
        blurredBackgroundDrawable = drawable;
        blurredBackgroundDrawable.setPadding(dp(7));
        blurredBackgroundDrawable.setRadius(composerRadius(INPUT_BUBBLE_RADIUS));
    }

    public void setUnderKeyboardBackgroundDrawable(BlurredBackgroundDrawable drawable) {
        underKeyboardBackgroundDrawable = drawable;
        underKeyboardBackgroundDrawable.enableInAppKeyboardOptimization();
        int radius = composerRadius(INPUT_KEYBOARD_RADIUS);
        underKeyboardBackgroundDrawable.setRadius(radius, radius, 0, 0);
        underKeyboardBackgroundDrawable.setThickness(dp(32));
        underKeyboardBackgroundDrawable.setIntensity(0.4f);
    }

    public void updateColors() {
        blurredBackgroundDrawable.updateColors();
        underKeyboardBackgroundDrawable.updateColors();
        invalidate();
    }


    @NonNull
    public FrameLayout getInputIslandBubbleContainer() {
        return inputIslandBubbleContainer;
    }

    @NonNull
    public FrameLayout getInAppKeyboardBubbleContainer() {
        return inAppKeyboardBubbleContainer;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        checkViewsPositions();
        checkInAppKeyboardChild();
    }



    private void checkInAppKeyboardViewHeight() {
        LayoutParams lp = (LayoutParams) inAppKeyboardBubbleContainer.getLayoutParams();

        final int oldHeight = lp.height;
        final int newHeight = windowInsetsProvider.getInAppKeyboardRecommendedViewHeight();

        if (oldHeight != newHeight) {
            lp.height = newHeight;
            requestLayout();
        }
    }

    private final Path underKeyboardPath = new Path();

    private int currentBlurredHeight;
    private int composerRadius(int radius) {
        return flatComposer && com.dazewell.gram.helpers.InterfaceStyleController.applyComposer() ? 0 : dp(radius);
    }

    private void checkBlurredHeight(boolean force) {
        checkViewsPositions();

        final int blurredHeight = inputBubbleHeightRound + Math.round(getInputBubbleBottomLift()) + Math.round(maxBottomInset);
        final boolean changed = currentBlurredHeight != blurredHeight;
        if (changed || force) {
            currentBlurredHeight = blurredHeight;
        }
        updateInputBubbleGeometry();

        if (changed || force) {
            final int r = composerRadius(INPUT_KEYBOARD_RADIUS);
            tmpRectF.set(0, getMeasuredHeight() - imeBottomInset, getMeasuredWidth(), getMeasuredHeight());
            underKeyboardPath.rewind();
            underKeyboardPath.addRoundRect(tmpRectF, new float[] {r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
            underKeyboardPath.close();
            invalidate();
        }
    }

    private float maxBottomInset;
    private float imeBottomInset;
    private boolean needDrawInAppKeyboard;

    public void checkInsets() {
        maxBottomInset = windowInsetsProvider.getAnimatedMaxBottomInset();
        imeBottomInset = windowInsetsProvider.getAnimatedImeBottomInset();

        needDrawInAppKeyboard = windowInsetsProvider.inAppViewIsVisible();

        if ((inAppKeyboardBubbleContainer.getVisibility() == VISIBLE) != needDrawInAppKeyboard) {
            inAppKeyboardBubbleContainer.setVisibility(needDrawInAppKeyboard ? VISIBLE : GONE);
        }

        checkInAppKeyboardViewHeight();
        checkBlurredHeight(false);
        checkInAppKeyboardChild();

        if (underKeyboardBackgroundDrawable != null) {
            int leftBottomRadius = 0;
            int rightBottomRadius = 0;
            if (Build.VERSION.SDK_INT >= 31) {
                final WindowInsets insets = getRootWindowInsets();
                if (insets != null) {
                    final RoundedCorner bottomLeft = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
                    final RoundedCorner bottomRight = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
                    leftBottomRadius = bottomLeft == null ? 0 : bottomLeft.getRadius();
                    rightBottomRadius = bottomRight == null ? 0 : bottomRight.getRadius();
                }
            }
            int radius = composerRadius(INPUT_KEYBOARD_RADIUS);
            underKeyboardBackgroundDrawable.setRadius(radius, radius,
                    radius == 0 ? 0 : rightBottomRadius, radius == 0 ? 0 : leftBottomRadius, true);
        }
    }

    private void checkViewsPositions() {
        inputIslandBubbleContainer.setTranslationY(-maxBottomInset - getInputBubbleBottomLift());
        inAppKeyboardBubbleContainer.setTranslationY(inAppKeyboardBubbleContainer.getMeasuredHeight() - imeBottomInset);
    }


    private void checkInAppKeyboardChild() {
        final int navbarHeight = windowInsetsProvider.getCurrentNavigationBarInset();
        final float keyboardHeight = windowInsetsProvider.getAnimatedImeBottomInset();

        for (int a = 0, N = inAppKeyboardBubbleContainer.getChildCount(); a < N; a++) {
            final View child = inAppKeyboardBubbleContainer.getChildAt(a);
            if (child instanceof InAppKeyboardInsetView) {
                InAppKeyboardInsetView insetView = (InAppKeyboardInsetView) child;
                insetView.applyNavigationBarHeight(navbarHeight);
                insetView.applyInAppKeyboardAnimatedHeight(keyboardHeight);
            }
        }
    }



    /* */

    private float inputBubbleOffsetLeft;
    private float inputBubbleOffsetRight;

    private float inputBubbleHeight;
    private int inputBubbleHeightRound;
    private float inputBubbleBottomInset;
    private float appliedInputBubbleBottomInset;
    private float inputBubbleBottomLiftReduction;
    public void setInputBubbleHeight(float height) {
        inputBubbleHeight = height;
        inputBubbleHeightRound = Math.round(inputBubbleHeight);
        checkBlurredHeight(false);
    }

    public void setInputBubbleBottomInset(float inset) {
        inset = Math.max(0, inset);
        if (inputBubbleBottomInset == inset) {
            return;
        }
        inputBubbleBottomInset = inset;
        checkBlurredHeight(false);
        invalidate();
    }

    // NagramX (#composer-toolbar): the island floats INPUT_BUBBLE_BOTTOM off the keyboard, sized for a
    // pill whose glass runs to its own edge. The fork's formatting row is the bottom-most thing instead, and
    // it already holds its capsule 2dp inside itself, so that lift reads as double the intended gap. Let the
    // owner shorten it, in px to match setInputBubbleBottomInset above, rather than moving the constant:
    // DialogsActivity shares this view and never enables the row, so it keeps the stock lift.
    public void setInputBubbleBottomLiftReduction(float reduction) {
        reduction = Math.max(0, Math.min(reduction, dp(INPUT_BUBBLE_BOTTOM)));
        if (inputBubbleBottomLiftReduction == reduction) {
            return;
        }
        inputBubbleBottomLiftReduction = reduction;
        checkBlurredHeight(false);
        invalidate();
    }

    // Everything anchored to the island's bottom reads this rather than the raw constant, so the pill, its
    // children and the buttons parked beside them stay on the same edge.
    public float getInputBubbleBottomLift() {
        // NagramX (#interface-style): the MD3 island sets its own lift, which depends on whether it is docked.
        return dp(INPUT_BUBBLE_BOTTOM) - inputBubbleBottomLiftReduction + (md3Surface != null ? md3Surface.liftAboveStock(maxBottomInset) : 0)
            // NagramX (#composer-keyboard-gap): extra room over an open keyboard, none at rest.
            + (keyboardGapEnabled ? com.dazewell.gram.ui.ComposerToolbarLayout.keyboardGap(this, maxBottomInset) : 0);
    }

    public void setInputBubbleOffsets(float left, float right) {
        inputBubbleOffsetLeft = left;
        inputBubbleOffsetRight = right;
        updateInputBubbleGeometry();
        invalidate();
    }

    public float getInputBubbleHeight() {
        // NagramX: the MD3 Composer island starts above the pill, and everything laid out against the composer
        // (the message list's bottom padding, side buttons, fades) has to clear the island, not the pill.
        if (md3Surface != null && inputBubbleHeight > 0) {
            return inputBubbleHeight + md3Surface.topOverhang();
        }
        return inputBubbleHeight;
    }

    public float getInputBubbleTop() {
        return getInputBubbleBottom() - getInputBubbleHeight();
    }

    public float getInputBubbleBottom() {
        return getMeasuredHeight() - maxBottomInset - getInputBubbleBottomLift();
    }

    // NagramX (#composer-toolbar): the bottom of the pill as drawn. The toolbar row sits inside the island's
    // height but under the pill, so anything centred on the pill alone, like the pull-to-next hint once the
    // input has faded out, has to stop short of it. The MD3 island reaches as far below the pill as above it.
    public float getInputBubbleDrawnBottom() {
        return getInputBubbleBottom() - appliedInputBubbleBottomInset + (md3Surface != null ? md3Surface.topOverhang() : 0);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        checkBlurredHeight(true);
        checkDrawableBounds();
        checkViewsPositions();
        checkInAppKeyboardChild();
    }

    /* Render */

    private final Rect tmpRect = new Rect();
    private final Rect inputBubbleFullBounds = new Rect();
    private final RectF tmpRectF = new RectF();

    private void updateInputBubbleGeometry() {
        appliedInputBubbleBottomInset = Math.min(inputBubbleBottomInset, Math.max(0, inputBubbleHeightRound - dp(INPUT_BUBBLE_MIN_HEIGHT)));
        final int blurTop = getMeasuredHeight() - currentBlurredHeight;
        inputBubbleFullBounds.set(
            Math.round(inputBubbleOffsetLeft),
            0,
            getMeasuredWidth() - Math.round(inputBubbleOffsetRight),
            inputBubbleHeightRound
        );
        inputBubbleFullBounds.inset(0, -dp(7));
        inputBubbleFullBounds.offset(0, blurTop + (int) bubbleInputTranlationY);
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        underKeyboardBackgroundDrawable.setBounds(
            0,
            getMeasuredHeight() - (int) imeBottomInset,
            getMeasuredWidth(),
            Math.max(getMeasuredHeight(), getMeasuredHeight() - (int) imeBottomInset + dp(INPUT_KEYBOARD_RADIUS * 2))
        );

        updateInputBubbleGeometry();
        final int blurTop = getMeasuredHeight() - currentBlurredHeight;
        final int drawnBubbleHeight = Math.max(0, inputBubbleHeightRound - Math.round(appliedInputBubbleBottomInset));

        tmpRect.set(
            Math.round(inputBubbleOffsetLeft),
            0,
            getMeasuredWidth() - Math.round(inputBubbleOffsetRight),
            drawnBubbleHeight
        );
        tmpRect.inset(0, -dp(7));
        tmpRect.offset(0, blurTop + (int) bubbleInputTranlationY);
        // NagramX (#glass-composer-fold): at rest the pill shrinks into the gap between the tool capsules, at their height. Drawing
        // only; inputBubbleFullBounds keeps the full width for touch. The fold's last frames move no height, so
        // nothing else redraws this container for them.
        if (md3Surface == null && glassFoldHost != null) {
            glassFoldEdges[0] = tmpRect.left;
            glassFoldEdges[1] = tmpRect.right;
            glassFoldEdges[2] = tmpRect.top;
            glassFoldEdges[3] = tmpRect.bottom;
            if (com.dazewell.gram.composer.ComposerInlineRest.glassPillEdges(glassFoldHost, this, dp(7), glassFoldEdges)) {
                tmpRect.set(glassFoldEdges[0], glassFoldEdges[2], glassFoldEdges[1], glassFoldEdges[3]);
            }
            if (com.dazewell.gram.composer.ComposerInlineRest.morphing(glassFoldHost)) {
                postInvalidateOnAnimation();
            }
        }
        // NagramX (#glass-lone-circle): the composer capsules' drop shades paint here, before the
        // pill they sit under, because the row's own canvas is clipped to the row's bounds
        // (adb-traced: [0,0][1042,147]) and their spill past the flush edges dies there. The pill's
        // own shade never had the problem - it paints on this container's screen-wide canvas.
        if (glassFoldHost != null) {
            glassFoldHost.drawComposerGlassShades(canvas, this);
        }
        blurredBackgroundDrawable.setBounds(tmpRect);
        // NagramX: the MD3 Composer paints its own island instead. Both drawables keep their bounds, since
        // the in-app keyboard clip below and the touch capture still read them.
        if (md3Surface != null) {
            md3Surface.draw(canvas, getMeasuredWidth(), getMeasuredHeight(), blurredBackgroundDrawable.getPaddedBounds(), bubbleInputTranlationY,
                blurredBackgroundDrawable.getAlpha(), appliedInputBubbleBottomInset, drawInputBackground, needDrawInAppKeyboard ? underKeyboardBackgroundDrawable.getBounds() : null);
        } else if (drawInputBackground)
            blurredBackgroundDrawable.draw(canvas);

        if (needDrawInAppKeyboard && md3Surface == null) {
            underKeyboardBackgroundDrawable.draw(canvas);
        }

        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        final boolean needClip = child == inAppKeyboardBubbleContainer;
        if (needClip) {
            canvas.save();
            canvas.clipPath(underKeyboardBackgroundDrawable.getPath());
        }

        final boolean result = super.drawChild(canvas, child, drawingTime);
        if (needClip) {
            canvas.restore();
        }

        return result;
    }





    private BlurredBackgroundWithFadeDrawable backgroundWithFadeDrawable;

    public void setBackgroundWithFadeDrawable(BlurredBackgroundWithFadeDrawable backgroundWithFadeDrawable) {
        this.backgroundWithFadeDrawable = backgroundWithFadeDrawable;
    }

    private float blurredBottomHeight;
    public void setBlurredBottomHeight(float height) {
        if (blurredBottomHeight != height) {
            blurredBottomHeight = height;
            checkDrawableBounds();
        }
    }

    private float bubbleInputTranlationY;
    public void setInputBubbleTranslationY(float translationY) {
        this.bubbleInputTranlationY = translationY;
        updateInputBubbleGeometry();
        invalidate();
    }

    public void setInputBubbleAlpha(int alpha) {
        if (blurredBackgroundDrawable != null) {
            blurredBackgroundDrawable.setAlpha(alpha);
        }

    }

    private void checkDrawableBounds() {
        if (backgroundWithFadeDrawable == null) {
            return;
        }

        final int oldBound = backgroundWithFadeDrawable.getBounds().top;
        final int newBound = getMeasuredHeight() - Math.round(blurredBottomHeight);

        if (oldBound != newBound) {
            backgroundWithFadeDrawable.setBounds(0, newBound, getMeasuredWidth(), getMeasuredHeight());
            fadeView.invalidate(0, Math.max(0, Math.min(oldBound, newBound)), getMeasuredWidth(), getMeasuredHeight());
            invalidate(0, Math.max(0, Math.min(oldBound, newBound)), getMeasuredWidth(), getMeasuredHeight());
        }
    }


    private boolean captured;

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // NagramX (#interface-style): the MD3 send circle's 48dp touch target reaches past its 44dp slot.
        if (md3Surface == null) {
            return super.dispatchTouchEvent(event);
        }
        md3Surface.retargetSendTouch(event);
        final boolean handled = super.dispatchTouchEvent(event);
        md3Surface.restoreSendTouch(event);
        return handled;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getAction();

        if (action == MotionEvent.ACTION_DOWN) {
            final int x = (int) event.getX();
            final int y = (int) event.getY();

            captured = blurredBackgroundDrawable != null && blurredBackgroundDrawable.getAlpha() == 255 && inputBubbleFullBounds.contains(x, y)
                || underKeyboardBackgroundDrawable != null && underKeyboardBackgroundDrawable.getBounds().contains(x, y)
                || md3Surface != null && md3Surface.contains(x, y);

        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            captured = false;
        }

        return captured;
    }
}
