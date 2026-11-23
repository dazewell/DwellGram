package com.dazewell.gram.videonote;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/** Send-time formatting shared by memo messages and media captions. */
final class TextMemoSignature {
    static final String SUFFIX = "\n\n" + LocaleController.getString(R.string.TextMemoSignatureText);

    private TextMemoSignature() {
    }

    // Append after Markdown parsing, which can change the body's length and entity offsets.
    static ArrayList<TLRPC.MessageEntity> append(CharSequence[] text, ArrayList<TLRPC.MessageEntity> entities) {
        if (text[0] == null || text[0].length() == 0) {
            return entities;
        }
        int offset = text[0].length() + 2;
        text[0] = text[0].toString() + SUFFIX;
        if (entities == null) {
            entities = new ArrayList<>();
        }
        TLRPC.TL_messageEntityBlockquote quote = new TLRPC.TL_messageEntityBlockquote();
        quote.offset = offset;
        quote.length = SUFFIX.length() - 2;
        entities.add(quote);
        TLRPC.TL_messageEntityItalic italic = new TLRPC.TL_messageEntityItalic();
        italic.offset = offset;
        italic.length = quote.length;
        entities.add(italic);
        return entities;
    }
}
