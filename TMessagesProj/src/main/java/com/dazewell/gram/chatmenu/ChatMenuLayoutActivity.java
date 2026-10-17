package com.dazewell.gram.chatmenu;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * N-Settings > Chats > Chat menu: every configurable chat button sits in Header, ⋮ menu or Hidden.
 * Drag by the handle to move a button anywhere, tap it to hide it or bring it back to the ⋮ menu.
 * Every change is saved as it happens, like the composer toolbar editor whose rules this mirrors.
 */
public class ChatMenuLayoutActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_BUTTON = 1;
    private static final int TYPE_PLACEHOLDER = 2;
    private static final int TYPE_INFO = 3;

    private static final int reset_id = 1;

    /** Autoscroll drags a row across Header on its way up, so a swap into a full Header waits for the finger to rest. */
    private static final long HEADER_SWAP_DWELL_MS = 350;

    private static final class Item {
        final int type;
        final int section;
        final String key;

        Item(int type, int section, String key) {
            this.type = type;
            this.section = section;
            this.key = key;
        }
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;
    private ItemTouchHelper itemTouchHelper;
    private List<List<String>> lastSaved;
    private boolean rebuildPending;

    private int pendingSwapFrom = RecyclerView.NO_POSITION;
    private int pendingSwapTo = RecyclerView.NO_POSITION;
    private long pendingSwapSince;

    @Override
    public boolean onFragmentCreate() {
        lastSaved = ChatMenuLayout.snapshot();
        buildItems(lastSaved);
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ChatMenu));
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

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(listView);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setSections(true);
        actionBar.setAdaptiveBackground(listView);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
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

    /** A chat builds its buttons once, so leaving after a change rebuilds the screens behind, as the composer editor does. */
    private void flushRebuild() {
        if (!rebuildPending) return;
        rebuildPending = false;
        INavigationLayout layout = getParentLayout();
        // reloadInterface alone misses the chat that opened us (long-press on its menu button):
        // LaunchActivity rebuilds with last=false, skipping whatever is on top, and every exit calls
        // onPause while we are still stacked. A swipe defers that rebuild past the pop, so it skips
        // the chat; back or arrow runs it with us on top and clears the visible chat until the
        // black-screen watchdog in ActionBarLayout puts it back. On the next loop we are past the
        // pop: if we are gone our own stack is rebuilt top included, and reloadInterface still
        // covers the main stack when we were opened from a bubble or sheet. reloadInterface goes
        // first so that if both defer behind a transition, the deferred flags keep the top.
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            if (layout != null && layout.getLastFragment() != this) {
                layout.rebuildAllFragmentViews(true, true);
            }
        });
    }

    private void buildItems(List<List<String>> layout) {
        items.clear();
        for (int section = 0; section < ChatMenuLayout.SECTIONS; section++) {
            items.add(new Item(TYPE_HEADER, section, null));
            List<String> keys = layout.get(section);
            for (String key : keys) {
                items.add(new Item(TYPE_BUTTON, section, key));
            }
            if (keys.isEmpty()) {
                items.add(new Item(TYPE_PLACEHOLDER, section, null));
            }
            items.add(new Item(TYPE_INFO, section, null));
        }
    }

    /** The layout as the list shows it: each button belongs to the section whose title is above it. */
    private List<List<String>> collect() {
        List<List<String>> layout = new ArrayList<>(ChatMenuLayout.SECTIONS);
        for (int i = 0; i < ChatMenuLayout.SECTIONS; i++) layout.add(new ArrayList<>());
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            int section = sectionAt(i);
            if (item.type == TYPE_BUTTON && section >= 0) layout.get(section).add(item.key);
        }
        return layout;
    }

    private int sectionAt(int position) {
        for (int i = position; i >= 0; i--) {
            if (items.get(i).type == TYPE_HEADER) return items.get(i).section;
        }
        return -1;
    }

    private int headerCount() {
        int count = 0;
        for (int i = 1; i < items.size() && items.get(i).type == TYPE_BUTTON; i++) count++;
        return count;
    }

    private void persist() {
        List<List<String>> current = ChatMenuLayout.normalize(collect());
        if (current.equals(lastSaved)) return;
        ChatMenuLayout.save(current);
        lastSaved = current;
        rebuildPending = true;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void apply(List<List<String>> layout) {
        ChatMenuLayout.save(layout);
        lastSaved = ChatMenuLayout.snapshot();
        rebuildPending = true;
        buildItems(lastSaved);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void resetLayout() {
        List<List<String>> previous = ChatMenuLayout.snapshot();
        // Written out in full rather than cleared: an empty value means "never edited" and is derived from the old switches.
        apply(ChatMenuLayout.defaults());
        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.ChatMenuResetDone),
                LocaleController.getString(R.string.Undo), () -> apply(previous)).show();
    }

    /** Header or ⋮ to the end of Hidden, Hidden to the end of the ⋮ menu: the non-drag way to switch a button off and on. */
    private void toggleHidden(int position) {
        if (position < 0 || position >= items.size() || items.get(position).type != TYPE_BUTTON) return;
        String key = items.get(position).key;
        int section = sectionAt(position);
        List<List<String>> layout = collect();
        layout.get(section).remove(key);
        layout.get(section == ChatMenuLayout.HIDDEN ? ChatMenuLayout.MENU : ChatMenuLayout.HIDDEN).add(key);
        apply(layout);
    }

    private void clearPendingSwap() {
        pendingSwapFrom = RecyclerView.NO_POSITION;
        pendingSwapTo = RecyclerView.NO_POSITION;
    }

    private class TouchHelperCallback extends ItemTouchHelper.Callback {

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            return viewHolder.getItemViewType() == TYPE_BUTTON ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) : 0;
        }

        // Buttons and empty-section placeholders are targets; section titles and footers never are,
        // and a full Header only takes a button by swapping with one of its rows.
        @Override
        public boolean canDropOver(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder current, @NonNull RecyclerView.ViewHolder target) {
            int type = target.getItemViewType();
            return type == TYPE_BUTTON || type == TYPE_PLACEHOLDER;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
            int from = source.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || items.get(from).type != TYPE_BUTTON) return false;
            int targetType = items.get(to).type;
            if (targetType != TYPE_BUTTON && targetType != TYPE_PLACEHOLDER) return false;
            boolean intoFullHeader = sectionAt(to) == ChatMenuLayout.HEADER && sectionAt(from) != ChatMenuLayout.HEADER
                    && headerCount() >= ChatMenuLayout.HEADER_CAPACITY;
            if (!intoFullHeader) {
                clearPendingSwap();
                items.add(to, items.remove(from));
                adapter.notifyItemMoved(from, to);
                return true;
            }
            long now = SystemClock.uptimeMillis();
            if (pendingSwapFrom != from || pendingSwapTo != to) {
                pendingSwapFrom = from;
                pendingSwapTo = to;
                pendingSwapSince = now;
                return false;
            }
            if (now - pendingSwapSince < HEADER_SWAP_DWELL_MS) return false;
            clearPendingSwap();
            Collections.swap(items, from, to);
            adapter.notifyItemMoved(from, to);
            adapter.notifyItemMoved(to > from ? to - 1 : to + 1, from);
            return true;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE && viewHolder != null) {
                listView.cancelClickRunnables(false);
                viewHolder.itemView.setPressed(true);
                viewHolder.itemView.setTag(R.id.dragging, true);
            }
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setPressed(false);
            viewHolder.itemView.setTag(R.id.dragging, null);
            // onMove only re-fires while the finger moves, so a swap whose dwell ran out under a still finger lands on release.
            if (pendingSwapFrom != RecyclerView.NO_POSITION && SystemClock.uptimeMillis() - pendingSwapSince >= HEADER_SWAP_DWELL_MS) {
                Collections.swap(items, pendingSwapFrom, pendingSwapTo);
            }
            clearPendingSwap();
            // Rebuilt from the saved layout so an emptied section gets its placeholder back.
            persist();
            buildItems(lastSaved);
            adapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_BUTTON;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_PLACEHOLDER:
                    view = new PlaceholderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(context);
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
                    cell.setOnClickListener(v -> toggleHidden(listView.getChildAdapterPosition(cell)));
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
                    ((HeaderCell) holder.itemView).setText(LocaleController.getString(item.section == ChatMenuLayout.HEADER ? R.string.ChatMenuHeaderSection
                            : item.section == ChatMenuLayout.MENU ? R.string.ChatMenuMoreSection : R.string.ComposerZoneHidden));
                    break;
                case TYPE_PLACEHOLDER:
                    ((PlaceholderCell) holder.itemView).textView.setText(LocaleController.getString(R.string.ComposerZoneEmpty));
                    break;
                case TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(item.section == ChatMenuLayout.HEADER ? R.string.ChatMenuHeaderInfo
                            : item.section == ChatMenuLayout.MENU ? R.string.ChatMenuMoreInfo : R.string.ChatMenuHiddenInfo));
                    break;
                default:
                    ((ButtonRowCell) holder.itemView).set(item.key, position + 1 < items.size() && items.get(position + 1).type == TYPE_BUTTON);
                    break;
            }
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

        ButtonRowCell(Context context) {
            super(context);
            setWillNotDraw(false);
            boolean rtl = LocaleController.isRTL;

            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            addView(iconView, LayoutHelper.createFrame(24, 24, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 20, 0, 20, 0));

            titleView = new SimpleTextView(context);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setTextSize(16);
            titleView.setMaxLines(1);
            titleView.setGravity((rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, rtl ? 56 : 64, 0, rtl ? 64 : 56, 0));

            reorderView = new ImageView(context);
            reorderView.setScaleType(ImageView.ScaleType.CENTER);
            reorderView.setImageResource(R.drawable.list_reorder);
            reorderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.SRC_IN));
            reorderView.setContentDescription(LocaleController.getString(R.string.FilterReorder));
            reorderView.setClickable(true);
            addView(reorderView, LayoutHelper.createFrame(48, 48, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 6, 0, 6, 0));
        }

        void applyTheme() {
            iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            reorderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.SRC_IN));
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }

        void set(String key, boolean divider) {
            needDivider = divider;
            iconView.setImageResource(ChatMenuLayout.menuIcon(key));
            titleView.setText(LocaleController.getString(ChatMenuLayout.titleRes(key)));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider) {
                canvas.drawLine(dp(LocaleController.isRTL ? 0 : 64), getHeight() - dp(1), getWidth() - dp(LocaleController.isRTL ? 64 : 0), getHeight() - dp(1), Theme.dividerPaint);
            }
        }
    }

    // Same set as the composer editor: the row icons are colour-filtered in code, so a theme change re-applies them.
    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (listView == null) return;
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (child instanceof ButtonRowCell) ((ButtonRowCell) child).applyTheme();
            }
        };
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{HeaderCell.class, ButtonRowCell.class, PlaceholderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
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
