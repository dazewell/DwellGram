package com.dazewell.gram.composer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.QuoteSpan;
import org.telegram.ui.Components.TextStyleSpan;
import org.telegram.ui.Components.URLSpanReplacement;
import org.telegram.utils.code.highlight.CodeHighlighting;

import java.util.Locale;

/**
 * Pasting a lone URL over selected text links the selection to it instead of
 * replacing it, the same result as the input's own Link action.
 */
public final class PasteAsLink {

    public interface SpanFactory {
        URLSpanReplacement create(String url);
    }

    private PasteAsLink() {
    }

    /** Returns true when the text was consumed as a link. */
    public static boolean tryApply(EditText editText, CharSequence text, SpanFactory spanFactory) {
        String url = linkFor(editText, text);
        if (url == null) {
            return false;
        }
        Editable editable = editText.getText();
        int start = Math.min(editText.getSelectionStart(), editText.getSelectionEnd());
        int end = Math.max(editText.getSelectionStart(), editText.getSelectionEnd());
        // Same span cleanup as EditTextCaption.makeSelectedUrl, so a pasted link
        // and one made from the formatting menu clear the same styles.
        CharacterStyle[] spans = editable.getSpans(start, end, CharacterStyle.class);
        for (CharacterStyle oldSpan : spans) {
            if (oldSpan instanceof AnimatedEmojiSpan || oldSpan instanceof QuoteSpan.QuoteStyleSpan) {
                continue;
            }
            int spanStart = editable.getSpanStart(oldSpan);
            int spanEnd = editable.getSpanEnd(oldSpan);
            editable.removeSpan(oldSpan);
            if (spanStart < start) {
                editable.setSpan(oldSpan, spanStart, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (spanEnd > end) {
                // One span instance can't cover both sides, so a span straddling the
                // selection needs a copy for the suffix (as EditTextCaption.removeStyle does).
                CharacterStyle suffix = spanStart < start ? copyOf(oldSpan) : oldSpan;
                if (suffix != null) {
                    editable.setSpan(suffix, end, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        try {
            editable.setSpan(spanFactory.create(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        editText.setSelection(end);
        return true;
    }

    // Styles, links and code blocks are what the composer's formatting puts down; any other
    // straddling type stays on the prefix only.
    private static CharacterStyle copyOf(CharacterStyle span) {
        if (span instanceof URLSpanReplacement) {
            URLSpanReplacement url = (URLSpanReplacement) span;
            TextStyleSpan.TextStyleRun run = url.getTextStyleRun();
            return new URLSpanReplacement(url.getURL(), run == null ? null : new TextStyleSpan.TextStyleRun(run));
        }
        if (span instanceof TextStyleSpan) {
            return new TextStyleSpan(new TextStyleSpan.TextStyleRun(((TextStyleSpan) span).getTextStyleRun()));
        }
        if (span instanceof CodeHighlighting.Span) {
            CodeHighlighting.Span code = (CodeHighlighting.Span) span;
            TextStyleSpan.TextStyleRun run = code.style == null ? null : new TextStyleSpan.TextStyleRun(code.style);
            return new CodeHighlighting.Span(code.smallerSize, code.currentType, run, code.lng, code.code);
        }
        return null;
    }

    /** Whether a paste of the current clipboard would become a link, for paste handlers that run first. */
    public static boolean wouldApply(EditText editText) {
        try {
            ClipboardManager clipboard = (ClipboardManager) editText.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            return clipboard != null && linkFor(editText, clipText(editText, clipboard.getPrimaryClip())) != null;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    /** The clip's text, read only when there is a selection to link so a plain paste never touches it. */
    public static CharSequence clipText(EditText editText, ClipData clipData) {
        if (clipData == null || clipData.getItemCount() != 1 || selectionRange(editText) == null) {
            return null;
        }
        try {
            // coerceToText reads the plain text of an HTML clip too, so a URL copied off a page counts.
            return clipData.getItemAt(0).coerceToText(editText.getContext());
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Keyboard clipboard managers (Heliboard, Gboard) insert a picked entry with commitText,
     * never reaching the paste menu, so the composer's input connection routes it here too.
     */
    public static InputConnection wrap(InputConnection ic, EditTextCaption editText) {
        if (ic == null) {
            return null;
        }
        return new InputConnectionWrapper(ic, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                return asLink(text) || super.commitText(text, newCursorPosition);
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition, android.view.inputmethod.TextAttribute textAttribute) {
                return asLink(text) || super.commitText(text, newCursorPosition, textAttribute);
            }

            private boolean asLink(CharSequence text) {
                // An active composing region is what a commit replaces, not the selection.
                return BaseInputConnection.getComposingSpanStart(editText.getText()) < 0 && editText.pasteLinkText(text);
            }
        };
    }

    private static int[] selectionRange(EditText editText) {
        Editable editable = editText.getText();
        int start = Math.max(0, Math.min(editText.getSelectionStart(), editText.getSelectionEnd()));
        int end = Math.min(editable.length(), Math.max(editText.getSelectionStart(), editText.getSelectionEnd()));
        if (start >= end || TextUtils.getTrimmedLength(editable.subSequence(start, end)) == 0) {
            return null;
        }
        return new int[]{start, end};
    }

    private static String linkFor(EditText editText, CharSequence text) {
        if (text == null || selectionRange(editText) == null) {
            return null;
        }
        String url = text.toString().trim();
        return isLink(url) ? url : null;
    }

    // Only an explicit scheme counts: a bare word like "notes.txt" pasted over a
    // selection is meant to replace it.
    static boolean isLink(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String scheme : new String[]{"https://", "http://", "tg://"}) {
            if (lower.startsWith(scheme) && lower.length() > scheme.length()) {
                return true;
            }
        }
        return false;
    }
}
