package com.dazewell.gram.videonote;

import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.Base64;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.QuoteSpan;
import org.telegram.ui.Components.URLSpanUserMention;
import org.telegram.utils.code.highlight.CodeHighlighting;

import java.util.ArrayList;

import xyz.nextalone.nagram.NaConfig;

/** One text/entity snapshot shared by the editor, memo messages and media captions. */
public final class TextMemoSignature {
    public static final int MAX_LENGTH = 1024;
    private static final int MAX_ENTITIES = 4096;
    private static final int MAX_ENCODED_LENGTH = 262144;

    public final String text;
    public final ArrayList<TLRPC.MessageEntity> entities;

    private TextMemoSignature(String text, ArrayList<TLRPC.MessageEntity> entities) {
        this.text = text;
        this.entities = entities;
        validate();
    }

    private static TextMemoSignature empty() {
        return new TextMemoSignature("", new ArrayList<>());
    }

    public static TextMemoSignature preset() {
        String text = LocaleController.getString(R.string.TextMemoSignatureText);
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        TLRPC.TL_messageEntityBlockquote quote = new TLRPC.TL_messageEntityBlockquote();
        quote.length = text.length();
        entities.add(quote);
        TLRPC.TL_messageEntityItalic italic = new TLRPC.TL_messageEntityItalic();
        italic.length = text.length();
        entities.add(italic);
        return new TextMemoSignature(text, entities);
    }

    public static TextMemoSignature load() {
        String encoded = NaConfig.INSTANCE.getTextMemoSignatureContent().String();
        if (encoded.isEmpty() || encoded.length() > MAX_ENCODED_LENGTH) {
            return preset();
        }
        SerializedData data = null;
        try {
            data = new SerializedData(Base64.decode(encoded, Base64.DEFAULT));
            String text = data.readString(true);
            int count = data.readInt32(true);
            if (count < 0 || count > MAX_ENTITIES) {
                return preset();
            }
            ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                TLRPC.MessageEntity entity = TLRPC.MessageEntity.TLdeserialize(data, data.readInt32(true), true);
                if (entity == null || entity instanceof TLRPC.TL_inputMessageEntityMentionName) {
                    return preset();
                }
                entities.add(entity);
            }
            if (data.remaining() != 0) {
                return preset();
            }
            return new TextMemoSignature(text, entities);
        } catch (Exception e) {
            return preset();
        } finally {
            if (data != null) {
                data.cleanup();
            }
        }
    }

    public static TextMemoSignature enabled() {
        return NaConfig.INSTANCE.getTextMemoSignature().Bool() ? load() : empty();
    }

    public static TextMemoSignature fromEditor(int account, CharSequence source) {
        SpannableStringBuilder copy = new SpannableStringBuilder(source);
        ArrayList<TLRPC.MessageEntity> mentions = new ArrayList<>();
        // Keep user ids, not the account-specific InputUser/access hash produced by getEntities.
        for (URLSpanUserMention span : copy.getSpans(0, copy.length(), URLSpanUserMention.class)) {
            TLRPC.TL_messageEntityMentionName entity = new TLRPC.TL_messageEntityMentionName();
            entity.user_id = Utilities.parseLong(span.getURL());
            entity.offset = copy.getSpanStart(span);
            entity.length = copy.getSpanEnd(span) - entity.offset;
            if (entity.user_id > 0 && entity.length > 0) {
                mentions.add(entity);
            }
            copy.removeSpan(span);
        }
        CharSequence[] text = {copy};
        ArrayList<TLRPC.MessageEntity> entities = MediaDataController.getInstance(account).getEntities(text, true, false);
        if (entities == null) {
            entities = new ArrayList<>();
        }
        entities.addAll(mentions);
        if (text[0].toString().trim().isEmpty()) {
            return empty();
        }
        return new TextMemoSignature(text[0].toString(), entities);
    }

    public void save() {
        validate();
        SerializedData data = new SerializedData();
        try {
            data.writeString(text);
            data.writeInt32(entities.size());
            for (TLRPC.MessageEntity entity : entities) {
                entity.serializeToStream(data);
            }
            String encoded = Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP);
            if (encoded.length() > MAX_ENCODED_LENGTH) {
                throw new IllegalArgumentException("signature data too large");
            }
            NaConfig.INSTANCE.getTextMemoSignatureContent().setConfigString(encoded);
        } finally {
            data.cleanup();
        }
    }

    public CharSequence toEditor(Paint.FontMetricsInt metrics) {
        ArrayList<TLRPC.MessageEntity> inline = new ArrayList<>();
        for (TLRPC.MessageEntity entity : entities) {
            if (!(entity instanceof TLRPC.TL_messageEntityBlockquote)
                    && !(entity instanceof TLRPC.TL_messageEntityPre)
                    && !(entity instanceof TLRPC.TL_messageEntityMentionName)) {
                inline.add(cloneEntity(entity));
            }
        }
        SpannableStringBuilder result = new SpannableStringBuilder(ChatActivityEnterView.applyMessageEntities(inline, text, metrics));
        // Shared restoration inserts newlines for quotes and Markdown for code. Restore their spans
        // directly so reopening an editor never changes the saved text or the following offsets.
        for (TLRPC.MessageEntity entity : entities) {
            int start = entity.offset;
            int end = start + entity.length;
            if (entity instanceof TLRPC.TL_messageEntityBlockquote) {
                QuoteSpan.QuoteStyleSpan style = new QuoteSpan.QuoteStyleSpan();
                QuoteSpan quote = style.span = new QuoteSpan(true, entity.collapsed, style);
                quote.start = start;
                quote.end = end;
                result.setSpan(quote, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(style, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (entity instanceof TLRPC.TL_messageEntityPre) {
                result.setSpan(new CodeHighlighting.Span(true, 0, null, entity.language,
                        text.substring(start, end)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (entity instanceof TLRPC.TL_messageEntityMentionName) {
                result.setSpan(new URLSpanUserMention(Long.toString(((TLRPC.TL_messageEntityMentionName) entity).user_id), 3),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return result;
    }

    public int suffixLength() {
        return text.isEmpty() ? 0 : text.length() + 2;
    }

    // Append after body Markdown parsing, which can change both text length and entity offsets.
    public ArrayList<TLRPC.MessageEntity> append(int account, CharSequence[] body, ArrayList<TLRPC.MessageEntity> entities) {
        if (text.isEmpty() || body[0] == null || body[0].toString().trim().isEmpty()) {
            return entities;
        }
        int offset = body[0].length() + 2;
        body[0] = body[0].toString() + "\n\n" + text;
        if (entities == null) {
            entities = new ArrayList<>();
        }
        for (TLRPC.MessageEntity stored : this.entities) {
            TLRPC.MessageEntity entity;
            if (stored instanceof TLRPC.TL_messageEntityMentionName) {
                TLRPC.InputUser user = MessagesController.getInstance(account).getInputUser(((TLRPC.TL_messageEntityMentionName) stored).user_id);
                if (user == null || user instanceof TLRPC.TL_inputUserEmpty) {
                    continue;
                }
                TLRPC.TL_inputMessageEntityMentionName mention = new TLRPC.TL_inputMessageEntityMentionName();
                mention.user_id = user;
                mention.offset = stored.offset;
                mention.length = stored.length;
                entity = mention;
            } else {
                entity = cloneEntity(stored);
            }
            entity.offset += offset;
            entities.add(entity);
        }
        return entities;
    }

    static TLRPC.MessageEntity cloneEntity(TLRPC.MessageEntity entity) {
        SerializedData output = new SerializedData();
        SerializedData input = null;
        try {
            entity.serializeToStream(output);
            input = new SerializedData(output.toByteArray());
            return TLRPC.MessageEntity.TLdeserialize(input, input.readInt32(true), true);
        } finally {
            output.cleanup();
            if (input != null) {
                input.cleanup();
            }
        }
    }

    private void validate() {
        if (text.length() > MAX_LENGTH || entities.size() > MAX_ENTITIES) {
            throw new IllegalArgumentException("signature too large");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("invalid signature text");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("invalid signature text");
            }
        }
        for (TLRPC.MessageEntity entity : entities) {
            if (entity == null || entity.offset < 0 || entity.length <= 0
                    || entity.offset > text.length() || entity.length > text.length() - entity.offset
                    || splitsSurrogate(entity.offset) || splitsSurrogate(entity.offset + entity.length)
                    || entity instanceof TLRPC.TL_inputMessageEntityMentionName) {
                throw new IllegalArgumentException("invalid signature entity");
            }
        }
    }

    private boolean splitsSurrogate(int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }
}
