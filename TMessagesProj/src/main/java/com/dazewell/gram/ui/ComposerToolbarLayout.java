package com.dazewell.gram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import xyz.nextalone.nagram.NaConfig;
import com.dazewell.gram.helpers.InterfaceStyleController;
import com.dazewell.gram.composer.ComposerButtons;
import com.dazewell.gram.composer.ComposerLayout;

/**
 * Presentation-only row for the chat composer controls.
 *
 * The controls keep their original ownership and state transitions in
 * ChatActivityEnterView; this class only gives them stable slots.
 */
public final class ComposerToolbarLayout extends FrameLayout {

    private static final int BASE_HEIGHT = 56;
    private static final int BASE_BUTTON_SIZE = 48;
    // Cells are packed to this share of BASE_BUTTON_SIZE: the tightest step the removed Icon spacing
    // slider offered, now fixed. Never above 100, or the cell would outgrow the row and be clipped.
    private static final float PACKING = 0.75f;
    private static final int SCALE_MIN = 75;
    private static final int SCALE_MAX = 125;
    /**
     * Smallest cell the row will draw. Two terms, and neither one is a
     * taste number: an absolute floor that stops a small row shrinking its targets away entirely, and
     * the widest glyph the cell has to hold, read from the icon table so that measuring a sparser
     * glyph than today's widest moves this floor with it instead of starving that glyph.
     *
     * <p>The press ripple is sized off this rather than the other way round - see
     * {@link #panelSelector(int)}.
     */
    private static final int MIN_CELL_FLOOR = 36;
    // MD3 sizes run linearly through the 75/100/125% anchors: row and cell 40/48/56dp, state layer 32/40/48dp,
    // glyph 20/24/28dp. Cells are packed MD3_SPACING_SQUEEZE narrower than the row, never shorter.
    private static final float MD3_ROW_BASE = 16, MD3_ROW_PER_SCALE = 32;
    private static final float MD3_STATE_BASE = 8, MD3_STATE_PER_SCALE = 32;
    private static final float MD3_GLYPH_BASE = 8, MD3_GLYPH_PER_SCALE = 16;
    private static final int MD3_SPACING_SQUEEZE = 4;
    private static final int MD3_CELL_FLOOR = 36;
    private static final int MD3_GROUP_GAP = 8;
    private static final int MD3_SEAM_HEIGHT = 24;
    private static final int ICON_GLYPH = 24;
    private static final int GLASS_INSET = 4;
    private static final int GLASS_DRAW_INSET = 2;
    private static final int BOUNDS_SETTLE_DELAY = 48;
    private static final int BOUNDS_SETTLE_MAX = 150;
    private static final int CONFIGURATION_LONG_PRESS_MS = 1000;
    // The keyboard gap reaches its full size once the keyboard or emoji panel has risen this far.
    private static final int KEYBOARD_GAP_RAMP = 48;
    public static final int KEYBOARD_GAP_MAX = 24;

    private final ControlsLayout controls;
    private final CollapsingLinearLayout startSlot;
    private final HorizontalScrollView middleScrollView;
    private final CollapsingLinearLayout middleLeadingSlot;
    private final CollapsingLinearLayout orderedSlot;
    private final CollapsingLinearLayout endSlot;
    private final Map<View, Integer> configuredOrder = new HashMap<>();
    /**
     * deleteRichDraftButton (see addStart) is not a registered, orderable button - it is a plain
     * child appended once and left there. Its own visibility is toggled elsewhere (see
     * updateRichDraftPreview in ChatActivityEnterView, left untouched by this change) so that at
     * most it or the emoji button is showing at a time; this class does not enforce that. What it
     * does need from this class is a fixed anchor position so a later configurable button never
     * inserts on the wrong side of it.
     */
    private View pinnedLeadingView;
    private Runnable configurationLongPress;
    private boolean configurationLongPressTriggered;
    private float configurationLongPressX;
    private float configurationLongPressY;
    private final Rect controlsHitRect = new Rect();
    private final Runnable configurationLongPressRunnable = () -> {
        configurationLongPressTriggered = true;
        if (configurationLongPress != null) {
            configurationLongPress.run();
        }
    };

    public ComposerToolbarLayout(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);

        controls = new ControlsLayout(context);
        controls.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        controls.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        addView(controls, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, controls.rowHeightDp(), Gravity.BOTTOM));

        startSlot = createCollapsingSlot(context);
        startSlot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        middleScrollView = new ComposerMiddleScrollView(context);
        middleScrollView.setHorizontalScrollBarEnabled(false);
        middleScrollView.setHorizontalFadingEdgeEnabled(true);
        middleScrollView.setFadingEdgeLength(AndroidUtilities.dp(12));
        middleScrollView.setFillViewport(false);
        middleScrollView.setFocusable(false);
        middleScrollView.setFocusableInTouchMode(false);
        middleScrollView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        middleScrollView.setClipChildren(true);
        middleScrollView.setClipToPadding(true);
        middleScrollView.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        LinearLayout middle = createLinearSlot(context);
        middle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        middle.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        middleScrollView.addView(middle, new HorizontalScrollView.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));

        middleLeadingSlot = createCollapsingSlot(context);
        middleLeadingSlot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        orderedSlot = createCollapsingSlot(context);
        orderedSlot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        middle.addView(middleLeadingSlot, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        middle.addView(orderedSlot, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));

        endSlot = createCollapsingSlot(context);
        endSlot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        controls.setSlots(startSlot, middleScrollView, middle, endSlot);
        middle.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) {
                pinMiddleToStart();
            }
        });
        middleScrollView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) {
                pinMiddleToStart();
            }
        });
        middleScrollView.post(this::pinMiddleToStart);
        // NagramX (#composer-inline-rest): the send-as avatar and the bot pill arrive after the chat opens, and either
        // one keeps the composer in two rows. The slot still lays out while a one-row composer hides it.
        middleLeadingSlot.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft && controls.restGeometryListener != null) {
                post(controls.restGeometryListener);
            }
        });
    }

    public void setConfigurationLongPress(Runnable listener) {
        configurationLongPress = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (configurationLongPressTriggered && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                removeCallbacks(configurationLongPressRunnable);
            }
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                configurationLongPressTriggered = false;
                configurationLongPressX = event.getX();
                configurationLongPressY = event.getY();
                removeCallbacks(configurationLongPressRunnable);
                boolean consumed = super.dispatchTouchEvent(event);
                // Only arm the timer once super confirms this DOWN actually became a
                // touch target here - that's the framework's own guarantee that an UP
                // or CANCEL will come back to cancel it. A DOWN nobody consumed never
                // gets redelivered, so arming on bounds alone left the timer stuck
                // armed whenever the tap missed every button (dead space, gaps between
                // slots, the transparent margin around the capsule).
                if (configurationLongPress != null && consumed) {
                    controls.getHitRect(controlsHitRect);
                    if (controls.getVisibility() == View.VISIBLE
                            && controlsHitRect.contains((int) configurationLongPressX, (int) configurationLongPressY)) {
                        postDelayed(configurationLongPressRunnable, CONFIGURATION_LONG_PRESS_MS);
                    }
                }
                return consumed;
            }
            case MotionEvent.ACTION_MOVE:
                if (!configurationLongPressTriggered
                        && (Math.abs(event.getX() - configurationLongPressX) > ViewConfiguration.get(getContext()).getScaledTouchSlop()
                        || Math.abs(event.getY() - configurationLongPressY) > ViewConfiguration.get(getContext()).getScaledTouchSlop())) {
                    removeCallbacks(configurationLongPressRunnable);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(configurationLongPressRunnable);
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(configurationLongPressRunnable);
        super.onDetachedFromWindow();
    }

    public void attachGlass(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        if (InterfaceStyleController.applyComposer()) {
            controls.clearGlass();
            return;
        }
        controls.attachGlass(factory, colorProvider);
    }

    // NagramX: force the glass bubbles to re-record their baked display lists. The RenderNode glass
    // path copies the source's BitmapShader Paint at record time, so after the preview re-points that
    // source at new dimensions (onSizeChanged) the capture is stale until the list is invalidated.
    public void reprimeGlass() {
        controls.reprimeGlass();
    }

    public void updateColors() {
        controls.updateColors();
    }

    public void addStart(View view) {
        AndroidUtilities.removeFromParent(view);
        // Always appended, never re-sorted against the configurable buttons: it stands in for
        // whichever of them the user is not currently looking at (see updateRichDraftPreview in
        // ChatActivityEnterView), so it needs a fixed slot of its own rather than a config order.
        pinnedLeadingView = view;
        startSlot.addView(view, LayoutHelper.createLinear(buttonSize(), buttonSize()));
        applyIconBox(null, view);
    }

    /**
     * Places a button the user is allowed to move. The zone and the position inside it come from the
     * saved layout, resolved as each view arrives: schedule, the send-as avatar and the bot pill are
     * all created long after the constructor, so there is no single moment where everything is present
     * and the whole row could be sorted in one pass.
     */
    public void addConfigurable(String key, View view) {
        addConfigurable(key, view, ComposerLayout.zoneOf(key), ComposerLayout.indexOf(key));
    }

    /**
     * Places a button against a caller supplied layout rather than the saved one. The settings
     * preview needs this: it renders the arrangement being dragged, which is deliberately not
     * written to config until the drag ends, so resolving placement from the stored string there
     * would show the previous layout for the whole gesture. The order is local to the supplied
     * zone, matching ComposerLayout.indexOf().
     */
    public void addConfigurable(String key, View view, int zone, int order) {
        AndroidUtilities.removeFromParent(view);
        configuredOrder.put(view, order);
        // One sizing gate for every configurable button, whatever zone it lands in. The assets are all
        // 24dp vectors, but their ink fills the canvas by different amounts and sits off-centre by a
        // fraction of a dp, so the box centers each glyph in its cell; the per-glyph optical
        // correction is baked into the fork-owned vector, not applied here.
        applyIconBox(key, view);
        if (zone == ComposerButtons.ZONE_HIDDEN) {
            // Left without a parent rather than set GONE: the enter view reads these buttons' visibility
            // to decide its own geometry, so a removed button has to keep whatever visibility it had.
            return;
        }
        if (zone == ComposerButtons.ZONE_START) {
            startSlot.addView(view, insertIndex(startSlot, order, startContextIndex()), LayoutHelper.createLinear(buttonSize(), buttonSize()));
            return;
        }
        if (zone == ComposerButtons.ZONE_END) {
            endSlot.addView(view, insertIndex(endSlot, order, endSlot.getChildCount()), LayoutHelper.createLinear(buttonSize(), buttonSize()));
            return;
        }
        orderedSlot.addView(view, insertIndex(orderedSlot, order, orderedSlot.getChildCount()), LayoutHelper.createLinear(buttonSize(), buttonSize()));
        middleScrollView.post(this::pinMiddleToStart);
    }

    // Walks the siblings already in the slot and stops in front of the first one the layout puts
    // after this button. Views with no recorded order (the trailing context group) are left where they are.
    private int insertIndex(ViewGroup slot, int order, int limit) {
        for (int i = 0; i < limit && i < slot.getChildCount(); i++) {
            Integer other = configuredOrder.get(slot.getChildAt(i));
            if (other != null && other > order) {
                return i;
            }
        }
        return limit;
    }

    public void addMiddleLeading(View view, int width, int height, float horizontalMargin, float verticalMargin, int index) {
        AndroidUtilities.removeFromParent(view);
        middleLeadingSlot.addView(view, Math.min(index, middleLeadingSlot.getChildCount()),
                LayoutHelper.createLinear(width, height, horizontalMargin, verticalMargin, horizontalMargin, verticalMargin));
        middleScrollView.post(this::pinMiddleToStart);
    }

    // The attach group is not user-placeable, so it stays at the head of the trailing zone and the
    // configured buttons order themselves after it.
    public void addContextGroup(View view) {
        AndroidUtilities.removeFromParent(view);
        endSlot.addView(view, 0, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        endSlot.setContextGroup(view);
        controls.setTrailingContextGroup(view);
    }

    // Caps where a configurable leading button can land so it never
    // inserts past the delete-draft button's reserved slot (see addStart).
    private int startContextIndex() {
        int index = pinnedLeadingView != null ? startSlot.indexOfChild(pinnedLeadingView) : -1;
        return index < 0 ? startSlot.getChildCount() : index;
    }

    public void addReplacement(View view) {
        AndroidUtilities.removeFromParent(view);
        addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.BOTTOM));
    }

    public void setControlsVisible(boolean visible) {
        controls.setPanelVisible(visible);
    }

    public boolean controlsVisible() {
        return controls.getVisibility() == VISIBLE;
    }

    // NagramX (#composer-inline-rest): the floating composer folds the field into this row at rest. The scrolling
    // zone has no room beside the field, so it goes rather than its buttons, which keeps every slot's occupancy as is.
    public void setRestCompact(boolean compact) {
        controls.setRestCompact(compact);
    }

    /** Keeps the send column, which sits at this row's end under the inline-rest composer, clear of the trailing zone. */
    public void setSendReserve(int px) {
        controls.setSendReserve(px);
    }

    public void setMiddleAlpha(float alpha) {
        if (middleScrollView.getAlpha() != alpha) {
            middleScrollView.setAlpha(alpha);
        }
    }

    /** How far the leading zone reaches in from the row's start edge, in px; 0 while it is empty, -1 before layout. */
    public int leadingExtent() {
        return controls.restLeading;
    }

    /** How far the trailing zone, plus the send reserve, reaches in from the row's end edge, in px; -1 before layout. */
    public int trailingExtent() {
        return controls.restTrailing;
    }

    /** The send-as avatar or the bot menu pill: both head the scrolling zone, which the one-row layout drops. */
    public boolean hasMiddleLeading() {
        for (int i = 0; i < middleLeadingSlot.getChildCount(); i++) {
            if (middleLeadingSlot.isOccupied(middleLeadingSlot.getChildAt(i))) {
                return true;
            }
        }
        return false;
    }

    public int rowHeightDp() {
        return controls.rowHeightDp();
    }

    /** The space layout reserves between neighbouring zones, half of it painted by each one's glass capsule. */
    public int bubbleGapPx() {
        return controls.gapPx;
    }

    /**
     * What the leading and trailing zones take with an empty middle and no send reserve, side padding included, or -1
     * before the row is measured. The slots are measured unbounded, so this doesn't move with the reserve.
     */
    public int fixedSlotsWidth() {
        if (controls.getMeasuredWidth() <= 0 || controls.startSlot == null || controls.endSlot == null) {
            return -1;
        }
        final int start = controls.startSlot.getMeasuredWidth();
        final int end = controls.endSlot.getMeasuredWidth();
        return start + end + (start > 0 && end > 0 ? controls.gapPx : 0) + 2 * controls.sidePaddingPx;
    }

    public int rowWidth() {
        return controls.getMeasuredWidth();
    }

    /** True while the row paints Liquid Glass capsules. */
    public boolean hasGlassCapsules() {
        return controls.bubbles != null;
    }

    /** Top of the painted capsule band, in px from this view's top. */
    public int glassBandTop() {
        return controls.getTop() + controls.glassDrawInsetPx + controls.glassPaddingPx;
    }

    /** Bottom of the painted capsule band, in px from this view's top. */
    public int glassBandBottom() {
        return controls.getBottom() - controls.glassDrawInsetPx - controls.glassPaddingPx;
    }

    /** Runs, posted, whenever a layout moves either extent. */
    public void setOnRestGeometryChanged(Runnable listener) {
        controls.restGeometryListener = listener;
    }

    private void pinMiddleToStart() {
        View child = middleScrollView.getChildAt(0);
        if (child == null) {
            return;
        }
        int scrollX = LocaleController.isRTL
                ? Math.max(0, child.getWidth() - middleScrollView.getWidth())
                : 0;
        if (middleScrollView.getScrollX() != scrollX) {
            middleScrollView.scrollTo(scrollX, 0);
        }
    }

    private static SlidingLinearLayout createLinearSlot(Context context) {
        SlidingLinearLayout slot = new SlidingLinearLayout(context);
        slot.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        return slot;
    }

    private static CollapsingLinearLayout createCollapsingSlot(Context context) {
        CollapsingLinearLayout slot = new CollapsingLinearLayout(context);
        slot.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        return slot;
    }

    /**
     * The user's panel scale as a factor, clamped to the slider's own range so a hand-edited config
     * value cannot produce a panel that no longer fits the composer.
     */
    public static float scale() {
        return scalePercent() / 100f;
    }

    /** The user's panel scale as a whole percent, clamped to the slider's own range. */
    public static int scalePercent() {
        int percent = NaConfig.INSTANCE.getComposerToolbarScale().Int();
        return Math.max(SCALE_MIN, Math.min(SCALE_MAX, percent));
    }

    /**
     * Extra lift, in px, between the composer and an open keyboard or emoji panel. 0 while the
     * composer rests on the nav bar, ramping in over the first {@link #KEYBOARD_GAP_RAMP}dp the panel
     * rises so the composer does not jump as it starts to open.
     *
     * <p>The resting inset is the one WindowInsetsStateHolder builds the container's bottom inset from,
     * as ComposerMd3Surface.keyboardShare reads it, so a hidden nav bar or a bottom cutout is never
     * mistaken for a keyboard.
     */
    public static float keyboardGap(View host, float maxBottomInset) {
        int gapPx = keyboardGapPx();
        android.view.WindowInsets insets = gapPx == 0 ? null : host.getRootWindowInsets();
        if (insets == null) {
            return 0;
        }
        int rest = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, host)
                .getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                        | androidx.core.view.WindowInsetsCompat.Type.displayCutout()).bottom;
        float share = maxBottomInset - rest;
        return share <= 0 ? 0 : gapPx * Math.min(1f, share / AndroidUtilities.dp(KEYBOARD_GAP_RAMP));
    }

    /** The full keyboard gap in px, as it stands once the keyboard is up. */
    public static int keyboardGapPx() {
        return AndroidUtilities.dp(Math.max(0, Math.min(KEYBOARD_GAP_MAX, NaConfig.INSTANCE.getComposerKeyboardGap().Int())));
    }

    /**
     * Row height in dp for the <em>current</em> scale. This reads the live setting, so it can move
     * under an existing view the moment the slider changes. A view that has to hold one geometry for
     * its whole life - ControlsLayout, whose capsule radius and measured height must stay half-and-
     * whole of each other - snapshots this once at construction instead of calling it again per pass;
     * see ControlsLayout's geometry fields. The scale only changes while the settings screen is open
     * and closing it rebuilds every chat, so a fresh view picks up the new value together with the
     * layout params it is given.
     */
    public static int height() {
        if (InterfaceStyleController.applyComposer()) {
            return Math.round(MD3_ROW_BASE + MD3_ROW_PER_SCALE * scale());
        }
        return Math.round(BASE_HEIGHT * scale());
    }

    /** The glyph factor applyIconBox multiplies ICON_GLYPH by, before any per-button optical correction. */
    private static float glyphScale() {
        if (InterfaceStyleController.applyComposer()) {
            return (MD3_GLYPH_BASE + MD3_GLYPH_PER_SCALE * scale()) / ICON_GLYPH;
        }
        return scale();
    }

    /**
     * Button cell size in dp at the row's scale, packed as tight as it goes and held above a floor
     * so the cell always holds the widest glyph applyIconBox draws in it.
     */
    public static int buttonSize() {
        float scale = scale();
        return Math.max(minCellDp(scale), cellDp(scale));
    }

    private static int cellDp(float scale) {
        if (InterfaceStyleController.applyComposer()) {
            return Math.round(MD3_ROW_BASE + MD3_ROW_PER_SCALE * scale - MD3_SPACING_SQUEEZE);
        }
        return Math.round(BASE_BUTTON_SIZE * scale * PACKING);
    }

    /**
     * Smallest cell allowed at a given row scale. The absolute floor keeps a small row's targets
     * usable; the glyph term keeps the cell able to hold what it draws, and is read from the icon
     * table rather than typed here so that measuring a sparser glyph than today's widest moves this
     * floor with it instead of starving that glyph.
     */
    private static int minCellDp(float scale) {
        if (InterfaceStyleController.applyComposer()) {
            int widestGlyph = (int) Math.ceil((MD3_GLYPH_BASE + MD3_GLYPH_PER_SCALE * scale) * ComposerButtons.maxIconScale());
            return Math.max(MD3_CELL_FLOOR, widestGlyph);
        }
        int widestGlyph = (int) Math.ceil(ICON_GLYPH * ComposerButtons.maxIconScale() * scale);
        return Math.max(MIN_CELL_FLOOR, widestGlyph);
    }

    /**
     * The press selector every button in this row wears, with its circle sized to the smallest cell
     * the row allows at the current scale.
     *
     * <p>Sized off {@link #minCellDp(float)} rather than a dp constant on purpose. The radius has to
     * track the toolbar scale - it did not before, so scaling the row up left the circles further and
     * further inside their cells - and it has to track the glyph floor, since that floor is what the
     * tightest cell actually lands on.
     *
     * <p>Reads the live scale at construction, like {@link #buttonSize()}, and inherits that method's
     * lifecycle assumption: closing the settings screen rebuilds the stack it was opened from, top
     * included, and fires reloadInterface for the chats LaunchActivity owns
     * (ComposerLayoutActivity.flushRebuild). A bubble chat in another stack - BubbleActivity keeps
     * its own actionBarLayout - keeps its old geometry until it is recreated. That gap is older than
     * this method - buttonSize() has always had it - and is not made worse by sizing the circle the
     * same way.
     */
    public static Drawable panelSelector(int color) {
        // NagramX (#interface-style): MD3 draws a state layer 8dp narrower than the row, and at least 4dp
        // narrower than the cell so neighbouring circles never touch.
        if (InterfaceStyleController.applyComposer()) {
            float radius = Math.min(MD3_STATE_BASE + MD3_STATE_PER_SCALE * scale(), buttonSize() - MD3_SPACING_SQUEEZE) / 2f;
            return Theme.createSelectorDrawable(color, Theme.RIPPLE_MASK_CIRCLE_20DP, AndroidUtilities.dp(radius));
        }
        return Theme.createSelectorDrawable(color, Theme.RIPPLE_MASK_CIRCLE_20DP,
                AndroidUtilities.dp(minCellDp(scale()) / 2f));
    }

    /**
     * Places a panel button in the shared cell box at natural size. For the configurable format and
     * text buttons whose glyph needs an optical correction, that correction is baked into the
     * fork-owned vector asset (a group transform), not applied here - so the box is the same for
     * every button and this is all the enter view has to call.
     */
    public static void applyPanelIconBox(View view) {
        applyPanelIconBox(view, glyphScale());
    }

    /**
     * As {@link #applyPanelIconBox(View)}, but for the core buttons whose drawable is a raster or a
     * CrossOutDrawable that cannot be re-cropped as a vector. The correction is keyed on the drawable
     * resource in {@link ComposerButtons#iconScaleForResource(int)}, so no bare float is typed at the
     * call site.
     */
    public static void applyPanelIconBox(View view, int resourceId) {
        applyPanelIconBox(view, ComposerButtons.iconScaleForResource(resourceId) * glyphScale());
    }

    private static void applyPanelIconBox(View view, float iconScale) {
        applyIconBox(view, buttonSize(), iconScale);
    }

    private static int glassInset() {
        return Math.max(1, Math.round(GLASS_INSET * scale()));
    }

    /**
     * Vertical inset of the drawn bubbles inside the row. Paired with the attached glass padding on
     * the vertical axis only: each bubble's painted top and bottom land at glassDrawInset() +
     * glassInset() from the row edge. The horizontal edges do not use this - a bubble's outer flush
     * edge reaches the row edge and its inner edges are set from live child geometry (see drawGlass),
     * so this term is vertical-only and does not decide left/right alignment with the input pill.
     */
    private static int glassDrawInset() {
        return Math.max(1, Math.round(GLASS_DRAW_INSET * scale()));
    }

    /**
     * One icon inset (glyph-to-cell-edge at the neutral glyph scale) of horizontal padding for the
     * scrolling middle content, so its first and last icons clear the bubble's rounded end the same way
     * a mid-row icon clears its neighbour instead of running to the glass edge. Middle alone gets this:
     * it is the only bubble that scrolls and the only one whose item count the user leaves unbounded, so
     * it is the only one whose content can reach its own ends. Leading and the merged trailing group each
     * stay at one icon inset and so match each other, which is what keeps the row's two ends reading the
     * same; the trailing zone's roles share one slot with no container of their own, where padding would
     * become gap rather than clearance, so it is not given any.
     *
     * <p>Derived from the exact expression applyIconBox uses for a neutral glyph: the row scale() still
     * applies (that is the factor applyPanelIconBox passes for a plain glyph), only the per-key optical
     * correction is dropped to 1, since those corrections make any single button's inset glyph-specific
     * and reading one real button's value would make this drift with the user's layout. Re-derived per
     * measure pass rather than frozen as a dp. 12dp at 100% scale, 9dp at 75%, 15dp at 125%. This is a
     * taste number, not a derived
     * constraint - the 22dp rounded corner sweeps back across the glyph's corner, so perpendicular
     * clearance wants roughly one icon inset even though the linear reference sits nearer half that. Kept
     * as one expression in one place so halving it later is a one-line change.
     */
    private static int middleContentSideInset() {
        int cellPx = Math.round(AndroidUtilities.dpf2(buttonSize()));
        int glyphPx = Math.round(AndroidUtilities.dpf2(ICON_GLYPH) * scale());
        return Math.max(0, cellPx - glyphPx) / 2;
    }

    private static void applyIconBox(View view, int cellDp, float scale) {
        int cellPx = Math.round(AndroidUtilities.dpf2(cellDp));
        // Kept in pixel space rather than rounded to whole dp: at the odd steps the scaled glyph
        // lands on a fraction of a dp, and rounding it first would bias the two insets apart and
        // push the icon off center inside its cell.
        int glyphPx = Math.round(AndroidUtilities.dpf2(ICON_GLYPH) * scale);
        int remainingPx = Math.max(0, cellPx - glyphPx);
        int startInset = remainingPx / 2;
        int endInset = remainingPx - startInset;
        if (view instanceof RLottieImageView) {
            // RLottieImageView never sets its own scale type, so it inherits ImageView's FIT_CENTER
            // and the padding alone centers the composition - no setScaleType needed or wanted.
            view.setPadding(startInset, startInset, endInset, endInset);
            return;
        }
        if (!(view instanceof ImageView)) {
            return;
        }
        ImageView icon = (ImageView) view;
        // FIT_CENTER is what gives the padding below its meaning: it makes the drawable fill the box
        // the insets leave, so the box is the glyph's size. Under CENTER the drawable would draw at its
        // intrinsic size and every scale here would silently become a no-op - no crash, nothing logged.
        // Set it here, last, rather than trusting each call site to have chosen the right scale type.
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(startInset, startInset, endInset, endInset);
    }

    private static void applyIconBox(String key, View view) {
        // Two deliberate choices live here. First, the optical correction is expressed as a wider or
        // narrower inset rather than a view scale: these buttons carry a press animator that drives
        // scaleX/scaleY, so a scale set here would be animated away on the first tap. Second, the
        // resulting padding is absolute (setPadding, not setPaddingRelative) even though the slots are
        // laid out RTL. That is correct and must stay: none of these drawables is autoMirrored, so the
        // ink renders the same pixels in RTL, and the per-glyph offset baked into the fork vectors is
        // physical canvas geometry, not reading order. Switching to setPaddingRelative would mirror the
        // inset in RTL and reintroduce exactly the decentring this change removes.
        applyPanelIconBox(view, ComposerButtons.iconScaleForKey(key) * glyphScale());
    }

    // The panel and its slots react to the same layout passes, so they share one settle schedule and start
    // moving on the same frame. Each further change pushes the start back, capped so a slot that keeps
    // resizing cannot hold everything frozen.
    private static long getSettleDelay(long holdStartTime) {
        long held = SystemClock.elapsedRealtime() - holdStartTime;
        return Math.max(0, Math.min(BOUNDS_SETTLE_DELAY, BOUNDS_SETTLE_MAX - held));
    }

    // A context child (a button inside the attach wrapper) is engaged when it is on screen and not faded
    // out. This is the single rule that decides whether the context group is carrying anything: the end
    // slot's collapse, the gap occupancy and - through the collapsed geometry - the painted bubble all
    // route through it, so they cannot disagree. attachLayout is a plain LinearLayout, so a VISIBLE child
    // still measures to full width at alpha 0; in toolbar mode a hidden suggestion button only fades its
    // alpha to 0 and stays VISIBLE (ChatActivityEnterView never sets it GONE on this path), so without this
    // the wrapper would hold full width with nothing to show. Width is deliberately not part of the rule:
    // a child only gets a width once the group is measured, and the group is only measured once it has an
    // engaged child, so gating engagement on width would deadlock the group's first appearance. Non-zero
    // width is enforced where it is live instead - the painter's isBubbleContent check on the wrapper - and
    // the two cannot disagree, because an engaged group is always measured to a real width and a released
    // one always collapses to zero.
    private static boolean isContextChildEngaged(View child) {
        return child.getVisibility() == View.VISIBLE && child.getAlpha() > 0f;
    }

    private static boolean hasEngagedChild(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (isContextChildEngaged(group.getChildAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static final class ControlsLayout extends FrameLayout {
        private CollapsingLinearLayout startSlot;
        private HorizontalScrollView middleScrollView;
        private LinearLayout middleContent;
        private CollapsingLinearLayout endSlot;
        // The row is drawn as up to four separate glass bubbles instead of one full-width capsule, one
        // per child group that can collapse or slide independently. Left to right in LTR: the leading
        // zone, the scrolling middle group, the attach context group, and the configurable trailing
        // group. Each has its own drawable (see attachGlass) so its own backdrop sample and render node
        // stay correct; a single drawable re-bounded across the rects would force a chat-wide re-capture
        // every frame.
        private static final int BUBBLE_LEADING = 0;
        private static final int BUBBLE_MIDDLE = 1;
        private static final int BUBBLE_CONTEXT = 2;
        private static final int BUBBLE_TRAILING = 3;
        private static final int BUBBLE_COUNT = 4;
        // Matches the inline-rest fold, so the droplet is gone as the pill settles.
        private static final long DROPLET_DURATION = 450;
        private static final float DROPLET_PINCH = 0.45f;
        private BlurredBackgroundDrawable[] bubbles;
        // The one trailing sub-group the drawGlass split still needs to tell apart inside endSlot: the
        // attach context group (endSlot child 0, its own chat-type lifecycle). Everything else in endSlot
        // is the single trailing bubble.
        private View trailingContextGroup;
        // Reused across dispatchDraw so no frame allocates: each bubble's content span in this view's
        // coordinates, whether it has anything to draw, and the alpha its group is currently faded to.
        private final float[] bubbleLeft = new float[BUBBLE_COUNT];
        private final float[] bubbleRight = new float[BUBBLE_COUNT];
        private final boolean[] bubbleOccupied = new boolean[BUBBLE_COUNT];
        private final int[] bubbleAlpha = new int[BUBBLE_COUNT];
        // NagramX (#glass-lone-circle): cells each zone's bubble wraps this pass. A bubble wrapping one
        // cell paints as a circle (see drawGlass); the count, not the painted width, decides it, because
        // the width rides the slot slides and the bounds animator and would pop into a circle mid-slide.
        private final int[] bubbleCells = new int[BUBBLE_COUNT];
        // NagramX (#glass-lone-circle): that one cell itself, when a zone holds exactly one. The loop
        // in computeBubbleSpans nulls every role but trailing whenever the count leaves one, and
        // trailing is only ever read behind the same count its span walk wrote it under, so a stale
        // slot is never read. The circle centres on the cell, not the span: the attach wrapper
        // measures wider than its lone engaged button while a faded suggestion keeps its width.
        private final View[] loneCell = new View[BUBBLE_COUNT];
        // NagramX (#glass-lone-circle): the cell width off the same construction-time scale sample as the
        // geometry below, matching what addConfigurable laid every button out at. A lone child laid out at
        // a fixed width no larger than this is a cell; the wrap-content bot menu and anything wider keep
        // the pill.
        private final int cellPx;
        // Whether each bubble actually painted last frame. A bubble that stops painting has its bounds
        // cleared to empty once, so getVisiblePositions stops reporting a phantom backdrop region for it;
        // this fires only on the occupied->hidden edge, never per frame, so it does not churn render nodes.
        private final boolean[] bubbleDrawn = new boolean[BUBBLE_COUNT];
        // The clamped capsule radius last written to each bubble drawable. drawGlass recomputes the
        // clamp every frame off live width but only calls setRadius when it actually moves, because
        // setRadius rebuilds the drawable's props and (with blur off) re-keys and re-bakes its
        // nine-patch. Initialised to -1 in attachGlass so the first painted frame always writes.
        private final float[] bubbleRadius = new float[BUBBLE_COUNT];
        // One scale sample for this view's whole life, captured in the constructor. Everything the row
        // draws with - its height, the button-box inset, both glass draw insets and the capsule radius -
        // comes off these three dp values, so a relayout can never pair a freshly read height with a
        // radius that was sampled at some other scale (which is how the end caps would stop being
        // semicircular in the deferred-rebuild window after the slider moves). The scale only moves
        // while the settings screen is open and closing it rebuilds every chat, so a live view keeps
        // one coherent geometry and a rebuilt view takes the new scale. It stays consistent with the
        // layout params ChatActivityEnterView sizes this toolbar with because composerToolbarHeight()
        // there reads height() in the same synchronous construction - no config write can land between
        // the two, and a rebuild replaces both together. These hold dp with only the user's scale
        // setting frozen in. onMeasure re-converts geometryHeightDp to px every pass, so the row height
        // still tracks a density change; the button-box padding, glass padding and draw inset are
        // converted to px once (in the constructor and attachGlass), exactly as they were before this
        // change - a config change that alters density recreates the view, which is what re-reads them.
        private final int geometryHeightDp;
        private final int geometryInsetDp;
        private final int geometryDrawInsetDp;
        // The exact pixel geometry handed to every bubble drawable at attachGlass, derived from the
        // snapshot above. glassPaddingPx is the value passed to each drawable's setPadding; drawGlass
        // cancels it on every bubble edge by the same field - never a fresh glassInset() - so the two
        // cannot drift, and each bubble's painted rounded rect reaches its group's content edges. The
        // gap between neighbours is not painted: it is reserved in layout (see gapPx) so the glass
        // separates the button cells rather than being inset inside them.
        private int glassPaddingPx;
        private int glassDrawInsetPx;
        // The full requested capsule radius - half the unpacked bubble height, off the construction
        // snapshot - computed once in attachGlass. drawGlass clamps this down against each bubble's own
        // painted box every frame; it is never handed to a drawable raw.
        private float requestedRadiusPx;
        // The gap reserved in layout between two adjacent occupied bubbles, and the same value the
        // bubbles then read back when they derive their rects from the laid-out geometry. Scale-derived
        // off the construction snapshot (2 x the box inset, 8dp at 100%). Fixed for the view's life like
        // the rest of the geometry, so it is captured in the constructor rather than at attachGlass.
        private final int gapPx;
        // Which of the four semantic groups actually occupy space this pass. The two trailing groups
        // (context and trailing) are read off visibility/alpha before the end slot is measured, since they
        // feed its gap margin; the leading and middle groups come from measured width afterwards. The
        // end-slot gap margin and the middle viewport subtraction both key off these, so a gap is only
        // reserved between two groups that are both present. Reused every measure, no allocation.
        private boolean occLeading;
        private boolean occMiddle;
        private boolean occContext;
        private boolean occTrailing;
        // The leading|middle gap, carried from onMeasure to onLayout so the middle group is shifted off
        // the leading zone by the same amount its viewport was shrunk. Nothing else needs carrying: the
        // middle|end gap and the one remaining end-slot gap (context group to trailing) fall out of the
        // viewport subtraction and the child margin respectively.
        private int leadingMiddleGapPx;
        private ValueAnimator boundsAnimator;
        private int measuredPanelWidth = -1;
        private boolean laidOut;
        private boolean availableWidthChanged;
        private boolean holdingBounds;
        private long holdStartTime;
        private int previousRightAnchoredLeft;
        private boolean hasRightAnchoredLeft;
        private View rightAnchoredSlot;
        private float pendingEndShift;
        private boolean resumingMidAnimation;
        private final Runnable boundsAnimationStarter = this::startBoundsAnimation;
        private final int sidePaddingPx;
        private final int verticalPaddingPx;
        private boolean restCompact;
        private long dropletStart;
        private float dropletLeft;
        private float dropletRight;
        private int dropletAlpha;
        private int sendReservePx;
        private int restLeading = -1;
        private int restTrailing = -1;
        private int restWidth = -1;
        private Runnable restGeometryListener;
        // A slot occupancy change should clear on the very next traversal: checkSlotOccupancy(true) runs
        // before measuring slots and force-layouts the changed slot (and the middle strip host when needed),
        // so this listener only needs to request one fresh pass when a mismatch is observed at pre-draw time.
        private final ViewTreeObserver.OnPreDrawListener occupancyPreDrawListener = () -> {
            if (checkSlotOccupancy(false)) {
                requestLayout();
            }
            return true;
        };

        ControlsLayout(Context context) {
            super(context);
            setClipChildren(true);
            setClipToPadding(true);
            // Snapshot the scale-derived geometry once, before anything measures, lays out or draws, so
            // every later read is the same sample (see the field comment).
            geometryHeightDp = height();
            geometryInsetDp = glassInset();
            geometryDrawInsetDp = glassDrawInset();
            // NagramX (#glass-lone-circle): same construction-time scale sample as the geometry above; see
            // the cellPx field comment.
            cellPx = AndroidUtilities.dp(buttonSize());
            // The gap between two neighbouring bubbles, reserved in layout so the glass separates the
            // button cells. 2 x the box inset (8dp at 100%), scale-derived and frozen with the rest of
            // the geometry. MD3 has no bubbles to separate, only groups, and keeps that at a flat 8dp.
            gapPx = InterfaceStyleController.applyComposer() ? AndroidUtilities.dp(MD3_GROUP_GAP) : 2 * AndroidUtilities.dp(geometryInsetDp);
            // Vertical inset only. The row keeps glassInset() (4dp at 100%) of top and bottom padding so
            // its slots still measure to the unpacked cell (see the height comment above) and each bubble
            // sits lifted off the row edge vertically. The horizontal component is deliberately zero:
            // while it was glassInset() too, a bubble's flush edge - painted to the row edge (literal 0 /
            // getWidth() in drawGlass) - picked up that whole glassInset() of clearance, but its inner
            // edge, set from live content geometry, sat right on the content, so a single-flush leading or
            // trailing bubble came out lopsided (wider gap on the flush side). That was F3/F4. At zero,
            // contentLeft is 0 and contentRight is getWidth(), so the content edges coincide with the
            // painted flush edges and painted-edge-to-content is the same on every edge, flush or inner.
            // The trade: the outermost glyph now sits glassInset() nearer the row edge and no longer lines
            // up with the input pill's hint start (the bubble edge still does). Accepted.
            // MD3: the enter view already holds the row 7dp off the screen edge; one more dp puts the first and
            // last targets on the composer's 8dp keyline.
            int sidePadding = InterfaceStyleController.applyComposer() ? AndroidUtilities.dp(1) : 0;
            // MD3 cells are as tall as the row, so there is no vertical inset to keep.
            int verticalPadding = InterfaceStyleController.applyComposer() ? 0 : AndroidUtilities.dp(geometryInsetDp);
            sidePaddingPx = sidePadding;
            verticalPaddingPx = verticalPadding;
            setPaddingRelative(sidePadding, verticalPadding, sidePadding, verticalPadding);
        }

        int rowHeightDp() {
            return geometryHeightDp;
        }

        void setRestCompact(boolean compact) {
            if (restCompact == compact) {
                return;
            }
            restCompact = compact;
            // NagramX (#glass-composer-fold): the middle capsule folds away as a droplet rather than vanishing.
            dropletStart = compact && bubbles != null && bubbleDrawn[BUBBLE_MIDDLE] ? SystemClock.uptimeMillis() : 0;
            if (dropletStart != 0) {
                dropletLeft = bubbleLeft[BUBBLE_MIDDLE];
                dropletRight = bubbleRight[BUBBLE_MIDDLE];
                dropletAlpha = bubbleAlpha[BUBBLE_MIDDLE];
            }
            middleScrollView.setVisibility(compact ? INVISIBLE : VISIBLE);
            requestLayout();
        }

        // The reserve rides the end padding, which onMeasure and onLayout already take the slots' room from.
        void setSendReserve(int px) {
            px = Math.max(0, px);
            // NagramX (#glass-composer-fold): the reserve ends at the send circle's drawn edge, and under Glass that
            // circle is a disc of its own, so the trailing capsule painted to the reserve touched it. One capsule
            // gap more keeps the same spacing every other pair of capsules has.
            if (px > 0 && !InterfaceStyleController.applyComposer()) {
                px += gapPx;
            }
            if (sendReservePx == px) {
                return;
            }
            sendReservePx = px;
            setPaddingRelative(sidePaddingPx, verticalPaddingPx, sidePaddingPx + px, verticalPaddingPx);
        }

        void setSlots(CollapsingLinearLayout startSlot, HorizontalScrollView middleScrollView, LinearLayout middleContent, CollapsingLinearLayout endSlot) {
            this.startSlot = startSlot;
            this.middleScrollView = middleScrollView;
            this.middleContent = middleContent;
            this.endSlot = endSlot;

            addView(startSlot, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, geometryHeightDp, Gravity.LEFT));
            addView(middleScrollView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, geometryHeightDp, Gravity.LEFT));
            addView(endSlot, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, geometryHeightDp, Gravity.LEFT));
        }

        void attachGlass(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
            glassPaddingPx = AndroidUtilities.dp(geometryInsetDp);
            glassDrawInsetPx = AndroidUtilities.dp(geometryDrawInsetDp);
            // One drawable per bubble, each created with this view so viewPositionWatcher.subscribe wires
            // it to the shared backdrop; multiple subscriptions of the same view are supported, and each
            // drawable then samples the region under its own bounds. The capsule radius handed to each
            // drawable is clamped against that bubble's own painted box every frame in drawGlass, not set
            // for good here: only the blur+glass RenderNode path clips to an outline (which clamps the
            // radius to half its shorter painted side), so with that path off nothing else would reduce an
            // over-large radius and a short bubble would paint an impossible corner. requestedRadiusPx is
            // the unclamped starting value, off the construction snapshot so it stays the same scale as the
            // height it is drawn against. Padding and drawInset are stored in px here for drawGlass.
            requestedRadiusPx = AndroidUtilities.dp((geometryHeightDp - geometryInsetDp) / 2f);
            bubbles = new BlurredBackgroundDrawable[BUBBLE_COUNT];
            for (int i = 0; i < BUBBLE_COUNT; i++) {
                BlurredBackgroundDrawable drawable = factory.create(this, colorProvider);
                drawable.setRadius(requestedRadiusPx);
                drawable.setPadding(glassPaddingPx);
                bubbles[i] = drawable;
                // -1 so the first drawGlass pass always writes the clamped radius (see bubbleRadius).
                bubbleRadius[i] = -1f;
            }
            invalidate();
        }

        void clearGlass() {
            bubbles = null;
            invalidate();
        }

        void updateColors() {
            if (bubbles == null) {
                return;
            }
            for (BlurredBackgroundDrawable drawable : bubbles) {
                if (drawable != null) {
                    drawable.updateColors();
                }
            }
            invalidate();
        }

        void reprimeGlass() {
            if (bubbles == null) {
                return;
            }
            for (BlurredBackgroundDrawable drawable : bubbles) {
                if (drawable instanceof BlurredBackgroundDrawableRenderNode) {
                    ((BlurredBackgroundDrawableRenderNode) drawable).invalidateDisplayList();
                }
            }
            invalidate();
        }

        void setTrailingContextGroup(View view) {
            trailingContextGroup = view;
        }

        void setPanelVisible(boolean visible) {
            int visibility = visible ? VISIBLE : GONE;
            if (getVisibility() != visibility) {
                if (!visible) {
                    cancelBoundsAnimation();
                    resetBounds();
                    SlidingLinearLayout.cancelSlidesIn(this);
                }
                setVisibility(visibility);
                // Coming back from a record or review panel, the buttons fade in alongside the field tone easing back
                // rather than popping in on the frame the panel lets go of the row.
                animate().cancel();
                if (visible) {
                    setAlpha(0f);
                    animate().alpha(1f).setDuration(com.dazewell.gram.composer.ComposerMd3Surface.ROW_RETURN_DURATION)
                            .setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
                } else {
                    setAlpha(1f);
                }
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
            int height = AndroidUtilities.dp(geometryHeightDp);
            int contentHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
            int heightSpec = MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY);
            int unboundedWidthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

            // Work out which trailing groups are present and reserve the end-slot gaps as child margins
            // before the slot is measured, so its measured width already carries them. Visibility/alpha
            // only, no measure needed, and every transition that flips one of these also requests a
            // layout (a visibility change, or the end slot crossing its alpha-0.5 occupancy line), so the
            // margins are recomputed on the same pass that lays the buttons out at their new positions.
            computeEndSlotOccupancy();
            applyEndSlotGapMargins();

            checkSlotOccupancy(true);
            startSlot.measure(unboundedWidthSpec, heightSpec);
            endSlot.measure(unboundedWidthSpec, heightSpec);
            // One icon inset of horizontal padding on the scrolling middle content so its first and last
            // icons clear the bubble's rounded corner (F2) instead of running almost to the glass edge.
            // Set here, guarded like the clip toggle below so it only writes when it actually changes, and
            // re-derived each pass rather than frozen. Measured into middleContent's width, so it flows
            // through middleViewportWidth and the reserved gaps stay correct.
            int middleInset = InterfaceStyleController.applyComposer() ? 0 : middleContentSideInset();
            if (middleContent.getPaddingLeft() != middleInset || middleContent.getPaddingRight() != middleInset) {
                middleContent.setPadding(middleInset, 0, middleInset, 0);
            }
            middleContent.measure(unboundedWidthSpec, heightSpec);

            int startWidth = startSlot.getMeasuredWidth();
            int endWidth = endSlot.getMeasuredWidth();
            int middleWidth = middleContent.getMeasuredWidth();
            int horizontalPadding = getPaddingLeft() + getPaddingRight();
            // The leading and middle groups occupy space when their measured content has width. Middle now
            // carries its own horizontal padding, so subtract it: an empty middle measures to just the
            // padding, and counting that as occupied would draw a blank pill and reserve gaps around it.
            int middleButtonsWidth = Math.max(0, middleWidth - middleContent.getPaddingLeft() - middleContent.getPaddingRight());
            occLeading = startWidth > 0;
            occMiddle = middleButtonsWidth > 0 && !restCompact;
            // Always take the full width the row offers instead of shrink-wrapping the content, so the
            // leading and trailing bubbles can reach the row edges and line up with the main input pill
            // above, and the trailing zone stays anchored right at every button count.
            int panelWidth = availableWidth;
            // The two outer gaps - leading|middle and middle|end - come out of the middle group's
            // viewport so the total still fits the band. leadingMiddleGapPx is carried to onLayout to
            // push the middle group off the leading zone by the same amount; the middle|end gap needs no
            // shift because the end slot is right-anchored, so shrinking the viewport leaves the gap
            // between the middle group's right edge and the end slot.
            boolean endHasContent = occContext || occTrailing;
            leadingMiddleGapPx = (occLeading && occMiddle) ? gapPx : 0;
            int middleEndGapPx = (occMiddle && endHasContent) ? gapPx : 0;
            int reservedGaps = leadingMiddleGapPx + middleEndGapPx;
            // An empty middle reserves no viewport, so its padding never becomes a stray blank pill; an
            // occupied one takes content plus padding into its window (capped by the width the row has left).
            int desiredMiddleWidth = occMiddle ? middleWidth : 0;
            int middleViewportWidth = Math.min(desiredMiddleWidth, Math.max(0, panelWidth - horizontalPadding - startWidth - endWidth - reservedGaps));

            middleScrollView.measure(MeasureSpec.makeMeasureSpec(middleViewportWidth, MeasureSpec.EXACTLY), heightSpec);
            // A control fading out inside the middle group sits past the viewport edge, so only clip once the
            // group actually scrolls - that is the case the clip exists for.
            boolean clipMiddle = middleWidth > middleViewportWidth;
            if (middleScrollView.getClipChildren() != clipMiddle) {
                middleScrollView.setClipChildren(clipMiddle);
            }
            if (laidOut && measuredPanelWidth != panelWidth) {
                // The panel width only moves now when the row itself is resized (rotation, multi-window).
                // The layout pass settles the buttons on the new width, so snap the capsule to it rather
                // than easing a stale edge out behind buttons that have already arrived.
                availableWidthChanged = true;
            }
            measuredPanelWidth = panelWidth;
            setMeasuredDimension(panelWidth, height);
        }

        private boolean checkSlotOccupancy(boolean forceLayout) {
            boolean changed = false;
            if (startSlot != null && startSlot.occupancyChanged()) {
                changed = true;
                if (forceLayout) {
                    startSlot.forceLayout();
                }
            }
            if (endSlot != null && endSlot.occupancyChanged()) {
                changed = true;
                if (forceLayout) {
                    endSlot.forceLayout();
                }
            }
            if (middleContent != null) {
                for (int i = 0; i < middleContent.getChildCount(); i++) {
                    View child = middleContent.getChildAt(i);
                    if (!(child instanceof CollapsingLinearLayout)) {
                        continue;
                    }
                    CollapsingLinearLayout slot = (CollapsingLinearLayout) child;
                    if (!slot.occupancyChanged()) {
                        continue;
                    }
                    changed = true;
                    if (forceLayout) {
                        slot.forceLayout();
                        middleContent.forceLayout();
                        if (middleScrollView != null) {
                            middleScrollView.forceLayout();
                        }
                    }
                }
            }
            return changed;
        }

        // Which trailing groups occupy space this pass. Read straight off visibility/alpha so it can run
        // before the end slot is measured and feed its gap margin. The trailing group is every end-slot
        // button that is not the context group - each a single button whose slot occupancy line (VISIBLE
        // and alpha >= 0.5) matches what it lays out - so it is occupied if any one of them is. Context is
        // different: attachLayout is a plain LinearLayout that still
        // measures a VISIBLE child to full width at alpha 0 (a suggestion button that finished fading out
        // stays VISIBLE in toolbar mode), so it is occupied only while it holds an engaged child - the
        // same rule the end slot collapses on (hasEngagedChild) - not merely because the wrapper is on
        // screen. A wrapper full of faded-out buttons collapses to zero width, so reserving a gap after it
        // would strand blank glass. The wrapper's own alpha > 0 tracks the painter through its chat-type
        // fade. The leading and middle groups are not decided here - they come from measured width once the
        // slots have been measured.
        private void computeEndSlotOccupancy() {
            occContext = trailingContextGroup != null
                    && trailingContextGroup.getVisibility() == VISIBLE
                    && trailingContextGroup.getAlpha() > 0f
                    && trailingContextGroup instanceof ViewGroup
                    && hasEngagedChild((ViewGroup) trailingContextGroup);
            occTrailing = false;
            if (endSlot != null) {
                for (int i = 0; i < endSlot.getChildCount(); i++) {
                    View child = endSlot.getChildAt(i);
                    if (child == trailingContextGroup) {
                        continue;
                    }
                    if (endSlot.isOccupied(child)) {
                        occTrailing = true;
                        break;
                    }
                }
            }
        }

        // Reserve the one end-slot gap - context|trailing - as a margin on the context group, which owns
        // it, so SlidingLinearLayout spaces it out for free in both measure and layout. The gap rides
        // context's array-trailing margin; the walk in SlidingLinearLayout advances by rightMargin in LTR
        // and leftMargin in RTL, so the side it lives on flips with the layout direction. It is keyed on
        // occTrailing (any non-context end-slot button) so the common context-group-plus-attach row still
        // gets its gap. Suppressed unless both groups are present, which keeps the first occupied end-slot
        // group flush against the middle|end gap rather than doubling it, and leaves an empty context
        // reserving nothing.
        private void applyEndSlotGapMargins() {
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            // MD3 has no bubble per group, so the attach group and the trailing buttons read as one run.
            int contextGap = (occContext && occTrailing && !InterfaceStyleController.applyComposer()) ? gapPx : 0;
            // Context's trailing-in-array side: rightMargin in LTR, leftMargin in RTL.
            setBubbleMargins(trailingContextGroup, rtl ? contextGap : 0, rtl ? 0 : contextGap);
        }

        private static void setBubbleMargins(View view, int leftMargin, int rightMargin) {
            if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
                return;
            }
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            if (lp.leftMargin != leftMargin || lp.rightMargin != rightMargin) {
                lp.leftMargin = leftMargin;
                lp.rightMargin = rightMargin;
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int startWidth = startSlot.getMeasuredWidth();
            int middleWidth = middleScrollView.getMeasuredWidth();
            int endWidth = endSlot.getMeasuredWidth();
            int contentLeft = getPaddingLeft();
            int contentRight = getWidth() - getPaddingRight();
            int contentTop = getPaddingTop();
            int contentBottom = getHeight() - getPaddingBottom();
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
                // Leading zone (startSlot) sits at the right edge, trailing (endSlot) at the left.
                // Anchor the scrolling middle group to the leading zone so the slack on a sparse row
                // falls between the middle group and the trailing zone, the same side it does in LTR.
                // The leading|middle gap shifts the middle group left off the leading zone.
                int middleRight = contentRight - startWidth - leadingMiddleGapPx;
                endSlot.layout(contentLeft, contentTop, contentLeft + endWidth, contentBottom);
                middleScrollView.layout(middleRight - middleWidth, contentTop, middleRight, contentBottom);
                startSlot.layout(contentRight - startWidth, contentTop, contentRight, contentBottom);
            } else {
                int middleLeft = contentLeft + startWidth + leadingMiddleGapPx;
                startSlot.layout(contentLeft, contentTop, contentLeft + startWidth, contentBottom);
                middleScrollView.layout(middleLeft, contentTop, middleLeft + middleWidth, contentBottom);
                endSlot.layout(contentRight - endWidth, contentTop, contentRight, contentBottom);
            }
            int leading = startWidth > 0 ? getPaddingStart() + startWidth : 0;
            int trailing = getPaddingEnd() + endWidth;
            // The fit check also reads the row's width, which rotation and multi-window move on their own.
            if (leading != restLeading || trailing != restTrailing || getWidth() != restWidth) {
                restLeading = leading;
                restTrailing = trailing;
                restWidth = getWidth();
                if (restGeometryListener != null) {
                    // Posted: the listener moves the field's margins, which must not request layout mid-pass.
                    post(restGeometryListener);
                }
            }
            // Whichever slot is pinned to the trailing edge is laid out against its new anchor the
            // instant a control claims or frees a slot, so it jumps there in a single frame. Hand its
            // catch-up to the bounds animator below so it eases back over the settle window instead.
            View trailingSlot = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? startSlot : endSlot;
            if (trailingSlot != rightAnchoredSlot) {
                if (rightAnchoredSlot != null) {
                    rightAnchoredSlot.setTranslationX(0);
                }
                rightAnchoredSlot = trailingSlot;
                hasRightAnchoredLeft = false;
            }
            int anchoredLeft = trailingSlot.getLeft();
            int endShift = hasRightAnchoredLeft ? previousRightAnchoredLeft - anchoredLeft : 0;
            previousRightAnchoredLeft = anchoredLeft;
            hasRightAnchoredLeft = true;
            if (!laidOut) {
                laidOut = true;
            } else if (availableWidthChanged) {
                // Rotation or multi-window: the row and the anchored slot are already at their new
                // positions, so settle the capsule and the slot on the new bounds without a slide.
                availableWidthChanged = false;
                snapBounds();
            } else if (endShift != 0) {
                // The trailing group jumped to a new anchor while the panel width held (a control
                // claimed or freed its slot). Ease it back so it and the buttons beside it settle
                // together instead of popping.
                holdTrailingSlide(endShift);
                AndroidUtilities.runOnUIThread(boundsAnimationStarter, resumingMidAnimation ? 0 : getRemainingSettleDelay());
            } else if (!holdingBounds) {
                trailingSlot.setTranslationX(0);
            }
            invalidate();
        }

        // This row paints content derived from its descendants' geometry (see drawGlass), but a
        // descendant invalidation only marks ancestors dirty without re-recording them - the child's own
        // render node refreshes while this view replays its last recorded bubble span. Invalidate
        // explicitly so the painter is never staler than what it is painting.
        @Override
        public void onDescendantInvalidated(View child, View target) {
            super.onDescendantInvalidated(child, target);
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            drawGlass(canvas);
            super.dispatchDraw(canvas);
            drawLeadingSeam(canvas);
        }

        private final Paint seamPaint = new Paint();

        // NagramX (#interface-style): MD3 marks where the fixed leading group ends and the clipped scrolling
        // strip begins with a hairline centred in the gap between them, so the cut reads as a seam. The trailing
        // end gets one too while the strip overflows, the only time that end is a cut rather than open space.
        private void drawLeadingSeam(Canvas canvas) {
            if (!InterfaceStyleController.applyComposer() || middleScrollView.getWidth() <= 0) {
                return;
            }
            final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            final float width = Math.max(1, AndroidUtilities.dp(0.66f));
            final float half = AndroidUtilities.dp(MD3_SEAM_HEIGHT) / 2f;
            final float centre = getHeight() / 2f;
            final int surface = Theme.getColor(Theme.key_chat_messagePanelBackground);
            final int color = InterfaceStyleController.panelDividerPaint(surface, null).getColor();
            if (leadingMiddleGapPx > 0 && startSlot.getWidth() > 0) {
                final float x = rtl
                        ? (startSlot.getLeft() + middleScrollView.getRight()) / 2f
                        : (startSlot.getRight() + middleScrollView.getLeft()) / 2f;
                seamPaint.setColor(color);
                seamPaint.setAlpha(Math.round(seamPaint.getAlpha() * Math.min(startSlot.getAlpha(), middleScrollView.getAlpha())));
                canvas.drawRect(x - width / 2f, centre - half, x + width / 2f, centre + half, seamPaint);
            }
            final View content = middleScrollView.getChildAt(0);
            if (content != null && content.getWidth() > middleScrollView.getWidth() && endSlot.getWidth() > 0) {
                final float x = rtl
                        ? middleScrollView.getLeft() - gapPx / 2f
                        : middleScrollView.getRight() + gapPx / 2f;
                seamPaint.setColor(color);
                seamPaint.setAlpha(Math.round(seamPaint.getAlpha() * Math.min(endSlot.getAlpha(), middleScrollView.getAlpha())));
                canvas.drawRect(x - width / 2f, centre - half, x + width / 2f, centre + half, seamPaint);
            }
        }

        private void drawGlass(Canvas canvas) {
            if (bubbles == null || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            // Same global vertical guard the single capsule used: below this the painted band would
            // invert once each drawable insets its padding. Cheap to test once for the whole row.
            if (getHeight() <= (glassDrawInsetPx + glassPaddingPx) * 2) {
                return;
            }
            computeBubbleSpans();
            int top = glassDrawInsetPx;
            int bottom = getHeight() - glassDrawInsetPx;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            // The leading group owns the row's leading edge and the trailing group its trailing edge; those
            // two outer edges repeat the capsule's flush arithmetic so they still line up with the input pill
            // above. Every other edge sits at its group's own content edge, and the real gap reserved in
            // layout separates it from its neighbour. If the trailing zone is empty the row loses its
            // trailing flush (accepted), which trailingEdgeRole reports as -1; likewise a row with only
            // trailing content leaves the leading edge unflushed rather than stretching a trailing pill
            // across the empty leading side.
            int leadingEdgeRole = leadingEdgeRole();
            int trailingEdgeRole = trailingEdgeRole();
            for (int role = 0; role < BUBBLE_COUNT; role++) {
                if (!bubbleOccupied[role]) {
                    if (role == BUBBLE_MIDDLE && dropletStart != 0 && drawDroplet(canvas, top, bottom)) {
                        continue;
                    }
                    hideBubble(role);
                    continue;
                }
                boolean flushLeft = rtl ? role == trailingEdgeRole : role == leadingEdgeRole;
                boolean flushRight = rtl ? role == leadingEdgeRole : role == trailingEdgeRole;
                // Outer flush edge painted to the row edge; every inner edge painted to the group's own
                // content edge. Both outset the bounds by glassPaddingPx, which the drawable's own
                // padding insets back, so the painted rounded rect reaches exactly the target edge and
                // the glass covers the whole button cell instead of stopping short inside it. Neighbours
                // are held apart by the layout gap, not by leaving this padding on the inner edges.
                // The trailing flush edge stops at the send reserve, so the send column keeps its own room
                // beside the capsule rather than sitting inside it. The lone-cell circle below is the
                // one deliberate exception: it centres on its cell, so a flush zone's circle sits
                // inset of the row edge instead of reaching it.
                float paintedLeft = flushLeft ? (rtl ? sendReservePx : 0f) : bubbleLeft[role];
                float paintedRight = flushRight ? (rtl ? getWidth() : getWidth() - sendReservePx) : bubbleRight[role];
                int bubbleTop = top;
                int bubbleBottom = bottom;
                // NagramX (#glass-lone-circle): a bubble wrapping exactly one button cell paints as a
                // circle. The cell beside the band height reads as an oval at every scale (36x32dp at
                // 75%, 36x44dp at 100%), and only a lone bubble shows it - two cells or more is a wide
                // pill and looks right. The circle centres on the lone cell itself, not the zone's span:
                // the attach wrapper measures wider than its lone engaged button while a faded suggestion
                // keeps its width, and the span would centre the circle on that dead space. Only a real
                // cell circles - a child laid out at a fixed width no larger than the cell; the
                // wrap-content bot menu keeps its pill. The vertical bounds outset by the glass padding
                // the drawable's own padding insets back, so the painted circle is the full d and not d
                // less two paddings (drawDroplet does the same). The count decides, never the painted
                // width: slides sweep the width past any threshold and would snap a pill into a circle
                // mid-move. At 75% the circle is the band height, so the folded pill beside it keeps the
                // row reading as one height; at 100% the lone circle sits shorter than the band - taken,
                // a 36dp circle cannot reach a 44dp band.
                View lone = bubbleCells[role] == 1 ? loneCell[role] : null;
                if (lone != null && isCellWidth(lone)) {
                    // One rounded diameter feeds both axes: rounding d and each vertical edge
                    // separately could leave a 1px stadium where the radius clamps to the
                    // shorter painted side.
                    int d = Math.round(Math.min(lone.getWidth(), (bottom - top) - 2f * glassPaddingPx));
                    if (d > 0) {
                        // The middle group's cell sits in scrolled content - its left is its place
                        // in the strip, so map it out by the scroll the way the canvas does, or a
                        // dragged strip displaces its circle (RTL pins a non-zero scroll on overflow).
                        float cx = descendantLeft(lone) + lone.getWidth() / 2f
                                - (role == BUBBLE_MIDDLE ? middleScrollView.getScrollX() : 0);
                        float cy = (top + bottom) / 2f;
                        paintedLeft = cx - d / 2f;
                        paintedRight = cx + d / 2f;
                        bubbleTop = Math.round(cy - d / 2f) - glassPaddingPx;
                        bubbleBottom = Math.round(cy + d / 2f) + glassPaddingPx;
                    }
                }
                int left = Math.round(paintedLeft) - glassPaddingPx;
                int right = Math.round(paintedRight) + glassPaddingPx;
                if (right - left <= glassPaddingPx * 2) {
                    // Painted width would be zero or negative once padding is applied. Skip so a collapsed
                    // bubble never paints a sliver; not drawing also removes its side of the adjacent gap.
                    hideBubble(role);
                    continue;
                }
                BlurredBackgroundDrawable drawable = bubbles[role];
                // Clamp the requested radius down to half the bubble's own painted box before drawing it.
                // The painted box is the setBounds rect inset by glassPaddingPx on every side - the same
                // boundsWithPadding the RenderNode outline measures against on the blur+glass path
                // (BlurredBackgroundDrawable.getOutline), so glass-off converges on that outline's shape
                // instead of over-rounding. The blur+glass outline itself is unchanged - it already clamps
                // to min(w,h)/2 on its own; in the width-bound case the clamp additionally pulls the
                // liquid-glass shader radius down to match that outline, where LiquidGlassEffect otherwise
                // held a height-only value.
                // min(w,h)/2f, not h/2: a single-button bubble can be narrower than it is tall at a
                // reachable scale/spacing, and clamping only the height would round the same impossible
                // corner into the width. Recomputed every frame because widths move with the slot slides
                // and the bounds animator; written only when it changes so an idle bubble re-bakes nothing.
                float paintedW = (right - left) - 2f * glassPaddingPx;
                float paintedH = (bubbleBottom - bubbleTop) - 2f * glassPaddingPx;
                float effectiveRadius = Math.min(requestedRadiusPx, Math.min(paintedW, paintedH) / 2f);
                if (bubbleRadius[role] != effectiveRadius) {
                    drawable.setRadius(effectiveRadius);
                    bubbleRadius[role] = effectiveRadius;
                }
                // setAlpha unconditionally sets renderNodeInvalidated (BlurredBackgroundDrawableRenderNode),
                // forcing a display-list re-record even when the value is unchanged. Only write it when it
                // actually moves, so an idle bubble at full opacity re-records nothing frame to frame; the
                // ones that are fading still re-record because their alpha is genuinely changing.
                if (drawable.getAlpha() != bubbleAlpha[role]) {
                    drawable.setAlpha(bubbleAlpha[role]);
                }
                drawable.setBounds(left, bubbleTop, right, bubbleBottom);
                drawable.draw(canvas);
                bubbleDrawn[role] = true;
            }
        }

        // NagramX (#glass-composer-fold): the folded-away middle capsule pinches to a round droplet at its centre,
        // then shrinks to nothing, over the fold's duration. False once it is gone.
        private boolean drawDroplet(Canvas canvas, int top, int bottom) {
            float t = (SystemClock.uptimeMillis() - dropletStart) / (float) DROPLET_DURATION;
            if (t >= 1f || bubbles == null) {
                dropletStart = 0;
                return false;
            }
            float e = CubicBezierInterpolator.EASE_OUT.getInterpolation(Math.max(0f, t));
            float height = bottom - top - 2f * glassPaddingPx;
            float width = Math.max(0f, dropletRight - dropletLeft);
            float shrink = 0f;
            if (e < DROPLET_PINCH) {
                width = width + (Math.min(width, height) - width) * (e / DROPLET_PINCH);
            } else {
                shrink = (e - DROPLET_PINCH) / (1f - DROPLET_PINCH);
                width = Math.min(width, height) * (1f - shrink);
            }
            float paintedH = height * (1f - shrink);
            if (width < 1f || paintedH < 1f) {
                dropletStart = 0;
                return false;
            }
            float cx = (dropletLeft + dropletRight) / 2f;
            float cy = (top + bottom) / 2f;
            BlurredBackgroundDrawable drawable = bubbles[BUBBLE_MIDDLE];
            float radius = Math.min(requestedRadiusPx, Math.min(width, paintedH) / 2f);
            if (bubbleRadius[BUBBLE_MIDDLE] != radius) {
                drawable.setRadius(radius);
                bubbleRadius[BUBBLE_MIDDLE] = radius;
            }
            int alpha = Math.round(dropletAlpha * (1f - shrink));
            if (drawable.getAlpha() != alpha) {
                drawable.setAlpha(alpha);
            }
            drawable.setBounds(Math.round(cx - width / 2f) - glassPaddingPx, Math.round(cy - paintedH / 2f) - glassPaddingPx,
                    Math.round(cx + width / 2f) + glassPaddingPx, Math.round(cy + paintedH / 2f) + glassPaddingPx);
            drawable.draw(canvas);
            bubbleDrawn[BUBBLE_MIDDLE] = true;
            postInvalidateOnAnimation();
            return true;
        }

        // Drops a bubble that is not painting this frame back to empty bounds, but only on the frame it
        // stops - Drawable.setBounds short-circuits an unchanged rect, so a bubble that was already hidden
        // costs nothing and never re-triggers onBoundsChange.
        private void hideBubble(int role) {
            if (bubbleDrawn[role]) {
                bubbles[role].setBounds(0, 0, 0, 0);
                bubbleDrawn[role] = false;
            }
        }

        // Fills bubbleLeft/bubbleRight/bubbleOccupied/bubbleAlpha for this frame from the live child
        // geometry, so every bubble follows its buttons through the existing slot slides and the trailing
        // catch-up with no animator of its own. All reused fields, no allocation.
        private void computeBubbleSpans() {
            setBubbleSpan(BUBBLE_LEADING, startSlot);
            bubbleCells[BUBBLE_LEADING] = countOccupiedCells(startSlot);
            setBubbleSpan(BUBBLE_MIDDLE, middleScrollView);
            bubbleCells[BUBBLE_MIDDLE] = countOccupiedCells(middleContent);
            setBubbleSpan(BUBBLE_CONTEXT, trailingContextGroup);
            bubbleCells[BUBBLE_CONTEXT] = countEngagedCells(trailingContextGroup);
            computeTrailingSpan();
            // NagramX (#glass-lone-circle): the lone cell itself, when a zone holds exactly one. The count
            // is the row's own occupancy rule, so the decision flips the same frame the slot's measured
            // width does - never between measures - and the finder only runs at one, so zero-or-many needs
            // no third state. Trailing is not here: its lone cell is captured in its own span walk.
            for (int role = 0; role < BUBBLE_TRAILING; role++) {
                loneCell[role] = bubbleCells[role] == 1 ? findCell(role) : null;
            }
        }

        // NagramX (#glass-lone-circle): the zone's one occupied cell, only ever called with the count
        // already at one, so the first hit is the hit. The middle group's cells sit inside its two slots
        // (the recursion covers them).
        private View findCell(int role) {
            if (role == BUBBLE_LEADING) {
                return firstOccupied(startSlot);
            }
            if (role == BUBBLE_MIDDLE) {
                return firstOccupied(middleContent);
            }
            if (role == BUBBLE_CONTEXT && trailingContextGroup instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) trailingContextGroup;
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (isContextChildEngaged(group.getChildAt(i))) {
                        return group.getChildAt(i);
                    }
                }
            }
            return null;
        }

        private static View firstOccupied(ViewGroup group) {
            if (group == null) {
                return null;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof CollapsingLinearLayout) {
                    View inSlot = firstOccupied((ViewGroup) child);
                    if (inSlot != null) {
                        return inSlot;
                    }
                } else if (group instanceof CollapsingLinearLayout && ((CollapsingLinearLayout) group).isOccupied(child)) {
                    return child;
                }
            }
            return null;
        }

        // NagramX (#glass-lone-circle): a lone child laid out at a fixed width no larger than the cell is
        // a cell - every configurable button is (addConfigurable lays them out at exactly buttonSize()),
        // while the wrap-content bot menu and anything wider keep the pill.
        private boolean isCellWidth(View child) {
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            return lp.width > 0 && lp.width <= cellPx;
        }

        // NagramX (#glass-lone-circle): the cells a zone holds this pass, counted the same way the row
        // itself counts occupancy, so the lone-circle decision flips on the same frame the slot's width
        // does - never between measures. The middle group's cells sit inside its two slots, and the
        // attach wrapper only counts while it holds an engaged child, exactly as isOccupied judges it.
        private static int countOccupiedCells(ViewGroup group) {
            if (group == null) {
                return 0;
            }
            int count = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof CollapsingLinearLayout) {
                    count += countOccupiedCells((ViewGroup) child);
                } else if (group instanceof CollapsingLinearLayout && ((CollapsingLinearLayout) group).isOccupied(child)) {
                    count++;
                }
            }
            return count;
        }

        private static int countEngagedCells(View view) {
            if (!(view instanceof ViewGroup)) {
                return 0;
            }
            ViewGroup group = (ViewGroup) view;
            int count = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (isContextChildEngaged(group.getChildAt(i))) {
                    count++;
                }
            }
            return count;
        }

        private void setBubbleSpan(int role, View view) {
            if (view == null || !isDescendantOfThis(view) || !isBubbleContent(view)) {
                bubbleOccupied[role] = false;
                return;
            }
            float left = descendantLeft(view);
            bubbleLeft[role] = left;
            bubbleRight[role] = left + view.getWidth();
            bubbleAlpha[role] = alphaOf(view);
            bubbleOccupied[role] = true;
        }

        // The cached context reference outlives the view it points at if the group is moved out of the
        // row; only paint its bubble while the view is still parented under this layout, so a detached
        // view never paints at stale coordinates.
        private boolean isDescendantOfThis(View view) {
            ViewParent parent = view.getParent();
            while (parent != null) {
                if (parent == this) {
                    return true;
                }
                parent = parent.getParent();
            }
            return false;
        }

        // The trailing bubble spans every end-slot button that is not the attach context group, from the
        // leftmost occupied one to the rightmost, so it hugs its content and collapses with it.
        private void computeTrailingSpan() {
            bubbleOccupied[BUBBLE_TRAILING] = false;
            bubbleCells[BUBBLE_TRAILING] = 0;
            if (endSlot == null) {
                return;
            }
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            int alpha = 0;
            for (int i = 0; i < endSlot.getChildCount(); i++) {
                View child = endSlot.getChildAt(i);
                if (child == trailingContextGroup) {
                    continue;
                }
                if (!isBubbleContent(child)) {
                    continue;
                }
                // NagramX (#glass-lone-circle): the count rides the same painted-span walk, and its
                // one hit is the lone cell; drawGlass only reads the slot behind that count. This
                // test keeps a wider predicate (any alpha > 0 paints) than the slot occupancy rule
                // (>= 0.5), so a fading trailing button counts here before its slot frees - matching
                // the span it still has to cover.
                bubbleCells[BUBBLE_TRAILING]++;
                loneCell[BUBBLE_TRAILING] = child;
                float left = descendantLeft(child);
                float right = left + child.getWidth();
                if (left < min) {
                    min = left;
                }
                if (right > max) {
                    max = right;
                }
                // The most opaque button drives the pill, so a trailing button that is fully visible keeps
                // the pill solid while a neighbour fades in.
                alpha = Math.max(alpha, alphaOf(child));
            }
            if (max > min) {
                bubbleLeft[BUBBLE_TRAILING] = min;
                bubbleRight[BUBBLE_TRAILING] = max;
                bubbleAlpha[BUBBLE_TRAILING] = alpha;
                bubbleOccupied[BUBBLE_TRAILING] = true;
            }
        }

        // Whichever present bubble sits against the row's leading edge: the leading zone, else the
        // scrolling middle group. Only these two are left-anchored, so a trailing group never reaches
        // the leading edge even when the leading side is empty. -1 when neither is present. Mirrors
        // trailingEdgeRole so an empty leading zone leaves the middle group flush to the row edge.
        private int leadingEdgeRole() {
            if (bubbleOccupied[BUBBLE_LEADING]) {
                return BUBBLE_LEADING;
            }
            if (bubbleOccupied[BUBBLE_MIDDLE]) {
                return BUBBLE_MIDDLE;
            }
            return -1;
        }

        // Whichever present bubble sits against the row's trailing edge: the trailing group, else the
        // context group. -1 when the whole trailing zone is empty.
        private int trailingEdgeRole() {
            if (bubbleOccupied[BUBBLE_TRAILING]) {
                return BUBBLE_TRAILING;
            }
            if (bubbleOccupied[BUBBLE_CONTEXT]) {
                return BUBBLE_CONTEXT;
            }
            return -1;
        }

        // The left edge of a descendant in this view's coordinates, following getLeft() + translationX up
        // to this layout. getLeft() already carries the slot slides (SlidingLinearLayout uses
        // offsetLeftAndRight), and endSlot's translationX carries the trailing catch-up, so a bubble
        // tracks exactly where its buttons are drawn.
        private float descendantLeft(View view) {
            float x = 0f;
            View current = view;
            while (current != null && current != this) {
                x += current.getLeft() + current.getTranslationX();
                ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            return x;
        }

        private static boolean isBubbleContent(View view) {
            return view.getVisibility() == VISIBLE && view.getAlpha() > 0f && view.getWidth() > 0;
        }

        private static int alphaOf(View view) {
            float alpha = view.getAlpha();
            if (alpha <= 0f) {
                return 0;
            }
            if (alpha >= 1f) {
                return 255;
            }
            return Math.round(alpha * 255);
        }

        // Hold the pinned trailing group where it was drawn instead of letting it pop to its new anchor.
        // Erasing a draft flips two slots a frame or two apart (attach arrives, the action beside it
        // leaves), and each flip re-layouts; waiting for the anchor to settle turns what used to be two
        // consecutive slides into one. Only the slot is held and eased; the trailing bubbles are derived
        // from the slot's live geometry every frame, so they ride the same ease with no clock of their own.
        private void holdTrailingSlide(int endShift) {
            resumingMidAnimation = boundsAnimator != null;
            cancelBoundsAnimation();
            if (holdStartTime == 0) {
                holdStartTime = SystemClock.elapsedRealtime();
            }
            holdingBounds = true;
            // A hold arriving mid-animation stacks on whatever is left of the last one, so the group keeps
            // the distance it still had to cover instead of snapping back to the new anchor.
            pendingEndShift = rightAnchoredSlot.getTranslationX() + endShift;
            rightAnchoredSlot.setTranslationX(pendingEndShift);
        }

        // Each further change pushes the start back, so cap the wait: a slot that keeps resizing must not
        // leave the trailing group frozen.
        private long getRemainingSettleDelay() {
            return getSettleDelay(holdStartTime);
        }

        private void startBoundsAnimation() {
            holdingBounds = false;
            holdStartTime = 0;
            resumingMidAnimation = false;
            float initialEndShift = pendingEndShift;
            View anchored = rightAnchoredSlot;
            pendingEndShift = 0;
            if (initialEndShift == 0) {
                setAnchoredTranslation(anchored, 0);
                return;
            }
            setAnchoredTranslation(anchored, initialEndShift);
            boundsAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            boundsAnimator.setDuration(220);
            boundsAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            boundsAnimator.addUpdateListener(animation -> {
                float progress = (float) animation.getAnimatedValue();
                setAnchoredTranslation(anchored, initialEndShift * (1.0f - progress));
            });
            boundsAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animation == boundsAnimator) {
                        boundsAnimator = null;
                        setAnchoredTranslation(anchored, 0);
                    }
                }
            });
            boundsAnimator.start();
        }

        private void cancelBoundsAnimation() {
            holdingBounds = false;
            AndroidUtilities.cancelRunOnUIThread(boundsAnimationStarter);
            if (boundsAnimator != null) {
                // Drop the reference before cancelling: cancel() runs the end listener, which would settle
                // the pinned group on its final anchor. A re-hold reads what is left of the current move,
                // so it has to still be there. resetBounds is the path that clears it.
                ValueAnimator running = boundsAnimator;
                boundsAnimator = null;
                running.cancel();
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnPreDrawListener(occupancyPreDrawListener);
        }

        @Override
        protected void onDetachedFromWindow() {
            ViewTreeObserver observer = getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(occupancyPreDrawListener);
            }
            super.onDetachedFromWindow();
            cancelBoundsAnimation();
            resetBounds();
        }

        // Settle the pinned group on its current anchor with no slide. Used on a real width change
        // (rotation, multi-window), where the buttons have already moved to the new bounds.
        private void snapBounds() {
            cancelBoundsAnimation();
            holdStartTime = 0;
            resumingMidAnimation = false;
            pendingEndShift = 0;
            setAnchoredTranslation(rightAnchoredSlot, 0);
        }

        private void resetBounds() {
            availableWidthChanged = false;
            holdingBounds = false;
            holdStartTime = 0;
            pendingEndShift = 0;
            resumingMidAnimation = false;
            hasRightAnchoredLeft = false;
            setAnchoredTranslation(rightAnchoredSlot, 0);
        }

        private static void setAnchoredTranslation(View slot, float translation) {
            if (slot != null) {
                slot.setTranslationX(translation);
            }
        }
    }

    private static class SlidingLinearLayout extends LinearLayout {
        private final HashMap<View, int[]> slides = new HashMap<>();
        private final HashSet<View> hiddenFromAccessibility = new HashSet<>();
        private final Runnable slideStarter = this::startSlides;
        private ValueAnimator slideAnimator;
        private long slideHoldStart;
        protected int occupiedChildCount;
        private boolean swallowingTouch;

        SlidingLinearLayout(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setClipChildren(false);
            setClipToPadding(false);
        }

        boolean isOccupied(View child) {
            return child.getVisibility() != GONE;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = getPaddingLeft() + getPaddingRight();
            int height = getPaddingTop() + getPaddingBottom();
            int state = 0;
            int occupiedChildCount = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!isOccupied(child)) {
                    continue;
                }
                occupiedChildCount++;
                measureChildWithMargins(child, widthMeasureSpec, width, heightMeasureSpec, 0);
                LayoutParams layoutParams = (LayoutParams) child.getLayoutParams();
                width += layoutParams.leftMargin + child.getMeasuredWidth() + layoutParams.rightMargin;
                height = Math.max(height, getPaddingTop() + layoutParams.topMargin + child.getMeasuredHeight() + layoutParams.bottomMargin + getPaddingBottom());
                state = combineMeasuredStates(state, child.getMeasuredState());
            }
            this.occupiedChildCount = occupiedChildCount;
            setMeasuredDimension(
                    resolveSizeAndState(Math.max(width, getSuggestedMinimumWidth()), widthMeasureSpec, state),
                    resolveSizeAndState(Math.max(height, getSuggestedMinimumHeight()), heightMeasureSpec, state << MEASURED_HEIGHT_STATE_SHIFT)
            );
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int x = rtl ? getWidth() - getPaddingRight() : getPaddingLeft();
            slides.clear();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                int previousLeft = child.getLeft();
                int previousWidth = child.getWidth();
                if (!isOccupied(child)) {
                    layoutReleasedChild(child, x);
                    continue;
                }
                LayoutParams layoutParams = (LayoutParams) child.getLayoutParams();
                int childWidth = child.getMeasuredWidth();
                int childHeight = child.getMeasuredHeight();
                int availableHeight = getHeight() - getPaddingTop() - getPaddingBottom() - layoutParams.topMargin - layoutParams.bottomMargin;
                int childTop = getPaddingTop() + layoutParams.topMargin + Math.max(0, (availableHeight - childHeight) / 2);
                int childLeft;
                if (rtl) {
                    x -= layoutParams.rightMargin + childWidth;
                    childLeft = x;
                    x -= layoutParams.leftMargin;
                } else {
                    x += layoutParams.leftMargin;
                    childLeft = x;
                    x += childWidth + layoutParams.rightMargin;
                }
                child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
                setHiddenFromAccessibility(child, false);
                // Lay the control out where it belongs, push it back to where it was drawn, then ease it
                // across. It offsets rather than translates because the enter view already drives
                // translationX on several of these controls.
                int offset = previousLeft - childLeft;
                if (previousWidth > 0 && offset != 0) {
                    slides.put(child, new int[]{childLeft, offset});
                    child.offsetLeftAndRight(offset);
                }
            }
            if (slides.isEmpty()) {
                cancelSlides();
            } else {
                scheduleSlides();
            }
        }

        private void scheduleSlides() {
            // Something already moving should keep moving: only a settled row waits for the panel's
            // coalescing window.
            boolean animating = slideAnimator != null;
            if (slideHoldStart == 0) {
                slideHoldStart = SystemClock.elapsedRealtime();
            }
            // Children are already sitting where they were drawn, so drop a running animation instead of
            // letting it finish against the new targets at whatever progress it had reached.
            if (animating) {
                ValueAnimator running = slideAnimator;
                slideAnimator = null;
                running.cancel();
            }
            AndroidUtilities.cancelRunOnUIThread(slideStarter);
            AndroidUtilities.runOnUIThread(slideStarter, animating ? 0 : getSettleDelay(slideHoldStart));
        }

        private void startSlides() {
            slideHoldStart = 0;
            if (slides.isEmpty()) {
                return;
            }
            slideAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            slideAnimator.setDuration(220);
            slideAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            slideAnimator.addUpdateListener(animation -> applySlides((float) animation.getAnimatedValue()));
            slideAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animation == slideAnimator) {
                        slideAnimator = null;
                        applySlides(1.0f);
                        slides.clear();
                    }
                }
            });
            slideAnimator.start();
        }

        private void applySlides(float progress) {
            for (Map.Entry<View, int[]> entry : slides.entrySet()) {
                View child = entry.getKey();
                if (child.getParent() != this) {
                    continue;
                }
                int[] slide = entry.getValue();
                child.offsetLeftAndRight(slide[0] + Math.round(slide[1] * (1.0f - progress)) - child.getLeft());
            }
        }

        private void cancelSlides() {
            AndroidUtilities.cancelRunOnUIThread(slideStarter);
            slideHoldStart = 0;
            if (slideAnimator != null) {
                ValueAnimator running = slideAnimator;
                slideAnimator = null;
                running.cancel();
            }
            applySlides(1.0f);
            slides.clear();
        }

        @Override
        public void onViewRemoved(View child) {
            super.onViewRemoved(child);
            slides.remove(child);
            setHiddenFromAccessibility(child, false);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            cancelSlides();
        }

        // Hiding the row does not detach the slots, and a GONE -> VISIBLE round trip is not guaranteed to
        // lay out again, so a half-finished slide would come back stuck.
        static void cancelSlidesIn(View view) {
            if (view instanceof SlidingLinearLayout) {
                ((SlidingLinearLayout) view).cancelSlides();
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    cancelSlidesIn(group.getChildAt(i));
                }
            }
        }

        // A control that gave up its slot keeps the box it last held until its fade actually ends. Collapsing
        // it the moment the row stops counting it wiped it off screen at half opacity, so every swap looked
        // like a pop rather than a fade.
        private void layoutReleasedChild(View child, int x) {
            if (shouldKeepReleasedBox(child)) {
                child.layout(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
                setHiddenFromAccessibility(child, true);
                return;
            }
            setHiddenFromAccessibility(child, false);
            child.layout(x, getPaddingTop(), x, getPaddingTop());
        }

        // A released control still has something on screen to hold in place while it is VISIBLE, past zero
        // alpha and still holding width. Subclasses that wrap their content override this where the wrapper's
        // own alpha is not what is fading.
        protected boolean shouldKeepReleasedBox(View child) {
            return child.getWidth() > 0 && child.getVisibility() == VISIBLE && child.getAlpha() > 0;
        }

        // Touch is swallowed below, but a screen reader would still reach a control that is on its way out.
        private void setHiddenFromAccessibility(View child, boolean hidden) {
            if (hidden ? !hiddenFromAccessibility.add(child) : !hiddenFromAccessibility.remove(child)) {
                return;
            }
            child.setImportantForAccessibility(hidden
                    ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }

        // A control still fading keeps its hit rect, and it overlaps whatever took its place. Swallow the tap
        // rather than firing the action the user can no longer see.
        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                swallowingTouch = isInsideReleasedChild(event.getX(), event.getY());
            }
            if (swallowingTouch) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    swallowingTouch = false;
                }
                return true;
            }
            return super.dispatchTouchEvent(event);
        }

        private boolean isInsideReleasedChild(float x, float y) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (isOccupied(child) || child.getWidth() == 0 || child.getVisibility() != VISIBLE || child.getAlpha() <= 0) {
                    continue;
                }
                if (x >= child.getLeft() && x < child.getRight() && y >= child.getTop() && y < child.getBottom()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class CollapsingLinearLayout extends SlidingLinearLayout {
        private View contextGroup;

        CollapsingLinearLayout(Context context) {
            super(context);
        }

        // The attach wrapper is a plain LinearLayout whose own visibility and alpha stay put while its
        // buttons come and go, so the row cannot judge it the way it judges a single button. Point the slot
        // at it so isOccupied and the release path can look at its content instead.
        void setContextGroup(View view) {
            contextGroup = view;
        }

        // A control claims its slot once its fade is past halfway and gives it up at the same point on
        // the way out. Releasing only at alpha 0 made a leaving control hold the row open for its whole
        // fade, so the panel reflowed twice - once when its replacement arrived, again when it finally
        // let go - and the neighbouring buttons crawled back into place. The attach wrapper adds one more
        // condition: even while it is on screen it only occupies the row while it holds an engaged child,
        // so a wrapper full of faded-out buttons (a hidden suggestion button stays VISIBLE at alpha 0 in
        // toolbar mode) collapses to zero width and neighbouring configurable buttons slide left instead of
        // sitting beyond an empty opaque pill.
        @Override
        boolean isOccupied(View child) {
            if (child == contextGroup && child instanceof ViewGroup
                    && !hasEngagedChild((ViewGroup) child)) {
                return false;
            }
            return child.getVisibility() == VISIBLE && child.getAlpha() >= 0.5f;
        }

        // The wrapper's own alpha is not what fades when its buttons leave, so the released-box hold has to
        // look at its content. Once it holds no engaged child there is nothing to fade out, so collapse it
        // now rather than holding an opaque empty box; while it still has an engaged child (its own
        // chat-type fade) the base rule keeps the box the whole way down as before.
        @Override
        protected boolean shouldKeepReleasedBox(View child) {
            if (child == contextGroup && child instanceof ViewGroup
                    && !hasEngagedChild((ViewGroup) child)) {
                return false;
            }
            return super.shouldKeepReleasedBox(child);
        }

        @Override
        public void onDescendantInvalidated(View child, View target) {
            super.onDescendantInvalidated(child, target);
            invalidate();
        }

        // Occupancy changes reflow the row; a finished fade has to reflow too, otherwise the control that
        // just went invisible keeps its box (and its hit rect) forever.
        // Only onMeasure writes occupiedChildCount, so it records what the row actually laid out rather
        // than what it meant to. Recording the new count here instead used to strand a control: the panel
        // re-measures its slots with the same specs every pass, so a slot skips onMeasure unless a layout
        // was requested for it, and a request that got lost mid-pass left the count already updated. The
        // mismatch was gone, nothing asked again, and the control sat visible inside a zero-width box for
        // the rest of the session. Leaving the count alone keeps the mismatch until a measure clears it.
        boolean occupancyChanged() {
            int occupied = 0;
            boolean releasedChildVisible = false;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (isOccupied(child)) {
                    occupied++;
                } else if (child.getWidth() > 0 && (child.getVisibility() != VISIBLE || child.getAlpha() <= 0)) {
                    releasedChildVisible = true;
                }
            }
            return occupiedChildCount != occupied || releasedChildVisible;
        }
    }

    // ActionBarLayout's back-swipe arms from a right-drag on ACTION_MOVE and only backs off for a
    // horizontal child that already owns the touch. A plain HorizontalScrollView only claims that
    // ownership once its own onInterceptTouchEvent sees enough horizontal drag, which can be a frame
    // or two behind ActionBarLayout on a fast flick - so the swipe that was meant to scroll the middle
    // strip closes the chat instead. Claiming the touch on ACTION_DOWN, whenever the strip has content
    // to scroll to, closes that gap.
    private static final class ComposerMiddleScrollView extends HorizontalScrollView {
        private float downX;
        private float downY;
        private boolean guardArmed;

        ComposerMiddleScrollView(Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    guardArmed = canScrollHorizontally(-1) || canScrollHorizontally(1);
                    if (guardArmed && getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (guardArmed) {
                        float dx = Math.abs(ev.getX() - downX);
                        float dy = Math.abs(ev.getY() - downY);
                        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                        if (dy > dx && dy > slop) {
                            // The drag turned vertical (e.g. dragging the ComposerLayoutActivity preview
                            // row) - hand the gesture back instead of pinning ourselves as its owner for
                            // the rest of the touch stream.
                            guardArmed = false;
                            if (getParent() != null) {
                                getParent().requestDisallowInterceptTouchEvent(false);
                            }
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    guardArmed = false;
                    break;
                default:
                    break;
            }
            return super.onInterceptTouchEvent(ev);
        }
    }
}
