package com.dazewell.gram.videonote;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.VideoPlayer;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

/**
 * Press and hold the round video circle to send a hands-free (locked) recording, or the paused preview it
 * stopped into, so the send doesn't need the small button in the corner. A disc fills the circle from its centre
 * while held; once full, letting go sends. A light tick marks the hold beginning (shortly after the touch), a firm one the moment it arms, and a
 * short one when sliding off disarms it. Letting go before it arms is a tap, which pauses or resumes as the
 * composer's pause button would. Sliding off the circle or a second finger (pinch zoom) cancels and nothing
 * changes. The rim is left alone on purpose: it already carries the recording-time arc.
 * A small label above the circle says how it works whenever it's available.
 * While recording, a swipe from the circle also steers the camera: up/down steps the zoom, left/right flips it.
 * It is reported once per touch, once the finger has travelled SWIPE_DP.
 * The preview used to toggle its sound on any tap, which the hold now owns, so its sound moves to a chip in the
 * composer's record controls (VideoPreviewSoundChip) for as long as the preview is up.
 * UI thread only.
 */
public final class VideoHoldToSend {

    private static final long ARM_MS = 250;
    private static final long CANCEL_MS = 150;
    private static final int SWIPE_DP = 48;
    private static final long FILL_DELAY_MS = 120;
    private static final int PRE_FILL_SLOP_DP = 14;
    public static final int SWIPE_UP = 0;
    public static final int SWIPE_DOWN = 1;
    public static final int SWIPE_LEFT = 2;
    public static final int SWIPE_RIGHT = 3;
    private static final float LABEL_FADE_PER_MS = 1f / 150f;

    private final Utilities.Callback0Return<Boolean> canSend;
    private final Runnable send;
    private final Utilities.Callback0Return<VideoPlayer> previewPlayer;
    private final Utilities.Callback0Return<Boolean> previewSends;
    private final Utilities.Callback0Return<Boolean> canToggle;
    private final Runnable toggle;
    private final Utilities.Callback0Return<Boolean> canSwipe;
    private final Utilities.Callback<Integer> swipe;

    private View circle;
    private View host;
    private boolean swipeTracking;
    private float swipeDownX;
    private float swipeDownY;
    private boolean tracking;
    private boolean armed;
    private float progress;
    private ValueAnimator animator;
    private Paint discPaint;
    private Drawable arrow;

    private TextPaint labelPaint;
    private TextPaint hintPaint;
    private Paint labelBackground;
    private final RectF labelRect = new RectF();
    private float labelAlpha;
    private long labelLastDraw;

    private final Runnable armRunnable = this::arm;
    private final Runnable fillRunnable = this::startFill;

    private void startFill() {
        if (!tracking) {
            return;
        }
        buzz(10, 40, HapticFeedbackConstants.CLOCK_TICK);
        animateTo(1f, ARM_MS - FILL_DELAY_MS);
    }

    // previewPlayer is the paused preview's player, null while there's no preview. previewSends says whether the
    // host can send from its preview at all; story replies can't, and keep tap-to-toggle-sound. canToggle is
    // separate from canSend because resuming sends nothing, so slow mode or a disabled send button mustn't block it.
    public VideoHoldToSend(Utilities.Callback0Return<Boolean> canSend, Runnable send,
                           Utilities.Callback0Return<VideoPlayer> previewPlayer, Utilities.Callback0Return<Boolean> previewSends,
                           Utilities.Callback0Return<Boolean> canToggle, Runnable toggle,
                           Utilities.Callback0Return<Boolean> canSwipe, Utilities.Callback<Integer> swipe) {
        this.canSend = canSend;
        this.send = send;
        this.previewPlayer = previewPlayer;
        this.previewSends = previewSends;
        this.canToggle = canToggle;
        this.toggle = toggle;
        this.canSwipe = canSwipe;
        this.swipe = swipe;
    }

    // True when a tap on the preview belongs to the hold rather than toggling its sound.
    public boolean ownsPreviewTaps() {
        return NaConfig.INSTANCE.getVideoMessagesHoldToSend().Bool() && previewSends.run();
    }

    // The paused preview's sound, for the composer's sound chip (VideoPreviewSoundChip), only while a tap on the
    // preview belongs to the hold.
    public boolean hasPreviewSound() {
        return previewPlayer.run() != null && ownsPreviewTaps();
    }

    public boolean isPreviewMuted() {
        final VideoPlayer player = previewPlayer.run();
        return player == null || player.isMuted();
    }

    public boolean togglePreviewSound() {
        final VideoPlayer player = previewPlayer.run();
        if (player == null || !ownsPreviewTaps()) {
            return false;
        }
        player.setMute(!player.isMuted());
        return true;
    }

    // Observes the host's touch stream and never consumes it, so the host's own pinch-to-zoom keeps working.
    public void onTouchEvent(MotionEvent ev, View circle) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                reset();
                if (!NaConfig.INSTANCE.getVideoMessagesHoldToSend().Bool() || !contains(circle, ev)) {
                    return;
                }
                // a swipe from the circle is tracked on its own: it works wherever the hold itself can't arm
                swipeTracking = canSwipe.run();
                swipeDownX = ev.getX();
                swipeDownY = ev.getY();
                final boolean sendable = canSend.run();
                if (!sendable && !canToggle.run()) {
                    return;
                }
                this.circle = circle;
                tracking = true;
                // a hold that can't send has nothing to arm, so only a tap is tracked, without the tick or the fill
                // the tick and the fill wait out FILL_DELAY_MS, so a tap or a swipe, which moves off first, never flashes them
                if (sendable) {
                    AndroidUtilities.runOnUIThread(fillRunnable, FILL_DELAY_MS);
                    AndroidUtilities.runOnUIThread(armRunnable, ARM_MS);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                swipeTracking = false; // a second finger is a pinch
                cancel();
                break;
            case MotionEvent.ACTION_MOVE:
                if (swipeTracking) {
                    final float dx = ev.getX() - swipeDownX;
                    final float dy = ev.getY() - swipeDownY;
                    final float travel = Math.max(Math.abs(dx), Math.abs(dy));
                    if (tracking && progress == 0f && !armed && travel >= dp(PRE_FILL_SLOP_DP)) {
                        cancel(); // already a swipe before the fill began: no fill for it, and no hold either
                    }
                    if (travel >= dp(SWIPE_DP)) {
                        swipeTracking = false;
                        cancel(); // a swipe overrides the hold, even an armed one, so it can't also send on release
                        if (canSwipe.run()) {
                            if (!NaConfig.INSTANCE.getVideoMessagesSwipeLearned().Bool()) {
                                NaConfig.INSTANCE.getVideoMessagesSwipeLearned().setConfigBool(true);
                            }
                            swipe.run(Math.abs(dx) > Math.abs(dy) ? (dx < 0 ? SWIPE_LEFT : SWIPE_RIGHT) : (dy < 0 ? SWIPE_UP : SWIPE_DOWN));
                        }
                        break;
                    }
                }
                if (tracking && !contains(circle, ev)) {
                    cancel();
                }
                break;
            case MotionEvent.ACTION_UP:
                // a fast slide-off can report its last position only in the UP, with no MOVE outside first
                // the event times, not armRunnable, tell a tap from a hold: a stalled main thread can arm before
                // a quick release is processed, and that tap must not send
                final boolean held = ev.getEventTime() - ev.getDownTime() >= ARM_MS;
                if (tracking && armed && held && contains(circle, ev) && canSend.run()) {
                    // cleared before sending: a paid-message confirmation pauses into the preview instead of
                    // closing the camera, and the disc must not stay painted over it
                    reset();
                    if (learnedSends() < LEARNED_SENDS) {
                        NaConfig.INSTANCE.getVideoMessagesHoldToSendSends().setConfigInt(learnedSends() + 1);
                    }
                    send.run();
                } else if (tracking && !held && contains(circle, ev) && canToggle.run()) {
                    // a tap; also where nothing arms, since a long press that can't send isn't one
                    cancel();
                    toggle.run();
                } else {
                    cancel();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                break;
        }
    }

    // Drops any hold at once, without the shrink animation. For the host's camera open/close, pause and
    // segment rollover.
    public void reset() {
        AndroidUtilities.cancelRunOnUIThread(armRunnable);
        AndroidUtilities.cancelRunOnUIThread(fillRunnable);
        tracking = false;
        armed = false;
        swipeTracking = false;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (progress != 0f) {
            progress = 0f;
            if (circle != null) {
                circle.invalidate();
            }
        }
        // the host stops redrawing itself once it stops recording, so let the label see that and fade out
        if (host != null) {
            host.invalidate();
        }
    }

    // The fill, drawn inside the circle so it sits over the camera.
    public void draw(Canvas canvas, View circle) {
        if (progress <= 0f) {
            return;
        }
        final float cx = circle.getWidth() / 2f;
        final float cy = circle.getHeight() / 2f;
        final float radius = Math.min(cx, cy) * CubicBezierInterpolator.EASE_OUT.getInterpolation(progress);
        if (discPaint == null) {
            discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        discPaint.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelVoiceBackground), 0x99));
        canvas.drawCircle(cx, cy, radius, discPaint);

        if (arrow == null) {
            arrow = circle.getContext().getResources().getDrawable(R.drawable.attach_send).mutate();
            arrow.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        }
        final float arrowAlpha = Utilities.clamp((progress - 0.3f) / 0.7f, 1f, 0f);
        final int half = (int) (dp(24) * (0.8f + 0.2f * arrowAlpha));
        arrow.setAlpha((int) (255 * arrowAlpha));
        arrow.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
        arrow.draw(canvas);
    }

    // The how-to label, drawn by the host above the circle, clear of the recording-time arc. The host keeps at
    // least 64dp free above the circle, which this fits in. It teaches, then gets out of the way: a full pill, with
    // the swipes on a second line until the first swipe, and after LEARNED_SENDS hold-to-sends a small "Hold to send".
    public void drawLabel(Canvas canvas, View host, View circle) {
        this.host = host;
        final boolean visible = NaConfig.INSTANCE.getVideoMessagesHoldToSend().Bool() && canSend.run();
        final long now = SystemClock.elapsedRealtime();
        final long dt = labelLastDraw == 0 ? 16 : Math.min(64, now - labelLastDraw);
        labelLastDraw = now;
        final float target = visible ? 1f : 0f;
        if (labelAlpha != target) {
            labelAlpha = visible ? Math.min(1f, labelAlpha + dt * LABEL_FADE_PER_MS) : Math.max(0f, labelAlpha - dt * LABEL_FADE_PER_MS);
            host.invalidate();
        }
        final float alpha = labelAlpha * circle.getAlpha();
        if (alpha <= 0f) {
            labelLastDraw = 0;
            return;
        }
        if (labelPaint == null) {
            labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            labelPaint.setTextSize(dp(13));
            labelPaint.setTypeface(AndroidUtilities.bold());
            labelPaint.setColor(Color.WHITE);
            hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            hintPaint.setTextSize(dp(11));
            hintPaint.setColor(Color.WHITE);
            labelBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelBackground.setColor(0x4d000000);
        }
        final boolean quiet = !armed && learnedSends() >= LEARNED_SENDS;
        final TextPaint paint = quiet ? hintPaint : labelPaint;
        paint.setTypeface(AndroidUtilities.bold());
        final String text = LocaleController.getString(armed ? R.string.VideoMessagesHoldToSendArmed
                : quiet ? R.string.VideoMessagesHoldToSendShort : R.string.VideoMessagesHoldToSendLabel);
        final float textWidth = paint.measureText(text);
        String hint = null;
        float hintWidth = 0;
        // Skipped rather than clipped wherever the room or the width isn't there
        if (!quiet && !NaConfig.INSTANCE.getVideoMessagesSwipeLearned().Bool() && canSwipe.run() && circle.getY() >= dp(64)) {
            hint = LocaleController.getString(R.string.VideoMessagesGesturesHint);
            hintPaint.setTypeface(android.graphics.Typeface.DEFAULT);
            hintWidth = hintPaint.measureText(hint);
            if (Math.max(textWidth, hintWidth) + dp(36) > host.getWidth()) {
                hint = null;
            }
        }
        final float lineHeight = quiet ? dp(18) : dp(24);
        final float height = hint != null ? dp(40) : lineHeight;
        final float width = Math.max(textWidth, hintWidth) + (quiet ? dp(16) : dp(20));
        final float cx = circle.getX() + circle.getWidth() / 2f;
        final float bottom = circle.getY() - dp(20);
        labelRect.set(cx - width / 2f, bottom - height, cx + width / 2f, bottom);
        labelBackground.setAlpha((int) (0x4d * alpha));
        paint.setAlpha((int) (255 * alpha));
        final float radius = quiet ? dp(9) : dp(12);
        canvas.drawRoundRect(labelRect, radius, radius, labelBackground);
        final float firstCenter = hint != null ? labelRect.top + dp(14) : labelRect.centerY();
        canvas.drawText(text, cx - textWidth / 2f, firstCenter - (paint.descent() + paint.ascent()) / 2f, paint);
        if (hint != null) {
            hintPaint.setAlpha((int) (255 * alpha * 0.8f));
            final float secondCenter = labelRect.bottom - dp(12);
            canvas.drawText(hint, cx - hintWidth / 2f, secondCenter - (hintPaint.descent() + hintPaint.ascent()) / 2f, hintPaint);
        }
    }

    private static final int LEARNED_SENDS = 5;

    /** The gestures setting toggled: show the full hint again. */
    public static void resetHints() {
        NaConfig.INSTANCE.getVideoMessagesHoldToSendSends().setConfigInt(0);
        NaConfig.INSTANCE.getVideoMessagesSwipeLearned().setConfigBool(false);
    }

    // Clamped at the read: a stored value from anywhere else is just a count
    private static int learnedSends() {
        return Math.max(0, Math.min(LEARNED_SENDS, NaConfig.INSTANCE.getVideoMessagesHoldToSendSends().Int()));
    }

    private void arm() {
        if (!tracking) {
            return;
        }
        if (!canSend.run()) {
            cancel();
            return;
        }
        armed = true;
        buzz(45, 255, HapticFeedbackConstants.LONG_PRESS);
        if (host != null) {
            host.invalidate(); // the label switches to its armed text
        }
    }

    private void cancel() {
        AndroidUtilities.cancelRunOnUIThread(armRunnable);
        AndroidUtilities.cancelRunOnUIThread(fillRunnable);
        if (!tracking && !armed) {
            return;
        }
        if (armed) {
            buzz(20, 120, HapticFeedbackConstants.KEYBOARD_TAP);
        }
        tracking = false;
        armed = false;
        animateTo(0f, CANCEL_MS);
        if (host != null) {
            host.invalidate();
        }
    }

    // Amplitude is ignored where the vibrator can't vary it. A dead vibrator route falls back to the view's
    // haptic, as RecordingLimitVibration does.
    private void buzz(long ms, int amplitude, int hapticFallback) {
        if (NekoConfig.disableVibration.Bool()) {
            return;
        }
        if (vibrate(ms, amplitude) || circle == null) {
            return;
        }
        try {
            circle.performHapticFeedback(hapticFallback, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        } catch (Exception ignored) {
        }
    }

    private static boolean vibrate(long ms, int amplitude) {
        final Vibrator vibrator = AndroidUtilities.getVibrator();
        if (vibrator == null || !vibrator.hasVibrator()) {
            return false;
        }
        try {
            final VibrationEffect effect = VibrationEffect.createOneShot(ms,
                    vibrator.hasAmplitudeControl() ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE);
            // same attributes as RecordingLimitVibration, for the same Do Not Disturb reason
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
            } else {
                vibrator.vibrate(effect, new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private void animateTo(float target, long duration) {
        if (animator != null) {
            animator.cancel();
        }
        final View view = circle;
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(duration);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            if (view != null) {
                view.invalidate();
            }
        });
        animator.start();
    }

    private static boolean contains(View circle, MotionEvent ev) {
        // same bounds the host's pinch-to-zoom uses
        AndroidUtilities.rectTmp.set(circle.getX(), circle.getY(), circle.getX() + circle.getMeasuredWidth(), circle.getY() + circle.getMeasuredHeight());
        return AndroidUtilities.rectTmp.contains(ev.getX(), ev.getY());
    }
}
