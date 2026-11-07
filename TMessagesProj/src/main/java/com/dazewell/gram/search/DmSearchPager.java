package com.dazewell.gram.search;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/** UI-thread pager whose raw cursor is independent of locally matched messages. */
public final class DmSearchPager {
    public interface Listener {
        void loading();
        void updated(boolean jump, boolean resultsChanged);
        void failed(boolean emptyQuery);
    }

    private final int account;
    private long generation;
    private int requestId;
    private int guid;
    private long dialogId;
    private boolean active;
    private boolean loading;
    private boolean complete;
    private int offset;
    private int position;
    private int target;
    private int pendingPosition;
    private boolean pendingJump;
    private String query;
    private TLRPC.MessagesFilter filter;
    private DmSearchCriteria dates;
    private long sender;
    private long selfId;
    private Listener listener;
    private final ArrayList<MessageObject> matches = new ArrayList<>();
    private final HashSet<Integer> ids = new HashSet<>();
    private final HashSet<Integer> deletedIds = new HashSet<>();

    public DmSearchPager(int account) {
        this.account = account;
    }

    public boolean owns(int guid, long dialogId) {
        return active && this.guid == guid && this.dialogId == dialogId;
    }

    public boolean active(int guid) { return active && this.guid == guid; }
    public boolean active() { return active; }
    public boolean loading() { return loading; }
    public boolean complete() { return complete; }
    public int position() { return position; }
    public void selectPosition(int index) {
        if (index >= 0 && index < matches.size()) {
            position = pendingPosition = index;
            pendingJump = false;
        }
    }
    public ArrayList<MessageObject> matches() { return matches; }

    public int mask() {
        return (position < matches.size() - 1 || !complete ? 1 : 0) | (position > 0 ? 2 : 0);
    }

    public void close() {
        ++generation;
        active = loading = false;
        listener = null;
        matches.clear();
        ids.clear();
        deletedIds.clear();
        query = null;
        filter = null;
        dates = null;
        sender = selfId = 0;
        if (requestId != 0) {
            ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
            requestId = 0;
        }
    }

    public void start(int guid, long dialogId, String query, TLRPC.MessagesFilter filter,
                      DmSearchCriteria dates, long sender, boolean jump, Listener listener) {
        close();
        this.guid = guid;
        this.dialogId = dialogId;
        this.query = query;
        this.filter = filter;
        this.dates = dates;
        this.sender = sender;
        selfId = UserConfig.getInstance(account).getClientUserId();
        this.listener = listener;
        active = true;
        complete = false;
        offset = position = 0;
        matches.clear();
        ids.clear();
        pendingPosition = 0;
        pendingJump = jump;
        demand();
    }

    public void navigate(int direction, boolean jump) {
        if (direction == 2) {
            position = pendingPosition = Math.max(0, position - 1);
            pendingJump = false;
            listener.updated(jump, false);
        } else if (direction == 1) {
            if (position + 1 < matches.size()) {
                pendingPosition = ++position;
                pendingJump = false;
                listener.updated(jump, false);
            } else if (!complete) {
                pendingPosition = matches.isEmpty() ? 0 : position + 1;
                pendingJump = jump;
                if (!loading) demand();
            }
        }
    }

    public void removeMessages(int guid, long dialogId, ArrayList<Integer> removedIds) {
        if (!owns(guid, dialogId)) return;
        // A response already in flight can still contain ids after their deletion update.
        HashSet<Integer> removed = new HashSet<>(removedIds);
        deletedIds.addAll(removed);
        boolean changed = false;
        for (int i = 0; i < matches.size();) {
            int id = matches.get(i).getId();
            if (!removed.contains(id)) {
                i++;
                continue;
            }
            boolean pendingWasCached = pendingPosition < matches.size();
            matches.remove(i);
            ids.remove(id);
            if (i < position) position--;
            position = Math.max(0, Math.min(position, matches.size() - 1));
            if (i < pendingPosition) pendingPosition--;
            int pendingLimit = pendingWasCached ? Math.max(0, matches.size() - 1) : matches.size();
            pendingPosition = Math.max(0, Math.min(pendingPosition, pendingLimit));
            if (loading) target = Math.max(0, target - 1);
            changed = true;
        }
        if (changed) listener.updated(false, true);
    }

    public void loadMore() {
        if (complete || loading || !active) return;
        pendingPosition = position;
        pendingJump = false;
        demand();
    }

    private void demand() {
        loading = true;
        target = matches.size() + 20;
        listener.loading();
        fetch(generation);
    }

    private void fetch(long token) {
        if (!active || token != generation || !loading) return;
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
        req.q = query;
        req.filter = filter == null ? new TLRPC.TL_inputMessagesFilterEmpty() : filter;
        // Keep lower-bound candidates for the exact local check instead of restricting them server-side.
        req.min_date = 0;
        req.max_date = dates.maxDate;
        req.offset_id = offset;
        req.limit = 100;
        // Private-chat sender filtering is local: from_id is unreliable in private chats.
        requestId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            AndroidUtilities.runOnUIThread(() -> {
                if (!active || token != generation) return;
                requestId = 0;
                if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                    loading = false;
                    boolean empty = query.isEmpty() && error != null
                            && ("SEARCH_QUERY_EMPTY".equals(error.text) || "QUERY_EMPTY".equals(error.text));
                    listener.failed(empty);
                    return;
                }
                TLRPC.messages_Messages page = (TLRPC.messages_Messages) response;
                int next = offset;
                // Advance from raw ids before sender/date filtering, including pages with no matches.
                for (TLRPC.Message message : page.messages) {
                    if (message.id > 0 && (next == 0 || message.id < next)) next = message.id;
                }
                if (!page.messages.isEmpty() && (next == 0 || offset != 0 && next >= offset)) {
                    loading = false;
                    listener.failed(false);
                    return;
                }
                MessagesController.getInstance(account).putUsers(page.users, false);
                MessagesController.getInstance(account).putChats(page.chats, false);
                int previousSize = matches.size();
                for (TLRPC.Message message : page.messages) {
                    if (message instanceof TLRPC.TL_messageEmpty || message.action instanceof TLRPC.TL_messageActionHistoryClear
                            || message.id <= 0 || ids.contains(message.id) || deletedIds.contains(message.id)) continue;
                    if (dates.minDate != 0 && message.date <= dates.minDate
                            || dates.maxDate != 0 && message.date >= dates.maxDate) continue;
                    long from = MessageObject.getFromChatId(message);
                    if (from == 0) from = message.out ? selfId : dialogId;
                    if (sender != 0 && sender != from) continue;
                    MessageObject object = new MessageObject(account, message, null, null, null, null, null, true, true, 0, false, false, false);
                    if (object.hasValidGroupId()) object.isPrimaryGroupMessage = true;
                    object.setQuery(query, true);
                    ids.add(message.id);
                    matches.add(object);
                }
                offset = next;
                complete = page.messages.isEmpty();
                loading = !complete && matches.size() < target;
                boolean jumpNow = pendingJump && pendingPosition < matches.size();
                if (pendingPosition < matches.size()) position = pendingPosition;
                if (jumpNow) pendingJump = false;
                if (matches.size() > previousSize || complete) listener.updated(jumpNow, matches.size() > previousSize);
                if (active && token == generation && loading) {
                    // Yield between sequential raw pages; there is no scan cutoff or silent truncation.
                    AndroidUtilities.runOnUIThread(() -> fetch(token), 1000);
                }
            });
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }
}
