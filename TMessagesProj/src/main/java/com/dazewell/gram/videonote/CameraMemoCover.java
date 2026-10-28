package com.dazewell.gram.videonote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
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
 * #camera-memo: the plain camera screen over a camera memo's chat. Opaque black, with the whole camera frame, uncropped,
 * as a viewfinder (CameraMemoBackdrop) edge to edge under the status bar, the controls below it, and a grid of square
 * cells whose three rows are exactly the crop the round clip sends. While the viewfinder is live it covers the round
 * camera too, so nothing on screen reads as a round video recorder. The moment it isn't (no fresh frame, switched off,
 * paused), a hole opens over the stock circle, so a live picture is always on screen. The one exception is the start:
 * until the camera's first frames reach either view the circle has no live picture to show, so the hole stays shut and
 * the screen comes up as a camera's would, black under its grid. A gesture that starts on the crop
 * is handed to the camera view, mapped onto its circle, so the round video's own gestures work as usual. The recording cue and the controls are drawn here, white on black, with no Telegram colour.
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
    private static final long COMMIT_SETTLE_MS = 1_500;
    private static final long PAUSE_INTENT_MS = 600;
    private static final long TAP_MS = 250; // VideoHoldToSend.ARM_MS: shorter is a tap, which pauses; longer is a hold, which sends
    private static final int VIEWFINDER_MAX_PX = 720; // its buffer's long side, not its size on screen
    private static final int GRID = 0x80ffffff;
    private static final int GRID_CROP = 0xe6ffffff; // the crop's top and bottom, a little stronger than the rest
    private static final long VIEWFINDER_STALE_MS = 500;
    // From the recorder's start, how long the viewfinder gets to show its first frame before the circle stands in for it.
    // Both are fed by the same GL draw, so this only runs out when the viewfinder failed.
    private static final long VIEWFINDER_FIRST_FRAME_MS = 1_000;

    private static final int RED = 0xffff3b30;
    private static final int AMBER = 0xffffb300;
    private static final int TONAL = 0xff2b2e33;
    private static final int ONLINE = 0xff34c759; // a fixed green, not the theme's online colour, which would give the app away

    private final WeakReference<ChatActivity> chatRef;
    private final long attachedAt = SystemClock.elapsedRealtime();

    private final Path holePath = new Path();
    private final RectF hole = new RectF();
    private boolean hasHole;
    private final RectF flatHole = new RectF(); // the last bounds with no flip turn on the circle
    private boolean hasFlatHole;
    private final int[] location = new int[2];
    private final int[] ownLocation = new int[2];

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridCropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blackPaint = new Paint();
    // Stamped by the viewfinder's own TextureView each time a camera frame actually reaches it, on the UI thread
    private long viewfinderFrameAt;
    private boolean viewfinderCovers;
    private boolean holeOpen; // the stock circle shows through: set with viewfinderCovers, once per draw
    private boolean awaitingFirstFrame;
    private final RectF viewfinderRect = new RectF();
    private final RectF cropRect = new RectF(); // the viewfinder's centre square: what the round clip sends
    private final Matrix forwardMatrix = new Matrix();
    private InstantCameraView forwardTarget;
    private boolean forwarding;
    private final TextureView viewfinder;
    private float laidFrameWidth;
    private float laidFrameHeight;

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
    private long firstRecordingAt;
    // Whether the last stop can be resumed. The recorder has no lasting answer (togglePause only refuses while the file is
    // handed off or the encoder is tearing down), so the cause decides: a stop that follows a pause asked for here (the
    // Pause button, a tap on the crop) is a pause. Anything else, a send landing on a preview, the cap, the onPause
    // grace's finalize or a lock, ended the encoder for good.
    private boolean resumableStop;
    private long pauseIntentAt;
    private long forwardDownAt;
    private long elapsed;
    private long lastTick;
    private boolean committed;
    private long committedAt;
    private boolean failed;
    private boolean removed;
    private boolean statusBarRestored;
    private boolean touchThrough;
    private int controlsTop = -1;

    private final Runnable tick = this::tick;
    // The recipient's online state and unread count, top right, as a camera's own indicators. Null where nothing applies.
    private final MemoRecipientStatus recipient;
    private final RectF pillRect = new RectF();
    private final Paint pillTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        // Same traversal as the draw, so the hole never lags the circle's open animation by a frame
        updateHole();
        return true;
    };

    CameraMemoCover(Context context, ChatActivity chat) {
        super(context);
        chatRef = new WeakReference<>(chat);
        setWillNotDraw(false);
        int account = chat.getCurrentAccount();
        long recipientId = chat.getDialogId();
        recipient = MemoRecipientStatus.applies(account, recipientId) ? new MemoRecipientStatus(account, recipientId, this::invalidate) : null;
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
        // The status sits over the live viewfinder, which can be as bright as the text
        textPaint.setShadowLayer(AndroidUtilities.dp(3), 0, 0, 0x99000000);
        dimTextPaint.setShadowLayer(AndroidUtilities.dp(3), 0, 0, 0x99000000);
        pillTextPaint.setColor(0xffffffff);
        pillTextPaint.setTypeface(medium);
        pillTextPaint.setTextSize(sp(13));
        pillTextPaint.setFontFeatureSettings("tnum");
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(AndroidUtilities.dp(1));
        gridPaint.setColor(GRID);
        gridCropPaint.setStyle(Paint.Style.STROKE);
        gridCropPaint.setStrokeWidth(AndroidUtilities.dp(2));
        gridCropPaint.setColor(GRID_CROP);

        // First child, so everything else draws over it; clipped out of the hole while it is not live (dispatchDraw)
        viewfinder = new TextureView(context);
        viewfinder.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                handOver(surface, width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                handOver(surface, width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                CameraMemoBackdrop.clearTarget(surface);
                return false; // the backdrop releases it, once no EGL surface is left on it
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                viewfinderFrameAt = SystemClock.elapsedRealtime();
                if (!viewfinderCovers) {
                    invalidate(); // close the hole over the circle now that the viewfinder shows it
                }
            }
        });
        viewfinder.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(viewfinder);

        discardButton = new ControlButton(context, ControlButton.DISCARD);
        discardButton.setContentDescription(LocaleController.getString(R.string.Discard));
        discardButton.setOnClickListener(v -> {
            ChatActivity c = chatRef.get();
            if (c != null && discardButton.isEnabled() && CameraMemo.discard(c)) {
                committed = true;
                committedAt = SystemClock.elapsedRealtime();
                refresh();
            }
        });
        sendButton = new ControlButton(context, ControlButton.SEND);
        sendButton.setContentDescription(LocaleController.getString(R.string.Send));
        sendButton.setOnClickListener(v -> {
            ChatActivity c = chatRef.get();
            if (c != null && sendButton.isEnabled() && CameraMemo.send(c)) {
                committed = true;
                committedAt = SystemClock.elapsedRealtime();
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

    private static void handOver(SurfaceTexture surface, int width, int height) {
        float scale = Math.min(1f, VIEWFINDER_MAX_PX / (float) Math.max(1, Math.max(width, height)));
        int bufferWidth = Math.max(1, Math.round(width * scale));
        int bufferHeight = Math.max(1, Math.round(height * scale));
        surface.setDefaultBufferSize(bufferWidth, bufferHeight);
        CameraMemoBackdrop.setTarget(surface, bufferWidth, bufferHeight);
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
        if (recipient != null) {
            recipient.stop();
        }
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
        if (recipient != null && !removed) {
            recipient.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        AndroidUtilities.cancelRunOnUIThread(tick);
        if (recipient != null) {
            recipient.stop();
        }
        restoreStatusBar();
        CameraMemo.onCoverDetached(this);
    }

    // A gesture that starts on what is sent is the round video's, so its tap, hold and swipes behave exactly as in a chat.
    // While the viewfinder covers the circle that is the crop, and the gesture is handed to the camera view mapped from
    // the crop onto the circle; while the real circle shows, declining the DOWN lets the whole gesture fall through to it.
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchThrough = false;
            forwarding = false;
            if (!failed && hasHole) {
                if (viewfinderCovers && hasFlatHole && cropRect.contains(event.getX(), event.getY())) {
                    startForwarding();
                    forwardDownAt = event.getEventTime();
                } else if (holeOpen) {
                    touchThrough = isInHole(event.getX(), event.getY());
                }
            }
        }
        if (touchThrough) {
            return false;
        }
        if (forwarding) {
            MotionEvent mapped = MotionEvent.obtain(event);
            mapped.transform(forwardMatrix);
            forwardTarget.dispatchTouchEvent(mapped);
            mapped.recycle();
            if (action == MotionEvent.ACTION_UP && event.getEventTime() - forwardDownAt < TAP_MS) {
                pauseIntentAt = SystemClock.elapsedRealtime(); // a tap on the crop pauses or resumes, as on the circle
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                forwarding = false;
                forwardTarget = null;
            }
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    // Crop to circle, in this view's coordinates, then into the camera view's: a sibling under the same parent, which
    // this view fills from its origin
    private void startForwarding() {
        ChatActivity chat = chatRef.get();
        InstantCameraView camera = chat != null ? chat.instantCameraView : null;
        if (camera == null || cropRect.isEmpty()) {
            return;
        }
        forwardMatrix.setRectToRect(cropRect, flatHole, Matrix.ScaleToFit.FILL);
        forwardMatrix.postTranslate(-camera.getX(), -camera.getY());
        forwardTarget = camera;
        forwarding = true;
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
        refresh(); // refresh invalidates, so a viewfinder gone stale opens the hole on this tick
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
            if (!everRecorded) {
                firstRecordingAt = now;
            }
            everRecorded = true;
            failed = false; // the recorder overrules the cover: never a failure message over a live recording
            resumableStop = false;
        } else if (wasRecording) {
            resumableStop = now - pauseIntentAt < PAUSE_INTENT_MS;
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
            pauseIntentAt = SystemClock.elapsedRealtime();
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
        // A send can land on a preview instead (confirm before sending, a declined paid-message sheet). If one is still
        // waiting a moment later and nothing was handed off, the controls come back for it.
        if (committed && bound && SystemClock.elapsedRealtime() - committedAt > COMMIT_SETTLE_MS) {
            ChatActivity chat = chatRef.get();
            InstantCameraView camera = chat != null ? chat.instantCameraView : null;
            if (camera == null || !camera.isFileHandedOff()) {
                committed = false;
            }
        }
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
        if (CameraMemoBackdrop.frameWidth != laidFrameWidth || CameraMemoBackdrop.frameHeight != laidFrameHeight) {
            requestLayout(); // the first camera frame says what shape the viewfinder is
        }
        invalidate();
    }

    // A clip waiting in the preview, short of the cap, with the camera off, that the recorder can pick up again: paused
    // by the Pause button or a tap on the crop. Any other stop reads Stopped (resumableStop).
    private boolean isPaused() {
        return !recording && bound && resumableStop && elapsed < PAUSE_UNTIL_MS;
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
        float rotation = valid ? container.getRotationY() % 360f : 0f;
        if (valid && rotation != 0f && hasFlatHole) {
            // The flip turns the circle about its vertical axis without moving it. Its location in window maps through
            // that rotation, so narrow the last flat oval instead, or the chat would show at its sides mid-turn. Edge-on
            // it is a valid zero-width hole, not a missing one.
            float half = flatHole.width() / 2f * Math.abs((float) Math.cos(Math.toRadians(rotation)));
            left = flatHole.centerX() - half;
            right = flatHole.centerX() + half;
            top = flatHole.top;
            bottom = flatHole.bottom;
        } else if (valid) {
            container.getLocationInWindow(location);
            getLocationInWindow(ownLocation);
            float scaleX = container.getScaleX() * camera.getScaleX();
            float scaleY = container.getScaleY() * camera.getScaleY();
            left = location[0] - ownLocation[0];
            top = location[1] - ownLocation[1];
            right = left + container.getWidth() * scaleX;
            bottom = top + container.getHeight() * scaleY;
            valid = right > left && bottom > top;
            if (valid && rotation == 0f) {
                flatHole.set(left, top, right, bottom);
                hasFlatHole = true;
            }
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
            requestLayout(); // without a viewfinder the controls follow the circle
            invalidate();
        }
    }

    // Centred in the space under the viewfinder; where there isn't room for the row, at the bottom over its edge.
    // Without a viewfinder, below the circle where there's room. Never past the navigation bar.
    private int computeControlsTop(boolean withViewfinder) {
        int height = getHeight();
        int rowHeight = AndroidUtilities.dp(72);
        int lowest = height - AndroidUtilities.navigationBarHeight - AndroidUtilities.dp(16) - rowHeight;
        int top;
        if (withViewfinder) {
            top = Math.round((viewfinderRect.bottom + height - AndroidUtilities.navigationBarHeight - rowHeight) / 2f);
        } else {
            top = lowest - AndroidUtilities.dp(40);
            if (hasHole && top < hole.bottom + AndroidUtilities.dp(24)) {
                top = (int) hole.bottom + AndroidUtilities.dp(24);
            }
        }
        return Math.max(0, Math.min(top, lowest));
    }

    // The full frame edge to edge, from under the status bar, raised only as far as the control row needs below it. The
    // crop is its centre square, the frame's shorter side. Until the first camera frame says otherwise, the shape
    // CameraMemo.roundCameraWidth asks for.
    private boolean computeViewfinder(int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        laidFrameWidth = CameraMemoBackdrop.frameWidth;
        laidFrameHeight = CameraMemoBackdrop.frameHeight;
        float frameWidth = laidFrameWidth;
        float frameHeight = laidFrameHeight;
        if (!(frameWidth >= 1f && frameWidth <= 4f && frameHeight >= 1f && frameHeight <= 4f)) {
            frameWidth = 1f;
            frameHeight = 4f / 3f;
        }
        float viewfinderWidth = width;
        float viewfinderHeight = width * frameHeight / frameWidth;
        float roomBottom = height - AndroidUtilities.navigationBarHeight - AndroidUtilities.dp(72 + 2 * 12);
        if (viewfinderHeight > roomBottom && roomBottom > 0) {
            // Too short for it even from the top (split screen, a tablet): narrower, so the whole frame still shows
            viewfinderHeight = roomBottom;
            viewfinderWidth = viewfinderHeight * frameWidth / frameHeight;
        }
        float top = AndroidUtilities.statusBarHeight;
        if (top + viewfinderHeight > roomBottom) {
            top = Math.max(0, roomBottom - viewfinderHeight);
        }
        float left = (width - viewfinderWidth) / 2f;
        viewfinderRect.set(left, top, left + viewfinderWidth, top + viewfinderHeight);
        float side = Math.min(viewfinderRect.width(), viewfinderRect.height());
        cropRect.set(viewfinderRect.centerX() - side / 2f, viewfinderRect.centerY() - side / 2f,
                viewfinderRect.centerX() + side / 2f, viewfinderRect.centerY() + side / 2f);
        return true;
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
        View parent = (View) getParent();
        boolean shown = computeViewfinder(width, parent != null && parent.getHeight() > 0 ? parent.getHeight() : height);
        viewfinder.measure(MeasureSpec.makeMeasureSpec(shown ? Math.round(viewfinderRect.width()) : 0, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(shown ? Math.round(viewfinderRect.height()) : 0, MeasureSpec.EXACTLY));
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
        boolean shown = computeViewfinder(width, height);
        if (shown) {
            int vw = Math.round(viewfinderRect.width());
            int vh = Math.round(viewfinderRect.height());
            if (viewfinder.getMeasuredWidth() != vw || viewfinder.getMeasuredHeight() != vh) {
                viewfinder.measure(MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY));
            }
            int vl = Math.round(viewfinderRect.left);
            int vt = Math.round(viewfinderRect.top);
            viewfinder.layout(vl, vt, vl + vw, vt + vh);
        } else {
            viewfinder.layout(0, 0, 0, 0);
            cropRect.setEmpty();
        }
        controlsTop = computeControlsTop(shown);
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

    // The viewfinder may stand in for the circle only while it is demonstrably showing the camera: a frame reached its
    // TextureView recently. A stalled, switched-off or paused viewfinder opens the hole again within VIEWFINDER_STALE_MS.
    private boolean isViewfinderLive() {
        return hasFlatHole && viewfinder.getWidth() > 0 && viewfinder.isAvailable()
                && SystemClock.elapsedRealtime() - viewfinderFrameAt < VIEWFINDER_STALE_MS;
    }

    // No viewfinder frame yet, and the recorder either hasn't started or started only just now. Before its first frames the
    // circle shows a placeholder, not the camera, so there is no live picture to open the hole for.
    // Bounded by START_TIMEOUT_MS even before recording, since a locked memo never sets failed here.
    private boolean isAwaitingFirstFrame() {
        long now = SystemClock.elapsedRealtime();
        return viewfinderFrameAt == 0 && !failed && (everRecorded
                ? now - firstRecordingAt < VIEWFINDER_FIRST_FRAME_MS
                : now - attachedAt < START_TIMEOUT_MS);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        viewfinderCovers = isViewfinderLive();
        awaitingFirstFrame = isAwaitingFirstFrame();
        holeOpen = hasHole && !viewfinderCovers && !awaitingFirstFrame;
        canvas.save();
        if (holeOpen) {
            canvas.clipOutPath(holePath);
        }
        canvas.drawColor(0xff000000);
        canvas.restore();
    }

    // Children, the viewfinder first. Unless the viewfinder covers the circle, none of them draws into the hole: that is
    // the real circle's. The grid and the status go last, over everything: thin lines, like any camera's grid.
    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.save();
        if (holeOpen) {
            canvas.clipOutPath(holePath);
        }
        super.dispatchDraw(canvas);
        canvas.restore();
        if (!failed && !holeOpen && (viewfinderCovers || awaitingFirstFrame) && !cropRect.isEmpty()) {
            drawGrid(canvas); // never with the real circle showing: it would mark the wrong place
        }
        if (!failed) {
            drawStatus(canvas);
            drawRecipient(canvas);
        }
    }

    // A viewfinder that isn't live would leave its last frame frozen around the live circle, so it is painted over. Still
    // drawn underneath, never hidden: a TextureView that isn't drawn gets no new frame, and couldn't come back.
    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        boolean more = super.drawChild(canvas, child, drawingTime);
        if (child == viewfinder && !viewfinderCovers) {
            canvas.drawRect(child.getLeft(), child.getTop(), child.getRight(), child.getBottom(), blackPaint);
        }
        return more;
    }

    // Square cells a third of the crop wide. The crop spans the viewfinder's width, so its three columns are the frame's,
    // and its three rows sit between the two stronger lines. Outside the crop only the two columns carry on.
    private void drawGrid(Canvas canvas) {
        float cell = cropRect.width() / 3f;
        if (cell < AndroidUtilities.dp(4)) {
            return;
        }
        float left = viewfinder.getLeft();
        float right = viewfinder.getRight();
        float top = viewfinder.getTop();
        float bottom = viewfinder.getBottom();
        float cropTop = cropRect.top;
        float cropBottom = cropRect.bottom;
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        for (int i = 1; i < 3; i++) {
            float x = cropRect.left + cell * i;
            canvas.drawLine(x, top, x, bottom, gridPaint);
        }
        for (float y = cropTop + cell; y < cropBottom - 1f; y += cell) {
            canvas.drawLine(left, y, right, y, gridPaint);
        }
        canvas.drawLine(left, cropTop, right, cropTop, gridCropPaint);
        canvas.drawLine(left, cropBottom, right, cropBottom, gridCropPaint);
        canvas.restore();
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

    private float statusBaseline() {
        float baseline = Math.max(AndroidUtilities.statusBarHeight + AndroidUtilities.dp(44), AndroidUtilities.dp(56));
        if (viewfinder.getWidth() > 0) {
            baseline = Math.max(viewfinder.getTop(), AndroidUtilities.statusBarHeight) + AndroidUtilities.dp(36);
        }
        if (holeOpen && baseline > hole.top - AndroidUtilities.dp(16)) { // never over the real circle
            baseline = Math.max(AndroidUtilities.statusBarHeight + AndroidUtilities.dp(24), hole.top - AndroidUtilities.dp(16));
        }
        return baseline;
    }

    // Top right, on the clock's line: a green dot while the recipient is online and a small count of their unread
    // messages, both dressed as a camera's mode indicators, with no word that names either. Nothing at all when neither
    // holds, as on any camera. Half strength while capturing, so the red dot stays the one thing that stands out.
    private void drawRecipient(Canvas canvas) {
        if (recipient == null) {
            return;
        }
        boolean showCount = recipient.unread > 0 || recipient.unreadMark;
        if (!recipient.online && !showCount) {
            return;
        }
        int alpha = recording ? 0x80 : 0xff;
        float centerY = statusBaseline() - textPaint.getTextSize() * 0.35f;
        float x = getWidth() - AndroidUtilities.dp(16);
        if (showCount) {
            float height = AndroidUtilities.dp(22);
            float width = height;
            String count = null;
            if (recipient.unread > 0) {
                count = recipient.unread > 99 ? "99+" : String.valueOf(recipient.unread);
                width = Math.max(height, pillTextPaint.measureText(count) + AndroidUtilities.dp(14));
            }
            pillRect.set(x - width, centerY - height / 2f, x, centerY + height / 2f);
            fillPaint.setColor(TONAL);
            fillPaint.setAlpha(alpha * 0xe6 / 0xff);
            canvas.drawRoundRect(pillRect, height / 2f, height / 2f, fillPaint);
            int ink = recipient.muted ? 0x99 : 0xff;
            if (count != null) {
                pillTextPaint.setAlpha(alpha * ink / 0xff);
                Paint.FontMetrics metrics = pillTextPaint.getFontMetrics();
                canvas.drawText(count, pillRect.centerX() - pillTextPaint.measureText(count) / 2f, centerY - (metrics.ascent + metrics.descent) / 2f, pillTextPaint);
            } else {
                fillPaint.setColor(0xffffffff);
                fillPaint.setAlpha(alpha * ink / 0xff); // marked unread with nothing counted: a bare dot, as the chat list shows
                canvas.drawCircle(pillRect.centerX(), centerY, AndroidUtilities.dp(3.5f), fillPaint);
            }
            x = pillRect.left - AndroidUtilities.dp(8);
        }
        if (recipient.online) {
            float radius = AndroidUtilities.dp(5);
            fillPaint.setColor(ONLINE);
            fillPaint.setAlpha(alpha);
            canvas.drawCircle(x - radius, centerY, radius, fillPaint);
        }
    }

    // Top centre: a blinking red dot and the clock while capturing, a pause or stop glyph and its word otherwise
    private void drawStatus(Canvas canvas) {
        float baseline = statusBaseline();
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
