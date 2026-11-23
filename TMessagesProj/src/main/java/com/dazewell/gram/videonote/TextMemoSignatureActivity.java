package com.dazewell.gram.videonote;

import android.content.Context;
import android.text.InputType;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.LayoutHelper;

/** The same selection formatting and entities as the chat composer, without a send action. */
public final class TextMemoSignatureActivity extends BaseFragment {
    private EditTextCaption editor;

    @Override
    public View createView(Context context) {
        android.util.Log.i("MemoSignature", "NAX_SMOKE_text-memo-signature-editor PATH editor-create");
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.TextMemoSignature));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    TextMemoSignature signature = readEditor();
                    if (signature != null) {
                        try {
                            signature.save();
                            finishFragment();
                        } catch (IllegalArgumentException e) {
                            showInvalidSignature();
                        }
                    }
                } else if (id == 2) {
                    editor.setText(TextMemoSignature.preset().toEditor(editor.getPaint().getFontMetricsInt()));
                    editor.invalidateQuotes(true);
                    editor.invalidateEffects();
                }
            }
        });
        actionBar.createMenu().addItem(1, LocaleController.getString(R.string.Save));
        actionBar.createMenu().addItem(0, R.drawable.ic_ab_other)
                .addSubItem(2, R.drawable.msg_reset, LocaleController.getString(R.string.Reset));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
        fragmentView = scroll;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(20));
        scroll.addView(content);

        editor = new EditTextCaption(context, getResourceProvider()) {
            @Override
            protected void extendActionMode(ActionMode mode, Menu menu) {
                ChatActivity.fillActionModeMenu(menu, null, true, true);
            }
        };
        editor.setWindowView(scroll);
        editor.setAllowTextEntitiesIntersection(true);
        editor.pasteUrlAsLink = true;
        editor.setTextSize(18);
        editor.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        editor.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, getResourceProvider()));
        editor.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        editor.setBackgroundDrawable(null);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        editor.setMinLines(5);
        editor.setHint(LocaleController.getString(R.string.TextMemoSignatureHint));
        editor.setText(TextMemoSignature.load().toEditor(editor.getPaint().getFontMetricsInt()));
        editor.invalidateQuotes(true);
        editor.invalidateEffects();
        content.addView(editor, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView help = new TextView(context);
        help.setText(LocaleController.formatString(R.string.TextMemoSignatureHelp, TextMemoSignature.MAX_LENGTH));
        help.setTextSize(14);
        help.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, getResourceProvider()));
        content.addView(help, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 12));

        TextView preview = new TextView(context);
        preview.setText(LocaleController.getString(R.string.TextMemoSignaturePreview));
        preview.setTextSize(16);
        preview.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        preview.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, getResourceProvider()));
        preview.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, getResourceProvider()), 2));
        preview.setOnClickListener(v -> showPreview());
        content.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        android.util.Log.i("MemoSignature", "NAX_SMOKE_text-memo-signature-editor END editor-ready");
        return fragmentView;
    }

    @Override
    public boolean extendActionMode(Menu menu) {
        if (editor != null && editor.hasSelection()) {
            ChatActivity.fillActionModeMenu(menu, null, true, true);
        }
        return true;
    }

    private TextMemoSignature readEditor() {
        CharSequence text = editor.getTextToUse();
        if (text.length() > TextMemoSignature.MAX_LENGTH) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.formatString(
                    R.string.TextMemoSignatureLimit, TextMemoSignature.MAX_LENGTH)).show();
            return null;
        }
        try {
            return TextMemoSignature.fromEditor(currentAccount, text);
        } catch (IllegalArgumentException e) {
            showInvalidSignature();
            return null;
        }
    }

    private void showInvalidSignature() {
        BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.TextMemoSignatureInvalid)).show();
    }

    private void showPreview() {
        TextMemoSignature signature = readEditor();
        if (signature == null) {
            return;
        }
        if (signature.text.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.TextMemoSignatureEmpty)).show();
            return;
        }
        editor.hideActionMode();
        AndroidUtilities.hideKeyboard(editor);
        TLRPC.TL_message message = new TLRPC.TL_message();
        // Use the send-time entity resolution even while the global switch is off.
        CharSequence[] text = {LocaleController.getString(R.string.TextMemoSignaturePreviewBody)};
        message.entities = signature.append(currentAccount, text, null);
        message.message = text[0].toString();
        message.out = true;
        message.id = 1;
        message.date = getConnectionsManager().getCurrentTime();
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = getUserConfig().getClientUserId();
        message.peer_id = message.from_id;
        message.dialog_id = getUserConfig().getClientUserId();
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.flags = 256 | 512 | 128;
        MessageObject object = new MessageObject(currentAccount, message, true, false);
        object.eventId = 1;
        ChatMessageCell cell = new ChatMessageCell(getParentActivity(), currentAccount, false, null, getResourceProvider());
        cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {});
        cell.setMessageObject(object, null, false, false, false);
        ScrollView scroll = new ScrollView(getParentActivity());
        scroll.addView(cell);
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.TextMemoSignaturePreview))
                .setView(scroll).setPositiveButton(LocaleController.getString(R.string.Close), null).create());
    }

    @Override
    public void onFragmentDestroy() {
        if (editor != null) {
            editor.hideActionMode();
            AndroidUtilities.hideKeyboard(editor);
        }
        super.onFragmentDestroy();
    }
}
