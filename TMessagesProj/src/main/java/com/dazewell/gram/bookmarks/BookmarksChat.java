package com.dazewell.gram.bookmarks;

import android.os.Bundle;
import android.text.TextUtils;

import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.proprietary.AyuMessageUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.helper.BookmarksHelper;

/**
 * Opens one chat's bookmarks in ChatActivity's read-only pinned list.
 *
 * Resolution runs as a chain of stages, each posted by the one before, so the
 * {@link Load} is only ever touched by one thread at a time:
 * storage queue (local rows) → UI (put users, network chunks one by one) →
 * global queue (saved-deleted copies, MessageObjects) → UI (present).
 *
 * Nothing here writes messages, pins or bookmarks: a row that can't be resolved
 * is left out of the view and stays bookmarked, because a failed fetch is not a deletion.
 */
public final class BookmarksChat {

    private static final int FETCH_CHUNK = 100;
    private static final long FETCH_TIMEOUT_MS = 8000;

    private BookmarksChat() {
    }

    private static final class Load {
        final int account;
        final long dialogId;
        final ArrayList<Integer> missing = new ArrayList<>();
        final HashMap<Integer, TLRPC.Message> found = new HashMap<>();
        final int total;
        // UI thread only, from the first fetchNext on.
        ArrayList<Integer> toFetch;
        // UI thread only: drops a reply that lands after its timeout already moved on.
        int fetchGeneration;

        Load(int account, long dialogId, int[] ids) {
            this.account = account;
            this.dialogId = dialogId;
            this.total = ids.length;
            for (int id : ids) {
                missing.add(id);
            }
        }
    }

    public static void open(BaseFragment from, long dialogId) {
        if (from == null || from.getParentActivity() == null) {
            return;
        }
        int account = from.getCurrentAccount();
        if (ChatObject.isMonoForum(account, dialogId)) {
            // ChatActivity turns a monoforum into MODE_SUGGESTIONS in onFragmentCreate, which would be a live chat.
            BulletinFactory.of(from).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.BookmarksNotInThisChat)).show();
            return;
        }
        int[] ids = BookmarksHelper.getBookmarkedMessageIds(account, dialogId);
        if (ids.length == 0) {
            BulletinFactory.of(from).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NoBookmarks)).show();
            return;
        }
        Load load = new Load(account, dialogId, ids);
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<TLRPC.User> users = new ArrayList<>();
            ArrayList<TLRPC.Chat> chats = new ArrayList<>();
            readLocal(storage, load, users, chats);
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(account).putUsers(users, true);
                MessagesController.getInstance(account).putChats(chats, true);
                fetchNext(from, load);
            });
        });
    }

    private static void readLocal(MessagesStorage storage, Load load, ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        SQLiteCursor cursor = null;
        try {
            long selfId = UserConfig.getInstance(load.account).getClientUserId();
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            // random_id is not serialized in a secret message's data; without it a delete never reaches the peer.
            cursor = storage.getDatabase().queryFinalized(String.format(Locale.US, "SELECT m.data, m.mid, m.date, r.random_id FROM messages_v2 as m LEFT JOIN randoms_v2 as r ON r.mid = m.mid AND r.uid = m.uid WHERE m.uid = %d AND m.mid IN (%s)", load.dialogId, TextUtils.join(",", load.missing)));
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                if (message != null) {
                    message.readAttachPath(data, selfId);
                    message.id = cursor.intValue(1);
                    message.date = cursor.intValue(2);
                    if (!cursor.isNull(3)) {
                        message.random_id = cursor.longValue(3);
                    }
                    message.dialog_id = load.dialogId;
                    MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                    load.found.put(message.id, message);
                }
                data.reuse();
            }
            cursor.dispose();
            cursor = null;
            if (!usersToLoad.isEmpty()) {
                storage.getUsersInternal(usersToLoad, users);
            }
            // The fetch picks channels.getMessages from the chat object, so it must be in memory even when no row is local.
            if (DialogObject.isChatDialog(load.dialogId) && !chatsToLoad.contains(-load.dialogId)) {
                chatsToLoad.add(-load.dialogId);
            }
            if (!chatsToLoad.isEmpty()) {
                storage.getChatsInternal(TextUtils.join(",", chatsToLoad), chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        load.missing.removeAll(load.found.keySet());
    }

    /** UI thread. Fetches the still-missing ids one chunk at a time; secret chats never go to the network. */
    private static void fetchNext(BaseFragment from, Load load) {
        if (load.toFetch == null) {
            load.toFetch = DialogObject.isEncryptedDialog(load.dialogId) ? new ArrayList<>() : new ArrayList<>(load.missing);
        }
        ConnectionsManager connections = ConnectionsManager.getInstance(load.account);
        if (load.toFetch.isEmpty() || connections.getConnectionState() == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            finish(from, load);
            return;
        }
        List<Integer> head = load.toFetch.subList(0, Math.min(FETCH_CHUNK, load.toFetch.size()));
        ArrayList<Integer> chunk = new ArrayList<>(head);
        head.clear();
        TLObject request;
        TLRPC.Chat chat = DialogObject.isChatDialog(load.dialogId) ? MessagesController.getInstance(load.account).getChat(-load.dialogId) : null;
        if (DialogObject.isChatDialog(load.dialogId) && chat == null) {
            // Without the chat we can't tell a channel from a group, and messages.getMessages with channel ids
            // would return unrelated private-box messages under the same numbers.
            finish(from, load);
            return;
        }
        if (ChatObject.isChannel(chat)) {
            TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
            req.channel = MessagesController.getInputChannel(chat);
            req.id = chunk;
            request = req;
        } else {
            TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
            req.id = chunk;
            request = req;
        }
        int generation = ++load.fetchGeneration;
        int[] reqId = new int[1];
        // A request can wait indefinitely for a connection; after the timeout the list opens with what resolved.
        Runnable timeout = () -> {
            if (load.fetchGeneration == generation) {
                load.fetchGeneration++;
                connections.cancelRequest(reqId[0], true);
                finish(from, load);
            }
        };
        reqId[0] = connections.sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (load.fetchGeneration != generation) {
                return;
            }
            AndroidUtilities.cancelRunOnUIThread(timeout);
            if (response instanceof TLRPC.messages_Messages res) {
                MessagesController.getInstance(load.account).putUsers(res.users, false);
                MessagesController.getInstance(load.account).putChats(res.chats, false);
                ArrayList<TLRPC.Message> fetched = new ArrayList<>();
                for (TLRPC.Message message : res.messages) {
                    if (message instanceof TLRPC.TL_messageEmpty || !chunk.contains(message.id)) {
                        continue;
                    }
                    message.dialog_id = load.dialogId;
                    load.found.put(message.id, message);
                    fetched.add(message);
                }
                ImageLoader.saveMessagesThumbs(fetched);
                load.missing.removeAll(load.found.keySet());
            }
            if (error != null) {
                // A whole-batch error (left channel, flood) is not a deletion; open with what resolved.
                finish(from, load);
            } else {
                fetchNext(from, load);
            }
        }));
        AndroidUtilities.runOnUIThread(timeout, FETCH_TIMEOUT_MS);
    }

    /** UI thread. Moves the last stage off it: saved-deleted lookups and MessageObject building. */
    private static void finish(BaseFragment from, Load load) {
        long selfId = UserConfig.getInstance(load.account).getClientUserId();
        boolean useDeleted = NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool();
        Utilities.globalQueue.postRunnable(() -> {
            if (useDeleted) {
                for (Integer id : load.missing) {
                    TLRPC.Message deleted = loadDeleted(load, selfId, id);
                    if (deleted != null) {
                        load.found.put(id, deleted);
                    }
                }
            }
            ArrayList<Integer> ids = new ArrayList<>(load.found.keySet());
            // Newest first, as the pinned list. By date: secret-chat ids are negative and count down.
            Collections.sort(ids, (a, b) -> {
                int byDate = Integer.compare(load.found.get(b).date, load.found.get(a).date);
                return byDate != 0 ? byDate : Integer.compare(b, a);
            });
            HashMap<Integer, MessageObject> objects = new HashMap<>();
            for (Integer id : ids) {
                TLRPC.Message message = load.found.get(id);
                if (message.media != null) {
                    // The pinned list hosts real cells; a self-destruct timer would start counting on view.
                    message.media.ttl_seconds = 0;
                }
                objects.put(id, new MessageObject(load.account, message, false, false));
            }
            int unavailable = load.total - ids.size();
            AndroidUtilities.runOnUIThread(() -> present(from, load, ids, objects, unavailable));
        });
    }

    private static TLRPC.Message loadDeleted(Load load, long selfId, int id) {
        try {
            DeletedMessageFull deleted = AyuMessagesController.getInstance().getMessage(selfId, load.dialogId, id);
            if (deleted == null || deleted.message == null || TextUtils.isEmpty(deleted.message.text) && TextUtils.isEmpty(deleted.message.mediaPath) && deleted.message.documentSerialized == null) {
                return null;
            }
            TLRPC.TL_message message = new TLRPC.TL_message();
            AyuMessageUtils.map(deleted.message, message, load.account);
            AyuMessageUtils.mapMedia(deleted.message, message, load.account);
            message.ayuDeleted = true;
            message.dialog_id = load.dialogId;
            return message;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void present(BaseFragment from, Load load, ArrayList<Integer> ids, HashMap<Integer, MessageObject> objects, int unavailable) {
        if (from.isFinished || from.getParentActivity() == null || from.getParentLayout() == null) {
            return;
        }
        if (ids.isEmpty()) {
            // ChatActivity's pinned feed reads pinnedMessageIds.get(0), so an empty list never gets that far.
            BulletinFactory.of(from).createSimpleBulletin(R.raw.error, LocaleController.formatPluralString("BookmarksUnavailable", unavailable)).show();
            return;
        }
        ChatActivity list = ChatActivity.nkBookmarksList(dialogArgs(load.dialogId), ids, objects, (self, messageId, hashtag) -> openTarget(self, load, messageId, hashtag));
        list.setCurrentAccount(load.account);
        if (!from.presentFragment(list)) {
            return;
        }
        // LaunchActivity saves the top fragment's arguments and restores them with a bare new ChatActivity(args),
        // which has no feed. chatMode is read once, in onFragmentCreate (already run by presentFragment), so
        // dropping it here makes a process-death restore reopen the plain chat instead.
        list.getArguments().remove("chatMode");
        if (unavailable > 0) {
            BulletinFactory.of(list).createSimpleBulletin(R.raw.error, LocaleController.formatPluralString("BookmarksUnavailable", unavailable)).show();
        }
    }

    /** The selection bar's Remove bookmark: drops the selected ids from storage and returns them for the list to remove. */
    public static int[] removeSelected(int account, long dialogId, ArrayList<MessageObject> selected) {
        int[] ids = new int[selected.size()];
        for (int i = 0; i < selected.size(); i++) {
            ids[i] = selected.get(i).getId();
        }
        BookmarksHelper.removeBookmarks(account, dialogId, ids);
        return ids;
    }

    private static Bundle dialogArgs(long dialogId) {
        Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(dialogId)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
        } else if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        return args;
    }

    /**
     * "Show in chat" and hashtags from the list. ChatActivity calls this and then finishFragment() on the list.
     * When the chat underneath is this dialog at its root, it scrolls there and the list closes onto it.
     * Otherwise the chat replaces the list (removeLast), and that finishFragment() becomes a no-op:
     * animated, ActionBarLayout.closeLastFragment bails while the transition runs; unanimated, the list
     * was already destroyed, so BaseFragment.finishFragment sees isFinished.
     */
    private static void openTarget(ChatActivity list, Load load, int messageId, String hashtag) {
        if (list.getParentLayout() == null) {
            return;
        }
        List<BaseFragment> stack = list.getParentLayout().getFragmentStack();
        int index = stack.indexOf(list);
        BaseFragment under = index > 0 ? stack.get(index - 1) : null;
        if (under instanceof ChatActivity chat && chat.getCurrentAccount() == load.account && chat.getDialogId() == load.dialogId && chat.getChatMode() == 0 && chat.getTopicId() == 0) {
            if (hashtag != null) {
                chat.openHashtagSearch(hashtag);
            } else {
                chat.scrollToMessageId(messageId, 0, true, 0, true, 0);
            }
            return;
        }
        Bundle args = dialogArgs(load.dialogId);
        if (hashtag == null && messageId != 0) {
            args.putInt("message_id", messageId);
        }
        ChatActivity chat = new ChatActivity(args);
        chat.setCurrentAccount(load.account);
        if (list.presentFragment(chat, true) && hashtag != null) {
            chat.openHashtagSearch(hashtag);
        }
    }
}
