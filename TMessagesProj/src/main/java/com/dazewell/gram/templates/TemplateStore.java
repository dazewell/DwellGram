package com.dazewell.gram.templates;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import xyz.nextalone.nagram.NaConfig;

/**
 * The user's message templates, kept as one JSON array in NaConfig so they travel with settings
 * export and cloud sync. Global rather than per account: they are the user's own phrases and hold no
 * account-scoped ids. Main thread only.
 *
 * <p>Each entry is an object with a stable id so a title, flags or placeholder data can be added later
 * without touching what is already stored. Text is kept verbatim, so a {@code {token}} written today
 * survives until placeholders exist to resolve it.
 */
public final class TemplateStore {

    private static final String TAG = "TemplateStore";

    public static final int MAX_TEMPLATES = 20;
    public static final int MAX_LENGTH = 4096;

    private static final int LABEL_MAX = 48;

    public static final class Template {
        public final String id;
        public final String text;

        Template(String id, String text) {
            this.id = id;
            this.text = text;
        }

        public Template withText(String newText) {
            return new Template(id, newText);
        }
    }

    private TemplateStore() {
    }

    /** Never throws: an absent or unparseable value reads as no templates, a bad entry is dropped. */
    public static ArrayList<Template> load() {
        ArrayList<Template> result = new ArrayList<>();
        String raw = NaConfig.INSTANCE.getTemplateMessagesData().String();
        if (TextUtils.isEmpty(raw)) {
            return result;
        }
        JSONArray array;
        try {
            array = new JSONArray(raw);
        } catch (Exception e) {
            Log.w(TAG, "stored templates unparseable, reading as empty");
            return result;
        }
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < array.length() && result.size() < MAX_TEMPLATES; i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) {
                continue;
            }
            // optString turns a JSON null into the text "null", so test for it first.
            String text = object.isNull("text") ? "" : object.optString("text", "");
            if (TextUtils.isEmpty(text) || TextUtils.isEmpty(text.trim())) {
                continue;
            }
            if (text.length() > MAX_LENGTH) {
                text = text.substring(0, Character.isHighSurrogate(text.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH);
            }
            String id = object.isNull("id") ? "" : object.optString("id", "");
            if (TextUtils.isEmpty(id) || !seen.add(id)) {
                id = newId();
                seen.add(id);
            }
            result.add(new Template(id, text));
        }
        return result;
    }

    public static void save(List<Template> templates) {
        JSONArray array = new JSONArray();
        try {
            for (Template template : templates) {
                JSONObject object = new JSONObject();
                object.put("id", template.id);
                object.put("text", template.text);
                array.put(object);
            }
        } catch (Exception e) {
            Log.e(TAG, "failed to serialise templates", e);
            return;
        }
        NaConfig.INSTANCE.getTemplateMessagesData().setConfigString(array.toString());
    }

    public static Template create(String text) {
        return new Template(newId(), text);
    }

    /** The first non-blank line, shortened for a one-line row. */
    public static String label(String text) {
        String line = "";
        if (text != null) {
            for (String candidate : text.split("\n")) {
                if (!TextUtils.isEmpty(candidate.trim())) {
                    line = candidate.trim();
                    break;
                }
            }
        }
        if (line.length() > LABEL_MAX) {
            int end = Character.isHighSurrogate(line.charAt(LABEL_MAX - 1)) ? LABEL_MAX - 1 : LABEL_MAX;
            line = line.substring(0, end).trim() + "…";
        }
        return line;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
