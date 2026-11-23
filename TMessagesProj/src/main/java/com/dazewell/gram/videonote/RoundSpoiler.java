package com.dazewell.gram.videonote;

import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.dazewell.gram.helpers.VideoDraftStore;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.InstantCameraView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.HintView2;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

/**
 * A round video take's Spoiler choice, and what happens to a Send tapped while it is on. One per InstantCameraView,
 * which is one per ChatActivity, so per chat and per account. UI thread only.
 *
 * Spoiler changes nothing about how a take records, uploads live or pauses. It is read once, where the recorder
 * sends a finished file (InstantCameraView.send(4)): the clip then goes out through ChatActivity.sendMedia as an
 * ordinary video with the media spoiler set, exactly as the attach sheet sends one.
 *
 * A live take is not a finished file, so a Send tapped while recording with Spoiler on stops the take into its
 * preview instead (the same stop confirm-before-sending uses) and the controller is FINISHING. When the preview
 * binds, the preview's ordinary send fires as soon as the composer would take a tap, if that send is plain. Anything
 * else (a sheet, a picker, slow mode, a changed destination, the chat gone) leaves the take in the preview with
 * Spoiler still on, which is LANDED, the same as IDLE here. A paused take already has a finished copy, which the
 * recorder sends directly.
 */
public final class RoundSpoiler {

    // from the tap to the send: a stop takes well under a second, so this only catches a stop that never bound
    private static final long FINISH_DEADLINE_MS = 3000;
    private static final long TICK_MS = 50;
    // the recorder cancels a take shorter than this (InstantCameraView.send's recordedTime < 800)
    private static final long MIN_TAKE_MS = 800;
    // the pill's own cells (InstantCameraView's flash and Infinite buttons)
    private static final int CELL_DP = 44;
    // the pill's corner radius (InstantCameraView.setButtonsBackground), which the Infinite cell's fill reproduces
    private static final int CAP_DP = 21;
    // the preview pill comes back once the stock 180 ms fade (setupVideoPlayer) has taken it away
    private static final long PREVIEW_PILL_DELAY_MS = 200;
    private static final long PREVIEW_PILL_FADE_MS = 180;

    private final InstantCameraView recorder;
    // one long-press line at a time, across both surfaces
    private static WeakReference<HintView2> lastHint = new WeakReference<>(null);

    private LinearLayout pill;
    private View[] pillFollowers = new View[0];
    // what the preview pill hid, to put back when the camera opens again
    private final ArrayList<View> hiddenForPreview = new ArrayList<>();
    private boolean previewPillUp;
    private ImageView button;
    private int glyphColor = Color.WHITE;
    private int fillColor = Color.WHITE;
    private int glyphOnColor = Color.BLACK;
    // what the cell last drew: selected, first and last bits, and the fill it used
    private int appliedLook = -1;
    private int appliedFill;
    // the recorder's chat, for the cell's click; weak, as the recorder can outlive a rebuilt fragment
    private WeakReference<ChatActivity> chatRef = new WeakReference<>(null);

    private boolean selected;

    private boolean finishing;
    // false when the tap was not a plain send, so finishing only waits to land
    private boolean autoSend;
    private ChatActivityEnterView finishingView;
    private long finishDeadline;
    // where the tap sent it: the topic, and the reply and quote by reference (a still-sending reply's id changes on ack)
    private long tapTopicId;
    private MessageObject tapReply;
    private ChatActivity.ReplyQuote tapQuote;
    // the preview that bound while finishing; the send only goes if the composer still shows this exact one
    private VideoEditedInfo bound;
    private final Runnable tick = this::tick;

    public RoundSpoiler(InstantCameraView recorder) {
        this.recorder = recorder;
    }

    private static RoundSpoiler of(ChatActivity chat) {
        return chat != null && chat.instantCameraView != null ? chat.instantCameraView.spoiler : null;
    }

    private static ChatActivity chatOf(ChatActivityEnterView enter) {
        return enter != null ? enter.getParentFragment() : null;
    }

    // Where the attach sheet would offer a spoiler on a video (ChatAttachAlertPhotoLayout's hasSpoiler), minus a
    // forum's all-messages view, whose destination is a reply's topic rather than the chat in front of the user.
    private static boolean offered(ChatActivity chat) {
        return chat != null && NaConfig.INSTANCE.getVideoMessagesSpoiler().Bool() && !chat.isSecretChat()
                && !chat.isForumInViewAsMessagesMode() && ChatObject.canSendVideo(chat.getCurrentChat());
    }

    // Infinite as armed, not as its toggle shows it: schedule mode reports it unavailable while a rollover still runs
    private static boolean infiniteArmed(ChatActivity chat) {
        final ChatActivityEnterView enter = chat.getChatActivityEnterView();
        return enter != null && enter.isInfiniteVideoArmed();
    }

    private static boolean onceOn(ChatActivity chat) {
        final ChatActivityEnterView enter = chat.getChatActivityEnterView();
        return enter != null && enter.voiceOnce;
    }

    // Selected, and not overridden by view-once or Infinite Recording turned on since.
    private boolean chosen(ChatActivity chat) {
        return selected && chat != null && !onceOn(chat) && !infiniteArmed(chat);
    }

    // The bound preview's draft record keeps what a send of it would apply.
    private static void persistBound(ChatActivity chat, boolean spoiler) {
        final ChatActivityEnterView enter = chat.getChatActivityEnterView();
        if (enter != null && enter.getBoundVideoDraftId() != 0) {
            VideoDraftStore.setSpoiler(chat.getCurrentAccount(), enter.getBoundVideoDraftDialogId(), enter.getBoundVideoDraftTopicId(), enter.getBoundVideoDraftId(), spoiler);
        }
    }

    // ---- the recorder ----

    // The camera opens: a fresh recording starts without Spoiler, a resume keeps it; either way the pill is whole again.
    public void onShowCamera(boolean fromPaused) {
        restorePill();
        recorder.updateInfiniteButton(); // its availability may have changed in the preview; restorePill can't know
        if (!fromPaused) {
            endFinishing();
            selected = false;
        }
        updateButton(chatRef.get());
    }

    // A discard, or the fragment going (destroy, a passcode rebuild): a send can't follow from here, and a preview
    // pill still waiting to come back must not show over the close.
    public void onAbandon() {
        endFinishing();
        previewPillUp = false;
        if (pill != null) {
            pill.animate().cancel();
        }
    }

    // InstantCameraView.send(4) with ttl as passed: whether this finished file goes out as a spoiler video. Read here
    // and nowhere else; view-once (ttl) and Infinite Recording win over a stale choice.
    public boolean appliesTo(Object delegate, int ttl) {
        onAbandon(); // whoever sent it, the take is leaving
        return selected && ttl == 0 && delegate instanceof ChatActivity && !infiniteArmed((ChatActivity) delegate);
    }

    // The bound preview is pause's finished copy rather than the encoder's own, still-open file. Only then is the copy
    // what the user sees and what the draft store holds, and only while nothing is recording into the encoder.
    public static boolean isPausedCopy(VideoEditedInfo info, File cameraFile, boolean recording, boolean cameraOpen) {
        return info != null && info.originalPath != null && cameraFile != null && !recording && !cameraOpen
                && !info.originalPath.equals(cameraFile.getAbsolutePath()) && new File(info.originalPath).exists();
    }

    // InstantCameraView.adoptRestoredDraft took a stored draft: its Spoiler comes back with it, with a cue, since a
    // restored draft has no recorder buttons to show it on.
    public void onRestored(Object delegate, VideoEditedInfo info, int account) {
        endFinishing();
        selected = info != null && VideoDraftStore.isSpoiler(account, info.naxDraftDialogId, info.naxDraftTopicId, info.naxDraftId);
        if (selected && delegate instanceof ChatActivity) {
            BulletinFactory.of((ChatActivity) delegate).createSimpleBulletin(R.raw.photo_spoiler, LocaleController.getString(R.string.VideoMessagesSpoilerRestored)).show();
        }
    }

    // ---- the composer ----

    /**
     * The composer's live video Send, at each of its three sites, before it commits to sending. True when it was
     * taken here: a Spoiler take is stopped into its preview (as confirm-before-sending does) and the caller returns
     * without its send. A paid chat never reaches this; its own branch pauses first and confirms over the preview.
     */
    public static boolean finishFirst(ChatActivityEnterView enter) {
        final ChatActivity chat = chatOf(enter);
        final RoundSpoiler s = of(chat);
        if (s == null) {
            return false;
        }
        if (s.finishing) {
            return true; // already stopping for an earlier tap
        }
        if (!s.chosen(chat) || s.recorder.getCoverSegmentTime() < MIN_TAKE_MS) {
            return false; // a take too short to keep is cancelled by the stock send, as without Spoiler
        }
        if (!s.recorder.isRecording()) {
            return true; // a resume is still bringing the camera up; the stock send would leave as a plain round
        }
        s.finishing = true;
        s.autoSend = !NekoConfig.confirmAVMessage.Bool() && !AlertsCreator.needsPaidMessageAlert(chat.getCurrentAccount(), chat.getDialogId());
        s.finishingView = enter;
        s.finishDeadline = SystemClock.elapsedRealtime() + FINISH_DEADLINE_MS;
        s.tapTopicId = chat.getTopicId();
        s.tapReply = chat.getReplyMessage();
        s.tapQuote = chat.getReplyQuote();
        s.bound = null;
        chat.finalizeRoundVideoForLock(); // send(3): the stop into the preview that confirm-before-sending uses
        VideoNoteShortcut.onRecordVideoState(chat, 3); // what needStartRecordVideo(3) would tell it
        AndroidUtilities.runOnUIThread(s.tick, TICK_MS);
        return true;
    }

    // The composer's admission gate just bound a finished preview and onVideoDraftReady has saved its record.
    public static void onPreviewBound(ChatActivity chat, VideoEditedInfo info) {
        final RoundSpoiler s = of(chat);
        if (s == null || info == null) {
            return;
        }
        if (s.chosen(chat)) { // what send(4) would apply, so a restored draft's cue never promises more
            VideoDraftStore.setSpoiler(chat.getCurrentAccount(), info.naxDraftDialogId, info.naxDraftTopicId, info.naxDraftId, true);
        }
        if (s.finishing && s.finishingView == chat.getChatActivityEnterView()) {
            s.bound = info;
            setSendLoading(s.finishingView, true);
        }
        if (!s.finishing || !s.autoSend) {
            s.showPreviewPill(chat); // an auto-send is about to close the recorder; a landing shows it from tick
        }
    }

    /**
     * The composer's send runnable, before it sends anything. A Spoiler preview in a chat that can no longer take
     * videos is refused with the chat's own reason and stays in the preview, rather than going out without its cover.
     */
    public static boolean refusesSend(ChatActivityEnterView enter) {
        final ChatActivity chat = chatOf(enter);
        final RoundSpoiler s = of(chat);
        if (s == null || !enter.hasVideoToSend() || !s.chosen(chat)) {
            return false;
        }
        if (s.recorder.spoilerLacksFinishedFile()) {
            // send(4) has no finished file to cover; refused here, before the composer clears the preview and draft
            BulletinFactory.of(chat).createErrorBulletin(LocaleController.getString(R.string.VideoMessagesSpoilerUnfinished)).show();
            return true;
        }
        if (ChatObject.canSendVideo(chat.getCurrentChat())) {
            return false;
        }
        final String reason = ChatObject.getRestrictedErrorText(chat.getCurrentChat(), ChatObject.ACTION_SEND_VIDEO);
        BulletinFactory.of(chat).createErrorBulletin(reason != null ? reason : LocaleController.getString(R.string.GlobalAttachMediaRestricted)).show();
        return true;
    }

    // Back while finishing would cancel the take whose Send was just tapped, before its preview exists to keep it.
    public static boolean holdsBack(InstantCameraView recorder) {
        return recorder != null && recorder.spoiler.finishing;
    }

    private void tick() {
        if (!finishing) {
            return;
        }
        final ChatActivityEnterView enter = finishingView;
        final ChatActivity chat = chatOf(enter);
        if (chat == null || !chat.isCurrentEnterView(enter)) {
            endFinishing(); // the view is gone; a rebuilt one comes back through onRestored
            return;
        }
        if (SystemClock.elapsedRealtime() > finishDeadline) {
            final boolean wasBound = bound != null;
            endFinishing();
            if (wasBound) {
                showPreviewPill(chat); // landed: the preview bound but never took a tap in time
            }
            return;
        }
        if (bound == null) {
            AndroidUtilities.runOnUIThread(tick, TICK_MS); // still stopping
            return;
        }
        if (!autoSend || !isPlain(chat, enter)) {
            endFinishing(); // landed: the preview stays, Spoiler on
            showPreviewPill(chat);
            return;
        }
        if (!enter.isSendButtonTappable()) {
            AndroidUtilities.runOnUIThread(tick, TICK_MS); // the preview's own transition is still running
            return;
        }
        endFinishing();
        if (!enter.sendVideoPreviewIfPlain()) {
            showPreviewPill(chat); // landed: an armed send action or a rich draft wants the user's own tap
        }
    }

    // What a user tapping the preview's send would get without being asked anything, to the place they recorded in:
    // the topic of the tap and of the draft (pinned at recording start), and the same reply and quote.
    private boolean isPlain(ChatActivity chat, ChatActivityEnterView enter) {
        return !chat.isPaused() && chat.getParentActivity() != null && chat.getChatActivityEnterView() == enter
                && bound.naxDraftId != 0 && enter.getBoundVideoDraftId() == bound.naxDraftId
                && bound.originalPath != null && new File(bound.originalPath).exists()
                && !recorder.isFileHandedOff() && chosen(chat) && offered(chat)
                && !chat.isInScheduleMode() && enter.getSlowModeTimer() == null
                && !AlertsCreator.needsPaidMessageAlert(chat.getCurrentAccount(), chat.getDialogId())
                && chat.getTopicId() == tapTopicId && bound.naxDraftTopicId == tapTopicId
                && chat.getReplyMessage() == tapReply && chat.getReplyQuote() == tapQuote;
    }

    // FINISHING ends, sent or landed. Nothing but the spinner and the pending tick to undo.
    private void endFinishing() {
        AndroidUtilities.cancelRunOnUIThread(tick);
        if (finishing && finishingView != null) {
            setSendLoading(finishingView, false);
        }
        finishing = false;
        finishingView = null;
        bound = null;
        tapReply = null;
        tapQuote = null;
    }

    private static void setSendLoading(ChatActivityEnterView enter, boolean loading) {
        final View send = enter.getSendButtonInternal();
        if (send instanceof ChatActivityEnterView.SendButton) {
            ((ChatActivityEnterView.SendButton) send).setLoading(loading, ChatActivityEnterView.SendButton.INFINITE_LOADING);
        }
    }

    // ---- the button ----

    /**
     * The Spoiler cell joins the recorder's flash and Infinite pill (InstantCameraView's buttonsLayout), right after
     * flash, sized as they are and drawn on the pill's glass. alsoHidden are the views beside the pill that follow its
     * alpha (the flash brightness arm), so they leave with flash when the pill comes back for the preview.
     */
    public void joinPill(LinearLayout pill, View after, Object delegate, View... alsoHidden) {
        this.pill = pill;
        this.pillFollowers = alsoHidden;
        chatRef = new WeakReference<>(delegate instanceof ChatActivity ? (ChatActivity) delegate : null);
        button = new ImageView(pill.getContext());
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setColorFilter(new PorterDuffColorFilter(glyphColor, PorterDuff.Mode.SRC_IN));
        // the pill stays clickable while faded out, as the stock flash button does; only a showing pill takes a tap
        button.setOnClickListener(v -> {
            if (pill.getAlpha() > 0.5f) {
                toggle(chatRef.get());
            }
        });
        button.setOnLongClickListener(v -> {
            if (pill.getAlpha() <= 0.5f) {
                return false;
            }
            showHint(recorder, button, HintView2.DIRECTION_BOTTOM);
            return true;
        });
        pill.addView(button, pill.indexOfChild(after) + 1, LayoutHelper.createLinear(CELL_DP, CELL_DP));
        // Before each frame: while the preview pill is up, anything the recorder shows again (Infinite when view-once
        // goes off, the brightness arm when compact is toggled) is hidden before it draws, and that frame is skipped for
        // the relayout. The cell's state and its fill's corners follow view-once, Infinite and its neighbours, so they
        // are re-read too. On the window's shared observer only while attached, so a closed chat is not kept.
        final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
            final boolean draw = !previewPillUp || !hideBesideCell();
            updateButton(chatRef.get());
            return draw;
        };
        button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().addOnPreDrawListener(preDraw);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.getViewTreeObserver().removeOnPreDrawListener(preDraw);
            }
        });
        button.setImageResource(R.drawable.msg_spoiler);
        updateButton(chatRef.get());
    }

    // From InstantCameraView.updateThemeColors: the flash glyph's tint, and the Infinite cell's selected fill and glyph.
    public void setColors(int glyph, int fill, int glyphOn) {
        glyphColor = glyph;
        fillColor = fill;
        glyphOnColor = glyphOn;
        appliedLook = -1;
        applyLook();
    }

    // The preview: the stock fade (setupVideoPlayer) takes the pill away with flash and Infinite, which mean nothing
    // there. Spoiler still does, so the pill comes back holding only this cell, as the memo cover's pill does.
    private void showPreviewPill(ChatActivity chat) {
        if (pill == null || button == null || !isOffered(chat) || pill.getVisibility() != View.VISIBLE) {
            return; // compact recording hides the whole pill, and keeps it hidden
        }
        hideBesideCell();
        previewPillUp = true;
        pill.animate().cancel();
        pill.animate().alpha(1f).setStartDelay(PREVIEW_PILL_DELAY_MS).setDuration(PREVIEW_PILL_FADE_MS).start();
    }

    // true when something was showing and is hidden now
    private boolean hideBesideCell() {
        boolean hid = false;
        for (int i = 0; i < pill.getChildCount(); i++) {
            hid |= hideForPreview(pill.getChildAt(i));
        }
        for (View v : pillFollowers) {
            hid |= hideForPreview(v);
        }
        return hid;
    }

    private boolean hideForPreview(View v) {
        if (v == null || v == button || v.getVisibility() == View.GONE) {
            return false;
        }
        v.setVisibility(View.GONE); // INVISIBLE too: compact's state for the arm, which it rewrites on a toggle
        if (!hiddenForPreview.contains(v)) {
            hiddenForPreview.add(v);
        }
        return true;
    }

    // Out of the preview (a resume, a new take): what it hid is back before the stock open animation shows the pill.
    private void restorePill() {
        previewPillUp = false;
        if (pill != null) {
            pill.animate().cancel();
        }
        for (View v : hiddenForPreview) {
            if (v.getVisibility() == View.GONE) {
                // the pill's cells were showing; the arm is compact recording's to show, and it shows it with the pill
                v.setVisibility(v.getParent() == pill ? View.VISIBLE : pill.getVisibility());
            }
        }
        hiddenForPreview.clear();
    }

    /** The memo cover's Spoiler cell, and the column button: flip the take's choice. */
    public void toggle(ChatActivity chat) {
        if (chat == null || !isOffered(chat)) {
            return;
        }
        if (chosen(chat) || !offered(chat)) {
            selected = false; // a chat that stopped offering it still lets a selection be turned off
        } else {
            // each turns the other off; both calls are flips, so only where they are on
            if (infiniteArmed(chat)) {
                if (chat.getInfiniteRecordingState() != InstantCameraView.INFINITE_RECORDING_ON) {
                    return; // armed where its own toggle is gone (schedule mode): Infinite keeps the take
                }
                chat.toggleInfiniteRecording();
                recorder.updateInfiniteButton(); // a preview pill re-hides the cell this shows, before it draws
            }
            selected = true;
            final ChatActivityEnterView enter = chat.getChatActivityEnterView();
            if (enter != null && enter.voiceOnce && enter.canToggleVideoOnce()) {
                enter.toggleVideoOnce();
            }
        }
        final boolean wasBound = finishing && bound != null;
        endFinishing(); // a change of mind while stopping lands the take instead of sending it
        if (wasBound) {
            showPreviewPill(chat);
        }
        if (button != null) {
            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
        updateButton(chat); // first: a view-once that could not be turned off clears selected here
        persistBound(chat, chosen(chat));
    }

    /** Whether the memo cover shows its Spoiler cell: offered, or still selected so it can be turned off. */
    public boolean isOffered(ChatActivity chat) {
        return offered(chat) || selected && chat != null;
    }

    public boolean isOn(ChatActivity chat) {
        return chosen(chat);
    }

    private void updateButton(ChatActivity chat) {
        if (chat != null && selected && (onceOn(chat) || infiniteArmed(chat))) {
            selected = false; // view-once or Infinite turned on since: each turns the other off
            persistBound(chat, false); // so a restored draft doesn't bring back a choice the user saw cleared
        }
        if (button == null) {
            return;
        }
        final int visibility = chat == null || isOffered(chat) ? View.VISIBLE : View.GONE;
        if (chat != null && button.getVisibility() != visibility) {
            button.setVisibility(visibility);
        }
        if (!Boolean.valueOf(selected).equals(button.getTag())) {
            button.setTag(selected);
            button.setContentDescription(LocaleController.getString(selected ? R.string.AccDescrVideoSpoilerOff : R.string.AccDescrVideoSpoilerOn));
        }
        applyLook();
    }

    /**
     * Selected looks as the Infinite cell does (InstantCameraView.applyInfiniteRecordingState): one glyph, and a fill
     * that is the pill's own shape where the cell is an end of it and square against a neighbour. Here that changes:
     * Infinite comes and goes on the right, and the preview pill is this cell alone. The flash arm grows from the
     * flash cell's top and its fillet stops at the row's top edge, so a square left edge meets it as Infinite's did.
     */
    private void applyLook() {
        if (button == null || pill == null) {
            return;
        }
        boolean first = true, last = true;
        boolean before = true;
        for (int i = 0; i < pill.getChildCount(); i++) {
            final View c = pill.getChildAt(i);
            if (c == button) {
                before = false;
            } else if (c.getVisibility() != View.GONE) {
                if (before) {
                    first = false;
                } else {
                    last = false;
                }
            }
        }
        final int cap = AndroidUtilities.dp(CAP_DP);
        final int bl = first ? cap : 0, r = last ? cap : 0;
        // Beside flash, the brightness arm rises from the flash cell and its fillet curves onto the row's top edge
        // just right of it. A square corner there would meet that curve as a coloured block under a strip of the
        // arm's glass, so the corner rounds as the arm grows, and the curve runs on into the row's glass instead.
        final int tl = first ? cap : (int) (cap * armOut());
        final int look = (selected ? 1 : 0) | (bl > 0 ? 2 : 0) | (r > 0 ? 4 : 0) | (tl << 3);
        if (look == appliedLook && fillColor == appliedFill) {
            return;
        }
        appliedLook = look;
        appliedFill = fillColor;
        button.setBackground(selected ? Theme.createRoundRectDrawable(tl, r, r, bl, fillColor) : null);
        button.setColorFilter(new PorterDuffColorFilter(selected ? glyphOnColor : glyphColor, PorterDuff.Mode.SRC_IN));
    }

    // How far the flash brightness arm is out, 0 to 1; 0 when it isn't showing.
    private float armOut() {
        for (View v : pillFollowers) {
            if (v instanceof FrontFlashBrightnessSlider && v.getVisibility() == View.VISIBLE) {
                return ((FrontFlashBrightnessSlider) v).shownFraction();
            }
        }
        return 0f;
    }

    /**
     * The long-press line, as the composer's view-once hint is built (ControlsView.showOnceHint): a HintView2 filling
     * host, its pointer on anchor. DIRECTION_BOTTOM sits above the recorder pill's cell, DIRECTION_TOP below the memo
     * cover's.
     */
    public static void showHint(FrameLayout host, View anchor, int direction) {
        final HintView2 last = lastHint.get();
        if (last != null) {
            last.hide();
        }
        // anchor's centre in host, through any pill it sits in
        float x = anchor.getWidth() / 2f, y = anchor.getHeight() / 2f;
        for (View v = anchor; v != null && v != host; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            x += v.getX();
            y += v.getY();
        }
        final HintView2 h = new HintView2(host.getContext(), direction);
        h.setMultilineText(true);
        h.setText(LocaleController.getString(R.string.VideoMessagesSpoilerHint));
        h.setMaxWidthPx(HintView2.cutInFancyHalf(h.getText(), h.getTextPaint()));
        final FrameLayout.LayoutParams lp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL);
        if (direction == HintView2.DIRECTION_BOTTOM) {
            h.setJointPx(0, x);
            lp.bottomMargin = Math.max(0, host.getHeight() - (int) (y - anchor.getHeight() / 2f));
        } else {
            h.setJointPx(0, x);
            lp.topMargin = (int) (y + anchor.getHeight() / 2f);
        }
        host.addView(h, lp);
        h.setOnHiddenListener(() -> AndroidUtilities.removeFromParent(h));
        lastHint = new WeakReference<>(h);
        h.show();
    }
}
