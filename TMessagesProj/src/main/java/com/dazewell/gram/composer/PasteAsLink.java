package com.dazewell.gram.composer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.widget.EditText;

import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.QuoteSpan;
import org.telegram.ui.Components.URLSpanReplacement;

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

    /** Returns true when the paste was consumed as a link. */
    public static boolean tryApply(EditText editText, ClipData clipData, SpanFactory spanFactory) {
        String url = linkFor(editText, clipData);
        if (url == null) {
            return false;
        }
        Editable editable = editText.getText();
        int start = Math.min(editText.getSelectionStart(), editText.getSelectionEnd());
        int end = Math.max(editText.getSelectionStart(), editText.getSelectionEnd());
        // Same span cleanup as EditTextCaption.makeSelectedUrl, so a pasted link
        // and one made from the formatting menu end up identical.
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
                editable.setSpan(oldSpan, end, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

    /** Whether a paste of the current clipboard would become a link, for paste handlers that run first. */
    public static boolean wouldApply(EditText editText) {
        try {
            ClipboardManager clipboard = (ClipboardManager) editText.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            return clipboard != null && linkFor(editText, clipboard.getPrimaryClip()) != null;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static String linkFor(EditText editText, ClipData clipData) {
        if (clipData == null || clipData.getItemCount() != 1) {
            return null;
        }
        Editable editable = editText.getText();
        int start = Math.max(0, Math.min(editText.getSelectionStart(), editText.getSelectionEnd()));
        int end = Math.min(editable.length(), Math.max(editText.getSelectionStart(), editText.getSelectionEnd()));
        if (start >= end || TextUtils.getTrimmedLength(editable.subSequence(start, end)) == 0) {
            return null;
        }
        try {
            // coerceToText reads the plain text of an HTML clip too, so a URL copied off a page counts.
            CharSequence text = clipData.getItemAt(0).coerceToText(editText.getContext());
            String url = text == null ? null : text.toString().trim();
            return isLink(url) ? url : null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
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
