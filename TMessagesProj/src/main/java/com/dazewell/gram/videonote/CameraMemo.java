package com.dazewell.gram.videonote;

import android.view.ViewGroup;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;

import java.lang.ref.WeakReference;

/**
 * #camera-memo: the video memo's recorder under a plain camera screen (CameraMemoCover) instead of the chat. The cover
 * only changes how the screen looks. The live circle stays the stock one, at full size, and what it shows about the
 * recording (live, paused, stopped) is read from the recorder on every tick, never tracked here.
 *
 * One camera memo at a time, UI thread only. While the app is locked VideoNoteShortcut's phase machine owns locking and
 * leaving, exactly as for the video memo, and this only drives the recorder and the cover, which comes down when the
 * passcode goes up. Unlocked, a send, discard or back takes the cover down and leaves the chat where it is.
 */
public final class CameraMemo {

    private static WeakReference<ChatActivity> sessionChat;
    private static WeakReference<CameraMemoCover> sessionCover;

    private CameraMemo() {
    }

    static ChatActivity getChat() {
        ChatActivity chat = sessionChat != null ? sessionChat.get() : null;
        return chat != null && !chat.isFinished ? chat : null;
    }

    static boolean isLive() {
        return getChat() != null;
    }

    static boolean isSessionChat(ChatActivity chat) {
        return chat != null && getChat() == chat;
    }

    /** VideoNoteShortcut.onChatViewCreated, for the chat a camera memo shortcut is opening. Up from the push's first frame. */
    static void attach(ChatActivity chat, ViewGroup contentView) {
        end();
        CameraMemoCover cover = new CameraMemoCover(contentView.getContext(), chat);
        contentView.addView(cover, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sessionChat = new WeakReference<>(chat);
        sessionCover = new WeakReference<>(cover);
        android.util.Log.i("NaxCameraMemo", "NAX_SMOKE_camera-memo BEGIN build=" + org.telegram.messenger.BuildConfig.BUILD_VERSION_STRING
                + " app=" + org.telegram.messenger.BuildConfig.APPLICATION_ID + " account=" + chat.getCurrentAccount()
                + " locked=" + VideoNoteShortcut.isLockedMemo());
    }

    /** VideoNoteShortcut.onChatOpened, with whether the camera actually started. */
    static void onStart(ChatActivity chat, boolean started) {
        CameraMemoCover cover = isSessionChat(chat) && sessionCover != null ? sessionCover.get() : null;
        android.util.Log.i("NaxCameraMemo", "NAX_SMOKE_camera-memo start started=" + started + " cover=" + (cover != null));
        if (cover != null && !started) {
            cover.showFailed();
        }
    }

    /** Ends the session now; the cover comes down on the next frame, never from inside the caller's own dispatch. */
    static void end() {
        CameraMemoCover cover = sessionCover != null ? sessionCover.get() : null;
        sessionChat = null;
        sessionCover = null;
        if (cover != null) {
            android.util.Log.i("NaxCameraMemo", "NAX_SMOKE_camera-memo END locked=" + VideoNoteShortcut.isLockedMemo(), new Throwable());
            AndroidUtilities.runOnUIThread(cover::remove);
        }
    }

    /** The cover lost its window some other way (the chat was destroyed): nothing is left to take down. */
    static void onCoverDetached(CameraMemoCover cover) {
        if (sessionCover != null && sessionCover.get() == cover) {
            sessionChat = null;
            sessionCover = null;
        }
    }

    // --- Unlocked only: VideoNoteShortcut calls these while its phase is IDLE ---

    /** Back keeps the clip: a live recording is stopped into the chat's preview, and the cover comes down. */
    static boolean onBackPressed(ChatActivity chat, boolean invoked) {
        if (!isSessionChat(chat)) {
            return false;
        }
        if (invoked) {
            keepAndEnd(chat);
        }
        return true;
    }

    /** Anything pushed over the memo's chat ends it the same way back does. */
    static void onNavigation(BaseFragment fragment) {
        ChatActivity chat = getChat();
        if (chat != null && fragment != chat) {
            keepAndEnd(chat);
        }
    }

    // The same split onPasscodeShown makes: a live clip is finalized into the preview, a camera still opening has recorded
    // nothing and would otherwise start recording in the open chat, so it is cancelled.
    private static void keepAndEnd(ChatActivity chat) {
        if (!chat.finalizeRoundVideoForLock()) {
            ChatActivityEnterView enterView = chat.getChatActivityEnterView();
            if (enterView != null && enterView.isRecordingAudioVideo() && !enterView.hasVideoToSend()) {
                enterView.cancelRecordingAudioVideo();
            }
        }
        end();
    }

    // --- The cover's controls ---

    static void send(ChatActivity chat) {
        if (!isSessionChat(chat)) {
            return;
        }
        if (chat.instantCameraView != null && chat.instantCameraView.isRecording()) {
            chat.sendLockedRecording();
        } else {
            chat.sendVideoPreview();
        }
        // Locked, the phase machine leaves once the clip is handed off and the passcode takes the cover down
        if (!VideoNoteShortcut.isLockedMemo()) {
            end();
        }
    }

    static void discard(ChatActivity chat) {
        if (!isSessionChat(chat)) {
            return;
        }
        ChatActivityEnterView enterView = chat.getChatActivityEnterView();
        if (enterView == null) {
            return;
        }
        if (chat.instantCameraView != null && chat.instantCameraView.isRecording()) {
            enterView.cancelRecordingAudioVideo();
        } else {
            enterView.discardVideoPreview();
        }
        if (!VideoNoteShortcut.isLockedMemo()) {
            end();
        }
    }

    /** Close on the failure message. Locked, the phase machine's start timeout locks and leaves on its own. */
    static void close(ChatActivity chat) {
        if (isSessionChat(chat) && !VideoNoteShortcut.isLockedMemo()) {
            keepAndEnd(chat);
        }
    }
}
