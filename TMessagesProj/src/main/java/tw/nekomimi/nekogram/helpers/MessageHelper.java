package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.messenger.TranslateController.UNKNOWN_LANGUAGE;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.DatePicker;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.radolyn.ayugram.proprietary.AyuMessageUtils;
import com.radolyn.ayugram.utils.AyuState;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteException;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BaseController;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LanguageDetector;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessageSuggestionParams;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessageChatArguments;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.AnimatedFileDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.filters.AyuFilter;
import tw.nekomimi.nekogram.parts.MessageTransKt;
import xyz.nextalone.nagram.NaConfig;

public class MessageHelper extends BaseController {

    private static final MessageHelper[] Instance = new MessageHelper[UserConfig.MAX_ACCOUNT_COUNT];
    private static final CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder();

    public static class CopyDispatchResult {
        public boolean sentAny;
        public boolean allDispatched = true;
    }

    public MessageHelper(int num) {
        super(num);
    }

    public static MessageHelper getInstance(int num) {
        MessageHelper localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (MessageHelper.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new MessageHelper(num);
                }
            }
        }
        return localInstance;
    }

    public static String getPathToMessage(MessageObject messageObject) {
        return getPathToMessage(messageObject, UserConfig.selectedAccount);
    }

    private static String getPathToMessage(MessageObject messageObject, int accountId) {
        String path = messageObject.messageOwner.attachPath;
        if (!TextUtils.isEmpty(path)) {
            File f = new File(path);
            if (!f.exists()) {
                path = null;
            }
        }
        if (TextUtils.isEmpty(path)) {
            path = FileLoader.getInstance(accountId).getPathToMessage(messageObject.messageOwner).toString();
            File f = new File(path);
            if (!f.exists() || f.getAbsolutePath().endsWith("/cache")) {
                path = null;
            }
            if (TextUtils.isEmpty(path) && (NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool() || NaConfig.INSTANCE.getEnableSaveEditsHistory().Bool())) {
                String fileName = f.getName();
                if (!TextUtils.isEmpty(fileName)) {
                    File found = AyuMessageUtils.findExistingFileByBaseNameFast(fileName);
                    if (found == null || !found.exists()) {
                        fileName = "ttl_" + messageObject.getDialogId() + "_" + messageObject.getId() + "_" + fileName;
                        found = AyuMessageUtils.findExistingFileByBaseNameFast(fileName);
                    }
                    if (found != null && found.exists()) {
                        path = found.getAbsolutePath();
                    }
                }
            }
        }
        if (TextUtils.isEmpty(path)) {
            File f = FileLoader.getInstance(accountId).getPathToAttach(messageObject.getDocument(), true);
            if (f.exists() && !f.getAbsolutePath().endsWith("/cache")) {
                path = f.getAbsolutePath();
            } else {
                f = FileLoader.getInstance(accountId).getPathToAttach(messageObject.getDocument());
                if (f.exists()) {
                    path = f.getAbsolutePath();
                }
            }
        }
        return path != null && !path.endsWith("/cache") ? path : null;
    }

    public static String buildRepostCopySourceLookupKey(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        return messageObject.getDialogId() + ":" + messageObject.getId();
    }

    private static HashMap<String, String> copyRepostMarkerParams(HashMap<String, String> markerParams) {
        if (markerParams == null) {
            return null;
        }
        HashMap<String, String> params = new HashMap<>();
        String batch = markerParams.get(SendMessagesHelper.NAGRAMX_COPY_BATCH_PARAM);
        if (!TextUtils.isEmpty(batch)) {
            params.put(SendMessagesHelper.NAGRAMX_COPY_BATCH_PARAM, batch);
        }
        String source = markerParams.get(SendMessagesHelper.NAGRAMX_COPY_SOURCE_PARAM);
        if (!TextUtils.isEmpty(source)) {
            params.put(SendMessagesHelper.NAGRAMX_COPY_SOURCE_PARAM, source);
        }
        return params.isEmpty() ? null : params;
    }

    private static HashMap<String, String> resolveRepostMarkerParams(HashMap<String, HashMap<String, String>> markerParamsBySource, MessageObject messageObject) {
        if (markerParamsBySource == null || messageObject == null) {
            return null;
        }
        return copyRepostMarkerParams(markerParamsBySource.get(buildRepostCopySourceLookupKey(messageObject)));
    }

    public void resetMessageContent(long dialog_id, MessageObject messageObject) {
        TLRPC.Message message = messageObject.messageOwner;

        MessageObject obj = new MessageObject(currentAccount, message, true, true);

        ArrayList<MessageObject> arrayList = new ArrayList<>();
        arrayList.add(obj);
        getNotificationCenter().postNotificationName(NotificationCenter.replaceMessagesObjects, dialog_id, arrayList, false, true);
    }

    public void resetMessageContent(long dialog_id, ArrayList<MessageObject> messageObjects) {
        ArrayList<MessageObject> arrayList = new ArrayList<>();
        for (MessageObject messageObject : messageObjects) {
            MessageObject obj = new MessageObject(currentAccount, messageObject.messageOwner, true, true);
            arrayList.add(obj);
        }
        getNotificationCenter().postNotificationName(NotificationCenter.replaceMessagesObjects, dialog_id, arrayList, false);
    }

    public interface FilteredMessageCallback {
        void onLoaded(MessageObject result);
    }

    public void loadLastMessageSkippingFilteredAsync(long dialogId, FilteredMessageCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            MessageObject result = getLastMessageSkippingFiltered(dialogId);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onLoaded(result));
            }
        });
    }

    public MessageObject getLastMessageSkippingFiltered(long dialogId) {
        SQLiteCursor cursor = null;
        NativeByteBuffer data = null;
        try {
            boolean ignoreBlocked = NekoConfig.ignoreBlocked.Bool();
            long currentUserId = UserConfig.getInstance(currentAccount).clientUserId;
            HashMap<Long, HashMap<Long, TLRPC.Message>> replyMessageCache = ignoreBlocked ? new HashMap<>() : null;
            String query = ignoreBlocked
                ? String.format(Locale.US, "SELECT data,send_state,mid,date,replydata FROM messages_v2 WHERE uid = %d ORDER BY date DESC LIMIT %d,%d", dialogId, 0, 20)
                : String.format(Locale.US, "SELECT data,send_state,mid,date FROM messages_v2 WHERE uid = %d ORDER BY date DESC LIMIT %d,%d", dialogId, 0, 20);
            cursor = getMessagesStorage().getDatabase().queryFinalized(query);
            while (cursor.next()) {
                data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                if (message == null) {
                    data.reuse();
                    data = null;
                    continue;
                }
                message.send_state = cursor.intValue(1);
                message.id = cursor.intValue(2);
                message.date = cursor.intValue(3);
                message.dialog_id = dialogId;
                message.readAttachPath(data, currentUserId);
                data.reuse();
                data = null;

                if (ignoreBlocked) {
                    long fromId = MessageObject.getFromChatId(message);
                    if (isBlockedUser(fromId) || AyuFilter.isBlockedChannel(fromId)) {
                        continue;
                    }
                    if (message.reply_to != null && message.reply_to.reply_to_msg_id != 0) {
                        if (!cursor.isNull(4)) {
                            NativeByteBuffer replyData = cursor.byteBufferValue(4);
                            if (replyData != null) {
                                message.replyMessage = TLRPC.Message.TLdeserialize(replyData, replyData.readInt32(false), false);
                                if (message.replyMessage != null) {
                                    message.replyMessage.readAttachPath(replyData, currentUserId);
                                }
                                replyData.reuse();
                            }
                        }
                        if (message.replyMessage == null) {
                            long replyDialogId = MessageObject.getReplyToDialogId(message);
                            if (replyDialogId == 0) {
                                replyDialogId = dialogId;
                            }
                            long replyMsgId = message.reply_to.reply_to_msg_id;
                            HashMap<Long, TLRPC.Message> dialogCache = replyMessageCache.computeIfAbsent(replyDialogId, k -> new HashMap<>());
                            if (dialogCache.containsKey(replyMsgId)) {
                                message.replyMessage = dialogCache.get(replyMsgId);
                            } else {
                                message.replyMessage = getMessage(replyDialogId, replyMsgId);
                                dialogCache.put(replyMsgId, message.replyMessage);
                            }
                        }
                        if (message.replyMessage != null) {
                            fromId = MessageObject.getFromChatId(message.replyMessage);
                            if (isBlockedUser(fromId) || AyuFilter.isBlockedChannel(fromId)) {
                                continue;
                            }
                        }
                    }
                }

                MessageObject obj = new MessageObject(currentAccount, message, false, false);
                if (AyuFilter.isFiltered(obj, null)) {
                    continue;
                }
                if (getMessagesController().getUser(obj.getSenderId()) == null) {
                    TLRPC.User user = getMessagesStorage().getUser(obj.getSenderId());
                    if (user != null) {
                        getMessagesController().putUser(user, true);
                    }
                }
                return obj;
            }
        } catch (SQLiteException e) {
            FileLog.e("RegexFilter, SQLiteException when reading last unfiltered message", e);
        } finally {
            if (data != null) {
                data.reuse();
            }
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return null;
    }

    public TLRPC.Message getMessage(long dialogId, long msgId) {
        TLRPC.Message message = null;
        SQLiteCursor cursor = null;
        try {
            cursor = getMessagesStorage().getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE uid = " + dialogId + " AND mid = " + msgId + " LIMIT 1");
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data != null) {
                    message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
                    }
                    data.reuse();
                }
            }
            cursor.dispose();
            cursor = null;
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return message;
    }

    public ArrayList<TLRPC.Message> getMessagesStorageMessages(long dialogId, ArrayList<Integer> messageIds) {
        ArrayList<TLRPC.Message> messages = null;
        SQLiteCursor cursor = null;
        try {
            String ids = TextUtils.join(",", messageIds);
            cursor = getMessagesStorage().getDatabase().queryFinalized(String.format(Locale.US,"SELECT data FROM messages_v2 WHERE uid = %d AND mid IN (%s)", dialogId, ids));
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data != null) {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
                        if (messages == null) {
                            messages = new ArrayList<>();
                        }
                        messages.add(message);
                    }
                    data.reuse();
                }
            }
            cursor.dispose();
            cursor = null;
        } catch (SQLiteException e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return messages;
    }

    public void saveStickerToGallery(Context context, MessageObject messageObject, Utilities.Callback<Uri> callback) {
        if (messageObject.isAnimatedSticker()) return;
        // Animated Sticker is not supported.

        String path = getPathToMessage(messageObject, currentAccount);
        if (!TextUtils.isEmpty(path)) {
            saveStickerToGallery(context, path, messageObject.isVideoSticker(), null, callback);
        }
    }

    public void saveStickerToGallery(Context context, TLRPC.Document document, Utilities.Callback<Uri> callback) {
        String path = FileLoader.getInstance(currentAccount).getPathToAttach(document, true).toString();
        if (!TextUtils.isEmpty(path)) {
            saveStickerToGallery(context, path, MessageObject.isVideoSticker(document), document.mime_type, callback);
        }
    }

    private static void saveStickerToGallery(Context context, String path, boolean videoSticker, String mimeType, Utilities.Callback<Uri> callback) {
        if (context == null || TextUtils.isEmpty(path)) {
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (videoSticker) {
                    MediaController.saveFile(path, context, 1, null, mimeType, callback);
                } else {
                    Bitmap image = BitmapFactory.decodeFile(path);
                    if (image != null) {
                        File file = new File(path.endsWith(".webp") ? path.replace(".webp", ".png") : path + ".png");
                        try (FileOutputStream stream = new FileOutputStream(file)) {
                            image.compress(Bitmap.CompressFormat.PNG, 100, stream);
                        } finally {
                            image.recycle();
                        }
                        MediaController.saveFile(file.toString(), context, 0, null, null, callback);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void addStickerToClipboard(TLRPC.Document document, Runnable callback) {
        String path = FileLoader.getInstance(currentAccount).getPathToAttach(document, true).toString();

        if (TextUtils.isEmpty(path)) {
            return;
        }
        if (MessageObject.isVideoSticker(document)) {
            return;
        }
        File file = new File(path);
        if (file.exists()) {
            addFileToClipboard(file, callback);
        }
    }

    public MessageObject getMessageForRepeat(MessageObject selectedObject, MessageObject.GroupedMessages selectedObjectGroup) {
        MessageObject messageObject = null;
        if (selectedObjectGroup != null && !selectedObjectGroup.isDocuments) {
            messageObject = getTargetMessageObjectFromGroup(selectedObjectGroup);
        } else if (!TextUtils.isEmpty(selectedObject.messageOwner.message) || selectedObject.isAnyKindOfSticker()) {
            messageObject = selectedObject;
        }
        return messageObject;
    }

    private MessageObject getTargetMessageObjectFromGroup(MessageObject.GroupedMessages selectedObjectGroup) {
        MessageObject messageObject = null;
        for (MessageObject object : selectedObjectGroup.messages) {
            if (!TextUtils.isEmpty(object.messageOwner.message)) {
                if (messageObject != null) {
                    messageObject = null;
                    break;
                } else {
                    messageObject = object;
                }
            }
        }
        return messageObject;
    }

    public void createDeleteHistoryAlert(BaseFragment fragment, TLRPC.Chat chat, TLRPC.TL_forumTopic forumTopic, long mergeDialogId, Theme.ResourcesProvider resourcesProvider) {
        createDeleteHistoryAlert(fragment, chat, forumTopic, mergeDialogId, -1, resourcesProvider);
    }

    private void createDeleteHistoryAlert(BaseFragment fragment, TLRPC.Chat chat, TLRPC.TL_forumTopic forumTopic, long mergeDialogId, int before, Theme.ResourcesProvider resourcesProvider) {
        if (fragment == null || fragment.getParentActivity() == null || chat == null) {
            return;
        }

        Context context = fragment.getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);

        CheckBoxCell cell = before == -1 && forumTopic == null && ChatObject.isChannel(chat) && ChatObject.canUserDoAction(chat, ChatObject.ACTION_DELETE_MESSAGES) ? new CheckBoxCell(context, 1, resourcesProvider) : null;

        TextView messageTextView = new TextView(context);
        messageTextView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        messageTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP);

        FrameLayout frameLayout = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                if (cell != null) {
                    setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight() + cell.getMeasuredHeight() + AndroidUtilities.dp(7));
                }
            }
        };
        builder.setView(frameLayout);

        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setTextSize(AndroidUtilities.dp(12));
        avatarDrawable.setInfo(chat);

        BackupImageView imageView = new BackupImageView(context);
        imageView.setRoundRadius(AndroidUtilities.dp(20));
        if (forumTopic != null) {
            if (forumTopic.id == 1) {
                imageView.setImageDrawable(ForumUtilities.createGeneralTopicDrawable(context, 0.75f, Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider), false));
            } else {
                ForumUtilities.setTopicIcon(imageView, forumTopic, false, true, resourcesProvider);
            }
        } else {
            imageView.setForUserOrChat(chat, avatarDrawable);
        }
        frameLayout.addView(imageView, LayoutHelper.createFrame(40, 40, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 22, 5, 22, 0));

        TextView textView = new TextView(context);
        textView.setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, resourcesProvider));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        textView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        textView.setLines(1);
        textView.setMaxLines(1);
        textView.setSingleLine(true);
        textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setText(getString(R.string.DeleteAllFromSelf));

        frameLayout.addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, (LocaleController.isRTL ? 21 : 76), 11, (LocaleController.isRTL ? 76 : 21), 0));
        frameLayout.addView(messageTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 24, 57, 24, 9));

        if (cell != null) {
            boolean sendAs = ChatObject.getSendAsPeerId(chat, getMessagesController().getChatFull(chat.id), true) != getUserConfig().getClientUserId();
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setText(getString(R.string.DeleteAllFromSelfAdmin), "", !ChatObject.shouldSendAnonymously(chat) && !sendAs, false);
            cell.setPadding(LocaleController.isRTL ? AndroidUtilities.dp(16) : AndroidUtilities.dp(8), 0, LocaleController.isRTL ? AndroidUtilities.dp(8) : AndroidUtilities.dp(16), 0);
            frameLayout.addView(cell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM | Gravity.LEFT, 0, 0, 0, 0));
            cell.setOnClickListener(v -> {
                CheckBoxCell cell1 = (CheckBoxCell) v;
                cell1.setChecked(!cell1.isChecked(), true);
            });
        }

        if (before > 0) {
            messageTextView.setText(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.DeleteAllFromSelfAlertBefore, LocaleController.formatDateForBan(before))));
        } else {
            messageTextView.setText(AndroidUtilities.replaceTags(getString(R.string.DeleteAllFromSelfAlert)));
        }

        builder.setNeutralButton(getString(R.string.DeleteAllFromSelfBefore), (dialog, which) -> showBeforeDatePickerAlert(fragment, before1 -> createDeleteHistoryAlert(fragment, chat, forumTopic, mergeDialogId, before1, resourcesProvider)));
        builder.setPositiveButton(getString(R.string.DeleteAll), (dialogInterface, i) -> {
            if (cell != null && cell.isChecked()) {
                showDeleteHistoryBulletin(fragment, 0, false, () -> getMessagesController().deleteUserChannelHistory(chat, getUserConfig().getCurrentUser(), null, 0), resourcesProvider);
            } else {
                deleteUserHistoryWithSearch(fragment, -chat.id, forumTopic != null ? forumTopic.id : 0, mergeDialogId, before == -1 ? getConnectionsManager().getCurrentTime() : before, (count, deleteAction) -> showDeleteHistoryBulletin(fragment, count, true, deleteAction, resourcesProvider));
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog alertDialog = builder.create();
        fragment.showDialog(alertDialog);
        TextView button = (TextView) alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        }
    }

    private void showBeforeDatePickerAlert(BaseFragment fragment, Utilities.Callback<Integer> callback) {
        Context context = fragment.getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.DeleteAllFromSelfBefore));
        builder.setItems(new CharSequence[]{
                LocaleController.formatPluralString("Days", 1),
                LocaleController.formatPluralString("Weeks", 1),
                LocaleController.formatPluralString("Months", 1),
                getString(R.string.UserRestrictionsCustom)
        }, (dialog1, which) -> {
            switch (which) {
                case 0:
                    callback.run(getConnectionsManager().getCurrentTime() - 60 * 60 * 24);
                    break;
                case 1:
                    callback.run(getConnectionsManager().getCurrentTime() - 60 * 60 * 24 * 7);
                    break;
                case 2:
                    callback.run(getConnectionsManager().getCurrentTime() - 60 * 60 * 24 * 30);
                    break;
                case 3: {
                    DatePickerDialog dateDialog = getDatePickerDialog(fragment, callback, context);

                    final DatePicker datePicker = dateDialog.getDatePicker();

                    datePicker.setMinDate(1375315200000L);
                    datePicker.setMaxDate(System.currentTimeMillis());

                    dateDialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.Set), dateDialog);
                    dateDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.Cancel), (dialog2, which2) -> {
                    });
                    dateDialog.setOnShowListener(dialog12 -> {
                        int count = datePicker.getChildCount();
                        for (int b = 0; b < count; b++) {
                            View child = datePicker.getChildAt(b);
                            ViewGroup.LayoutParams layoutParams = child.getLayoutParams();
                            layoutParams.width = LayoutHelper.MATCH_PARENT;
                            child.setLayoutParams(layoutParams);
                        }
                    });
                    fragment.showDialog(dateDialog);
                    break;
                }
            }
            builder.getDismissRunnable().run();
        });
        fragment.showDialog(builder.create());
    }

    @NonNull
    private static DatePickerDialog getDatePickerDialog(BaseFragment fragment, Utilities.Callback<Integer> callback, Context context) {
        Calendar calendar = Calendar.getInstance();
        return new DatePickerDialog(context, (view1, year1, month, dayOfMonth1) -> {
            TimePickerDialog timeDialog = new TimePickerDialog(context, (view11, hourOfDay, minute) -> {
                calendar.set(year1, month, dayOfMonth1, hourOfDay, minute);
                callback.run((int) (calendar.getTimeInMillis() / 1000));
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true);
            timeDialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.Set), timeDialog);
            timeDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.Cancel), (dialog3, which3) -> {
            });
            fragment.showDialog(timeDialog);
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH));
    }

    public static void showDeleteHistoryBulletin(BaseFragment fragment, int count, boolean search, Runnable delayedAction, Theme.ResourcesProvider resourcesProvider) {
        if (fragment.getParentActivity() == null) {
            if (delayedAction != null) {
                delayedAction.run();
            }
            return;
        }
        Bulletin.ButtonLayout buttonLayout;
        if (search) {
            final Bulletin.TwoLineLottieLayout layout = new Bulletin.TwoLineLottieLayout(fragment.getParentActivity(), resourcesProvider);
            layout.titleTextView.setText(getString(R.string.DeleteAllFromSelfDone));
            layout.subtitleTextView.setText(LocaleController.formatPluralString("MessagesDeletedHint", count));
            layout.setTimer();
            buttonLayout = layout;
        } else {
            final Bulletin.LottieLayout layout = new Bulletin.LottieLayout(fragment.getParentActivity(), resourcesProvider);
            layout.textView.setText(getString(R.string.DeleteAllFromSelfDone));
            layout.setTimer();
            buttonLayout = layout;
        }
        buttonLayout.setButton(new Bulletin.UndoButton(fragment.getParentActivity(), true, resourcesProvider).setDelayedAction(delayedAction));
        Bulletin.make(fragment, buttonLayout, Bulletin.DURATION_PROLONG).show();
    }

    private void deleteUserHistoryWithSearch(BaseFragment fragment, final long dialogId, int replyMessageId, final long mergeDialogId, int before, SearchMessagesResultCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<Integer> messageIds = new ArrayList<>();
            var latch = new CountDownLatch(1);
            var peer = getMessagesController().getInputPeer(dialogId);
            var fromId = MessagesController.getInputPeer(getUserConfig().getCurrentUser());
            doSearchMessages(fragment, latch, messageIds, peer, replyMessageId, fromId, before, Integer.MAX_VALUE, 0);
            try {
                latch.await();
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (!messageIds.isEmpty()) {
                ArrayList<ArrayList<Integer>> lists = new ArrayList<>();
                final int N = messageIds.size();
                for (int i = 0; i < N; i += 100) {
                    lists.add(new ArrayList<>(messageIds.subList(i, Math.min(N, i + 100))));
                }
                Runnable deleteAction = () -> {
                    for (ArrayList<Integer> list : lists) {
                        for (int msgId : list) {
                            AyuState.permitDeleteMessage(currentAccount, dialogId, msgId);
                        }
                        getMessagesController().deleteMessages(list, null, null, dialogId, 0, true, 0);
                    }
                };
                AndroidUtilities.runOnUIThread(callback != null ? () -> callback.run(messageIds.size(), deleteAction) : deleteAction);
            }
            if (mergeDialogId != 0) {
                deleteUserHistoryWithSearch(fragment, mergeDialogId, 0, 0, before, null);
            }
        });
    }

    private interface SearchMessagesResultCallback {
        void run(int count, Runnable deleteAction);
    }

    private void doSearchMessages(BaseFragment fragment, CountDownLatch latch, ArrayList<Integer> messageIds, TLRPC.InputPeer peer, int replyMessageId, TLRPC.InputPeer fromId, int before, int offsetId, long hash) {
        var req = new TLRPC.TL_messages_search();
        req.peer = peer;
        req.limit = 100;
        req.q = "";
        req.offset_id = offsetId;
        req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        long dialogId = DialogObject.getPeerDialogId(peer);
        boolean isMonoForum = getMessagesController().isMonoForum(dialogId);
        if (!isMonoForum) {
            req.from_id = fromId;
            req.flags |= 1;
        }
        if (replyMessageId != 0) {
            if (isMonoForum) {
                req.saved_peer_id = getMessagesController().getInputPeer(replyMessageId);
                req.flags |= 4;
            } else {
                req.top_msg_id = replyMessageId;
                req.flags |= 2;
            }
        }
        req.hash = hash;
        getConnectionsManager().sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.messages_Messages res) {
                if (response instanceof TLRPC.TL_messages_messagesNotModified || res.messages.isEmpty()) {
                    latch.countDown();
                    return;
                }
                var newOffsetId = offsetId;
                for (TLRPC.Message message : res.messages) {
                    newOffsetId = Math.min(newOffsetId, message.id);
                    if (!message.out || message.post || message.date >= before) {
                        continue;
                    }
                    messageIds.add(message.id);
                }
                doSearchMessages(fragment, latch, messageIds, peer, replyMessageId, fromId, before, newOffsetId, calcMessagesHash(res.messages));
            } else {
                if (error != null) {
                    AndroidUtilities.runOnUIThread(() -> AlertsCreator.showSimpleAlert(fragment, getString(R.string.ErrorOccurred) + "\n" + error.text));
                }
                latch.countDown();
            }
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private long calcMessagesHash(ArrayList<TLRPC.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long acc = 0;
        for (TLRPC.Message message : messages) {
            acc = MediaDataController.calcHash(acc, message.id);
        }
        return acc;
    }

    public static String getTextOrBase64(byte[] data) {
        try {
            return utf8Decoder.decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return Base64.encodeToString(data, Base64.NO_PADDING | Base64.NO_WRAP);
        }
    }

    public void clearMessageFiles(MessageObject messageObject, Runnable done) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                var files = getFilesToMessage(messageObject);
                for (File file : files) {
                    if (file.exists() && !file.delete()) {
                        file.deleteOnExit();
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            messageObject.checkMediaExistance();
            AndroidUtilities.runOnUIThread(done);
        });
    }

    public ArrayList<File> getFilesToMessage(MessageObject messageObject) {
        ArrayList<File> files = new ArrayList<>();
        files.add(new File(messageObject.messageOwner.attachPath));
        files.add(getFileLoader().getPathToMessage(messageObject.messageOwner));
        var document = messageObject.getDocument();
        if (document != null) {
            files.add(getFileLoader().getPathToAttach(document, false));
            files.add(getFileLoader().getPathToAttach(document, true));
        }
        var media = messageObject.messageOwner.media;
        if (media != null && !media.alt_documents.isEmpty()) {
            media.alt_documents.forEach(doc -> {
                files.add(getFileLoader().getPathToAttach(doc, false));
                files.add(getFileLoader().getPathToAttach(doc, true));
            });
        }
        return files;
    }

    public static boolean shouldSkipTranslation(String message) {
        if (TextUtils.isEmpty(message)) {
            return true;
        }
        final int len = message.length();
        int wordStart = 0;
        for (int i = 0; i <= len; i++) {
            if (i == len || Character.isWhitespace(message.charAt(i))) {
                if (wordStart < i) {
                    if (!isSkippedWord(message, wordStart, i)) {
                        return false;
                    }
                }
                wordStart = i + 1;
            }
        }
        return true;
    }

    private static boolean isSkippedWord(String message, int start, int end) {
        int wordLen = end - start;
        if (wordLen == 0) return true;
        char firstChar = message.charAt(start);
        return switch (firstChar) {
            case '@', '#', '/' -> true;
            case 'h' -> {
                if (wordLen >= 8) {
                    yield message.startsWith("http://", start) || message.startsWith("https://", start);
                } else if (wordLen == 7) {
                    yield message.startsWith("http://", start);
                }
                yield false;
            }
            case 'f' -> {
                if (wordLen >= 6) {
                    yield message.startsWith("ftp://", start);
                }
                yield false;
            }
            default -> false;
        };
    }

    public void detectLanguageNow(MessageObject messageObject) {
        final long dialogId = messageObject.getDialogId();
        LanguageDetector.detectLanguage(messageObject.messageOwner.message, lng -> AndroidUtilities.runOnUIThread(() -> {
            String detectedLanguage = lng;
            if (detectedLanguage == null) {
                detectedLanguage = UNKNOWN_LANGUAGE;
            }
            messageObject.messageOwner.originalLanguage = detectedLanguage;
            getMessagesStorage().updateMessageCustomParams(dialogId, messageObject.messageOwner);
        }), err -> AndroidUtilities.runOnUIThread(() -> {
            messageObject.messageOwner.originalLanguage = UNKNOWN_LANGUAGE;
            getMessagesStorage().updateMessageCustomParams(dialogId, messageObject.messageOwner);
        }));
    }

    public static String getMessagePlainText(MessageObject messageObject, MessageObject.GroupedMessages messageGroup) {
        if (messageGroup != null) {
            MessageObject captionMessage = messageGroup.findCaptionMessageObject();
            if (captionMessage != null && !TextUtils.isEmpty(captionMessage.caption)) {
                return captionMessage.caption.toString();
            }
        }
        if (messageObject == null) {
            return null;
        }
        if (messageObject.isPoll()) {
            TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) messageObject.messageOwner.media).poll;
            StringBuilder pollText = new StringBuilder(poll.question.text).append("\n");
            for (TLRPC.PollAnswer answer : poll.answers) {
                pollText.append("\n\uD83D\uDD18 ");
                pollText.append(answer.text.text);
            }
            return pollText.toString();
        } else if (!TextUtils.isEmpty(messageObject.getVoiceTranscription())) {
            return messageObject.messageOwner.voiceTranscription;
        }
        return messageObject.messageOwner.message;
    }

    public static CharSequence getMessagePlainTextFull(MessageObject messageObject, MessageObject.GroupedMessages messageGroup) {
        StringBuilder text = new StringBuilder();
        if (messageGroup != null) {
            for (var groupedMessage : messageGroup.messages) {
                text.append(getMessagePlainText(groupedMessage, null));
            }
        }
        if (messageObject != null && messageObject.messageOwner != null) {
            if (messageObject.isPoll()) {
                TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) messageObject.messageOwner.media).poll;
                StringBuilder pollText = new StringBuilder(poll.question.text).append("\n");
                for (TLRPC.PollAnswer answer : poll.answers) {
                    pollText.append("\n\uD83D\uDD18 ");
                    pollText.append(answer.text.text);
                }
                text.append(pollText);
            } else if (!TextUtils.isEmpty(messageObject.getVoiceTranscription())) {
                text.append(messageObject.messageOwner.voiceTranscription);
            } else {
                text.append(messageObject.messageOwner.message);
            }
        }
        return text.toString();
    }

    public static boolean messageObjectIsFile(int type, MessageObject messageObject) {
        boolean canSave = (type == 4 || type == 5 || type == 6 || type == 10);
        boolean downloading = messageObject.loadedFileSize > 0;
        if (type == 4 && messageObject.getDocument() == null) {
            return false;
        }
        return canSave || downloading;
    }

    public boolean shouldKeepLocalMessageOnRestrictedEdit(TLRPC.Message oldMessage, TLRPC.Message newMessage) {
        if (oldMessage == null || newMessage == null) {
            return false;
        }
        return (oldMessage.restriction_reason == null || oldMessage.restriction_reason.isEmpty()) &&
                newMessage.restriction_reason != null && !newMessage.restriction_reason.isEmpty();
    }

    // Merged from xyz.nextalone.nagram.helper.MessageHelper.kt

    private static final SpannableStringBuilder[] spannedStrings = new SpannableStringBuilder[5];
    private static final Pattern ZALGO_PATTERN = Pattern.compile("\\p{M}{4}");
    private static final Pattern ZALGO_CLEANUP = Pattern.compile("\\p{M}+");

    public static void addMessageToClipboard(MessageObject selectedObject, Runnable callback) {
        String path = getPathToMessage(selectedObject);
        if (!TextUtils.isEmpty(path)) {
            File file = new File(path);
            if (file.exists()) {
                addFileToClipboard(file, callback);
            }
        }
    }

    public static void addMessageToClipboardAsSticker(MessageObject selectedObject, Runnable callback) {
        String path = getPathToMessage(selectedObject);
        try {
            if (!TextUtils.isEmpty(path)) {
                Bitmap image = BitmapFactory.decodeFile(path);
                if (image != null) {
                    File file2 = path.endsWith(".jpg") ? new File(path.replace(".jpg", ".webp")) : new File(path + ".webp");
                    try (FileOutputStream stream = new FileOutputStream(file2)) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            image.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, stream);
                        } else {
                            image.compress(Bitmap.CompressFormat.WEBP, 100, stream);
                        }
                    } finally {
                        image.recycle();
                    }
                    addFileToClipboard(file2, callback);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void addFileToClipboard(File file, Runnable callback) {
        try {
            Context context = ApplicationLoader.applicationContext;
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            Uri uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file);
            ClipData clip = ClipData.newUri(context.getContentResolver(), "label", uri);
            clipboard.setPrimaryClip(clip);
            if (callback != null) callback.run();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static String showForwardDate(MessageObject obj, String orig) {
        long date = obj.messageOwner != null && obj.messageOwner.fwd_from != null ? obj.messageOwner.fwd_from.date : 0;
        String day = LocaleController.formatDate(date);
        String time = LocaleController.getInstance().getFormatterDay().format(new Date(date * 1000L));
        boolean enabled = NaConfig.INSTANCE.getDateOfForwardedMsg().Bool();
        if (!enabled || date == 0) {
            return orig;
        } else {
            if (day.equals(time)) {
                return orig + " · " + day;
            } else {
                return orig + " · " + day + ' ' + time;
            }
        }
    }

    public static String zalgoFilter(String text) {
        CharSequence res = zalgoFilter((CharSequence) text);
        return res == null ? "" : res.toString();
    }

    public static CharSequence zalgoFilter(CharSequence text) {
        if (TextUtils.isEmpty(text)) return "";
        if (!NaConfig.INSTANCE.getZalgoFilter().Bool()) return text;
        if (text.length() < 4 || text.length() > 2048) return text;
        if (!ZALGO_PATTERN.matcher(text).find()) return text;

        if (!(text instanceof Spannable)) {
            return ZALGO_CLEANUP.matcher(text).replaceAll("");
        }

        SpannableStringBuilder ssb = new SpannableStringBuilder(text);
        Matcher matcher = ZALGO_CLEANUP.matcher(ssb);
        List<int[]> ranges = new ArrayList<>();

        while (matcher.find()) {
            ranges.add(new int[]{matcher.start(), matcher.end()});
        }

        for (int i = ranges.size() - 1; i >= 0; i--) {
            int[] range = ranges.get(i);
            ssb.delete(range[0], range[1]);
        }

        return ssb;
    }

    public static boolean containsMarkdown(CharSequence text) {
        CharSequence newText = AndroidUtilities.getTrimmedString(text);
        var message = new CharSequence[]{AndroidUtilities.getTrimmedString(newText)};
        var entities = MediaDataController.getInstance(UserConfig.selectedAccount).getEntities(message, true);
        return entities != null && !entities.isEmpty();
    }

    public static boolean canSendAsDice(String text, ChatActivity parentFragment, long dialog_id) {
        boolean canSendGames = true;
        if (DialogObject.isChatDialog(dialog_id)) {
            TLRPC.Chat chat = parentFragment.getMessagesController().getChat(-dialog_id);
            canSendGames = ChatObject.canSendStickers(chat);
        }
        // noinspection UnnecessaryUnicodeEscape
        return canSendGames && parentFragment.getMessagesController().diceEmojies.contains(text.replace("\ufe0f", ""));
    }

    private static String formatTime(int timestamp) {
        return LocaleController.formatString(R.string.formatDateAtTime,
                LocaleController.getInstance().getFormatterYear().format(new Date(timestamp * 1000L)),
                LocaleController.getInstance().getFormatterDay().format(new Date(timestamp * 1000L)));
    }

    public static CharSequence getTimeHintText(MessageObject messageObject) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (spannedStrings[3] == null) {
            spannedStrings[3] = new SpannableStringBuilder("\u200B");
            spannedStrings[3].setSpan(new ColoredImageSpan(Theme.chat_timeHintSentDrawable), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.append(spannedStrings[3]);
        text.append(' ');
        text.append(formatTime(messageObject.messageOwner.date));
        if (messageObject.messageOwner.edit_date != 0) {
            text.append("\n");
            if (spannedStrings[1] == null) {
                spannedStrings[1] = new SpannableStringBuilder("\u200B");
                spannedStrings[1].setSpan(new ColoredImageSpan(Theme.chat_editDrawable), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            text.append(spannedStrings[1]);
            text.append(' ');
            text.append(formatTime(messageObject.messageOwner.edit_date));
        }
        if (messageObject.messageOwner.fwd_from != null && messageObject.messageOwner.fwd_from.date != 0) {
            text.append("\n");
            if (spannedStrings[4] == null) {
                spannedStrings[4] = new SpannableStringBuilder("\u200B");
                ColoredImageSpan span = new ColoredImageSpan(Theme.chat_timeHintForwardDrawable);
                span.setSize(AndroidUtilities.dp(12f));
                spannedStrings[4].setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            text.append(spannedStrings[4]);
            text.append(' ');
            text.append(formatTime(messageObject.messageOwner.fwd_from.date));
        }
        return text;
    }

    public boolean isBlockedUser(long senderId) {
        if (!NekoConfig.ignoreBlocked.Bool()) {
            return false;
        }
        return getMessagesController().blockePeers.indexOfKey(senderId) >= 0 || AyuFilter.isCustomFilteredPeer(senderId);
    }

    public boolean isBlockedOrFiltered(TLRPC.Message message) {
        if (message == null) {
            return false;
        }
        return isBlockedOrFiltered(new MessageObject(currentAccount, message, false, false));
    }

    public boolean isBlockedOrFiltered(MessageObject message) {
        if (message == null) {
            return false;
        }
        long fromId = message.getFromChatId();
        boolean blocked = isBlockedUser(fromId) || AyuFilter.isBlockedChannel(fromId);
        return blocked || AyuFilter.isFiltered(message, null);
    }

    public static void copyVideoFrameToClipboard(File videoFile, long positionMs, View bulletinContainer, Theme.ResourcesProvider resourcesProvider, Runnable fallbackAction) {
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap bitmap = null;
            if (videoFile != null && videoFile.exists()) {
                bitmap = createVideoFrameBitmap(videoFile, positionMs);
            }
            if (bitmap == null) {
                if (fallbackAction != null) {
                    AndroidUtilities.runOnUIThread(fallbackAction);
                }
                return;
            }
            saveFrameBitmapToClipboard(bitmap, bulletinContainer, resourcesProvider);
        });
    }

    public static Bitmap createVideoFrameBitmap(File videoFile, long positionMs) {
        Bitmap bitmap = null;
        AnimatedFileDrawable fileDrawable = null;
        try {
            fileDrawable = new AnimatedFileDrawable(videoFile, true, 0, 0, null, null, null, 0, 0, true, null);
            bitmap = fileDrawable.getFrameAtTime(positionMs, true);
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (fileDrawable != null) {
                fileDrawable.recycle();
            }
        }
        if (bitmap == null) {
            bitmap = SendMessagesHelper.createVideoThumbnailAtTime(videoFile.getAbsolutePath(), positionMs * 1000);
        }
        return bitmap;
    }

    public static void saveFrameBitmapToClipboard(Bitmap bitmap, View bulletinContainer, Theme.ResourcesProvider resourcesProvider) {
        if (bitmap == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File tempFile = null;
            boolean saved = false;
            try {
                File cacheDir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
                tempFile = new File(cacheDir, "frame_" + System.currentTimeMillis() + ".png");
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    saved = bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                try {
                    bitmap.recycle();
                } catch (Exception ignore) {
                }
            }
            if (!saved) {
                if (tempFile != null) {
                    try {
                        if (!tempFile.delete()) {
                            tempFile.deleteOnExit();
                        }
                    } catch (Exception ignore) {
                    }
                }
                return;
            }
            final File finalTempFile = tempFile;
            AndroidUtilities.runOnUIThread(() -> {
                Runnable callback = null;
                if (bulletinContainer instanceof FrameLayout container) {
                    callback = () -> BulletinFactory.of(container, resourcesProvider).createCopyBulletin(getString(R.string.PhotoCopied)).show();
                }
                addFileToClipboard(finalTempFile, callback);
            });
        });
    }

    public static ArrayList<TLRPC.MessageEntity> reparseMessageEntities(ArrayList<TLRPC.MessageEntity> translatedEntities) {
        if (translatedEntities == null) {
            return null;
        }
        if (!NaConfig.INSTANCE.getTranslatorKeepMarkdown().Bool()) {
            ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
            for (TLRPC.MessageEntity entity : translatedEntities) {
                boolean isMarkdownEntity = entity instanceof TLRPC.TL_messageEntitySpoiler;
                isMarkdownEntity |= entity instanceof TLRPC.TL_messageEntityBold;
                isMarkdownEntity |= entity instanceof TLRPC.TL_messageEntityItalic;
                isMarkdownEntity |= entity instanceof TLRPC.TL_messageEntityCode;
                isMarkdownEntity |= entity instanceof TLRPC.TL_messageEntityStrike;
                isMarkdownEntity |= entity instanceof TLRPC.TL_messageEntityUnderline;
                if (!isMarkdownEntity) {
                    entities.add(entity);
                }
            }
            return entities;
        }
        return translatedEntities;
    }

    public static ArrayList<TLRPC.MessageEntity> getEntitiesForText(MessageObject messageObject, CharSequence text, boolean summarized) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        final TLRPC.Message messageOwner = messageObject.messageOwner;
        if (summarized) {
            if (messageObject.translated && messageOwner.translatedSummaryText != null) {
                return mergeAppendTranslatedEntities(
                    messageOwner.summaryText != null ? messageOwner.summaryText.entities : null,
                    messageOwner.translatedSummaryText,
                    text
                );
            } else if (messageOwner.summaryText != null) {
                return messageOwner.summaryText.entities;
            }
            return null;
        }
        if (messageObject.translated) {
            if (messageOwner.voiceTranscriptionOpen) {
                return messageOwner.translatedVoiceTranscription != null ? messageOwner.translatedVoiceTranscription.entities : null;
            } else {
                return messageOwner.translatedText != null ? mergeAppendTranslatedEntities(messageOwner.entities, messageOwner.translatedText, text) : null;
            }
        }
        if (messageOwner.translated && messageOwner.translatedText != null) {
            return mergeAppendTranslatedEntities(messageOwner.entities, messageOwner.translatedText, text);
        }
        return messageOwner.entities;
    }

    public static ArrayList<TLRPC.MessageEntity> mergeAppendTranslatedEntities(ArrayList<TLRPC.MessageEntity> baseEntities, TLRPC.TL_textWithEntities translatedText, CharSequence fullText) {
        if (fullText == null || translatedText == null || TextUtils.isEmpty(translatedText.text) || translatedText.entities == null) {
            return baseEntities;
        }
        final int fullLen = fullText.length();
        final int translatedLen = translatedText.text.length();
        if (translatedLen == 0 || fullLen < translatedLen) {
            return baseEntities;
        }
        final int translatedOffset = fullLen - translatedLen;
        final ArrayList<TLRPC.MessageEntity> translatedEntities = reparseMessageEntities(translatedText.entities);
        if (translatedEntities == null) {
            return baseEntities;
        }
        if (translatedOffset == 0) {
            return translatedEntities;
        }
        if (!TextUtils.regionMatches(fullText, translatedOffset, translatedText.text, 0, translatedLen)) {
            return baseEntities;
        }
        final ArrayList<TLRPC.MessageEntity> merged = new ArrayList<>();
        if (baseEntities != null) {
            merged.addAll(baseEntities);
        }
        for (int i = 0, size = translatedEntities.size(); i < size; i++) {
            final TLRPC.MessageEntity entity = translatedEntities.get(i);
            if (entity == null) {
                continue;
            }
            final TLRPC.MessageEntity copied = copyMessageEntity(entity);
            if (copied == null) {
                continue;
            }
            copied.offset += translatedOffset;
            if (copied.length <= 0 || copied.offset < 0 || copied.offset >= fullLen) {
                continue;
            }
            if (copied.offset + copied.length > fullLen) {
                copied.length = fullLen - copied.offset;
            }
            merged.add(copied);
        }
        return merged;
    }

    public static TLRPC.MessageEntity copyMessageEntity(TLRPC.MessageEntity entity) {
        if (entity == null) {
            return null;
        }
        NativeByteBuffer data = null;
        try {
            data = new NativeByteBuffer(entity.getObjectSize());
            entity.serializeToStream(data);
            data.rewind();
            final int constructor = data.readInt32(true);
            final TLRPC.MessageEntity result = TLRPC.MessageEntity.TLdeserialize(data, constructor, true);
            if (result instanceof TLRPC.TL_messageEntityCustomEmoji && entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
                ((TLRPC.TL_messageEntityCustomEmoji) result).document = ((TLRPC.TL_messageEntityCustomEmoji) entity).document;
            }
            return result;
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (data != null) {
                data.reuse();
            }
        }
        return null;
    }

    public static boolean shouldKeepOriginalForManualTranslation(int translatorMode) {
        return translatorMode == MessageTransKt.TRANSLATE_MODE_WITH_ORIGINAL_MANUAL_ONLY
            || translatorMode == MessageTransKt.TRANSLATE_MODE_WITH_ORIGINAL_ALL;
    }

    public static boolean shouldKeepOriginalForDisplay(int translatorMode, boolean manualTranslated, boolean autoTranslated) {
        if (translatorMode == MessageTransKt.TRANSLATE_MODE_WITH_ORIGINAL_ALL) {
            return manualTranslated || autoTranslated;
        }
        return translatorMode == MessageTransKt.TRANSLATE_MODE_WITH_ORIGINAL_MANUAL_ONLY && manualTranslated;
    }

    public static String buildTranslatedDisplayText(CharSequence originalText, TLRPC.TL_textWithEntities translatedText, boolean keepOriginal) {
        return buildTranslatedDisplayText(originalText, translatedText != null ? translatedText.text : null, keepOriginal);
    }

    public static String buildTranslatedDisplayText(CharSequence originalText, String translatedText, boolean keepOriginal) {
        if (TextUtils.isEmpty(translatedText)) {
            return originalText == null ? "" : originalText.toString();
        }
        if (!keepOriginal || TextUtils.isEmpty(originalText)) {
            return translatedText;
        }
        return originalText + MessageTransKt.TRANSLATION_SEPARATOR + translatedText;
    }

    public boolean sendMessageAsCopy(MessageObject messageObject, MessageObject.GroupedMessages messageGroup, long targetDialogId, MessageObject replyTo, MessageObject replyToTopMsg, ChatActivity.ReplyQuote quote, boolean hideCaption, boolean notify, int scheduleDate, int mode, String quickReplyShortcut, int quickReplyShortcutId, long payStars, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        return sendMessageAsCopy(messageObject, messageGroup, targetDialogId, replyTo, replyToTopMsg, quote, hideCaption, notify, scheduleDate, mode, quickReplyShortcut, quickReplyShortcutId, payStars, monoForumPeerId, suggestionParams, null);
    }

    public boolean sendMessageAsCopy(MessageObject messageObject, MessageObject.GroupedMessages messageGroup, long targetDialogId, MessageObject replyTo, MessageObject replyToTopMsg, ChatActivity.ReplyQuote quote, boolean hideCaption, boolean notify, int scheduleDate, int mode, String quickReplyShortcut, int quickReplyShortcutId, long payStars, long monoForumPeerId, MessageSuggestionParams suggestionParams, HashMap<String, String> repostMarkerParams) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        SendMessageChatArguments sendMessageChatArguments = createSendMessageChatArguments(quickReplyShortcut, quickReplyShortcutId);
        HashMap<String, String> messageParams = copyRepostMarkerParams(repostMarkerParams);
        CharSequence caption = hideCaption ? null : ChatActivity.getMessageCaption(messageObject, messageGroup, null);
        if (caption == null && (messageObject.type == 0 || messageObject.isAnimatedEmoji())) {
            caption = ChatActivity.getMessageContent(messageObject, 0, false);
        }
        if ((messageObject.isSticker() || messageObject.isAnimatedSticker()) && messageObject.getDocument() != null) {
            SendMessagesHelper.getInstance(currentAccount).sendSticker(messageObject.getDocument(), null, targetDialogId, null, null, replyTo, replyToTopMsg, null, quote, null, notify, scheduleDate, 0, false, null, sendMessageChatArguments, payStars, monoForumPeerId, suggestionParams, false, messageParams);
            return true;
        }
        String path = getPathToMessage(messageObject, currentAccount);
        if (!TextUtils.isEmpty(path)) {
            ArrayList<TLRPC.MessageEntity> entities = caption != null ? messageObject.messageOwner.entities : null;
            if (messageObject.isRoundVideo()) {
                VideoEditedInfo info = messageObject.videoEditedInfo != null ? messageObject.videoEditedInfo : new VideoEditedInfo();
                info.roundVideo = true;
                SendMessagesHelper.prepareSendingVideo(getAccountInstance(), path, info, null, null, targetDialogId, replyTo, replyToTopMsg, null, quote, entities, messageObject.messageOwner.ttl, null, notify, scheduleDate, 0, false, messageObject.hasMediaSpoilers(), caption, sendMessageChatArguments, 0, payStars, monoForumPeerId, suggestionParams, messageObject.messageOwner.invert_media, messageParams);
                return true;
            } else if (messageObject.isPhoto() || messageObject.isVideo()) {
                ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
                media.add(createSendingMediaInfo(messageObject, path, caption, entities, messageParams));
                SendMessagesHelper.prepareSendingMedia(getAccountInstance(), media, targetDialogId, replyTo, replyToTopMsg, null, quote, false, false, null, notify, scheduleDate, 0, mode, false, null, sendMessageChatArguments, 0, messageObject.messageOwner.invert_media, payStars, monoForumPeerId, suggestionParams);
                return true;
            } else if (messageObject.getDocument() != null) {
                ArrayList<String> paths = new ArrayList<>();
                paths.add(path);
                String mime = messageObject.getDocument().mime_type;
                SendMessagesHelper.prepareSendingDocuments(getAccountInstance(), paths, paths, null, caption != null ? caption.toString() : null, entities, mime, targetDialogId, replyTo, replyToTopMsg, null, quote, null, notify, scheduleDate, 0, null, sendMessageChatArguments, 0, messageObject.messageOwner.invert_media, payStars, monoForumPeerId, suggestionParams, messageParams);
                return true;
            }
        }
        if (caption != null && (messageObject.type == 0 || messageObject.isAnimatedEmoji()) && !TextUtils.isEmpty(caption)) {
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(caption.toString(), targetDialogId, replyTo, replyToTopMsg, null, false, messageObject.messageOwner.entities, null, messageParams, notify, scheduleDate, 0, null, false);
            params.replyQuote = quote;
            params.sendMessageChatArguments = sendMessageChatArguments;
            params.payStars = payStars;
            params.monoForumPeer = monoForumPeerId;
            params.suggestionParams = suggestionParams;
            SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
            return true;
        }
        return false;
    }


    public boolean sendMessagesAsCopy(ArrayList<MessageObject> messages, long targetDialogId, MessageObject replyTo, MessageObject replyToTopMsg, ChatActivity.ReplyQuote quote, boolean preserveOwnReply, boolean hideCaption, boolean notify, int scheduleDate, int mode, String quickReplyShortcut, int quickReplyShortcutId, long payStars, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        return sendMessagesAsCopy(messages, targetDialogId, replyTo, replyToTopMsg, quote, preserveOwnReply, hideCaption, notify, scheduleDate, mode, quickReplyShortcut, quickReplyShortcutId, payStars, monoForumPeerId, suggestionParams, null).sentAny;
    }

    public CopyDispatchResult sendMessagesAsCopy(ArrayList<MessageObject> messages, long targetDialogId, MessageObject replyTo, MessageObject replyToTopMsg, ChatActivity.ReplyQuote quote, boolean preserveOwnReply, boolean hideCaption, boolean notify, int scheduleDate, int mode, String quickReplyShortcut, int quickReplyShortcutId, long payStars, long monoForumPeerId, MessageSuggestionParams suggestionParams, HashMap<String, HashMap<String, String>> repostMarkerParamsBySource) {
        CopyDispatchResult result = new CopyDispatchResult();
        if (messages == null || messages.isEmpty()) {
            result.allDispatched = false;
            return result;
        }
        if (!canSendMessagesAsCopy(messages)) {
            result.allDispatched = false;
            return result;
        }
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            boolean needsFile = messageObject != null && messageObject.messageOwner != null && !messageObject.isSticker() && !messageObject.isAnimatedSticker() && !messageObject.isAnimatedEmoji() &&
                    (messageObject.isPhoto() || messageObject.isVideo() || messageObject.isRoundVideo() || messageObject.getDocument() != null);
            if (needsFile && TextUtils.isEmpty(getPathToMessage(messageObject, currentAccount))) {
                result.allDispatched = false;
                return result;
            }
        }
        long currentGroupId = 0;
        boolean currentInvertMedia = false;
        boolean currentAlbumIsDoc = false;
        ArrayList<SendMessagesHelper.SendingMediaInfo> media = null;
        // A grouped document/music album copies through its own per-child path (docAlbum* below) so each
        // member keeps its own extension, caption, entities and repost marker while sharing one group id;
        // photos and videos keep the prepareSendingMedia path. The two accumulators are never both active:
        // the isDoc == currentAlbumIsDoc term in sameAlbum below is what enforces it - a type change fails
        // sameAlbum, flushes whichever accumulator is filling and nulls both before the other starts, so
        // this does not lean on the server sending a type-homogeneous grouped_id. If a mixed group ever did
        // arrive it would just flush at the type boundary into two server groups on one timestamp - degraded
        // grouping, never a null-accumulator crash.
        ArrayList<String> docAlbumPaths = null;
        ArrayList<CharSequence> docAlbumCaptions = null;
        ArrayList<ArrayList<TLRPC.MessageEntity>> docAlbumEntities = null;
        ArrayList<HashMap<String, String>> docAlbumParams = null;
        // When preserving the source's own reply, replyTo/quote are resolved per message (and per
        // album, whose members share one reply target) below; otherwise they stay the passed values.
        MessageObject currentReply = replyTo;
        ChatActivity.ReplyQuote currentQuote = quote;

        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            HashMap<String, String> markerParams = resolveRepostMarkerParams(repostMarkerParamsBySource, messageObject);
            if (repostMarkerParamsBySource != null && markerParams == null) {
                result.allDispatched = false;
            }
            boolean isPhotoVideo = messageObject != null && messageObject.messageOwner != null && !messageObject.isRoundVideo()
                    && (messageObject.isPhoto() || messageObject.isVideo());
            boolean isGroupedDoc = messageObject != null && messageObject.messageOwner != null && !isPhotoVideo
                    && messageObject.getDocument() != null && messageObject.getGroupIdForUse() != 0
                    && !messageObject.isRoundVideo() && !messageObject.isSticker() && !messageObject.isAnimatedSticker() && !messageObject.isAnimatedEmoji();
            if (isPhotoVideo || isGroupedDoc) {
                String path = getPathToMessage(messageObject, currentAccount);
                long groupId = messageObject.getGroupIdForUse();
                boolean invertMedia = messageObject.messageOwner.invert_media;
                boolean isDoc = isGroupedDoc;
                boolean accumulating = media != null || docAlbumPaths != null;
                boolean sameAlbum = accumulating && groupId != 0 && groupId == currentGroupId
                        && invertMedia == currentInvertMedia && isDoc == currentAlbumIsDoc;
                if (accumulating && !sameAlbum) {
                    if (flushCopyAlbum(media, docAlbumPaths, docAlbumCaptions, docAlbumEntities, docAlbumParams, targetDialogId, currentReply, replyToTopMsg, currentQuote, notify, scheduleDate, mode, quickReplyShortcut, quickReplyShortcutId, currentInvertMedia, payStars, monoForumPeerId, suggestionParams)) {
                        result.sentAny = true;
                    }
                    media = null;
                    docAlbumPaths = null;
                    docAlbumCaptions = null;
                    docAlbumEntities = null;
                    docAlbumParams = null;
                    accumulating = false;
                }
                if (!accumulating) {
                    currentGroupId = groupId;
                    currentInvertMedia = invertMedia;
                    currentAlbumIsDoc = isDoc;
                    if (preserveOwnReply) {
                        MessageObject ownReply = getPreservableOwnReply(messageObject, targetDialogId, replyToTopMsg);
                        currentReply = ownReply != null ? ownReply : replyToTopMsg;
                        currentQuote = ownReply != null ? getOwnReplyQuote(messageObject) : null;
                    }
                    if (isDoc) {
                        docAlbumPaths = new ArrayList<>();
                        docAlbumCaptions = new ArrayList<>();
                        docAlbumEntities = new ArrayList<>();
                        docAlbumParams = new ArrayList<>();
                    } else {
                        media = new ArrayList<>();
                    }
                }
                CharSequence caption = hideCaption ? null : ChatActivity.getMessageCaption(messageObject, null, null);
                ArrayList<TLRPC.MessageEntity> entities = caption != null ? messageObject.messageOwner.entities : null;
                if (isDoc) {
                    docAlbumPaths.add(path);
                    docAlbumCaptions.add(caption);
                    docAlbumEntities.add(entities);
                    docAlbumParams.add(copyRepostMarkerParams(markerParams));
                } else {
                    media.add(createSendingMediaInfo(messageObject, path, caption, entities, markerParams));
                }
            } else {
                if (media != null || docAlbumPaths != null) {
                    if (flushCopyAlbum(media, docAlbumPaths, docAlbumCaptions, docAlbumEntities, docAlbumParams, targetDialogId, currentReply, replyToTopMsg, currentQuote, notify, scheduleDate, mode, quickReplyShortcut, quickReplyShortcutId, currentInvertMedia, payStars, monoForumPeerId, suggestionParams)) {
                        result.sentAny = true;
                    }
                    media = null;
                    docAlbumPaths = null;
                    docAlbumCaptions = null;
                    docAlbumEntities = null;
                    docAlbumParams = null;
                    currentGroupId = 0;
                }
                MessageObject messageReply = replyTo;
                ChatActivity.ReplyQuote messageQuote = quote;
                if (preserveOwnReply) {
                    MessageObject ownReply = getPreservableOwnReply(messageObject, targetDialogId, replyToTopMsg);
                    messageReply = ownReply != null ? ownReply : replyToTopMsg;
                    messageQuote = ownReply != null ? getOwnReplyQuote(messageObject) : null;
                }
                if (sendMessageAsCopy(messageObject, null, targetDialogId, messageReply, replyToTopMsg, messageQuote, hideCaption, notify, scheduleDate, mode, quickReplyShortcut, quickReplyShortcutId, payStars, monoForumPeerId, suggestionParams, markerParams)) {
                    result.sentAny = true;
                } else {
                    result.allDispatched = false;
                }
            }
        }
        if (media != null || docAlbumPaths != null) {
            if (flushCopyAlbum(media, docAlbumPaths, docAlbumCaptions, docAlbumEntities, docAlbumParams, targetDialogId, currentReply, replyToTopMsg, currentQuote, notify, scheduleDate, mode, quickReplyShortcut, quickReplyShortcutId, currentInvertMedia, payStars, monoForumPeerId, suggestionParams)) {
                result.sentAny = true;
            }
        }
        return result;
    }

    // NagramX: split a forward selection into copy-spread slots for #repost-spread. A slot is one
    // scheduled send: an album (every message sharing a non-zero grouped_id) is one slot; every
    // other message is its own slot. Slots come from captured album membership - grouping by
    // grouped_id, never assuming equal ids are consecutive in the list - and keep the caller's
    // order (each slot sits at the position of its first member), so the [merge box][current box]
    // ascending order the forward chokepoint already built is preserved without a global sort.
    public ArrayList<ArrayList<MessageObject>> buildCopySpreadSlots(ArrayList<MessageObject> messages) {
        ArrayList<ArrayList<MessageObject>> slots = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return slots;
        }
        HashMap<Long, ArrayList<MessageObject>> albumSlots = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject == null) {
                continue;
            }
            long groupId = messageObject.getGroupIdForUse();
            if (groupId != 0) {
                ArrayList<MessageObject> slot = albumSlots.get(groupId);
                if (slot == null) {
                    slot = new ArrayList<>();
                    albumSlots.put(groupId, slot);
                    slots.add(slot);
                }
                slot.add(messageObject);
            } else {
                ArrayList<MessageObject> slot = new ArrayList<>(1);
                slot.add(messageObject);
                slots.add(slot);
            }
        }
        return slots;
    }

    // NagramX: result of the one whole-dispatch pre-flight for #repost-spread. unsupportedCount is
    // messages the copy engine can't re-send at all; missingFileCount is messages whose media isn't
    // in cache right now. The cache check is inherently racy - File.exists() reserves nothing - so
    // ok means "admissible for the conditions observed here", not a guarantee the send can't still
    // fail later on eviction, upload error or server rejection (that path leaves deletion unarmed).
    public static class CopyPreflightResult {
        public boolean ok;
        public int unsupportedCount;
        public int missingFileCount;
    }

    public CopyPreflightResult preflightSpreadAsCopy(ArrayList<MessageObject> messages) {
        CopyPreflightResult result = new CopyPreflightResult();
        if (messages == null || messages.isEmpty()) {
            return result;
        }
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (!canSendSingleMessageAsCopy(messageObject)) {
                result.unsupportedCount++;
                continue;
            }
            boolean needsFile = messageObject.messageOwner != null && !messageObject.isSticker() && !messageObject.isAnimatedSticker() && !messageObject.isAnimatedEmoji() &&
                    (messageObject.isPhoto() || messageObject.isVideo() || messageObject.isRoundVideo() || messageObject.getDocument() != null);
            if (needsFile && TextUtils.isEmpty(getPathToMessage(messageObject, currentAccount))) {
                result.missingFileCount++;
            }
        }
        result.ok = result.unsupportedCount == 0 && result.missingFileCount == 0;
        return result;
    }

    // NagramX: a genuine messages.forwardMessages can't carry a reply header, so "Repost as Copy"
    // re-sends the message instead when it can do better. These helpers pull the reply the source
    // message itself points at, gated so a forum-topic anchor (which carries a full reply_to whose
    // target is the "topic created" message) is not treated as a real reply.
    public MessageObject getOwnReply(MessageObject messageObject) {
        if (messageObject == null || messageObject.currentAccount != currentAccount || messageObject.messageOwner == null) {
            return null;
        }
        if (DialogObject.isEncryptedDialog(messageObject.getDialogId())) {
            return messageObject.hasValidReplyMessageObject() ? messageObject.replyMessageObject : null;
        }
        TLRPC.MessageReplyHeader header = messageObject.messageOwner.reply_to;
        if (header == null || header.reply_to_msg_id <= 0
                || header.forum_topic && header.reply_to_msg_id == header.reply_to_top_id) {
            return null;
        }
        long replyDialogId = header.reply_to_peer_id != null
                ? DialogObject.getPeerDialogId(header.reply_to_peer_id) : messageObject.getDialogId();
        if (replyDialogId == 0) {
            return null;
        }
        // Decide first whether the loaded preview is this header's target at all. A preview left over
        // from another message says nothing about this one, so it may neither enrich the reference nor
        // veto it; only a preview that really is the target can refuse the reply.
        MessageObject loaded = messageObject.replyMessageObject;
        boolean loadedIsTarget = loaded != null && loaded.messageOwner != null
                && loaded.currentAccount == currentAccount
                && loaded.getId() == header.reply_to_msg_id
                && loaded.getDialogId() == replyDialogId;
        if (loadedIsTarget && (loaded.scheduled
                || loaded.messageOwner instanceof TLRPC.TL_messageEmpty
                || loaded.messageOwner.action instanceof TLRPC.TL_messageActionHistoryClear
                || loaded.messageOwner.action instanceof TLRPC.TL_messageActionTopicCreate)) {
            return null;
        }
        // The header is the address even when its preview hasn't loaded, and it carries the target's own
        // peer: resolving the dialog id back through getPeer() would re-guess channel vs chat from the
        // chat cache. A detached reference also avoids sendMessage redirecting a loaded forwarded target
        // to that target's channel origin.
        TLRPC.TL_message reference = new TLRPC.TL_message();
        reference.id = header.reply_to_msg_id;
        reference.dialog_id = replyDialogId;
        reference.peer_id = header.reply_to_peer_id != null
                ? header.reply_to_peer_id : getMessagesController().getPeer(replyDialogId);
        reference.from_id = reference.peer_id;
        reference.message = header.quote_text != null ? header.quote_text : "";
        if (loadedIsTarget) {
            reference.from_id = loaded.messageOwner.from_id;
            reference.date = loaded.messageOwner.date;
            reference.message = loaded.messageOwner.message;
            reference.media = loaded.messageOwner.media;
            reference.reply_to = loaded.messageOwner.reply_to;
            if (loaded.messageOwner.entities != null) {
                reference.entities = new ArrayList<>(loaded.messageOwner.entities);
            }
        }
        return new MessageObject(currentAccount, reference, false, false);
    }

    // Cloud replies carry their original peer through SendMessagesHelper's cross-chat reply path.
    // Secret-chat random ids can't be reused outside their own dialog, and neither can a Saved
    // Messages id: a reply *pointing into* Saved Messages means nothing once the copy lands
    // elsewhere. Saved Messages as the destination is just another cloud chat - a reply to some
    // other chat is qualified by reply_to_peer_id and resolves there like any cross-chat reply.
    // A forum still needs its destination topic root: sendMessage dereferences it for replies.
    public MessageObject getPreservableOwnReply(MessageObject messageObject, long targetDialogId, MessageObject replyToTopMsg) {
        if (messageObject == null
                || (messageObject.getDialogId() != targetDialogId
                    && (DialogObject.isEncryptedDialog(messageObject.getDialogId()) || DialogObject.isEncryptedDialog(targetDialogId)))) {
            return null;
        }
        if (replyToTopMsg == null && isForumDialog(targetDialogId)) {
            return null;
        }
        MessageObject reply = getOwnReply(messageObject);
        if (reply != null && reply.getDialogId() == getUserConfig().getClientUserId() && reply.getDialogId() != targetDialogId) {
            return null;
        }
        return reply;
    }

    private boolean isForumDialog(long targetDialogId) {
        if (!DialogObject.isChatDialog(targetDialogId)) {
            return false;
        }
        return ChatObject.isForum(getMessagesController().getChat(-targetDialogId));
    }

    public ChatActivity.ReplyQuote getOwnReplyQuote(MessageObject messageObject) {
        MessageObject target = getOwnReply(messageObject);
        if (target == null || target.messageOwner == null || target.messageOwner.message == null
                || messageObject.messageOwner == null || messageObject.messageOwner.reply_to == null) {
            return null;
        }
        TLRPC.MessageReplyHeader header = messageObject.messageOwner.reply_to;
        if (!header.quote || TextUtils.isEmpty(header.quote_text)) {
            return null;
        }
        MessageObject loaded = messageObject.replyMessageObject;
        if (!messageObject.hasValidReplyMessageObject()
                || loaded.currentAccount != currentAccount || loaded.getId() != target.getId()
                || loaded.getDialogId() != target.getDialogId()) {
            // Only the quoted fragment is available, not the target's full text. Initialize from that
            // fragment, then restore its source offset rather than interpreting it as offset zero.
            ChatActivity.ReplyQuote quote = ChatActivity.ReplyQuote.from(target, 0, header.quote_text.length());
            quote.text = header.quote_text;
            quote.entities = header.quote_entities != null ? new ArrayList<>(header.quote_entities) : null;
            quote.start = (header.flags & 1024) != 0 ? Math.max(0, header.quote_offset) : 0;
            quote.end = quote.start + quote.text.length();
            return quote;
        }
        // The server header already carries the quote the user made, so preserve it verbatim rather
        // than re-deriving from the target text. quote_offset is trusted only when its flag is set and
        // it still lands on the quote; otherwise the text has to occur exactly once, or the quote is
        // dropped to a plain reply so a duplicate occurrence can never carry the wrong formatting.
        String fulltext = target.messageOwner.message;
        String quoteText = header.quote_text;
        int start = -1;
        boolean hasOffset = (header.flags & 1024) != 0;
        if (hasOffset && header.quote_offset >= 0 && header.quote_offset + quoteText.length() <= fulltext.length()
                && fulltext.startsWith(quoteText, header.quote_offset)) {
            start = header.quote_offset;
        } else {
            int first = fulltext.indexOf(quoteText);
            if (first >= 0 && fulltext.indexOf(quoteText, first + 1) < 0) {
                start = first;
            }
        }
        if (start < 0) {
            return null;
        }
        ChatActivity.ReplyQuote quote = ChatActivity.ReplyQuote.from(target, start, start + quoteText.length());
        if (quote == null || !quote.isValid()) {
            return null;
        }
        // Carry the header's own text/entities (the list cloned so the send path never shares the
        // source message's list) and its offset, instead of the entities update() re-derived from the
        // current target text, which can differ if the target was edited after the quote was made.
        quote.text = quoteText;
        quote.entities = header.quote_entities != null ? new ArrayList<>(header.quote_entities) : null;
        quote.start = start;
        return quote;
    }

    // Decide once for the whole selection: reconstruct only when every message can be re-sent as a
    // copy (cached media, a supported type) and at least one carries a preservable reply. Otherwise
    // the caller forwards with drop_author exactly as before, so nothing that works today regresses.
    // The source no longer has to be the destination dialog. That rule existed because a chat-scoped
    // reply_to_msg_id resolved against another peer points at the wrong message, but getOwnReply now
    // addresses the target by the header's own peer - falling back to the *source's* dialog, never the
    // destination's - so a cross-chat copy carries a qualified reply that SendMessagesHelper converts
    // to a cross-chat quote. getPreservableOwnReply still refuses the cases that genuinely can't cross.
    public boolean shouldRepostAsCopyPreservingReply(ArrayList<MessageObject> messages, long targetDialogId, MessageObject replyToTopMsg) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        if (!canSendMessagesAsCopy(messages)) {
            return false;
        }
        boolean anyReply = false;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject == null || messageObject.messageOwner == null) {
                return false;
            }
            boolean needsFile = !messageObject.isSticker() && !messageObject.isAnimatedSticker() && !messageObject.isAnimatedEmoji() &&
                    (messageObject.isPhoto() || messageObject.isVideo() || messageObject.isRoundVideo() || messageObject.getDocument() != null);
            if (needsFile && TextUtils.isEmpty(getPathToMessage(messageObject, currentAccount))) {
                return false;
            }
            if (getPreservableOwnReply(messageObject, targetDialogId, replyToTopMsg) != null) {
                anyReply = true;
            }
        }
        return anyReply;
    }

    public boolean canSendMessageAsCopy(MessageObject messageObject, MessageObject.GroupedMessages messageGroup) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        if (messageGroup != null && !messageGroup.isDocuments) {
            for (int i = 0; i < messageGroup.messages.size(); i++) {
                if (!canSendSingleMessageAsCopy(messageGroup.messages.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return canSendSingleMessageAsCopy(messageObject);
    }

    public boolean canSendMessagesAsCopy(ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (!canSendSingleMessageAsCopy(messages.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean canSendSingleMessageAsCopy(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        if (messageObject.isPoll() || messageObject.isTodo() || messageObject.isLocation() || messageObject.isLiveLocation() || messageObject.isGame() || messageObject.isInvoice() || messageObject.isStoryMedia()) {
            return false;
        }
        if (messageObject.type == MessageObject.TYPE_CONTACT || messageObject.type == MessageObject.TYPE_GEO) {
            return false;
        }
        return messageObject.type == MessageObject.TYPE_TEXT || messageObject.isAnimatedEmoji() || messageObject.isSticker() || messageObject.isAnimatedSticker() || messageObject.isPhoto() || messageObject.isVideo() || messageObject.getDocument() != null || ChatActivity.getMessageCaption(messageObject, null, null) != null;
    }

    public boolean shouldRepeatMessagesAsCopy(ArrayList<MessageObject> messages, TLRPC.Chat currentChat) {
        if (getMessagesController().isChatNoForwards(currentChat)) {
            return true;
        }
        if (messages == null) {
            return false;
        }
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message != null && ((message.messageOwner != null && message.messageOwner.noforwards) || message.isAyuDeleted())) {
                return true;
            }
        }
        return false;
    }

    private SendMessagesHelper.SendingMediaInfo createSendingMediaInfo(MessageObject messageObject, String path, CharSequence caption, ArrayList<TLRPC.MessageEntity> entities, HashMap<String, String> repostMarkerParams) {
        SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
        info.path = path;
        info.isVideo = messageObject.isVideo();
        info.caption = caption != null ? caption.toString() : null;
        info.entities = entities;
        info.ttl = messageObject.messageOwner.ttl;
        info.hasMediaSpoilers = messageObject.hasMediaSpoilers();
        info.params = copyRepostMarkerParams(repostMarkerParams);
        return info;
    }

    // Flush whichever copy-album accumulator is filling. At most one of the two is non-empty at a boundary
    // because the sameAlbum guard in sendMessagesAsCopy flushes and nulls both on any type change, not
    // because the server is trusted to keep a grouped_id type-homogeneous: photos/videos go through
    // prepareSendingMedia, a document/music album goes through the per-child prepareSendingDocumentAlbumAsCopy
    // so each member keeps its own extension, caption, entities and marker while sharing one group id.
    // Both being non-empty here would only mean a mixed group split into two server groups upstream, never
    // a crash. Returns whether anything was dispatched.
    private boolean flushCopyAlbum(ArrayList<SendMessagesHelper.SendingMediaInfo> media, ArrayList<String> docPaths, ArrayList<CharSequence> docCaptions, ArrayList<ArrayList<TLRPC.MessageEntity>> docEntities, ArrayList<HashMap<String, String>> docParams, long targetDialogId, MessageObject replyTo, MessageObject replyToTopMsg, ChatActivity.ReplyQuote quote, boolean notify, int scheduleDate, int mode, String quickReplyShortcut, int quickReplyShortcutId, boolean invertMedia, long payStars, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        boolean sent = false;
        if (media != null && !media.isEmpty()) {
            SendMessagesHelper.prepareSendingMedia(getAccountInstance(), media, targetDialogId, replyTo, replyToTopMsg, null, quote, false, media.size() > 1, null, notify, scheduleDate, 0, mode, false, null, createSendMessageChatArguments(quickReplyShortcut, quickReplyShortcutId), 0, invertMedia, payStars, monoForumPeerId, suggestionParams);
            sent = true;
        }
        if (docPaths != null && !docPaths.isEmpty()) {
            SendMessagesHelper.prepareSendingDocumentAlbumAsCopy(getAccountInstance(), docPaths, new ArrayList<>(docPaths), docCaptions, docEntities, docParams, targetDialogId, replyTo, replyToTopMsg, quote, notify, scheduleDate, createSendMessageChatArguments(quickReplyShortcut, quickReplyShortcutId), invertMedia, payStars, monoForumPeerId, suggestionParams);
            sent = true;
        }
        return sent;
    }

    private static SendMessageChatArguments createSendMessageChatArguments(String quickReplyShortcut, int quickReplyShortcutId) {
        SendMessageChatArguments.Builder builder = new SendMessageChatArguments.Builder();
        builder.setQuickReplyShortcut(quickReplyShortcut, quickReplyShortcutId);
        return builder.build();
    }
}
