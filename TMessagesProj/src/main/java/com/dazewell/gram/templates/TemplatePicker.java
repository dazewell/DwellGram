package com.dazewell.gram.templates;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.ItemOptions;

import java.util.ArrayList;

/**
 * The composer's Templates button: a popup of the saved templates. A tap sends the template straight
 * away when the field is empty and inserts it otherwise; a hold always inserts.
 */
public final class TemplatePicker {

    private TemplatePicker() {
    }

    public static void show(ChatActivityEnterView enterView, View anchor) {
        ChatActivity fragment = enterView.getParentFragment();
        if (fragment == null || fragment.getParentActivity() == null || anchor == null) {
            return;
        }
        ArrayList<TemplateStore.Template> templates = TemplateStore.load();
        ItemOptions options = ItemOptions.makeOptions(fragment, anchor);
        if (templates.isEmpty()) {
            options.addText(LocaleController.getString(R.string.TemplatePickerEmpty), 13);
        }
        for (TemplateStore.Template template : templates) {
            final String text = template.text;
            options.add(0, TemplateStore.label(text), () -> use(enterView, text, true), () -> use(enterView, text, false));
        }
        options.addGap();
        options.add(R.drawable.msg_customize, LocaleController.getString(R.string.TemplateManage), () -> {
            if (fragment.getParentActivity() != null) {
                fragment.presentFragment(new TemplateMessagesActivity());
            }
        });
        options.show();
    }

    private static void use(ChatActivityEnterView enterView, String text, boolean forceInsert) {
        ChatActivity fragment = enterView.getParentFragment();
        EditTextCaption field = enterView.getEditField();
        if (fragment == null || fragment.getParentActivity() == null || field == null || !enterView.isAttachedToWindow()) {
            return;
        }
        // Sending is only safe from an empty field: anything typed would be lost, and editing, the
        // scheduled list and slow mode each need the user's own Send. A second tap inside the ~200 ms
        // after a send still sees the first template in the field and so inserts; the pending clear
        // then wipes it. Reaching that means reopening the popup inside that window.
        boolean send = !forceInsert
                && field.length() == 0
                && !enterView.isEditingMessage()
                && !enterView.isInScheduleMode()
                && enterView.getSlowModeTimer() == null;
        if (send) {
            enterView.sendTemplateNow(text);
            return;
        }
        // A selection dragged backwards has its start after its end, so order the two before replacing.
        int length = field.length();
        int a = Math.max(0, Math.min(field.getSelectionStart(), length));
        int b = Math.max(0, Math.min(field.getSelectionEnd(), length));
        int start = Math.min(a, b);
        enterView.replaceWithText(start, Math.max(a, b) - start, text, true);
        field.requestFocus();
    }
}
