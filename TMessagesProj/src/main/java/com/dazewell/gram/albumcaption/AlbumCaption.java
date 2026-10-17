package com.dazewell.gram.albumcaption;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import com.dazewell.gram.helpers.AttachCaptionHelper;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.ChatAttachAlert;
import org.telegram.ui.Components.ChatAttachAlertPhotoLayout;
import org.telegram.ui.Components.EditTextEmoji;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;

import xyz.nextalone.nagram.NaConfig;

/**
 * Per-photo captions for the attach sheet (#album-caption).
 *
 * <p>Stock keeps a "same caption for all" mode alive between the sheet's comment field and the full-screen
 * viewer: opening the viewer pre-fills every photo with the field's text, and typing a different caption on
 * any later photo silently turns the mode off. With the setting on, that mode is never entered. Each photo
 * owns its caption, and the sheet's field is a live mirror of the <em>first selected</em> photo's caption --
 * the only caption an album can show under the whole bubble -- so text typed in either place is always held by
 * exactly one entry and a selection change can never leave it with no owner.
 *
 * <p>Nothing here changes what is sent. Every entry is sent with the caption it holds, as stock does.
 *
 * <p>All state is touched on the main thread only.
 */
public final class AlbumCaption {

    /**
     * The selection key the sheet's field currently mirrors, per sheet. Weak so a dismissed sheet is not pinned;
     * the key is a plain image id, so nothing else is held. Absent means the field mirrors nobody yet.
     */
    private static final WeakHashMap<ChatAttachAlert, Object> FIELD_KEYS = new WeakHashMap<>();

    private AlbumCaption() {
    }

    public static boolean enabled() {
        return NaConfig.INSTANCE.getAlbumCaptionPerPhoto().Bool();
    }

    /** The photo tabs are the only surfaces where the field is a photo caption; documents and music use it too. */
    private static boolean onPhotoSurface(ChatAttachAlert alert) {
        final Object layout = alert.getCurrentAttachLayout();
        return layout != null && (layout == alert.getPhotoLayout() || layout == alert.getPhotoPreviewLayout());
    }

    private static Object firstSelectedKey(ChatAttachAlertPhotoLayout layout) {
        final ArrayList<Object> order = layout.getSelectedPhotosOrder();
        return order == null || order.isEmpty() ? null : order.get(0);
    }

    private static CharSequence captionOf(Object entry) {
        if (entry instanceof MediaController.PhotoEntry) {
            return ((MediaController.PhotoEntry) entry).caption;
        } else if (entry instanceof MediaController.SearchImage) {
            return ((MediaController.SearchImage) entry).caption;
        }
        return null;
    }

    private static ArrayList<TLRPC.MessageEntity> entitiesOf(Object entry) {
        if (entry instanceof MediaController.PhotoEntry) {
            return ((MediaController.PhotoEntry) entry).entities;
        } else if (entry instanceof MediaController.SearchImage) {
            return ((MediaController.SearchImage) entry).entities;
        }
        return null;
    }

    /** Stores a detached copy, so the entry never holds the sheet field's live Editable. */
    private static void setCaption(Object entry, CharSequence text) {
        if (!(entry instanceof MediaController.PhotoEntry) && !(entry instanceof MediaController.SearchImage)) {
            return;
        }
        if (!AttachCaptionHelper.hasUnappliedCaption(UserConfig.selectedAccount, text, false, captionOf(entry), entitiesOf(entry))) {
            return;
        }
        CharSequence caption = null;
        ArrayList<TLRPC.MessageEntity> entities = null;
        if (!TextUtils.isEmpty(text)) {
            final CharSequence[] box = new CharSequence[]{new SpannableStringBuilder(text)};
            entities = MediaDataController.getInstance(UserConfig.selectedAccount).getEntities(box, false);
            caption = box[0];
        }
        if (entry instanceof MediaController.PhotoEntry) {
            ((MediaController.PhotoEntry) entry).caption = caption;
            ((MediaController.PhotoEntry) entry).entities = entities;
        } else {
            ((MediaController.SearchImage) entry).caption = caption;
            ((MediaController.SearchImage) entry).entities = entities;
        }
    }

    /** The caption as the field should show it: text with its entities turned back into spans. */
    private static CharSequence displayCaption(Object entry) {
        CharSequence caption = captionOf(entry);
        if (TextUtils.isEmpty(caption)) {
            return null;
        }
        final ArrayList<TLRPC.MessageEntity> entities = entitiesOf(entry);
        if (entities != null) {
            final SpannableStringBuilder copy = new SpannableStringBuilder(caption);
            MessageObject.addEntitiesToText(copy, entities, false, false, false, false);
            caption = copy;
        }
        return caption;
    }

    /**
     * The sheet field changed. Keeps the first selected photo's caption equal to it, including when it is
     * cleared (stock skips an empty field when it applies, so a cleared field used to leave the old caption on
     * the photo and send it anyway).
     */
    public static void onFieldChanged(ChatAttachAlert alert, CharSequence text) {
        if (alert == null || !enabled() || !onPhotoSurface(alert)) {
            return;
        }
        final ChatAttachAlertPhotoLayout layout = alert.getPhotoLayout();
        if (layout == null) {
            return;
        }
        final Object key = firstSelectedKey(layout);
        if (key == null) {
            return;
        }
        setCaption(layout.getSelectedPhotos().get(key), text);
    }

    /**
     * Called wherever the selection can change. When a different photo has become first, the field follows it.
     * The previous first photo keeps its own caption, so the text leaving the field is never the only copy.
     * Idempotent: it does nothing while the first photo is the one the field already mirrors.
     */
    public static void reconcile(ChatAttachAlert alert) {
        if (alert == null || !enabled() || !onPhotoSurface(alert)) {
            return;
        }
        final ChatAttachAlertPhotoLayout layout = alert.getPhotoLayout();
        if (layout == null) {
            return;
        }
        final Object key = firstSelectedKey(layout);
        final Object previous = FIELD_KEYS.get(alert);
        if (Objects.equals(previous, key)) {
            return;
        }
        if (key == null) {
            FIELD_KEYS.remove(alert);
            return;
        }
        FIELD_KEYS.put(alert, key);
        final Object entry = layout.getSelectedPhotos().get(key);
        final EditTextEmoji field = alert.getCommentView();
        if (entry == null || field == null) {
            return;
        }
        if (previous == null && TextUtils.isEmpty(captionOf(entry)) && field.length() > 0) {
            // Nothing owned the field: text typed before any photo was selected goes onto the first one.
            setCaption(entry, field.getText());
        } else {
            mirrorFirstIntoField(field, entry);
        }
    }

    /** Refills the field from the first selected photo, after the full-screen preview edited a caption. */
    public static void syncFieldFromFirst(ChatAttachAlert alert) {
        if (alert == null || !enabled() || !onPhotoSurface(alert)) {
            return;
        }
        final ChatAttachAlertPhotoLayout layout = alert.getPhotoLayout();
        final EditTextEmoji field = alert.getCommentView();
        if (layout == null || field == null) {
            return;
        }
        final Object key = firstSelectedKey(layout);
        if (key != null) {
            FIELD_KEYS.put(alert, key);
            mirrorFirstIntoField(field, layout.getSelectedPhotos().get(key));
        }
    }

    private static void mirrorFirstIntoField(EditTextEmoji field, Object entry) {
        final CharSequence shown = displayCaption(entry);
        if (TextUtils.equals(field.getText(), shown) || TextUtils.isEmpty(field.getText()) && TextUtils.isEmpty(shown)) {
            return;
        }
        field.setText(shown == null ? "" : AnimatedEmojiSpan.cloneSpans(shown, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW));
    }

    /**
     * A send that was started and then cancelled leaves a clone of the first photo in the selection (stock's
     * applyCaption swaps one in), while the grid's full-screen viewer edits the originals. Put the original
     * back, carrying the clone's caption, so there is one owner and an edit made in the viewer is not lost.
     */
    public static void restoreOriginal(HashMap<Object, Object> selected, ArrayList<Object> order, List<?> originals) {
        if (!enabled() || order == null || order.isEmpty() || originals == null) {
            return;
        }
        final Object key = order.get(0);
        final Object current = selected.get(key);
        if (!(current instanceof MediaController.PhotoEntry)) {
            return;
        }
        for (Object o : originals) {
            if (o instanceof MediaController.PhotoEntry && o != current && key.equals(((MediaController.PhotoEntry) o).imageId)) {
                final MediaController.PhotoEntry original = (MediaController.PhotoEntry) o;
                original.caption = ((MediaController.PhotoEntry) current).caption;
                original.entities = ((MediaController.PhotoEntry) current).entities;
                selected.put(key, original);
                return;
            }
        }
    }

    /**
     * Unchecking a photo in the grid resets its edits, and the reset takes its caption along. The caption is
     * text the user typed, so it is held across the reset and put back (a viewer uncheck already keeps it).
     */
    public static Object[] keepCaption(MediaController.PhotoEntry entry) {
        return enabled() && entry != null && !TextUtils.isEmpty(entry.caption) ? new Object[]{entry.caption, entry.entities} : null;
    }

    @SuppressWarnings("unchecked")
    public static void restoreCaption(MediaController.PhotoEntry entry, Object[] kept) {
        if (kept != null && entry != null) {
            entry.caption = (CharSequence) kept[0];
            entry.entities = (ArrayList<TLRPC.MessageEntity>) kept[1];
        }
    }

    /**
     * What to put back in the message box after an app lock rebuilds the chat (#attach-caption-guard). The
     * attachments cannot be restored, so every caption of a selected photo comes back as text, in send order,
     * separated by a blank line. With a single caption this is that caption alone, as before.
     *
     * <p>The viewer is asked to apply first: the chat fragment pauses before the viewer does, so text still
     * sitting in the viewer's editor is not on its photo yet.
     */
    public static CharSequence rescueText(ChatAttachAlert alert, CharSequence stock) {
        if (alert == null || !enabled() || !alert.isShowing() || !onPhotoSurface(alert)) {
            return stock;
        }
        final ChatAttachAlertPhotoLayout layout = alert.getPhotoLayout();
        if (layout == null) {
            return stock;
        }
        PhotoViewer.getInstance().applyCaptionForRescue();
        final ArrayList<CharSequence> parts = new ArrayList<>();
        final ArrayList<Object> order = layout.getSelectedPhotosOrder();
        final HashMap<Object, Object> selected = layout.getSelectedPhotos();
        for (int i = 0; i < order.size(); i++) {
            final CharSequence caption = displayCaption(selected.get(order.get(i)));
            if (TextUtils.isEmpty(caption)) {
                continue;
            }
            boolean duplicate = false;
            for (int j = 0; j < parts.size(); j++) {
                duplicate |= TextUtils.equals(parts.get(j), caption);
            }
            if (!duplicate) {
                parts.add(caption);
            }
        }
        if (parts.isEmpty()) {
            return stock;
        }
        final SpannableStringBuilder joined = new SpannableStringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                joined.append("\n\n");
            }
            joined.append(parts.get(i));
        }
        return joined;
    }
}
