package com.dazewell.gram.videonote;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;

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
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

import java.io.File;

import xyz.nextalone.nagram.NaConfig;

import static org.telegram.messenger.AndroidUtilities.dp;

public final class RecorderSpoiler {
    private RecorderSpoiler() {
    }

    public static void smoke(String detail) {
        android.util.Log.i("NaxRecorderSpoiler", "NAX_SMOKE_spoiler-video-messages " + detail);
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
                && chat.canSendRecorderVideoToCurrentTopic();
    }

    public static boolean checkSend(Object delegate) {
        if (!(delegate instanceof ChatActivity)) {
            return false;
        }
        ChatActivity chat = (ChatActivity) delegate;
        if (canSend(chat)) {
            return true;
        }
        smoke("REFUSED permission");
        BulletinFactory.of(chat).createErrorBulletin(LocaleController.getString(R.string.GlobalAttachVideoRestricted)).show();
        return false;
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
            smoke("SEND ordinary spoiler trimmed=" + info.needConvert());
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
            setLayoutParams(new FrameLayout.LayoutParams(dp(48), dp(48)));
            setScaleType(ScaleType.CENTER);
            setForeground(Theme.createCircleSelectorDrawable(0x24909090, dp(3), dp(3)));
            setFocusable(true);
            setVisibility(GONE);
            setOnClickListener(view -> {
                if (RecordControlsColumn.shown(this)) {
                    onClick.run();
                }
            });
        }

        public void setGlass(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
            BlurredBackgroundDrawable glass = factory.create(this, colorProvider);
            glass.setPadding(dp(6));
            glass.setRadius(dp(21));
            setBackground(glass);
        }

        public void update(boolean shown, boolean selected, int normalColor, int selectedColor) {
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
            setColorFilter(selected ? selectedColor : normalColor);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !RecordControlsColumn.shown(this)) {
                return false;
            }
            return super.onTouchEvent(event);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setCheckable(true);
            info.setChecked(isSelected());
        }
    }
}
