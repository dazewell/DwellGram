package com.dazewell.gram.composer;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.chat.ChatInputViewsContainer;
import org.telegram.ui.Components.chat.ViewPositionWatcher;

import me.vkryl.core.reference.ReferenceList;
import xyz.nextalone.nagram.NaConfig;
import com.dazewell.gram.helpers.InterfaceStyleController;

/**
 * NagramX (#interface-style): the MD3 Composer. ChatActivity's ChatInputViewsContainer hands its draw pass
 * here instead of painting the glass island and under-keyboard drawables. The composer becomes one island,
 * frosted and lightly shadowed, docked as a sheet while it rests on the nav bar and floating once the keyboard
 * lifts it: a tonal field with the send column beside it and the tools row
 * under it, or a tonal island around whichever action run replaces the input. The under-keyboard panel
 * stays docked and opaque.
 * Colours are read from the chat's theme on every draw. Only the frost drawable's alpha and corner radius are kept
 * between frames, and changed only when they move, because each change re-records its render node.
 */
public final class ComposerMd3Surface {
    private static final float CONTAINER_ON_SURFACE_BLEND = 0.06f;
    // Laid over the island like the strip, one step above its 6%. On an opaque island this matches an opaque
    // blend of the surface to within alpha rounding; on the frost it tints what shows through instead of hiding it.
    private static final float FIELD_ON_SURFACE_BLEND = 0.08f;
    // Lightening reads weaker per percent than darkening, so a dark theme's field takes a bigger step.
    private static final float FIELD_ON_SURFACE_BLEND_DARK = 0.14f;
    // The composer keeps this share of the blur the Blur strength setting asks for, so a bright bubble passing
    // under it cannot glow through: the 0.5-1 alpha range becomes 0.65-1.
    private static final float FROST_SHARE = 0.7f;
    private static final int STRIP_RADIUS = 10;
    private static final int STRIP_ACCENT = 3;
    private static final int STRIP_ACCENT_INSET = 6;
    // Inset top and bottom inside the 48dp top view, which centres the strip on the upstream close button.
    private static final int STRIP_INSET = 3;
    // Island radius minus the 3dp margin, so the field and the reply strip sit concentric in the island.
    private static final int FIELD_RADIUS = 10;
    // The field is drawn this far inside the 44dp text row top and bottom, so it is 42dp and shares the
    // row's centre with the send circle.
    private static final int FIELD_INSET = 1;
    // The island reaches this far past the text row and the tools row, and the field sits the same 3dp in
    // from its leading edge, so the field has an even margin on three sides.
    private static final int ISLAND_PADDING = 2;
    private static final int FIELD_SIDE_INSET = 3;
    private static final int FIELD_SEND_GAP = 8;
    // The send slot is DEFAULT_HEIGHT square; its touch target reaches this far past it on every side, to 48dp.
    private static final int SEND_TARGET_REACH = 2;
    // The send circle's top sits about 5dp inside the island, which caps the radius near 13dp.
    private static final int ISLAND_RADIUS = 13;
    // How far the keyboard or emoji panel lifts the island before its lift has fully turned from docked to floating.
    private static final int SHEET_MORPH = 48;
    // The sheet's shape morphs on its own clock, so the change stays visible however fast the keyboard moves.
    private static final long SHEET_SHAPE_DURATION = 300;
    // A docked sheet starts floating once its dock factor drops below this, a few dp into the lift.
    private static final float SHEET_FLOAT_AT = 0.9f;
    // Docked, the tools row's bottom sits this far above the nav bar in place of the floating lift.
    private static final int DOCKED_LIFT = 4;
    private static final int SHADOW_RADIUS = 4;
    private static final int SHADOW_DY = 2;
    private static final int SHADOW_ALPHA = 77;
    private static final int DARK_EDGE_TOP = 0x28FFFFFF;
    private static final int DARK_EDGE_BOTTOM = 0x14FFFFFF;
    // The stock pill is drawn this far in from its children's edges, which is how far the input, search and
    // overlay children already sit in from the container.
    private static final int PILL_INSET = 7;
    // The island sits this much further in from the screen sides than the stock pill, 9dp from each edge.
    private static final int ISLAND_EXTRA = 2;
    // Every island child is padded this far in from the container, so the send circle, drawn 3dp inside its slot,
    // clears the island's side by the same 5dp it clears its top.
    private static final int CHILD_SIDE_PADDING = 4;
    // The selection bar fills its padded parent edge to edge, so its island edge is measured from the bar itself:
    // the pill inset, plus the island's extra inset, less the padding the bar already sits inside.
    private static final int ACTION_RUN_SIDE_INSET = PILL_INSET + ISLAND_EXTRA - CHILD_SIDE_PADDING;
    // Added to the stock 9dp lift so the floating island, less its 2dp bottom padding, clears the keyboard by the
    // same 9dp it clears the screen sides.
    private static final int EXTRA_LIFT = ISLAND_PADDING;
    // Measured on device: with the full trim the expanded island sat about 18dp under the header, twice its side gap.
    private static final int EXPANDED_HEADROOM_RETURN = 9;
    // Docked composer: a flat full-width panel has no outer radius to stay concentric with, so the field, the reply
    // strip and the nested selection buttons share MD3's medium shape instead. Full round would waste the corners.
    private static final int DOCKED_SHAPE_RADIUS = 12;
    // Docked composer: the tools row's bottom sits this far above the nav bar or the keyboard; its 48dp cells already
    // leave air under the glyphs.
    private static final int DOCKED_FLAT_LIFT = 2;
    // Docked composer: the panel's top gap grows and its side gaps shrink so the two read as one margin. The field
    // sits 10dp from the screen edge and 6dp under the panel top; the round send circle, which reads farther in than
    // a flat edge, sits 12dp from its edge, and the tools row follows the circle.
    private static final int DOCKED_ISLAND_PADDING = 5;
    private static final int DOCKED_FIELD_SIDE_INSET = 1;
    private static final int DOCKED_CHILD_SIDE_PADDING = 2;

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint();
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        edgePaint.setStyle(Paint.Style.STROKE);
    }
    private final RectF rect = new RectF();
    private final RectF island = new RectF();
    private final Rect islandBounds = new Rect();
    private final float[] islandRadii = new float[8];
    private final Path islandPath = new Path();
    private float frostTopRadius = -1, frostBottomRadius = -1;
    private final RectF sendSlot = new RectF();
    private float touchShiftX, touchShiftY;
    private float appliedShiftX, appliedShiftY;

    private ChatActivityEnterView enterView;
    private View channelButtons;
    private View actionButtons;
    private boolean barVisible;
    private ChatInputViewsContainer host;
    private AnimatedFloat sheetShape;
    private BlurredBackgroundDrawable frost;
    private int frostAccount;
    // Read once: the chat is rebuilt when the setting changes, like the rest of Interface Style.
    private final boolean docked;
    private final RectF run = new RectF();
    // The tools row's share of the pill this frame, kept for drawContents, which the docked panel also calls.
    private float toolsInset;
    private boolean sheetFollowsFold;
    private int sheetFoldId;
    private float sheetFoldFrom;
    private float sheetFoldFromFraction;

    public ComposerMd3Surface(Theme.ResourcesProvider resourcesProvider) {
        this.resourcesProvider = resourcesProvider;
        this.docked = InterfaceStyleController.dockedComposer();
    }

    public static ComposerMd3Surface createIfEnabled(Theme.ResourcesProvider resourcesProvider) {
        return InterfaceStyleController.applyComposer() ? new ComposerMd3Surface(resourcesProvider) : null;
    }

    // The island shows one of these at a time; their alpha is how ChatActivity cross-fades between them,
    // so it is also how this surface decides which run the island wraps. The host supplies the window insets.
    public void bind(ChatInputViewsContainer host, ChatActivityEnterView enterView, View channelButtons, View actionButtons) {
        this.host = host;
        // The docked panel never floats, so it has no shape to morph.
        sheetShape = docked ? null : new AnimatedFloat(host, SHEET_SHAPE_DURATION, CubicBezierInterpolator.EASE_OUT);
        final FrameLayout island = host.getInputIslandBubbleContainer();
        island.setClipToPadding(false);
        final int childPadding = dp(docked ? DOCKED_CHILD_SIDE_PADDING : CHILD_SIDE_PADDING);
        island.setPadding(childPadding, 0, childPadding, 0);
        this.enterView = enterView;
        this.channelButtons = channelButtons;
        this.actionButtons = nestActionButtons(actionButtons);
    }

    /**
     * Frosts the island the way the MD3 chat header is frosted. It gets its own factory over the chat's frosted
     * source because the shared one hands out drawables with the Liquid Glass shader when that is enabled.
     * Without a source (below API 31, or chat blur off when the chat opened) the island stays opaque.
     */
    public void attachFrost(@Nullable BlurredBackgroundSource source, ViewPositionWatcher watcher, ViewGroup root,
                            ReferenceList<View> linkedViews, ReferenceList<BlurredBackgroundDrawable> linkedDrawables,
                            View view, int account) {
        if (source == null) {
            return;
        }
        final BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(watcher, root, source);
        factory.setLinkedViewsRef(linkedViews);
        factory.setLinkedDrawablesRef(linkedDrawables);
        frost = factory.create(view, frostProvider());
        frost.setRadius(docked ? 0 : dp(ISLAND_RADIUS));
        frostAccount = account;
    }

    // A soft shadow in the theme's own panel-shadow colour lifts the island. On a dark theme the shadow vanishes
    // into the wallpaper, so a faint light edge, brighter on top, traces it instead.
    // Docked, only the top edge is on screen: it takes the panel divider when those are on, the shadow and the dark
    // theme's light edge otherwise, never both.
    private BlurredBackgroundProvider frostProvider() {
        if (docked) {
            return new BlurredBackgroundProviderBuilder(resourcesProvider)
                    .setBackgroundColor((r, isDark) -> Theme.multAlpha(surfaceColor(), frostAlpha()))
                    .setStrokeColorTop((r, isDark) -> isDark && !InterfaceStyleController.panelDividers() ? DARK_EDGE_TOP : 0)
                    .setStrokeColorBottom(0, 0)
                    .setStrokeWidth(dpf2(1), dpf2(1))
                    .setShadowColor((r, isDark) -> InterfaceStyleController.panelDividers() ? 0 : shadowColor())
                    .setShadowLayer(dp(SHADOW_RADIUS), 0, 0)
                    .build();
        }
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> Theme.multAlpha(surfaceColor(), frostAlpha()))
                .setStrokeColorTop(0, DARK_EDGE_TOP)
                .setStrokeColorBottom(0, DARK_EDGE_BOTTOM)
                .setStrokeWidth(dpf2(1), dpf2(1))
                .setShadowColor((r, isDark) -> shadowColor())
                .setShadowLayer(dp(SHADOW_RADIUS), 0, dp(SHADOW_DY))
                .build();
    }

    private boolean isDark() {
        return resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
    }

    private int islandPadding() {
        return dp(docked ? DOCKED_ISLAND_PADDING : ISLAND_PADDING);
    }

    private int fieldSideInset() {
        return dp(docked ? DOCKED_FIELD_SIDE_INSET : FIELD_SIDE_INSET);
    }

    private int fieldRadius() {
        return dp(docked ? DOCKED_SHAPE_RADIUS : FIELD_RADIUS);
    }

    private int stripRadius() {
        return dp(docked ? DOCKED_SHAPE_RADIUS : STRIP_RADIUS);
    }

    private boolean frosted() {
        return frost != null && BlurredBackgroundProviderImpl.checkBlurEnabled(frostAccount, resourcesProvider);
    }

    public int surfaceColor() {
        return ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider), 255);
    }

    /** The 6% on-surface tint laid over the island, so it tones a frosted island and an opaque one alike. */
    private int containerOverlay() {
        final int onSurface = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        return ColorUtils.setAlphaComponent(onSurface, Math.round(255 * CONTAINER_ON_SURFACE_BLEND));
    }

    /** The field's on-surface tint laid over the island, one step above the strip's: darker in a light theme, lighter in a dark one. */
    private int fieldOverlay() {
        final int onSurface = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        return ColorUtils.setAlphaComponent(onSurface, Math.round(255 * (isDark() ? FIELD_ON_SURFACE_BLEND_DARK : FIELD_ON_SURFACE_BLEND)));
    }

    private int shadowColor() {
        return ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelShadow, resourcesProvider), SHADOW_ALPHA);
    }

    public int primaryColor() {
        return Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider);
    }

    private int outlineVariantColor(int surface) {
        return InterfaceStyleController.panelDividerPaint(surface, resourcesProvider).getColor();
    }

    public boolean contains(float x, float y) {
        // Same rule as the island glass: only a fully shown island takes touches.
        return barVisible && island.contains(x, y);
    }

    /**
     * The send, mic and camera circle keeps the stock 44dp slot, but under MD3 its touch target is 48dp. A
     * press that starts in the 2dp band around the slot is shifted into it, and the rest of that gesture is
     * shifted by the same amount, so a hold-to-record slide still tracks the finger exactly.
     */
    public void retargetSendTouch(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchShiftX = touchShiftY = 0;
            if (!sendSlot.isEmpty()) {
                final float x = event.getX(), y = event.getY();
                final float reach = dp(SEND_TARGET_REACH);
                if (!sendSlot.contains(x, y) && x >= sendSlot.left - reach && x <= sendSlot.right + reach
                        && y >= sendSlot.top - reach && y <= sendSlot.bottom + reach) {
                    touchShiftX = Math.max(sendSlot.left + 1, Math.min(sendSlot.right - 1, x)) - x;
                    touchShiftY = Math.max(sendSlot.top + 1, Math.min(sendSlot.bottom - 1, y)) - y;
                }
            }
        }
        appliedShiftX = touchShiftX;
        appliedShiftY = touchShiftY;
        if (appliedShiftX != 0 || appliedShiftY != 0) {
            event.offsetLocation(appliedShiftX, appliedShiftY);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchShiftX = touchShiftY = 0;
            }
        }
    }

    /** Undoes {@link #retargetSendTouch} once the container has dispatched the event, for whoever reads it next. */
    public void restoreSendTouch(MotionEvent event) {
        if (appliedShiftX != 0 || appliedShiftY != 0) {
            event.offsetLocation(-appliedShiftX, -appliedShiftY);
            appliedShiftX = appliedShiftY = 0;
        }
    }

    /**
     * 1 while the island rests on the nav bar, where it docks as a sheet running down to the screen's bottom edge
     * so no edge cuts across the display's rounded corners; 0 once the keyboard or emoji panel has lifted it
     * {@link #SHEET_MORPH} clear, where it floats as an island again.
     */
    private float dockFactor(float maxBottomInset) {
        final float keyboard = keyboardShare(maxBottomInset);
        return keyboard < 0 ? 0 : Math.max(0, Math.min(1, 1f - keyboard / dp(SHEET_MORPH)));
    }

    /** The keyboard's or emoji panel's share of the container's bottom inset, or -1 before the host has insets. */
    private float keyboardShare(float maxBottomInset) {
        if (host == null) {
            return -1;
        }
        final WindowInsets insets = host.getRootWindowInsets();
        if (insets == null) {
            return -1;
        }
        // The same resting inset WindowInsetsStateHolder builds the container's bottom inset from, so the difference
        // below is exactly the keyboard's or emoji panel's share of it.
        final int rest = WindowInsetsCompat.toWindowInsetsCompat(insets, host).getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).bottom;
        return Math.max(0, maxBottomInset - rest);
    }

    /**
     * What the island adds to the stock lift for the container's current bottom inset. Floating it is
     * {@link #extraLift()}; docked there are no corners left to clear, so the tools row sits
     * {@link #DOCKED_LIFT} above the nav bar instead.
     */
    public float liftAboveStock(float maxBottomInset) {
        if (docked) {
            return dockedLiftAboveStock();
        }
        final float dock = dockFactor(maxBottomInset);
        final float docked = dp(DOCKED_LIFT) - dp(ChatInputViewsContainer.INPUT_BUBBLE_BOTTOM);
        return extraLift() + (docked - extraLift()) * dock;
    }

    private static float frostAlpha() {
        return 1f - (1f - NaConfig.interfaceStyleBlurAlpha()) * FROST_SHARE;
    }

    /** How much higher than the stock lift the floating island sits, so its gap to the keyboard matches its side gaps. */
    public int extraLift() {
        return dp(EXTRA_LIFT);
    }

    /** How far the island reaches above the pill. */
    public int topOverhang() {
        return islandPadding();
    }

    // Docked, the panel rests on the nav bar and the keyboard alike, so its lift is the same constant in both.
    private int dockedLiftAboveStock() {
        return dp(DOCKED_FLAT_LIFT) - dp(ChatInputViewsContainer.INPUT_BUBBLE_BOTTOM);
    }

    /**
     * How much higher the island's top sits than a Liquid Glass pill's: the extra lift plus its reach above the pill.
     * The docked panel sits lower than the stock pill, so there it is negative.
     */
    public int reachAboveStock() {
        return (docked ? dockedLiftAboveStock() : extraLift()) + topOverhang();
    }

    /**
     * How much less room the expanded input gets than a Liquid Glass one: the extra lift, the island's reach
     * above its pill, and whatever panel the header shows under the action bar, so it never slides under it.
     * The stock budget already leaves more headroom above the pill than the island needs; this much of it is
     * handed back so the gap under the header matches the island's 9dp side gaps.
     */
    public int expandedInputTrim(int headerPanelHeight) {
        return reachAboveStock() + Math.max(0, headerPanelHeight) - dp(EXPANDED_HEADROOM_RETURN);
    }

    /**
     * @param pill            the island's visible pill in container coordinates, including its slide offset
     * @param pillTranslation the vertical slide the pill carries while selection mode takes the island over
     * @param pillAlpha       the island's own fade, 0-255
     * @param toolsInset      the part of the pill the tools row occupies, already subtracted from {@code pill}
     * @param underKeyboard   bounds of the in-app keyboard panel, or null while it is not showing
     */
    public void draw(Canvas canvas, int width, int height, Rect pill, float pillTranslation, int pillAlpha,
                     float toolsInset, boolean drawPill, Rect underKeyboard) {
        if (pill.isEmpty()) {
            island.setEmpty();
            barVisible = false;
            return;
        }
        this.toolsInset = toolsInset;
        final float alpha = pillAlpha / 255f;
        final float inputFactor = visibility(enterView);
        final float channelFactor = visibility(channelButtons);
        final float actionFactor = visibility(actionButtons);
        // ChatActivity fades the island out both for the selection bar, which the island keeps hosting, and
        // for modes like adding a poll option, which hide the composer outright. Dividing the selection share
        // out of the island's alpha leaves only the second kind, so the cross-fade into selection never dims it.
        final float barFactor = actionFactor >= 1f ? 1f : Math.max(actionFactor, Math.min(1f, alpha / (1f - actionFactor)));
        barVisible = barFactor >= 1f;
        final int surface = surfaceColor();
        final float pad = islandPadding();
        // The pill is drawn from the container's edges; the island sits ISLAND_EXTRA inside it.
        final float pillLeft = pill.left + dp(ISLAND_EXTRA);
        final float pillRight = pill.right - dp(ISLAND_EXTRA);

        final float topViewProgress = enterView != null ? enterView.getTopViewEnterProgress() : 0;
        final float topViewHeight = enterView != null ? Math.max(0, enterView.getTopViewHeight()) * topViewProgress : 0;

        // One island, shaped by whichever run is showing and eased between them as ChatActivity cross-fades.
        // The input wraps the text row and the tools row under it; the channel run is the pill that
        // setInputBubbleOffsets already shrank to the buttons; the selection bar keeps the pill's height.
        // Search and the bottom overlay text have no view bound here, so whatever share the three runs leave
        // goes to the plain pill, which is what the island is sized to while they show.
        float weight = 0, left = 0, top = 0, right = 0, bottom = 0;
        final float plainFactor = Math.max(0, 1f - inputFactor - channelFactor - actionFactor);
        if (plainFactor > 0) {
            weight += plainFactor;
            left += pillLeft * plainFactor;
            top += (pill.top - pad) * plainFactor;
            right += pillRight * plainFactor;
            bottom += (pill.bottom + pad) * plainFactor;
        }
        if (inputFactor > 0) {
            weight += inputFactor;
            left += pillLeft * inputFactor;
            top += (pill.top - pad) * inputFactor;
            right += pillRight * inputFactor;
            bottom += (pill.bottom + toolsInset + pad) * inputFactor;
        }
        if (channelFactor > 0) {
            weight += channelFactor;
            left += pillLeft * channelFactor;
            top += (pill.top - pad) * channelFactor;
            right += pillRight * channelFactor;
            bottom += (pill.bottom + pad) * channelFactor;
        }
        if (actionFactor > 0 && actionButtons != null) {
            weight += actionFactor;
            final int actionInset = dp(docked ? PILL_INSET + ISLAND_EXTRA - DOCKED_CHILD_SIDE_PADDING : ACTION_RUN_SIDE_INSET);
            left += (actionButtons.getLeft() + actionInset) * actionFactor;
            top += (pill.top - pillTranslation - pad) * actionFactor;
            right += (actionButtons.getRight() - actionInset) * actionFactor;
            bottom += (pill.bottom - pillTranslation + pad) * actionFactor;
        }
        island.set(left / weight, top / weight, right / weight, bottom / weight);
        if (docked) {
            drawDocked(canvas, width, height, pill, alpha, inputFactor, channelFactor, actionFactor, barFactor, drawPill,
                    pillLeft, pillRight, topViewProgress, topViewHeight, underKeyboard);
            return;
        }
        // Docked, the island keeps its width and runs down off the screen, so it has no bottom edge to show. The shape
        // starts floating as soon as the keyboard begins lifting it, so the morph plays alongside the keyboard rather
        // than after it, and docks again halfway down, so closing the keyboard doesn't wait for the island to land.
        final float resting = dockFactor(height - host.getInputBubbleBottom() - host.getInputBubbleBottomLift());
        final float dockAt = sheetShape != null && sheetShape.getTargetValue() > 0.5f ? SHEET_FLOAT_AT : 0.5f;
        final float dock = coupleSheetToFold(sheetShape != null ? sheetShape.set(resting > dockAt ? 1f : 0f) : resting);
        island.bottom += (height - island.bottom) * dock;
        final float radius = Math.min(dp(ISLAND_RADIUS), island.height() / 2f);
        final float bottomRadius = Math.min(dp(ISLAND_RADIUS) * (1f - dock), island.height() / 2f);
        islandRadii[0] = islandRadii[1] = islandRadii[2] = islandRadii[3] = radius;
        islandRadii[4] = islandRadii[5] = islandRadii[6] = islandRadii[7] = bottomRadius;
        islandPath.rewind();
        islandPath.addRoundRect(island, islandRadii, Path.Direction.CW);

        final int frostAlpha = Math.round(255 * barFactor);
        if (frost != null && frost.getAlpha() != frostAlpha) {
            // Only on a change: every setAlpha re-records the blur's display list.
            frost.setAlpha(frostAlpha);
        }
        final boolean frosted = frosted();
        if (barFactor > 0) {
            if (frosted) {
                if (frostTopRadius != radius || frostBottomRadius != bottomRadius) {
                    frostTopRadius = radius;
                    frostBottomRadius = bottomRadius;
                    frost.setRadius(radius, radius, bottomRadius, bottomRadius);
                }
                island.roundOut(islandBounds);
                frost.setBounds(islandBounds);
                frost.draw(canvas);
            } else {
                fillPaint.setColor(Theme.multAlpha(surface, barFactor));
                fillPaint.setShadowLayer(dp(SHADOW_RADIUS), 0, dp(SHADOW_DY), Theme.multAlpha(shadowColor(), barFactor));
                canvas.drawPath(islandPath, fillPaint);
                fillPaint.clearShadowLayer();
                if (isDark()) {
                    final float edge = dpf2(1);
                    edgePaint.setStrokeWidth(edge);
                    edgePaint.setColor(Theme.multAlpha(DARK_EDGE_TOP, barFactor));
                    BlurredBackgroundDrawable.drawStroke(canvas, island.left, island.top, island.right, island.bottom, islandRadii, edge, true, edgePaint);
                    edgePaint.setColor(Theme.multAlpha(DARK_EDGE_BOTTOM, barFactor));
                    BlurredBackgroundDrawable.drawStroke(canvas, island.left, island.top, island.right, island.bottom, islandRadii, edge, false, edgePaint);
                }
            }
        }

        // The channel run has no field of its own, so the island itself takes the tone; so does the selection run unless MD3 Buttons nests its buttons as fields.
        final float runFactor = Math.max(InterfaceStyleController.applyButtons() ? 0 : actionFactor, drawPill ? alpha * channelFactor : 0);
        if (runFactor > 0) {
            fillPaint.setColor(Theme.multAlpha(fieldOverlay(), runFactor));
            canvas.drawPath(islandPath, fillPaint);
        }

        drawContents(canvas, width, height, pill, alpha, inputFactor, drawPill, pillLeft, pillRight, topViewProgress, topViewHeight, surface, underKeyboard);
    }

    /**
     * The sheet's own 300ms clock starts once the keyboard has lifted it a few dp, the fold's when the enter view sees
     * the keyboard, so side by side the bottom edge trailed the fold. While both head the same way (keyboard up:
     * floating and two rows; down: docked and one row) the sheet follows the fold from wherever it had got to, and
     * lands with it; a fold the keyboard didn't cause, or a sheet change without a fold, keeps the sheet's own clock.
     */
    private float coupleSheetToFold(float sheetDock) {
        final ComposerInlineRest rest = enterView != null ? enterView.getComposerInlineRest() : null;
        if (sheetShape == null || rest == null) {
            return sheetDock;
        }
        final float foldTarget = rest.isCompact() ? 1f : 0f;
        if (rest.isMorphing() && sheetShape.getTargetValue() == foldTarget) {
            final float fraction = rest.foldFraction();
            // A reversal can flip both targets inside one draw, so a new fold, not only a disagreeing frame, re-captures.
            if (!sheetFollowsFold || sheetFoldId != rest.foldId()) {
                sheetFollowsFold = true;
                sheetFoldId = rest.foldId();
                sheetFoldFrom = sheetDock;
                sheetFoldFromFraction = Math.min(fraction, 0.99f);
            }
            final float share = Math.max(0, Math.min(1, (fraction - sheetFoldFromFraction) / (1f - sheetFoldFromFraction)));
            final float dock = sheetFoldFrom + (foldTarget - sheetFoldFrom) * share;
            // Held where the fold has it, so letting go when the fold ends, or a reversal, starts from here.
            sheetShape.set(dock, true);
            sheetShape.set(foldTarget);
            return dock;
        }
        sheetFollowsFold = false;
        return sheetDock;
    }

    /**
     * The docked composer: one flat panel across the whole width, from the island's top to just under the keyboard's
     * top (the keyboard hides the overlap) or the screen's bottom edge, square at every corner. The channel and selection runs no longer own the panel's
     * outline, so their tone is a field-shaped rect inside it rather than the whole panel.
     */
    private void drawDocked(Canvas canvas, int width, int height, Rect pill, float alpha, float inputFactor,
                            float channelFactor, float actionFactor, float barFactor, boolean drawPill, float pillLeft,
                            float pillRight, float topViewProgress, float topViewHeight, Rect underKeyboard) {
        run.set(island);
        final float keyboard = Math.max(0, keyboardShare(height - host.getInputBubbleBottom() - host.getInputBubbleBottomLift()));
        island.set(0, run.top, width, Math.max(run.bottom, height - keyboard));
        for (int i = 0; i < islandRadii.length; i++) {
            islandRadii[i] = 0;
        }
        islandPath.rewind();
        islandPath.addRect(island, Path.Direction.CW);

        final int surface = surfaceColor();
        final boolean dividers = InterfaceStyleController.panelDividers();
        final int frostAlpha = Math.round(255 * barFactor);
        if (frost != null && frost.getAlpha() != frostAlpha) {
            // Only on a change: every setAlpha re-records the blur's display list.
            frost.setAlpha(frostAlpha);
        }
        if (barFactor > 0) {
            if (frosted()) {
                // A dp past each side, so the drawable's own side strokes stay off screen.
                island.roundOut(islandBounds);
                islandBounds.inset(-dp(1), 0);
                frost.setBounds(islandBounds);
                frost.draw(canvas);
            } else {
                fillPaint.setColor(Theme.multAlpha(surface, barFactor));
                if (!dividers) {
                    fillPaint.setShadowLayer(dp(SHADOW_RADIUS), 0, 0, Theme.multAlpha(shadowColor(), barFactor));
                }
                canvas.drawRect(island, fillPaint);
                fillPaint.clearShadowLayer();
                if (!dividers && isDark()) {
                    fillPaint.setColor(Theme.multAlpha(DARK_EDGE_TOP, barFactor));
                    canvas.drawRect(island.left, island.top, island.right, island.top + dpf2(1), fillPaint);
                }
            }
            if (dividers) {
                dividerPaint.setColor(Theme.multAlpha(outlineVariantColor(surface), barFactor));
                canvas.drawRect(island.left, island.top, island.right, island.top + Math.max(1, dp(0.66f)), dividerPaint);
            }
        }

        final float runFactor = Math.max(InterfaceStyleController.applyButtons() ? 0 : actionFactor, drawPill ? alpha * channelFactor : 0);
        if (runFactor > 0) {
            // The same 3dp margin the field keeps inside the text row.
            rect.set(run.left + fieldSideInset(), run.top + islandPadding() + dp(FIELD_INSET),
                    run.right - fieldSideInset(), run.bottom - islandPadding() - dp(FIELD_INSET));
            if (rect.width() > 0 && rect.height() > 0) {
                final float r = Math.min(fieldRadius(), rect.height() / 2f);
                fillPaint.setColor(Theme.multAlpha(fieldOverlay(), runFactor));
                canvas.drawRoundRect(rect, r, r, fillPaint);
            }
        }
        drawContents(canvas, width, height, pill, alpha, inputFactor, drawPill, pillLeft, pillRight, topViewProgress, topViewHeight, surface, underKeyboard);
    }

    /** The field, the reply strip, the send slot and the under-keyboard panel, shared by the island and the docked panel. */
    private void drawContents(Canvas canvas, int width, int height, Rect pill, float alpha, float inputFactor, boolean drawPill,
                              float pillLeft, float pillRight, float topViewProgress, float topViewHeight, int surface,
                              Rect underKeyboard) {
        final int container = containerOverlay();
        // The reply strip stops where the send and close controls begin; so does the field, unless the send column sits
        // in the tools row (below).
        float columnLeft = pillLeft + fieldSideInset();
        float columnRight = pillRight - fieldSideInset();
        sendSlot.setEmpty();
        final ComposerInlineRest rest = enterView != null ? enterView.getComposerInlineRest() : null;
        if (enterView != null) {
            final int primaryEndInset = enterView.getComposerPrimaryEndInset();
            if (primaryEndInset > 0) {
                // The send column follows LocaleController.isRTL, not the layout direction.
                if (LocaleController.isRTL) {
                    columnLeft = Math.max(columnLeft, enterView.getLeft() + primaryEndInset + dp(FIELD_SEND_GAP));
                } else {
                    columnRight = Math.min(columnRight, enterView.getRight() - primaryEndInset - dp(FIELD_SEND_GAP));
                }
                if (barVisible && drawPill && inputFactor >= 1f) {
                    // The slot is square at the bottom of the column's row, flush with the enter view's send side.
                    final float slot = dp(ChatActivityEnterView.DEFAULT_HEIGHT);
                    final float slotLeft = LocaleController.isRTL ? enterView.getLeft() : enterView.getRight() - slot;
                    // Under both MD3 styles the column sits in the tools row instead, below the pill.
                    final float slotBottom = rest != null
                            ? pill.bottom + toolsInset - dp(rest.primaryColumnBottomDp(ChatActivityEnterView.DEFAULT_HEIGHT))
                            : pill.bottom;
                    sendSlot.set(slotLeft, slotBottom - slot, slotLeft + slot, slotBottom);
                }
            }
        }
        // The field alone follows the fold: at rest it runs between the leading and trailing buttons, open it
        // takes the whole row, the send column having gone down to the tools row. The reply strip keeps its column.
        float fieldLeft = columnLeft;
        float fieldRight = columnRight;
        if (rest != null) {
            final float openLeft = pillLeft + fieldSideInset();
            final float openRight = pillRight - fieldSideInset();
            final int restStart = rest.restFieldStart();
            final int restEnd = rest.restFieldEnd();
            final float restLeft, restRight;
            if (LocaleController.isRTL) {
                restLeft = enterView.getLeft() + restEnd;
                restRight = restStart > 0 ? enterView.getRight() - restStart : openRight;
            } else {
                restLeft = restStart > 0 ? enterView.getLeft() + restStart : openLeft;
                restRight = enterView.getRight() - restEnd;
            }
            final float open = rest.progress();
            fieldLeft = restLeft + (openLeft - restLeft) * open;
            fieldRight = restRight + (openRight - restRight) * open;
        }

        if (drawPill && inputFactor > 0 && topViewHeight > dp(STRIP_INSET * 2)) {
            // Reply, edit, forward and link preview all share this top view, so one strip covers them.
            final float stripAlpha = alpha * inputFactor * topViewProgress;
            rect.set(columnLeft, pill.top + dp(STRIP_INSET), columnRight, pill.top + topViewHeight - dp(STRIP_INSET));
            final float stripRadius = Math.min(stripRadius(), rect.height() / 2f);
            fillPaint.setColor(Theme.multAlpha(container, stripAlpha));
            canvas.drawRoundRect(rect, stripRadius, stripRadius, fillPaint);
            // The accent is its own rounded bar inside the strip, so the strip's corners stay as round as the field's
            // instead of being cut square by a sliver of accent colour.
            final float accentInset = dp(STRIP_ACCENT_INSET);
            rect.set(rect.left + accentInset, rect.top + accentInset, rect.left + accentInset + dp(STRIP_ACCENT), rect.bottom - accentInset);
            fillPaint.setColor(Theme.multAlpha(primaryColor(), stripAlpha));
            canvas.drawRoundRect(rect, rect.width() / 2f, rect.width() / 2f, fillPaint);
        }

        if (drawPill && inputFactor > 0) {
            rect.set(fieldLeft, pill.top + topViewHeight + dp(FIELD_INSET), fieldRight, pill.bottom - dp(FIELD_INSET));
            final float fieldRadius = Math.min(fieldRadius(), rect.height() / 2f);
            fillPaint.setColor(Theme.multAlpha(fieldOverlay(), alpha * inputFactor));
            canvas.drawRoundRect(rect, fieldRadius, fieldRadius, fillPaint);
        }
        if (underKeyboard != null) {
            fillPaint.setColor(Theme.getColor(Theme.key_chat_emojiPanelBackground, resourcesProvider));
            canvas.drawRect(0, underKeyboard.top, width, height, fillPaint);
            if (InterfaceStyleController.panelDividers()) {
                dividerPaint.setColor(outlineVariantColor(surface));
                canvas.drawRect(0, underKeyboard.top, width, underKeyboard.top + Math.max(1, dp(0.66f)), dividerPaint);
            }
        }
    }
    /**
     * The layout editor's stand-in for the island, frosted over the preview's wallpaper when a factory is
     * given. Like the Liquid Glass preview it shows the configured blur whatever the account's blur state.
     */
    public static Drawable previewIsland(@Nullable BlurredBackgroundDrawableViewFactory factory, @Nullable View view) {
        final ComposerMd3Surface surface = new ComposerMd3Surface(null);
        final BlurredBackgroundDrawable frost = factory != null ? factory.create(view, surface.frostProvider()) : null;
        final int radius = surface.docked ? 0 : dp(ISLAND_RADIUS);
        if (frost != null) {
            frost.setRadius(radius);
        }
        return new PreviewDrawable() {
            @Override
            public void draw(@NonNull Canvas canvas) {
                final Rect bounds = getBounds();
                if (frost != null) {
                    frost.setBounds(bounds);
                    frost.draw(canvas);
                } else {
                    surface.island.set(bounds);
                    surface.fillPaint.setColor(surface.surfaceColor());
                    surface.fillPaint.setShadowLayer(dp(SHADOW_RADIUS), 0, surface.docked ? 0 : dp(SHADOW_DY), surface.shadowColor());
                    canvas.drawRoundRect(surface.island, radius, radius, surface.fillPaint);
                    surface.fillPaint.clearShadowLayer();
                }
            }
        };
    }

    /** How far the preview island reaches past the mock input and toolbar, matching the chat. */
    public static int previewPadding() {
        return dp(InterfaceStyleController.dockedComposer() ? DOCKED_ISLAND_PADDING : ISLAND_PADDING);
    }

    /** The layout editor's field, stopping the field-to-send gap short of the send slot, {@code sendReserve} from its end. */
    public static Drawable previewField(int sendReserve) {
        final ComposerMd3Surface surface = new ComposerMd3Surface(null);
        return new PreviewDrawable() {
            @Override
            public void draw(@NonNull Canvas canvas) {
                final Rect bounds = getBounds();
                // Gravity.END puts the preview's send circle on the right even in RTL: the app does not
                // declare supportsRtl.
                surface.rect.set(bounds.left + surface.fieldSideInset(), bounds.top + dp(FIELD_INSET), bounds.right - sendReserve - dp(FIELD_SEND_GAP), bounds.bottom - dp(FIELD_INSET));
                final float radius = Math.min(surface.fieldRadius(), surface.rect.height() / 2f);
                surface.fillPaint.setColor(surface.fieldOverlay());
                canvas.drawRoundRect(surface.rect, radius, radius, surface.fillPaint);
            }
        };
    }

    private abstract static class PreviewDrawable extends Drawable {
        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static float visibility(View view) {
        return view != null && view.getVisibility() == View.VISIBLE ? view.getAlpha() : 0;
    }

    /**
     * The recorded-preview trash fakes its stripes by painting them in the colour behind it, and its resting stripes share
     * those layer names. A frosted MD3 field has no single colour to match, so the stripes take the can's colour instead.
     * Only the chat's own enter view sits on the field; the Stories reply bar records too but has no MD3 surface.
     */
    public static int recordedDeleteStripeColor(Theme.ResourcesProvider resourcesProvider, boolean chatComposer, int stockColor) {
        if (!chatComposer || !InterfaceStyleController.applyComposer()) {
            return stockColor;
        }
        return recordedDeleteIconColor(resourcesProvider, true, stockColor);
    }

    /** The composer's icon tint flattened onto the field over an opaque island, so the can reads like the other composer icons. */
    public static int recordedDeleteIconColor(Theme.ResourcesProvider resourcesProvider, boolean chatComposer, int stockColor) {
        if (!chatComposer || !InterfaceStyleController.applyComposer()) {
            return stockColor;
        }
        final ComposerMd3Surface surface = new ComposerMd3Surface(resourcesProvider);
        return ColorUtils.compositeColors(Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider), ColorUtils.compositeColors(surface.fieldOverlay(), surface.surfaceColor()));
    }

    // The selection bar's buttons are drawn 2dp inside the island, so this is the island radius less that inset,
    // the same rule FIELD_RADIUS follows for the input field's 3dp.
    private static final int ACTION_BUTTON_RADIUS = ISLAND_RADIUS - 2;

    /**
     * With MD3 Buttons on, the selection bar's two buttons become the run's fields, so the island is left untoned
     * around them (see draw). Their fill is the field tone made opaque: a translucent one would show the drawable's
     * own blur of the wallpaper instead of the island. Buttons are rebuilt with the chat when the setting changes.
     */
    private View nestActionButtons(View actionButtons) {
        if (actionButtons instanceof org.telegram.ui.Components.chat.layouts.ChatActivityActionsButtonsLayout && InterfaceStyleController.applyButtons()) {
            final org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider tone = new org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider() {
                @Override
                public int getShadowColor() {
                    return 0;
                }

                @Override
                public int getBackgroundColor() {
                    return ColorUtils.compositeColors(fieldOverlay(), surfaceColor());
                }

                @Override
                public int getStrokeColorTop() {
                    return 0;
                }

                @Override
                public int getStrokeColorBottom() {
                    return 0;
                }
            };
            final org.telegram.ui.Components.chat.layouts.ChatActivityActionsButtonsLayout layout = (org.telegram.ui.Components.chat.layouts.ChatActivityActionsButtonsLayout) actionButtons;
            final int radius = dp(docked ? DOCKED_SHAPE_RADIUS : ACTION_BUTTON_RADIUS);
            ((org.telegram.ui.Components.chat.buttons.ChatActivityBlurredRoundButton) layout.getReplyButton()).setNestedBackground(radius, tone);
            ((org.telegram.ui.Components.chat.buttons.ChatActivityBlurredRoundButton) layout.getForwardButton()).setNestedBackground(radius, tone);
        }
        return actionButtons;
    }
}
