package com.dazewell.gram.bookmarks;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MessageBackgroundDrawable;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import tw.nekomimi.nekogram.ui.cells.NekoMessageCell;
import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.helper.BookmarksHelper;

/**
 * Multi-select for one dialog's bookmarked messages. Holds the selected message
 * ids, the action-mode bar, the drag-range delegate and the bulk actions, so
 * BookmarksActivity only wires hook lines. Everything is UI-thread, transient
 * and scoped to this one screen's dialog and account: never static, never persisted.
 */
public class BookmarksMultiSelect implements RecyclerListView.onMultiSelectionChanged {

    private static final int action_forward = 1;
    private static final int action_copy = 2;
    private static final int action_remove_bookmark = 3;
    private static final int action_other = 4;
    private static final int sub_share = 5;
    private static final int sub_save = 6;
    private static final int sub_select_between = 7;

    private final BaseFragment fragment;
    private final long dialogId;
    private final Runnable reloadCallback;
    /** Selected message ids, in pick order. UI thread only. */
    private final LinkedHashSet<Integer> selectedIds = new LinkedHashSet<>();
    /** Live reference to the installed (filtered) rows; replaced on every prune. */
    private ArrayList<MessageObject> currentMessages;
    /** Ids whose messages are gone from storage; bulk Forward, Share and Save skip them. */
    private Set<Integer> placeholderIds;

    private RecyclerListView listView;
    private ActionBarMenu actionMode;
    private NumberTextView selectedCountView;
    private ActionBarMenuItem otherItem;
    /** Which way the current hold-and-swipe paints: every row it crosses becomes this. */
    private boolean dragSelects;
    /** Selected ids as they were when the drag began; the drag may only touch rows matching its direction. */
    private final Set<Integer> dragSnapshot = new LinkedHashSet<>();
    /** False once selection exits: the list's drag callbacks after that must not repopulate anything. */
    private boolean dragActive;

    public BookmarksMultiSelect(BaseFragment fragment, long dialogId, Runnable reloadCallback) {
        this.fragment = fragment;
        this.dialogId = dialogId;
        this.reloadCallback = reloadCallback;
    }

    public boolean isActive() {
        ActionBar actionBar = fragment.getActionBar();
        return actionBar != null && actionBar.isActionModeShowed();
    }

    /**
     * Entry from a long-press: closes the search field first (collapsing it re-installs the
     * unfiltered list, which would otherwise shift or drop the row under the finger), then
     * arms the bar and hands the drag to the list. The position is re-derived from the
     * message itself because the collapse may have moved the row.
     */
    public boolean onItemLongClick(RecyclerListView listView, View view, ArrayList<MessageObject> messages) {
        MessageObject msg = view instanceof NekoMessageCell ? ((NekoMessageCell) view).getMessageObject() : null;
        if (msg == null) {
            return false;
        }
        this.listView = listView;
        this.currentMessages = messages;
        ActionBar actionBar = fragment.getActionBar();
        if (actionBar != null) {
            actionBar.closeSearchField();
        }
        int dragPosition = messages.indexOf(msg);
        if (dragPosition < 0) {
            return true;
        }
        if (!isActive()) {
            enterSelectionMode();
        }
        // NagramX: #bookmarks-multiselect. Snapshot before the start row toggles: the drag may
        // only add rows that were unselected, or drop rows that were selected (ChatActivity's
        // alreadySelectedMessagesIds), so retreating never strips a pre-existing selection.
        dragSnapshot.clear();
        dragSnapshot.addAll(selectedIds);
        dragSelects = !selectedIds.contains(msg.getId());
        toggleSelected(msg.getId());
        dragActive = true;
        listView.startMultiselect(dragPosition, false, this);
        return true;
    }

    /** Entry from the tap popup's Select item. No gesture is in flight, so no drag is armed. */
    public void enterFromPopup(View cell, RecyclerListView listView, ArrayList<MessageObject> messages) {
        MessageObject msg = cell instanceof NekoMessageCell ? ((NekoMessageCell) cell).getMessageObject() : null;
        if (msg == null) {
            return;
        }
        this.listView = listView;
        this.currentMessages = messages;
        ActionBar actionBar = fragment.getActionBar();
        if (actionBar != null) {
            actionBar.closeSearchField();
        }
        if (!isActive()) {
            enterSelectionMode();
        }
        toggleSelected(msg.getId());
    }

    /**
     * A tap while the bar is up toggles the row; when the bar is down the caller keeps the
     * per-message popup. Returns whether the tap was consumed.
     */
    public boolean onItemClick(View view) {
        if (!isActive()) {
            return false;
        }
        MessageObject msg = view instanceof NekoMessageCell ? ((NekoMessageCell) view).getMessageObject() : null;
        if (msg != null) {
            toggleSelected(msg.getId());
        }
        return true;
    }

    public boolean onBackPressed() {
        if (isActive()) {
            exitSelectionMode();
            return true;
        }
        return false;
    }

    /** Drops ids that are not in the freshly installed list; a prune that empties the set leaves the mode. */
    public void pruneTo(ArrayList<MessageObject> messages, Set<Integer> placeholders) {
        currentMessages = messages;
        placeholderIds = placeholders;
        if (selectedIds.isEmpty()) {
            return;
        }
        selectedIds.retainAll(collectIds(messages));
        if (selectedIds.isEmpty()) {
            if (isActive()) {
                exitSelectionMode();
            }
            return;
        }
        updateCount();
        updateVisibleRows();
    }

    /** Selection is transient; it never outlives the screen. */
    public void clear() {
        selectedIds.clear();
        dragActive = false;
    }

    /**
     * Bind is the one path every row funnels through, so selection visuals and the inert
     * flag ride it; recycled cells can otherwise carry stale checked state.
     */
    public void applyTo(NekoMessageCell cell) {
        MessageObject msg = cell.getMessageObject();
        boolean active = isActive();
        boolean selected = active && msg != null && selectedIds.contains(msg.getId());
        // Rows must be inert while selecting: avatars, links and buttons would otherwise open
        // on top of the drag. The other NekoMessageCell hosts never set this, so they are unchanged.
        cell.setNaxActionsEnabled(!active);
        if (active) {
            cell.setCheckBoxVisible(true, true);
            if (selected) {
                cell.setDrawSelectionBackground(true);
                cell.setChecked(true, true, true);
            } else {
                cell.setDrawSelectionBackground(false);
                cell.setChecked(false, false, true);
            }
        } else {
            cell.setDrawSelectionBackground(false);
            cell.setChecked(false, false, true);
            cell.setCheckBoxVisible(false, true);
        }
    }

    public boolean isActionId(int id) {
        return id >= action_forward && id <= sub_select_between;
    }

    public void onActionBarItemClick(int id) {
        if (id == action_forward) {
            forwardSelected();
        } else if (id == action_copy) {
            copySelected();
        } else if (id == action_remove_bookmark) {
            removeSelected();
        } else if (id == sub_share) {
            shareSelected();
        } else if (id == sub_save) {
            saveSelected();
        } else if (id == sub_select_between) {
            selectBetween();
        }
    }

    private void enterSelectionMode() {
        ActionBar actionBar = fragment.getActionBar();
        if (actionBar == null) {
            return;
        }
        // Built lazily, on first entry: this screen sets the glass action bar up at the end of
        // createView, and a mode built before that would paint its solid colour over the glass.
        if (actionMode == null) {
            buildActionMode(actionBar);
        }
        actionBar.showActionMode();
        updateCount();
    }

    private void buildActionMode(ActionBar actionBar) {
        actionMode = actionBar.createActionMode();
        selectedCountView = new NumberTextView(actionMode.getContext());
        selectedCountView.setTextSize(18);
        selectedCountView.setTypeface(AndroidUtilities.bold());
        selectedCountView.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        selectedCountView.setOnTouchListener((v, event) -> true);
        // NagramX: #bookmarks-multiselect. 85 left / 5 right keeps the counter off the glass
        // panel's rounded edge and clear of the forward button, as in the chat bar.
        actionMode.addView(selectedCountView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 85, 0, 5, 0));
        actionMode.addItemWithWidth(action_forward, R.drawable.msg_forward_noquote, AndroidUtilities.dp(54), LocaleController.getString(R.string.Forward));
        actionMode.addItemWithWidth(action_copy, R.drawable.msg_copy, AndroidUtilities.dp(54), LocaleController.getString(R.string.Copy));
        actionMode.addItemWithWidth(action_remove_bookmark, R.drawable.msg_unfave, AndroidUtilities.dp(54), LocaleController.getString(R.string.RemoveBookmark));
        otherItem = actionMode.addItemWithWidth(action_other, R.drawable.ic_ab_other, AndroidUtilities.dp(54), LocaleController.getString(R.string.More));
        otherItem.addSubItem(sub_share, R.drawable.msg_shareout, LocaleController.getString(R.string.ShareMessages));
        otherItem.addSubItem(sub_save, R.drawable.msg_download, LocaleController.getString(R.string.Save));
        otherItem.addSubItem(sub_select_between, R.drawable.ic_select_between, LocaleController.getString(R.string.SelectBetween));
    }

    private void exitSelectionMode() {
        selectedIds.clear();
        // NagramX: #bookmarks-multiselect. The list only ends its multiselect gesture on UP/CANCEL,
        // so a drag still in flight here must not write back into the empty set.
        dragActive = false;
        ActionBar actionBar = fragment.getActionBar();
        if (actionBar != null && actionBar.isActionModeShowed()) {
            actionBar.hideActionMode();
        }
        updateVisibleRows();
    }

    private void toggleSelected(int messageId) {
        if (!selectedIds.remove(messageId)) {
            selectedIds.add(messageId);
        }
        updateCount();
        updateVisibleRows();
    }

    private void updateCount() {
        if (selectedCountView != null) {
            selectedCountView.setNumber(selectedIds.size(), true);
        }
        if (actionMode != null) {
            boolean hasText = false;
            boolean hasForwardable = false;
            boolean hasSavable = false;
            ArrayList<MessageObject> messages = selectedMessages(false);
            for (int i = 0; i < messages.size(); i++) {
                MessageObject msg = messages.get(i);
                if (msg.messageOwner != null && (!TextUtils.isEmpty(msg.messageOwner.message) || !TextUtils.isEmpty(msg.caption))) {
                    hasText = true;
                }
                if (isForwardable(msg)) {
                    hasForwardable = true;
                }
                if (isSavable(msg)) {
                    hasSavable = true;
                }
            }
            actionMode.setItemVisibility(action_forward, hasForwardable && NaConfig.INSTANCE.getActionBarButtonForward().Bool() ? View.VISIBLE : View.GONE);
            actionMode.setItemVisibility(action_copy, hasText && NaConfig.INSTANCE.getActionBarButtonCopy().Bool() ? View.VISIBLE : View.GONE);
            otherItem.setSubItemVisibility(sub_share, hasForwardable);
            otherItem.setSubItemVisibility(sub_save, hasSavable);
            otherItem.setSubItemVisibility(sub_select_between, NaConfig.INSTANCE.getActionBarButtonSelectBetween().Bool() && selectedIds.size() >= 2);
        }
    }

    private void updateVisibleRows() {
        if (listView == null) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof NekoMessageCell) {
                applyTo((NekoMessageCell) child);
            }
        }
    }

    /**
     * The selected-row wash, drawn behind the rows. ChatMessageCell never paints it itself:
     * ChatActivity's chatListView draws it in its own dispatch-draw pass (the ungrouped branch
     * of its selection block), so a plain RecyclerListView shows no highlight whatever
     * setDrawSelectionBackground was fed. The drawable's own draw advances the select ripple
     * and the fade-out, and invalidates the row through its parent view.
     */
    public static void drawSelectionBackground(BaseFragment fragment, RecyclerListView listView, Canvas canvas) {
        int count = listView.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = listView.getChildAt(i);
            if (!(child instanceof ChatMessageCell)) {
                continue;
            }
            ChatMessageCell cell = (ChatMessageCell) child;
            MessageBackgroundDrawable drawable = cell.getBackgroundDrawable();
            if (!(drawable.isAnimationInProgress() || cell.isDrawingSelectionBackground())) {
                continue;
            }
            canvas.save();
            int y = (int) cell.getY();
            int height = cell.getMeasuredHeight();
            canvas.clipRect(0, y, listView.getMeasuredWidth(), y + height);
            Paint selectedBackgroundPaint = fragment.getThemedPaint(Theme.key_paint_chatMessageBackgroundSelected);
            if (selectedBackgroundPaint != null) {
                drawable.setCustomPaint(selectedBackgroundPaint);
            } else {
                drawable.setCustomPaint(null);
                drawable.setColor(fragment.getThemedColor(Theme.key_chat_selectedBackground));
            }
            drawable.setBounds(0, y, listView.getMeasuredWidth(), y + height);
            drawable.draw(canvas);
            canvas.restore();
        }
    }

    /**
     * The row's checkbox, drawn over the row after it. Same story as the wash: the cell only
     * exposes drawCheckBox, the list paints it (ChatActivity's chatListView drawChild).
     */
    public static void drawCheckBox(Canvas canvas, View child) {
        if (!(child instanceof ChatMessageCell)) {
            return;
        }
        if (child.getTranslationY() != 0) {
            canvas.save();
            canvas.translate(0, child.getTranslationY());
        }
        ((ChatMessageCell) child).drawCheckBox(canvas);
        if (child.getTranslationY() != 0) {
            canvas.restore();
        }
    }

    private MessageObject messageAt(int position) {
        if (currentMessages == null || position < 0 || position >= currentMessages.size()) {
            return null;
        }
        return currentMessages.get(position);
    }

    private boolean isForwardable(MessageObject msg) {
        if (msg.isAyuDeleted() || placeholderIds != null && placeholderIds.contains(msg.getId())) {
            return false;
        }
        // NagramX: #bookmarks-multiselect. noforwards lands on each row of the dialog, and
        // canForwardMessage also drops secret-media and live-location rows the bar would
        // otherwise hand to SendMessagesHelper.
        return !fragment.getMessagesController().isPeerNoForwards(dialogId) && msg.canForwardMessage();
    }

    // NagramX: #bookmarks-multiselect. A row with no document parks MediaLoader's worker
    // forever: addMessageToLoad returns on a null document, so the latch it awaits is never
    // counted down (MediaController). Document rows are what Save means here anyway, matching
    // the per-row popup's own Save gate.
    private boolean isSavable(MessageObject msg) {
        return !msg.isAyuDeleted() && (placeholderIds == null || !placeholderIds.contains(msg.getId())) && msg.getDocument() != null;
    }

    /** The selected rows in list order; forwardableOnly drops placeholder and Ayu-deleted rows. */
    private ArrayList<MessageObject> selectedMessages(boolean forwardableOnly) {
        ArrayList<MessageObject> result = new ArrayList<>();
        if (currentMessages == null) {
            return result;
        }
        for (int i = 0; i < currentMessages.size(); i++) {
            MessageObject msg = currentMessages.get(i);
            if (selectedIds.contains(msg.getId()) && (!forwardableOnly || isForwardable(msg))) {
                result.add(msg);
            }
        }
        return result;
    }

    private static Set<Integer> collectIds(ArrayList<MessageObject> messages) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            ids.add(messages.get(i).getId());
        }
        return ids;
    }

    private void forwardSelected() {
        // Captured here, not read later: forwarding pushes DialogsActivity and pauses this
        // fragment, and the delegate runs after that pause.
        ArrayList<MessageObject> messages = selectedMessages(true);
        if (messages.isEmpty()) {
            return;
        }
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("canSelectTopics", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        DialogsActivity dialogsFragment = new DialogsActivity(args);
        dialogsFragment.setDelegate((fragment1, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids.size() > 1 || dids.get(0).dialogId == fragment.getUserConfig().getClientUserId() || message != null) {
                exitSelectionMode();
                for (int a = 0; a < dids.size(); a++) {
                    long did = dids.get(a).dialogId;
                    if (message != null) {
                        fragment.getSendMessagesHelper().sendMessage(SendMessagesHelper.SendMessageParams.of(message.toString(), did, null, null, null, true, null, null, null, true, 0, 0, null, false));
                    }
                    fragment.getSendMessagesHelper().sendMessage(messages, did, false, false, true, 0, 0);
                }
                fragment1.finishFragment();
            } else {
                long did = dids.get(0).dialogId;
                Bundle args1 = new Bundle();
                args1.putBoolean("scrollToTopOnResume", true);
                if (DialogObject.isEncryptedDialog(did)) {
                    args1.putInt("enc_id", DialogObject.getEncryptedChatId(did));
                } else {
                    if (DialogObject.isUserDialog(did)) {
                        args1.putLong("user_id", did);
                    } else {
                        args1.putLong("chat_id", -did);
                    }
                    if (!fragment.getMessagesController().checkCanOpenChat(args1, fragment1)) {
                        return true;
                    }
                }
                fragment.getNotificationCenter().postNotificationName(NotificationCenter.closeChats);
                ChatActivity chatActivity = new ChatActivity(args1);
                ForumUtilities.applyTopic(chatActivity, dids.get(0));
                exitSelectionMode();
                fragment1.presentFragment(chatActivity, true);
                chatActivity.showFieldPanelForForward(true, messages);
            }
            return true;
        });
        fragment.presentFragment(dialogsFragment);
    }

    private void copySelected() {
        ArrayList<MessageObject> messages = selectedMessages(false);
        if (messages.isEmpty()) {
            return;
        }
        StringBuilder str = new StringBuilder();
        long previousUid = 0;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject msg = messages.get(i);
            if (str.length() != 0) {
                str.append("\n\n");
            }
            str.append(ChatActivity.getMessageContent(msg, previousUid, messages.size() != 1));
            previousUid = msg.getFromChatId();
        }
        if (str.length() != 0) {
            AndroidUtilities.addToClipboard(str.toString());
            BulletinFactory.of(fragment).createCopyBulletin(LocaleController.getString(R.string.MessageCopied)).show();
        }
        exitSelectionMode();
    }

    private void removeSelected() {
        if (selectedIds.isEmpty()) {
            return;
        }
        int[] ids = new int[selectedIds.size()];
        int index = 0;
        for (Integer id : selectedIds) {
            ids[index++] = id;
        }
        BookmarksHelper.removeBookmarks(fragment.getCurrentAccount(), dialogId, ids);
        exitSelectionMode();
        reloadCallback.run();
    }

    private void shareSelected() {
        ArrayList<MessageObject> messages = selectedMessages(true);
        if (messages.isEmpty()) {
            return;
        }
        exitSelectionMode();
        if (fragment.getParentActivity() != null) {
            fragment.showDialog(new ShareAlert(fragment.getParentActivity(), messages, null, false, null, false, fragment.getResourceProvider()));
        }
    }

    private void saveSelected() {
        // NagramX: #bookmarks-multiselect. Document rows only: a text or photo row parks
        // MediaLoader's worker on a latch nothing counts down (see isSavable).
        ArrayList<MessageObject> messages = new ArrayList<>();
        if (currentMessages != null) {
            for (int i = 0; i < currentMessages.size(); i++) {
                MessageObject msg = currentMessages.get(i);
                if (selectedIds.contains(msg.getId()) && isSavable(msg)) {
                    messages.add(msg);
                }
            }
        }
        if (messages.isEmpty()) {
            return;
        }
        boolean isMusic = messages.get(0).isMusic();
        exitSelectionMode();
        MediaController.saveFilesFromMessages(fragment.getParentActivity(), fragment.getAccountInstance(), messages, (count) -> {
            if (count > 0 && fragment.getParentActivity() != null) {
                BulletinFactory.of(fragment).createDownloadBulletin(isMusic ? BulletinFactory.FileType.AUDIOS : BulletinFactory.FileType.UNKNOWNS, count, fragment.getResourceProvider()).show();
            }
        });
    }

    private void selectBetween() {
        if (currentMessages == null || selectedIds.size() < 2) {
            return;
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i < currentMessages.size(); i++) {
            if (selectedIds.contains(currentMessages.get(i).getId())) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return;
        }
        for (int i = first; i <= last; i++) {
            selectedIds.add(currentMessages.get(i).getId());
        }
        updateCount();
        updateVisibleRows();
    }

    @Override
    public void onSelectionChanged(int position, boolean selected, float x, float y) {
        if (!dragActive) {
            return;
        }
        MessageObject msg = messageAt(position);
        if (msg == null || !canSelect(position)) {
            return;
        }
        // selected says whether the row entered or left the band between the drag start and the
        // finger; an unselect-drag inverts that (ChatActivity does the same before acting).
        boolean shouldSelect = dragSelects ? selected : !selected;
        if (shouldSelect) {
            selectedIds.add(msg.getId());
        } else {
            selectedIds.remove(msg.getId());
        }
        RecyclerView.ViewHolder holder = listView == null ? null : listView.findViewHolderForAdapterPosition(position);
        if (holder != null && holder.itemView instanceof NekoMessageCell) {
            applyTo((NekoMessageCell) holder.itemView);
        }
        updateCount();
    }

    @Override
    public boolean canSelect(int position) {
        if (!dragActive) {
            return false;
        }
        MessageObject msg = messageAt(position);
        if (msg == null) {
            return false;
        }
        // A select-drag only ever adds freshly-selected rows, an unselect-drag only ever drops
        // rows the snapshot already held; retreating across the opposite kind changes nothing.
        return dragSelects != dragSnapshot.contains(msg.getId());
    }

    @Override
    public int checkPosition(int position, boolean selectionTop) {
        return position;
    }

    @Override
    public boolean limitReached() {
        return false;
    }

    @Override
    public void getPaddings(int[] paddings) {
        // The glass action bar sizing owns the list paddings and re-applies them on layout,
        // so they must be read live or the drag clamp and edge auto-scroll bands break.
        paddings[0] = listView == null ? 0 : listView.getPaddingTop();
        paddings[1] = listView == null ? 0 : listView.getPaddingBottom();
    }

    @Override
    public void scrollBy(int dy) {
        if (listView != null) {
            listView.scrollBy(0, dy);
        }
    }
}
