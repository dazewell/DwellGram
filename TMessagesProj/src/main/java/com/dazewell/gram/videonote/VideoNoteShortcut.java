package com.dazewell.gram.videonote;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.LaunchActivity;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;

import xyz.nextalone.nagram.NaConfig;

/**
 * Launcher shortcut that opens Saved Messages, or the person picked in VideoNoteTarget, and starts a hands-free round
 * video right away.
 * All state here is touched on the UI thread only.
 */
public final class VideoNoteShortcut {

    public static final String ACTION = "nax_video_note";
    // One id per camera, so a copy pinned to the home screen keeps its camera when the setting changes. Neither reuses
    // the old "video_note": publishing under it would rewrite copies pinned before shortcuts carried their camera, which
    // have no extra and follow the setting instead.
    private static final String SHORTCUT_ID = "video_note_front";
    private static final String SHORTCUT_ID_REAR = "video_note_rear";
    private static final String EXTRA_HASH = "hash";
    private static final String EXTRA_REAR = "rear";
    private static final long PENDING_TTL_MS = 10_000;

    public static final int CAMERA_FRONT = 0;
    public static final int CAMERA_REAR = 1;
    public static final int CAMERA_BOTH = 2;

    // Armed when the intent is accepted, consumed by the chat it opens. Static on purpose: it dies with
    // the process, so a restored activity or fragment can never replay the camera start.
    private static int pendingAccount = -1;
    private static long pendingDialogId;
    private static long pendingSince;
    private static boolean pendingRear;

    private VideoNoteShortcut() {
    }

    public static boolean isEnabled() {
        return NaConfig.INSTANCE.getVideoNoteShortcut().Bool();
    }

    /** VideoNoteShortcutCamera, with anything unknown read as front. */
    public static int getCameraMode() {
        int mode = NaConfig.INSTANCE.getVideoNoteShortcutCamera().Int();
        return mode == CAMERA_REAR || mode == CAMERA_BOTH ? mode : CAMERA_FRONT;
    }

    /** The launcher label: plain while there is one shortcut, naming the camera once there are two. */
    public static String getLabel(boolean rear, boolean longLabel) {
        return com.dazewell.gram.helpers.ShortcutHelper.variantLabel(getBaseLabel(rear, longLabel));
    }

    private static String getBaseLabel(boolean rear, boolean longLabel) {
        if (getCameraMode() != CAMERA_BOTH) {
            return LocaleController.getString(R.string.VideoNoteShortcutLabel);
        }
        if (longLabel) {
            return LocaleController.getString(rear ? R.string.VideoNoteShortcutLabelRearLong : R.string.VideoNoteShortcutLabelFrontLong);
        }
        return LocaleController.getString(rear ? R.string.VideoNoteShortcutLabelRear : R.string.VideoNoteShortcutLabelFront);
    }

    private static boolean publishes(boolean rear) {
        return isEnabled() && getCameraMode() != (rear ? CAMERA_FRONT : CAMERA_REAR);
    }

    /** MediaDataController.buildShortcuts: ranks taken past the one it hands to publish, which recent chats skip. */
    public static int getExtraRanks() {
        int count = (publishes(false) ? 1 : 0) + (publishes(true) ? 1 : 0) + (TextMemoShortcut.isEnabled() ? 2 : 0);
        return Math.max(0, count - 1);
    }

    /** MediaDataController.buildShortcuts: the ids it keeps when pruning stale shortcuts. */
    public static void addShortcutId(List<String> wantedIds) {
        if (publishes(false)) {
            wantedIds.add(SHORTCUT_ID);
        }
        if (publishes(true)) {
            wantedIds.add(SHORTCUT_ID_REAR);
        }
        TextMemoShortcut.addShortcutId(wantedIds);
    }

    /** MediaDataController.buildShortcuts, on its queue after directShareHash is set. */
    public static void publish(boolean recreate, List<String> existingIds, int rank) {
        if (SharedConfig.directShareHash == null) {
            return;
        }
        if (publishes(false)) {
            publish(false, recreate, existingIds, rank++);
        }
        if (publishes(true)) {
            publish(true, recreate, existingIds, rank++);
        }
        // The text memo rides these hooks rather than adding its own to MediaDataController
        TextMemoShortcut.publish(recreate, existingIds, rank);
    }

    // Own try so a launcher rejecting one can't abort the rest
    private static void publish(boolean rear, boolean recreate, List<String> existingIds, int rank) {
        String id = rear ? SHORTCUT_ID_REAR : SHORTCUT_ID;
        try {
            Intent intent = createIntent(rear);
            ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(ApplicationLoader.applicationContext, id)
                    .setShortLabel(getLabel(rear, false))
                    .setLongLabel(getLabel(rear, true))
                    .setIcon(IconCompat.createWithBitmap(createIcon()))
                    .setRank(rank)
                    .setIntent(intent)
                    .build();
            if (recreate) {
                ShortcutManagerCompat.pushDynamicShortcut(ApplicationLoader.applicationContext, shortcut);
            } else if (existingIds.contains(id)) {
                ShortcutManagerCompat.updateShortcuts(ApplicationLoader.applicationContext, Collections.singletonList(shortcut));
            } else {
                ShortcutManagerCompat.addDynamicShortcuts(ApplicationLoader.applicationContext, Collections.singletonList(shortcut));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** The intent a launcher shortcut or MemoPickerActivity starts, carrying the current hash. */
    static Intent createIntent(boolean rear) {
        Intent intent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
        intent.setAction(ACTION);
        intent.putExtra(EXTRA_HASH, SharedConfig.directShareHash);
        intent.putExtra(EXTRA_REAR, rear);
        return intent;
    }

    // Same look as the Ayu Mode shortcut: white glyph on the Telegram-blue disc, rasterized for picky OEM launchers.
    public static Bitmap createIcon() {
        return createIcon(R.drawable.input_video);
    }

    static Bitmap createIcon(int glyphRes) {
        int size = AndroidUtilities.dp(48);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xff3390ec);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        Drawable glyph = ApplicationLoader.applicationContext.getResources().getDrawable(glyphRes, null).mutate();
        glyph.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
        int inset = size / 5;
        glyph.setBounds(inset, inset, size - inset, size - inset);
        glyph.draw(canvas);
        return bitmap;
    }

    private static boolean isGenuine(Intent intent) {
        return intent != null && ACTION.equals(intent.getAction()) && isEnabled()
                && SharedConfig.directShareHash != null && SharedConfig.directShareHash.equals(intent.getStringExtra(EXTRA_HASH));
    }

    /**
     * LaunchActivity.handleIntent's action chain. Consumes the action so a recreate can't replay it, and returns the
     * user id to open (the chosen person or Saved Messages), or 0 to fall through to a plain launch (forged intent, or
     * the setting is off).
     */
    public static long accept(Intent intent, int account) {
        boolean genuine = isGenuine(intent);
        // A copy pinned before shortcuts carried their camera has no extra, and still follows the setting
        boolean rear = intent.hasExtra(EXTRA_REAR) ? intent.getBooleanExtra(EXTRA_REAR, false) : getCameraMode() == CAMERA_REAR;
        intent.setAction(null);
        intent.removeExtra(EXTRA_HASH);
        intent.removeExtra(EXTRA_REAR);
        if (!genuine || !UserConfig.getInstance(account).isClientActivated()) {
            if (phase != IDLE) {
                lockNow();
            }
            return 0;
        }
        long userId = VideoNoteTarget.resolve(account, true);
        pendingAccount = account;
        pendingDialogId = userId;
        pendingSince = SystemClock.elapsedRealtime();
        pendingRear = rear;
        if (phase == STARTING) {
            phaseSince = pendingSince; // the database read above mustn't eat into the start timeout
        }
        return userId;
    }

    private static boolean isPendingChat(ChatActivity chat) {
        return pendingAccount == chat.getCurrentAccount() && pendingDialogId == chat.getDialogId() && chat.getChatMode() == 0;
    }

    // The navigation hooks see the fragment before onFragmentCreate has read its arguments, so its dialog id is still 0
    private static boolean isPendingChatPush(BaseFragment fragment) {
        if (!(fragment instanceof ChatActivity) || fragment.getArguments() == null || pendingAccount != fragment.getCurrentAccount()) {
            return false;
        }
        android.os.Bundle args = fragment.getArguments();
        return args.getLong("user_id", 0) == pendingDialogId && args.getInt("chatMode", 0) == 0;
    }

    /** ChatActivity.onTransitionAnimationEnd, forward open. */
    public static void onChatOpened(ChatActivity chat) {
        if (!isPendingChat(chat)) {
            return;
        }
        boolean fresh = SystemClock.elapsedRealtime() - pendingSince < PENDING_TTL_MS;
        boolean front = !pendingRear;
        pendingAccount = -1;
        pendingDialogId = 0;
        if (!fresh) {
            return;
        }
        boolean locked = phase == STARTING;
        if (locked) {
            sessionChat = new WeakReference<>(chat);
        }
        // A no-animation present fires this synchronously from inside presentFragment, before the chat is laid out or
        // resumed, so start one frame later.
        AndroidUtilities.runOnUIThread(() -> {
            if (locked && !isSessionChat(chat)) {
                return; // the lock already came down in between
            }
            ChatActivityEnterView enterView = chat.getChatActivityEnterView();
            boolean started = enterView != null && !chat.isFinished && chat.getParentActivity() != null
                    && enterView.startRoundVideoFromShortcut(front, !locked);
            if (locked && !started) {
                lockNow();
            }
        });
    }

    // --- Recording while the app is locked ---
    //
    // The passcode is deferred for exactly one recording. It fails closed: before anything opens, appLocked is set and
    // saved, so a crash, a kill or a trip to the background still lands on the passcode, and isWaitingForPasscodeEnter
    // sends every other intent through the passcode gate, which ends the session. Any showPasscodeActivity, whoever
    // calls it, ends the session too (onPasscodeShown). Everything else below only decides when to lock early, and
    // whether to also step back to the launcher: a send, a discard or a back press does (lockAndLeave, lockAndClose).
    // A stall or anything that tried to leave the memo some other way stays on the passcode, which tells the user why it
    // ended.

    private static final int IDLE = 0;
    private static final int STARTING = 1;   // gate passed, the memo's chat is opening
    private static final int RECORDING = 2;  // camera open, or its clip sitting in the preview
    private static final int SENDING = 4;    // send requested, waiting for the clip to reach SendMessagesHelper

    private static final long POLL_MS = 250;
    private static final long START_TIMEOUT_MS = 5_000;
    private static final long IDLE_GRACE_MS = 2_000;
    private static final long SEND_TIMEOUT_MS = 10_000;

    private static int phase = IDLE;
    private static long phaseSince;
    private static long idleSince;
    private static boolean stopRequested; // a stop into the preview (state 3) is under way or done: nothing left to cancel
    private static WeakReference<ChatActivity> sessionChat;
    private static final Runnable poll = VideoNoteShortcut::poll;
    private static final Runnable leave = VideoNoteShortcut::lockAndLeave;
    private static final long LEAVE_DELAY_MS = 220; // the fade below, plus a margin
    private static final long MIN_CLIP_MS = 800; // InstantCameraView.send drops anything shorter, so no preview ever binds
    // The memo chat a back press left behind, closed once its clip is safe (closeWhenSafe)
    private static WeakReference<ChatActivity> closingChat;
    private static boolean closingWaitsForClip;
    private static long closingSince;
    private static final Runnable close = VideoNoteShortcut::closeTick;

    private static void setPhase(int newPhase) {
        phase = newPhase;
        phaseSince = SystemClock.elapsedRealtime();
        idleSince = 0;
        if (newPhase == STARTING) {
            stopRequested = false;
        }
    }

    private static boolean isSessionChat(ChatActivity chat) {
        return phase != IDLE && sessionChat != null && sessionChat.get() == chat;
    }

    /** LaunchActivity.handleIntent's passcode gate. True lets this one intent through while the app stays locked. */
    public static boolean bypassLock(LaunchActivity activity, Intent intent, boolean restore) {
        // A task this shortcut started keeps its intent, action and hash intact, and a recreated activity or a relaunch
        // from recents hands it back here. The action chain skips both, so nothing would open to end the session, and
        // the real tap that follows would find it taken and get the passcode first.
        if (phase != IDLE || !isGenuine(intent) || restore || (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return false;
        }
        SharedConfig.appLocked = true;
        SharedConfig.isWaitingForPasscodeEnter = true;
        SharedConfig.saveConfig();
        setPhase(STARTING);
        AndroidUtilities.cancelRunOnUIThread(close); // a new memo may reuse the chat the last one is still closing
        closingChat = null;
        AndroidUtilities.cancelRunOnUIThread(poll);
        AndroidUtilities.runOnUIThread(poll, POLL_MS);
        activity.hidePasscodeForVideoNote();
        return true;
    }

    /** First thing in LaunchActivity.showPasscodeActivity. */
    public static void onPasscodeShown() {
        // #video-pause-grace: onPause defers finalizing a live round video, so a lock must stop it or the unlock rebuild drops it. A shortcut session finalizes below.
        BaseFragment top = LaunchActivity.getLastFragment();
        if (phase == IDLE && top instanceof ChatActivity) {
            ((ChatActivity) top).finalizeRoundVideoForLock();
        }
        if (phase == IDLE) {
            return;
        }
        ChatActivity chat = sessionChat != null ? sessionChat.get() : null;
        boolean cameraMayBeOpening = phase == STARTING || phase == RECORDING && !stopRequested;
        setPhase(IDLE);
        sessionChat = null;
        pendingAccount = -1;
        pendingDialogId = 0;
        AndroidUtilities.cancelRunOnUIThread(poll);
        AndroidUtilities.cancelRunOnUIThread(leave);
        if (chat != null && !chat.finalizeRoundVideoForLock() && cameraMayBeOpening) {
            // A lock raised in the foreground gets no onPause, so a live recording is stopped into the preview above or
            // the unlock rebuild throws it away. A camera still opening has recorded nothing yet, and left alone it
            // would start recording behind the passcode, so cancel it.
            ChatActivityEnterView enterView = chat.getChatActivityEnterView();
            if (enterView != null && enterView.isRecordingAudioVideo()) {
                enterView.cancelRecordingAudioVideo();
            }
        }
    }

    private static void lockNow() {
        LaunchActivity activity = LaunchActivity.instance;
        if (activity != null && !activity.isFinishing()) {
            activity.showPasscodeActivity(true, false, -1, -1, null, null);
        }
        onPasscodeShown(); // no-op if showPasscodeActivity got that far; appLocked is saved either way
    }

    // The passcode is up (and the lock saved) before the task goes back, so recents shows the passcode, not the chat.
    // moveTaskToBack only stops the activity; the send or discard is already handed over by the time this is called.
    private static void lockAndLeave() {
        ChatActivity chat = sessionChat != null ? sessionChat.get() : null;
        lockAndMinimize();
        // Left on the stack, the memo's chat would be the first thing the unlock shows, and showing it reads it. Posted,
        // because this can run from inside the chat's own send. Only on a send or discard: a clip kept for later
        // waits in this chat's preview.
        if (chat != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (phase == IDLE && canClose(chat)) {
                    chat.finishFragment(false);
                }
            });
        }
    }

    // A memo chat that is the only fragment (a cold start) stays open: closing it would close the app's last screen
    private static boolean canClose(ChatActivity chat) {
        return !chat.isFinished && chat.isLastFragment() && chat.getParentLayout() != null
                && chat.getParentLayout().getFragmentStack().size() > 1;
    }

    private static void lockAndMinimize() {
        LaunchActivity activity = LaunchActivity.instance;
        lockNow();
        if (activity != null && !activity.isFinishing()) {
            activity.moveTaskToBack(true);
        }
    }

    // Back keeps the clip: the lock stops a live recording into the preview (onPasscodeShown), and that preview is also
    // where the draft store saves it, in the same call. So the chat closes as soon as a preview is bound, or at once if no
    // clip is coming. Closed any earlier, the clip would land in a destroyed composer and be lost. The chat is left open,
    // as the fallback, if the wait outlasts SEND_TIMEOUT_MS or the user unlocks first. An unlock that beats the finalize
    // also rebuilds the chat and leaves the clip as an orphan on disk; that race is accepted, since it needs a relaunch.
    private static void lockAndClose(ChatActivity chat) {
        ChatActivityEnterView enterView = chat.getChatActivityEnterView();
        boolean live = chat.instantCameraView != null && chat.instantCameraView.isRecording();
        // Certain to be dropped: a camera still opening (onPasscodeShown cancels it), or a live clip younger than
        // MIN_CLIP_MS, which phaseSince bounds since it marks the camera starting to open. A stop or pause has already
        // cleared isRecordingAudioVideo, so its finalize still counts as coming.
        boolean dropped = enterView != null && enterView.isRecordingAudioVideo()
                && (!live || SystemClock.elapsedRealtime() - phaseSince < MIN_CLIP_MS);
        // A recording phase without a bound preview has a clip on its way: live, stopped, or paused and finalizing
        boolean clipComing = phase == RECORDING && !dropped && (enterView == null || !enterView.hasVideoToSend());
        lockAndMinimize();
        closingChat = new WeakReference<>(chat);
        closingWaitsForClip = clipComing;
        closingSince = SystemClock.elapsedRealtime();
        AndroidUtilities.cancelRunOnUIThread(close);
        AndroidUtilities.runOnUIThread(close); // posted: never finish the chat from inside its own onBackPressed
    }

    private static void closeTick() {
        ChatActivity chat = closingChat != null ? closingChat.get() : null;
        if (chat == null || phase != IDLE || !SharedConfig.appLocked || !canClose(chat)) {
            closingChat = null; // gone, a new memo started, or the user unlocked and may be looking at it
            return;
        }
        ChatActivityEnterView enterView = chat.getChatActivityEnterView(); // an unlock rebuild replaces it, so read it fresh
        if (!closingWaitsForClip || enterView != null && enterView.hasVideoToSend()) {
            closingChat = null;
            chat.finishFragment(false);
            return;
        }
        if (SystemClock.elapsedRealtime() - closingSince > SEND_TIMEOUT_MS) {
            closingChat = null; // a slow finalize may still land here: leave the chat rather than lose the clip
            return;
        }
        AndroidUtilities.runOnUIThread(close, POLL_MS);
    }

    /** ChatActivity.createView, right after the input container is added. */
    public static void onChatViewCreated(ChatActivity chat, ViewGroup contentView, View inputContainer, org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory wallpaperBlurFactory) {
        if (!(phase == STARTING && isPendingChat(chat)) && !isSessionChat(chat)) {
            return;
        }
        // Keeps the history, the action bar and every button on them out of sight and out of reach. The input container
        // and the round camera (added later, just above the input container) stay on top. Not persisted: the unlock
        // rebuild re-runs createView after the session has ended, and the shield is gone.
        View shield = new View(contentView.getContext()) {
            // The chat view lays its ordinary children out below the action bar, which would leave the header drawn and
            // tappable (its menu can clear the history). Stretch to the container's top edge so the shield covers it
            // for both drawing and hit-testing.
            @Override
            public void layout(int l, int t, int r, int b) {
                super.layout(0, 0, ((View) getParent()).getWidth(), b);
            }
        };
        // Same surface as the normal recording scrim: the chat's wallpaper blurred, which has no messages in it and is
        // what the recorder's controls (the white zoom slider included) are drawn for. Opaque here, not 232, since
        // this one hides real content. The composer panel colour is the fallback.
        shield.setBackgroundColor(Theme.getColor(Theme.key_chat_messagePanelBackground, chat.getResourceProvider()));
        if (wallpaperBlurFactory != null) {
            try {
                org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable wallpaper = wallpaperBlurFactory.create(shield);
                wallpaper.setAlpha(255);
                shield.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{
                        new android.graphics.drawable.ColorDrawable(Theme.getColor(Theme.key_chat_messagePanelBackground, chat.getResourceProvider())),
                        wallpaper
                }));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        shield.setClickable(true);
        int index = contentView.indexOfChild(inputContainer);
        contentView.addView(shield, index < 0 ? contentView.getChildCount() : index, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Above the input container: the round camera is drawn just before it (ChatActivity's drawChild), and its backdrop
        // would wash out anything placed below
        index = contentView.indexOfChild(inputContainer);
        addStatusRow(chat, contentView, index < 0 ? contentView.getChildCount() : index + 1);
    }

    // The real header is under the shield, so the recipient's online state and unread count go on it instead
    private static void addStatusRow(ChatActivity chat, ViewGroup contentView, int index) {
        long did = chat.getDialogId();
        if (!MemoRecipientStatus.applies(chat.getCurrentAccount(), did)) {
            return;
        }
        // A plain holder over the shield: it is laid out below the covered header, like any ordinary child, and takes no touches
        android.widget.FrameLayout holder = new android.widget.FrameLayout(contentView.getContext());
        android.widget.TextView row = new android.widget.TextView(contentView.getContext());
        // A plain line where the header's subtitle would be, not a pill: it is about the recipient, while the pills above the
        // circle are about the controls. The composer's text colour, since the shield is the composer panel's surface.
        row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        row.setTypeface(AndroidUtilities.bold());
        row.setTextColor(Theme.getColor(Theme.key_chat_messagePanelText, chat.getResourceProvider()));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable onlineDot = new android.graphics.drawable.GradientDrawable();
        onlineDot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        onlineDot.setColor(Theme.getColor(Theme.key_chats_onlineCircle, chat.getResourceProvider()));
        onlineDot.setSize(AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        row.setCompoundDrawablePadding(AndroidUtilities.dp(6));
        row.setSingleLine(true);
        row.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(24), android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        params.topMargin = AndroidUtilities.dp(16);
        holder.addView(row, params);
        contentView.addView(holder, index, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        MemoRecipientStatus[] status = new MemoRecipientStatus[1];
        status[0] = new MemoRecipientStatus(chat.getCurrentAccount(), did, () -> {
            // Locking ends the session but tears nothing down until the unlock, which can be hours away
            if (!hidesHistory(chat)) {
                status[0].stop();
                row.animate().cancel();
                row.setVisibility(View.GONE);
                return;
            }
            String online = status[0].online ? LocaleController.getString(R.string.Online) : null;
            String unread = status[0].unread > 0 ? LocaleController.formatPluralString("NewMessages", status[0].unread) : null;
            String text = online != null && unread != null ? LocaleController.formatString(R.string.MemoStatusJoin, online, unread)
                    : online != null ? online : unread;
            if (text != null) {
                row.setText(text);
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(online != null ? onlineDot : null, null, null, null);
                if (row.getVisibility() != View.VISIBLE) {
                    row.setAlpha(0f);
                    row.setVisibility(View.VISIBLE);
                }
                row.animate().alpha(1f).setDuration(150).withEndAction(null).start();
            } else if (row.getVisibility() == View.VISIBLE) {
                row.animate().alpha(0f).setDuration(150).withEndAction(() -> row.setVisibility(View.GONE)).start();
            }
        });
        holder.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                status[0].start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                status[0].stop();
            }
        });
    }

    /** End of ChatActivity's needStartRecordVideo delegate. */
    public static void onRecordVideoState(ChatActivity chat, int state) {
        if (!isSessionChat(chat)) {
            return;
        }
        if (state == 0 && phase == STARTING) {
            setPhase(RECORDING);
        } else if (state == 3) {
            stopRequested = true;
        } else if ((state == 1 || state == 4) && phase == RECORDING) {
            setPhase(SENDING);
        } else if (state == 2 || state == 5) {
            lockAndLeave(); // a lock's own cancel echoes here too, but onPasscodeShown has already ended the session
        }
    }

    /** ChatActivity.sendMedia, once the clip has been handed to SendMessagesHelper. Ends the memo on the launcher. */
    public static void onMediaSent(ChatActivity chat) {
        if (isSessionChat(chat) && (phase == RECORDING || phase == SENDING)) {
            // The round video fades first: the passcode would cover it. The lock is already saved,
            // so the wait fails closed, and any other lock cancels the pending leave (onPasscodeShown).
            if (chat.instantCameraView != null && org.telegram.messenger.SharedConfig.animationsEnabled() && AndroidUtilities.getAnimatorDurationScale() > 0 && AndroidUtilities.shouldEnableAnimation()) {
                AndroidUtilities.cancelRunOnUIThread(poll); // the clip is handed off: a timeout firing now would lock and cancel the leave
                chat.instantCameraView.animate().alpha(0f).setDuration(LEAVE_DELAY_MS - 20).start(); // a plain fade: the camera's own shrink glitches under the lock
                AndroidUtilities.cancelRunOnUIThread(leave);
                AndroidUtilities.runOnUIThread(leave, LEAVE_DELAY_MS);
            } else {
                lockAndLeave();
            }
        }
    }

    /** ChatActivity.onBackPressed. True means the back was taken. */
    public static boolean onBackPressed(ChatActivity chat, boolean invoked) {
        if (!isSessionChat(chat) && !(phase == STARTING && isPendingChat(chat))) {
            return false;
        }
        if (invoked) {
            lockAndClose(chat);
        }
        return true;
    }

    public static boolean blocksSwipeBack(ChatActivity chat) {
        return hidesHistory(chat);
    }

    /** ChatActivity's visible-messages read check. The shield is up, so nothing on screen has been read. */
    public static boolean hidesHistory(ChatActivity chat) {
        return isSessionChat(chat) || phase == STARTING && isPendingChat(chat);
    }

    /** LaunchActivity.needPresentFragment / needAddFragmentToStack. Opening anything but our own chat locks. */
    public static void onNavigation(BaseFragment fragment) {
        if (phase == IDLE || phase == STARTING && isPendingChatPush(fragment)) {
            return;
        }
        lockNow();
    }

    private static void poll() {
        if (phase == IDLE) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long age = now - phaseSince;
        ChatActivity chat = sessionChat != null ? sessionChat.get() : null;
        if (phase == STARTING) {
            if (age > START_TIMEOUT_MS) {
                lockNow();
                return;
            }
        } else if (chat == null || chat.isFinished || chat.getParentActivity() == null || !chat.isLastFragment()) {
            lockNow();
            return;
        } else {
            ChatActivityEnterView enterView = chat.getChatActivityEnterView();
            boolean recording = enterView != null && enterView.isRecordingAudioVideo();
            boolean preview = enterView != null && enterView.hasVideoToSend();
            if (phase == SENDING && age > SEND_TIMEOUT_MS) {
                lockNow();
                return;
            }
            if (phase == RECORDING) {
                // stop -> preview passes briefly through neither state, hence the grace
                if (recording || preview) {
                    idleSince = 0;
                } else if (idleSince == 0) {
                    idleSince = now;
                } else if (now - idleSince > IDLE_GRACE_MS) {
                    lockNow();
                    return;
                }
            }
        }
        AndroidUtilities.runOnUIThread(poll, POLL_MS);
    }
}
