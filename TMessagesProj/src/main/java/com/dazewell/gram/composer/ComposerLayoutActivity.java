package com.dazewell.gram.composer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.SlideIntChooseView;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;
import org.telegram.ui.Components.chat.WallpaperBitmapProvider;

import xyz.nextalone.nagram.NaConfig;
import com.dazewell.gram.helpers.InterfaceStyleController;
import com.dazewell.gram.ui.ComposerToolbarLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lets the user place each composer toolbar button into a zone and order it, by dragging rows
 * between four sections. Config only: it never touches a live enter view, it reads and writes the
 * layout through {@link ComposerLayout} and asks the toolbar to rebuild by posting reloadInterface.
 */
public class ComposerLayoutActivity extends BaseFragment {

    private static final int TYPE_HEADER = 1;
    private static final int TYPE_BUTTON = 2;
    private static final int TYPE_PLACEHOLDER = 3;
    private static final int TYPE_INFO = 4;
    private static final int TYPE_SCALE = 5;
    /**
     * The packing slider gets its own type rather than sharing TYPE_SCALE: one type means the
     * recycler can hand this row a view still carrying the other slider's minValueAllowed and seek
     * bar min progress, which does not look like a stale bind - it silently locks or unlocks the
     * wrong control.
     */
    private static final int TYPE_SPACING = 6;
    /**
     * Title above a slider. Must NOT be TYPE_HEADER: zoneAt() walks back to the nearest TYPE_HEADER
     * to decide which zone a row belongs to, so reusing that type here would make every lookup above
     * the Leading header answer with a slider's sentinel zone.
     */
    private static final int TYPE_SLIDER_HEADER = 7;
    /** Its own type for the same reason TYPE_SPACING has one: no slider row may inherit another's
     * options or floor through recycling. */
    private static final int TYPE_TEXT_SIZE = 8;
    /** The MD3 composer's Floating / Docked select, shown only while the MD3 composer applies. */
    private static final int TYPE_STYLE = 9;
    /** Sentinel "zones" for the slider groups, so one header/footer lookup serves all of them. */
    private static final int GROUP_SCALE = -1;
    private static final int GROUP_SPACING = -2;
    private static final int GROUP_TEXT_SIZE = -3;
    private static final int GROUP_STYLE = -4;

    /** The clamp ChatActivityEnterView.getInputTextSizeDp() applies, and NaConfig.inputTextSize's default. */
    private static final int TEXT_SIZE_MIN = 14;
    private static final int TEXT_SIZE_MAX = 20;
    private static final int TEXT_SIZE_DEFAULT = 18;
    private static final int[] TEXT_SIZE_STEPS = {14, 15, 16, 17, 18, 19, 20};

    private static final int SCALE_MIN = 75;
    private static final int SCALE_MAX = 125;
    private static final int[] SCALE_STEPS = {
            75, 80, 85, 90, 95, 100, 105, 110, 115, 120, 125
    };

    /**
     * The absolute range the stored value is clamped into, and the floor the bind drops the knob to
     * before applying a new one. Not the anchors the slider actually draws - those are built from the
     * scale-dependent floor by {@link #spacingSteps()}, so the track's left endpoint is always a
     * value the thumb can reach.
     *
     * <p>Every reachable value is a whole percent, one apart. This is deliberate
     * and must not be "simplified" back to {85, 90, 95, 100} with SPACING_BETWEEN_STEPS raised to
     * subdivide them: SlideIntChooseView.getProgress does Math.round(...) / options.betweenSteps as
     * an integer division (betweenSteps is int), while getValue divides by (float) betweenSteps, so
     * for betweenSteps > 1 the two stop being inverses and a between-anchor value cannot round-trip.
     * A rebind then drives the thumb to getProgress(value), which truncates down to the lower
     * anchor, while the label keeps the exact value - the thumb and number desync. Unit anchors keep
     * getProgress exact for every value the slider can hold.
     */
    private static final int[] SPACING_STEPS = {
            75, 76, 77, 78, 79, 80, 81, 82, 83, 84,
            85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100
    };

    /**
     * One sub-step, because every anchor above is already a whole percent, so the packing slider
     * delivers whole percents with getProgress and getValue as exact inverses.
     *
     * <p>Must stay 1. With more than one sub-step, getProgress's integer division truncates any
     * value that falls between two anchors down to the lower anchor's progress (see SPACING_STEPS),
     * so the thumb lands left of the label on any rebind, and the floor spacingSteps() puts at the
     * array's left endpoint would no longer land exactly on it. The value range is real, not
     * cosmetic: the cell is round(48 x size x packing), so the rounding boundary falls every couple
     * of percent, giving eleven distinct cell sizes at 100% size and fourteen at 125%.
     */
    private static final int SPACING_BETWEEN_STEPS = 1;

    /** Header text plus the breathing room above and below the capsule, in dp. */
    private static final int PREVIEW_HEADER_HEIGHT = 40;
    private static final int PREVIEW_INPUT_GAP = 2;
    private static final int PREVIEW_INPUT_HEIGHT = 44;
    private static final int PREVIEW_PADDING = 12;
    /** Matches ChatActivityEnterView.COMPOSER_PRIMARY_INSET - the real send button's own background inset
     * inside its DEFAULT_HEIGHT slot, kept in step so the preview's placeholder end-margin lines up with
     * where the real field actually stops next to it. */
    private static final int SEND_BUTTON_INSET_DP = 3;

    private static final int[] PREVIEW_ZONES = {
            ComposerButtons.ZONE_START,
            ComposerButtons.ZONE_MIDDLE,
            ComposerButtons.ZONE_END,
    };

    /** Configured but only conditionally shown in a real chat (draft state, live layout budget,
     * pending scheduled messages) - dimmed in the preview rather than drawn at full strength, since
     * there is no live chat here to decide whether they would actually be visible right now. */
    private static final java.util.Set<String> CONDITIONAL_PREVIEW_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            ComposerButtons.AI, ComposerButtons.RICH, ComposerButtons.EXPAND, ComposerButtons.SCHEDULE));
    /** Alpha component (0-255) baked into the icon's own color filter for a conditional preview
     * button, rather than applied via View.setAlpha() - the real toolbar's CollapsingLinearLayout
     * treats a child below 0.5 View alpha as unoccupied and gives it zero width (see
     * ComposerToolbarLayout.CollapsingLinearLayout.isOccupied()), so setAlpha() here made these
     * buttons vanish instead of just dimming. ~115/255 matches the old 0.45f visual strength. */
    private static final int CONDITIONAL_PREVIEW_COLOR_ALPHA = 115;

    /** One height for the pinned preview and the list's matching top margin, so the two cannot
     * drift apart as the scale changes the capsule's size. */
    private static int previewHeight() {
        return PREVIEW_HEADER_HEIGHT + ComposerToolbarLayout.height() + PREVIEW_INPUT_GAP
                + PREVIEW_INPUT_HEIGHT + PREVIEW_PADDING;
    }

    private static final int reset_id = 1;

    private static final int[] ZONE_ORDER = {
            ComposerButtons.ZONE_START,
            ComposerButtons.ZONE_MIDDLE,
            ComposerButtons.ZONE_END,
            ComposerButtons.ZONE_HIDDEN,
    };

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ItemTouchHelper itemTouchHelper;
    private PreviewCell previewCell;

    private final ArrayList<Item> items = new ArrayList<>();
    private List<List<String>> lastSaved;
    private boolean rebuildPending;
    private boolean startZoneArmed;

    /**
     * A finger is down somewhere on the screen. Set on the root's ACTION_DOWN, cleared on UP/CANCEL
     * (see createView). While it is true the toolbar-size drag defers the packing row's rebind
     * rather than firing it on every step crossed - the packing thumb and its footer stay put until
     * the gesture ends, then settle once.
     */
    private boolean gestureInProgress;
    /**
     * A toolbar-size change arrived mid-gesture and the packing rows still owe a rebind. Cleared and
     * acted on when the gesture ends. No Runnable, no post, nothing to cancel in onFragmentDestroy.
     */
    private boolean settlePending;
    /**
     * The packing slider was moved mid-gesture, so its footer's saved-vs-floor disclosure is stale
     * and owes a refresh at gesture end. Separate from settlePending because this path refreshes
     * only the footer, never the slider row the user is still holding.
     */
    private boolean spacingFooterRefreshPending;

    /**
     * Bumped once per finished theme transition (see getThemeDescriptions()) so getItemViewType()
     * can hand the four slider rows a view type RecyclerView has never bound before. A view-type
     * mismatch between what an already bound/cached/pooled holder reports and what the adapter now
     * returns for that position is exactly what makes RecyclerView treat that holder as unusable and
     * fall through to onCreateViewHolder instead of reusing it - see
     * RecyclerView.Recycler#validateViewHolderForOffsetPosition, which covers the scrap/cache path,
     * not just the shared pool. A real recreation, not a rebind, is required because of where
     * SlideIntChooseView puts its colours: minText and valueText are AnimatedTextView instances,
     * and ThemeDescription#processViewColor has no case for them. This screen still recreates its
     * cached slider holders on theme changes instead of adding per-holder refresh plumbing here.
     * (The row background is not in the same boat - this file paints it in onCreateViewHolder below,
     * and it would be reachable the ordinary way by adding SlideIntChooseView.class to the
     * FLAG_CELLBACKGROUNDCOLOR listClasses further down; it just rides along with the same fix.)
     */
    private int sliderThemeGeneration = 0;
    private static final int SLIDER_TYPE_GENERATION_STRIDE = 1000;

    private static final class Item {
        final int type;
        final int zone;
        final String key;

        Item(int type, int zone, String key) {
            this.type = type;
            this.zone = zone;
            this.key = key;
        }
    }

    @Override
    public boolean onFragmentCreate() {
        lastSaved = ComposerLayout.snapshot();
        buildItems(lastSaved);
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ComposerLayoutTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == reset_id) {
                    resetLayout();
                }
            }
        });

        ActionBarMenuItem other = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(reset_id, R.drawable.msg_reset_solar, LocaleController.getString(R.string.ComposerLayoutReset));

        FrameLayout frameLayout = new FrameLayout(context) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    gestureInProgress = true;
                }
                boolean handled = super.dispatchTouchEvent(ev);
                // Settle after super has run, not before. On ACTION_UP the slider's own terminal
                // setSeekBarDrag fires inside that super call, so settling first would rebind the
                // packing row off a stale value; on ACTION_CANCEL no drag callback fires at all, and
                // the settle just picks up the values earlier ACTION_MOVEs already wrote.
                // This root sees the terminal UP/CANCEL because the DOWN was consumed: a consumed
                // DOWN makes this root the touch target for the rest of the gesture. Every region of
                // the root is covered by a child that consumes - the pinned preview strip above,
                // whose onInterceptTouchEvent/onTouchEvent both return true, and the scrollable list
                // below - so the flag set on DOWN always gets a matching terminal event to clear it.
                // SlideIntChooseView's requestDisallowInterceptTouchEvent only stops the RecyclerView
                // intercepting, which keeps moves flowing to the slider; it is not what delivers the
                // terminal event here.
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    gestureInProgress = false;
                    // Snap every slider thumb onto its value's track position. On release SeekBarView
                    // never canonicalizes the position to a step: its ACTION_UP handler may reposition
                    // thumbX to the raw finger pixel (SeekBarView.java:259-262) before reporting the
                    // released progress through setSeekBarDrag (SeekBarView.java:280), and the only
                    // place thumbX gets rounded at all - onDraw's needVisuallyDivideSteps() branch
                    // (SeekBarView.java:503-505), which this widget hard-codes false - rounds a
                    // draw-local shadow of thumbX (SeekBarView.java:496), never the stored field -
                    // so a thumb dropped between two steps rests off-detent while the label above
                    // already shows the nearest value. Re-binding each slider row drives its thumb to
                    // getProgress(value). Fired for all four rows unconditionally rather than
                    // hit-testing the dragged one: updateVisibleRow skips any holder whose adapter
                    // position no longer matches, so the binds are cheap and a snap racing a rebuild
                    // is a no-op, not a stale bind. Correct only because every options table here uses
                    // betweenSteps == 1 (see docs/codemap/upstream-traps.md); a betweenSteps > 1
                    // slider would be mis-snapped on every touch-up. This also subsumes settlePending's
                    // spacing-row rebind, so that row binds once per gesture end, not twice - only its
                    // footer still needs the deferred refresh.
                    boolean footerNeedsRefresh = settlePending || spacingFooterRefreshPending;
                    settlePending = false;
                    spacingFooterRefreshPending = false;
                    snapSliderRows();
                    if (footerNeedsRefresh) {
                        refreshSpacingFooter();
                    }
                }
                return handled;
            }
        };
        fragmentView = frameLayout;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(listView);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 0, previewHeight(), 0, 0));
        listView.setAdapter(adapter = new ListAdapter(context));
        // Adopt the same rounded grouped-card treatment every other settings page uses. The one-arg
        // overload resolves to setSections(dp(12), dp(16), true) and the default section-exclusion
        // predicate, matching the two fork settings base classes; card boundaries then come purely
        // from where the TextInfoPrivacyCell footers sit, detected live per frame.
        listView.setSections(true);
        actionBar.setAdaptiveBackground(listView);

        // Pinned rather than scrolled with the list: it is the feedback surface for every drag, so
        // it has to stay on screen while the user works down a twenty-row list.
        previewCell = new PreviewCell(context);
        frameLayout.addView(previewCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, previewHeight(), Gravity.TOP | Gravity.LEFT));
        updatePreview();

        return fragmentView;
    }

    private void updatePreview() {
        if (previewCell == null) {
            return;
        }
        previewCell.setLayout(ComposerLayout.normalize(collect()));
        // The capsule grows with the scale, so the pinned preview and the space the list leaves for
        // it are re-applied together - a stale margin would either clip the panel or float the list.
        int height = dp(previewHeight());
        ViewGroup.LayoutParams previewParams = previewCell.getLayoutParams();
        if (previewParams != null && previewParams.height != height) {
            previewParams.height = height;
            previewCell.setLayoutParams(previewParams);
        }
        if (listView != null && listView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams listParams = (FrameLayout.LayoutParams) listView.getLayoutParams();
            if (listParams.topMargin != height) {
                listParams.topMargin = height;
                listView.setLayoutParams(listParams);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        persist();
        flushRebuild();
    }

    @Override
    public void onFragmentDestroy() {
        persist();
        flushRebuild();
        super.onFragmentDestroy();
    }

    private void buildItems(List<List<String>> zones) {
        items.clear();
        // Sits above the zone list so the size controls are the first thing under the preview, rather
        // than something the user only finds after scrolling past twenty draggable rows. Each slider
        // is titled: two unlabelled percentage bars stacked together are indistinguishable, and
        // SlideIntChooseView.setLabel() only feeds the accessibility description, never the screen.
        // The composer's shape comes first: it changes the whole preview, not one part of it. Of the
        // sliders, text size leads because the field sits above the toolbar in the preview.
        if (InterfaceStyleController.applyComposer()) {
            items.add(new Item(TYPE_STYLE, GROUP_STYLE, null));
            items.add(new Item(TYPE_INFO, GROUP_STYLE, null));
        }
        items.add(new Item(TYPE_SLIDER_HEADER, GROUP_TEXT_SIZE, null));
        items.add(new Item(TYPE_TEXT_SIZE, GROUP_TEXT_SIZE, null));
        items.add(new Item(TYPE_INFO, GROUP_TEXT_SIZE, null));
        items.add(new Item(TYPE_SLIDER_HEADER, GROUP_SCALE, null));
        items.add(new Item(TYPE_SCALE, GROUP_SCALE, null));
        items.add(new Item(TYPE_INFO, GROUP_SCALE, null));
        items.add(new Item(TYPE_SLIDER_HEADER, GROUP_SPACING, null));
        items.add(new Item(TYPE_SPACING, GROUP_SPACING, null));
        items.add(new Item(TYPE_INFO, GROUP_SPACING, null));
        for (int zone : ZONE_ORDER) {
            items.add(new Item(TYPE_HEADER, zone, null));
            List<String> keys = zones.get(zone);
            for (String key : keys) {
                items.add(new Item(TYPE_BUTTON, zone, key));
            }
            // Leading never gets a placeholder row, even under capacity: its header already says
            // how many buttons it holds, and its header row itself is a valid drop target, so a
            // dedicated empty-slot row would just be a second way to say the same thing.
            if (zone != ComposerButtons.ZONE_START && keys.isEmpty()) {
                items.add(new Item(TYPE_PLACEHOLDER, zone, null));
            }
            // Every zone is closed by its own footer, which both explains the zone and draws the
            // shadow that separates it from the next one.
            items.add(new Item(TYPE_INFO, zone, null));
        }
    }

    /** Rebuilds the four zone lists from the current flat order, taking each button's zone from the
     * header above it so the model always matches what the user sees. */
    private List<List<String>> collect() {
        List<List<String>> zones = new ArrayList<>(ComposerButtons.ZONE_COUNT);
        for (int i = 0; i < ComposerButtons.ZONE_COUNT; i++) {
            zones.add(new ArrayList<>());
        }
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.type == TYPE_BUTTON) {
                int zone = zoneAt(i);
                if (zone >= 0) {
                    zones.get(zone).add(item.key);
                }
            }
        }
        return zones;
    }

    private int zoneAt(int position) {
        for (int i = position; i >= 0; i--) {
            if (items.get(i).type == TYPE_HEADER) {
                return items.get(i).zone;
            }
        }
        return -1;
    }

    /** Leading's header never moves - it isn't draggable - so its position only changes when the
     * whole list is rebuilt, but looked up fresh rather than cached against that rebuild. */
    private int startHeaderPosition() {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.type == TYPE_HEADER && item.zone == ComposerButtons.ZONE_START) {
                return i;
            }
        }
        return -1;
    }

    /** Counts the button rows directly under a zone's header - relies on Leading (and every zone)
     * laying out as header, then zero or more button rows, then its footer, with no placeholder
     * row breaking up that run any more. */
    private int startZoneButtonCount(int headerPos) {
        int count = 0;
        for (int i = headerPos + 1; i < items.size() && items.get(i).type == TYPE_BUTTON; i++) {
            count++;
        }
        return count;
    }

    private int startZoneOccupancy() {
        int headerPos = startHeaderPosition();
        return headerPos < 0 ? 0 : startZoneButtonCount(headerPos);
    }

    private void setStartZoneArmed(boolean armed) {
        if (startZoneArmed == armed) {
            return;
        }
        startZoneArmed = armed;
        // The header only arms alongside the button rows when Leading still has room - at 2/2 it
        // is not a valid drop target (see canDropOver), so it shows no highlight even while the
        // occupied rows themselves light up for a compatible drag.
        boolean headerArmed = armed && startZoneOccupancy() < ComposerButtons.START_CAPACITY;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            int position = listView.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION || zoneAt(position) != ComposerButtons.ZONE_START) {
                continue;
            }
            if (child instanceof ButtonRowCell) {
                ((ButtonRowCell) child).setArmed(armed, true);
            } else if (child instanceof ArmableHeaderCell) {
                ((ArmableHeaderCell) child).setArmed(headerArmed, true);
            }
        }
    }

    private void persist() {
        List<List<String>> current = ComposerLayout.normalize(collect());
        if (current.equals(lastSaved)) {
            return;
        }
        ComposerLayout.save(current);
        lastSaved = current;
        // The stored layout is the toolbar's only input, so it is written on every drop; the rebuild
        // that picks it up is deferred to leaving the screen, since rebuilding every chat behind the
        // editor after each micro-drag buys nothing the preview row isn't already showing.
        rebuildPending = true;
    }

    private void flushRebuild() {
        if (!rebuildPending) {
            return;
        }
        rebuildPending = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
    }

    private void resetLayout() {
        ComposerLayout.reset();
        NaConfig.INSTANCE.getComposerToolbarScale().setConfigInt(100);
        NaConfig.INSTANCE.getComposerToolbarSpacing().setConfigInt(100);
        NaConfig.INSTANCE.getInputTextSize().setConfigInt(TEXT_SIZE_DEFAULT);
        lastSaved = ComposerLayout.snapshot();
        buildItems(lastSaved);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updatePreview();
        rebuildPending = true;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            // RecyclerListView disables the child view whenever this returns false (see
            // onChildAttachedToWindow), and a disabled view silently swallows setOnClickListener -
            // it never reaches performClick. Button rows need to stay enabled for the Hidden/Middle
            // tap-toggle to fire; everything else (headers, footers, placeholders, the scale slider)
            // has no click behaviour, so it can stay disabled as before.
            final int type = baseType(holder.getItemViewType());
            return type == TYPE_BUTTON || type == TYPE_STYLE;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            int type = items.get(position).type;
            // Shifted so a bumped generation is a view type RecyclerView has never seen before -
            // see sliderThemeGeneration's javadoc for why that's what forces a real recreation.
            // Other row types never change shape across a theme flip, so they're left alone.
            if (isSliderRowType(type)) {
                return type + sliderThemeGeneration * SLIDER_TYPE_GENERATION_STRIDE;
            }
            return type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            // Non-slider types are always below the stride and are never shifted, so this recovers
            // the real type for both a shifted slider view type and an ordinary one unchanged.
            switch (viewType % SLIDER_TYPE_GENERATION_STRIDE) {
                case TYPE_HEADER:
                    view = new ArmableHeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_PLACEHOLDER:
                    view = new PlaceholderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(context);
                    break;
                // Keep in step with isSliderRowType() below - a slider type added here but not
                // there silently skips the theme-refresh fix for it, with no compile error.
                case TYPE_SCALE:
                case TYPE_SPACING:
                case TYPE_TEXT_SIZE:
                    view = new SlideIntChooseView(context, null);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_SLIDER_HEADER:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_STYLE:
                    org.telegram.ui.Cells.TextSettingsCell styleCell = new org.telegram.ui.Cells.TextSettingsCell(context);
                    styleCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    styleCell.setOnClickListener(ComposerLayoutActivity.this::showComposerStyleMenu);
                    view = styleCell;
                    break;
                default:
                    ButtonRowCell cell = new ButtonRowCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    cell.reorderView.setOnTouchListener((v, event) -> {
                        if (event.getAction() == MotionEvent.ACTION_DOWN) {
                            itemTouchHelper.startDrag(listView.getChildViewHolder(cell));
                        }
                        return false;
                    });
                    cell.setOnClickListener(v -> {
                        int position = listView.getChildAdapterPosition(cell);
                        if (position != RecyclerView.NO_POSITION) {
                            toggleHiddenMiddle(position);
                        }
                    });
                    view = cell;
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Item item = items.get(position);
            switch (item.type) {
                case TYPE_HEADER:
                    ArmableHeaderCell headerCell = (ArmableHeaderCell) holder.itemView;
                    headerCell.setText(headerTitle(item.zone));
                    // Only Leading's header is ever a drop target, and only while it has room -
                    // every other header's arm state stays permanently false.
                    headerCell.setArmed(item.zone == ComposerButtons.ZONE_START && startZoneArmed
                            && startZoneOccupancy() < ComposerButtons.START_CAPACITY, false);
                    break;
                case TYPE_PLACEHOLDER:
                    // Only Middle/Trailing/Hidden ever produce this row now - Leading never does,
                    // regardless of how many of its two slots are filled (see buildItems()).
                    ((PlaceholderCell) holder.itemView).textView.setText(LocaleController.getString(R.string.ComposerZoneEmpty));
                    break;
                case TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(footerText(item.zone));
                    break;
                case TYPE_SCALE:
                    SlideIntChooseView scaleView = (SlideIntChooseView) holder.itemView;
                    scaleView.setLabel(LocaleController.getString(R.string.ComposerScaleAccDescr));
                    scaleView.set(currentScale(), scaleOptions(), value -> {
                        if (value == NaConfig.INSTANCE.getComposerToolbarScale().Int()) {
                            return;
                        }
                        NaConfig.INSTANCE.getComposerToolbarScale().setConfigInt(value);
                        rebuildPending = true;
                        // How tight the icons can be packed depends on the scale - a smaller row
                        // reaches the cell floor sooner - so the other slider's allowed range has to
                        // follow this one. Defer that rebind to the end of the gesture instead of
                        // firing it on every step crossed: rebinding the packing row mid-drag is what
                        // made its thumb and footer jump under the user's finger. The else branch is
                        // load-bearing, not a guard - the accessibility delegate drives this slider
                        // with no touch events at all, so a TalkBack change would never settle
                        // otherwise, and it steps one value at a time so it has no jump to defer.
                        if (gestureInProgress) {
                            settlePending = true;
                        } else {
                            settleSpacingRows();
                        }
                        updatePreview();
                    });
                    // Cleared to the options' own minimum rather than Integer.MIN_VALUE: getProgress
                    // subtracts the minimum before dividing, and MIN_VALUE - 75 overflows to a large
                    // positive int, so clamp01 returns 1.0 and setMinProgress pins this slider at its
                    // maximum. SCALE_MIN maps to progress 0, which is the no-op floor that was meant.
                    // Needed at all only as insurance: this row and the packing row are built by the
                    // same branch above, so a future edit that let them share a recycler pool would
                    // otherwise inherit that row's floor.
                    scaleView.setMinValueAllowed(SCALE_MIN);
                    break;
                case TYPE_SPACING:
                    SlideIntChooseView spacingView = (SlideIntChooseView) holder.itemView;
                    spacingView.setLabel(LocaleController.getString(R.string.ComposerSpacingAccDescr));
                    // Floor down, then apply, then floor up. set() below drives the seek bar to the
                    // value's progress, and SeekBarView clamps that progress up against whatever floor
                    // the previous bind left behind - so when the toolbar just grew and the floor fell,
                    // applying the value against the old higher floor strands the thumb to the right of
                    // a label that already moved. Dropping the allowed minimum to the slider's own floor
                    // first makes set() land unclamped.
                    spacingView.setMinValueAllowed(SPACING_STEPS[0]);
                    spacingView.set(currentSpacing(), spacingOptions(), value -> {
                        if (value == NaConfig.INSTANCE.getComposerToolbarSpacing().Int()) {
                            return;
                        }
                        NaConfig.INSTANCE.getComposerToolbarSpacing().setConfigInt(value);
                        rebuildPending = true;
                        // This write can flip what the footer discloses: a clamped write destroys the
                        // saved value the Overridden text was promising would come back, so the footer
                        // has to re-evaluate saved-vs-floor or it keeps promising a value that no
                        // longer exists. Defer to gesture end like the scale path so the text does not
                        // change under the finger; the else branch covers the accessibility delegate,
                        // which drives this with no touch events and one discrete step at a time. This
                        // flag now defers only the footer - the thumb itself is snapped to its value at
                        // gesture end by snapSliderRows(), which is intended: under unit anchors
                        // (betweenSteps == 1) the nearest detent is exactly the value the label shows.
                        if (gestureInProgress) {
                            spacingFooterRefreshPending = true;
                        } else {
                            refreshSpacingFooter();
                        }
                        updatePreview();
                    });
                    // Raise to the real floor after the value has landed. Under the anchors
                    // spacingSteps() builds this normally equals the array's own minimum, so it
                    // clamps a genuinely-below-floor saved value up and draws no band; the one case
                    // it does draw a band is the locked state, where the array keeps a spare anchor
                    // below a floor of 100. A falling floor must be lowered before set() (above) or
                    // the thumb strands; a rising floor must be applied after or a value below it
                    // would not clamp up. On the first bind of a freshly created view the pre-call
                    // no-ops while options is still null, so this call is also what makes the floor
                    // stick at all.
                    spacingView.setMinValueAllowed(spacingFloor());
                    break;
                case TYPE_TEXT_SIZE:
                    SlideIntChooseView textSizeView = (SlideIntChooseView) holder.itemView;
                    textSizeView.setLabel(LocaleController.getString(R.string.InputTextSize));
                    textSizeView.set(currentTextSize(), textSizeOptions(), value -> {
                        if (value == NaConfig.INSTANCE.getInputTextSize().Int()) {
                            return;
                        }
                        // No toolbar rebuild: an open chat re-reads this in ChatActivityEnterView.onResume.
                        NaConfig.INSTANCE.getInputTextSize().setConfigInt(value);
                        updatePreview();
                    });
                    textSizeView.setMinValueAllowed(TEXT_SIZE_MIN);
                    break;
                case TYPE_SLIDER_HEADER:
                    ((HeaderCell) holder.itemView).setText(LocaleController.getString(sliderHeaderText(item.zone)));
                    break;
                case TYPE_STYLE:
                    ((org.telegram.ui.Cells.TextSettingsCell) holder.itemView).setTextAndValue(
                            LocaleController.getString(R.string.ComposerStyle), composerStyleName(InterfaceStyleController.composerStyle()), false);
                    break;
                default:
                    ComposerButtons.Button button = ComposerButtons.get(item.key);
                    ButtonRowCell cell = (ButtonRowCell) holder.itemView;
                    cell.setButton(button, position + 1 < items.size() && items.get(position + 1).type == TYPE_BUTTON);
                    cell.setArmed(item.zone == ComposerButtons.ZONE_START && startZoneArmed, false);
                    break;
            }
        }
    }

    private static CharSequence headerTitle(int zone) {
        switch (zone) {
            case ComposerButtons.ZONE_START:
                return LocaleController.getString(R.string.ComposerZoneLeading);
            case ComposerButtons.ZONE_MIDDLE:
                return LocaleController.getString(R.string.ComposerZoneScrolling);
            case ComposerButtons.ZONE_END:
                return LocaleController.getString(R.string.ComposerZoneTrailing);
            default:
                return LocaleController.getString(R.string.ComposerZoneHidden);
        }
    }

    private static CharSequence footerText(int zone) {
        switch (zone) {
            case GROUP_SCALE:
                return LocaleController.getString(R.string.ComposerScaleInfo);
            case GROUP_SPACING:
                return spacingFooterText();
            case GROUP_TEXT_SIZE:
                return LocaleController.getString(R.string.InputTextSizeInfo);
            case GROUP_STYLE:
                return LocaleController.getString(R.string.ComposerStyleInfo);
            case ComposerButtons.ZONE_START:
                return LocaleController.getString(R.string.ComposerZoneLeadingInfo);
            case ComposerButtons.ZONE_MIDDLE:
                return LocaleController.getString(R.string.ComposerZoneScrollingInfo);
            case ComposerButtons.ZONE_END:
                return LocaleController.getString(R.string.ComposerZoneTrailingInfo);
            default:
                return LocaleController.getString(R.string.ComposerLayoutInfo);
        }
    }

    /**
     * The packing footer says one of a few things depending on how the saved value sits against the
     * scale-dependent floor, so a user reads which state they are in before touching the slider that
     * would overwrite a hidden value. Overridden wins whenever a saved value is hidden - telling the
     * user it still exists and will return is worth more than the "no room" line, which is reserved
     * for the case where nothing is hidden and the slider genuinely cannot move. When the floor has
     * reached the top of the range the hidden value is still disclosed, but without the "moving the
     * slider replaces it" warning: every candidate there clamps to the top, so the slider cannot
     * write and nothing can replace the saved value.
     *
     * The saved value is clamped into the slider's expressible range before it is compared or
     * printed: the number shown as "your saved X%" has to be the value that would actually be
     * restored, and spacingPercent() clamps to this same range, so a stored integer below the
     * minimum reads back as the minimum rather than as itself. Printing the raw integer would name a
     * value that can never return at any toolbar size and promise a comeback that cannot happen.
     */
    private static CharSequence spacingFooterText() {
        int min = SPACING_STEPS[0];
        int top = SPACING_STEPS[SPACING_STEPS.length - 1];
        int saved = Math.max(min, Math.min(top, NaConfig.INSTANCE.getComposerToolbarSpacing().Int()));
        int floor = spacingFloor();
        if (saved < floor) {
            int res = floor >= top
                    ? R.string.ComposerSpacingInfoOverriddenLocked
                    : R.string.ComposerSpacingInfoOverridden;
            return LocaleController.formatString(res, saved);
        }
        if (floor >= top) {
            return LocaleController.getString(R.string.ComposerSpacingInfoNoRoom);
        }
        return LocaleController.getString(R.string.ComposerSpacingInfo);
    }

    /** One lookup for every TYPE_SLIDER_HEADER row - a binary ternary worked while there were only
     * two slider groups, but stopped being able to tell four apart. */
    private static int sliderHeaderText(int zone) {
        switch (zone) {
            case GROUP_SPACING:
                return R.string.ComposerSpacing;
            case GROUP_TEXT_SIZE:
                return R.string.InputTextSize;
            default:
                return R.string.ComposerScale;
        }
    }

    private static String composerStyleName(int style) {
        return LocaleController.getString(style == InterfaceStyleController.COMPOSER_STYLE_DOCKED
                ? R.string.ComposerStyleDocked : R.string.ComposerStyleFloating);
    }

    /** The same popup a settings select box opens. The open chats pick the style up through the rebuild on leaving. */
    private void showComposerStyleMenu(View anchor) {
        final int[] styles = {InterfaceStyleController.COMPOSER_STYLE_FLOATING, InterfaceStyleController.COMPOSER_STYLE_DOCKED};
        final String[] names = {composerStyleName(styles[0]), composerStyleName(styles[1])};
        final tw.nekomimi.nekogram.ui.PopupBuilder builder = new tw.nekomimi.nekogram.ui.PopupBuilder(anchor);
        builder.setItems(names, (index, name) -> {
            final int style = styles[index];
            if (style != InterfaceStyleController.composerStyle()) {
                NaConfig.INSTANCE.getComposerLayoutStyle().setConfigInt(style);
                rebuildPending = true;
                final int position = rowPosition(TYPE_STYLE, GROUP_STYLE);
                if (adapter != null && position >= 0) {
                    adapter.notifyItemChanged(position);
                }
                // The preview's island and field are built for one style, so they are rebuilt like on a theme change.
                if (previewCell != null) {
                    previewCell.applyTheme();
                }
            }
            return kotlin.Unit.INSTANCE;
        });
        builder.show();
    }

    private static final long START_ZONE_SWAP_DWELL_MS = 350;

    private int pendingStartSwapFrom = RecyclerView.NO_POSITION;
    private int pendingStartSwapTo = RecyclerView.NO_POSITION;
    private long pendingStartSwapSince;

    /** Tap shortcut for Hidden <-> Scrolling: dragging across a long Hidden list is fiddly, so a plain
     * tap flips a row to the other side without needing to reach the row's spot at all. Start and End
     * stay drag-only - Start is capacity constrained and gates an incoming drop behind a dwell, End is
     * left drag-only so this shortcut stays focused on the common Hidden <-> Middle move. */
    private void toggleHiddenMiddle(int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        Item item = items.get(position);
        if (item.type != TYPE_BUTTON) {
            return;
        }
        int zone = zoneAt(position);
        if (zone != ComposerButtons.ZONE_MIDDLE && zone != ComposerButtons.ZONE_HIDDEN) {
            return;
        }
        ComposerButtons.Button button = ComposerButtons.get(item.key);
        int targetZone = zone == ComposerButtons.ZONE_HIDDEN ? ComposerButtons.ZONE_MIDDLE : ComposerButtons.ZONE_HIDDEN;
        if (button == null || !button.canSitIn(targetZone)) {
            return;
        }
        List<List<String>> zones = collect();
        zones.get(zone).remove(item.key);
        // Appended at the end of the target zone: a sensible, predictable default rather than trying
        // to guess where in the middle of an existing order a tapped button "should" go.
        zones.get(targetZone).add(item.key);
        buildItems(zones);
        adapter.notifyDataSetChanged();
        persist();
        updatePreview();
    }

    private void clearPendingStartSwap() {
        pendingStartSwapFrom = RecyclerView.NO_POSITION;
        pendingStartSwapTo = RecyclerView.NO_POSITION;
    }

    private class TouchHelperCallback extends ItemTouchHelper.Callback {

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            if (baseType(viewHolder.getItemViewType()) != TYPE_BUTTON) {
                return makeMovementFlags(0, 0);
            }
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean canDropOver(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder current, @NonNull RecyclerView.ViewHolder target) {
            int type = baseType(target.getItemViewType());
            if (type == TYPE_BUTTON || type == TYPE_PLACEHOLDER) {
                return true;
            }
            if (type != TYPE_HEADER) {
                return false;
            }
            int targetPosition = target.getAdapterPosition();
            int currentPosition = current.getAdapterPosition();
            if (targetPosition == RecyclerView.NO_POSITION || currentPosition == RecyclerView.NO_POSITION) {
                return false;
            }
            // The only header that is ever a valid target is Leading's own, and only while it has
            // spare capacity - inserting AT a header's own index, rather than below it, is what
            // stranded a row above every header and made collect() silently drop it (see onMove),
            // so a full Leading or any other header just isn't a target at all.
            if (zoneAt(targetPosition) != ComposerButtons.ZONE_START) {
                return false;
            }
            if (zoneAt(currentPosition) == ComposerButtons.ZONE_START) {
                // Reordering Leading's own two rows against each other stays a row-to-row
                // operation - the header is only ever a landing spot for a button arriving
                // from outside Leading.
                return false;
            }
            return startZoneOccupancy() < ComposerButtons.START_CAPACITY;
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder source, RecyclerView.ViewHolder target) {
            int from = source.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                return false;
            }
            Item dragged = items.get(from);
            if (dragged.type != TYPE_BUTTON) {
                return false;
            }
            int targetType = items.get(to).type;
            int targetZone = zoneAt(to);
            if (targetZone < 0) {
                return false;
            }
            boolean headerTarget = targetType == TYPE_HEADER && targetZone == ComposerButtons.ZONE_START;
            if (targetType != TYPE_BUTTON && targetType != TYPE_PLACEHOLDER && !headerTarget) {
                return false;
            }
            ComposerButtons.Button button = ComposerButtons.get(dragged.key);
            if (button == null || !button.canSitIn(targetZone)) {
                return false;
            }
            int sourceZone = zoneAt(from);
            // A button arriving in Leading from anywhere else either swaps with one of its two
            // occupied rows or fills a free slot - landing on the header, on an occupied row while
            // under capacity, and on an occupied row at capacity are all just different ways to hit
            // the same fill-or-swap decision below, gated by the same dwell. Reordering the Leading
            // rows against each other (source already in Leading) skips it and falls through to the
            // plain move: autoscroll only ever carries a row INTO Leading from outside, never
            // between rows already there.
            if (targetZone == ComposerButtons.ZONE_START && sourceZone != ComposerButtons.ZONE_START) {
                int headerPos = startHeaderPosition();
                int occupancy = headerPos < 0 ? 0 : startZoneButtonCount(headerPos);
                boolean willSwap = occupancy >= ComposerButtons.START_CAPACITY;
                if (willSwap) {
                    if (targetType != TYPE_BUTTON) {
                        // canDropOver keeps a full Leading's header from arming, so this should be
                        // unreachable - bail rather than fall through to an insert if it happens.
                        return false;
                    }
                    ComposerButtons.Button incumbent = ComposerButtons.get(items.get(to).key);
                    if (incumbent == null || !incumbent.canSitIn(sourceZone)) {
                        return false;
                    }
                }
                // Autoscroll carries the dragged row over Leading just passing through on the way to
                // a zone further up the list, and onMove fires on every one of those scroll ticks, so
                // committing on the first hit turned "scrolling past the top" into an accidental drop.
                // The header is the topmost Leading row and the strongest autoscroll magnet of all -
                // it needs this dwell at least as much as an occupied row does, so every drop into
                // Leading from outside goes through the same gate regardless of which row it lands on.
                long now = SystemClock.uptimeMillis();
                if (pendingStartSwapFrom != from || pendingStartSwapTo != to) {
                    pendingStartSwapFrom = from;
                    pendingStartSwapTo = to;
                    pendingStartSwapSince = now;
                    return false;
                }
                if (now - pendingStartSwapSince < START_ZONE_SWAP_DWELL_MS) {
                    return false;
                }
                clearPendingStartSwap();
                if (willSwap) {
                    Collections.swap(items, from, to);
                    adapter.notifyItemMoved(from, to);
                    adapter.notifyItemMoved(to > from ? to - 1 : to + 1, from);
                } else {
                    // Fill: whichever Leading row (or the header) was actually hit, the incoming
                    // button always lands after whatever is already there and nothing already
                    // there moves. Computed as a real index below the header, never the header's
                    // own index - inserting there would land the row ABOVE the header, where
                    // zoneAt() finds no header above it, returns -1, and collect() silently drops
                    // the button from the saved layout instead of placing it.
                    int insertIndex = headerPos + 1 + occupancy;
                    items.add(insertIndex, items.remove(from));
                    adapter.notifyItemMoved(from, insertIndex);
                }
                updatePreview();
                return true;
            }
            clearPendingStartSwap();
            items.add(to, items.remove(from));
            adapter.notifyItemMoved(from, to);
            updatePreview();
            return true;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                listView.cancelClickRunnables(false);
                if (viewHolder != null) {
                    viewHolder.itemView.setPressed(true);
                    // During a drag, make RecyclerListView.top()/bottom() read this row's settled
                    // layout bounds (getTop()/getBottom()) instead of its translation-inclusive
                    // getY(), so the section card stays anchored to the row's slot while
                    // ItemTouchHelper translates the lifted view. Cleared in clearView so post-drop
                    // move/fling/settle animations use getY() again; a tag left set would freeze the
                    // card edge at the layout position. clearView is the only place it can be
                    // cleared, since this callback gets viewHolder == null on the transition to idle.
                    viewHolder.itemView.setTag(R.id.dragging, true);
                }
            }
            boolean arm = false;
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                int position = viewHolder.getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    ComposerButtons.Button button = ComposerButtons.get(items.get(position).key);
                    // Every configurable button can land in Leading now; its 2-slot capacity gate is
                    // enforced by canDropOver/setStartZoneArmed rather than by button type.
                    arm = button != null && button.canSitIn(ComposerButtons.ZONE_START);
                }
            }
            setStartZoneArmed(arm);
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setPressed(false);
            viewHolder.itemView.setTag(R.id.dragging, null);
            setStartZoneArmed(false);
            clearPendingStartSwap();
            // A cross-section move can empty the source zone (Middle/Trailing/Hidden need a fresh
            // placeholder row for that) or leave Leading under its two-slot capacity, which needs
            // no row at all. Persist takes the flat order as truth, then a rebuild regenerates
            // every zone's rows from that order rather than patching the raw item list in place.
            persist();
            buildItems(lastSaved);
            adapter.notifyDataSetChanged();
            updatePreview();
        }
    }

    private static SlideIntChooseView.Options scaleOptions() {
        return SlideIntChooseView.Options.make(0, SCALE_STEPS, 1,
                (type, value) -> value + "%");
    }

    private static SlideIntChooseView.Options textSizeOptions() {
        return SlideIntChooseView.Options.make(0, TEXT_SIZE_STEPS, 1,
                (type, value) -> String.valueOf(value));
    }

    private static int currentTextSize() {
        return Math.max(TEXT_SIZE_MIN, Math.min(TEXT_SIZE_MAX, NaConfig.INSTANCE.getInputTextSize().Int()));
    }

    private static int currentScale() {
        int percent = NaConfig.INSTANCE.getComposerToolbarScale().Int();
        return Math.max(SCALE_MIN, Math.min(SCALE_MAX, percent));
    }

    private static SlideIntChooseView.Options spacingOptions() {
        return SlideIntChooseView.Options.make(0, spacingSteps(), SPACING_BETWEEN_STEPS,
                // The left endpoint (type == -1) is handed the step array's own minimum, which
                // spacingSteps() builds to be the floor - except in the locked state, where it backs
                // off one step to keep two anchors. Substituting the floor covers that case, so the
                // label always prints the leftmost value the thumb can actually hold.
                (type, value) -> (type == -1 ? spacingFloor() : value) + "%");
    }

    /**
     * The anchors the packing slider draws, from the floor at the current toolbar size up to 100.
     *
     * <p>Built per bind rather than taken from {@link #SPACING_STEPS} because the floor moves with
     * the toolbar size. A fixed array that starts below the floor leaves a stretch of track the
     * thumb can never enter - SlideIntChooseView.setMinValueAllowed draws it as a dimmed band, which
     * at the default toolbar size was four steps wide and read as a jammed slider rather than as a
     * disabled range, especially against a left-endpoint label that already printed the floor.
     *
     * <p>The floor is held one below the top so the array always has at least two entries. At the
     * toolbar size where packing cannot move at all the floor comes back as 100, and a single-entry
     * array would send SlideIntChooseView.getProgress down its no-interval fallback and divide by a
     * zero range. Two entries keep that arithmetic sane; setMinValueAllowed still pins the thumb to
     * the top, and the footer says why it cannot move.
     */
    private static int[] spacingSteps() {
        int top = SPACING_STEPS[SPACING_STEPS.length - 1];
        int floor = Math.max(SPACING_STEPS[0], Math.min(spacingFloor(), top - 1));
        int[] steps = new int[top - floor + 1];
        for (int i = 0; i < steps.length; i++) {
            steps[i] = floor + i;
        }
        return steps;
    }

    /**
     * One place for the scale-dependent packing floor, read both by the knob's clamp above and by
     * the label lambda here - the bug this fixes was those two agreeing by coincidence rather than
     * by construction.
     */
    private static int spacingFloor() {
        return ComposerToolbarLayout.lowestUsableSpacing(currentScale());
    }

    /**
     * Read through the toolbar rather than re-derived here, so the number on the slider and the row
     * it draws come from one rule instead of two that have to be kept in step.
     */
    private static int currentSpacing() {
        return ComposerToolbarLayout.spacingPercent();
    }

    /** Rebinds the packing slider and its footer once, together, for a toolbar-size change that never
     * goes through a gesture at all - the accessibility branch of the TYPE_SCALE bind, which drives
     * the scale slider one discrete step at a time with no touch events to defer against, so this
     * settle fires immediately instead of waiting for a gesture end that will never arrive. A direct
     * onBindViewHolder on the two attached children, not a notify - it rebuilds the anchor array at
     * the new floor, moves the min label, and when the saved value is below that floor the thumb and
     * printed value too, without
     * a change animation in the middle of the list, and it refreshes the footer so its three-way
     * disclosure never goes stale. */
    private void settleSpacingRows() {
        AndroidUtilities.updateVisibleRow(listView, rowPosition(TYPE_SPACING, GROUP_SPACING));
        refreshSpacingFooter();
    }

    /** Snaps every slider thumb to its value's canonical track position, called once at gesture end
     * (see the root's dispatchTouchEvent). This is the whole point of the settle: SeekBarView leaves
     * thumbX at the raw finger pixel on release rather than re-deriving it, so a thumb dragged and let
     * go inside one step's band sits off the detent its label already names. Re-running each slider
     * row's bind path drives its thumb to getProgress(value). Scans items by type rather than the
     * attached children so an off-screen slider is skipped for free, and never runs mid-gesture - the
     * settlePending / spacingFooterRefreshPending deferrals exist so a row is never rebound under the
     * finger. */
    private void snapSliderRows() {
        for (int i = 0; i < items.size(); i++) {
            if (isSliderRowType(items.get(i).type)) {
                AndroidUtilities.updateVisibleRow(listView, i);
            }
        }
    }

    /** Rebinds only the packing footer, so its three-way saved-vs-floor disclosure catches up with a
     * config change without touching the slider row. Shared by the scale settle (floor moved) and the
     * spacing write (saved value moved) - both can flip which of the three strings is true. */
    private void refreshSpacingFooter() {
        AndroidUtilities.updateVisibleRow(listView, rowPosition(TYPE_INFO, GROUP_SPACING));
    }

    /** Position of the one row with this type in this group, or NO_POSITION. Both the packing slider
     * and its footer are found through here so the two scanners cannot drift apart. */
    private int rowPosition(int type, int zone) {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.type == type && item.zone == zone) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    /**
     * A {@link HeaderCell} that can also show the same drag-armed wash {@link ButtonRowCell} rows
     * use. Only Leading's header is ever actually armed (see setStartZoneArmed) - every other
     * header just never calls setArmed(true), so it draws exactly like a plain HeaderCell.
     */
    private static class ArmableHeaderCell extends HeaderCell {

        private float armProgress;
        private ValueAnimator armAnimator;
        private final Paint armPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ArmableHeaderCell(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void setArmed(boolean armed, boolean animated) {
            float target = armed ? 1f : 0f;
            if (armAnimator != null) {
                armAnimator.cancel();
                armAnimator = null;
            }
            if (!animated) {
                armProgress = target;
                invalidate();
                return;
            }
            armAnimator = ValueAnimator.ofFloat(armProgress, target);
            armAnimator.setDuration(150);
            armAnimator.addUpdateListener(a -> {
                armProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            armAnimator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (armAnimator != null) {
                armAnimator.cancel();
                armAnimator = null;
            }
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (armProgress > 0) {
                int wash = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
                armPaint.setColor(ColorUtils.setAlphaComponent(wash, (int) (26 * armProgress)));
                canvas.drawRect(0, 0, getWidth(), getHeight(), armPaint);
            }
            super.onDraw(canvas);
        }
    }

    private static class PlaceholderCell extends FrameLayout {

        final TextView textView;

        PlaceholderCell(Context context) {
            super(context);
            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            textView.setTextSize(15);
            textView.setGravity(Gravity.CENTER);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 22, 0, 22, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
        }
    }

    private static class ButtonRowCell extends FrameLayout {

        final ImageView iconView;
        final SimpleTextView titleView;
        final ImageView reorderView;
        private boolean needDivider;
        private float armProgress;
        private ValueAnimator armAnimator;
        private final Paint armPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        /** Marks the row as the live drop target while a compatible button is being dragged. */
        void setArmed(boolean armed, boolean animated) {
            float target = armed ? 1f : 0f;
            if (armAnimator != null) {
                armAnimator.cancel();
                armAnimator = null;
            }
            if (!animated) {
                armProgress = target;
                invalidate();
                return;
            }
            armAnimator = ValueAnimator.ofFloat(armProgress, target);
            armAnimator.setDuration(150);
            armAnimator.addUpdateListener(a -> {
                armProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            armAnimator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (armAnimator != null) {
                armAnimator.cancel();
                armAnimator = null;
            }
            super.onDetachedFromWindow();
        }

        ButtonRowCell(Context context) {
            super(context);
            setWillNotDraw(false);

            boolean rtl = LocaleController.isRTL;

            iconView = new ImageView(context);
            // FIT_CENTER, not CENTER: a couple of the registry drawables are authored much smaller
            // than 24dp and CENTER would draw them at their intrinsic size, which is why the AI
            // star arrived as a speck.
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            addView(iconView, LayoutHelper.createFrame(24, 24, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 20, 0, 20, 0));

            titleView = new SimpleTextView(context);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setTextSize(16);
            titleView.setMaxLines(1);
            titleView.setGravity((rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            // Centred against the row rather than pinned to a top offset, so the title, the icon and
            // the drag handle all sit on one line no matter what the row contains.
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, rtl ? 56 : 64, 0, rtl ? 64 : 56, 0));

            reorderView = new ImageView(context);
            reorderView.setScaleType(ImageView.ScaleType.CENTER);
            reorderView.setImageResource(R.drawable.list_reorder);
            reorderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.SRC_IN));
            reorderView.setContentDescription(LocaleController.getString(R.string.FilterReorder));
            reorderView.setClickable(true);
            addView(reorderView, LayoutHelper.createFrame(48, 48, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 6, 0, 6, 0));
        }

        void setButton(ComposerButtons.Button button, boolean divider) {
            needDivider = divider;
            if (button == null) {
                iconView.setVisibility(INVISIBLE);
                iconView.setScaleX(1f);
                iconView.setScaleY(1f);
                titleView.setText("");
                return;
            }
            if (button.iconRes != 0) {
                iconView.setVisibility(VISIBLE);
                iconView.setImageResource(button.iconRes);
            } else {
                iconView.setVisibility(INVISIBLE);
            }
            // Reset on every bind because these rows are recycled. The preview draws the registry's
            // named asset, so it takes the same optical scale the live toolbar uses - except for the
            // two buttons that compose their glyph at runtime, where the toolbar's number was measured
            // against geometry this row never shows. previewIconScale owns that one divergence.
            float previewScale = ComposerButtons.previewIconScale(button.key);
            iconView.setScaleX(previewScale);
            iconView.setScaleY(previewScale);
            titleView.setText(LocaleController.getString(button.titleRes));
        }

        void applyTheme() {
            iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            reorderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.SRC_IN));
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (armProgress > 0) {
                int wash = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
                armPaint.setColor(ColorUtils.setAlphaComponent(wash, (int) (26 * armProgress)));
                canvas.drawRect(0, 0, getWidth(), getHeight(), armPaint);
            }
            if (needDivider) {
                canvas.drawLine(dp(LocaleController.isRTL ? 0 : 64), getHeight() - dp(1), getWidth() - dp(LocaleController.isRTL ? 64 : 0), getHeight() - dp(1), Theme.dividerPaint);
            }
        }
    }

    /**
     * Shows the arrangement being edited as the real thing: an actual {@link ComposerToolbarLayout}
     * with its glass capsule, on the user's chat wallpaper, at the size the scale slider is set to.
     * A schematic row of flat icons could not show what the settings on this screen actually do.
     */
    private static class PreviewCell extends FrameLayout {

        private final TextView header;
        private final FrameLayout stage;
        private final Drawable shadowDrawable;
        private final WallpaperBitmapProvider wallpaperProvider = new WallpaperBitmapProvider();
        private ComposerToolbarLayout toolbar;
        private List<List<String>> zones;
        private Drawable backgroundDrawable;
        private BlurredBackgroundSourceBitmap glassSource;
        /** Retained so addMockInput can glass the placeholder pill with the same wallpaper sample the
         * toolbar bubbles use, rather than the flat GradientDrawable it painted before. */
        private BlurredBackgroundDrawableViewFactory glassFactory;
        private Drawable md3Island;

        PreviewCell(Context context) {
            super(context);
            setWillNotDraw(false);

            shadowDrawable = Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow);

            header = new TextView(context);
            header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
            header.setTypeface(AndroidUtilities.bold());
            header.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            header.setText(LocaleController.getString(R.string.ComposerPreviewHeader));
            addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 22, 13, 22, 0));

            stage = new FrameLayout(context);
            stage.setClipChildren(false);
            addView(stage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 8, PREVIEW_HEADER_HEIGHT, 8, 0));
        }

        /**
         * Rebuilt rather than resized whenever the layout or the scale changes: the toolbar bakes its
         * row height into its slots' layout params and its glass radius when the glass is attached,
         * on the premise that a panel keeps one height for its whole life. Resizing in place would
         * break that premise and the bounds animator with it.
         */
        void setLayout(List<List<String>> zones) {
            this.zones = zones;
            header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            stage.removeAllViews();

            toolbar = new ComposerToolbarLayout(getContext());
            // Fed the wallpaper as a static source rather than the chat's render node blur: there is
            // no live chat behind this row to sample from, but the capsule still has to be drawn over
            // the same backdrop it is composited against, or its translucency reads wrong.
            attachGlass(Theme.getCachedWallpaperNonBlocking());

            for (int zone : PREVIEW_ZONES) {
                List<String> keys = zones.get(zone);
                for (int order = 0; order < keys.size(); order++) {
                    String key = keys.get(order);
                    ComposerButtons.Button button = ComposerButtons.get(key);
                    if (button == null || button.iconRes == 0) {
                        continue;
                    }
                    ImageView icon = new ImageView(getContext());
                    icon.setImageResource(button.iconRes);
                    int iconColor = Theme.getColor(Theme.key_chat_messagePanelIcons);
                    // AI, Rich draft, Expand and Schedule's own pin only actually show in a real chat
                    // once its live state allows them (draft length/content, a measured expand budget,
                    // pending scheduled messages) - there is no live chat here to evaluate that against,
                    // so dim them the same way a disabled format button dims, rather than implying they
                    // are always on the row like Emoji or Attach are. Dimmed via the color filter's own
                    // alpha, not View.setAlpha() - the toolbar's CollapsingLinearLayout drops any child
                    // under 0.5 View alpha from measurement entirely (isOccupied()), so setAlpha() here
                    // made the button disappear rather than merely dim.
                    if (CONDITIONAL_PREVIEW_KEYS.contains(key)) {
                        iconColor = ColorUtils.setAlphaComponent(iconColor, CONDITIONAL_PREVIEW_COLOR_ALPHA);
                    }
                    icon.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
                    toolbar.addConfigurable(key, icon, zone, order);
                }
            }
            addMockInput();
            stage.addView(toolbar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, ComposerToolbarLayout.height(),
                    Gravity.TOP | Gravity.START, 0, PREVIEW_INPUT_HEIGHT + PREVIEW_INPUT_GAP, 0, 0));
        }

        private void addMockInput() {
            FrameLayout input = new FrameLayout(getContext());
            input.setFocusable(false);
            input.setClickable(false);
            input.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

            // NagramX: glassed with the same wallpaper sample the toolbar bubbles use, fed the
            // slider-configured pass-through for whichever theme is currently running, instead of the
            // flat panel color this used to paint - otherwise the two sliders below would have no
            // visible effect anywhere in this preview. Shares ComposerGlassProvider with the real chat
            // (see attachGlass below) so the shadow/stroke shown here matches what actually ships;
            // gateOnBlurEnabled=false because this preview has always shown the configured glass
            // regardless of whether blur happens to be off for the previewing account right now.
            ComposerGlassProvider bodyGlassColor = new ComposerGlassProvider(UserConfig.selectedAccount, null, false, ComposerGlassProvider.ROLE_COMPOSER);
            FrameLayout body = new FrameLayout(getContext());
            BlurredBackgroundDrawable bodyDrawable = glassFactory.create(body, bodyGlassColor);
            bodyDrawable.setRadius(InterfaceStyleController.applyComposer() ? 0 : dp(PREVIEW_INPUT_HEIGHT / 2f));
            // The MD3 field stops short of the send circle, whose drawn left edge is PREVIEW_INPUT_HEIGHT
            // in from the end once its own inset and margin cancel out.
            body.setBackground(InterfaceStyleController.applyComposer()
                    ? ComposerMd3Surface.previewField(dp(PREVIEW_INPUT_HEIGHT)) : bodyDrawable);
            body.setFocusable(false);
            body.setClickable(false);
            body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            input.addView(body, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, PREVIEW_INPUT_HEIGHT, Gravity.TOP | Gravity.START));

            TextView hint = new TextView(getContext());
            // Typed-looking text in the field's own text colour rather than the placeholder, so the
            // text size slider above has something that reads as a message to act on.
            hint.setText(LocaleController.getString(R.string.ComposerPreviewSampleText));
            hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, currentTextSize());
            hint.setTextColor(Theme.getColor(Theme.key_chat_messagePanelText));
            hint.setSingleLine(true);
            hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
            hint.setGravity(Gravity.CENTER_VERTICAL);
            hint.setFocusable(false);
            hint.setClickable(false);
            FrameLayout.LayoutParams hintParams = (FrameLayout.LayoutParams) LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, PREVIEW_INPUT_HEIGHT, Gravity.TOP | Gravity.START, 0, 0, 0, 0);
            // Real field: ChatActivityEnterView.COMPOSER_TEXT_HORIZONTAL_INSET (16dp) on the start side.
            // The 8dp used before was an approximation that read visibly closer to the bubble's edge than
            // the real field ever sits.
            int textInsetDp = 16;
            hintParams.setMarginStart(dp(textInsetDp));
            // Real field's end inset is InputSatellites' published right offset (the send button's own
            // drawn footprint, i.e. its slot minus its own background inset on both sides, plus that same
            // inset again as InputSatellites' own content margin) plus the 16dp text inset on top of that -
            // not just the slot height plus a flat 3dp, which read the placeholder noticeably closer to the
            // button than the real field's text ever gets.
            int sendDrawnWidthDp = PREVIEW_INPUT_HEIGHT - 2 * SEND_BUTTON_INSET_DP;
            hintParams.setMarginEnd(dp(sendDrawnWidthDp + SEND_BUTTON_INSET_DP + textInsetDp));
            body.addView(hint, hintParams);

            ChatActivityEnterView.SendButton send = new ChatActivityEnterView.SendButton(
                    getContext(), R.drawable.send_plane_24, null, true);
            send.setBackgroundInset(dp(SEND_BUTTON_INSET_DP));
            send.setFocusable(false);
            send.setClickable(false);
            send.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams sendParams = (FrameLayout.LayoutParams) LayoutHelper.createFrame(
                    PREVIEW_INPUT_HEIGHT, PREVIEW_INPUT_HEIGHT, Gravity.TOP | Gravity.END);
            sendParams.setMarginEnd(dp(SEND_BUTTON_INSET_DP));
            body.addView(send, sendParams);
            stage.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, PREVIEW_INPUT_HEIGHT,
                    Gravity.TOP | Gravity.START));
        }

        /** Points the capsule's glass at whatever the chat wallpaper currently is. A null wallpaper
         * still yields a usable source, so the capsule is never left unpainted. */
        private void attachGlass(Drawable wallpaper) {
            BlurredBackgroundSource source = wallpaperProvider.updateSourceFromBackgroundViewDrawable(wallpaper);
            // NagramX: a bitmap-backed proxy (gradient mesh or blurred photo) has to sample at the
            // preview's own aspect or it reads as a stretched 1:1 fragment. Retain the source and feed it
            // the preview cell's measured size; before the first measure (the setLayout-time attach) fall
            // back to the screen aspect, then onSizeChanged replaces it with the real, wide-and-shallow
            // cell dimensions once they are known.
            if (source instanceof BlurredBackgroundSourceBitmap) {
                glassSource = (BlurredBackgroundSourceBitmap) source;
                int w = getMeasuredWidth();
                int h = getMeasuredHeight();
                if (w <= 0 || h <= 0) {
                    w = AndroidUtilities.displaySize.x;
                    h = AndroidUtilities.displaySize.y;
                }
                glassSource.setParentSize(w, h, 0);
            } else {
                glassSource = null;
            }
            glassFactory = new BlurredBackgroundDrawableViewFactory(source);
            // The MD3 island frosts from the same factory, so a new wallpaper has to rebuild it too.
            md3Island = null;
            // NagramX: the preview uses the ungated default glass provider here; ComposerToolbarLayout
            // itself clears these bubbles when the MD3 Composer is active, matching the real chat.
            toolbar.attachGlass(
                    glassFactory,
                    new ComposerGlassProvider(UserConfig.selectedAccount, null, false));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // NagramX: attachGlass runs before the cell is measured, so the source starts sized to the
            // screen. Once the real preview dimensions arrive, re-point the source's centre-crop matrix at
            // them and reprime the baked glass display lists, or the wallpaper samples as a full-screen
            // fragment in this wide, shallow cell.
            if (glassSource != null && w > 0 && h > 0) {
                glassSource.setParentSize(w, h, 0);
                if (toolbar != null) {
                    toolbar.reprimeGlass();
                }
                invalidate();
            }
        }

        /** The preview colours its own children at build time, so a live theme switch has to rebuild
         * it rather than just repaint the background. */
        void applyTheme() {
            backgroundDrawable = null;
            if (zones != null) {
                setLayout(zones);
            }
        }

        // Purely a display surface sitting above a drag and drop list: without this a horizontal
        // fling would scroll the toolbar's middle group, or take the gesture off the list below.
        // Returning true is also load-bearing for the root's gesture flag (see the frameLayout
        // dispatchTouchEvent above): it consumes the strip's DOWN, which is what makes the matching
        // UP/CANCEL reach that root override and clear gestureInProgress. A future change that lets
        // touches through here must clear the flag on an unconsumed DOWN, or the accessibility
        // settle can strand.
        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
            if (Theme.wallpaperLoadTask != null) {
                invalidate();
            }
            if (wallpaper != backgroundDrawable) {
                backgroundDrawable = wallpaper;
                // The glass samples the wallpaper it is drawn over, so a wallpaper that arrives
                // after the toolbar was built has to be handed to it, not just painted here. Only on
                // an actual change, so this stays off the per frame path.
                if (toolbar != null) {
                    attachGlass(wallpaper);
                }
            }
            if (wallpaper == null) {
                canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                drawWallpaper(canvas, wallpaper);
            }
            if (InterfaceStyleController.applyComposer()) {
                if (md3Island == null) {
                    md3Island = ComposerMd3Surface.previewIsland(glassFactory, this);
                }
                final int pad = ComposerMd3Surface.previewPadding();
                // The docked composer runs edge to edge in the chat, so its stand-in spans the preview's width.
                final boolean docked = InterfaceStyleController.dockedComposer();
                md3Island.setBounds(docked ? 0 : stage.getLeft(), stage.getTop() - pad, docked ? getMeasuredWidth() : stage.getRight(), stage.getBottom() + pad);
                md3Island.draw(canvas);
            }
            shadowDrawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
            shadowDrawable.draw(canvas);
        }

        private void drawWallpaper(Canvas canvas, Drawable drawable) {
            if (drawable instanceof ColorDrawable || drawable instanceof GradientDrawable || drawable instanceof MotionBackgroundDrawable) {
                drawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
                drawable.draw(canvas);
                return;
            }
            if (!(drawable instanceof BitmapDrawable)) {
                return;
            }
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            bitmapDrawable.setFilterBitmap(true);
            canvas.save();
            if (bitmapDrawable.getTileModeX() == Shader.TileMode.REPEAT) {
                float scale = 2.0f / AndroidUtilities.density;
                canvas.scale(scale, scale);
                drawable.setBounds(0, 0, (int) Math.ceil(getMeasuredWidth() / scale), (int) Math.ceil(getMeasuredHeight() / scale));
            } else {
                float scale = Math.max(
                        getMeasuredWidth() / (float) drawable.getIntrinsicWidth(),
                        getMeasuredHeight() / (float) drawable.getIntrinsicHeight());
                int width = (int) Math.ceil(drawable.getIntrinsicWidth() * scale);
                int height = (int) Math.ceil(drawable.getIntrinsicHeight() * scale);
                int x = (getMeasuredWidth() - width) / 2;
                int y = (getMeasuredHeight() - height) / 2;
                canvas.clipRect(0, 0, getMeasuredWidth(), getMeasuredHeight());
                drawable.setBounds(x, y, x + width, y + height);
            }
            drawable.draw(canvas);
            canvas.restore();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(previewHeight()), MeasureSpec.EXACTLY));
        }
    }

    private static boolean isSliderRowType(int type) {
        return type == TYPE_SCALE || type == TYPE_SPACING || type == TYPE_TEXT_SIZE;
    }

    // getItemViewType() shifts slider rows by generation * STRIDE so a theme flip forces a real
    // recreation; every other type stays below the stride. Anything that compares a raw view type
    // against an unshifted TYPE_* constant has to undo that shift first, the same way
    // onCreateViewHolder does inline. Harmless today (only slider types are ever shifted) but the
    // drag/tap-toggle gates below would fail silently if the shift ever grew to cover them.
    private static int baseType(int viewType) {
        return viewType % SLIDER_TYPE_GENERATION_STRIDE;
    }

    /**
     * Bumps sliderThemeGeneration and notifies every slider row so RecyclerView re-asks the adapter
     * for each one's view type. Looked up by item type rather than by walking listView's attached
     * children: a slider row scrolled off-screen when the theme flips is just as stale, and by the
     * time this runs it may already be sitting in the cache or the recycled view pool rather than
     * attached to listView, so a child walk alone would miss it - scanning items by type doesn't.
     * notifyItemChanged (not a structural remove+insert) is enough here: the generation bump alone
     * is what makes the existing holder unusable (see sliderThemeGeneration's javadoc), so this
     * reruns the exact bind path (including TYPE_SPACING's set()-then-setMinValueAllowed() order) a
     * fresh entry would. Correctness does not depend on it, but this needs no pool clear of its own
     * either way: RecyclerView.Recycler#validateViewHolderForOffsetPosition rejects a holder purely
     * on a view-type mismatch, regardless of what is sitting in the pool at the time - and the
     * FLAG_CELLBACKGROUNDCOLOR description added for this same listView below
     * (HeaderCell/ButtonRowCell/PlaceholderCell) has listClasses set, so ThemeDescription#setColor's
     * listClasses != null branch already calls recyclerListView.getRecycledViewPool().clear() on
     * that listView on every setColor() it makes, including the terminal frame this runs on - one
     * fewer reason, not the reason, a second clear here would be redundant.
     */
    private void refreshSliderRowsForThemeChange() {
        if (adapter == null) {
            return;
        }
        // onAnimationProgress(1f) is delivered from an animator update callback, which normally runs
        // well before RecyclerView's own layout pass for that frame - but notifyItemChanged() asserts
        // it is not, and throws if it is. Deferring to the next frame here costs nothing in the
        // common case and removes any doubt in the uncommon one, rather than relying on that ordering.
        if (listView != null && listView.isComputingLayout()) {
            listView.post(this::refreshSliderRowsForThemeChange);
            return;
        }
        sliderThemeGeneration++;
        for (int i = 0; i < items.size(); i++) {
            if (isSliderRowType(items.get(i).type)) {
                adapter.notifyItemChanged(i);
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();

        ThemeDescription.ThemeDescriptionDelegate delegate = new ThemeDescription.ThemeDescriptionDelegate() {
            @Override
            public void didSetColor() {
                if (listView != null) {
                    for (int i = 0; i < listView.getChildCount(); i++) {
                        View child = listView.getChildAt(i);
                        if (child instanceof ButtonRowCell) {
                            ((ButtonRowCell) child).applyTheme();
                        }
                    }
                }
                if (previewCell != null) {
                    previewCell.applyTheme();
                }
            }

            @Override
            public void onAnimationProgress(float progress) {
                // didSetColor() above runs every frame of the theme transition (or once, for an
                // instant change) and is fine with that - applyTheme() is idempotent. Rebuilding the
                // slider rows is not: it must happen exactly once per transition, at its settled
                // end, not on every intermediate frame - see ActionBarLayout#setThemeAnimationValue,
                // which calls onAnimationProgress with the live interpolated value on every frame of
                // an animated change and once with 1f for an instant one. Gating on progress reaching
                // 1f covers both paths with one rebuild and avoids baking in a half-way colour.
                if (progress >= 1f) {
                    refreshSliderRowsForThemeChange();
                }
            }
        };

        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{HeaderCell.class, ButtonRowCell.class, PlaceholderCell.class, org.telegram.ui.Cells.TextSettingsCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{org.telegram.ui.Cells.TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{org.telegram.ui.Cells.TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        // Delegate only, with no background flag: the preview paints the chat wallpaper itself now,
        // so letting the theme engine set a flat colour on it would just be overdrawn.
        descriptions.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhite));

        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        descriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{ButtonRowCell.class}, new String[]{"titleView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{PlaceholderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));

        return descriptions;
    }
}
