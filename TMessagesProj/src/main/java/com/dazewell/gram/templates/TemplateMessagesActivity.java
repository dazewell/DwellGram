package com.dazewell.gram.templates;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Chat settings > Template messages: add, edit, delete and reorder the composer's templates. Every
 * change is saved as it happens; the picker reads the store fresh each time it opens.
 */
public class TemplateMessagesActivity extends BaseFragment {

    private static final int TYPE_ADD = 0;
    private static final int TYPE_TEMPLATE = 1;
    private static final int TYPE_INFO = 2;

    private ArrayList<TemplateStore.Template> templates = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;
    private ItemTouchHelper itemTouchHelper;
    private boolean moved;

    @Override
    public boolean onFragmentCreate() {
        templates = TemplateStore.load();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.TemplateMessages));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(listView);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            int type = adapter.getItemViewType(position);
            if (type == TYPE_ADD) {
                if (templates.size() >= TemplateStore.MAX_TEMPLATES) {
                    BulletinFactory.of(this).createErrorBulletin(LocaleController.formatString(R.string.TemplateLimit, TemplateStore.MAX_TEMPLATES)).show();
                } else {
                    showEditor(-1);
                }
            } else if (type == TYPE_TEMPLATE) {
                showEditor(position - 1);
            }
        });
        actionBar.setAdaptiveBackground(listView);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void commit() {
        TemplateStore.save(templates);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** index -1 adds a new template; otherwise edits the one at that index. */
    private void showEditor(int index) {
        Context context = getParentActivity();
        if (context == null || index >= templates.size()) {
            return;
        }
        final TemplateStore.Template existing = index >= 0 ? templates.get(index) : null;

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setBackground(null);
        editText.setLineColors(
                Theme.getColor(Theme.key_dialogInputField),
                Theme.getColor(Theme.key_dialogInputFieldActivated),
                Theme.getColor(Theme.key_text_RedBold));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHint(LocaleController.getString(R.string.TemplateHint));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setSingleLine(false);
        editText.setMinLines(1);
        editText.setMaxLines(8);
        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(TemplateStore.MAX_LENGTH)});
        editText.setGravity(Gravity.START | Gravity.TOP);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(dp(20));
        editText.setCursorWidth(1.5f);
        editText.setPadding(0, dp(4), 0, dp(8));
        if (existing != null) {
            editText.setText(existing.text);
            editText.setSelection(editText.length());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(existing != null ? R.string.TemplateEdit : R.string.TemplateNew));
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.START, 24, 8, 24, 0));
        builder.setView(linearLayout);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            AndroidUtilities.hideKeyboard(editText);
            String text = editText.getText().toString();
            if (TextUtils.isEmpty(text.trim())) {
                return;
            }
            // Re-resolved by id: the list can't change under a modal dialog today, but an index held
            // across a dialog is the kind of thing that quietly edits the wrong row later.
            if (existing == null) {
                if (templates.size() >= TemplateStore.MAX_TEMPLATES) {
                    return;
                }
                templates.add(TemplateStore.create(text));
            } else {
                int at = indexOf(existing.id);
                if (at < 0) {
                    return;
                }
                templates.set(at, existing.withText(text));
            }
            commit();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (existing != null) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                AndroidUtilities.hideKeyboard(editText);
                delete(existing.id);
            });
        }

        AlertDialog alertDialog = builder.create();
        alertDialog.setOnShowListener(d -> AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }));
        // Cancel and back dismiss without passing through a button handler, so close the keyboard here.
        showDialog(alertDialog, d -> AndroidUtilities.hideKeyboard(editText));

        View delete = alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (delete instanceof TextView) {
            ((TextView) delete).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
        View save = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (save != null) {
            Runnable updateSave = () -> {
                boolean enabled = !TextUtils.isEmpty(editText.getText().toString().trim());
                save.setEnabled(enabled);
                save.setAlpha(enabled ? 1f : 0.5f);
            };
            updateSave.run();
            editText.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    updateSave.run();
                }
            });
        }
    }

    private void delete(String id) {
        int at = indexOf(id);
        if (at < 0) {
            return;
        }
        TemplateStore.Template removed = templates.remove(at);
        commit();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.TemplateDeleted),
                LocaleController.getString(R.string.Undo), () -> {
                    if (indexOf(removed.id) >= 0 || templates.size() >= TemplateStore.MAX_TEMPLATES) {
                        return;
                    }
                    templates.add(Math.min(at, templates.size()), removed);
                    commit();
                }).show();
    }

    private int indexOf(String id) {
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private class TouchHelperCallback extends ItemTouchHelper.Callback {

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            return viewHolder.getItemViewType() == TYPE_TEMPLATE ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) : 0;
        }

        @Override
        public boolean canDropOver(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder current, @NonNull RecyclerView.ViewHolder target) {
            return target.getItemViewType() == TYPE_TEMPLATE;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
            int from = source.getAdapterPosition() - 1;
            int to = target.getAdapterPosition() - 1;
            if (from < 0 || to < 0 || from >= templates.size() || to >= templates.size()) {
                return false;
            }
            templates.add(to, templates.remove(from));
            adapter.notifyItemMoved(from + 1, to + 1);
            moved = true;
            return true;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE && viewHolder != null) {
                listView.cancelClickRunnables(false);
                viewHolder.itemView.setPressed(true);
            }
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setPressed(false);
            if (moved) {
                moved = false;
                commit();
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != TYPE_INFO;
        }

        @Override
        public int getItemCount() {
            return templates.size() + 2;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return TYPE_ADD;
            }
            return position <= templates.size() ? TYPE_TEMPLATE : TYPE_INFO;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_ADD:
                    view = new TextCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(context);
                    break;
                default:
                    TemplateRowCell cell = new TemplateRowCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    cell.reorderView.setOnTouchListener((v, event) -> {
                        if (event.getAction() == MotionEvent.ACTION_DOWN) {
                            itemTouchHelper.startDrag(listView.getChildViewHolder(cell));
                        }
                        return false;
                    });
                    view = cell;
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_ADD:
                    TextCell textCell = (TextCell) holder.itemView;
                    textCell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
                    textCell.setTextAndIcon(LocaleController.getString(R.string.TemplateAdd), R.drawable.msg_add, !templates.isEmpty());
                    break;
                case TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(R.string.TemplateMessagesInfo));
                    break;
                default:
                    int index = position - 1;
                    if (index >= 0 && index < templates.size()) {
                        ((TemplateRowCell) holder.itemView).set(TemplateStore.label(templates.get(index).text), index < templates.size() - 1);
                    }
                    break;
            }
        }
    }

    private static class TemplateRowCell extends FrameLayout {

        final TextView titleView;
        final ImageView reorderView;
        private boolean needDivider;

        TemplateRowCell(Context context) {
            super(context);
            setWillNotDraw(false);
            boolean rtl = LocaleController.isRTL;

            titleView = new TextView(context);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setGravity((rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, rtl ? 56 : 21, 0, rtl ? 21 : 56, 0));

            reorderView = new ImageView(context);
            reorderView.setScaleType(ImageView.ScaleType.CENTER);
            reorderView.setImageResource(R.drawable.list_reorder);
            reorderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.SRC_IN));
            reorderView.setContentDescription(LocaleController.getString(R.string.FilterReorder));
            reorderView.setClickable(true);
            addView(reorderView, LayoutHelper.createFrame(48, 48, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 6, 0, 6, 0));
        }

        void applyTheme() {
            reorderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.SRC_IN));
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }

        void set(String title, boolean divider) {
            needDivider = divider;
            titleView.setText(title);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(50), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider) {
                canvas.drawLine(dp(LocaleController.isRTL ? 0 : 21), getHeight() - 1, getWidth() - dp(LocaleController.isRTL ? 21 : 0), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (listView == null) return;
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (child instanceof TemplateRowCell) ((TemplateRowCell) child).applyTheme();
            }
        };
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextCell.class, TemplateRowCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        return descriptions;
    }
}
