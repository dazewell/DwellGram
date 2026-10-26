package com.dazewell.gram.videonote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.InstantCameraView;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * #camera-memo: the plain camera screen over a camera memo's chat. Opaque black everywhere except the round camera,
 * which shows through a hole cut to its exact on-screen bounds, so the stock live circle is never scaled, dimmed or
 * covered. A touch that starts on the circle goes through to it, so the round video's own gestures work as usual.
 * The recording cue and the controls are drawn here, white on black, with no Telegram colour.
 *
 * Fixed dark in both themes on purpose: a theme colour would give the app away. Everything it shows is read from the
 * recorder: the red dot and the running clock only while it is really capturing.
 */
@SuppressLint("ViewConstructor")
final class CameraMemoCover extends FrameLayout {

    private static final long TICK_MS = 250;
    private static final long CAP_MS = 60_000; // InstantCameraView's round video limit
    private static final long MIN_CLIP_MS = 1_000; // togglePause cancels anything under 800 ms
    private static final long PAUSE_UNTIL_MS = 59_000; // the composer hides its pause button from here
    private static final long WARN_FROM_MS = 55_000;
    private static final long START_TIMEOUT_MS = 6_000;

    private static final int RED = 0xffff3b30;
    private static final int AMBER = 0xffffb300;
    private static final int TONAL = 0xff2b2e33;

    private final WeakReference<ChatActivity> chatRef;
    private final long attachedAt = SystemClock.elapsedRealtime();

    private final Path holePath = new Path();
    private final RectF hole = new RectF();
    private boolean hasHole;
    private final int[] location = new int[2];
    private final int[] ownLocation = new int[2];

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ControlButton discardButton;
    private final ControlButton sendButton;
    private final ControlButton pauseButton;
    private final TextView failedText;
    private final TextView closeButton;

    // Recorder state, read every tick
    private boolean recording;
    private boolean bound;
    private boolean wasRecording;
    private boolean everRecorded;
    private long elapsed;
    private long lastTick;
    private boolean committed;
    private boolean failed;
    private boolean removed;
    private boolean statusBarRestored;
    private boolean touchThrough;
    private int controlsTop = -1;

    private final Runnable tick = this::tick;

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        // Same traversal as the draw, so the hole never lags the circle's open animation by a frame
        updateHole();
        return true;
    };

    CameraMemoCover(Context context, ChatActivity chat) {
        super(context);
        chatRef = new WeakReference<>(chat);
        setWillNotDraw(false);
        // Drawn and hit-tested above every later child of the chat's view (its hints and tag bar are added after this),
        // with no shadow
        setOutlineProvider(null);
        setTranslationZ(1f);
        setClickable(true); // the chat under it, the composer and the round camera's own gestures all stay out of reach
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        textPaint.setColor(0xffffffff);
        textPaint.setTypeface(medium);
        textPaint.setTextSize(sp(20));
        textPaint.setFontFeatureSettings("tnum");
        dimTextPaint.setColor(0xb3ffffff);
        dimTextPaint.setTypeface(medium);
        dimTextPaint.setTextSize(sp(20));
        dimTextPaint.setFontFeatureSettings("tnum");

        discardButton = new ControlButton(context, ControlButton.DISCARD);
        discardButton.setContentDescription(LocaleController.getString(R.string.Discard));
        discardButton.setOnClickListener(v -> {
            ChatActivity c = chatRef.get();
            if (c != null && discardButton.isEnabled() && CameraMemo.discard(c)) {
                committed = true;
                refresh();
            }
        });
        sendButton = new ControlButton(context, ControlButton.SEND);
        sendButton.setContentDescription(LocaleController.getString(R.string.Send));
        sendButton.setOnClickListener(v -> {
            ChatActivity c = chatRef.get();
            if (c != null && sendButton.isEnabled() && CameraMemo.send(c)) {
                committed = true;
                refresh();
            }
        });
        pauseButton = new ControlButton(context, ControlButton.PAUSE);
        pauseButton.setOnClickListener(v -> onPauseClick());
        addView(discardButton);
        addView(sendButton);
        addView(pauseButton);

        failedText = new TextView(context);
        failedText.setTextColor(0xffffffff);
        failedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        failedText.setGravity(Gravity.CENTER);
        failedText.setText(LocaleController.getString(R.string.CameraMemoFailed));
        failedText.setVisibility(GONE);
        addView(failedText);

        closeButton = new TextView(context);
        closeButton.setTextColor(0xffffffff);
        closeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        closeButton.setTypeface(medium);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setText(LocaleController.getString(R.string.Close));
        GradientDrawable closeBackground = new GradientDrawable();
        closeBackground.setColor(TONAL);
        closeBackground.setCornerRadius(AndroidUtilities.dp(24));
        closeButton.setBackground(closeBackground);
        closeButton.setPadding(AndroidUtilities.dp(28), 0, AndroidUtilities.dp(28), 0);
        closeButton.setVisibility(GONE);
        closeButton.setOnClickListener(v -> {
            ChatActivity c = chatRef.get();
            if (c != null) {
                CameraMemo.close(c);
            }
        });
        addView(closeButton);

        refresh();
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }

    /** The camera didn't start: say so, and offer the way out. Overruled the moment the recorder is capturing. */
    void showFailed() {
        readRecorder();
        failed = !recording;
        refresh();
    }

    /** CameraMemo.end, posted. */
    void remove() {
        if (removed) {
            return;
        }
        removed = true;
        AndroidUtilities.cancelRunOnUIThread(tick);
        restoreStatusBar();
        if (getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) getParent()).removeView(this);
        }
    }

    // Here and on detach: the chat can also be torn down under the cover, without remove()
    private void restoreStatusBar() {
        if (statusBarRestored) {
            return;
        }
        statusBarRestored = true;
        ChatActivity chat = chatRef.get();
        Activity activity = chat != null ? chat.getParentActivity() : null;
        if (activity != null && chat.isLastFragment()) {
            AndroidUtilities.setLightStatusBar(activity.getWindow(), chat.isLightStatusBar());
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
        ChatActivity chat = chatRef.get();
        Activity activity = chat != null ? chat.getParentActivity() : null;
        if (activity != null) {
            AndroidUtilities.setLightStatusBar(activity.getWindow(), false);
        }
        lastTick = SystemClock.elapsedRealtime();
        AndroidUtilities.cancelRunOnUIThread(tick);
        AndroidUtilities.runOnUIThread(tick, TICK_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        AndroidUtilities.cancelRunOnUIThread(tick);
        restoreStatusBar();
        CameraMemo.onCoverDetached(this);
    }

    // A gesture that starts on the circle is the round video's: declining its DOWN hands the whole gesture to the camera
    // view below, whose tap, hold and swipes then behave exactly as in a chat. Everything else stays here.
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchThrough = !failed && hasHole && isInHole(event.getX(), event.getY());
        }
        if (touchThrough) {
            return false;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean isInHole(float x, float y) {
        float rx = hole.width() / 2f;
        float ry = hole.height() / 2f;
        if (rx <= 0 || ry <= 0) {
            return false;
        }
        float dx = (x - hole.centerX()) / rx;
        float dy = (y - hole.centerY()) / ry;
        return dx * dx + dy * dy <= 1f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        super.onTouchEvent(event);
        return true;
    }

    private void tick() {
        if (removed) {
            return;
        }
        ChatActivity chat = chatRef.get();
        if (chat == null || chat.isFinished || chat.getParentActivity() == null) {
            CameraMemo.end();
            return;
        }
        if (!failed && !everRecorded && !VideoNoteShortcut.isLockedMemo()
                && SystemClock.elapsedRealtime() - attachedAt > START_TIMEOUT_MS) {
            failed = true; // locked, the phase machine's own start timeout locks instead
        }
        refresh();
        AndroidUtilities.runOnUIThread(tick, TICK_MS);
    }

    private void readRecorder() {
        ChatActivity chat = chatRef.get();
        InstantCameraView camera = chat != null ? chat.instantCameraView : null;
        ChatActivityEnterView enterView = chat != null ? chat.getChatActivityEnterView() : null;
        long now = SystemClock.elapsedRealtime();
        recording = camera != null && camera.isRecording();
        bound = enterView != null && enterView.hasVideoToSend();
        if (recording && wasRecording) {
            elapsed += now - lastTick;
        }
        if (recording) {
            everRecorded = true;
            failed = false; // the recorder overrules the cover: never a failure message over a live recording
        }
        wasRecording = recording;
        lastTick = now;
    }

    private void onPauseClick() {
        ChatActivity chat = chatRef.get();
        if (chat == null || !pauseButton.isEnabled()) {
            return;
        }
        readRecorder();
        if (recording || isPaused()) {
            chat.toggleRecordingPause();
        }
        refresh();
    }

    private void refresh() {
        if (removed) {
            return;
        }
        readRecorder();
        updateHole();
        boolean live = !failed && !committed;
        discardButton.setEnabled(live && (recording || bound));
        sendButton.setEnabled(live && (recording && elapsed >= MIN_CLIP_MS || !recording && bound));
        boolean canPause = live && recording && elapsed >= MIN_CLIP_MS && elapsed < PAUSE_UNTIL_MS;
        boolean canResume = live && isPaused();
        pauseButton.setEnabled(canPause || canResume);
        pauseButton.setPlaying(isPaused());
        pauseButton.setContentDescription(LocaleController.getString(isPaused() ? R.string.AccActionResume : R.string.AccActionPause));
        int controls = failed ? GONE : VISIBLE;
        discardButton.setVisibility(controls);
        sendButton.setVisibility(controls);
        pauseButton.setVisibility(controls);
        failedText.setVisibility(failed ? VISIBLE : GONE);
        closeButton.setVisibility(failed && !VideoNoteShortcut.isLockedMemo() ? VISIBLE : GONE);
        setContentDescription(stateText());
        invalidate();
    }

    // A clip waiting in the preview, short of the cap, with the camera off. Whatever paused it (the Pause button, a tap on
    // the circle, the onPause grace), the recorder can pick it up again from here.
    private boolean isPaused() {
        return !recording && bound && elapsed < PAUSE_UNTIL_MS;
    }

    // The circle's bounds as drawn, open animation included, in this view's coordinates. Fails open: while the recorder
    // is capturing and the circle can't be placed, the cover gets out of the way rather than black out a recording.
    private void updateHole() {
        ChatActivity chat = chatRef.get();
        InstantCameraView camera = chat != null ? chat.instantCameraView : null;
        View container = camera != null ? camera.getCameraContainer() : null;
        boolean valid = container != null && camera.isAttachedToWindow() && camera.getVisibility() == VISIBLE
                && container.isAttachedToWindow() && container.getWidth() > 0 && container.getHeight() > 0;
        float left = 0, top = 0, right = 0, bottom = 0;
        if (valid) {
            container.getLocationInWindow(location);
            getLocationInWindow(ownLocation);
            float scaleX = container.getScaleX() * camera.getScaleX();
            float scaleY = container.getScaleY() * camera.getScaleY();
            left = location[0] - ownLocation[0];
            top = location[1] - ownLocation[1];
            right = left + container.getWidth() * scaleX;
            bottom = top + container.getHeight() * scaleY;
            valid = right > left && bottom > top;
        }
        boolean isRecording = camera != null && camera.isRecording();
        setVisibility(isRecording && !valid ? INVISIBLE : VISIBLE);
        if (valid != hasHole || valid && (hole.left != left || hole.top != top || hole.right != right || hole.bottom != bottom)) {
            hasHole = valid;
            hole.set(left, top, right, bottom);
            holePath.reset();
            if (valid) {
                holePath.addOval(hole, Path.Direction.CW);
            }
            if (computeControlsTop() != controlsTop) {
                requestLayout();
            }
            invalidate();
        }
    }

    // Below the circle where there's room, never past the navigation bar
    private int computeControlsTop() {
        int height = getHeight();
        int rowHeight = AndroidUtilities.dp(72);
        int lowest = height - AndroidUtilities.navigationBarHeight - AndroidUtilities.dp(32) - rowHeight;
        int top = lowest - AndroidUtilities.dp(40);
        if (hasHole && top < hole.bottom + AndroidUtilities.dp(24)) {
            top = (int) hole.bottom + AndroidUtilities.dp(24);
        }
        return Math.max(0, Math.min(top, lowest));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        int small = MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(56), MeasureSpec.EXACTLY);
        int big = MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(72), MeasureSpec.EXACTLY);
        discardButton.measure(small, small);
        pauseButton.measure(small, small);
        sendButton.measure(big, big);
        failedText.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width - AndroidUtilities.dp(64)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
        closeButton.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // The chat's view lays its ordinary children out below the action bar and above the keyboard inset. Stretch to
        // the whole container, like the locked memo's shield, so the header is covered for drawing and touches alike.
        // ViewGroup.layout is final, hence the frame is reset here instead.
        View parent = (View) getParent();
        if (parent != null && (getLeft() != 0 || getTop() != 0 || getRight() != parent.getWidth() || getBottom() != parent.getHeight())) {
            setLeft(0);
            setTop(0);
            setRight(parent.getWidth());
            setBottom(parent.getHeight());
        }
        int width = getWidth();
        int height = getHeight();
        controlsTop = computeControlsTop();
        int centerY = controlsTop + AndroidUtilities.dp(36);
        int centerX = width / 2;
        int offset = AndroidUtilities.dp(104);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        ControlButton start = rtl ? pauseButton : discardButton;
        ControlButton end = rtl ? discardButton : pauseButton;
        place(start, centerX - offset, centerY);
        place(sendButton, centerX, centerY);
        place(end, centerX + offset, centerY);
        int textTop = height / 2 - failedText.getMeasuredHeight();
        failedText.layout((width - failedText.getMeasuredWidth()) / 2, textTop,
                (width + failedText.getMeasuredWidth()) / 2, textTop + failedText.getMeasuredHeight());
        int closeTop = textTop + failedText.getMeasuredHeight() + AndroidUtilities.dp(24);
        closeButton.layout((width - closeButton.getMeasuredWidth()) / 2, closeTop,
                (width + closeButton.getMeasuredWidth()) / 2, closeTop + closeButton.getMeasuredHeight());
    }

    private static void place(View view, int centerX, int centerY) {
        int w = view.getMeasuredWidth();
        int h = view.getMeasuredHeight();
        view.layout(centerX - w / 2, centerY - h / 2, centerX + w / 2, centerY + h / 2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        if (hasHole) {
            canvas.clipOutPath(holePath);
        }
        canvas.drawColor(0xff000000);
        canvas.restore();
        if (!failed) {
            drawStatus(canvas);
        }
    }

    private String stateText() {
        if (failed) {
            return LocaleController.getString(R.string.CameraMemoFailed);
        }
        if (!everRecorded) {
            return LocaleController.getString(R.string.CameraMemoStarting);
        }
        String time = formatTime(elapsed) + " / " + formatTime(CAP_MS);
        if (recording) {
            return time;
        }
        return LocaleController.getString(isPaused() ? R.string.CameraMemoPaused : R.string.CameraMemoStopped) + " " + time;
    }

    private static String formatTime(long ms) {
        long seconds = Math.min(ms, CAP_MS) / 1000;
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    // Top centre: a blinking red dot and the clock while capturing, a pause or stop glyph and its word otherwise
    private void drawStatus(Canvas canvas) {
        float baseline = Math.max(AndroidUtilities.statusBarHeight + AndroidUtilities.dp(44), AndroidUtilities.dp(56));
        if (hasHole && baseline > hole.top - AndroidUtilities.dp(16)) {
            baseline = Math.max(AndroidUtilities.statusBarHeight + AndroidUtilities.dp(24), hole.top - AndroidUtilities.dp(16));
        }
        if (!everRecorded) {
            String text = LocaleController.getString(R.string.CameraMemoStarting);
            canvas.drawText(text, (getWidth() - dimTextPaint.measureText(text)) / 2f, baseline, dimTextPaint);
            return;
        }
        textPaint.setColor(recording && elapsed >= WARN_FROM_MS ? AMBER : 0xffffffff);
        boolean paused = isPaused();
        String word = recording ? null : LocaleController.getString(paused ? R.string.CameraMemoPaused : R.string.CameraMemoStopped);
        String time = formatTime(elapsed);
        String cap = " / " + formatTime(CAP_MS);
        float glyph = AndroidUtilities.dp(12);
        float gap = AndroidUtilities.dp(8);
        float wordWidth = word != null ? textPaint.measureText(word) + gap : 0;
        float total = glyph + gap + wordWidth + textPaint.measureText(time) + dimTextPaint.measureText(cap);
        float x = (getWidth() - total) / 2f;
        float centerY = baseline - textPaint.getTextSize() * 0.35f;
        if (recording) {
            if ((SystemClock.elapsedRealtime() / 500) % 2 == 0) {
                fillPaint.setColor(RED);
                canvas.drawCircle(x + glyph / 2f, centerY, AndroidUtilities.dp(5), fillPaint);
            }
        } else {
            fillPaint.setColor(0xffffffff);
            if (paused) {
                float bar = AndroidUtilities.dp(3.5f);
                canvas.drawRect(x + AndroidUtilities.dp(1), centerY - glyph / 2f, x + AndroidUtilities.dp(1) + bar, centerY + glyph / 2f, fillPaint);
                canvas.drawRect(x + glyph - AndroidUtilities.dp(1) - bar, centerY - glyph / 2f, x + glyph - AndroidUtilities.dp(1), centerY + glyph / 2f, fillPaint);
            } else {
                float half = AndroidUtilities.dp(5);
                canvas.drawRect(x + glyph / 2f - half, centerY - half, x + glyph / 2f + half, centerY + half, fillPaint);
            }
        }
        x += glyph + gap;
        if (word != null) {
            canvas.drawText(word, x, baseline, textPaint);
            x += wordWidth;
        }
        canvas.drawText(time, x, baseline, textPaint);
        x += textPaint.measureText(time);
        canvas.drawText(cap, x, baseline, dimTextPaint);
    }

    /** Discard, send or pause, drawn white on black. Dimmed and inert while it can't act. */
    private static final class ControlButton extends View {

        static final int DISCARD = 0;
        static final int SEND = 1;
        static final int PAUSE = 2;

        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Drawable trash;
        private boolean playing;

        ControlButton(Context context, int type) {
            super(context);
            this.type = type;
            if (type == DISCARD) {
                trash = context.getResources().getDrawable(R.drawable.msg_delete, null).mutate();
                trash.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
            } else {
                trash = null;
            }
        }

        void setPlaying(boolean playing) {
            if (this.playing != playing) {
                this.playing = playing;
                invalidate();
            }
        }

        @Override
        public void setEnabled(boolean enabled) {
            if (enabled != isEnabled()) {
                super.setEnabled(enabled);
                setAlpha(enabled ? 1f : 0.4f);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            paint.setStyle(Paint.Style.FILL);
            if (type == SEND) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(AndroidUtilities.dp(4));
                paint.setColor(0xffffffff);
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(34), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(RED);
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(27), paint);
                return;
            }
            paint.setColor(TONAL);
            canvas.drawCircle(cx, cy, AndroidUtilities.dp(24), paint);
            paint.setColor(0xffffffff);
            if (type == DISCARD) {
                int half = AndroidUtilities.dp(12);
                trash.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
                trash.draw(canvas);
            } else if (playing) {
                float s = AndroidUtilities.dp(8);
                path.reset();
                path.moveTo(cx - s * 0.6f, cy - s);
                path.lineTo(cx + s, cy);
                path.lineTo(cx - s * 0.6f, cy + s);
                path.close();
                canvas.drawPath(path, paint);
            } else {
                float h = AndroidUtilities.dp(8);
                float bar = AndroidUtilities.dp(3.5f);
                float gap = AndroidUtilities.dp(2.5f);
                canvas.drawRect(cx - gap - bar, cy - h, cx - gap, cy + h, paint);
                canvas.drawRect(cx + gap, cy - h, cx + gap + bar, cy + h, paint);
            }
        }
    }
}
