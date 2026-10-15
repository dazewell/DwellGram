package com.dazewell.gram.composer;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Editable;
import android.text.Spanned;
import android.view.View;
import android.widget.ImageView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.QuoteSpan;
import org.telegram.ui.Components.ScaleStateListAnimator;
import com.dazewell.gram.ui.ComposerToolbarLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The text actions in the composer toolbar: every style the selection menu offers, Select All,
 * Cut/Copy/Paste, and the Templates button.
 *
 * Each one is its own view registered with the toolbar under its own key, so the saved layout can put
 * them wherever the user wants and interleave them with the rest of the row.
 */
public final class ComposerFormattingActions {

    private static final int DISABLED_ICON_ALPHA = 115;

    private final ChatActivityEnterView enterView;
    private final Theme.ResourcesProvider resourcesProvider;
    private final boolean isChat;
    private final List<Action> actions = new ArrayList<>();
    private boolean updating;
    private boolean destroyed;
    private int selectionStart = -1;
    private int selectionEnd = -1;

    private static final class Action {
        final ComposerButtons.Button button;
        final ImageView view;

        Action(ComposerButtons.Button button, ImageView view) {
            this.button = button;
            this.view = view;
        }
    }

    public ComposerFormattingActions(ChatActivityEnterView enterView, ComposerToolbarLayout toolbar, Theme.ResourcesProvider resourcesProvider, boolean isChat) {
        this.enterView = enterView;
        this.resourcesProvider = resourcesProvider;
        this.isChat = isChat;
        for (ComposerButtons.Button button : ComposerButtons.all()) {
            if (button.kind != ComposerButtons.KIND_FORMAT && button.kind != ComposerButtons.KIND_TEXT && button.kind != ComposerButtons.KIND_CLIPBOARD
                    && button.kind != ComposerButtons.KIND_ACTION) {
                continue;
            }
            if (!ComposerLayout.isVisible(button.key)) {
                continue;
            }
            Action action = new Action(button, createView(toolbar.getContext(), button));
            action.view.setVisibility(View.GONE);
            actions.add(action);
            toolbar.addConfigurable(button.key, action.view);
        }
    }

    public void onSelectionChanged() {
        refresh();
    }

    public void onTextChanged() {
        refresh();
    }

    public void refresh() {
        if (updating) {
            return;
        }
        updating = true;
        try {
            EditTextCaption editText = enterView.getEditField();
            int rawStart = editText != null ? editText.getSelectionStart() : -1;
            int rawEnd = editText != null ? editText.getSelectionEnd() : -1;
            int start = Math.min(rawStart, rawEnd);
            int end = Math.max(rawStart, rawEnd);
            boolean composerAvailable = !destroyed
                    && enterView.isComposerToolbarEnabled()
                    && editText != null
                    && editText.getVisibility() == View.VISIBLE
                    && !enterView.isRichDraftActive();
            boolean hasSelection = composerAvailable && start >= 0 && end > start;
            if (hasSelection) {
                selectionStart = start;
                selectionEnd = end;
            } else {
                clearSelection();
            }
            boolean hasText = composerAvailable && editText.getText() != null && editText.getText().length() > 0;
            for (Action action : actions) {
                boolean visible = composerAvailable && (isChat || !"quote".equals(action.button.key));
                action.view.setVisibility(visible ? View.VISIBLE : View.GONE);
                if (!visible) {
                    continue;
                }
                if (action.button.kind == ComposerButtons.KIND_ACTION) {
                    setActionEnabled(action.view, true);
                } else if (action.button.kind == ComposerButtons.KIND_TEXT) {
                    setActionEnabled(action.view, hasText);
                } else if (ComposerButtons.PASTE.equals(action.button.key)) {
                    // Pasting replaces/inserts at the cursor regardless of selection, so it doesn't need one.
                    setActionEnabled(action.view, composerAvailable);
                } else if ("quote".equals(action.button.key)) {
                    setActionEnabled(action.view, hasSelection && isQuoteAvailable(editText.getText(), start, end));
                } else {
                    setActionEnabled(action.view, hasSelection);
                }
            }
        } finally {
            updating = false;
        }
    }

    public void onDestroy() {
        destroyed = true;
        clearSelection();
        for (Action action : actions) {
            action.view.setVisibility(View.GONE);
        }
    }

    public void updateColors() {
        for (Action action : actions) {
            action.view.setBackground(ComposerToolbarLayout.panelSelector(Theme.getColor(Theme.key_listSelector, resourcesProvider)));
            applyIconColor(action.view, action.view.isEnabled());
        }
    }

    private ImageView createView(Context context, ComposerButtons.Button button) {
        ImageView view = new ImageView(context);
        view.setImageResource(button.iconRes);
        view.setBackground(ComposerToolbarLayout.panelSelector(Theme.getColor(Theme.key_listSelector, resourcesProvider)));
        view.setContentDescription(LocaleController.getString(button.titleRes));
        applyIconColor(view, true);
        ScaleStateListAnimator.apply(view);
        view.setOnClickListener(v -> apply(button, v));
        return view;
    }

    private void apply(ComposerButtons.Button button, View anchor) {
        if (updating || destroyed) {
            return;
        }
        if (button.kind == ComposerButtons.KIND_ACTION) {
            if (ComposerButtons.TEMPLATES.equals(button.key)) {
                com.dazewell.gram.templates.TemplatePicker.show(enterView, anchor);
            }
            return;
        }
        updating = true;
        try {
            EditTextCaption editText = enterView.getEditField();
            Editable editable = editText != null ? editText.getText() : null;
            if (editable == null) {
                return;
            }
            if (button.kind == ComposerButtons.KIND_TEXT) {
                // Setting the selection directly rather than firing the platform menu action, which
                // would also raise the text context popup over the composer.
                if (editable.length() > 0) {
                    editText.requestFocus();
                    editText.setSelection(0, editable.length());
                }
                return;
            }
            if (button.kind == ComposerButtons.KIND_CLIPBOARD) {
                // Cut/copy/paste run through onTextContextMenuItem directly: it's the fork's own
                // clipboard logic (HTML-aware paste, quote-span handling), and unlike performMenuAction
                // it must not go through the platform selection popup.
                editText.requestFocus();
                if (ComposerButtons.PASTE.equals(button.key)) {
                    editText.onTextContextMenuItem(button.menuAction);
                    return;
                }
                if (selectionStart < 0 || selectionEnd <= selectionStart || selectionEnd > editable.length()) {
                    clearSelection();
                    return;
                }
                editText.setSelection(selectionStart, selectionEnd);
                editText.onTextContextMenuItem(button.menuAction);
                return;
            }
            if (selectionStart < 0 || selectionEnd <= selectionStart || selectionEnd > editable.length()) {
                clearSelection();
                return;
            }
            editText.setSelectionOverride(selectionStart, selectionEnd);
            try {
                editText.performMenuAction(button.menuAction);
            } finally {
                editText.setSelectionOverride(-1, -1);
            }
        } finally {
            updating = false;
            refresh();
        }
    }

    private void clearSelection() {
        selectionStart = -1;
        selectionEnd = -1;
    }

    // Dim the icon rather than the view: the row gives up a slot once a child drops below half alpha, so
    // fading the view itself would collapse every action out of the toolbar the moment nothing is selected.
    private void setActionEnabled(ImageView action, boolean enabled) {
        if (action.isEnabled() == enabled) {
            return;
        }
        action.setEnabled(enabled);
        applyIconColor(action, enabled);
    }

    private void applyIconColor(ImageView action, boolean enabled) {
        int color = Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider);
        if (!enabled) {
            color = ColorUtils.setAlphaComponent(color, DISABLED_ICON_ALPHA);
        }
        action.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
    }

    private static boolean isQuoteAvailable(Editable editable, int start, int end) {
        if (!(editable instanceof Spanned)) {
            return false;
        }
        QuoteSpan.QuoteStyleSpan[] spans = ((Spanned) editable).getSpans(start, end, QuoteSpan.QuoteStyleSpan.class);
        if (spans == null || spans.length == 0) {
            return true;
        }
        // Also enable as a toggle-off when the selection exactly contains one existing quote block;
        // any other overlap (partial, non-containing) stays disabled so the button can never create
        // a second overlapping quote block on top of a messy selection. start/end here are already
        // normalized (min/max) by refresh(), the only caller.
        return spans.length == 1 && editable.getSpanStart(spans[0]) <= start && editable.getSpanEnd(spans[0]) >= end;
    }
}
