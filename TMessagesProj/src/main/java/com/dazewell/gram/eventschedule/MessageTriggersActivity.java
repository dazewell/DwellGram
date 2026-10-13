package com.dazewell.gram.eventschedule;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only list of every armed {@code #eventschedule} trigger for the current account, reached
 * by long-pressing the Chats nav button (see {@link MessageTriggersMenu}). Its whole reason to
 * exist: after arming several messages on one trigger, this is the one place that tells the truth
 * about whether they are actually armed, without the user having to wait and see if they fire.
 *
 * <p>Deliberately flat, not the two-level {@code BookmarkManagerActivity} drill-down shape: a
 * collapsed count is exactly the kind of confident-looking summary that this page exists to
 * avoid. Every live entry gets its own visible row.
 *
 * <p>Named "Message Triggers" to the user -- "armed" is the engine's own internal vocabulary
 * ({@code STATE_ARMED}) and never appears in the trigger sheet itself ({@code EventScheduleTrigger}
 * = "Send on event"), so surfacing it here as a screen name was the one place this feature broke
 * its own naming.
 */
public class MessageTriggersActivity extends BaseFragment {

    /**
     * Marker for the two kinds of row this list renders: one header per (dialogId, triggerKey)
     * group -- chat name plus the trigger shown once -- followed by that group's live entries.
     * A plain interface rather than sealed: nothing else in this codebase uses sealed types, and
     * the exhaustiveness win here doesn't carry its weight against staying consistent with it.
     */
    private interface ListItem {
    }

    /** Chat name and this group's trigger chip, rendered once per (dialogId, triggerKey) group. */
    private record HeaderItem(String chatTitle, CharSequence triggerSummary) implements ListItem {
    }

    /**
     * One live armed entry. Brief/timeline intentionally do not repeat the chat name or the
     * trigger -- both already sit in this row's HeaderItem, so repeating them per row would be
     * exactly the redundant-by-construction look dazewell's screenshot showed: identical avatar,
     * identical chat name, identical trigger text, distinguishable only by an easy-to-miss year.
     */
    private record RowItem(long dialogId, TLObject peer, CharSequence brief, CharSequence timeline, EventScheduleEntry entry, boolean divider) implements ListItem {
    }

    /** Trust statement, appended once below the list when it is non-empty -- see applyRows(). */
    private record FooterItem() implements ListItem {
    }

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;
    private final ArrayList<ListItem> items = new ArrayList<>();
    private int loadRequestId;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.MessageTriggersTitle));

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        adapter = new ListAdapter(context, items);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size() || !(items.get(position) instanceof RowItem row)) {
                return;
            }
            openScheduled(row.dialogId(), row.entry().serverIds.isEmpty() ? 0 : row.entry().serverIds.get(0));
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= items.size() || !(items.get(position) instanceof RowItem row)) {
                return false;
            }
            EventScheduleEntry entry = row.entry();
            ItemOptions.makeOptions(this, view)
                    .setScrimViewBackground(listView.getClipBackground(view))
                    .add(R.drawable.msg_delete, LocaleController.getString(R.string.EventScheduleClear), true, () -> confirmRemove(entry))
                    .show();
            return true;
        });

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.MessageTriggersEmpty));
        emptyView.setTextColor(Theme.getColor(Theme.key_emptyListPlaceholder));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // NagramX: no reloadData() here -- onResume() runs right after this on initial presentation
        // (same as BookmarkManagerActivity), and calling it in both places would schedule two full
        // loads on first entry for nothing.

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadData();
    }

    private void openScheduled(long dialogId, int messageId) {
        Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(dialogId)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
        } else if (DialogObject.isChatDialog(dialogId)) {
            args.putLong("chat_id", -dialogId);
        } else {
            args.putLong("user_id", dialogId);
        }
        args.putInt("chatMode", ChatActivity.MODE_SCHEDULED);
        ChatActivity chat = new ChatActivity(args);
        if (messageId != 0) {
            scrollWhenLoaded(chat, messageId);
        }
        presentFragment(chat);
    }

    /**
     * Not the stock "message_id" argument: its post-load match takes the first loaded message whose
     * id is <= the target, which assumes list order follows id order. A scheduled list is ordered by
     * date, so any message scheduled out of creation order breaks that and it can land on the wrong
     * message (cited in docs/codemap/upstream-traps.md). Instead this waits for that fragment's own
     * load and then scrolls to the exact id (see scrollWhenSettled). A message that is no longer
     * scheduled is simply not in the load, so the list opens as before. The observer goes once the
     * id has loaded, on the server's answer, or after 30 s (a not-modified reply posts nothing).
     */
    private void scrollWhenLoaded(ChatActivity chat, int messageId) {
        final NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        final int guid = chat.getClassGuid();
        // Weak, so backing out of the chat before its load lands doesn't keep it alive for the wait.
        final WeakReference<ChatActivity> chatRef = new WeakReference<>(chat);
        final boolean[] scrolled = {false};
        final NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        final Runnable stop = () -> center.removeObserver(observer[0], NotificationCenter.messagesDidLoad);
        observer[0] = (id, account, args) -> {
            if ((Integer) args[10] != guid) {
                if (chatRef.get() == null) {
                    AndroidUtilities.cancelRunOnUIThread(stop);
                    stop.run();
                }
                return;
            }
            boolean found = false;
            for (Object loaded : (ArrayList<?>) args[2]) {
                if (((MessageObject) loaded).getId() == messageId) {
                    found = true;
                    break;
                }
            }
            if (found && !scrolled[0]) {
                scrolled[0] = true;
                // Posted, so it starts after the chat has added this load to its list.
                final long deadline = SystemClock.elapsedRealtime() + 3000;
                AndroidUtilities.runOnUIThread(() -> scrollWhenSettled(chatRef, messageId, deadline));
            }
            if (scrolled[0] || !(Boolean) args[3]) {
                AndroidUtilities.cancelRunOnUIThread(stop);
                stop.run();
            }
        };
        center.addObserver(observer[0], NotificationCenter.messagesDidLoad);
        AndroidUtilities.runOnUIThread(stop, 30000);
    }

    /**
     * Scrolls only once the chat's open transition has ended and its list is idle. Calling
     * scrollToMessageId straight after the first load left the list at the bottom on a device. That
     * load lands before the delayed open animation, and RecyclerAnimationScrollHelper.scrollToPosition
     * drops a scroll outright while the list's item animator is running. Re-checks every 50 ms, and
     * past the deadline scrolls anyway rather than never.
     */
    private static void scrollWhenSettled(WeakReference<ChatActivity> chatRef, int messageId, long deadline) {
        ChatActivity chat = chatRef.get();
        if (chat == null || chat.getFragmentView() == null) {
            return;
        }
        RecyclerView list = chat.getChatListView();
        boolean busy = !chat.openAnimationEnded
                || NotificationCenter.getInstance(chat.getCurrentAccount()).isAnimationInProgress()
                || list != null && list.getItemAnimator() != null && list.getItemAnimator().isRunning();
        if (busy && SystemClock.elapsedRealtime() < deadline) {
            AndroidUtilities.runOnUIThread(() -> scrollWhenSettled(chatRef, messageId, deadline), 50);
            return;
        }
        chat.scrollToMessageId(messageId, 0, true, 0, true, 0);
    }

    /**
     * A confirmation, not an Undo: round 2 found Undo can resurrect a trigger whose send already
     * completed. EventScheduleController.fire()'s success callback (EventScheduleController.java:
     * 588-599) checks EventScheduleStore.contains() immediately before removing the entry, and
     * re-persisting via armExisting() would put back stale serverIds this page can never fire
     * again -- exactly the failure class this page exists to expose. This page cannot tell
     * "removed before completion" and "completed before Undo" apart: both leave the entry absent
     * from the store with serverIds unchanged. Asking first, then only ever removing, sidesteps
     * that distinction instead of trying to make it.
     */
    private void confirmRemove(@NonNull EventScheduleEntry entry) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.EventScheduleClear));
        builder.setMessage(LocaleController.getString(R.string.MessageTriggersRemoveConfirm));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Remove), (dialog, which) -> removeEntry(entry));
        showDialog(builder.create());
    }

    /**
     * No Undo, so no re-persist path exists any more: whichever runs first between this and
     * fire()'s success callback, the entry ends up removed from the store and stays that way. The
     * bulletin only ever confirms a removal, never offers to reverse a send that may already be at
     * the server.
     */
    private void removeEntry(@NonNull EventScheduleEntry entry) {
        int account = getCurrentAccount();
        EventScheduleStore.remove(account, entry);
        // NagramX: strip the row now, synchronously -- reloadData() below is async (it hops
        // through the storage queue), and leaving a confirmed-removed row in place until it
        // returns would let it be tapped or long-pressed again during that gap.
        removeRowSynchronously(entry);
        reloadData();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.MessageTriggersRemoved)).show();
    }

    /**
     * Also drops the group's HeaderItem if this was its only row -- otherwise a lone header with
     * nothing under it would sit there until the async reload catches up.
     *
     * Compares by entry.key(), not object identity: a reload between the long-press (which
     * captured this EventScheduleEntry) and the confirm click rebuilds fresh entry instances with
     * the same key, and reference equality would silently fail to find the row, leaving it
     * tappable until the async reload lands.
     */
    private void removeRowSynchronously(@NonNull EventScheduleEntry entry) {
        int rowIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof RowItem row && row.entry().key().equals(entry.key())) {
                rowIndex = i;
                break;
            }
        }
        if (rowIndex < 0) {
            return;
        }
        RowItem removedRow = (RowItem) items.get(rowIndex);
        boolean onlyRowInGroup = (rowIndex == 0 || items.get(rowIndex - 1) instanceof HeaderItem)
                && (rowIndex == items.size() - 1 || !(items.get(rowIndex + 1) instanceof RowItem));
        // NagramX: if the removed row was the last in its group (divider == false), the sibling
        // row right before it becomes the new last row and must lose its divider too -- otherwise
        // it keeps showing a divider line meant for a row that's now gone, until the next full
        // reload rebuilds the list from scratch.
        if (!onlyRowInGroup && !removedRow.divider() && rowIndex > 0
                && items.get(rowIndex - 1) instanceof RowItem previousRow && previousRow.divider()) {
            items.set(rowIndex - 1, new RowItem(previousRow.dialogId(), previousRow.peer(), previousRow.brief(),
                    previousRow.timeline(), previousRow.entry(), false));
        }
        items.remove(rowIndex);
        if (onlyRowInGroup && rowIndex > 0 && items.get(rowIndex - 1) instanceof HeaderItem) {
            items.remove(rowIndex - 1);
        }
        // NagramX: a lone FooterItem with no rows above it would misrepresent "still armed" as
        // shown, so drop it too if that last row was the only one left.
        boolean anyRowLeft = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof RowItem) {
                anyRowLeft = true;
                break;
            }
        }
        if (!anyRowLeft) {
            items.clear();
        }
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    /**
     * serverIds non-empty is the only thing that means "this arm is genuinely live" -- the send ack
     * already landed. An unbound arm (serverIds empty) is either still binding or already dead (its
     * bind window expired), and the in-memory GC that would clean up a dead one runs on
     * AndroidUtilities.runOnUIThread(..., delay), which is backed by Handler.postDelayed and does
     * not run through deep sleep, while bindExpiresAt keeps counting on elapsedRealtime through it.
     * So after a long screen-off this page can render before that GC has caught up, and store
     * membership alone would show a dead arm as an ordinary armed row -- the exact lie this page
     * exists to end. Omit both the still-binding and the dead case; a message armed moments ago can
     * take a few seconds to appear here once its ack lands, which is expected.
     */
    // Package-private (not private) so MessageTriggersMenu's row-visibility gate can share this exact
    // definition instead of drifting from it -- see the class doc above for why store membership
    // alone is not enough.
    static boolean isLiveArm(@NonNull EventScheduleEntry entry) {
        return !entry.serverIds.isEmpty();
    }

    private void reloadData() {
        final int account = getCurrentAccount();
        final int requestId = ++loadRequestId;
        Utilities.globalQueue.postRunnable(() -> {
            // NagramX: this snapshot decides only which peers to preload. The rows themselves are
            // rebuilt from a *fresh* forAccount() read at the UI-thread callback below, not from
            // this list -- loadRequestId only rejects a stale *reload*, it says nothing about a
            // fire/removal/expiry that happens during this storage-queue hop, and reusing this
            // snapshot there would render a since-changed trigger as though nothing had happened.
            ArrayList<EventScheduleEntry> snapshotForPeers = new ArrayList<>();
            for (EventScheduleEntry entry : EventScheduleStore.forAccount(account)) {
                if (isLiveArm(entry)) {
                    snapshotForPeers.add(entry);
                }
            }
            MessagesController messagesController = MessagesController.getInstance(account);
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            HashSet<Long> seenUsers = new HashSet<>();
            HashSet<Long> seenChats = new HashSet<>();
            // NagramX: (dialogId, primaryServerId) pairs to preview, decided from the same
            // pre-hop snapshot as the peers above -- see loadPreviews() for why the join key at
            // apply time still has to be recomputed per entry rather than reused from here.
            LinkedHashMap<Long, ArrayList<Integer>> previewIdsByDialog = new LinkedHashMap<>();
            for (EventScheduleEntry entry : snapshotForPeers) {
                collectPeerToLoad(messagesController, entry.dialogId, usersToLoad, chatsToLoad, seenUsers, seenChats);
                previewIdsByDialog.computeIfAbsent(entry.dialogId, d -> new ArrayList<>()).add(entry.serverIds.get(0));
            }
            MessagesStorage messagesStorage = MessagesStorage.getInstance(account);
            messagesStorage.getStorageQueue().postRunnable(() -> {
                ArrayList<TLRPC.User> loadedUsers = new ArrayList<>(usersToLoad.size());
                ArrayList<TLRPC.Chat> loadedChats = new ArrayList<>(chatsToLoad.size());
                try {
                    if (!usersToLoad.isEmpty()) {
                        messagesStorage.getUsersInternal(usersToLoad, loadedUsers);
                    }
                    if (!chatsToLoad.isEmpty()) {
                        messagesStorage.getChatsInternal(TextUtils.join(",", chatsToLoad), loadedChats);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                // NagramX: batches into the same storage-queue runnable as the peer reads above,
                // rather than adding a second hop -- see loadPreviews() doc for the query itself.
                LinkedHashMap<PreviewKey, CharSequence> previews = loadPreviews(account, previewIdsByDialog);
                AndroidUtilities.runOnUIThread(() -> {
                    if (requestId != loadRequestId) {
                        return;
                    }
                    messagesController.putUsers(loadedUsers, true);
                    messagesController.putChats(loadedChats, true);
                    // NagramX: re-read the store here rather than reusing snapshotForPeers -- see
                    // the comment above it for why the pre-hop snapshot cannot be trusted for rows.
                    ArrayList<EventScheduleEntry> live = new ArrayList<>();
                    for (EventScheduleEntry entry : EventScheduleStore.forAccount(account)) {
                        if (isLiveArm(entry)) {
                            live.add(entry);
                        }
                    }
                    applyRows(messagesController, live, previews);
                });
            });
        });
    }

    private void collectPeerToLoad(MessagesController messagesController, long dialogId, ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad, HashSet<Long> seenUsers, HashSet<Long> seenChats) {
        long userDialogId = DialogObject.isEncryptedDialog(dialogId) ? encryptedChatUserDialogId(messagesController, dialogId) : dialogId;
        if (userDialogId != 0 && DialogObject.isUserDialog(userDialogId)) {
            TLRPC.User user = messagesController.getUser(userDialogId);
            if ((user == null || user.min) && seenUsers.add(userDialogId)) {
                usersToLoad.add(userDialogId);
            }
        } else if (DialogObject.isChatDialog(dialogId)) {
            long chatId = -dialogId;
            TLRPC.Chat chat = messagesController.getChat(chatId);
            if ((chat == null || chat.min || TextUtils.isEmpty(chat.title)) && seenChats.add(chatId)) {
                chatsToLoad.add(chatId);
            }
        }
    }

    // NagramX: a secret chat's dialogId resolves to neither isUserDialog nor isChatDialog -- the
    // real peer is the encrypted chat's other user, same pattern DialogCell uses for its avatar/title.
    private static long encryptedChatUserDialogId(MessagesController messagesController, long dialogId) {
        TLRPC.EncryptedChat encryptedChat = messagesController.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
        return encryptedChat != null ? encryptedChat.user_id : 0;
    }

    /**
     * One batched scheduled_messages_v2 query for the whole reload, issued from the same
     * storage-queue runnable as the peer reads above -- not a query per row, and not a second
     * hop. Message ids collide across dialogs (see MessagesStorage's own uid+mid keying, e.g.
     * MessagesStorage.java:562, 3804), so results are keyed by (dialogId, messageId): keying by
     * messageId alone could render another chat's message against this row's trigger.
     */
    private static LinkedHashMap<PreviewKey, CharSequence> loadPreviews(int account, LinkedHashMap<Long, ArrayList<Integer>> idsByDialog) {
        LinkedHashMap<PreviewKey, CharSequence> result = new LinkedHashMap<>();
        if (idsByDialog.isEmpty()) {
            return result;
        }
        SQLiteDatabase database = MessagesStorage.getInstance(account).getDatabase();
        if (database == null) {
            return result;
        }
        StringBuilder where = new StringBuilder();
        for (Map.Entry<Long, ArrayList<Integer>> dialogIds : idsByDialog.entrySet()) {
            if (dialogIds.getValue().isEmpty()) {
                continue;
            }
            if (where.length() > 0) {
                where.append(" OR ");
            }
            where.append("(uid = ").append(dialogIds.getKey()).append(" AND mid IN (").append(TextUtils.join(",", dialogIds.getValue())).append("))");
        }
        if (where.length() == 0) {
            return result;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized("SELECT data, mid, date, uid FROM scheduled_messages_v2 WHERE " + where);
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                // NagramX: same deserialization order MessagesStorage itself uses for this table
                // (MessagesStorage.java:3808-3822) -- id/date/dialog_id come from the cursor, not
                // from the serialized blob, which doesn't carry them.
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                if (message == null) {
                    data.reuse();
                    continue;
                }
                message.readAttachPath(data, selfId);
                data.reuse();
                message.id = cursor.intValue(1);
                message.date = cursor.intValue(2);
                message.dialog_id = cursor.longValue(3);
                // generateLayout=false: this is a lightweight lookup for a one-line preview, not a
                // chat row -- updateMessageText() runs unconditionally either way (MessageObject.
                // java:1952), so the brief text (and Telegram's own localized voice/round/video/
                // album labels) come along for free without paying for layout measurement.
                MessageObject messageObject = new MessageObject(account, message, false, false);
                // NagramX: captioned media keeps its real text in caption, not messageText --
                // messageText stays the generic type label ("Video", "Photo", ...) even when a
                // caption is present. Preferring caption here is the entire point of the row
                // redesign: two captioned videos in one chat must stay distinguishable by their
                // caption text, not collapse to the same generic label. A caption-less voice/round
                // video/album still falls back to messageText's localized type label.
                CharSequence brief = !TextUtils.isEmpty(messageObject.caption) ? messageObject.caption : messageObject.messageText;
                result.put(new PreviewKey(message.dialog_id, message.id), AndroidUtilities.replaceNewLines(brief));
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return result;
    }

    private TLObject resolvePeer(MessagesController messagesController, long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            long userDialogId = encryptedChatUserDialogId(messagesController, dialogId);
            return userDialogId != 0 ? messagesController.getUser(userDialogId) : null;
        } else if (DialogObject.isUserDialog(dialogId)) {
            return messagesController.getUser(dialogId);
        } else if (DialogObject.isChatDialog(dialogId)) {
            return messagesController.getChat(-dialogId);
        }
        return null;
    }

    @NonNull
    private String resolveTitle(TLObject peer) {
        if (peer instanceof TLRPC.Chat chat) {
            return chat.title != null ? chat.title : "";
        } else if (peer instanceof TLRPC.User user) {
            return UserObject.isUserSelf(user) ? LocaleController.getString(R.string.SavedMessages) : UserObject.getUserName(user);
        }
        return LocaleController.getString(R.string.HiddenName);
    }

    /**
     * Groups by (dialogId, triggerKey) -- EventScheduleController.queueKey() minus the account
     * term, which is constant on a single-account page, so this grouping is provably the engine's
     * own firing identity. A record key (not a delimiter-joined string) because triggerKey() embeds
     * the raw user-entered pattern, which could otherwise collide with a hand-picked delimiter.
     * Orders within a group with the engine's own QUEUE_ORDER rather than reimplementing it, and
     * orders groups by soonest fallbackDate.
     */
    private record GroupKey(long dialogId, String triggerKey) {
    }

    private void applyRows(MessagesController messagesController, ArrayList<EventScheduleEntry> live, LinkedHashMap<PreviewKey, CharSequence> previews) {
        LinkedHashMap<GroupKey, ArrayList<EventScheduleEntry>> groups = new LinkedHashMap<>();
        for (EventScheduleEntry entry : live) {
            GroupKey key = new GroupKey(entry.dialogId, entry.triggerKey());
            ArrayList<EventScheduleEntry> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(entry);
        }
        ArrayList<ArrayList<EventScheduleEntry>> groupList = new ArrayList<>(groups.values());
        for (ArrayList<EventScheduleEntry> group : groupList) {
            Collections.sort(group, EventScheduleController.QUEUE_ORDER);
        }
        groupList.sort((a, b) -> Integer.compare(a.get(0).fallbackDate, b.get(0).fallbackDate));

        long nowSeconds = System.currentTimeMillis() / 1000L;
        items.clear();
        for (ArrayList<EventScheduleEntry> group : groupList) {
            EventScheduleEntry first = group.get(0);
            TLObject peer = resolvePeer(messagesController, first.dialogId);
            String chatTitle = resolveTitle(peer);
            // triggerKey() guarantees every member of this group shares types/regex and the same
            // normalized pattern set, so any member's summary(false) speaks for the whole group -- that's what makes it safe
            // to show the trigger once here instead of repeating it on every row below.
            items.add(new HeaderItem(chatTitle, first.summary(false)));
            for (int i = 0; i < group.size(); i++) {
                EventScheduleEntry entry = group.get(i);
                CharSequence brief = resolveBrief(entry, previews);
                CharSequence timeline = resolveTimeline(entry, nowSeconds);
                boolean divider = i < group.size() - 1;
                items.add(new RowItem(entry.dialogId, peer, brief, timeline, entry, divider));
            }
        }
        // NagramX: below the list, not the empty state -- the trust statement matters most while
        // looking at rows, not while looking at nothing.
        if (!items.isEmpty()) {
            items.add(new FooterItem());
        }
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    /** (dialogId, messageId) -- message ids collide across dialogs, so messageId alone can't key this. */
    private record PreviewKey(long dialogId, int messageId) {
    }

    /**
     * A miss (nothing loaded for this entry's primary serverId, or the batched query simply
     * hasn't run against it yet) always falls back to "message unavailable" and never hides the
     * row or touches its liveness -- that's decided only by serverIds (see isLiveArm above).
     */
    private CharSequence resolveBrief(@NonNull EventScheduleEntry entry, @NonNull LinkedHashMap<PreviewKey, CharSequence> previews) {
        CharSequence brief = previews.get(new PreviewKey(entry.dialogId, entry.serverIds.get(0)));
        return brief != null ? brief : LocaleController.getString(R.string.MessageTriggersUnavailable);
    }

    /**
     * delaySeconds is deliberately not part of triggerKey(), so it can differ between two entries
     * in the same header's group -- it has to stay per-row rather than moving into the once-per-
     * group chip.
     */
    @NonNull
    private CharSequence resolveTimeline(@NonNull EventScheduleEntry entry, long nowSeconds) {
        String time = entry.fallbackDate <= nowSeconds
                ? LocaleController.getString(R.string.MessageTriggersAnyMoment)
                : LocaleController.formatDateTime(entry.fallbackDate, true);
        if (entry.delaySeconds <= 0) {
            return time;
        }
        return "+" + entry.delaySeconds + "s \u00b7 " + time;
    }

    private void updateEmptyView() {
        if (emptyView == null) {
            return;
        }
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * setData's peer argument is what gets a premium/emoji/verified badge attached to the
     * *title* text view (UserCell.update(), :679-717). After the row redesign the title holds
     * the message brief, not the peer's name, so an unmodified UserCell would attach that badge
     * to the brief instead. Keeping the real peer (needed for the avatar UserCell.update() itself
     * owns) and clearing just the title's badge drawables after super.update() runs preserves
     * upstream's avatar handling instead of bypassing it by stamping avatarImageView directly,
     * which update() may overwrite on its own next pass.
     */
    private static class UserCellNoBadge extends UserCell {
        UserCellNoBadge(Context context, int padding, int checkbox, boolean admin) {
            super(context, padding, checkbox, admin);
        }

        @Override
        public void update(int mask) {
            super.update(mask);
            nameTextView.setLeftDrawable(null);
            nameTextView.setRightDrawable(null);
        }
    }

    /**
     * Chat name (GraySectionCell) plus this group's trigger, styled with the same rounded-rect
     * chip recipe EventScheduleHelper.addTriggerRow uses for the schedule sheet -- deliberately
     * not clickable, this page is read-only and per-row/chip editing is out of scope for it.
     */
    private static class TriggerGroupHeaderCell extends LinearLayout {
        private final GraySectionCell sectionCell;
        private final TextView chip;

        TriggerGroupHeaderCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

            sectionCell = new GraySectionCell(context);
            addView(sectionCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            chip = new TextView(context);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            chip.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(5), AndroidUtilities.dp(12), AndroidUtilities.dp(5));
            chip.setMinHeight(AndroidUtilities.dp(28));
            chip.setMaxLines(3);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setGravity(Gravity.CENTER);

            FrameLayout chipContainer = new FrameLayout(context);
            chipContainer.addView(chip, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 16, 6, 16, 10));
            addView(chipContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        void bind(String chatTitle, CharSequence triggerSummary) {
            sectionCell.setText(chatTitle);
            int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);
            int backgroundColor = Theme.getColor(Theme.key_windowBackgroundGray);
            int chipBg = Theme.blendOver(backgroundColor, Theme.multAlpha(textColor, 0.075f));
            int chipSelector = Theme.multAlpha(textColor, 0.1f);
            chip.setTextColor(textColor);
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14), chipBg, Theme.blendOver(chipBg, chipSelector)));
            chip.setText(triggerSummary);
        }
    }

    private static class ListAdapter extends RecyclerListView.SelectionAdapter {
        private static final int VIEW_TYPE_HEADER = 0;
        private static final int VIEW_TYPE_ROW = 1;
        private static final int VIEW_TYPE_FOOTER = 2;

        private final Context context;
        private final ArrayList<ListItem> items;

        ListAdapter(Context context, ArrayList<ListItem> items) {
            this.context = context;
            this.items = items;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.itemView instanceof UserCell;
        }

        @Override
        public int getItemViewType(int position) {
            ListItem item = items.get(position);
            if (item instanceof HeaderItem) {
                return VIEW_TYPE_HEADER;
            } else if (item instanceof FooterItem) {
                return VIEW_TYPE_FOOTER;
            }
            return VIEW_TYPE_ROW;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_HEADER) {
                return new RecyclerListView.Holder(new TriggerGroupHeaderCell(context));
            } else if (viewType == VIEW_TYPE_FOOTER) {
                TextInfoPrivacyCell cell = new TextInfoPrivacyCell(context);
                cell.setText(LocaleController.getString(R.string.MessageTriggersFooter));
                return new RecyclerListView.Holder(cell);
            }
            return new RecyclerListView.Holder(new UserCellNoBadge(context, 8, 0, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) {
                return;
            }
            ListItem item = items.get(position);
            if (item instanceof HeaderItem header && holder.itemView instanceof TriggerGroupHeaderCell cell) {
                cell.bind(header.chatTitle(), header.triggerSummary());
            } else if (item instanceof RowItem row && holder.itemView instanceof UserCell cell) {
                cell.setData(row.peer(), row.brief(), row.timeline(), 0, row.divider());
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
