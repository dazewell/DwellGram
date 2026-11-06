package com.dazewell.gram.composer;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.Selection;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.chat.ChatInputViewsContainer;

import com.dazewell.gram.ui.ComposerToolbarLayout;

/** One composer's UI-thread gesture, selection and draw-only spotlight state. */
public final class ComposerCursorController {
    private static final int CHARACTER_STEP_DP = 12;
    private static final int LINE_STEP_DP = 24;
    private static final int[] ACTION_LABELS = {
            R.string.ComposerCursorLeft, R.string.ComposerCursorRight,
            R.string.ComposerCursorUp, R.string.ComposerCursorDown,
            R.string.ComposerCursorSelectLeft, R.string.ComposerCursorSelectRight,
            R.string.ComposerCursorSelectUp, R.string.ComposerCursorSelectDown
    };

    private final ChatActivityEnterView enterView;
    private final View root;
    private final Theme.ResourcesProvider resourcesProvider;
    private final ImageView button;
    private final Spotlight spotlight = new Spotlight();
    private EditTextCaption field;
    private ComposerCursorNavigation navigation;
    private boolean destroyed, pending, active, selecting, tapWaiting;
    private int generation, pointer = -1, anchor, focus, axis = -1;
    private float downX, downY, lastX, lastY, remainder;
    private float tapX, tapY;
    private long downTime, tapTime;
    private Runnable holdCallback, hintCallback;
    private final Rect hitRect = new Rect();
    private int iconColor, selectorColor;
    private boolean colorsApplied;

    private final TextWatcher watcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { cancel(); }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
        @Override public void afterTextChanged(Editable s) { refresh(); }
    };
    private final ViewTreeObserver.OnPreDrawListener preDraw = this::onPreDraw;

    private boolean onPreDraw() {
        refresh();
        if ((pending || active || tapWaiting) && (!available() || !button.isShown()
                || !enterView.hasWindowFocus())) {
            cancel();
        }
        if (active) {
            spotlight.setBounds(0, 0, root.getWidth(), root.getHeight());
            root.invalidate();
        }
        return true;
    }
    private final View.OnAttachStateChangeListener fieldAttachment = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) { }
        @Override public void onViewDetachedFromWindow(View v) { cancel(); }
    };

    public ComposerCursorController(ChatActivityEnterView enterView, ComposerToolbarLayout toolbar,
                                    View root, Theme.ResourcesProvider resourcesProvider) {
        this.enterView = enterView;
        this.root = root;
        this.resourcesProvider = resourcesProvider;
        button = new ImageView(toolbar.getContext()) {
            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                getViewTreeObserver().addOnPreDrawListener(preDraw);
            }
            @Override protected void onDetachedFromWindow() {
                cancel();
                getViewTreeObserver().removeOnPreDrawListener(preDraw);
                super.onDetachedFromWindow();
            }
            @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
                super.onWindowFocusChanged(hasWindowFocus);
                if (!hasWindowFocus) cancel();
            }
            @Override protected void onVisibilityChanged(View changedView, int visibility) {
                super.onVisibilityChanged(changedView, visibility);
                if (visibility != VISIBLE && spotlight != null) cancel();
            }
        };
        button.setImageResource(R.drawable.composer_trackball);
        button.setContentDescription(LocaleController.getString(R.string.ComposerCursor));
        button.setFocusable(false);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
                if (available()) {
                    for (int label : ACTION_LABELS) {
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(label, LocaleController.getString(label)));
                    }
                }
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                for (int i = 0; i < ACTION_LABELS.length; i++) {
                    if (action == ACTION_LABELS[i] && available()) {
                        if (pending || active || tapWaiting) cancel();
                        boolean extend = i >= 4;
                        if (navigation == null || selecting != extend || focus != field.getSelectionEnd()
                                || extend && anchor != field.getSelectionStart()) {
                            snapshot(extend);
                        }
                        move(i % 4);
                        return true;
                    }
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
        toolbar.addConfigurable(ComposerButtons.CURSOR, button);
        toolbar.setCursorTouchTarget(button, (v, event) -> onTouch(event));
        refresh();
    }

    public void bind(EditTextCaption editor) {
        cancel();
        if (field != null) {
            field.removeTextChangedListener(watcher);
            field.removeOnAttachStateChangeListener(fieldAttachment);
        }
        field = editor;
        if (editor != null) {
            editor.addTextChangedListener(watcher);
            editor.addOnAttachStateChangeListener(fieldAttachment);
        }
        refresh();
    }

    private boolean available() {
        return !destroyed && field != null && field.isEnabled() && field.isShown()
                && field.getKeyListener() != null && field.length() > 0
                && !enterView.isRichDraftActive() && field.getSelectionStart() >= 0
                && field.getSelectionEnd() >= 0;
    }

    private void refresh() {
        if (button == null) return;
        boolean enabled = available();
        button.setEnabled(enabled);
        int color = Theme.getColor(active ? Theme.key_chat_messagePanelCursor : Theme.key_glass_defaultIcon, resourcesProvider);
        if (!enabled) color = (color & 0x00ffffff) | 0x73000000;
        if (!colorsApplied || iconColor != color) {
            iconColor = color;
            button.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        }
        int selector = Theme.getColor(Theme.key_listSelector, resourcesProvider);
        if (!colorsApplied || selectorColor != selector) {
            selectorColor = selector;
            button.setBackground(ComposerToolbarLayout.panelSelector(selector));
        }
        colorsApplied = true;
        button.setPressed(active);
    }

    private void snapshot(boolean selection) {
        selecting = selection;
        anchor = field.getSelectionStart();
        focus = field.getSelectionEnd();
        navigation = new ComposerCursorNavigation(field.getText());
    }

    private boolean onTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            boolean second = tapWaiting && event.getEventTime() - tapTime <= ViewConfiguration.getDoubleTapTimeout()
                    && Math.hypot(event.getRawX() - tapX, event.getRawY() - tapY)
                    <= ViewConfiguration.get(button.getContext()).getScaledDoubleTapSlop();
            cancel();
            if (!available()) return true;
            snapshot(second);
            pointer = event.getPointerId(0);
            downX = lastX = event.getRawX();
            downY = lastY = event.getRawY();
            downTime = event.getEventTime();
            pending = true;
            final int token = generation;
            holdCallback = () -> {
                if (token == generation && pending && available()) activate();
            };
            button.postDelayed(holdCallback, ViewConfiguration.getLongPressTimeout());
            return true;
        }
        if (pointer < 0) return true;
        int index = event.findPointerIndex(pointer);
        if (index < 0 || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer) {
            cancel();
            return true;
        }
        if (!available()) {
            cancel();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float x = event.getX(index) + event.getRawX() - event.getX();
            float y = event.getY(index) + event.getRawY() - event.getY();
            float dx = x - downX;
            float dy = y - downY;
            if (pending && Math.hypot(dx, dy) > ViewConfiguration.get(button.getContext()).getScaledTouchSlop()) activate();
            if (active) track(x - lastX, y - lastY);
            if (active || !pending) {
                lastX = x;
                lastY = y;
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            boolean quickTap = pending && event.getEventTime() - downTime < ViewConfiguration.getLongPressTimeout();
            boolean selectWord = quickTap && selecting;
            int[] word = selectWord ? navigation.wordAt(focus) : null;
            float x = event.getRawX(), y = event.getRawY();
            long time = event.getEventTime();
            cancel();
            if (selectWord) {
                if (word != null) {
                    Selection.setSelection(field.getText(), word[0], word[1]);
                    field.bringPointIntoView(word[1]);
                    field.invalidate();
                }
            } else if (quickTap) {
                tapWaiting = true;
                tapX = x;
                tapY = y;
                tapTime = time;
                final int token = generation;
                hintCallback = () -> {
                    if (token == generation && tapWaiting && available()) {
                        tapWaiting = false;
                        Toast.makeText(button.getContext(), LocaleController.getString(R.string.ComposerCursorHint), Toast.LENGTH_LONG).show();
                    }
                };
                button.postDelayed(hintCallback, ViewConfiguration.getDoubleTapTimeout());
            }
        }
        return true;
    }

    private void activate() {
        pending = false;
        active = true;
        button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        spotlight.setBounds(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(spotlight);
        refresh();
    }

    static int sensitivity() {
        return Math.max(50, Math.min(200, xyz.nextalone.nagram.NaConfig.INSTANCE.getComposerCursorSensitivity().Int()));
    }

    private void track(float x, float y) {
        if (x == 0 && y == 0) return;
        boolean horizontal = Math.abs(x) >= Math.abs(y);
        int nextAxis = horizontal ? 0 : 1;
        if (axis != nextAxis) remainder = 0;
        axis = nextAxis;
        remainder += horizontal ? x : y;
        float threshold = AndroidUtilities.dp(horizontal ? CHARACTER_STEP_DP : LINE_STEP_DP) * 100f / sensitivity();
        while (Math.abs(remainder) >= threshold) {
            move(horizontal ? (remainder < 0 ? 0 : 1) : (remainder < 0 ? 2 : 3));
            remainder -= Math.copySign(threshold, remainder);
        }
    }

    private void move(int direction) {
        if (!available() || field.getLayout() == null || navigation == null) return;
        int next = navigation.move(field.getLayout(), focus, direction);
        if (next == focus) return;
        focus = next;
        Selection.setSelection(field.getText(), selecting ? anchor : focus, focus);
        field.bringPointIntoView(focus);
        field.invalidate();
    }

    private void cancel() {
        generation++;
        pending = active = tapWaiting = false;
        pointer = -1;
        axis = -1;
        remainder = 0;
        navigation = null;
        if (button != null) {
            if (holdCallback != null) button.removeCallbacks(holdCallback);
            if (hintCallback != null) button.removeCallbacks(hintCallback);
        }
        holdCallback = hintCallback = null;
        root.getOverlay().remove(spotlight);
        refresh();
    }

    public void destroy() {
        destroyed = true;
        bind(null);
        if (button.getViewTreeObserver().isAlive()) button.getViewTreeObserver().removeOnPreDrawListener(preDraw);
    }

    private final class Spotlight extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path inputPath = new Path();
        private final Path buttonPath = new Path();
        private final Path holes = new Path();
        private final Matrix toRoot = new Matrix();
        private final Rect visible = new Rect();
        private final RectF cutout = new RectF();
        private final RectF inputBounds = new RectF();
        private final RectF buttonBounds = new RectF();

        @Override public void draw(Canvas canvas) {
            if (!active || field == null) return;
            if (!root.getLocalVisibleRect(visible) || !inputPath()) return;
            inputPath.computeBounds(inputBounds, true);
            if (!button.getLocalVisibleRect(hitRect) || !mapToRoot(button)) return;
            cutout.set(hitRect);
            buttonPath.rewind();
            buttonPath.addRoundRect(cutout, AndroidUtilities.dp(8), AndroidUtilities.dp(8), Path.Direction.CW);
            buttonPath.transform(toRoot);
            buttonPath.computeBounds(buttonBounds, true);
            // Overlapping cutouts must stay clear rather than cancel each other under EVEN_ODD.
            holes.op(inputPath, buttonPath, Path.Op.UNION);
            int save = canvas.save();
            canvas.clipRect(visible);
            path.rewind();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(visible.left, visible.top, visible.right, visible.bottom, Path.Direction.CW);
            path.addPath(holes);
            // A neutral scrim keeps its contrast against both light and dark chat themes.
            paint.setColor(Theme.isCurrentThemeDark() ? 0x99000000 : 0x66000000);
            canvas.drawPath(path, paint);
            String label = LocaleController.getString(selecting ? R.string.ComposerCursorSelecting : R.string.ComposerCursorMoving);
            paint.setTextSize(AndroidUtilities.dp(12));
            float width = paint.measureText(label) + AndroidUtilities.dp(16);
            float left = Math.max(visible.left, Math.min(buttonBounds.centerX() - width / 2, visible.right - width));
            float upperEdge = Math.min(buttonBounds.top, inputBounds.top);
            float top = Math.max(visible.top, upperEdge - AndroidUtilities.dp(30));
            cutout.set(left, top, left + width, top + AndroidUtilities.dp(24));
            paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
            canvas.drawRoundRect(cutout, AndroidUtilities.dp(8), AndroidUtilities.dp(8), paint);
            paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            canvas.drawText(label, left + AndroidUtilities.dp(8), top + AndroidUtilities.dp(16), paint);
            canvas.restoreToCount(save);
        }

        private boolean inputPath() {
            View ancestor = enterView;
            while (!(ancestor instanceof ChatInputViewsContainer)) {
                if (!(ancestor.getParent() instanceof View)) return false;
                ancestor = (View) ancestor.getParent();
            }
            ChatInputViewsContainer host = (ChatInputViewsContainer) ancestor;
            if (!host.drawInputBackground || !mapToRoot(host)) return false;
            if (host.md3Surface != null) {
                if (!host.md3Surface.copyInputFieldPath(inputPath)) return false;
            } else {
                if (host.blurredBackgroundDrawable == null || host.blurredBackgroundDrawable.getAlpha() == 0) return false;
                inputPath.set(host.blurredBackgroundDrawable.getPath());
            }
            inputPath.transform(toRoot);
            return !inputPath.isEmpty();
        }

        private boolean mapToRoot(View view) {
            toRoot.reset();
            while (view != root) {
                if (!(view.getParent() instanceof View)) return false;
                View parent = (View) view.getParent();
                toRoot.postConcat(view.getMatrix());
                toRoot.postTranslate(view.getLeft() - parent.getScrollX(), view.getTop() - parent.getScrollY());
                view = parent;
            }
            return true;
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
