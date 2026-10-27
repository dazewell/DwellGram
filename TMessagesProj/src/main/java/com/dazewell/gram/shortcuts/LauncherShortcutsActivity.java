package com.dazewell.gram.shortcuts;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.LayoutTransition;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.dazewell.gram.videonote.TextMemoShortcut;
import com.dazewell.gram.videonote.VideoNoteShortcut;
import com.dazewell.gram.videonote.VideoNoteTarget;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;

import kotlin.Unit;
import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellSelectBox;
import tw.nekomimi.nekogram.config.cell.ConfigCellText;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.settings.BaseNekoXSettingsActivity;
import tw.nekomimi.nekogram.ui.PopupBuilder;
import xyz.nextalone.nagram.NaConfig;
import com.dazewell.gram.helpers.ShortcutHelper;

/**
 * N-Settings -> General -> Launcher shortcuts. One section per shortcut, Telegram's own New conversation included, under
 * a mock of the launcher's long-press popup that shows which of them are on.
 */
public class LauncherShortcutsActivity extends BaseNekoXSettingsActivity {

    public static final String SETTINGS_KEY = "shortcuts";

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerPreview = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.ComposerPreviewHeader)));
    private final AbstractConfigCell previewRow = cellGroup.appendCell(new ConfigCellCustom(null, ConfigCellCustom.CUSTOM_ITEM_LauncherShortcutsPreview, false));
    private final AbstractConfigCell dividerPreview = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerBuiltIn = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.BuiltInShortcutsHeader)));
    private final AbstractConfigCell newConversationRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getNewConversationShortcut(), getString(R.string.NewConversationShortcutNotice)));
    private final AbstractConfigCell dividerBuiltIn = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerGhostMode = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.GhostMode)));
    private final AbstractConfigCell ghostModeRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getGhostModeShortcut(), getString(R.string.GhostModeShortcutNotice)));
    private final AbstractConfigCell dividerGhostMode = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerVideoNote = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.MemoShortcutsHeader)));
    private final AbstractConfigCell videoNoteRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getVideoNoteShortcut(), getString(R.string.VideoNoteShortcutNotice)));
    // Not enabled for the list: the cell's two options take their own taps
    private final AbstractConfigCell videoNoteScreenRow = cellGroup.appendCell(new ConfigCellCustom(NaConfig.INSTANCE.getVideoNoteShortcutScreen().getKey(), ConfigCellCustom.CUSTOM_ITEM_VideoMemoScreen, false));
    private final AbstractConfigCell videoNoteCameraRow = cellGroup.appendCell(new ConfigCellSelectBox("VideoNoteShortcutCamera", NaConfig.INSTANCE.getVideoNoteShortcutCamera(), new String[]{
            getString(R.string.CameraInVideoMessagesFront),
            getString(R.string.CameraInVideoMessagesRear),
            getString(R.string.VideoNoteShortcutCameraBoth)
    }, null));
    private final AbstractConfigCell textMemoRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getTextMemoShortcut(), getString(R.string.TextMemoShortcutNotice)));
    // Shared by every memo, so it sits last
    private final ConfigCellText videoNoteTargetRow = (ConfigCellText) cellGroup.appendCell(new ConfigCellText("VideoNoteShortcutTarget", null));
    private final AbstractConfigCell dividerVideoNote = cellGroup.appendCell(new ConfigCellDivider());

    private ListAdapter listAdapter;
    private PreviewCell previewCell;
    private MemoScreenCell screenCell;
    private int targetLoadRequest;

    public LauncherShortcutsActivity() {
        updateMemoRows(false);
        updateTargetValue();
        addRowsToMap(cellGroup);
    }

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @Override
    protected String getSettingsPrefix() {
        return SETTINGS_KEY;
    }

    @Override
    public int getBaseGuid() {
        return 15000;
    }

    @Override
    public int getDrawable() {
        return R.drawable.msg_home;
    }

    @Override
    public String getTitle() {
        return getString(R.string.LauncherShortcuts);
    }

    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> onShortcutSettingChanged(key);

        return superView;
    }

    @Override
    protected void onConfigImported(String key, Object value) {
        onShortcutSettingChanged(key);
    }

    private void onShortcutSettingChanged(String key) {
        boolean memo = key.equals(NaConfig.INSTANCE.getVideoNoteShortcut().getKey()) || key.equals(NaConfig.INSTANCE.getTextMemoShortcut().getKey());
        // The camera decides how many video memo shortcuts there are, and with the screen what they are called
        boolean camera = key.equals(NaConfig.INSTANCE.getVideoNoteShortcutCamera().getKey()) || key.equals(NaConfig.INSTANCE.getVideoNoteShortcutScreen().getKey());
        if (!memo && !camera && !key.equals(NaConfig.INSTANCE.getGhostModeShortcut().getKey())
                && !key.equals(NaConfig.INSTANCE.getNewConversationShortcut().getKey())) {
            return;
        }
        MediaDataController.getInstance(currentAccount).buildShortcuts();
        if (key.equals(NaConfig.INSTANCE.getVideoNoteShortcutScreen().getKey())) {
            VideoNoteShortcut.refreshScreen(); // queued after the rebuild, and reaches pinned copies it doesn't
        }
        if (memo) {
            updateMemoRows(true);
        }
        if (previewCell != null) {
            previewCell.update();
        }
        if (screenCell != null) {
            screenCell.update(); // an import changes the screen without a tap
        }
    }

    // The screen tap writes the setting itself, then takes the same path an import does
    private void setScreen(int screen) {
        NaConfig.INSTANCE.getVideoNoteShortcutScreen().setConfigInt(screen);
        onShortcutSettingChanged(NaConfig.INSTANCE.getVideoNoteShortcutScreen().getKey());
    }

    // The screen and camera only mean something while the video memo is on, the recipient while any memo is.
    // ConfigCellSelectBox can't be dimmed, so the rows are taken out instead, and the row map is rebuilt so search results
    // and settings links still land on the right row. The screen goes in first: the camera row is placed after it.
    private void updateMemoRows(boolean animated) {
        setRowShown(videoNoteScreenRow, videoNoteRow, VideoNoteShortcut.isEnabled(), animated);
        setRowShown(videoNoteCameraRow, videoNoteScreenRow, VideoNoteShortcut.isEnabled(), animated);
        setRowShown(videoNoteTargetRow, textMemoRow, VideoNoteShortcut.isEnabled() || TextMemoShortcut.isEnabled(), animated);
        addRowsToMap(cellGroup);
    }

    private void setRowShown(AbstractConfigCell row, AbstractConfigCell after, boolean show, boolean animated) {
        if (show == cellGroup.rows.contains(row)) {
            return;
        }
        int index;
        if (show) {
            index = cellGroup.rows.indexOf(after) + 1;
            cellGroup.rows.add(index, row);
        } else {
            index = cellGroup.rows.indexOf(row);
            cellGroup.rows.remove(index);
        }
        if (animated && listAdapter != null) {
            if (show) {
                listAdapter.notifyItemInserted(index);
            } else {
                listAdapter.notifyItemRemoved(index);
            }
            listAdapter.notifyItemChanged(index - 1); // the row above: its divider follows the next row
        }
    }

    private void updateTargetValue() {
        int request = ++targetLoadRequest;
        long id = VideoNoteTarget.get(currentAccount);
        TLRPC.User user = id != 0 ? getMessagesController().getUser(id) : null;
        if (id == 0 || user != null) {
            videoNoteTargetRow.setValue(user != null ? UserObject.getUserName(user) : getString(R.string.SavedMessages));
            return;
        }
        videoNoteTargetRow.setValue("");
        MessagesStorage storage = getMessagesStorage();
        storage.getStorageQueue().postRunnable(() -> {
            TLRPC.User stored = storage.getUser(id);
            AndroidUtilities.runOnUIThread(() -> {
                if (request != targetLoadRequest) {
                    return;
                }
                if (stored != null) {
                    getMessagesController().putUser(stored, true);
                }
                // Unknown here means the shortcut falls back to Saved Messages, so say so
                videoNoteTargetRow.setValue(stored != null ? UserObject.getUserName(stored) : getString(R.string.SavedMessages));
            });
        });
    }

    private void setTarget(long userId) {
        VideoNoteTarget.set(currentAccount, userId);
        updateTargetValue();
    }

    @Override
    protected void handleCellClick(View view, int position, float x, float y) {
        if (position >= 0 && position < cellGroup.rows.size() && cellGroup.rows.get(position) == videoNoteTargetRow) {
            onTargetClick(view);
            return;
        }
        super.handleCellClick(view, position, x, y);
    }

    private void onTargetClick(View view) {
        if (VideoNoteTarget.get(currentAccount) == 0) {
            openTargetPicker();
            return;
        }
        PopupBuilder builder = new PopupBuilder(view);
        builder.setItems(new CharSequence[]{getString(R.string.ChooseUser), getString(R.string.SavedMessages)}, (i, text) -> {
            if (i == 0) {
                openTargetPicker();
            } else {
                setTarget(0);
            }
            return Unit.INSTANCE;
        });
        builder.show();
    }

    // Upstream's attach-bot chat chooser, narrowed to people. Its list drops bots, deleted accounts and yourself, but
    // search and the recent-contacts strip let some of those through, hence the check on every pick.
    private void openTargetPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_START_ATTACH_BOT);
        args.putBoolean("allowGlobalSearch", false);
        args.putBoolean("allowUsers", true);
        args.putBoolean("allowBots", false);
        args.putBoolean("allowGroups", false);
        args.putBoolean("allowMegagroups", false);
        args.putBoolean("allowLegacyGroups", false);
        args.putBoolean("allowChannels", false);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            long id = dids == null || dids.isEmpty() ? 0 : dids.get(0).dialogId;
            TLRPC.User user = DialogObject.isUserDialog(id) ? getMessagesController().getUser(id) : null;
            if (!VideoNoteTarget.isEligible(currentAccount, user)) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.VideoNoteShortcutTargetUnsupported)).show();
                return false;
            }
            setTarget(id);
            // Stores their privacy settings locally, which is all the shortcut will look at when it fires
            getMessagesController().loadFullUser(user, classGuid, true);
            fragment.finishFragment();
            return true;
        });
        presentFragment(picker);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, () -> {
            if (previewCell != null) {
                previewCell.update();
            }
            if (screenCell != null) {
                screenCell.update();
            }
        }, Theme.key_windowBackgroundWhite));
        return themeDescriptions;
    }

    private class ListAdapter extends BaseListAdapter {

        ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == ConfigCellCustom.CUSTOM_ITEM_LauncherShortcutsPreview) {
                return previewCell = new PreviewCell(mContext);
            }
            if (viewType == ConfigCellCustom.CUSTOM_ITEM_VideoMemoScreen) {
                return screenCell = new MemoScreenCell(mContext, LauncherShortcutsActivity.this::setScreen);
            }
            return null;
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof PreviewCell cell) {
                cell.update();
            } else if (holder.itemView instanceof MemoScreenCell cell) {
                cell.update();
            }
        }
    }

    /**
     * The launcher's long-press popup as the user will see it: Telegram's own New conversation first, then each fork
     * shortcut that is on, in the rank buildShortcuts gives them. Recent-chat shortcuts are left out, they come and go.
     */
    private static class PreviewCell extends FrameLayout {

        private static final int ITEM_HEIGHT = 48;
        private static final int POPUP_PADDING = 6;
        private static final int POPUP_TOP = 8;
        private static final int POPUP_BOTTOM = 16;
        private static final int ITEM_COUNT = 6;

        private final LinearLayout popup;
        private final GradientDrawable popupBackground = new GradientDrawable();
        private final TextView[] labels = new TextView[ITEM_COUNT];
        private final ImageView[] icons = new ImageView[ITEM_COUNT];
        // Both video memo icons built once, swapped by screen
        private final Bitmap videoNoteIcon = VideoNoteShortcut.createIcon();
        private final Bitmap cameraMemoIcon = VideoNoteShortcut.createCameraMemoIcon();
        private final View newConversationItem;
        private final View ghostModeItem;
        private final View videoNoteItem;
        private final View videoNoteRearItem;
        private final View textMemoItem;
        private final View textMemoCardItem;

        PreviewCell(Context context) {
            super(context);
            popup = new LinearLayout(context);
            popup.setOrientation(LinearLayout.VERTICAL);
            popup.setPadding(0, dp(POPUP_PADDING), 0, dp(POPUP_PADDING));
            popupBackground.setCornerRadius(dp(20));
            popup.setBackground(popupBackground);
            // The card animates inside its own frame. A LayoutTransition skips layout() on the view it sits on while it
            // runs, and on this cell that kept the list from placing it during a row insert, so the cell flew in from
            // below.
            FrameLayout card = new FrameLayout(context);
            card.addView(popup, LayoutHelper.createFrame(240, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, POPUP_TOP, 0, 0));
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            newConversationItem = addItem(context, 0, R.drawable.shortcut_compose, null, R.string.NewConversationShortcut);
            // Built once, not per bind: both are fresh bitmaps, and the base page rebinds everything on resume.
            ghostModeItem = addItem(context, 1, 0, GhostModeShortcut.createIcon(), R.string.AyuModeShortcut);
            videoNoteItem = addItem(context, 2, 0, videoNoteIcon, R.string.VideoNoteShortcutLabel);
            videoNoteRearItem = addItem(context, 3, 0, videoNoteIcon, R.string.VideoNoteShortcutLabel);
            textMemoItem = addItem(context, 4, 0, TextMemoShortcut.createIcon(), R.string.TextMemoShortcutLabel);
            textMemoCardItem = addItem(context, 5, 0, TextMemoShortcut.createIcon(), R.string.TextMemoShortcutLabelCard);

            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            update();

            // Set last so the initial state doesn't animate in. Items fade in and out and the card eases to its new
            // height instead of snapping. Kept off the parent hierarchy, which would reach into the RecyclerView.
            LayoutTransition itemTransition = new LayoutTransition();
            itemTransition.setAnimateParentHierarchy(false);
            itemTransition.setDuration(220);
            popup.setLayoutTransition(itemTransition);
            LayoutTransition cardTransition = new LayoutTransition();
            cardTransition.enableTransitionType(LayoutTransition.CHANGING);
            cardTransition.setAnimateParentHierarchy(false);
            cardTransition.setDuration(220);
            card.setLayoutTransition(cardTransition);
        }

        // Always as tall as the card with every item showing, so a switch only changes the card, never the rows below
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int height = dp(POPUP_TOP + 2 * POPUP_PADDING + ITEM_COUNT * ITEM_HEIGHT + POPUP_BOTTOM);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }

        private View addItem(Context context, int index, int iconRes, Bitmap iconBitmap, int labelRes) {
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);

            ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (iconBitmap != null) {
                icon.setImageBitmap(iconBitmap);
            } else {
                icon.setImageResource(iconRes);
            }
            item.addView(icon, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 16, 0, 16, 0));

            TextView label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setText(getString(labelRes));
            item.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
            labels[index] = label;
            icons[index] = icon;

            popup.addView(item, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, ITEM_HEIGHT));
            return item;
        }

        void update() {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            popupBackground.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            for (TextView label : labels) {
                label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            }
            newConversationItem.setVisibility(ShortcutHelper.isComposeShortcutEnabled() ? VISIBLE : GONE);
            ghostModeItem.setVisibility(GhostModeShortcut.isEnabled() ? VISIBLE : GONE);
            // With one shortcut the first item stands for whichever camera it opens, labelled plainly
            boolean both = VideoNoteShortcut.getCameraMode() == VideoNoteShortcut.CAMERA_BOTH;
            labels[1].setText(GhostModeShortcut.getLabel());
            labels[2].setText(VideoNoteShortcut.getLabel(false, true));
            labels[3].setText(VideoNoteShortcut.getLabel(true, true));
            Bitmap videoIcon = VideoNoteShortcut.isCameraScreen() ? cameraMemoIcon : videoNoteIcon;
            icons[2].setImageBitmap(videoIcon);
            icons[3].setImageBitmap(videoIcon);
            videoNoteItem.setVisibility(VideoNoteShortcut.isEnabled() ? VISIBLE : GONE);
            videoNoteRearItem.setVisibility(VideoNoteShortcut.isEnabled() && both ? VISIBLE : GONE);
            labels[4].setText(TextMemoShortcut.getLabel(false));
            labels[5].setText(TextMemoShortcut.getLabel(true));
            textMemoItem.setVisibility(TextMemoShortcut.isEnabled() ? VISIBLE : GONE);
            textMemoCardItem.setVisibility(TextMemoShortcut.isEnabled() ? VISIBLE : GONE);
        }
    }
}
