package com.dazewell.gram.videonote;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
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
                }
            }
        });
        actionBar.createMenu().addItem(1, LocaleController.getString(R.string.Save));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
        fragmentView = scroll;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(20));
        scroll.addView(content);

        TextView label = new TextView(context);
        label.setText(LocaleController.getString(R.string.TextMemoSignatureField));
        label.setTextSize(14);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        content.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        editor = new EditTextCaption(context, getResourceProvider()) {
            @Override
            protected void extendActionMode(ActionMode mode, Menu menu) {
                ChatActivity.fillActionModeMenu(menu, null, true, true);
            }

            @Override
            protected boolean showActionModeAboveSelectionHandles() {
                return true;
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        && (canScrollVertically(-1) || canScrollVertically(1))) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                // ScrollView releases interception when the gesture ends.
                return super.onTouchEvent(event);
            }
        };
        editor.setId(View.generateViewId());
        label.setLabelFor(editor.getId());
        editor.setAllowTextEntitiesIntersection(true);
        editor.pasteUrlAsLink = true;
        editor.setTextSize(18);
        editor.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        editor.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, getResourceProvider()));
        editor.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] { android.R.attr.state_focused }, fieldBackground(Theme.key_windowBackgroundWhiteInputFieldActivated));
        background.addState(new int[0], fieldBackground(Theme.key_windowBackgroundWhiteInputField));
        editor.setBackground(background);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        editor.setMinLines(3);
        editor.setMaxLines(5);
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

        addEditorAction(content, R.string.TextMemoSignaturePreview, this::showPreview);
        addEditorAction(content, R.string.TextMemoSignatureReset, () -> {
            editor.hideActionMode();
            editor.setText(TextMemoSignature.preset().toEditor(editor.getPaint().getFontMetricsInt()));
            editor.invalidateQuotes(true);
            editor.invalidateEffects();
        });
        return fragmentView;
    }

    private GradientDrawable fieldBackground(int strokeColor) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(AndroidUtilities.dp(12));
        background.setColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));
        background.setStroke(AndroidUtilities.dp(1), Theme.getColor(strokeColor, getResourceProvider()));
        return background;
    }

    private void addEditorAction(LinearLayout content, int label, Runnable action) {
        TextView button = new TextView(content.getContext());
        button.setText(LocaleController.getString(label));
        button.setTextSize(16);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(AndroidUtilities.dp(48));
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, getResourceProvider()));
        button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, getResourceProvider()), 2));
        button.setOnClickListener(v -> action.run());
        content.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
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
