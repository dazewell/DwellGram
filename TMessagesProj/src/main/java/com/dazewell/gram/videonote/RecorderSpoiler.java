package com.dazewell.gram.videonote;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

import com.dazewell.gram.helpers.VideoDraftStore;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatActivityEnterView;

import java.io.File;

import xyz.nextalone.nagram.NaConfig;

import static org.telegram.messenger.AndroidUtilities.dp;

public final class RecorderSpoiler {
    private RecorderSpoiler() {
    }

    public static boolean brandAtStart(Object delegate) {
        return NaConfig.INSTANCE.getSpoilerVideoMessages().Bool()
                && delegate instanceof ChatActivity && canSend((ChatActivity) delegate);
    }

    public static boolean canSend(ChatActivity chat) {
        if (chat.isSecretChat()) {
            return false;
        }
        long dialogId = chat.getDialogId();
        if (!DialogObject.isChatDialog(dialogId)) {
            return true;
        }
        TLRPC.Chat peer = MessagesController.getInstance(chat.getCurrentAccount()).getChat(-dialogId);
        if (peer == null) {
            peer = chat.getCurrentChat();
        }
        return peer != null && ChatObject.canWriteToChat(peer) && ChatObject.canSendVideo(peer)
                && chat.canSendRecorderVideoToCurrentTopic(peer);
    }

    public static ChatActivityEnterView composer(Object owner) {
        return owner instanceof ChatActivity ? ((ChatActivity) owner).getChatActivityEnterView() : null;
    }

    public static boolean hasPreview(Object owner) {
        ChatActivityEnterView composer = composer(owner);
        return composer != null && composer.hasVideoToSend();
    }

    public static void selectionChanged(Object owner, VideoEditedInfo info, boolean selected) {
        if (info != null) {
            info.naxDraftSpoiler = selected;
        }
        ChatActivityEnterView composer = composer(owner);
        if (composer != null) {
            composer.onRecorderSpoilerChanged(selected);
        }
    }

    public static DraftTarget draftTarget(Object owner) {
        if (owner instanceof ChatActivity && ((ChatActivity) owner).canRestoreVideoDraft()) {
            return new DraftTarget((ChatActivity) owner);
        }
        return null;
    }

    public static long recordingTopic(Object owner) {
        return owner instanceof ChatActivity ? ((ChatActivity) owner).getVideoDraftOwnerTopicId() : 0;
    }

    public static boolean canOffer(Object owner, boolean selected, boolean branded) {
        return owner instanceof ChatActivity && (selected || branded && brandAtStart(owner));
    }

    public static boolean isOwnerTopic(Object owner, long topicId) {
        return owner instanceof ChatActivity && ((ChatActivity) owner).getTopicId() == topicId;
    }

    public static boolean checkOwnerTopic(Object owner, long topicId) {
        if (!(owner instanceof ChatActivity)) {
            return false;
        }
        ChatActivity chat = (ChatActivity) owner;
        if (isOwnerTopic(chat, topicId)) {
            return true;
        }
        BulletinFactory.of(chat).createErrorBulletin(LocaleController.getString(R.string.RecorderSpoilerOriginalTopic)).show();
        return false;
    }

    public static boolean checkSend(Object delegate) {
        if (!(delegate instanceof ChatActivity)) {
            return false;
        }
        ChatActivity chat = (ChatActivity) delegate;
        if (canSend(chat)) {
            return true;
        }
        BulletinFactory.of(chat).createErrorBulletin(LocaleController.getString(R.string.GlobalAttachVideoRestricted)).show();
        return false;
    }

    // Created and consumed on the main thread. The encoder only carries this snapshot across the stop.
    public static final class DraftTarget {
        private final int account;
        private final long dialogId;
        private final long topicId;
        private final long previousId;

        private DraftTarget(ChatActivity chat) {
            account = chat.getCurrentAccount();
            dialogId = chat.getDialogId();
            topicId = chat.getVideoDraftOwnerTopicId();
            VideoDraftStore.Entry previous = VideoDraftStore.load(account, dialogId, topicId);
            previousId = previous == null ? 0 : previous.id;
        }

        public boolean save(VideoEditedInfo info, File file) {
            VideoDraftStore.Entry current = VideoDraftStore.load(account, dialogId, topicId);
            if ((current == null ? 0 : current.id) != previousId) {
                return false;
            }
            info.naxDraftDialogId = dialogId;
            info.naxDraftTopicId = topicId;
            return VideoDraftStore.save(account, dialogId, topicId, info.naxDraftId, file.getAbsolutePath(),
                    (int) info.estimatedDuration, info.startTime, info.endTime, false, true, info.naxBrandedCapture);
        }
    }

    public static VideoEditedInfo segmentInfo(File file, long duration) {
        VideoEditedInfo info = new VideoEditedInfo();
        info.startTime = -1;
        info.endTime = -1;
        info.roundVideo = true;
        info.estimatedSize = Math.max(1, file.length());
        info.estimatedDuration = duration;
        info.framerate = 25;
        info.resultWidth = info.originalWidth = 360;
        info.resultHeight = info.originalHeight = 360;
        info.originalPath = file.getAbsolutePath();
        return info;
    }

    public static void apply(VideoEditedInfo info, MediaController.PhotoEntry entry, boolean spoiler) {
        if (spoiler) {
            if (info.startTime <= 0 && info.endTime == info.estimatedDuration) {
                // The right trim handle can put the clip back at full length. The local edit string
                // doesn't carry estimatedDuration, so use its full-range sentinel before a retry.
                info.endTime = -1;
            }
            info.roundVideo = false;
            info.naxRecorderVideo = true;
            entry.hasSpoiler = true;
            entry.ttl = 0;
        }
    }

    public static String description(boolean selected) {
        return LocaleController.getString(selected ? R.string.DisablePhotoSpoiler : R.string.EnablePhotoSpoiler);
    }

    public static final class Button extends ImageView {
        private ObjectAnimator iconAnimation;

        public Button(Context context, Runnable onClick) {
            super(context);
            setScaleType(ScaleType.CENTER);
            setForeground(Theme.createCircleSelectorDrawable(0x24909090, dp(3), dp(3)));
            setFocusable(true);
            setTooltipText(LocaleController.getString(R.string.RecorderSpoilerHint));
            setVisibility(GONE);
            setOnClickListener(view -> onClick.run());
        }

        public void update(boolean shown, boolean selected) {
            setVisibility(shown ? VISIBLE : GONE);
            if (getDrawable() == null || isSelected() != selected) {
                setSelected(selected);
                setImageResource(selected ? R.drawable.msg_spoiler : R.drawable.msg_spoiler_off);
                setContentDescription(description(selected));
                if (iconAnimation != null) {
                    iconAnimation.cancel();
                }
                if (shown && isShown()) {
                    iconAnimation = ObjectAnimator.ofInt(this, "imageAlpha", 150, 255);
                    iconAnimation.setDuration(180).start();
                } else {
                    setImageAlpha(255);
                }
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setCheckable(true);
            info.setChecked(isSelected());
        }
    }
}
