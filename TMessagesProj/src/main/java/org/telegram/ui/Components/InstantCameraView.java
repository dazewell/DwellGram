/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.ColorUtils;

import androidx.media3.exoplayer.ExoPlayer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AutoDeleteMediaTask;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.camera.Camera2Session;
import org.telegram.messenger.camera.CameraController;
import org.telegram.messenger.camera.CameraInfo;
import org.telegram.messenger.camera.CameraSession;
import org.telegram.messenger.camera.Size;
import org.telegram.messenger.video.MP4Builder;
import org.telegram.messenger.video.Mp4Movie;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.voip.CellFlickerDrawable;
import org.telegram.ui.Stories.recorder.DualCameraView;
import org.telegram.ui.Stories.recorder.FlashViews;
import org.telegram.ui.Stories.recorder.StoryEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

import tw.nekomimi.nekogram.NekoConfig;
import com.dazewell.gram.ui.InstantZoomControlView;
import xyz.nextalone.nagram.NaConfig;
import com.dazewell.gram.helpers.RecordingLimitVibration;

@SuppressLint("ViewConstructor")
public class InstantCameraView extends InstantCameraViewBase implements NotificationCenter.NotificationCenterDelegate {

    public boolean WRITE_TO_FILE_IN_BACKGROUND;

    // NagramX: infinite video message toggle states
    public static final int INFINITE_RECORDING_UNAVAILABLE = -1;
    public static final int INFINITE_RECORDING_OFF = 0;
    public static final int INFINITE_RECORDING_ON = 1;

    private int currentAccount = UserConfig.selectedAccount;
    private InstantViewCameraContainer cameraContainer;
    private Delegate delegate;
    private Paint paint;
    private RectF rect;
    private final FlashViews.ImageViewInvertable flashButton;
    private final ImageView infiniteButton; // NagramX
    private final FlashViews flashViews;
    private RLottieDrawable flashOnDrawable, flashOffDrawable;
    private ImageView muteImageView;
    private float progress;
    private CameraInfo selectedCamera;
    private boolean isFrontface = true;
    private boolean initialCameraFront = true;
    private volatile boolean cameraReady;
    private AnimatorSet muteAnimation;
    private TLRPC.InputFile file;
    private TLRPC.InputEncryptedFile encryptedFile;
    private byte[] key;
    private byte[] iv;
    private long size;
    private boolean isSecretChat;
    @Nullable
    private VideoEditedInfo videoEditedInfo;
    private VideoPlayer videoPlayer;
    // NagramX (#video-draft-guard): set when the fragment is torn down (or rebuilt by a passcode lock) while a
    // finished draft still sits in the preview. The back-guard skips cancel() to avoid deleting that clip, so the
    // looping preview player and its ~60 Hz progress timer would otherwise outlive the view and pile up. This flag
    // also guards setupVideoPlayer against a runnable already queued on the UI thread landing after abandonment.
    // Non-volatile on purpose: every reader and writer runs on the UI thread, so the only hazard is a same-thread
    // queued post, not a cross-thread data race -- do not add volatile or synchronization.
    private boolean previewAbandoned;
    private Bitmap lastBitmap;
    private int recordingGuid;

    private volatile boolean cameraTextureAvailable;
    private final int[] position = new int[2];
    private final int[] cameraTexture = new int[] { Integer.MIN_VALUE, Integer.MIN_VALUE };
    private final int[] oldCameraTexture = new int[1];
    private float cameraTextureAlpha = 1.0f;

    private AnimatorSet animatorSet;

    private boolean deviceHasGoodCamera;
    private boolean requestingPermissions;
    private File cameraFile;
    private File previewFile;
    private long recordStartTime;
    private long recordPlusTime;
    private boolean recording;
    // NagramX (#video-draft-guard): true when a restored round-video draft's file has been adopted into this
    // instance (see adoptRestoredDraft). A rebuilt instance restoring a draft never opened its camera, so
    // textureView is null and send()'s textureView==null guard would drop the send -- this flag lets the
    // state==4 send through for exactly that adopted case. A fresh recording clears it (showCamera).
    private boolean sendAdoptedDraft;
    // NagramX (#round-video-restart-guard-fix): cameraFile was handed to a send, so this instance no longer owns
    // it. Until the next fresh open or adoption, nothing here may send, pause, re-record or delete it; the camera
    // still closes. UI thread only.
    private boolean cameraFileHandedOff;
    // NagramX (#round-video-restart-guard-fix): a terminal stop was queued and its teardown post hasn't run. The
    // recorder is reused by the next open, and its pending UI posts read videoFile, file, size and recordedTime
    // live, so no new recording may start until it is done. UI thread only.
    private boolean encoderTeardownPending;
    // NagramX (#video-hold-send): hold the circle to send a locked recording, or its paused preview through the
    // composer's send button. Never a handed-off file: the preview's send wind-down dismisses it even when send()
    // refuses. A recording also needs the teardown flag, since that wind-down doesn't guard against a stopped camera
    private final com.dazewell.gram.videonote.VideoHoldToSend holdToSend = new com.dazewell.gram.videonote.VideoHoldToSend(
            () -> !cameraFileHandedOff && delegate != null && (recording ? !encoderTeardownPending && delegate.isRecordLocked() : videoPlayer != null && delegate.canSendVideoPreview()),
            () -> {
                if (recording) {
                    delegate.sendLockedRecording();
                } else {
                    delegate.sendVideoPreview();
                }
            },
            () -> videoPlayer,
            () -> delegate != null && delegate.hasVideoPreviewSend(),
            // a tap pauses or resumes through the composer's pause button, so it needs the host's send path too
            () -> !cameraFileHandedOff && delegate != null && (recording ? !encoderTeardownPending && delegate.isRecordLocked() : videoPlayer != null && delegate.hasVideoPreviewSend()),
            () -> delegate.toggleRecordingPause(),
            // swipes steer a live recording only, locked or not, never the paused preview
            () -> recording && !encoderTeardownPending && !cameraFileHandedOff,
            this::onHoldSwipe);
    private long recordedTime;
    private boolean cancelled;

    // NagramX: true for the last ~5s before any round video recording hits its limit (rollover or plain
    // stop), so onDraw can pulse the arc. All writes come from ChatActivityEnterView.TimerView (armed) or
    // the stop/rollover paths (cleared), and they all run on the UI thread, same as the onDraw read -- no
    // volatile needed.
    private boolean limitWarningActive;
    private Paint limitWarningPaint;
    // NagramX: armed right before the ordinary 60s auto-stop dispatch. state==3 alone doesn't mean "hit
    // the cap" -- NekoConfig.confirmAVMessage reuses it for a manual send that wants a preview too -- so
    // this is a direct signal instead. Read and cleared once in shutdown() below.
    private boolean limitStopHapticArmed;

    private CameraGLThread cameraThread;
    private Size[] previewSize = new Size[2];
    private Size pictureSize;
    private Size aspectRatio = SharedConfig.roundCamera16to9 ? new Size(16, 9) : new Size(4, 3);
    private TextureView textureView;
    private BackupImageView textureOverlayView;
    // NagramX: the N-Settings switch opts video messages into Camera2 unless the debug menu forces a choice
    private final boolean useCamera2 = SharedConfig.isUsingCamera2(currentAccount) || SharedConfig.useCamera2Force == null && NaConfig.INSTANCE.getVideoMessagesCamera2().Bool();
    private CameraSession cameraSession;
    private volatile boolean bothCameras; // NagramX (#round-dual-camera-fix): volatile, since dual fallback clears it mid-recording while the GL and encoder threads read it
    private Camera2Session[] camera2Sessions = new Camera2Session[2];
    private Camera2Session camera2SessionCurrent;
    private boolean needDrawFlickerStub;

    private boolean isCameraSessionInitiated() {
        if (useCamera2) {
            return camera2SessionCurrent != null && camera2SessionCurrent.isInitiated();
        } else {
            return cameraSession != null && cameraSession.isInitied();
        }
    }

    private float panTranslationY;
    private float animationTranslationY;

    private final float[] mMVPMatrix = new float[16];
    private final float[] mSTMatrix = new float[16];
    private final float[] moldSTMatrix = new float[16];
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
                    "uniform mat4 uSTMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "void main() {\n" +
                    "   gl_Position = uMVPMatrix * aPosition;\n" +
                    "   vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                    "}\n";

    private static final String FRAGMENT_SCREEN_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision lowp float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "   gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                    "}\n";

    private FloatBuffer vertexBuffer;
    private FloatBuffer textureBuffer;
    private FloatBuffer oldTextureTextureBuffer;
    private float scaleX;
    private float scaleY;

    private Size oldTexturePreviewSize;

    private boolean flipAnimationInProgress;

    private View parentView;
    public boolean opened;

    float pinchStartDistance;
    float pinchStartZoom;

    float pinchScale;

    boolean isInPinchToZoomTouchMode;
    boolean maybePinchToZoomTouchMode;

    private int pointerId1, pointerId2;
    private int textureViewSize;
    private boolean isMessageTransition;
    private boolean updateTextureViewSize;
    private final Theme.ResourcesProvider resourcesProvider;
    private final boolean isNewDesign;

    private final static int audioSampleRate = 48000;

    private static final int[] ALLOW_BIG_CAMERA_WHITELIST = {
            285904780, // XIAOMI (Redmi Note 7)
            -1394191079 // samsung a31
    };
    private boolean allowSendingWhileRecording;

    private final LinearLayout buttonsLayout;
    private final int buttonsSizePx;
    private final InstantZoomControlView zoomControlView;
    private float lockedZoom;
    private final TextView zoomLabel;
    private int zoomGlideDirection;
    private boolean zoomGliding;
    private boolean zoomLabelVisible;
    private float zoomFollowCurrent = -1f;
    private float zoomFollowTarget;
    private boolean zoomFollowing;
    private long zoomFollowLastTick;
    private float appliedZoomRatio = -1f;
    private final Runnable zoomGlideStarter = this::startZoomGlide;
    private final Runnable zoomLabelHider = this::hideZoomLabel;
    private final Runnable zoomFollowerTick = this::stepZoomFollower;


    @SuppressLint("ClickableViewAccessibility")
    public InstantCameraView(Context context, Delegate delegate, Theme.ResourcesProvider resourcesProvider, boolean isNewDesign) {
        super(context);
        buttonsSizePx = dp(isNewDesign ? 24 : 28);

        WRITE_TO_FILE_IN_BACKGROUND = false;//SharedConfig.deviceIsAboveAverage();
        this.resourcesProvider = resourcesProvider;
        this.isNewDesign = isNewDesign;
        parentView = delegate.getFragmentView();
        setWillNotDraw(false);

        this.delegate = delegate;
        recordingGuid = delegate.getClassGuid();
        isSecretChat = delegate.isSecretChat();
        paint = new Paint(Paint.ANTI_ALIAS_FLAG) {
            @Override
            public void setAlpha(int a) {
                super.setAlpha(a);
                invalidate();
            }
        };
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(3));
        paint.setColor(0xffffffff);

        rect = new RectF();

        flashViews = new FlashViews(getContext(), null, this, null);
        flashViews.setWarmth(.5f);
        addView(flashViews.backgroundView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        cameraContainer = new InstantViewCameraContainer(context) {
            @Override
            public void setRotationY(float rotationY) {
                super.setRotationY(rotationY);
                InstantCameraView.this.invalidate();
            }

            @Override
            public void setAlpha(float alpha) {
                super.setAlpha(alpha);
                InstantCameraView.this.invalidate();
            }
        };
        cameraContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, textureViewSize, textureViewSize);
            }
        });
        cameraContainer.setClipToOutline(true);
        cameraContainer.setWillNotDraw(false);

        addView(cameraContainer, new LayoutParams(AndroidUtilities.roundPlayingMessageSize, AndroidUtilities.roundPlayingMessageSize, Gravity.CENTER));
        addView(flashViews.foregroundView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        zoomControlView = new InstantZoomControlView(context, Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider), glyphColor(isDarkAppearance()));
        zoomControlView.setAlpha(0.0f);
        zoomControlView.setContentDescription(LocaleController.getString(R.string.AccDescrZoomControl));
        zoomControlView.setDelegate(new InstantZoomControlView.Delegate() {
            @Override
            public void didSetZoom(float zoom) {
                setLockedZoom(zoom);
            }

            @Override
            public void onButtonDown(int direction) {
                zoomGlideDirection = direction;
                AndroidUtilities.cancelRunOnUIThread(zoomGlideStarter);
                AndroidUtilities.runOnUIThread(zoomGlideStarter, 250);
            }

            @Override
            public void onButtonUp(int direction, boolean cancelled) {
                AndroidUtilities.cancelRunOnUIThread(zoomGlideStarter);
                if (zoomGliding) {
                    stopZoomGlide();
                } else if (!cancelled) {
                    zoomStep(direction);
                }
            }

            @Override
            public void onSwitchCamera() {
                flipCamera();
            }
        });
        addView(zoomControlView, new LayoutParams(LayoutHelper.MATCH_PARENT, dp(104), Gravity.CENTER));

        zoomLabel = new TextView(context);
        zoomLabel.setTextColor(0xffffffff);
        zoomLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        zoomLabel.setTypeface(AndroidUtilities.bold());
        zoomLabel.setBackground(Theme.createRoundRectDrawable(dp(12), 0x4d000000));
        zoomLabel.setPadding(dp(8), dp(2), dp(8), dp(2));
        zoomLabel.setAlpha(0.0f);
        addView(zoomLabel, new LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));


        buttonsLayout = new LinearLayout(context);
        buttonsLayout.setPadding(dp(6), dp(6), dp(6), dp(6));

        buttonsLayout.setOrientation(LinearLayout.HORIZONTAL);
        addView(buttonsLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 56, Gravity.LEFT | Gravity.BOTTOM, 1, 0, 0, 0));

        // the camera flip now lives in the zoom control's button row, so this row only carries flash
        flashButton = new FlashViews.ImageViewInvertable(context);
        flashButton.setScaleType(ImageView.ScaleType.CENTER);
        buttonsLayout.addView(flashButton, LayoutHelper.createLinear(44, 44));
        flashButton.setOnClickListener(v -> {
            flashing = !flashing;
            updateFlash();
        });
        updateFlash();

        if (!isNewDesign) {
            flashViews.add(flashButton);
        } else {
            applyGlyphColor(flashButton, glyphColor(isDarkAppearance()));
        }

        // NagramX: infinite video message: the same toggle the camera-choice popup offers, but reachable
        // once the recording is already running (and in the fixed front/rear camera modes, where that
        // popup never appears). In practice you reach it after locking the recording: while your finger is
        // still down the gesture belongs to the record button, same as the flash button next to it.
        infiniteButton = new ImageView(context);
        infiniteButton.setScaleType(ImageView.ScaleType.CENTER);
        infiniteButton.setImageResource(R.drawable.nax_infinite);
        buttonsLayout.addView(infiniteButton, LayoutHelper.createLinear(44, 44));
        infiniteButton.setOnClickListener(v -> {
            // the button row only fades out for the preview, it stays clickable, so ignore taps that land
            // there once the recording has already stopped
            if (delegate != null && recording) {
                applyInfiniteRecordingState(delegate.toggleInfiniteRecording());
            }
        });
        updateInfiniteButton();

        muteImageView = new ImageView(context);
        muteImageView.setScaleType(ImageView.ScaleType.CENTER);
        muteImageView.setImageResource(R.drawable.video_mute);
        muteImageView.setAlpha(0.0f);
        addView(muteImageView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        Paint blackoutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        blackoutPaint.setColor(ColorUtils.setAlphaComponent(Color.BLACK, 40));
        textureOverlayView = new BackupImageView(getContext()) {

            CellFlickerDrawable flickerDrawable = new CellFlickerDrawable();

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (needDrawFlickerStub) {
                    flickerDrawable.setParentWidth(textureViewSize);
                    AndroidUtilities.rectTmp.set(0, 0, textureViewSize, textureViewSize);
                    float rad = AndroidUtilities.rectTmp.width() / 2f;
                    canvas.drawRoundRect(AndroidUtilities.rectTmp, rad, rad, blackoutPaint);
                    AndroidUtilities.rectTmp.inset(dp(1), dp(1));
                    flickerDrawable.draw(canvas, AndroidUtilities.rectTmp, rad, null);
                    invalidate();
                }
            }
        };
        addView(textureOverlayView, new LayoutParams(AndroidUtilities.roundPlayingMessageSize, AndroidUtilities.roundPlayingMessageSize, Gravity.CENTER));

        setVisibilityFromPause = false;
        setVisibility(INVISIBLE);
    }

    public void setButtonsBackground(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        BlurredBackgroundDrawable drawable = factory.create(buttonsLayout, colorProvider);
        drawable.setPadding(dp(6));
        drawable.setRadius(dp(21));
        buttonsLayout.setBackground(drawable);
        zoomControlView.setButtonsBackground(factory, colorProvider);
    }

    private Boolean wasFlashing;
    private boolean flashing;
    private boolean frontFlashing;
    private void updateFlash() {
        final boolean shouldFrontFlash = flashing && recording && isFrontface;
        if (frontFlashing != shouldFrontFlash) {
            if (frontFlashing = shouldFrontFlash) {
                flashViews.flashIn(null);
            } else {
                flashViews.flashOut();
            }
        }

        if (useCamera2) {
            // NagramX: pick the rear session by facing, not slot. A dual pair slots by the starting camera, a single
            // session by its own facing, and a pair whose second camera failed to open keeps the dual slot, so any fixed
            // index sends the torch to an empty slot when recording starts on the rear camera
            for (Camera2Session session : camera2Sessions) {
                if (session != null) {
                    session.setFlash(flashing && !isFrontface && recording && !session.isFront());
                }
            }
        } else {
            if (cameraSession != null) {
//                final String mode = (
//                    (flashing && !isFrontface && recording) ?
//                        (cameraSession.availableFlashModes != null && cameraSession.availableFlashModes.contains(Camera.Parameters.FLASH_MODE_TORCH) ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_ON) :
//                        Camera.Parameters.FLASH_MODE_OFF
//                );
//                cameraSession.setCurrentFlashMode(mode);
                cameraSession.setTorchEnabled(flashing && !isFrontface && recording);
            }
        }

        if (flashButton != null && (wasFlashing == null || wasFlashing != flashing)) {
            flashButton.setContentDescription(LocaleController.getString(flashing ? R.string.AccDescrCameraFlashOff : R.string.AccDescrCameraFlashOn));
            if (!flashing) {
                if (flashOnDrawable == null) {
                    flashOnDrawable = new RLottieDrawable(R.raw.roundcamera_flash_on, buttonsSizePx, buttonsSizePx);
                    flashOnDrawable.setCallback(flashButton);
                }
                flashButton.setImageDrawable(flashOnDrawable);
                if (wasFlashing == null) {
                    flashOnDrawable.setCurrentFrame(flashOnDrawable.getFramesCount() - 1);
                } else {
                    flashOnDrawable.setCurrentFrame(0);
                    flashOnDrawable.start();
                }
            } else {
                if (flashOffDrawable == null) {
                    flashOffDrawable = new RLottieDrawable(R.raw.roundcamera_flash_off, buttonsSizePx, buttonsSizePx);
                    flashOffDrawable.setCallback(flashButton);
                }
                flashButton.setImageDrawable(flashOffDrawable);
                if (wasFlashing == null) {
                    flashOffDrawable.setCurrentFrame(flashOffDrawable.getFramesCount() - 1);
                } else {
                    flashOffDrawable.setCurrentFrame(0);
                    flashOffDrawable.start();
                }
            }
            wasFlashing = flashing;
        }
    }

    private int internalPaddingBottom;
    private int keyboardFreePaddingBottom;

    // NagramX: the preview size is picked once, at the first measure after the camera opens, so a soft
    // keyboard still sliding out at that moment used to shrink it for the whole recording. Recording
    // always ends with the keyboard gone, so size against the keyboard-free padding while the layout
    // itself keeps following the animated one.
    @Override
    public void setInternalPadding(int padding) {
        setInternalPadding(padding, padding);
    }

    public void setInternalPadding(int padding, int keyboardFreePadding) {
        internalPaddingBottom = padding;
        keyboardFreePaddingBottom = keyboardFreePadding;
        setPadding(0, 0, 0, padding);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (updateTextureViewSize) {
            int newSize;
            if ((MeasureSpec.getSize(heightMeasureSpec) - keyboardFreePaddingBottom) > MeasureSpec.getSize(widthMeasureSpec) * 1.3f) {
                newSize = AndroidUtilities.roundPlayingMessageSize;
            } else {
                newSize = AndroidUtilities.roundMessageSize;
            }
            if (newSize != textureViewSize) {
                textureViewSize = newSize;
                textureOverlayView.getLayoutParams().width = textureOverlayView.getLayoutParams().height = textureViewSize;
                cameraContainer.getLayoutParams().width = cameraContainer.getLayoutParams().height = textureViewSize;
                ((LayoutParams) muteImageView.getLayoutParams()).topMargin = textureViewSize / 2 - dp(24);
                textureOverlayView.setRoundRadius(textureViewSize / 2);
                cameraContainer.invalidateOutline();
                updateTranslationY();
            }
            updateTextureViewSize = false;
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        final int flashWidthSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY);
        final int flashHeightSpec = MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY);
        flashViews.backgroundView.measure(flashWidthSpec, flashHeightSpec);
        flashViews.foregroundView.measure(flashWidthSpec, flashHeightSpec);
    }

    private boolean checkPointerIds(MotionEvent ev) {
        if (ev.getPointerCount() < 2) {
            return false;
        }
        if (pointerId1 == ev.getPointerId(0) && pointerId2 == ev.getPointerId(1)) {
            return true;
        }
        if (pointerId1 == ev.getPointerId(1) && pointerId2 == ev.getPointerId(0)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        getParent().requestDisallowInterceptTouchEvent(true);
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (getVisibility() != VISIBLE) {
            animationTranslationY = getMeasuredHeight() / 2f;
            updateTranslationY();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileUploaded);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelZoomInteractions();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileUploaded);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        if (flashViews != null) {
            flashViews.flashOut();
        }
    }

    // NagramX: the recorder is built once per chat and reused, so a live theme flip (e.g. battery-saver
    // dark mode) has to be pushed into the controls that snapshot their colors, or they keep the old ones
    private void updateThemeColors() {
        final boolean dark = isDarkAppearance();
        if (zoomControlView != null) {
            zoomControlView.updateColors(Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider), glyphColor(dark));
        }
        // NagramX: only the new-design flash button carries a fixed tint here; the legacy one is driven
        // by flashViews, so leave it alone
        if (flashButton != null && isNewDesign) {
            applyGlyphColor(flashButton, glyphColor(dark));
        }
        updateInfiniteButton();
    }

    // NagramX: resourcesProvider.isDark() answers for the global theme, so a chat carrying its own dark
    // theme (or a night wallpaper) left the glyphs inverted as if the panel were light. Read the panel
    // color these controls actually sit on instead.
    private boolean isDarkAppearance() {
        return ColorUtils.calculateLuminance(Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider)) <= 0.5f;
    }

    private int glyphColor(boolean dark) {
        final int stock = dark ? Color.WHITE : ColorUtils.blendARGB(Color.WHITE, Color.BLACK, 0.6f);
        return isNewDesign ? com.dazewell.gram.ui.Md3ButtonColorProvider.glyphColor(resourcesProvider, stock) : stock; // NagramX: MD3 Buttons share one glyph tint; the story recorder's legacy flash can't follow it
    }

    // NagramX: the flash glyphs are single-channel lotties, so RLottieDrawable draws them as a bare alpha
    // mask and installs its own SRC_IN white filter to give them a color. setInvert() replaces that with a
    // MULTIPLY filter, which has nothing to multiply against and leaves the mask black in every theme, so
    // tint with SRC_IN instead — the same way the zoom chips next to it are colored.
    private void applyGlyphColor(ImageView view, int color) {
        view.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
    }

    // NagramX: infinite video message: the toggle can go dead mid-recording (view-once armed, slow mode
    // started, last segment reached), so the button is re-read at every point that can move it instead of
    // only on click
    public void updateInfiniteButton() {
        applyInfiniteRecordingState(delegate == null ? INFINITE_RECORDING_UNAVAILABLE : delegate.getInfiniteRecordingState());
    }

    private void applyInfiniteRecordingState(int state) {
        if (infiniteButton == null) {
            return;
        }
        if (state == INFINITE_RECORDING_UNAVAILABLE) {
            infiniteButton.setVisibility(GONE);
            return;
        }
        final boolean on = state == INFINITE_RECORDING_ON;
        final boolean dark = isDarkAppearance();
        infiniteButton.setVisibility(VISIBLE);
        // NagramX: this cell is the row's right end, so its selected fill has to reproduce the parent
        // pill's cap exactly (right corners rounded to match, left corners square against the flash cell)
        // or a wedge of the light/dark panel shows through at the seam. Hardcoding the physical right side
        // is safe because android:supportsRtl="false" in AndroidManifest.xml:120 keeps this row from
        // mirroring.
        final int fillColor = dark ? Color.WHITE : Color.BLACK;
        final int glyphOnColor = dark ? Color.BLACK : Color.WHITE;
        infiniteButton.setBackground(on ? Theme.createRoundRectDrawable(0, dp(21), dp(21), 0, fillColor) : null);
        applyGlyphColor(infiniteButton, on ? glyphOnColor : glyphColor(dark));
        infiniteButton.setContentDescription(LocaleController.getString(on ? R.string.AccDescrInfiniteRecordingOff : R.string.AccDescrInfiniteRecordingOn));
    }

    // NagramX: arms (or clears) the pre-cut pulse on the progress arc. Called from TimerView, which
    // already owns the "what happens at the cap" decision -- onDraw here just reads the flag it sets.
    public void setLimitWarningActive(boolean active) {
        if (limitWarningActive != active) {
            limitWarningActive = active;
            invalidate();
        }
    }

    // NagramX: called right before the cap auto-stop dispatch, consumed once in shutdown() below.
    public void armLimitStopHaptic() {
        limitStopHapticArmed = true;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.fileUploaded) {
            final String location = (String) args[0];
            if (cameraFile != null && cameraFile.getAbsolutePath().equals(location)) {
                file = (TLRPC.InputFile) args[1];
                encryptedFile = (TLRPC.InputEncryptedFile) args[2];
                size = (Long) args[5];
                if (encryptedFile != null) {
                    key = (byte[]) args[3];
                    iv = (byte[]) args[4];
                }
            }
        } else if (id == NotificationCenter.didSetNewTheme) {
            updateThemeColors();
        }
    }

    public void destroy(boolean async) {
        if (useCamera2) {
            for (int a = 0; a < camera2Sessions.length; ++a) {
                if (camera2Sessions[a] != null) {
                    camera2Sessions[a].destroy(async);
                    camera2Sessions[a] = null;
                }
            }
        } else {
            if (cameraSession != null) {
                cameraSession.destroy();
                CameraController.getInstance().close(cameraSession, !async ? new CountDownLatch(1) : null, null);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float x = cameraContainer.getX();
        float y = cameraContainer.getY();
        rect.set(x - dp(8), y - dp(8), x + cameraContainer.getMeasuredWidth() + dp(8), y + cameraContainer.getMeasuredHeight() + dp(8));
        if (recording) {
            recordedTime = System.currentTimeMillis() - recordStartTime + recordPlusTime;
            progress = Math.min(1f, recordedTime / 60000.0f);
            invalidate();
        }

        if (progress != 0) {
            canvas.save();
            if (!flipAnimationInProgress) {
                canvas.scale(cameraContainer.getScaleX(), cameraContainer.getScaleY(), rect.centerX(), rect.centerY());
            }
            canvas.drawArc(rect, -90, 360 * progress, false, paint);
            // NagramX: pulse a second arc over the shared white one for the last ~5s before a round video
            // recording hits its limit. A separate paint on purpose -- `paint`'s alpha is already driven
            // by the PAINT_ALPHA animator in setupVideoPlayer, and tinting it here would fight that
            // animation. A pulse rather than a flat tint because a colour shift on a thin white arc
            // barely reads on a bright screen.
            if (limitWarningActive) {
                if (limitWarningPaint == null) {
                    limitWarningPaint = new Paint(paint);
                    limitWarningPaint.setColor(0xffff5252);
                }
                float phase = (System.currentTimeMillis() % 700L) / 700f;
                // NagramX: alpha must swing down to ~0, not just to a pinkish floor -- 110 + 145*|sin| never
                // drops below 110, so the white arc underneath was never visible again and this read as a
                // solid pink-to-red pulse instead of a white<->red flash. 255*|sin| hits 0 at both ends of
                // the cycle (sin is 0 at phase 0 and 1), so the white arc shows through there.
                limitWarningPaint.setAlpha((int) (255 * Math.abs(Math.sin(phase * Math.PI))));
                canvas.drawArc(rect, -90, 360 * progress, false, limitWarningPaint);
            }
            canvas.restore();
        }
        holdToSend.drawLabel(canvas, this, cameraContainer); // NagramX (#video-hold-send): above the circle and its arc
    }

    private boolean setVisibilityFromPause;
    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);

        buttonsLayout.setAlpha(0.0f);
        zoomControlView.setAlpha(0.0f);
        zoomLabel.setAlpha(0.0f);
        cameraContainer.setAlpha(0.0f);
        textureOverlayView.setAlpha(0.0f);
        muteImageView.setAlpha(0.0f);
        muteImageView.setScaleX(1.0f);
        muteImageView.setScaleY(1.0f);
        cameraContainer.setScaleX(setVisibilityFromPause ? 1f : 0.1f);
        cameraContainer.setScaleY(setVisibilityFromPause ? 1f : 0.1f);
        textureOverlayView.setScaleX(setVisibilityFromPause ? 1f : 0.1f);
        textureOverlayView.setScaleY(setVisibilityFromPause ? 1f : 0.1f);
        if (cameraContainer.getMeasuredWidth() != 0) {
            cameraContainer.setPivotX(cameraContainer.getMeasuredWidth() / 2);
            cameraContainer.setPivotY(cameraContainer.getMeasuredHeight() / 2);
            textureOverlayView.setPivotX(textureOverlayView.getMeasuredWidth() / 2);
            textureOverlayView.setPivotY(textureOverlayView.getMeasuredHeight() / 2);
        }
        try {
            if (visibility == VISIBLE) {
                ((Activity) getContext()).getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                ((Activity) getContext()).getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void togglePause() {
        if (!recording && cameraThread != null) {
            return; // NagramX (#round-video-restart-guard-fix): the last resume's camera is still coming up; a second resume would stop it as a terminal stop
        }
        if (cameraFileHandedOff || encoderTeardownPending) {
            return; // NagramX (#round-video-restart-guard-fix): a resume here would reopen the camera into the finished recorder
        }
        if (recording) {
            cancelled = recordedTime < 800;
            recording = false;
            updateFlash();
            if (cameraThread != null) {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStopped, recordingGuid, cancelled ? 4 : 2);
                saveLastCameraBitmap();
                cameraThread.shutdown(cancelled ? 0 : 2, true, 0, 0, cancelled ? 0 : -2, 0);
                cameraThread = null;
            }
            if (cancelled) {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.audioRecordTooShort, recordingGuid, true, (int) recordedTime);
                startAnimation(false, false);
                MediaController.getInstance().requestRecordAudioFocus(false);
            } else {
                // NagramX (#video-draft-guard): pin the draft generation before requesting the pause finalize,
                // symmetric with the stop path. handlePauseRecording snapshots it into finalizeGeneration and the
                // async audioDidSent post carries that, so a finalize superseded by a newer recording -- or by a
                // passcode-lock rebuild bumping the token -- is dropped at the enter-view gate instead of landing
                // an unsendable paused preview on the fresh instance. videoEncoder is non-null here (pause()
                // dereferences it next).
                videoEncoder.finalizeDraftToken = delegate != null ? delegate.getVideoDraftToken() : 0;
                videoEncoder.pause();
            }
        } else if (videoEncoder != null) {
            // NagramX (#round-video-restart-guard-fix): no videoEncoder.resume() here. It unpaused the recorder before its
            // audio thread restarted, and a stop in that gap waited forever for that thread. prepareEncoder(true)
            // unpauses it once audio is back, so until then a stop takes the paused single pass and tears down.
            hideCamera(false);
            if (videoPlayer != null) {
                videoPlayer.releasePlayer(true);
                videoPlayer = null;
            }
            showCamera(true);
            try {
                if (!NekoConfig.disableVibration.Bool()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {}
            AndroidUtilities.lockOrientation(delegate.getParentActivity());
            invalidate();
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordResumed);
        }
    }

    public boolean isPaused() {
        return !recording;
    }

    // NagramX: true only while a round video is actively capturing -- live with the finger down (a) or
    // hands-free/locked (c). Positive liveness, not isPaused()'s !recording, which also reads true when
    // nothing was ever started. onPause uses this to finalize a still-capturing recording before the app
    // backgrounds. Read on the UI thread only, like every other reader of `recording`.
    public boolean isRecording() {
        return recording;
    }

    // NagramX (#round-video-restart-guard-fix): a new recording can't start while the last camera is still up or
    // its recorder is still finishing.
    public boolean isBusyForNewRecording() {
        return textureView != null || encoderTeardownPending;
    }

    // NagramX (#round-video-restart-guard-fix): true only for a camera whose file went to a send, which is the only
    // camera the send transition and its fallback may retire.
    public boolean isFileHandedOff() {
        return cameraFileHandedOff;
    }

    // NagramX (#video-draft-guard): wire a restored round-video draft's on-disk file into this instance so the
    // normal state==4 send path can re-upload and send it, without re-opening the camera. Refuses when a live
    // session is present (recording, or a same-session finalize already holds its own cameraFile) so it can
    // never clobber a real recording's file -- in that case the object is already shared and native trim wins.
    // The passed info carries the persisted trim + draft id and becomes this instance's videoEditedInfo, so
    // send(4) reads the exact cut. Locks the file for the life of the send. Returns true only when it actually
    // took ownership (assigned cameraFile/size/videoEditedInfo and locked); false on every refusal so the caller
    // can decline to bind a preview this instance can't send.
    public boolean adoptRestoredDraft(File file, long fileSize, VideoEditedInfo info) {
        if (recording || videoEncoder != null || cameraThread != null) {
            return false;
        }
        if (cameraFile != null && cameraFile.exists()) {
            return false;
        }
        if (file == null || !file.exists() || info == null) {
            return false;
        }
        cameraFile = file;
        size = fileSize;
        videoEditedInfo = info;
        sendAdoptedDraft = true;
        cameraFileHandedOff = false; // NagramX (#round-video-restart-guard-fix): the adopted draft is owned again, or its send(4) would no-op
        // NagramX (#video-draft-guard): force the normal upload flow for this file. send(4)'s untrimmed branch
        // copies these fields straight onto videoEditedInfo without nulling them (only the trimmed branch clears
        // them), so any leftover upload material on this instance would otherwise ride along with the restored
        // draft as a stale InputFile / encryption key. A fresh instance holds nulls already; clear them anyway so
        // the adopted send never depends on that. Use this.file -- the java.io.File parameter shadows the field.
        this.file = null;
        encryptedFile = null;
        key = null;
        iv = null;
        AutoDeleteMediaTask.lockFile(file);
        return true;
    }

    // flips between the front and back camera; also reached from the zoom control's flip button.
    // guarded against re-entry so a rapid double-tap can't stack two flips over each other.
    private void flipCamera() {
        if (flipAnimationInProgress || !cameraReady || !isCameraSessionInitiated() || cameraThread == null) {
            return;
        }
        if (!bothCameras) {
            switchCamera();
        }
        flipAnimationInProgress = true;
        ValueAnimator valueAnimator = ValueAnimator.ofFloat(0, 1f);
        valueAnimator.setDuration(580);
        valueAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        final boolean[] didSwap = new boolean[1];
        Runnable doSwap = () -> {
            if (bothCameras) {
                switchCamera();
            }
        };
        cameraContainer.setCameraDistance(cameraContainer.getMeasuredHeight() * 8f);
        textureOverlayView.setCameraDistance(textureOverlayView.getMeasuredHeight() * 8f);
        valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                float p = (float) valueAnimator.getAnimatedValue();
                if (p > 0.5f && !didSwap[0]) {
                    didSwap[0] = true;
                    doSwap.run();
                }
                float rotation = p < 0.5f ? p : p - 1f;
                rotation *= 180;
                cameraContainer.setRotationY(rotation);
                textureOverlayView.setRotationY(rotation);
            }
        });
        valueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (!didSwap[0]) {
                    didSwap[0] = true;
                    doSwap.run();
                }
                cameraContainer.setRotationY(0f);
                textureOverlayView.setRotationY(0f);
                flipAnimationInProgress = false;
                invalidate();
            }
        });
        valueAnimator.start();
    }

    public void showCamera(boolean fromPaused) {
        if (textureView != null) {
            return;
        }
        if (!fromPaused && encoderTeardownPending) {
            return; // NagramX (#round-video-restart-guard-fix): a fresh open would reuse the recorder still finishing the last video
        }
        // NagramX: pick up the current theme each time the recorder opens, in case it changed while hidden
        updateThemeColors();
        textureOverlayView.setAlpha(1.0f);
        textureOverlayView.invalidate();
        if (lastBitmap == null) {
            try {
                File file = new File(ApplicationLoader.getFilesDirFixed(), "icthumb.jpg");
                lastBitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            } catch (Throwable ignore) {

            }
        }
        if (lastBitmap != null) {
            textureOverlayView.setImageBitmap(lastBitmap);
        } else {
            textureOverlayView.setImageResource(R.drawable.icplaceholder);
        }
        cameraReady = false;
        selectedCamera = null;
        if (!fromPaused) {
            int cameraMode = NaConfig.INSTANCE.getCameraInVideoMessages().Int();
            if (cameraMode != 2) {
                isFrontface = (cameraMode == 0); // 0 = front, 1 = rear
                initialCameraFront = isFrontface;
            }
            surfaceIndex = 0;
            resetZoom();
            updateFlash();
            recordedTime = 0;
            progress = 0;
        }
        cancelled = false;
        file = null;
        encryptedFile = null;
        key = null;
        iv = null;
        needDrawFlickerStub = true;

        if (!initCamera()) {
            return;
        }
        if (MediaController.getInstance().getPlayingMessageObject() != null) {
            if (MediaController.getInstance().getPlayingMessageObject().isVideo() || MediaController.getInstance().getPlayingMessageObject().isRoundVideo()) {
                MediaController.getInstance().cleanupPlayer(true, true);
            } else if (SharedConfig.pauseMusicOnRecord) {
                MediaController.getInstance().pauseByRewind();
            }
        }

        if (!fromPaused) {
            cameraFile = generateCameraFile();
            sendAdoptedDraft = false; // NagramX (#video-draft-guard): a fresh recording owns a live cameraFile; drop any restored-draft send state
            cameraFileHandedOff = false; // NagramX (#round-video-restart-guard-fix): same, this file is ours again
        }

        SharedConfig.saveConfig();
        AutoDeleteMediaTask.lockFile(cameraFile);

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("InstantCamera show round camera " + cameraFile.getAbsolutePath());
        }

        if (useCamera2) {
            bothCameras = DualCameraView.roundDualAvailableStatic(getContext());
            if (bothCameras) {
                for (int a = 0; a < 2; ++a) {
                    if (camera2Sessions[a] == null) {
                        camera2Sessions[a] = Camera2Session.create(a == (isFrontface ? 0 : 1), MessagesController.getInstance(UserConfig.selectedAccount).roundVideoSize, MessagesController.getInstance(UserConfig.selectedAccount).roundVideoSize);
                        if (camera2Sessions[a] != null) {
                            camera2Sessions[a].setRecordingVideo(true);
                            camera2Sessions[a].setRoundVideo(); // NagramX: per session, so the idle one of a dual pair matches
                            camera2Sessions[a].whenFailed(this::onRoundDualCameraLost); // NagramX (#round-dual-camera-fix)
                            previewSize[a] = new Size(camera2Sessions[a].getPreviewWidth(), camera2Sessions[a].getPreviewHeight());
                        }
                    }
                }
                updateFlash();
                camera2SessionCurrent = camera2Sessions[isFrontface == initialCameraFront ? 0 : 1];
                if (camera2SessionCurrent != null && camera2Sessions[isFrontface == initialCameraFront ? 1 : 0] == null) {
                    bothCameras = false;
                }
                if (camera2SessionCurrent == null) return;
            } else {
                camera2SessionCurrent = camera2Sessions[isFrontface ? 0 : 1] = Camera2Session.create(isFrontface, MessagesController.getInstance(UserConfig.selectedAccount).roundVideoSize, MessagesController.getInstance(UserConfig.selectedAccount).roundVideoSize);
                if (camera2SessionCurrent == null) return;
                camera2SessionCurrent.setRecordingVideo(true);
                camera2SessionCurrent.setRoundVideo(); // NagramX
                previewSize[0] = new Size(camera2SessionCurrent.getPreviewWidth(), camera2SessionCurrent.getPreviewHeight());
            }
            adaptZoomToSession(0f); // NagramX: 1x home depends on this camera's own range
            applyLockedZoomToCamera();
        }
        updateZoomControlAvailability();
        textureView = new TextureView(getContext());
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("InstantCamera camera surface available");
                }
                if (cameraThread == null && surface != null) {
                    // NagramX (#round-video-restart-guard-fix): a re-attach inside the send window would start a new GL
                    // thread recording into the handed-off file
                    if (cancelled || cameraFileHandedOff || encoderTeardownPending) {
                        return;
                    }
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("InstantCamera start create thread");
                    }
                    cameraThread = new CameraGLThread(surface, width, height);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, final int width, final int height) {
                if (cameraThread != null) {
                    cameraThread.surfaceWidth = width;
                    cameraThread.surfaceHeight = height;
                    cameraThread.updateScale();
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (cameraThread != null) {
                    cameraThread.shutdown(0, true, 0, 0, 0, 0);
                    cameraThread = null;
                }
                if (useCamera2) {
                    for (int a = 0; a < camera2Sessions.length; ++a) {
                        if (camera2Sessions[a] != null) {
                            camera2Sessions[a].destroy(false);
                            camera2Sessions[a] = null;
                        }
                    }
                } else {
                    if (cameraSession != null) {
                        CameraController.getInstance().close(cameraSession, null, null);
                    }
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
        cameraContainer.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        updateTextureViewSize = true;
        setVisibilityFromPause = fromPaused;
        setVisibility(VISIBLE);

        startAnimation(true, fromPaused);
        MediaController.getInstance().requestRecordAudioFocus(true);
    }

    public InstantViewCameraContainer getCameraContainer() {
        return cameraContainer;
    }

    public void startAnimation(boolean open, boolean fromPaused) {
        dispatchAnimationState(open, fromPaused);
        if (animatorSet != null) {
            animatorSet.removeAllListeners();
            animatorSet.cancel();
        }
        PipRoundVideoView pipRoundVideoView = PipRoundVideoView.getInstance();
        if (pipRoundVideoView != null) {
            pipRoundVideoView.showTemporary(!open);
        }
        if (open && !opened) {
            cameraContainer.setTranslationX(0);
            textureOverlayView.setTranslationX(0);
            zoomControlView.setTranslationX(0);

            animationTranslationY = fromPaused ? 0 : getMeasuredHeight() / 2f;
            updateTranslationY();
        }
        opened = open;
        if (!open) {
            cancelZoomInteractions();
        }
        if (parentView != null) {
            parentView.invalidate();
        }
        animatorSet = new AnimatorSet();
        float toX = 0;
        if (!open) {
            toX = recordedTime > 300 ? dp(24) - getMeasuredWidth() / 2f : 0;
        }
        ValueAnimator translationYAnimator = ValueAnimator.ofFloat(open ? 1f : 0f, open ? 0 : 1f);
        translationYAnimator.addUpdateListener(animation -> {
            animationTranslationY = fromPaused ? 0 : (getMeasuredHeight() / 2f) * (float) animation.getAnimatedValue();
            updateTranslationY();
        });
        animatorSet.playTogether(
                ObjectAnimator.ofFloat(buttonsLayout, View.ALPHA, open ? 1.0f : 0.0f),
                ObjectAnimator.ofFloat(zoomControlView, View.ALPHA, open ? 1.0f : 0.0f),
                ObjectAnimator.ofFloat(muteImageView, View.ALPHA, 0.0f),
                ObjectAnimator.ofInt(paint, AnimationProperties.PAINT_ALPHA, open ? 255 : 0),
                ObjectAnimator.ofFloat(cameraContainer, View.ALPHA, open ? 1.0f : 0.0f),
                ObjectAnimator.ofFloat(cameraContainer, View.SCALE_X, open ? 1.0f : 0.1f),
                ObjectAnimator.ofFloat(cameraContainer, View.SCALE_Y, open ? 1.0f : 0.1f),
                ObjectAnimator.ofFloat(cameraContainer, View.TRANSLATION_X, toX),
                ObjectAnimator.ofFloat(textureOverlayView, View.ALPHA, open ? 1.0f : 0.0f),
                ObjectAnimator.ofFloat(textureOverlayView, View.SCALE_X, open ? 1.0f : 0.1f),
                ObjectAnimator.ofFloat(textureOverlayView, View.SCALE_Y, open ? 1.0f : 0.1f),
                ObjectAnimator.ofFloat(textureOverlayView, View.TRANSLATION_X, toX),
                ObjectAnimator.ofFloat(zoomControlView, View.TRANSLATION_X, toX),
                translationYAnimator
        );
        if (!open) {
            animatorSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animation.equals(animatorSet)) {
                        hideCamera(true);
                        setVisibilityFromPause = false;
                        setVisibility(INVISIBLE);
                    }
                }
            });
        } else {
            setTranslationX(0);
        }
        animatorSet.setDuration(180);
        animatorSet.setInterpolator(new DecelerateInterpolator());
        animatorSet.start();
    }

    private void updateTranslationY() {
        // the circle and the zoom control are laid out Gravity.CENTER inside this view's padded content
        // area, and the keyboard is reserved as the bottom padding (internalPaddingBottom). so their
        // natural, untranslated center sits at the middle of the area above the keyboard, not the middle
        // of the whole view. measure everything from that visible center; the full-height center throws
        // the block up by half the reserved inset, which is what made the preview sit too high.
        final float visibleHalf = (getMeasuredHeight() - internalPaddingBottom) / 2f;
        final float bottomControlsTop = visibleHalf;

        // when the keyboard squeezes the room below the circle, lift the circle up so the two-row
        // control fits beneath it instead of being crammed over the preview. measured from the
        // settled (fully-open) position, so the lift stays constant through the open/close slide
        // rather than yanking the circle while animationTranslationY runs.
        // the roomy layout needs ~124dp (104dp of rows + margins); lift toward a hair more (dp132) as
        // far as the top chrome allows, then let whatever room is left pick roomy vs compact.
        final float restingGap = bottomControlsTop - (panTranslationY + textureViewSize / 2f + dp(8));
        // keep the circle on screen no matter what the keyboard/pan animators are doing. the parent
        // (ChatActivityFragmentView, which holds this view) is itself translated up while the keyboard
        // slides, and this view only carries +panTranslationY, so the circle's real on-screen top is
        // the parent's translation above its view position. cap the lift against the parent's actual
        // translationY rather than an assumed 2x pan (the two can desync mid-slide) so the shifts can't
        // stack and throw the preview off the top; dp(64) keeps the circle just below the action bar.
        // (was dp(80): too conservative — when a reply's top view squeezed the gap the circle stayed
        // pinned with ~20dp of usable room above it going unused, so the two-row control fell to compact.)
        final float parentTop = getParent() instanceof View ? ((View) getParent()).getTranslationY() : 0f;
        final float maxLift = Math.max(0f, parentTop + visibleHalf + panTranslationY - textureViewSize / 2f - dp(64));
        // lift toward dp(132), a little over the ~124dp two rows actually need, so the fed gap lands
        // solidly in the roomy band; the circle then sits as low (and as safely on screen) as the room
        // allows instead of being yanked all the way up.
        final float liftForRoomy = Math.max(0f, dp(132) - restingGap);
        final float lift = Math.min(liftForRoomy, maxLift);
        final float gapAfterLift = restingGap + lift;

        final float translationY = animationTranslationY + panTranslationY - lift;
        textureOverlayView.setTranslationY(translationY);
        cameraContainer.setTranslationY(translationY);

        // feed a gap that's decisively outside the compact hysteresis band [112..126] so the keyboard
        // slide can't flap the layout; gapAfterLift is continuous across the maxLift cap and crosses once.
        // the shortened two-row block (InstantZoomControlView, 68dp) has its buttons ~40dp below center,
        // so it only overlaps the panel below ~80dp of gap — gate there. between the relaxed lift cap
        // above (which now frees the circle to open the gap to ~100dp when a reply squeezes it) and this
        // floor gate, two rows win whenever they physically fit; feeding dp(132)/<dp(108) keeps the
        // values clear of the [112..126] band. compact only when it genuinely can't fit (very small gaps).
        // NagramX: with the lens strip up the rows reach 50dp above and 48dp below center, not 44 and 40 (see
        // InstantZoomControlView.updateGeometry), so the roomy gate (twice the reach below) and the placement
        // margin grow to match. the 80dp gate and 68dp rows described above are the no-strip case
        final boolean presetRows = zoomControlView.hasPresets();
        zoomControlView.setAvailableGap(gapAfterLift >= dp(presetRows ? 96 : 80) ? dp(132) : Math.min(gapAfterLift, dp(108)));
        final boolean compact = zoomControlView.isCompact();

        final float cameraBottom = translationY + textureViewSize / 2f + dp(8);
        // the drawn rows sit at the view's own center in both layouts, so translationY is the content center
        final float contentHalf = dp(compact ? 24 : presetRows ? 50 : 44);
        final float lo = cameraBottom + dp(8) + contentHalf;
        final float hi = bottomControlsTop - dp(12) - contentHalf;
        float zoomControlCenterY;
        if (compact && gapAfterLift < dp(64)) {
            // almost no room left: hug the record controls and let the scrims ride over the preview edge
            zoomControlCenterY = bottomControlsTop - dp(36);
        } else if (lo > hi) {
            // vertical budget too tight for the row to clear both edges: sit it centered in the gap
            zoomControlCenterY = (cameraBottom + bottomControlsTop) / 2f;
        } else {
            zoomControlCenterY = Math.min(Math.max((cameraBottom + bottomControlsTop) / 2f, lo), hi);
        }
        zoomControlView.setTranslationY(zoomControlCenterY);
        // NagramX: the lens strip fills the band above the slider, so the ratio readout docks in the circle
        if (compact || zoomControlView.hasPresets()) {
            // no vertical room above the row: dock the label inside the camera circle, bottom-center
            zoomLabel.setTranslationY(translationY + textureViewSize / 2f - dp(30));
        } else {
            zoomLabel.setTranslationY(zoomControlCenterY - dp(54));
        }
    }

    public RectF getCameraRect() {
        cameraContainer.getLocationOnScreen(position);
        return new RectF(
                position[0],
                position[1],
                position[0] + cameraContainer.getWidth(),
                position[1] + cameraContainer.getHeight()
        );
    }

    public void changeVideoPreviewState(int state, float progress) {
        if (videoPlayer == null) {
            return;
        }
        if (state == 0) {
            startProgressTimer();
            videoPlayer.play();
        } else if (state == 1) {
            stopProgressTimer();
            videoPlayer.pause();
        } else if (state == 2) {
            videoPlayer.seekTo((long) (progress * videoPlayer.getDuration()));
        }
    }

    public void send(int state, boolean notify, int scheduleDate, int scheduleRepeatPeriod, int ttl, long effectId, long stars) {
        if (cameraFileHandedOff) {
            return; // NagramX (#round-video-restart-guard-fix): this file already went; a second send would duplicate it
        }
        if (textureView == null && !(sendAdoptedDraft && state == 4)) {
            // NagramX (#video-draft-guard): a restored draft (a rebuilt instance that only adopted a file, never
            // opened the camera) has no textureView, but its cameraFile/videoEditedInfo are set up by
            // adoptRestoredDraft, so let the state==4 send through instead of silently dropping the message.
            return;
        }
        stopProgressTimer();
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
            videoPlayer = null;
        }
        if (state == 4) {
            // NagramX (#round-video-restart-guard-fix): with a terminal stop already queued the recorder is quitting and
            // would drop this; its file is finished, so send it directly, as happens once teardown nulls videoEncoder
            if (videoEncoder != null && recordedTime > 800 && !encoderTeardownPending) {
                // NagramX (#round-video-restart-guard-fix): from here the file belongs to the outgoing message
                cameraFileHandedOff = true;
                if (videoEncoder.handler != null) {
                    encoderTeardownPending = true;
                }
                videoEncoder.stopRecording(VideoRecorder.ENCODER_SEND_SEND, new SendOptions(notify, scheduleDate, scheduleRepeatPeriod, ttl, effectId, stars));
                return;
            }
            if (BuildVars.DEBUG_VERSION && !cameraFile.exists()) {
                FileLog.e(new RuntimeException("file not found :( round video"));
            }
            if (videoEditedInfo == null) {
                videoEditedInfo = new VideoEditedInfo();
                videoEditedInfo.startTime = -1;
                videoEditedInfo.endTime = -1;
            }
            if (videoEditedInfo.needConvert()) {
                file = null;
                encryptedFile = null;
                key = null;
                iv = null;
                double totalDuration = videoEditedInfo.estimatedDuration;
                long startTime = videoEditedInfo.startTime >= 0 ? videoEditedInfo.startTime : 0;
                long endTime = videoEditedInfo.endTime >= 0 ? videoEditedInfo.endTime : videoEditedInfo.estimatedDuration;
                videoEditedInfo.estimatedDuration = endTime - startTime;
                videoEditedInfo.estimatedSize = Math.max(1, (long) (size * (videoEditedInfo.estimatedDuration / totalDuration)));
                videoEditedInfo.bitrate = 1000000;
                if (videoEditedInfo.startTime > 0) {
                    videoEditedInfo.startTime *= 1000;
                }
                if (videoEditedInfo.endTime > 0) {
                    videoEditedInfo.endTime *= 1000;
                }
                FileLoader.getInstance(currentAccount).cancelFileUpload(cameraFile.getAbsolutePath(), false);
            } else {
                videoEditedInfo.estimatedSize = Math.max(1, size);
            }
            videoEditedInfo.file = file;
            videoEditedInfo.encryptedFile = encryptedFile;
            videoEditedInfo.key = key;
            videoEditedInfo.iv = iv;
            MediaController.PhotoEntry entry = new MediaController.PhotoEntry(0, 0, 0, cameraFile.getAbsolutePath(), 0, true, 0, 0, 0);
            entry.ttl = ttl;
            entry.effectId = effectId;
            // NagramX (#round-video-restart-guard-fix): set before sendMedia, which on stories calls cancel() back
            cameraFileHandedOff = true;
            delegate.sendMedia(entry, videoEditedInfo, notify, scheduleDate, scheduleRepeatPeriod, false, stars);
            if (scheduleDate != 0) {
                startAnimation(false, false);
            }
            MediaController.getInstance().requestRecordAudioFocus(false);
        } else {
            if (videoEncoder != null) {
                // NagramX: pin the draft generation this stop finalizes. The recorder snapshots it into
                // finalizeGeneration when the stop sequence begins, so the async audioDidSent post carries the
                // request-time value even if a newer recording bumps the token before the post runs.
                videoEncoder.finalizeDraftToken = delegate != null ? delegate.getVideoDraftToken() : 0;
            }
            cancelled = recordedTime < 800;
            recording = false;
            flashing = false;
            updateFlash();
            int reason;
            if (cancelled) {
                reason = 4;
            } else {
                reason = state == 3 ? 2 : 5;
            }
            if (cameraThread != null) {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStopped, recordingGuid, reason);
                int send;
                if (cancelled) {
                    send = 0;
                } else if (state == 3) {
                    send = 2;
                } else {
                    send = 1;
                    cameraFileHandedOff = true; // NagramX (#round-video-restart-guard-fix): the recorder sends this file now
                }
                saveLastCameraBitmap();
                cameraThread.shutdown(send, notify, scheduleDate, scheduleRepeatPeriod, ttl, effectId);
                cameraThread = null;
            }
            if (cancelled) {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.audioRecordTooShort, recordingGuid, true, (int) recordedTime);
                startAnimation(false, false);
                MediaController.getInstance().requestRecordAudioFocus(false);
            }
        }
    }

    // NagramX: same cache file the recorder starts with, so an infinite-mode rollover can open the next one
    private File generateCameraFile() {
        return new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT), System.currentTimeMillis() + "_" + SharedConfig.getLastLocalId() + ".mp4") {
            @Override
            public boolean delete() {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("delete camera file");
                }
                return super.delete();
            }
        };
    }

    // NagramX: infinite video message: wrap the current segment up, send it, and keep recording into the
    // next file. The camera, the encoders and the audio record all stay running, so nothing is lost at the
    // boundary beyond the frames it takes the encoder to produce a keyframe.
    public void rollOverSegment(boolean notify, int scheduleDate, int ttl, long effectId, long stars) {
        if (cameraThread == null || videoEncoder == null || !recording || cancelled) {
            return;
        }
        holdToSend.reset(); // NagramX (#video-hold-send): a hold must not send across a segment boundary
        final long segmentDuration = recordedTime;
        final File nextFile = generateCameraFile();
        AutoDeleteMediaTask.lockFile(nextFile);
        // the finished segment is still being written when we get here, so its upload can't have completed
        // yet and these carry nothing worth keeping: hand them straight to the next segment
        cameraFile = nextFile;
        file = null;
        encryptedFile = null;
        key = null;
        iv = null;
        size = 0;
        videoEncoder.rollOver(nextFile, segmentDuration, new SendOptions(notify, scheduleDate, 0, ttl, effectId, stars));
        recordPlusTime = 0;
        recordStartTime = System.currentTimeMillis();
        recordedTime = 0;
        progress = 0;
        setLimitWarningActive(false);
        invalidate();
        updateInfiniteButton();
    }

    // NagramX: send a finished infinite-mode segment as its own round message. Mirrors the normal round-video
    // send: the file is handed over while its upload is still being finalised, same as a user-stopped one.
    private VideoEditedInfo sendSegment(File segmentFile, long duration, SendOptions options) {
        if (delegate == null) {
            return null;
        }
        VideoEditedInfo info = new VideoEditedInfo();
        info.startTime = -1;
        info.endTime = -1;
        info.roundVideo = true;
        info.estimatedSize = Math.max(1, segmentFile.length());
        info.estimatedDuration = duration;
        info.framerate = 25;
        info.resultWidth = info.originalWidth = 360;
        info.resultHeight = info.originalHeight = 360;
        info.originalPath = segmentFile.getAbsolutePath();
        info.notReadyYet = true;
        if (textureView != null) {
            info.thumb = textureView.getBitmap();
        }
        MediaController.PhotoEntry entry = new MediaController.PhotoEntry(0, 0, 0, segmentFile.getAbsolutePath(), 0, true, 0, 0, 0);
        if (options != null) {
            entry.ttl = options.ttl;
            entry.effectId = options.effectId;
        }
        delegate.sendMediaKeepRecording(entry, info, options == null || options.notify, options != null ? options.scheduleDate : 0, options != null ? options.stars : 0);
        AutoDeleteMediaTask.unlockFile(segmentFile);
        return info;
    }

    private void saveLastCameraBitmap() {
        Bitmap bitmap = textureView.getBitmap();
        if (bitmap != null && bitmap.getPixel(0, 0) != 0) {
            lastBitmap = Bitmap.createScaledBitmap(textureView.getBitmap(), 50, 50, true);
            if (lastBitmap != null) {
                Utilities.blurBitmap(lastBitmap, 7);
                try {
                    File file = new File(ApplicationLoader.getFilesDirFixed(), "icthumb.jpg");
                    FileOutputStream stream = new FileOutputStream(file);
                    lastBitmap.compress(Bitmap.CompressFormat.JPEG, 87, stream);
                    stream.close();
                } catch (Throwable ignore) {

                }
            }
        }
    }

    public void cancel(boolean byGesture) {
        stopProgressTimer();
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
            videoPlayer = null;
        }
        if (textureView == null) {
            return;
        }
        if (!cameraFileHandedOff) {
            cancelled = true; // NagramX (#round-video-restart-guard-fix): the recorder reads this, and a handed-off one may still owe a rollover cut
        }
        recording = false;
        flashing = false;
        updateFlash();
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStopped, recordingGuid, byGesture ? 0 : 6);
        if (cameraThread != null) {
            saveLastCameraBitmap();
            cameraThread.shutdown(0, true, 0, 0, 0, 0);
            cameraThread = null;
        } else if (videoEncoder != null && !cameraFileHandedOff) {
            // NagramX (#round-video-restart-guard-fix): a handed-off recorder is still finishing that send; cancelling
            // it would delete the file under the upload. The close below still runs.
            if (videoEncoder.handler != null) {
                encoderTeardownPending = true;
            }
            videoEncoder.stopRecording(VideoRecorder.ENCODER_SEND_CANCEL, new SendOptions(true, 0, 0, 0, 0, 0));
        }
        if (cameraFile != null && !cameraFileHandedOff) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("delete camera file by cancel");
            }
            cameraFile.delete();
            AutoDeleteMediaTask.unlockFile(cameraFile);
            cameraFile = null;
        }
        MediaController.getInstance().requestRecordAudioFocus(false);
        startAnimation(false, false);
        invalidate();
    }

    // NagramX (#video-draft-guard): release only the preview player and its progress timer, never the clip.
    // cancel() releases these too, but it also deletes cameraFile and unlocks it in its delete-and-unlock block;
    // this narrow variant exists so fragment teardown and the passcode rebuild can stop the leaking player/timer
    // without touching the finished draft. hideCamera() / destroy() / cancel() are deliberately not called here --
    // they route to encoder cancellation and file deletion, which is exactly the deletion path this feature must
    // never introduce.
    public void abandonPreview() {
        previewAbandoned = true;
        stopProgressTimer();
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
            videoPlayer = null;
        }
    }

    public View getButtonsLayout() {
        return buttonsLayout;
    }

    public View getMuteImageView() {
        return muteImageView;
    }

    // NagramX (#video-hold-send): the composer's sound chip reads and flips the preview's sound through it
    public com.dazewell.gram.videonote.VideoHoldToSend getHoldToSend() {
        return holdToSend;
    }

    public Paint getPaint() {
        return paint;
    }

    public void hideCamera(boolean async) {
        cancelZoomInteractions();
        destroy(async);
        cameraContainer.setTranslationX(0);
        textureOverlayView.setTranslationX(0);
        zoomControlView.setTranslationX(0);
        animationTranslationY = 0;
        updateTranslationY();
        MediaController.getInstance().resumeByRewind();

        if (textureView != null) {
            ViewGroup parent = (ViewGroup) textureView.getParent();
            if (parent != null) {
                parent.removeView(textureView);
            }
        }
        textureView = null;
        cameraContainer.setImageReceiver(null);
    }

    private void switchCamera() {
        if (NaConfig.INSTANCE.getVideoMessagesResetZoomOnSwitch().Bool()) {
            resetZoom();
        }
        if (!(useCamera2 && bothCameras)) {
            saveLastCameraBitmap();
            if (lastBitmap != null) {
                needDrawFlickerStub = false;
                textureOverlayView.setImageBitmap(lastBitmap);
                textureOverlayView.setAlpha(1f);
            }
        }
        isFrontface = !isFrontface;
        updateFlash();
        if (useCamera2) {
            // NagramX: each camera has its own zoom range in ratio mode, so carry the ratio, not the slider spot
            final float carriedZoom = camera2SessionCurrent == null ? 0f : zoomFractionToRatio(camera2SessionCurrent, lockedZoom);
            if (bothCameras) {
                camera2SessionCurrent = camera2Sessions[isFrontface == initialCameraFront ? 0 : 1];
                adaptZoomToSession(carriedZoom); // NagramX
                applyLockedZoomToCamera();
                updateZoomControlAvailability();
                cameraThread.flipSurfaces();
                return;
            } else {
                if (camera2SessionCurrent != null) {
                    camera2SessionCurrent.destroy(false);
                    camera2SessionCurrent = null;
                    camera2Sessions[isFrontface ? 1 : 0] = null;
                }
                camera2SessionCurrent = camera2Sessions[isFrontface ? 0 : 1] = Camera2Session.create(isFrontface, MessagesController.getInstance(UserConfig.selectedAccount).roundVideoSize, MessagesController.getInstance(UserConfig.selectedAccount).roundVideoSize);
                if (camera2SessionCurrent == null) return;
                camera2SessionCurrent.setRecordingVideo(true);
                camera2SessionCurrent.setRoundVideo(); // NagramX
                previewSize[0] = new Size(camera2SessionCurrent.getPreviewWidth(), camera2SessionCurrent.getPreviewHeight());
                cameraThread.setCurrentSession(camera2SessionCurrent);
                updateFlash(); // NagramX: the flash was set above, before this session existed
                adaptZoomToSession(carriedZoom); // NagramX
                applyLockedZoomToCamera();
                updateZoomControlAvailability();
            }
        } else {
            if (cameraSession != null) {
                cameraSession.destroy();
                CameraController.getInstance().close(cameraSession, null, null);
                cameraSession = null;
            }
        }
        initCamera();
        cameraReady = false;
        cameraThread.reinitForNewCamera();
    }

    // NagramX (#round-dual-camera-fix): some phones advertise concurrent cameras yet evict the first camera when
    // the second opens, leaving the rear preview black. Drop to one camera for this recording. The pref is only
    // written while cameraReady is still false, since another app taking the camera mid-recording proves nothing
    // about the phone. Once flipped nothing changes this attempt, since single mode draws surface 0 alone.
    private void onRoundDualCameraLost(Camera2Session failed) {
        if (!bothCameras || failed != camera2Sessions[0] && failed != camera2Sessions[1]) {
            return;
        }
        FileLog.e("InstantCamera dual camera #" + failed.cameraId + " lost, ready=" + cameraReady);
        if (!cameraReady) {
            MessagesController.getGlobalMainSettings().edit().putBoolean("rounddual_available", false).apply();
        }
        if (flipAnimationInProgress || camera2SessionCurrent != camera2Sessions[0] || surfaceIndex != 0) {
            return;
        }
        final boolean keep = failed != camera2SessionCurrent;
        FileLog.e("InstantCamera falling back to single camera, reopen=" + !keep);
        bothCameras = false;
        for (int a = 0; a < camera2Sessions.length; ++a) {
            if (camera2Sessions[a] != null && !(keep && camera2Sessions[a] == camera2SessionCurrent)) {
                camera2Sessions[a].destroy(false);
            }
            camera2Sessions[a] = null;
        }
        if (!keep) {
            // both old cameras are closed first, so the replacement is the only one open
            Camera2Session session = Camera2Session.create(isFrontface, MessagesController.getInstance(UserConfig.selectedAccount).roundVideoSize, MessagesController.getInstance(UserConfig.selectedAccount).roundVideoSize);
            if (session == null) {
                return;
            }
            session.setRecordingVideo(true);
            session.setRoundVideo(); // NagramX (#video-zoom)
            camera2SessionCurrent = session;
            previewSize[0] = new Size(session.getPreviewWidth(), session.getPreviewHeight());
        }
        camera2Sessions[isFrontface ? 0 : 1] = camera2SessionCurrent;
        adaptZoomToSession(0f); // NagramX (#video-zoom)
        applyLockedZoomToCamera();
        updateFlash();
        updateZoomControlAvailability();
        if (cameraThread != null) {
            cameraThread.handOverSession(camera2SessionCurrent, !keep);
        }
    }

    // Old Camera1 API
    @Deprecated
    private boolean initCamera() {
        if (useCamera2) {
            return true;
        }
        ArrayList<CameraInfo> cameraInfos = CameraController.getInstance().getCameras();
        if (cameraInfos == null) {
            return false;
        }
        CameraInfo notFrontface = null;
        for (int a = 0; a < cameraInfos.size(); a++) {
            CameraInfo cameraInfo = cameraInfos.get(a);
            if (!cameraInfo.isFrontface()) {
                notFrontface = cameraInfo;
            }
            if (isFrontface && cameraInfo.isFrontface() || !isFrontface && !cameraInfo.isFrontface()) {
                selectedCamera = cameraInfo;
                break;
            } else {
                notFrontface = cameraInfo;
            }
        }
        if (selectedCamera == null) {
            selectedCamera = notFrontface;
        }
        if (selectedCamera == null) {
            return false;
        }

        ArrayList<Size> previewSizes = selectedCamera.getPreviewSizes();
        ArrayList<Size> pictureSizes = selectedCamera.getPictureSizes();
        previewSize[0] = chooseOptimalSize(previewSizes);
        pictureSize = chooseOptimalSize(pictureSizes);
        if (previewSize[0].mWidth != pictureSize.mWidth) {
            boolean found = false;
            for (int a = previewSizes.size() - 1; a >= 0; a--) {
                Size preview = previewSizes.get(a);
                for (int b = pictureSizes.size() - 1; b >= 0; b--) {
                    Size picture = pictureSizes.get(b);
                    if (preview.mWidth >= pictureSize.mWidth && preview.mHeight >= pictureSize.mHeight && preview.mWidth == picture.mWidth && preview.mHeight == picture.mHeight) {
                        previewSize[0] = preview;
                        pictureSize = picture;
                        found = true;
                        break;
                    }
                }
                if (found) {
                    break;
                }
            }

            if (!found) {
                for (int a = previewSizes.size() - 1; a >= 0; a--) {
                    Size preview = previewSizes.get(a);
                    for (int b = pictureSizes.size() - 1; b >= 0; b--) {
                        Size picture = pictureSizes.get(b);
                        if (preview.mWidth >= 360 && preview.mHeight >= 360 && preview.mWidth == picture.mWidth && preview.mHeight == picture.mHeight) {
                            previewSize[0] = preview;
                            pictureSize = picture;
                            found = true;
                            break;
                        }
                    }
                    if (found) {
                        break;
                    }
                }
            }
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("InstantCamera preview w = " + previewSize[0].mWidth + " h = " + previewSize[0].mHeight);
        }
        return true;
    }

    @Deprecated // used for old Camera1 API only
    private Size chooseOptimalSize(ArrayList<Size> previewSizes) {
        ArrayList<Size> sortedSizes = new ArrayList<>();
        boolean allowBigSizeCamera = allowBigSizeCamera();
        int maxVideoSize = allowBigSizeCamera ? 1440 : 1200;
        if (Build.MANUFACTURER.equalsIgnoreCase("Samsung")) {
            //1440 lead to gl crashes on samsung s9
            maxVideoSize = 1200;
        }
        for (int i = 0; i < previewSizes.size(); i++) {
            if (Math.max(previewSizes.get(i).mHeight, previewSizes.get(i).mWidth) <= maxVideoSize && Math.min(previewSizes.get(i).mHeight, previewSizes.get(i).mWidth) >= 320) {
                sortedSizes.add(previewSizes.get(i));
            }
        }
        if (sortedSizes.isEmpty() || !allowBigSizeCamera()) {
            ArrayList<Size> sizes = sortedSizes;
            if (!sortedSizes.isEmpty()) {
                sizes = sortedSizes;
            } else {
                sizes = previewSizes;
            }
            if (Build.MANUFACTURER.equalsIgnoreCase("Xiaomi")) {
                return CameraController.chooseOptimalSize(sizes, 640, 480, aspectRatio, false);
            } else {
                return CameraController.chooseOptimalSize(sizes, 480, 270, aspectRatio, false);
            }
        }
        Collections.sort(sortedSizes, (o1, o2) -> {
            float a1 = Math.abs(1f - Math.min(o1.mHeight, o1.mWidth) / (float) Math.max(o1.mHeight, o1.mWidth));
            float a2 = Math.abs(1f - Math.min(o2.mHeight, o2.mWidth) / (float) Math.max(o2.mHeight, o2.mWidth));

            if (a1 < a2) {
                return -1;
            } else if (a1 > a2) {
                return 1;
            }
            return 0;
        });
        return sortedSizes.get(0);
    }

    @Deprecated // used for old Camera1 API only
    private boolean allowBigSizeCamera() {
        if (SharedConfig.bigCameraForRound) {
            return true;
        }
        if (SharedConfig.deviceIsAboveAverage()) {
            return true;
        }
        int devicePerformanceClass = Math.max(SharedConfig.getDevicePerformanceClass(), SharedConfig.getLegacyDevicePerformanceClass());
        if (devicePerformanceClass == SharedConfig.PERFORMANCE_CLASS_HIGH) {
            return true;
        }
        int hash = (Build.MANUFACTURER + " " + Build.DEVICE).toUpperCase().hashCode();
        for (int i = 0; i < ALLOW_BIG_CAMERA_WHITELIST.length; ++i) {
            if (ALLOW_BIG_CAMERA_WHITELIST[i] == hash) {
                return true;
            }
        }
        return false;
    }

    @Deprecated // used for old Camera1 API only
    public static boolean allowBigSizeCameraDebug() {
        int devicePerformanceClass = Math.max(SharedConfig.getDevicePerformanceClass(), SharedConfig.getLegacyDevicePerformanceClass());
        if (devicePerformanceClass == SharedConfig.PERFORMANCE_CLASS_HIGH) {
            return true;
        }
        int hash = (Build.MANUFACTURER + " " + Build.DEVICE).toUpperCase().hashCode();
        for (int i = 0; i < ALLOW_BIG_CAMERA_WHITELIST.length; ++i) {
            if (ALLOW_BIG_CAMERA_WHITELIST[i] == hash) {
                return true;
            }
        }
        return false;
    }

    private void createCamera(final int index, final SurfaceTexture surfaceTexture) {
        AndroidUtilities.runOnUIThread(() -> {
            if (cameraThread == null) {
                return;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("InstantCamera create camera session " + index);
            }

            if (useCamera2) {
                if (bothCameras) {
                    if (camera2Sessions[index] != null) {
                        camera2Sessions[index].open(surfaceTexture);
                    }
                } else {
                    if (index == 1) return;
                    cameraThread.setCurrentSession(camera2SessionCurrent);
                    camera2SessionCurrent.open(surfaceTexture);
                }
            } else {
                if (index == 1) return;
                surfaceTexture.setDefaultBufferSize(previewSize[0].getWidth(), previewSize[0].getHeight());
                cameraSession = new CameraSession(selectedCamera, previewSize[0], pictureSize, ImageFormat.JPEG, true);
                updateFlash();
                cameraThread.setCurrentSession(cameraSession);
                CameraController.getInstance().openRound(cameraSession, surfaceTexture, () -> {
                    if (cameraSession != null) {
                        updateFlash();

                        boolean updateScale = false;
                        try {
                            Camera.Size size = cameraSession.getCurrentPreviewSize();
                            if (size.width != previewSize[0].getWidth() || size.height != previewSize[0].getHeight()) {
                                previewSize[0] = new Size(size.width, size.height);
                                FileLog.d("InstantCamera change preview size to w = " + previewSize[0].getWidth() + " h = " + previewSize[0].getHeight());
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                        }

                        try {
                            Camera.Size size = cameraSession.getCurrentPictureSize();
                            if (size.width != pictureSize.getWidth() || size.height != pictureSize.getHeight()) {
                                pictureSize = new Size(size.width, size.height);
                                FileLog.d("InstantCamera change picture size to w = " + pictureSize.getWidth() + " h = " + pictureSize.getHeight());
                                updateScale = true;
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("InstantCamera camera initied");
                        }
                        cameraSession.setInitied();
                        applyLockedZoomToCamera();
                        if (updateScale) {
                            if (cameraThread != null) {
                                cameraThread.reinitForNewCamera();
                            }
                        }
                    }
                }, () -> {
                    if (cameraThread != null) {
                        cameraThread.setCurrentSession(cameraSession);
                    }
                });
            }
        });
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e(GLES20.glGetShaderInfoLog(shader));
            }
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    private Timer progressTimer;

    private void startProgressTimer() {
        if (progressTimer != null) {
            try {
                progressTimer.cancel();
                progressTimer = null;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        progressTimer = new Timer();
        progressTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        if (videoPlayer != null && videoEditedInfo != null && videoEditedInfo.endTime > 0 && videoPlayer.getCurrentPosition() >= videoEditedInfo.endTime) {
                            videoPlayer.seekTo(videoEditedInfo.startTime > 0 ? videoEditedInfo.startTime : 0);
                        }
                        // NagramX: drives the playback cursor on the preview timeline; duration is unset until the player is prepared
                        long duration = videoPlayer != null ? videoPlayer.getDuration() : 0;
                        if (duration > 0) {
                            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.videoPreviewProgressChanged, recordingGuid, videoPlayer.getCurrentPosition() / (float) duration);
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                });
            }
        }, 0, 17);
    }

    private void stopProgressTimer() {
        if (progressTimer != null) {
            try {
                progressTimer.cancel();
                progressTimer = null;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public void onPanTranslationUpdate(float y) {
        panTranslationY = y / 2f;
        updateTranslationY();
    }

    public TextureView getTextureView() {
        return textureView;
    }

    public void setIsMessageTransition(boolean isMessageTransition) {
        this.isMessageTransition = isMessageTransition;
    }

    public void resetCameraFile() {
        cameraFile = null;
    }

    private VideoRecorder videoEncoder;
    // NagramX: object identity alone can't tell "this recording" apart from a later one -- onDraw below
    // reuses the same VideoRecorder instance across a stop/restart until its async teardown nulls
    // videoEncoder, and a fast cancel-then-record-again can beat that null out. volatile since it's written
    // from the GL thread (startRecording) and read from the encoder thread later.
    private volatile int recordingGeneration;

    private Bitmap firstFrameThumb;
    private volatile int surfaceIndex;

    public class CameraGLThread extends DispatchQueue {

        private final static int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        private final static int EGL_OPENGL_ES2_BIT = 4;
        private SurfaceTexture surfaceTexture;
        private EGL10 egl10;
        private EGLDisplay eglDisplay;
        private EGLContext eglContext;
        private EGLSurface eglSurface;
        private boolean initied;

        private Object currentSession;

        private final SurfaceTexture[] cameraSurface = new SurfaceTexture[2];

        private final int DO_RENDER_MESSAGE = 0;
        private final int DO_SHUTDOWN_MESSAGE = 1;
        private final int DO_REINIT_MESSAGE = 2;
        private final int DO_SETSESSION_MESSAGE = 3;
        private final int DO_FLIP = 4;
        private final int DO_OPEN_SESSION = 5; // NagramX (#round-dual-camera-fix)

        private int drawProgram;
        private int vertexMatrixHandle;
        private int textureMatrixHandle;
        private int positionHandle;
        private int textureHandle;

        private boolean recording;

        private Integer cameraId = 0;

        private int surfaceWidth;
        private int surfaceHeight;

        public CameraGLThread(SurfaceTexture surface, int surfaceWidth, int surfaceHeight) {
            super("CameraGLThread");
            surfaceTexture = surface;

            this.surfaceWidth = surfaceWidth;
            this.surfaceHeight = surfaceHeight;
        }

        private void updateScale() {
            int width, height;
            if (previewSize[surfaceIndex] != null) {
                width = previewSize[surfaceIndex].getWidth();
                height = previewSize[surfaceIndex].getHeight();
            } else {
                return;
            }

            float scale = surfaceWidth / (float) Math.min(width, height);

            width *= scale;
            height *= scale;

            if (width == height) {
                scaleX = 1f;
                scaleY = 1f;
            } else if (width > height) {
                scaleX = 1.0f;
                scaleY = width / (float) surfaceHeight;
            } else {
                scaleX = height / (float) surfaceWidth;
                scaleY = 1.0f;
            }
            FileLog.d("InstantCamera camera scaleX = " + scaleX + " scaleY = " + scaleY);

        }

        private boolean initGL() {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("InstantCamera start init gl");
            }
            egl10 = (EGL10) EGLContext.getEGL();

            eglDisplay = egl10.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera eglGetDisplay failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                }
                finish();
                return false;
            }

            int[] version = new int[2];
            if (!egl10.eglInitialize(eglDisplay, version)) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera eglInitialize failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                }
                finish();
                return false;
            }

            int[] configsCount = new int[1];
            EGLConfig[] configs = new EGLConfig[1];
            int[] configSpec = new int[]{
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 0,
                    EGL10.EGL_DEPTH_SIZE, 0,
                    EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_NONE
            };
            EGLConfig eglConfig;
            if (!egl10.eglChooseConfig(eglDisplay, configSpec, configs, 1, configsCount)) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera eglChooseConfig failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                }
                finish();
                return false;
            } else if (configsCount[0] > 0) {
                eglConfig = configs[0];
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera eglConfig not initialized");
                }
                finish();
                return false;
            }

            int[] attrib_list = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE};
            eglContext = egl10.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attrib_list);
            if (eglContext == null) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera eglCreateContext failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                }
                finish();
                return false;
            }

            if (surfaceTexture instanceof SurfaceTexture) {
                eglSurface = egl10.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, null);
            } else {
                finish();
                return false;
            }

            if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera createWindowSurface failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                }
                finish();
                return false;
            }
            if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera eglMakeCurrent failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                }
                finish();
                return false;
            }

            updateScale();

            float tX = 1.0f / scaleX / 2.0f;
            float tY = 1.0f / scaleY / 2.0f;
            float[] verticesData = {
                    -1.0f, -1.0f, 0,
                    1.0f, -1.0f, 0,
                    -1.0f, 1.0f, 0,
                    1.0f, 1.0f, 0
            };
            float[] texData = {
                    0.5f - tX, 0.5f - tY,
                    0.5f + tX, 0.5f - tY,
                    0.5f - tX, 0.5f + tY,
                    0.5f + tX, 0.5f + tY
            };

            if (videoEncoder == null) {
                videoEncoder = new VideoRecorder();
            }

            vertexBuffer = ByteBuffer.allocateDirect(verticesData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertexBuffer.put(verticesData).position(0);

            textureBuffer = ByteBuffer.allocateDirect(texData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            textureBuffer.put(texData).position(0);

            android.opengl.Matrix.setIdentityM(mSTMatrix, 0);

            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SCREEN_SHADER);
            if (vertexShader != 0 && fragmentShader != 0) {
                drawProgram = GLES20.glCreateProgram();
                GLES20.glAttachShader(drawProgram, vertexShader);
                GLES20.glAttachShader(drawProgram, fragmentShader);
                GLES20.glLinkProgram(drawProgram);
                int[] linkStatus = new int[1];
                GLES20.glGetProgramiv(drawProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
                if (linkStatus[0] == 0) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("InstantCamera failed link shader");
                    }
                    GLES20.glDeleteProgram(drawProgram);
                    drawProgram = 0;
                } else {
                    positionHandle = GLES20.glGetAttribLocation(drawProgram, "aPosition");
                    textureHandle = GLES20.glGetAttribLocation(drawProgram, "aTextureCoord");
                    vertexMatrixHandle = GLES20.glGetUniformLocation(drawProgram, "uMVPMatrix");
                    textureMatrixHandle = GLES20.glGetUniformLocation(drawProgram, "uSTMatrix");
                }
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera failed creating shader");
                }
                finish();
                return false;
            }

            android.opengl.Matrix.setIdentityM(mMVPMatrix, 0);

            GLES20.glGenTextures(2, cameraTexture, 0);
            for (int a = 0; a < 2; ++a) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture[a]);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                cameraSurface[a] = new SurfaceTexture(cameraTexture[a]);
                final int i = a;
                cameraSurface[a].setOnFrameAvailableListener(surfaceTexture -> {
                    cameraTextureAvailable = true;
                    requestRender(i == 0, i == 1);
                });
                createCamera(a, cameraSurface[a]);
            }

            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("InstantCamera gl initied");
            }

            return true;
        }

        public void reinitForNewCamera() {
            Handler handler = getHandler();
            if (handler != null) {
                sendMessage(handler.obtainMessage(DO_REINIT_MESSAGE), 0);
            }
        }

        public void finish() {
            initied = false;
            if (cameraSurface != null) {
                for (int a = 0; a < 2; ++a) {
                    if (cameraSurface[a] != null) {
                        cameraSurface[a].setOnFrameAvailableListener(null);
                        cameraSurface[a].release();
                        cameraSurface[a] = null;
                    }
                }
            }
            cameraTextureAvailable = false;
            if (eglSurface != null && eglContext != null) {
                if (!eglContext.equals(egl10.eglGetCurrentContext()) || !eglSurface.equals(egl10.eglGetCurrentSurface(EGL10.EGL_DRAW))) {
                    egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
                }
                if (cameraTexture != null && cameraTexture[0] != Integer.MIN_VALUE) {
                    GLES20.glDeleteTextures(1, cameraTexture, 0);
                    cameraTexture[0] = Integer.MIN_VALUE;
                }
                if (cameraTexture != null && cameraTexture[1] != Integer.MIN_VALUE) {
                    GLES20.glDeleteTextures(1, cameraTexture, 1);
                    cameraTexture[1] = Integer.MIN_VALUE;
                }
            }
            if (eglSurface != null) {
                egl10.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                egl10.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = null;
            }
            if (eglContext != null) {
                egl10.eglDestroyContext(eglDisplay, eglContext);
                eglContext = null;
            }
            if (eglDisplay != null) {
                egl10.eglTerminate(eglDisplay);
                eglDisplay = null;
            }
        }

        public void setCurrentSession(CameraSession session) {
            Handler handler = getHandler();
            if (handler != null) {
                sendMessage(handler.obtainMessage(DO_SETSESSION_MESSAGE, session), 0);
            }
        }

        public void setCurrentSession(Camera2Session session) {
            Handler handler = getHandler();
            if (handler != null) {
                sendMessage(handler.obtainMessage(DO_SETSESSION_MESSAGE, session), 0);
            }
        }

        public void flipSurfaces() {
            Handler handler = getHandler();
            if (handler != null) {
                sendMessage(handler.obtainMessage(DO_FLIP), 0);
                requestRender(true, true);
            }
        }

        private void onDraw(Integer cameraId, boolean updateTexImage1, boolean updateTexImage2) {
            if (!initied || !this.cameraId.equals(cameraId)) {
                return;
            }

            if (!eglContext.equals(egl10.eglGetCurrentContext()) || !eglSurface.equals(egl10.eglGetCurrentSurface(EGL10.EGL_DRAW))) {
                if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("eglMakeCurrent failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                    }
                    return;
                }
            }
            try {
                if (updateTexImage1) {
                    if (cameraSurface[0] == null) {
                        return;
                    }
                    cameraSurface[0].updateTexImage();
                }
                if (updateTexImage2) {
                    if (cameraSurface[1] == null) {
                        return;
                    }
                    cameraSurface[1].updateTexImage();
                }
            } catch (RuntimeException e) {
                FileLog.e(e);
                return;
            }

            boolean captureFirstFrameThumb = false;
            VideoRecorder recorder = videoEncoder;
            if (!recording) {
                if (recorder == null) {
                    recorder = videoEncoder = new VideoRecorder();
                }
                if (recorder.started) {
                    if (!cameraReady) {
                        cameraReady = true;
                        AndroidUtilities.runOnUIThread(() -> textureOverlayView.animate().setDuration(120).alpha(0.0f).setInterpolator(new DecelerateInterpolator()).start());
                    }
                } else {
                    captureFirstFrameThumb = true;
                }
                recorder.startRecording(cameraFile, EGL14.eglGetCurrentContext());
                int orientation;
                if (currentSession instanceof CameraSession) {
                    orientation = ((CameraSession) currentSession).getCurrentOrientation();
                } else if (currentSession instanceof Camera2Session) {
                    orientation = ((Camera2Session) currentSession).getCurrentOrientation();
                } else {
                    orientation = 0;
                }
                if (orientation == 90 || orientation == 270) {
                    float temp = scaleX;
                    scaleX = scaleY;
                    scaleY = temp;
                }
                recording = true;
                updateFlash();
            }

            if (recorder != null && (surfaceIndex == 0 && updateTexImage1 || surfaceIndex == 1 && updateTexImage2)) {
                recorder.frameAvailable(cameraSurface[surfaceIndex], bothCameras ? surfaceIndex : cameraId, System.nanoTime());
            }

            cameraSurface[surfaceIndex].getTransformMatrix(mSTMatrix);

            GLES20.glUseProgram(drawProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture[surfaceIndex]);

            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);
            GLES20.glEnableVertexAttribArray(positionHandle);

            GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
            GLES20.glEnableVertexAttribArray(textureHandle);

            GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, mSTMatrix, 0);
            GLES20.glUniformMatrix4fv(vertexMatrixHandle, 1, false, mMVPMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(textureHandle);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glUseProgram(0);

            egl10.eglSwapBuffers(eglDisplay, eglSurface);

            if (captureFirstFrameThumb) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (textureView == null) {
                        return;
                    }
                    if (firstFrameThumb != null) {
                        firstFrameThumb.recycle();
                        firstFrameThumb = null;
                    }
                    firstFrameThumb = textureView.getBitmap();
                });
            }
        }

        @Override
        public void run() {
            initied = initGL();
            super.run();
        }

        @Override
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;

            switch (what) {
                case DO_RENDER_MESSAGE:
                    onDraw(inputMessage.arg1, (inputMessage.arg2 & 1) != 0, (inputMessage.arg2 & 2) != 0);
                    break;
                case DO_SHUTDOWN_MESSAGE:
                    finish();
                    if (recording && (!(inputMessage.obj instanceof SendOptions) || ((SendOptions) inputMessage.obj).ttl != -2) && videoEncoder != null) {
                        videoEncoder.stopRecording(inputMessage.arg1, inputMessage.obj instanceof SendOptions ? (SendOptions) inputMessage.obj : null);
                    } else if (!(inputMessage.obj instanceof SendOptions) || ((SendOptions) inputMessage.obj).ttl != -2) {
                        // NagramX (#round-video-restart-guard-fix): a terminal stop that queued nothing (no frame yet,
                        // or no recorder) gets no teardown post, so release what send()/cancel() set for it here
                        AndroidUtilities.runOnUIThread(() -> {
                            encoderTeardownPending = false;
                            cameraFileHandedOff = false;
                        });
                    }
                    Looper looper = Looper.myLooper();
                    if (looper != null) {
                        looper.quit();
                    }
                    break;
                case DO_REINIT_MESSAGE: {
                    if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("InstantCamera eglMakeCurrent failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                        }
                        return;
                    }

                    if (cameraSurface[0] != null) {
                        cameraSurface[0].getTransformMatrix(moldSTMatrix);
                        cameraSurface[0].setOnFrameAvailableListener(null);
                        cameraSurface[0].release();
                        oldCameraTexture[0] = cameraTexture[0];
                        cameraTextureAlpha = 0.0f;
                        cameraTexture[0] = 0;
                        oldTextureTextureBuffer = textureBuffer.duplicate();
                        oldTexturePreviewSize = previewSize[0];
                    }
                    cameraId++;
                    cameraReady = false;

                    GLES20.glGenTextures(1, cameraTexture, 0);
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture[0]);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                    cameraSurface[0] = new SurfaceTexture(cameraTexture[0]);
                    cameraSurface[0].setOnFrameAvailableListener(surfaceTexture -> requestRender(true, false));
                    createCamera(0, cameraSurface[0]);

                    updateScale();

                    float tX = 1.0f / scaleX / 2.0f;
                    float tY = 1.0f / scaleY / 2.0f;

                    float[] texData = {
                            0.5f - tX, 0.5f - tY,
                            0.5f + tX, 0.5f - tY,
                            0.5f - tX, 0.5f + tY,
                            0.5f + tX, 0.5f + tY
                    };

                    textureBuffer = ByteBuffer.allocateDirect(texData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    textureBuffer.put(texData).position(0);
                    break;
                }
                case DO_SETSESSION_MESSAGE: {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("InstantCamera set gl renderer session");
                    }
                    Object newSession = inputMessage.obj;
                    if (currentSession == newSession) {
                        int rotationAngle;
                        if (currentSession instanceof CameraSession) {
                            rotationAngle = ((CameraSession) currentSession).getWorldAngle();
                        } else if (currentSession instanceof Camera2Session) {
                            rotationAngle = ((Camera2Session) currentSession).getWorldAngle();
                        } else {
                            rotationAngle = 0;
                        }
                        android.opengl.Matrix.setIdentityM(mMVPMatrix, 0);
                        if (rotationAngle != 0) {
                            android.opengl.Matrix.rotateM(mMVPMatrix, 0, rotationAngle, 0, 0, 1);
                        }
                    } else {
                        currentSession = newSession;
                    }
                    break;
                }
                case DO_FLIP: {
                    surfaceIndex = 1 - surfaceIndex;

                    updateScale();

                    float tX = 1.0f / scaleX / 2.0f;
                    float tY = 1.0f / scaleY / 2.0f;

                    float[] texData = {
                            0.5f - tX, 0.5f - tY,
                            0.5f + tX, 0.5f - tY,
                            0.5f - tX, 0.5f + tY,
                            0.5f + tX, 0.5f + tY
                    };

                    textureBuffer = ByteBuffer.allocateDirect(texData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    textureBuffer.put(texData).position(0);
                    break;
                }
                case DO_OPEN_SESSION: // NagramX (#round-dual-camera-fix)
                    currentSession = inputMessage.obj;
                    if (inputMessage.arg1 != 0 && cameraSurface[0] != null) {
                        ((Camera2Session) inputMessage.obj).open(cameraSurface[0]);
                    }
                    break;
            }
        }

        // NagramX (#round-dual-camera-fix): hands the dual-fallback session to this thread, which owns cameraSurface,
        // and opens it on surface 0 when asked. The handler only exists once initGL returns, so until then retry on
        // the UI thread for as long as this thread and this session are still the live ones.
        public void handOverSession(Camera2Session session, boolean open) {
            Handler handler = getHandler();
            if (handler != null) {
                handler.sendMessage(handler.obtainMessage(DO_OPEN_SESSION, open ? 1 : 0, 0, session)); // straight to the handler: DispatchQueue.sendMessage waits on its latch
            } else {
                AndroidUtilities.runOnUIThread(() -> {
                    if (cameraThread == this && camera2SessionCurrent == session) {
                        handOverSession(session, open);
                    }
                }, 16);
            }
        }

        public void shutdown(int send, boolean notify, int scheduleDate, int scheduleRepeatPeriod, int ttl, long effectId) {
            Handler handler = getHandler();
            if (handler != null) {
                handler.removeMessages(DO_RENDER_MESSAGE);
                SendOptions options = new SendOptions(notify, scheduleDate, scheduleRepeatPeriod, ttl, effectId, 0);
                // NagramX: still on the UI thread here, same call stack as the dispatch that may have just
                // armed this -- read and clear before SendOptions crosses to the encoder thread
                options.limitStop = limitStopHapticArmed;
                limitStopHapticArmed = false;
                if (ttl != -2) {
                    encoderTeardownPending = true; // NagramX (#round-video-restart-guard-fix): every shutdown but a pause is a terminal stop
                }
                sendMessage(handler.obtainMessage(DO_SHUTDOWN_MESSAGE, send, 0, options), 0);
            }
        }

        public void requestRender(boolean updateTexImage1, boolean updateTexImage2) {
            Handler handler = getHandler();
            if (handler != null) {
                sendMessage(handler.obtainMessage(DO_RENDER_MESSAGE, cameraId, (updateTexImage1 ? 1 : 0) + (updateTexImage2 ? 2 : 0)), 0);
            }
        }
    }

    private static final int MSG_START_RECORDING = 0;
    private static final int MSG_STOP_RECORDING = 1;
    private static final int MSG_VIDEOFRAME_AVAILABLE = 2;
    private static final int MSG_AUDIOFRAME_AVAILABLE = 3;
    private static final int MSG_PAUSE_RECORDING = 4;
    private static final int MSG_RESUME_RECORDING = 5;
    private static final int MSG_ROLLOVER_RECORDING = 6; // NagramX

    private static class EncoderHandler extends Handler {
        private WeakReference<VideoRecorder> mWeakEncoder;

        public EncoderHandler(VideoRecorder encoder) {
            mWeakEncoder = new WeakReference<>(encoder);
        }

        @Override
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Object obj = inputMessage.obj;

            VideoRecorder encoder = mWeakEncoder.get();
            if (encoder == null) {
                return;
            }

            switch (what) {
                case MSG_START_RECORDING: {
                    try {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.e("InstantCamera start encoder");
                        }
                        encoder.prepareEncoder(inputMessage.arg1 == 1);
                    } catch (Exception e) {
                        FileLog.e(e);
                        if (inputMessage.arg1 == 1) {
                            // NagramX (#round-video-restart-guard-fix): a resumed prepare can fail while still paused, and
                            // the stop's paused single pass would delete the paused clip; just let any audio thread end
                            encoder.running = false;
                        } else {
                            encoder.handleStopRecording(0, null);
                        }
                        encoder.postPrepareFailed(); // NagramX (#round-video-restart-guard-fix)
                        Looper.myLooper().quit();
                    }
                    break;
                }
                case MSG_STOP_RECORDING: {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("InstantCamera stop encoder");
                    }
                    encoder.handleStopRecording(inputMessage.arg1, (SendOptions) inputMessage.obj);
                    break;
                }
                case MSG_PAUSE_RECORDING: {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("InstantCamera pause encoder");
                    }
                    encoder.handlePauseRecording();
                    break;
                }
                case MSG_RESUME_RECORDING: {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("InstantCamera resume encoder");
                    }
                    encoder.handleResumeRecording();
                    break;
                }
                case MSG_ROLLOVER_RECORDING: {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("InstantCamera roll encoder over to the next segment");
                    }
                    encoder.handleRollover(inputMessage.arg1, (SendOptions) inputMessage.obj);
                    break;
                }
                case MSG_VIDEOFRAME_AVAILABLE: {
                    long timestamp = (((long) inputMessage.arg1) << 32) | (((long) inputMessage.arg2) & 0xffffffffL);
                    Integer cameraId = (Integer) inputMessage.obj;
                    encoder.handleVideoFrameAvailable(timestamp, cameraId);
                    break;
                }
                case MSG_AUDIOFRAME_AVAILABLE: {
                    encoder.handleAudioFrameAvailable((AudioBufferInfo) inputMessage.obj);
                    break;
                }
            }
        }

        public void exit() {
            Looper.myLooper().quit();
        }
    }

    public static class SendOptions {
        boolean notify;
        int scheduleDate;
        int scheduleRepeatPeriod;
        int ttl;
        long effectId;
        long stars;
        // NagramX: set once in CameraGLThread.shutdown() below, read in handleStopRecording() to fire the
        // cap-stop haptic for exactly the auto-stop, not a manual send/stop that happens to share state==3
        boolean limitStop;

        public SendOptions(boolean notify, int scheduleDate, int scheduleRepeatPeriod, int ttl, long effectId, long stars) {
            this.notify = notify;
            this.scheduleDate = scheduleDate;
            this.scheduleRepeatPeriod = scheduleRepeatPeriod;
            this.ttl = ttl;
            this.effectId = effectId;
            this.stars = stars;
        }
    }

    public static class AudioBufferInfo {
        public final static int MAX_SAMPLES = 10;
        public ByteBuffer[] buffer = new ByteBuffer[MAX_SAMPLES];
        public long[] offset = new long[MAX_SAMPLES];
        public int[] read = new int[MAX_SAMPLES];
        public int results;
        public int lastWroteBuffer;
        public boolean last;

        public AudioBufferInfo() {
            for (int i = 0; i < MAX_SAMPLES; i++) {
                buffer[i] = ByteBuffer.allocateDirect(2048);
                buffer[i].order(ByteOrder.nativeOrder());
            }
        }
    }

    private class VideoRecorder implements Runnable {

        private static final String VIDEO_MIME_TYPE = "video/avc";
        private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
        private static final int FRAME_RATE = 30;
        private static final int IFRAME_INTERVAL = 1;

        private File videoFile;
        private File fileToWrite;
        private boolean writingToDifferentFile;
        private int videoWidth;
        private int videoHeight;
        private int videoBitrate;
        private boolean videoConvertFirstWrite = true;
        private boolean blendEnabled;

        private Surface surface;
        private android.opengl.EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private android.opengl.EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private android.opengl.EGLContext sharedEglContext;
        private android.opengl.EGLConfig eglConfig;
        private android.opengl.EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

        private MediaCodec videoEncoder;
        private MediaCodec audioEncoder;

        private int prependHeaderSize;
        private boolean firstEncode;

        private MediaCodec.BufferInfo videoBufferInfo;
        private MediaCodec.BufferInfo audioBufferInfo;
        private MP4Builder mediaMuxer;
        private ArrayList<AudioBufferInfo> buffersToWrite = new ArrayList<>();
        private int videoTrackIndex = -5;
        private int audioTrackIndex = -5;
        // NagramX: kept so a segment rollover can re-add both tracks to the next file's muxer
        private MediaFormat videoTrackFormat;
        private MediaFormat audioTrackFormat;
        private volatile File rolloverFile;
        private boolean waitingForSyncFrame;

        private long lastCommitedFrameTime;
        private long audioStartTime = -1;
        // NagramX: audioStartTime is now the PTS base for the whole recording (see handleRollover), so
        // the 60s per-segment cutoff in handleAudioFrameAvailable needs its own start marker that does
        // get rebased at every rollover
        private long segmentAudioStartTime = -1;
        private boolean firstVideoFrameSincePause;

        private long currentTimestamp = 0;
        private long lastTimestamp = -1;

        private volatile EncoderHandler handler;

        private final Object sync = new Object();
        public volatile boolean ready;
        private volatile boolean running;
        private volatile int sendWhenDone;
        private volatile SendOptions sendWhenDoneOptions;
        private long skippedTime;
        private boolean skippedFirst;

        private long desyncTime;
        private long videoFirst = -1;
        private long videoLast;
        private long videoLastDt;
        private long videoDiff;
        private long prevVideoLast = -1;
        private long audioFirst = -1;
        private long audioLast = -1;
        private long audioLastDt = 0;
        private long prevAudioLast = -1;
        private long audioDiff;
        private boolean audioStopedByTime;
        // NagramX: true once the audio encoder has been handed an end-of-stream input -- either the normal
        // one queued in feedAudioToEncoder or the forced one in handleStopRecording. Gates the forced EOS so
        // it can't signal twice (queueing input after EOS throws). Encoder-thread only, like the rest of the
        // audio pipeline it guards; reset per fresh recording in prepareEncoder.
        private boolean audioEosSignaled;

        private int drawProgram;
        private int vertexMatrixHandle;
        private int textureMatrixHandle;
        private int positionHandle;
        private int textureHandle;
        private int resolutionHandle;
        private int previewSizeHandle;
        private int texelSizeHandle;
        private int alphaHandle;
        private int zeroTimeStamps;
        private Integer lastCameraId = 0;
        private InstantCameraVideoEncoderOverlayHelper overlayHelper;

        private AudioRecord audioRecorder;

        private ArrayBlockingQueue<AudioBufferInfo> buffers = new ArrayBlockingQueue<>(10);
        private ArrayList<Bitmap> keyframeThumbs = new ArrayList<>();
        private DispatchQueue generateKeyframeThumbsQueue;
        private int frameCount;

        DispatchQueue fileWriteQueue;

        private volatile boolean pauseRecorder;
        private Runnable recorderRunnable = new Runnable() {

            @RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public void run() {
                long audioPresentationTimeUs = -1;
                int readResult;
                boolean done = false;
                AudioTimestamp audioTimestamp = new AudioTimestamp();
                boolean shouldUseTimestamp = true;

                while (!done) {
                    if ((!running || pauseRecorder) && audioRecorder.getRecordingState() != AudioRecord.RECORDSTATE_STOPPED) {
                        try {
                            audioRecorder.stop();
                        } catch (Exception e) {
                            done = true;
                        }
                        if (sendWhenDone == 0) {
                            break;
                        }
                    }
                    AudioBufferInfo buffer;
                    if (buffers.isEmpty()) {
                        try {
                            buffer = new AudioBufferInfo();
                        } catch (OutOfMemoryError error) {
                            System.gc();
                            buffer = new AudioBufferInfo();
                        }
                    } else {
                        buffer = buffers.poll();
                    }
                    buffer.lastWroteBuffer = 0;
                    buffer.results = AudioBufferInfo.MAX_SAMPLES;
                    for (int a = 0; a < AudioBufferInfo.MAX_SAMPLES; a++) {
                        if (audioPresentationTimeUs == -1 && !shouldUseTimestamp) {
                            audioPresentationTimeUs = System.nanoTime() / 1000;
                        }

                        ByteBuffer byteBuffer = buffer.buffer[a];
                        byteBuffer.rewind();
                        readResult = audioRecorder.read(byteBuffer, 2048);
                        if (readResult > 0 && a % 2 == 0) {
                            byteBuffer.limit(readResult);
                            double s = 0;
                            for (int i = 0; i < readResult / 2; i++) {
                                short p = byteBuffer.getShort();
                                s += p * p;
                            }
                            double amplitude = Math.sqrt(s / readResult / 2);
                            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordProgressChanged, recordingGuid, amplitude));
                            byteBuffer.position(0);
                        }
                        if (readResult <= 0) {
                            buffer.results = a;
                            if (!running) {
                                buffer.last = true;
                            }
                            break;
                        }
                        long timestamp;
                        if (shouldUseTimestamp) {
                            try {
                                audioRecorder.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC);
                                timestamp = audioTimestamp.nanoTime / 1000;
                            } catch (Exception e) {
                                FileLog.e(e);
                                shouldUseTimestamp = false;
                                timestamp = audioPresentationTimeUs = System.nanoTime() / 1000;
                            }
                        } else {
                            timestamp = audioPresentationTimeUs;
                        }
                        buffer.offset[a] = timestamp;

                        buffer.read[a] = readResult;
                        int bufferDurationUs = 1000000 * readResult / audioSampleRate / 2;
                        if (!shouldUseTimestamp) {
                            audioPresentationTimeUs += bufferDurationUs;
                        }
                    }
                    if (buffer.results >= 0 || buffer.last) {
                        if (!running && buffer.results < AudioBufferInfo.MAX_SAMPLES) {
                            done = true;
                        }
                        handler.sendMessage(handler.obtainMessage(MSG_AUDIOFRAME_AVAILABLE, buffer));
                    } else {
                        if (!running) {
                            done = true;
                        } else {
                            try {
                                buffers.put(buffer);
                            } catch (Exception ignore) {

                            }
                        }
                    }
                }
                try {
                    audioRecorder.release();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                if (!pauseRecorder) {
                    handler.sendMessage(handler.obtainMessage(MSG_STOP_RECORDING, sendWhenDone, 0, sendWhenDoneOptions));
                }
            }
        };

        private boolean started;
        // NagramX: this recording's stamp, bumped in startRecording() -- which also runs on every
        // pause/resume, not just a fresh recording, so this moves more often than "one recording" implies
        // (harmless: a rollover can't be mid-flight while paused). A rollover or teardown callback captures
        // it into a local before posting so a later bump -- even one reusing this same instance -- makes a
        // stale callback's snapshot stop matching. volatile: written on the GL thread, read on the encoder
        // thread.
        private volatile int recordingToken;
        // NagramX: handleStopRecording runs twice for one real stop -- once when the request first arrives
        // (running still true, no teardown yet) and again once the audio thread notices running went false
        // and re-posts the stop message (this is the call that actually tears down and posts the teardown
        // runnable). recordingToken can't be read fresh in that second call: a fast stop-then-record-again
        // can reuse this instance and move recordingToken on in the gap between the two calls. Stashed once,
        // on the first call, while running is still true and no new recording can have started yet.
        private int stoppedGeneration;
        // NagramX: the draft generation a round-video finalize belongs to, written on the UI thread when a stop
        // or pause is requested (send()/togglePause, via this recorder). ChatActivity bumps its counter when a
        // new recording starts and when a passcode-lock rebuild recreates the view. This is the live value only:
        // this recorder instance is reused across stop/restart (see the field comment at recordingToken), so a
        // later request would overwrite it -- it's snapshotted into finalizeGeneration once per sequence and the
        // delivery post reads that, never this. volatile: UI write, encoder-thread read.
        private volatile int finalizeDraftToken;
        // NagramX: finalizeDraftToken pinned once when this stop/pause sequence begins, same discipline as
        // stoppedGeneration -- the audioDidSent finalize post runs async off the encoder thread and must carry
        // the generation requested at that instant, not whatever a newer recording reusing this instance has
        // since written into finalizeDraftToken.
        private int finalizeGeneration;

        public void startRecording(File outputFile, android.opengl.EGLContext sharedContext) {
            recordingToken = ++InstantCameraView.this.recordingGeneration;
            if (started && (handler != null && handler.getLooper() != null && handler.getLooper().getThread() != null && handler.getLooper().getThread().isAlive())) {
                sharedEglContext = sharedContext;
                handler.sendMessage(handler.obtainMessage(MSG_START_RECORDING, 1, 0));
            }

            started = true;
            int resolution = MessagesController.getInstance(currentAccount).roundVideoSize;
            int bitrate = MessagesController.getInstance(currentAccount).roundVideoBitrate * 1024;
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.stopAllHeavyOperations, 512);
            });

            videoFile = outputFile;
            videoWidth = resolution;
            videoHeight = resolution;
            videoBitrate = bitrate;
            sharedEglContext = sharedContext;

            synchronized (sync) {
                if (running) {
                    return;
                }
                running = true;
                Thread thread = new Thread(this, "TextureMovieEncoder");
                thread.setPriority(Thread.MAX_PRIORITY);
                thread.start();
                while (!ready) {
                    try {
                        sync.wait();
                    } catch (InterruptedException ie) {
                        // ignore
                    }
                }
            }

            if (WRITE_TO_FILE_IN_BACKGROUND) {
                fileWriteQueue = new DispatchQueue("IVR_FileWriteQueue");
                fileWriteQueue.setPriority(Thread.MAX_PRIORITY);
            }

            keyframeThumbs.clear();
            frameCount = 0;
            DispatchQueue queue = generateKeyframeThumbsQueue;
            if (queue != null) {
                queue.cleanupQueue();
                queue.recycle();
            }
            generateKeyframeThumbsQueue = new DispatchQueue("keyframes_thumb_queue");
            handler.sendMessage(handler.obtainMessage(MSG_START_RECORDING));
        }

        public void stopRecording(int send, SendOptions options) {
            handler.sendMessage(handler.obtainMessage(MSG_STOP_RECORDING, send, 0, options));
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.startAllHeavyOperations, 512);
            });
        }

        // NagramX (#round-video-restart-guard-fix): a failed prepare quits the looper before the stop's second pass,
        // so the teardown post never comes. Drop this recorder and release the admission flag here instead.
        private void postPrepareFailed() {
            final int token = recordingToken;
            AndroidUtilities.runOnUIThread(() -> {
                if (InstantCameraView.this.recordingGeneration == token) {
                    InstantCameraView.this.videoEncoder = null;
                }
                encoderTeardownPending = false;
            });
        }

        public void pause() {
            handler.sendMessage(handler.obtainMessage(MSG_PAUSE_RECORDING));
        }

        public void resume() {
            handler.sendMessage(handler.obtainMessage(MSG_RESUME_RECORDING));
        }

        // NagramX: infinite video message
        public void rollOver(File nextFile, long duration, SendOptions options) {
            rolloverFile = nextFile;
            handler.sendMessage(handler.obtainMessage(MSG_ROLLOVER_RECORDING, (int) duration, 0, options));
        }

        long prevTimestamp;
        public void frameAvailable(SurfaceTexture st, Integer cameraId, long timestampInternal) {
            synchronized (sync) {
                if (!ready) {
                    return;
                }
            }

            long timestamp = st.getTimestamp();
            if (timestamp == 0) {
                zeroTimeStamps++;
                if (zeroTimeStamps > 1) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("InstantCamera fix timestamp enabled");
                    }
                    timestamp = timestampInternal;
                } else {
                    return;
                }
            } else {
                zeroTimeStamps = 0;
            }
            prevTimestamp = timestamp;
            handler.sendMessage(handler.obtainMessage(MSG_VIDEOFRAME_AVAILABLE, (int) (timestamp >> 32), (int) timestamp, cameraId));
        }

        @Override
        public void run() {
            Looper.prepare();
            synchronized (sync) {
                handler = new EncoderHandler(this);
                ready = true;
                sync.notify();
            }
            Looper.loop();

            synchronized (sync) {
                ready = false;
            }
        }

        private void handleAudioFrameAvailable(AudioBufferInfo input) {
            if (pauseRecorder) {
                return;
            }
            if (audioStopedByTime) {
                return;
            }
            buffersToWrite.add(input);
            if (audioFirst == -1) {
                if (videoFirst == -1) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("InstantCamera video record not yet started");
                    }
                    return;
                }
                while (true) {
                    boolean ok = false;
                    for (int a = 0; a < input.results; a++) {
                        if (a == 0 && Math.abs(videoFirst - input.offset[a]) > 10_000_000L) {
                            desyncTime = videoFirst - input.offset[a];
                            audioFirst = input.offset[a];
                            ok = true;
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d("InstantCamera detected desync between audio and video " + desyncTime);
                            }
                            break;
                        }
                        if (input.offset[a] >= videoFirst) {
                            input.lastWroteBuffer = a;
                            audioFirst = input.offset[a];
                            ok = true;
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d("InstantCamera found first audio frame at " + a + " timestamp = " + input.offset[a]);
                            }
                            break;
                        } else {
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d("InstantCamera ignore first audio frame at " + a + " timestamp = " + input.offset[a]);
                            }
                        }
                    }
                    if (!ok) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("InstantCamera first audio frame not found, removing buffers " + input.results);
                        }
                        buffersToWrite.remove(input);
                    } else {
                        break;
                    }
                    if (!buffersToWrite.isEmpty()) {
                        input = buffersToWrite.get(0);
                    } else {
                        return;
                    }
                }
            }

            if (audioStartTime == -1) {
                audioStartTime = input.offset[input.lastWroteBuffer];
            }
            if (buffersToWrite.size() > 1) {
                input = buffersToWrite.get(0);
            }
            // NagramX: segmentAudioStartTime is the per-segment start (handleRollover resets it to -1),
            // separate from audioStartTime which stays continuous as the PTS base. Read after the backlog
            // reassignment above so it pins to this segment's actual first buffer, not the raw input
            // argument. Skip when input.results == 0 -- that buffer's offset[0] is stale from a previous
            // round, not a real timestamp yet.
            if (segmentAudioStartTime == -1 && input.results > 0) {
                segmentAudioStartTime = input.offset[input.lastWroteBuffer];
            }
            try {
                drainEncoder(false);
            } catch (Exception e) {
                FileLog.e(e);
            }
            feedAudioToEncoder(input);
        }

        // NagramX: pulled out of handleAudioFrameAvailable so handleRollover can flush whatever PCM is
        // still queued in buffersToWrite into segment N's encoder before its muxer closes -- otherwise
        // that tail never reaches file N and gets discarded by segment N+1 as if it belonged before the
        // (rebased) segment start. Must not be called from inside drainEncoder: drainEncoder already runs
        // mid-loop from here, and this mutates buffersToWrite / recycles buffers that the AudioRecord
        // thread refills from concurrently.
        private void feedAudioToEncoder(AudioBufferInfo input) {
            try {
                boolean isLast = false;
                int stalledDequeues = 0;
                long stallDeadline = 0;
                while (input != null) {
                    int inputBufferIndex = audioEncoder.dequeueInputBuffer(0);
                    if (inputBufferIndex < 0) {
                        // NagramX: the codec can have no free input slot for a moment, and handleRollover's
                        // flush can hand this more backlog at once than the steady per-frame caller usually
                        // does. Bounded by both a retry count and a wall clock, since each retry's
                        // drainEncoder(false) can itself block up to ~10ms on the video dequeue timeout --
                        // the count alone doesn't cap how long a genuinely stuck codec holds this thread
                        // (and therefore the camera).
                        if (stallDeadline == 0) {
                            stallDeadline = SystemClock.elapsedRealtime() + 500;
                        }
                        if (++stalledDequeues > 50 || SystemClock.elapsedRealtime() > stallDeadline) {
                            FileLog.e(new RuntimeException("InstantCamera audio encoder produced no input buffer, dropping remaining backlog"));
                            break;
                        }
                        try {
                            drainEncoder(false);
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                        continue;
                    }
                    stalledDequeues = 0;
                    stallDeadline = 0;
                    ByteBuffer inputBuffer;
                    inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
                    long startWriteTime = input.offset[input.lastWroteBuffer];
                    for (int a = input.lastWroteBuffer; a <= input.results; a++) {
                        if (a < input.results) {
                            long totalTime = input.offset[a] - segmentAudioStartTime;
                            if (!running && segmentAudioStartTime != -1 && (input.offset[a] >= videoLast - desyncTime || totalTime >= 60_000000)) {
                                if (BuildVars.LOGS_ENABLED) {
                                    if (totalTime >= 60_000000) {
                                        FileLog.d("InstantCamera stop audio encoding because recorded time more than 60s");
                                    } else {
                                        FileLog.d("InstantCamera stop audio encoding because of stoped video recording at " + input.offset[a] + " last video " + videoLast);
                                    }

                                }
                                audioStopedByTime = true;
                                isLast = true;
                                input = null;
                                buffersToWrite.clear();
                                break;
                            }
                            if (inputBuffer.remaining() < input.read[a]) {
                                input.lastWroteBuffer = a;
                                input = null;
                                break;
                            }
                            inputBuffer.put(input.buffer[a]);
                        }
                        if (a >= input.results - 1) {
                            buffersToWrite.remove(input);
                            if (running) {
                                // NagramX: offer(), not put() -- handleRollover's flush also reaches this method
                                // while still running, on the encoder thread, and the pool is otherwise only
                                // drained by a non-blocking poll() on the recorder thread. put() could block this
                                // thread if the pool happened to be at capacity; a dropped buffer just costs one
                                // more allocation later.
                                buffers.offer(input);
                            }
                            if (!buffersToWrite.isEmpty()) {
                                input = buffersToWrite.get(0);
                                // NagramX: `a` belongs to the buffer we just finished, not the new one. A
                                // single encoder input buffer can span two AudioBufferInfos when the first
                                // one wasn't full (KEY_MAX_INPUT_SIZE only covers one MAX_SAMPLES record), so
                                // without this the next samples copied would start from the old buffer's
                                // tail index instead of the new buffer's own lastWroteBuffer.
                                a = input.lastWroteBuffer - 1;
                            } else {
                                isLast = input.last;
                                input = null;
                                break;
                            }
                        }
                    }
                    long time = startWriteTime == 0 ? 0 : startWriteTime - audioStartTime;
                    long realtime = time;
                    if (prevAudioLast >= 0) {
                        time += prevAudioLast;
                    }
                    audioLastDt = time - audioLast;
                    audioLast = time;
                    audioEncoder.queueInputBuffer(inputBufferIndex, 0, inputBuffer.position(), time, isLast ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    if (isLast) {
                        audioEosSignaled = true; // NagramX: normal EOS went in; handleStopRecording must not force a second
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        private void handleVideoFrameAvailable(long timestampNanos, Integer cameraId) {
            if (pauseRecorder || !cameraTextureAvailable) {
                return;
            }
            try {
                drainEncoder(false);
            } catch (Exception e) {
                FileLog.e(e);
            }
            long dt, alphaDt;
            boolean cameraChanged = false;
            if (!lastCameraId.equals(cameraId)) {
                cameraChanged = true;
                lastCameraId = cameraId;
            }
            if (prevVideoLast >= 0) {
                if (videoDiff == -1) {
                    videoDiff = timestampNanos - prevVideoLast;
                }
                timestampNanos -= videoDiff;
            }
            if (cameraChanged || lastTimestamp == -1) {
                if (currentTimestamp != 0 && !firstVideoFrameSincePause) {
                    //real dt lead to asynchron aduio and video
                    //surface may return wrong measured timestamp so big or negative
                    // `\_(._.)_/`
                    long dtTimestamps = (timestampNanos - lastTimestamp);
                    long dtReal = (System.currentTimeMillis() - lastCommitedFrameTime) * 1000000;
                    if (dtTimestamps < 0 || Math.abs(dtReal - dtTimestamps) > 100_000_000) {
                        dt = dtReal;
                    } else {
                        dt = dtTimestamps;
                    }
                    if (dt < 0) {
                        dt = 0;
                    }
                    alphaDt = 0;
                } else {
                    alphaDt = dt = 0;
                }
                lastTimestamp = timestampNanos;
            } else {
                alphaDt = dt = (timestampNanos - lastTimestamp);
                lastTimestamp = timestampNanos;
            }
            firstVideoFrameSincePause = false;
            lastCommitedFrameTime = System.currentTimeMillis();
            if (!skippedFirst) {
                skippedTime += dt;
                if (skippedTime < 200000000) {
                    return;
                }
                skippedFirst = true;
            }
            currentTimestamp += dt;
            if (videoFirst == -1) {
                videoFirst = timestampNanos / 1000;
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("InstantCamera first video frame was at " + videoFirst);
                }
            }
            videoLastDt = timestampNanos - videoLast;
            videoLast = timestampNanos;

            FloatBuffer textureBuffer = InstantCameraView.this.textureBuffer;
            FloatBuffer vertexBuffer = InstantCameraView.this.vertexBuffer;
            FloatBuffer oldTextureBuffer = oldTextureTextureBuffer;
            if (textureBuffer == null || vertexBuffer == null) {
                FileLog.d("InstantCamera handleVideoFrameAvailable skip frame " + textureBuffer + " " + vertexBuffer);
                return;
            }

            if (overlayHelper != null) {
                overlayHelper.bind();
            }

            GLES20.glUseProgram(drawProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
            GLES20.glEnableVertexAttribArray(textureHandle);
            GLES20.glUniformMatrix4fv(vertexMatrixHandle, 1, false, mMVPMatrix, 0);

            GLES20.glUniform2f(resolutionHandle, videoWidth, videoHeight);

            if (oldCameraTexture[0] != 0 && oldTextureBuffer != null && !bothCameras) {
                if (!blendEnabled) {
                    GLES20.glEnable(GLES20.GL_BLEND);
                    blendEnabled = true;
                }
                if (oldTexturePreviewSize != null) {
                    GLES20.glUniform2f(previewSizeHandle, oldTexturePreviewSize.getWidth(), oldTexturePreviewSize.getHeight());
                }
                GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, oldTextureBuffer);

                GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, moldSTMatrix, 0);
                GLES20.glUniform1f(alphaHandle, 1.0f);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oldCameraTexture[0]);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }

            if (previewSize != null) {
                GLES20.glUniform2f(previewSizeHandle, previewSize[surfaceIndex].getWidth(), previewSize[surfaceIndex].getHeight());
                GLES20.glUniform2f(texelSizeHandle, (float) 1f / previewSize[surfaceIndex].getWidth() / 2f, (float) 1f / previewSize[surfaceIndex].getHeight() / 2f);
            }

            final int tex = cameraTexture[surfaceIndex];
            if (tex != Integer.MIN_VALUE) {
                GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, mSTMatrix, 0);
                GLES20.glUniform1f(alphaHandle, cameraTextureAlpha);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(textureHandle);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glUseProgram(0);

            if (overlayHelper != null) {
                overlayHelper.render();
                if (blendEnabled) {
                    GLES20.glEnable(GLES20.GL_BLEND);
                }
            }

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, currentTimestamp);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);

            createKeyframeThumb();
            frameCount++;

            if (oldCameraTexture[0] != 0 && cameraTextureAlpha < 1.0f && !bothCameras) {
                cameraTextureAlpha += alphaDt / 200000000.0f;
                if (cameraTextureAlpha > 1) {
                    GLES20.glDisable(GLES20.GL_BLEND);
                    blendEnabled = false;
                    cameraTextureAlpha = 1;
                    GLES20.glDeleteTextures(1, oldCameraTexture, 0);
                    oldCameraTexture[0] = 0;
                    if (!cameraReady) {
                        cameraReady = true;
                        AndroidUtilities.runOnUIThread(() -> textureOverlayView.animate().setDuration(120).alpha(0.0f).setInterpolator(new DecelerateInterpolator()).start());
                    }
                }
            } else if (!cameraReady) {
                cameraReady = true;
                AndroidUtilities.runOnUIThread(() -> textureOverlayView.animate().setDuration(120).alpha(0.0f).setInterpolator(new DecelerateInterpolator()).start());
            }
        }

        private void createKeyframeThumb() {
            DispatchQueue queue = generateKeyframeThumbsQueue;
            if (queue != null && SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_HIGH && frameCount % 33 == 0) {
                GenerateKeyframeThumbTask task = new GenerateKeyframeThumbTask();
                queue.postRunnable(task);
            }
        }

        private class GenerateKeyframeThumbTask implements Runnable {
            @Override
            public void run() {
                final TextureView textureView = InstantCameraView.this.textureView;
                if (textureView != null) {
                    try {
                        final Bitmap bitmap = textureView.getBitmap(dp(56), dp(56));
                        AndroidUtilities.runOnUIThread(() -> {
                            if ((bitmap == null || bitmap.getPixel(0, 0) == 0) && keyframeThumbs.size() > 1) {
                                keyframeThumbs.add(keyframeThumbs.get(keyframeThumbs.size() - 1));
                            } else {
                                keyframeThumbs.add(bitmap);
                            }
                        });
                    } catch (Exception e) {
                        FileLog.e(e);
                    }

                }
            }
        }

        private void handlePauseRecording() {
            // NagramX: pin the finalize's draft generation at request time -- before drainEncoder/finishMovie --
            // so the async audioDidSent post at the tail carries it, not whatever the live field was overwritten
            // to by a later recording reusing this instance. Runs once per pause (unlike the stop path), so it
            // needs no running-guard.
            finalizeGeneration = finalizeDraftToken;
            pauseRecorder = true;
            if (previewFile != null) {
                previewFile.delete();
                previewFile = null;
            }
            previewFile = StoryEntry.makeCacheFile(currentAccount, true);
            try {
                FileLog.d("InstantCamera handlePauseRecording drain encoders");
                drainEncoder(false);
            } catch (Exception e) {
                FileLog.e(e);
            }
//            if (videoEncoder != null) {
//                try {
//                    videoEncoder.stop();
//                    videoEncoder.release();
//                    videoEncoder = null;
//                } catch (Exception e) {
//                    FileLog.e(e);
//                }
//            }
//            if (audioEncoder != null) {
//                try {
//                    audioEncoder.stop();
//                    audioEncoder.release();
//                    audioEncoder = null;
//
//                    setBluetoothScoOn(false);
//                } catch (Exception e) {
//                    FileLog.e(e);
//                }
//            }
            // NagramX (#video-draft-guard): track whether finishMovie() actually completed (returned without
            // throwing), the same positive signal finishMuxer uses on the stop path. Set inside the write runnable
            // before countDown, so the await below happens-after it and the read is safe. A broken finalize must
            // not be minted a draft id, or it would persist and unlock the previous good record's file. finishMovie
            // succeeding doesn't prove the file has video either, so the mint below also requires videoTrackIndex.
            final boolean[] finalizeSuccess = {false};
            if (mediaMuxer != null) {
                if (WRITE_TO_FILE_IN_BACKGROUND) {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    fileWriteQueue.postRunnable(() -> {
                        try {
                            mediaMuxer.finishMovie(previewFile);
                            finalizeSuccess[0] = true;
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        countDownLatch.countDown();
                    });
                    try {
                        countDownLatch.await();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    try {
                        mediaMuxer.finishMovie(previewFile);
                        finalizeSuccess[0] = true;
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
            // videoTrackIndex (-5 sentinel unless a video track was added) is read here on the encoder thread that
            // writes it, folded into finalizeOk before the async UI post -- never read across the thread boundary.
            final boolean finalizeOk = finalizeSuccess[0] && videoTrackIndex != -5;
//            FileLoader.getInstance(currentAccount).cancelFileUpload(videoFile.getAbsolutePath(), false);
            // NagramX: capture the pin into a local before the async boundary, same idiom as capturedGeneration
            // in handleStopRecording -- the post must carry the generation pinned here, not re-read the field at
            // lambda-run time.
            final int capturedFinalizeGeneration = finalizeGeneration;
            AndroidUtilities.runOnUIThread(() -> {
                videoEditedInfo = new VideoEditedInfo();
                videoEditedInfo.roundVideo = true;
                videoEditedInfo.startTime = -1;
                videoEditedInfo.endTime = -1;
                videoEditedInfo.file = file;
                videoEditedInfo.encryptedFile = encryptedFile;
                videoEditedInfo.key = key;
                videoEditedInfo.iv = iv;
                videoEditedInfo.estimatedSize = Math.max(1, size);
                videoEditedInfo.framerate = 25;
                videoEditedInfo.resultWidth = videoEditedInfo.originalWidth = 360;
                videoEditedInfo.resultHeight = videoEditedInfo.originalHeight = 360;
                videoEditedInfo.originalPath = previewFile.getAbsolutePath();
                setupVideoPlayer(previewFile);
                videoEditedInfo.estimatedDuration = recordedTime;
                // NagramX (#video-draft-guard): same draft-identity stamp as the stop producer, so a paused-then-
                // previewed clip persists and restores by id like a fully-stopped one. Only when finalize succeeded;
                // a 0 id leaves the clip previewable but unpersisted (save()'s reject sentinel), superseding nothing.
                videoEditedInfo.naxDraftId = finalizeOk ? com.dazewell.gram.helpers.VideoDraftStore.newId() : 0;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.audioDidSent, recordingGuid, videoEditedInfo, previewFile.getAbsolutePath(), keyframeThumbs, capturedFinalizeGeneration);
            });
        }

        private void handleResumeRecording() {
            pauseRecorder = false;
        }

        // NagramX: maps the Camera-settings cutoff level to a pulse, shared by both cutoff sites below
        // (this rollover and handleStopRecording's cap auto-stop) so one can't drift from the other.
        // InstantCameraView.this, not `this` (VideoRecorder isn't a View) -- needed as the
        // performHapticFeedback fallback target if the Vibrator route comes back dead.
        private void fireCutVibration() {
            RecordingLimitVibration.fire(NaConfig.INSTANCE.getVideoMessagesCutVibration().Int(), InstantCameraView.this);
        }

        // NagramX: infinite video message: close the current file, hand it off to be sent, and open the next
        // one on the same encoders. Nothing is stopped or released here, so the camera never blinks.
        private void handleRollover(long segmentDuration, SendOptions sendOptions) {
            final File nextFile = rolloverFile;
            if (!running || pauseRecorder || cancelled || mediaMuxer == null || nextFile == null || videoTrackFormat == null) {
                if (nextFile != null) {
                    rolloverFile = null;
                    // the UI side already pointed cameraFile at the file we're not going to open, so put it
                    // back on the one still being written or a later send would reach for something empty
                    final File stillWriting = videoFile;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (cameraFile == nextFile) {
                            cameraFile = stillWriting;
                            recordStartTime = System.currentTimeMillis() - segmentDuration;
                            recordedTime = segmentDuration;
                            progress = Math.min(1f, segmentDuration / 60000.0f);
                            updateInfiniteButton();
                        }
                        AutoDeleteMediaTask.unlockFile(nextFile);
                    });
                }
                return;
            }
            // NagramX: snapshot this recording's stamp now, before any of the flush/drain/finishMuxer work
            // below that can block for a while -- a fast stop-then-record-again can reuse this VideoRecorder
            // instance and bump recordingToken while that work is still running, and reading it only
            // afterward would pick up the new recording's stamp instead of this one's. segmentFile is
            // snapshotted here for the same reason: startRecording() reassigns videoFile straight from the
            // GL thread (not through this thread's handler), so reading it after the flush/drain instead of
            // now would risk grabbing the next recording's file instead of this segment's.
            final int capturedGeneration = recordingToken;
            final File segmentFile = videoFile;
            // NagramX: push whatever PCM arrived just before the cut into segment N's encoder and drain its
            // output (drainRolloverAudioTail below) before finishMuxer, so the tail lands in file N instead
            // of surfacing later in file N+1 with file N's timestamps. running is still true, so
            // feedAudioToEncoder's 60s-cutoff branch can't fire and truncate this early. One call only
            // fills the encoder's next free input slot, so loop until the backlog is actually gone --
            // bounded by feedAudioToEncoder's own stall guard if the encoder itself is stuck, not by a
            // fixed attempt count.
            AudioBufferInfo previousHead = null;
            int previousHeadPosition = -1;
            while (!buffersToWrite.isEmpty()) {
                AudioBufferInfo head = buffersToWrite.get(0);
                if (head == previousHead && head.lastWroteBuffer == previousHeadPosition) {
                    break;
                }
                previousHead = head;
                previousHeadPosition = head.lastWroteBuffer;
                feedAudioToEncoder(head);
            }
            if (!buffersToWrite.isEmpty()) {
                FileLog.e(new RuntimeException("InstantCamera rollover audio flush left " + buffersToWrite.size() + " buffer(s) unflushed"));
                // NagramX: the encoder is stuck, so segment N+1 won't fare any better with this backlog
                // than segment N just did -- return the buffers to the pool and drop it rather than
                // mislabeling stale PCM as segment N+1's own. offer(), not put(): this runs on the encoder
                // thread while the pool is normally only drained by a non-blocking poll() on the recorder
                // thread, so put() could block this thread if the pool happened to be at capacity -- a
                // dropped buffer just means one more gets allocated later, which is cheap.
                for (AudioBufferInfo b : buffersToWrite) {
                    buffers.offer(b);
                }
                buffersToWrite.clear();
            }
            try {
                drainEncoder(false);
                // drainEncoder's own dequeue uses a 0us timeout, which only catches output already sitting
                // in the codec -- the AAC frames for what feedAudioToEncoder just queued above aren't out
                // yet, so poll a little longer here
                drainRolloverAudioTail();
            } catch (Exception e) {
                FileLog.e(e);
            }
            final boolean segmentClosedOk = finishMuxer();
            final boolean segmentFirstWrite = videoConvertFirstWrite;
            AndroidUtilities.runOnUIThread(() -> {
                // NagramX: fires from the actual commit, not TimerView's wall-clock trigger -- the encoder
                // can lag the 59.5s mark. Skipped if finishMuxer failed or this recording was replaced
                // while the flush/drain above was running.
                if (segmentClosedOk && InstantCameraView.this.recordingGeneration == capturedGeneration) {
                    fireCutVibration();
                }
                VideoEditedInfo info = sendSegment(segmentFile, segmentDuration, sendOptions);
                if (info != null) {
                    info.notReadyYet = false;
                }
                // the segment is complete on disk, so whatever the streaming upload managed during the
                // recording just needs closing off
                if (segmentFirstWrite) {
                    FileLoader.getInstance(currentAccount).uploadFile(segmentFile.toString(), isSecretChat, false, 1, ConnectionsManager.FileTypeVideo, false);
                }
                FileLoader.getInstance(currentAccount).checkUploadNewDataAvailable(segmentFile.toString(), isSecretChat, 0, segmentFile.length());
            });

            videoFile = nextFile;
            rolloverFile = null;
            videoConvertFirstWrite = true;
            firstEncode = true;
            videoTrackIndex = -5;
            audioTrackIndex = -5;
            // the thumb list is written from the UI thread, so let it do the clearing too
            AndroidUtilities.runOnUIThread(keyframeThumbs::clear);
            frameCount = 0;
            try {
                createMuxer();
                if (videoTrackFormat != null) {
                    videoTrackIndex = mediaMuxer.addTrack(videoTrackFormat, false);
                }
                if (audioTrackFormat != null) {
                    audioTrackIndex = mediaMuxer.addTrack(audioTrackFormat, true);
                }
            } catch (Exception e) {
                FileLog.e(e);
                // no muxer means there is nowhere to put the next segment, so stop the whole recording
                // instead of filming into a file that can never be finished. The segment that was just
                // handed off is already on its way and is not affected.
                mediaMuxer = null;
                AndroidUtilities.runOnUIThread(() -> cancel(false));
                return;
            }
            // whatever is already in the encoder still refers to the previous file's frames
            waitingForSyncFrame = true;
            try {
                android.os.Bundle params = new android.os.Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                videoEncoder.setParameters(params);
            } catch (Exception e) {
                FileLog.e(e);
            }
            // NagramX: unlike a pause, nothing here stops the camera, AudioRecord or either MediaCodec, so
            // the A/V sync fields are left alone rather than reset -- resetting them here is what used to
            // re-arm start-of-recording sync and discard audio right at the boundary (Track.prepare rebases
            // each file to its own first sample anyway, so a climbing PTS across segments is fine).
            // segmentAudioStartTime is the one exception: it needs a fresh per-segment baseline for the
            // 60s cutoff above.
            segmentAudioStartTime = -1;
        }

        // NagramX: pulled out of prepareEncoder so a rollover opens the next segment's movie the same way
        private void createMuxer() throws Exception {
            fileToWrite = videoFile;
            writingToDifferentFile = false;
            if (ImageLoader.isSdCardPath(videoFile)) {
                try {
                    fileToWrite = new File(ApplicationLoader.getFilesDirFixed(), "camera_tmp.mp4");
                    if (fileToWrite.exists()) {
                        fileToWrite.delete();
                    }
                    writingToDifferentFile = true;
                } catch (Throwable e) {
                    FileLog.e(e);
                    fileToWrite = videoFile;
                    writingToDifferentFile = false;
                }
            }
            Mp4Movie movie = new Mp4Movie();
            movie.setCacheFile(fileToWrite);
            movie.setRotation(0);
            movie.setSize(videoWidth, videoHeight);
            mediaMuxer = new MP4Builder().createMovie(movie, isSecretChat, false);
            mediaMuxer.setAllowSyncFiles(allowSendingWhileRecording = SharedConfig.deviceIsHigh());
        }

        // NagramX: pulled out of handleStopRecording so a rollover closes a segment the same way. Returns
        // whether finishMovie() actually completed instead of throwing, so a caller that wants to know
        // before treating the segment as done (the rollover haptic) can check, without changing the
        // pre-existing swallow-and-log behavior the other caller already relies on.
        private boolean finishMuxer() {
            if (mediaMuxer == null) {
                return false;
            }
            boolean[] success = {true};
            if (WRITE_TO_FILE_IN_BACKGROUND) {
                CountDownLatch countDownLatch = new CountDownLatch(1);
                fileWriteQueue.postRunnable(() -> {
                    try {
                        mediaMuxer.finishMovie();
                    } catch (Exception e) {
                        e.printStackTrace();
                        success[0] = false;
                    }
                    countDownLatch.countDown();
                });
                try {
                    countDownLatch.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    success[0] = false;
                }
            } else {
                try {
                    mediaMuxer.finishMovie();
                } catch (Exception e) {
                    FileLog.e(e);
                    success[0] = false;
                }
            }
            FileLog.d("InstantCamera finish muxer " + videoFile);
            if (writingToDifferentFile) {
                if (videoFile.exists()) {
                    try {
                        videoFile.delete();
                    } catch (Exception e) {
                        FileLog.e("InstantCamera copying fileToWrite to videoFile, deleting videoFile error " + videoFile);
                        FileLog.e(e);
                    }
                }
                if (!fileToWrite.renameTo(videoFile)) {
                    FileLog.e("InstantCamera unable to rename file, try move file");
                    try {
                        if (!AndroidUtilities.copyFile(fileToWrite, videoFile)) {
                            success[0] = false;
                        }
                        fileToWrite.delete();
                    } catch (IOException e) {
                        FileLog.e(e);
                        FileLog.e("InstantCamera unable to move file");
                        success[0] = false;
                    }
                }
            }
            return success[0];
        }

        private void setupVideoPlayer(File file) {
            // NagramX (#video-draft-guard): a runnable posted before the fragment was abandoned can still land here
            // after abandonPreview() ran on the same UI thread. Skip re-acquiring the preview player, the ~60 Hz
            // timer and the controls fade -- but let the EGL teardown below run unconditionally, since an early
            // return would trade the player leak this fix removes for an EGL surface/context leak. Guarding inside
            // the method rather than at the call sites is what keeps the naxDraftId mint and the audioDidSent post
            // that follow each call intact -- call-site guarding would have suppressed them on every abandonment.
            // That is all this guard does; it does not itself guarantee the draft persists. Persistence past the
            // post depends on the token gate in ChatActivityEnterView's audioDidSent handler: a finalize that lost
            // the race to a rebuild, which already bumped videoDraftToken, is rejected there before
            // ChatActivity.onVideoDraftReady's save and so survives only as a locked orphan on disk.
            if (!previewAbandoned) {
            videoPlayer = new VideoPlayer();
            videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                    if (videoPlayer == null) {
                        return;
                    }
                    if (videoPlayer.isPlaying() && playbackState == ExoPlayer.STATE_ENDED && videoEditedInfo != null) {
                        videoPlayer.seekTo(videoEditedInfo.startTime > 0 ? videoEditedInfo.startTime : 0);
                    }
                }

                @Override
                public void onError(VideoPlayer player, Exception e) {
                    FileLog.e(e);
                }

                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {

                }

                @Override
                public void onRenderedFirstFrame() {

                }
            });
            videoPlayer.setTextureView(textureView);
            videoPlayer.preparePlayer(Uri.fromFile(file), "other");
            videoPlayer.play();
            videoPlayer.setMute(true);
            startProgressTimer();

            cancelZoomInteractions();
            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.playTogether(
                    ObjectAnimator.ofFloat(buttonsLayout, View.ALPHA, 0.0f),
                    ObjectAnimator.ofFloat(zoomControlView, View.ALPHA, 0.0f),
                    ObjectAnimator.ofInt(paint, AnimationProperties.PAINT_ALPHA, 0),
                    ObjectAnimator.ofFloat(muteImageView, View.ALPHA, holdToSend.ownsPreviewTaps() ? 0.0f : 1.0f)); // NagramX (#video-hold-send): the composer's sound chip shows it instead
            animatorSet.setDuration(180);
            animatorSet.setInterpolator(new DecelerateInterpolator());
            animatorSet.start();
            }

            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = EGL14.EGL_NO_SURFACE;
            if (surface != null) {
                surface.release();
                surface = null;
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(eglDisplay);
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglConfig = null;
        }

        public static final int ENCODER_SEND_CANCEL = 0;
        public static final int ENCODER_SEND_SEND = 1;
        public static final int ENCODER_SEND_PLAYER = 2;

        private boolean sentMedia;

        private void handleStopRecording(final int send, final SendOptions sendOptions) {
            // NagramX: stash this recording's generation before anything else runs. This method is invoked
            // twice for one real stop (see the field comment above), and running is only ever true on the
            // first of those two calls -- capture it here, unconditionally, so the second call's teardown
            // has a stable value to compare against instead of reading recordingToken fresh at that point.
            if (running) {
                stoppedGeneration = recordingToken;
                // NagramX: pin the finalize's draft generation at the same instant as stoppedGeneration so the
                // two guards can't drift. The delivery post below reads this snapshot, never the live field,
                // which a newer recording reusing this instance may have moved on by post time.
                finalizeGeneration = finalizeDraftToken;
            }
            final boolean runDone;
            if (send == ENCODER_SEND_SEND && (videoEditedInfo == null || !videoEditedInfo.needConvert()) && !delegate.isInScheduleMode()) {
                runDone = false;
                if (!sentMedia) {
                    sentMedia = true;
                    AndroidUtilities.runOnUIThread(() -> {
                        videoEditedInfo = new VideoEditedInfo();
                        videoEditedInfo.startTime = -1;
                        videoEditedInfo.endTime = -1;
                        videoEditedInfo.estimatedSize = Math.max(1, size);
                        videoEditedInfo.roundVideo = true;
                        videoEditedInfo.file = file;
                        videoEditedInfo.encryptedFile = encryptedFile;
                        videoEditedInfo.key = key;
                        videoEditedInfo.iv = iv;
                        videoEditedInfo.framerate = 25;
                        videoEditedInfo.resultWidth = videoEditedInfo.originalWidth = 360;
                        videoEditedInfo.resultHeight = videoEditedInfo.originalHeight = 360;
                        videoEditedInfo.originalPath = videoFile.getAbsolutePath();
                        videoEditedInfo.notReadyYet = true;
                        videoEditedInfo.thumb = firstFrameThumb;
                        videoEditedInfo.estimatedDuration = recordedTime;
                        firstFrameThumb = null;
                        MediaController.PhotoEntry entry = new MediaController.PhotoEntry(0, 0, 0, videoFile.getAbsolutePath(), 0, true, 0, 0, 0);
                        if (sendOptions != null) {
                            entry.ttl = sendOptions.ttl;
                            entry.effectId = sendOptions.effectId;
                        }
                        delegate.sendMedia(entry, videoEditedInfo, sendOptions == null || sendOptions.notify, sendOptions != null ? sendOptions.scheduleDate : 0, sendOptions != null ? sendOptions.scheduleRepeatPeriod : 0, false, sendOptions != null ? sendOptions.stars : 0);
                    });
                }
            } else {
                runDone = true;
            }
            if (running && !pauseRecorder) {
                FileLog.d("InstantCamera handleStopRecording running=false");
                sendWhenDone = send;
                sendWhenDoneOptions = sendOptions;
                running = false;
                return;
            }
            // NagramX: guarantee the audio encoder gets an end-of-stream input before the final drain. Two
            // documented paths in feedAudioToEncoder can drop the .last-flagged buffer before it is queued
            // as EOS -- the stall-bound break (no free input slot for ~500ms) and the partial-buffer split
            // that strands the last buffer in buffersToWrite for a next call the already-exited recorder
            // thread never makes. With no EOS input, drainEncoder(true)'s audio loop below never sees an EOS
            // output and hot-spins. This is the second handleStopRecording call (re-posted from
            // recorderRunnable after its loop exited), so the recorder thread is provably dead and there is
            // no concurrent producer -- this forced EOS is the last input, after everything already queued.
            // The condition is the exact negation of that audio loop's break terms: force iff the loop could
            // not otherwise break. Skipped for the paused stop and the cancel path (which deletes the file).
            // Bounded by wall clock; on timeout we fall through without the flag and the deadline backstop in
            // drainEncoder ends the loop instead.
            if (!audioEosSignaled && audioEncoder != null && !running && sendWhenDone != ENCODER_SEND_CANCEL && !pauseRecorder) {
                long eosDeadline = SystemClock.elapsedRealtime() + 100;
                try {
                    while (SystemClock.elapsedRealtime() < eosDeadline) {
                        int inputBufferIndex = audioEncoder.dequeueInputBuffer(0);
                        if (inputBufferIndex >= 0) {
                            audioEncoder.queueInputBuffer(inputBufferIndex, 0, 0, Math.max(0, audioLast), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            audioEosSignaled = true;
                            break;
                        }
                        // NagramX: single output pass to free an input slot, not drainEncoder(false) -- that
                        // would run the ~10ms video dequeue and re-enter this loop.
                        drainAudioEncoderOutput(0);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            try {
                FileLog.d("InstantCamera handleStopRecording drain encoders");
                drainEncoder(true);
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (videoEncoder != null) {
                try {
                    videoEncoder.stop();
                    videoEncoder.release();
                    videoEncoder = null;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (audioEncoder != null) {
                try {
                    audioEncoder.stop();
                    audioEncoder.release();
                    audioEncoder = null;

                    setBluetoothScoOn(false);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (previewFile != null) {
                previewFile.delete();
                previewFile = null;
            }
            // NagramX (#video-draft-guard): capture whether finalization actually succeeded (finishMuxer already
            // computes this -- true iff finishMovie() didn't throw / the write wasn't interrupted). A broken mp4
            // must not be minted a draft id below, or it would persist, supersede the previous good record and
            // unlock its file. mediaMuxer == null means nothing was finalized here, so treat that as not-ok too.
            // finishMuxer proves the muxer closed cleanly, not that the file has video -- an audio-only clip (video
            // encoder never emitted a format, e.g. a camera glitch or a near-instant stop) finalizes fine but must
            // not be persisted either. videoTrackIndex stays at its -5 sentinel unless a video track was added, and
            // it's written on this (encoder) thread, so reading it here -- not in the UI post below -- is same-thread.
            final boolean finalizeOk;
            if (mediaMuxer != null) {
                finalizeOk = finishMuxer() && videoTrackIndex != -5;
            } else {
                finalizeOk = false;
            }
            if (send != 2) {
                DispatchQueue queue = generateKeyframeThumbsQueue;
                if (queue != null) {
                    queue.cleanupQueue();
                    queue.recycle();
                    generateKeyframeThumbsQueue = null;
                }
            }
            FileLog.d("InstantCamera handleStopRecording send " + send);
            if (send == ENCODER_SEND_CANCEL) {
                FileLoader.getInstance(currentAccount).cancelFileUpload(videoFile.getAbsolutePath(), false);
                try {
                    fileToWrite.delete();
                } catch (Throwable ignore) {}
                try {
                    videoFile.delete();
                } catch (Throwable ignore) {}
            } else {
                if (runDone && (send != ENCODER_SEND_SEND || !sentMedia)) {
                    sentMedia = true;
                    // NagramX: capture the pin into a local before the async boundary, same idiom as
                    // capturedGeneration below -- the post must carry the generation pinned when the stop
                    // sequence started, not re-read the field at lambda-run time.
                    final int capturedFinalizeGeneration = finalizeGeneration;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (videoEditedInfo == null) {
                            videoEditedInfo = new VideoEditedInfo();
                            videoEditedInfo.startTime = -1;
                            videoEditedInfo.endTime = -1;
                        }
                        if (videoEditedInfo.needConvert()) {
                            file = null;
                            encryptedFile = null;
                            key = null;
                            iv = null;
                            double totalDuration = videoEditedInfo.estimatedDuration;
                            long startTime = videoEditedInfo.startTime >= 0 ? videoEditedInfo.startTime : 0;
                            long endTime = videoEditedInfo.endTime >= 0 ? videoEditedInfo.endTime : videoEditedInfo.estimatedDuration;
                            videoEditedInfo.estimatedDuration = endTime - startTime;
                            videoEditedInfo.estimatedSize = Math.max(1, (long) (size * (videoEditedInfo.estimatedDuration / totalDuration)));
                            videoEditedInfo.bitrate = 1000000;
                            if (videoEditedInfo.startTime > 0) {
                                videoEditedInfo.startTime *= 1000;
                            }
                            if (videoEditedInfo.endTime > 0) {
                                videoEditedInfo.endTime *= 1000;
                            }
                            FileLoader.getInstance(currentAccount).cancelFileUpload(cameraFile.getAbsolutePath(), false);
                        } else {
                            videoEditedInfo.estimatedSize = Math.max(1, size);
                        }
                        videoEditedInfo.roundVideo = true;
                        videoEditedInfo.file = file;
                        videoEditedInfo.encryptedFile = encryptedFile;
                        videoEditedInfo.key = key;
                        videoEditedInfo.iv = iv;
                        videoEditedInfo.framerate = 25;
                        videoEditedInfo.resultWidth = videoEditedInfo.originalWidth = 360;
                        videoEditedInfo.resultHeight = videoEditedInfo.originalHeight = 360;
                        videoEditedInfo.originalPath = videoFile.getAbsolutePath();
                        final VideoEditedInfo info = videoEditedInfo;
                        if (send == ENCODER_SEND_SEND) {
                            // NagramX: infinite video message: a scheduled segment arrives with its slot already
                            // frozen into sendOptions, so skip the picker and send it directly at that time (the
                            // else branch consumes sendOptions.scheduleDate). Ordinary scheduled round video still
                            // has scheduleDate 0 here and gets the picker. A scheduled infinite ceiling-stop passes
                            // this point twice (state 4 via the preview send), and both passes take the else branch.
                            if (delegate.isInScheduleMode() && (sendOptions == null || sendOptions.scheduleDate == 0)) {
                                AlertsCreator.createScheduleDatePickerDialog(delegate.getParentActivity(), delegate.getDialogId(), (notify, scheduleDate, scheduleRepeatPeriod) -> {
                                    MediaController.PhotoEntry entry = new MediaController.PhotoEntry(0, 0, 0, videoFile.getAbsolutePath(), 0, true, 0, 0, 0);
                                    if (sendOptions != null) {
                                        entry.ttl = sendOptions.ttl;
                                        entry.effectId = sendOptions.effectId;
                                    }
                                    delegate.sendMedia(entry, info, notify || sendOptions == null || sendOptions.notify, scheduleDate != 0 ? scheduleDate : sendOptions != null ? sendOptions.scheduleDate : 0, scheduleRepeatPeriod != 0 ? scheduleRepeatPeriod : sendOptions != null ? sendOptions.scheduleRepeatPeriod : 0, false, sendOptions != null ? sendOptions.stars : 0);
                                    startAnimation(false, false);
                                }, () -> {
                                    startAnimation(false, false);
                                }, resourcesProvider);
                            } else {
                                MediaController.PhotoEntry entry = new MediaController.PhotoEntry(0, 0, 0, videoFile.getAbsolutePath(), 0, true, 0, 0, 0);
                                if (sendOptions != null) {
                                    entry.ttl = sendOptions.ttl;
                                    entry.effectId = sendOptions.effectId;
                                }
                                delegate.sendMedia(entry, info, sendOptions == null || sendOptions.notify, sendOptions != null ? sendOptions.scheduleDate : 0, sendOptions != null ? sendOptions.scheduleRepeatPeriod : 0, false, sendOptions != null ? sendOptions.stars : 0);
                            }
                            videoEditedInfo = null;
                        } else {
                            setupVideoPlayer(videoFile);
                            info.estimatedDuration = recordedTime;
                            // NagramX (#video-draft-guard): stamp a fresh draft identity onto the finished-but-unsent
                            // clip before it's posted to the composer, so persist / restore / clear can match this exact
                            // draft by id across a process boundary, never by chat slot. Only when finalize succeeded --
                            // a 0 id leaves save() a no-op (its reject sentinel), so a broken clip still previews but is
                            // never persisted, superseding nothing.
                            info.naxDraftId = finalizeOk ? com.dazewell.gram.helpers.VideoDraftStore.newId() : 0;
                            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.audioDidSent, recordingGuid, info, videoFile.getAbsolutePath(), keyframeThumbs, capturedFinalizeGeneration);
                        }
                    });
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (sentMedia && videoEditedInfo != null) {
                        videoEditedInfo.notReadyYet = false;
                    }
                    didWriteData(videoFile, 0, true);
                    MediaController.getInstance().requestRecordAudioFocus(false);
                });
            }
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = EGL14.EGL_NO_SURFACE;
            if (surface != null) {
                surface.release();
                surface = null;
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(eglDisplay);
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglConfig = null;
            handler.exit();
            if (overlayHelper != null) {
                overlayHelper.destroy();
                overlayHelper = null;
            }
            // NagramX: same generation guard as handleRollover's cut haptic, and for the same reason: this
            // teardown is posted async, drainEncoder/MediaCodec release/EGL teardown above can all take a
            // moment, and a fast stop-then-record-again can reuse this exact instance in that window.
            // stoppedGeneration was pinned back when this stop sequence started (see its field comment), so
            // it stays correct here regardless of what recordingToken has since been bumped to.
            final int capturedGeneration = stoppedGeneration;
            final boolean fireLimitStopHaptic = sendOptions != null && sendOptions.limitStop;
            AndroidUtilities.runOnUIThread(() -> {
                // NagramX (#round-video-restart-guard-fix): this stop is finished whatever the generation says, and
                // nothing can have started since, because the flag holds new recordings off
                encoderTeardownPending = false;
                if (InstantCameraView.this.recordingGeneration == capturedGeneration) {
                    InstantCameraView.this.videoEncoder = null;
                    // NagramX: recording is over one way or another, so any pre-cut warning still showing
                    // (e.g. the last segment stopped normally instead of rolling over) needs clearing too.
                    setLimitWarningActive(false);
                    // NagramX: same buzz as a rollover cut, fired only for the ordinary 60s auto-stop
                    if (fireLimitStopHaptic) {
                        fireCutVibration();
                    }
                }
            });
        }

        private void setBluetoothScoOn(boolean scoOn) {
            AudioManager am = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
            if (SharedConfig.recordViaSco && !PermissionRequest.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                SharedConfig.recordViaSco = false;
                SharedConfig.saveConfig();
            }
            if (am.isBluetoothScoAvailableOffCall() && SharedConfig.recordViaSco || !scoOn) {
                BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
                try {
                    if (btAdapter != null && btAdapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED || !scoOn) {
                        if (scoOn && !am.isBluetoothScoOn()) {
                            am.startBluetoothSco();
                        } else if (!scoOn && am.isBluetoothScoOn()) {
                            am.stopBluetoothSco();
                        }
                    }
                } catch (SecurityException ignored) {
                } catch (Throwable e) {
                    FileLog.e(e);
                    try {
                        if (!scoOn && am.isBluetoothScoOn()) {
                            am.stopBluetoothSco();
                        }
                    } catch (Exception e2) {
                        FileLog.e(e2);
                    }
                }
            }
        }

        private void prepareEncoder(boolean fromPause) {
            setBluetoothScoOn(true);

            try {
                int recordBufferSize = AudioRecord.getMinBufferSize(audioSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (recordBufferSize <= 0) {
                    recordBufferSize = 3584;
                }
                int bufferSize = 2048 * 24;
                if (bufferSize < recordBufferSize) {
                    bufferSize = ((recordBufferSize / 2048) + 1) * 2048 * 2;
                }
                buffers.clear();
                for (int a = 0; a < 3; a++) {
                    buffers.add(new AudioBufferInfo());
                }

                if (fromPause) {
                    prevVideoLast = videoLast + videoLastDt;
                    prevAudioLast = audioLast + audioLastDt;
                    firstVideoFrameSincePause = true;
                } else {
                    prevVideoLast = -1;
                    prevAudioLast = -1;
                    currentTimestamp = 0;
                    audioEosSignaled = false; // NagramX: fresh recording, no EOS handed to the audio encoder yet
                }
                lastTimestamp = -1;
                lastCommitedFrameTime = 0;
                audioStartTime = -1;
                segmentAudioStartTime = -1;
                audioFirst = -1;
                videoFirst = -1;
                videoLast = -1;
                videoDiff = -1;
                audioLast = -1;
                audioDiff = -1;
                skippedFirst = false;
                skippedTime = 0;

                audioRecorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, audioSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                audioRecorder.startRecording();
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("InstantCamera initied audio record with channels " + audioRecorder.getChannelCount() + " sample rate = " + audioRecorder.getSampleRate() + " bufferSize = " + bufferSize);
                }
                pauseRecorder = false;
                Thread thread = new Thread(recorderRunnable);
                thread.setPriority(Thread.MAX_PRIORITY);
                thread.start();

                audioBufferInfo = new MediaCodec.BufferInfo();
                videoBufferInfo = new MediaCodec.BufferInfo();

                MediaFormat audioFormat = new MediaFormat();
                audioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
                audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, audioSampleRate);
                audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
                audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, MessagesController.getInstance(currentAccount).roundAudioBitrate * 1024);
                audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048 * AudioBufferInfo.MAX_SAMPLES);

                audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
                audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                audioEncoder.start();

                videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
                firstEncode = true;

                MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, videoWidth, videoHeight);

                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

                videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                surface = videoEncoder.createInputSurface();
                videoEncoder.start();

                if (!fromPause) {
                    createMuxer();
                }

                AndroidUtilities.runOnUIThread(() -> {
                    if (cancelled || cameraFileHandedOff || encoderTeardownPending) { // NagramX (#round-video-restart-guard-fix): a late start post must not revive a session that already stopped
                        return;
                    }
                    try {
                        if (!NekoConfig.disableVibration.Bool()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                    } catch (Exception ignore) {}
                    AndroidUtilities.lockOrientation(delegate.getParentActivity());
                    recordPlusTime = fromPause ? recordedTime : 0;
                    recordStartTime = System.currentTimeMillis();
                    recording = true;
                    updateFlash();
                    invalidate();
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStarted, recordingGuid, false);
                });
            } catch (Exception ioe) {
                throw new RuntimeException(ioe);
            }

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("EGL already set up");
            }

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("unable to get EGL14 display");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                eglDisplay = null;
                throw new RuntimeException("unable to initialize EGL14");
            }

            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                int renderableType = EGL14.EGL_OPENGL_ES2_BIT;

                int[] attribList = {
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, renderableType,
                        0x3142, 1,
                        EGL14.EGL_NONE
                };
                android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
                int[] numConfigs = new int[1];
                if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
                    throw new RuntimeException("Unable to find a suitable EGLConfig");
                }

                int[] attrib2_list = {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                        EGL14.EGL_NONE
                };
                eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], sharedEglContext, attrib2_list, 0);
                eglConfig = configs[0];
            }

            int[] values = new int[1];
            EGL14.eglQueryContext(eglDisplay, eglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, values, 0);

            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                throw new IllegalStateException("surface already created");
            }

            int[] surfaceAttribs = {
                    EGL14.EGL_NONE
            };
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0);
            if (eglSurface == null) {
                throw new RuntimeException("surface was null");
            }

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("eglMakeCurrent failed " + GLUtils.getEGLErrorString(EGL14.eglGetError()));
                }
                throw new RuntimeException("eglMakeCurrent failed");
            }
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            if (overlayHelper != null) {
                overlayHelper.destroy();
                overlayHelper = null;
            }
            overlayHelper = new InstantCameraVideoEncoderOverlayHelper(videoWidth, videoHeight);

            String vertexShaderSource, fragmentShaderSource;
            if (overlayHelper != null) {
                vertexShaderSource = VERTEX_SHADER;
                fragmentShaderSource = createFragmentShaderV2(previewSize[0]);
            } else if (useCamera2) {
                vertexShaderSource = AndroidUtilities.readRes(R.raw.instant_lanczos_vert);
                fragmentShaderSource = AndroidUtilities.readRes(R.raw.instant_lanczos_frag_oes);
            } else {
                vertexShaderSource = VERTEX_SHADER;
                fragmentShaderSource = createFragmentShader(previewSize[0]);
            }
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource);
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource);
            if (vertexShader != 0 && fragmentShader != 0) {
                drawProgram = GLES20.glCreateProgram();
                GLES20.glAttachShader(drawProgram, vertexShader);
                GLES20.glAttachShader(drawProgram, fragmentShader);
                GLES20.glLinkProgram(drawProgram);
                int[] linkStatus = new int[1];
                GLES20.glGetProgramiv(drawProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
                if (linkStatus[0] == 0) {
                    GLES20.glDeleteProgram(drawProgram);
                    drawProgram = 0;
                } else {
                    positionHandle = GLES20.glGetAttribLocation(drawProgram, "aPosition");
                    textureHandle = GLES20.glGetAttribLocation(drawProgram, "aTextureCoord");
                    previewSizeHandle = GLES20.glGetUniformLocation(drawProgram, "preview");
                    resolutionHandle = GLES20.glGetUniformLocation(drawProgram, "resolution");
                    alphaHandle = GLES20.glGetUniformLocation(drawProgram, "alpha");
                    vertexMatrixHandle = GLES20.glGetUniformLocation(drawProgram, "uMVPMatrix");
                    textureMatrixHandle = GLES20.glGetUniformLocation(drawProgram, "uSTMatrix");
                    texelSizeHandle = GLES20.glGetUniformLocation(drawProgram, "texelSize");
                }
            }
        }

        public Surface getInputSurface() {
            return surface;
        }

        private void didWriteData(File file, long availableSize, boolean last) {
            if (videoConvertFirstWrite) {
                FileLoader.getInstance(currentAccount).uploadFile(file.toString(), isSecretChat, false, 1, ConnectionsManager.FileTypeVideo, false);
                videoConvertFirstWrite = false;
                if (last) {
                    FileLoader.getInstance(currentAccount).checkUploadNewDataAvailable(file.toString(), isSecretChat, availableSize, last ? file.length() : 0);
                }
            } else {
                FileLoader.getInstance(currentAccount).checkUploadNewDataAvailable(file.toString(), isSecretChat, availableSize, last ? file.length() : 0);
            }
        }

        public void drainEncoder(boolean endOfStream) throws Exception {
            // NagramX: a failed rollover leaves no muxer behind; there is nothing to drain into
            if (mediaMuxer == null) {
                if (endOfStream && videoEncoder != null) {
                    videoEncoder.signalEndOfInputStream();
                }
                return;
            }
            if (endOfStream) {
                videoEncoder.signalEndOfInputStream();
            }

            ByteBuffer[] encoderOutputBuffers = null;
            while (true) {
                int encoderStatus = videoEncoder.dequeueOutputBuffer(videoBufferInfo, 10000);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream || pauseRecorder) {
                        break;
                    }
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = videoEncoder.getOutputFormat();
                    if (videoTrackIndex == -5) {
                        videoTrackIndex = mediaMuxer.addTrack(newFormat, false);
                        videoTrackFormat = newFormat; // NagramX: reused when a rollover opens the next file
                        if (newFormat.containsKey(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES) && newFormat.getInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES) == 1) {
                            ByteBuffer spsBuff = newFormat.getByteBuffer("csd-0");
                            ByteBuffer ppsBuff = newFormat.getByteBuffer("csd-1");
                            prependHeaderSize = spsBuff.limit() + ppsBuff.limit();
                        }
                    }
                } else if (encoderStatus >= 0) {
                    ByteBuffer encodedData;
                    encodedData = videoEncoder.getOutputBuffer(encoderStatus);
                    if (encodedData == null) {
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                    }
                    // NagramX: right after a rollover the encoder is still emitting frames that reference the
                    // previous file's keyframe: they'd be unplayable at the head of the new one, so drop them
                    // until the sync frame we asked for shows up
                    if (waitingForSyncFrame && (videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            waitingForSyncFrame = false;
                        } else {
                            videoBufferInfo.size = 0;
                        }
                    }
                    if (videoBufferInfo.size > 1) {
                        if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (prependHeaderSize != 0 && (videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                videoBufferInfo.offset += prependHeaderSize;
                                videoBufferInfo.size -= prependHeaderSize;
                            }
                            if (firstEncode && (videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                if (videoBufferInfo.size > 100) {
                                    encodedData.position(videoBufferInfo.offset);
                                    byte[] temp = new byte[100];
                                    encodedData.get(temp);
                                    int nalCount = 0;
                                    for (int a = 0; a < temp.length - 4; a++) {
                                        if (temp[a] == 0 && temp[a + 1] == 0 && temp[a + 2] == 0 && temp[a + 3] == 1) {
                                            nalCount++;
                                            if (nalCount > 1) {
                                                videoBufferInfo.offset += a;
                                                videoBufferInfo.size -= a;
                                                break;
                                            }
                                        }
                                    }
                                }
                                firstEncode = false;
                            }
                            if (WRITE_TO_FILE_IN_BACKGROUND) {
                                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                                bufferInfo.size = videoBufferInfo.size;
                                bufferInfo.offset = videoBufferInfo.offset;
                                bufferInfo.flags = videoBufferInfo.flags;
                                bufferInfo.presentationTimeUs = videoBufferInfo.presentationTimeUs;
                                ByteBuffer byteBuffer = AndroidUtilities.cloneByteBuffer(encodedData);
                                fileWriteQueue.postRunnable(() -> {
                                    long availableSize = 0;
                                    try {
                                        availableSize = mediaMuxer.writeSampleData(videoTrackIndex, byteBuffer, bufferInfo, true);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                    if (availableSize != 0 && !writingToDifferentFile && allowSendingWhileRecording) {
                                        didWriteData(videoFile, availableSize, false);
                                    }
                                });
                            } else {
                                long availableSize = mediaMuxer.writeSampleData(videoTrackIndex, encodedData, videoBufferInfo, true);
                                if (availableSize != 0 && !writingToDifferentFile && allowSendingWhileRecording) {
                                    didWriteData(videoFile, availableSize, false);
                                }
                            }
                        } else if (videoTrackIndex == -5) {
                            byte[] csd = new byte[videoBufferInfo.size];
                            encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size);
                            encodedData.position(videoBufferInfo.offset);
                            encodedData.get(csd);
                            ByteBuffer sps = null;
                            ByteBuffer pps = null;
                            for (int a = videoBufferInfo.size - 1; a >= 0; a--) {
                                if (a > 3) {
                                    if (csd[a] == 1 && csd[a - 1] == 0 && csd[a - 2] == 0 && csd[a - 3] == 0) {
                                        sps = ByteBuffer.allocate(a - 3);
                                        pps = ByteBuffer.allocate(videoBufferInfo.size - (a - 3));
                                        sps.put(csd, 0, a - 3).position(0);
                                        pps.put(csd, a - 3, videoBufferInfo.size - (a - 3)).position(0);
                                        break;
                                    }
                                } else {
                                    break;
                                }
                            }

                            MediaFormat newFormat = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight);
                            if (sps != null && pps != null) {
                                newFormat.setByteBuffer("csd-0", sps);
                                newFormat.setByteBuffer("csd-1", pps);
                            }
                            videoTrackIndex = mediaMuxer.addTrack(newFormat, false);
                            videoTrackFormat = newFormat; // NagramX: reused when a rollover opens the next file
                        }
                    }
                    videoEncoder.releaseOutputBuffer(encoderStatus, false);
                    if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }

            // NagramX: backstop for the endOfStream drain only. Part 1 in handleStopRecording forces an EOS
            // input so this loop normally breaks at the EOS output below, but a wedged codec that never
            // frees an input slot (the same failure the :2939 stall bound documents) can leave Part 1 unable
            // to queue that EOS -- and then none of the break terms above can fire. Bound it by wall clock so
            // a silent codec can't pin this thread. Gated on the endOfStream parameter, so it applies only to
            // the single final-stop drainEncoder(true) call, never a rollover/steady drainEncoder(false)
            // where cutting the drain would displace audio across a segment boundary. At the final stop the
            // muxer closes right after with no next segment, so a truncated tail is only a shorter valid
            // file, never misplaced data.
            long audioDrainDeadline = endOfStream ? SystemClock.elapsedRealtime() + 100 : 0;
            while (true) {
                int encoderStatus = drainAudioEncoderOutput(0);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream || !running && sendWhenDone == ENCODER_SEND_CANCEL || pauseRecorder || SystemClock.elapsedRealtime() > audioDrainDeadline) {
                        break;
                    }
                } else if (encoderStatus >= 0 && (audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }

        // NagramX: one dequeue-and-write pass on the audio encoder's output side, factored out of the loop
        // above so handleRollover can also call it with its own timeout (see drainRolloverAudioTail).
        private int drainAudioEncoderOutput(long timeoutUs) throws Exception {
            int encoderStatus = audioEncoder.dequeueOutputBuffer(audioBufferInfo, timeoutUs);
            if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = audioEncoder.getOutputFormat();
                if (audioTrackIndex == -5) {
                    audioTrackIndex = mediaMuxer.addTrack(newFormat, true);
                    audioTrackFormat = newFormat; // NagramX: reused when a rollover opens the next file
                }
            } else if (encoderStatus >= 0) {
                ByteBuffer encodedData = audioEncoder.getOutputBuffer(encoderStatus);
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }
                if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    audioBufferInfo.size = 0;
                }
                if (audioBufferInfo.size != 0) {
                    if (WRITE_TO_FILE_IN_BACKGROUND) {
                        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                        bufferInfo.size = audioBufferInfo.size;
                        bufferInfo.offset = audioBufferInfo.offset;
                        bufferInfo.flags = audioBufferInfo.flags;
                        bufferInfo.presentationTimeUs = audioBufferInfo.presentationTimeUs;
                        ByteBuffer byteBuffer = AndroidUtilities.cloneByteBuffer(encodedData);
                        fileWriteQueue.postRunnable(() -> {
                            long availableSize = 0;
                            try {
                                availableSize = mediaMuxer.writeSampleData(audioTrackIndex, byteBuffer, bufferInfo, false);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            if (availableSize != 0 && !writingToDifferentFile && allowSendingWhileRecording) {
                                didWriteData(videoFile, availableSize, false);
                            }
                        });
                        if (audioEncoder != null) {
                            audioEncoder.releaseOutputBuffer(encoderStatus, false);
                        }
                    } else {
                        long availableSize = mediaMuxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo, false);
                        if (availableSize != 0 && !writingToDifferentFile && allowSendingWhileRecording) {
                            didWriteData(videoFile, availableSize, false);
                        }
                        if (audioEncoder != null) {
                            audioEncoder.releaseOutputBuffer(encoderStatus, false);
                        }
                    }
                } else if (audioEncoder != null) {
                    audioEncoder.releaseOutputBuffer(encoderStatus, false);
                }
            }
            return encoderStatus;
        }

        // NagramX: a freshly queued AAC input isn't available as output the instant it's queued, so the
        // single drainEncoder(false) call in handleRollover (0us timeout, returns on the first miss) grabs
        // little to none of what feedAudioToEncoder just fed it -- the rest surfaces later, in file N+1,
        // with file N's PTS. Poll with a short timeout instead of drainEncoder's 0us one until the codec
        // stops handing back real output, capped so a stuck codec can't hold up the camera.
        private void drainRolloverAudioTail() {
            long deadline = SystemClock.elapsedRealtime() + 100;
            try {
                // NagramX: INFO_TRY_AGAIN_LATER is a normal "nothing ready in this 2ms poll" result, not a
                // stop signal -- only the deadline should end this loop. audioBufferInfo is only trustworthy
                // right after a real (>=0) dequeue, same as drainEncoder's own audio loop above.
                while (SystemClock.elapsedRealtime() < deadline) {
                    int encoderStatus = drainAudioEncoderOutput(2000);
                    if (encoderStatus >= 0 && (audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }


        @Override
        protected void finalize() throws Throwable {
            if (fileWriteQueue != null) {
                fileWriteQueue.recycle();
                fileWriteQueue = null;
            }
            if (overlayHelper != null) {
                overlayHelper.destroy();
                overlayHelper = null;
            }
            try {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                    EGL14.eglReleaseThread();
                    EGL14.eglTerminate(eglDisplay);
                    eglDisplay = EGL14.EGL_NO_DISPLAY;
                    eglContext = EGL14.EGL_NO_CONTEXT;
                    eglConfig = null;
                }
            } finally {
                super.finalize();
            }
        }
    }

    private String createFragmentShader(Size previewSize) {
        if (SharedConfig.deviceIsLow() || !allowBigSizeCamera() || previewSize != null && Math.max(previewSize.getHeight(), previewSize.getWidth()) * 0.7f < MessagesController.getInstance(currentAccount).roundVideoSize) {
            return "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform float alpha;\n" +
                    "uniform vec2 preview;\n" +
                    "uniform vec2 resolution;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "   vec4 textColor = texture2D(sTexture, vTextureCoord);\n" +
                    "   vec2 coord = resolution * 0.5;\n" +
                    "   float radius = 0.51 * resolution.x;\n" +
                    "   float d = length(coord - gl_FragCoord.xy) - radius;\n" +
                    "   float t = clamp(d, 0.0, 1.0);\n" +
                    "   vec3 color = mix(textColor.rgb, vec3(1, 1, 1), t);\n" +
                    "   gl_FragColor = vec4(color * alpha, alpha);\n" +
                    "}\n";
        }
        //apply bilinear filtering
        return "#extension GL_OES_EGL_image_external : require\n" +
                "precision highp float;\n" +
                "varying vec2 vTextureCoord;\n" + //uv
                "uniform vec2 resolution;\n" + //rendering texture
                "uniform vec2 preview;\n" + //original texture size
                "uniform float alpha;\n" +

                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "   vec2 coord = resolution * 0.5;\n" +
                "   float radius = 0.51 * resolution.x;\n" +
                "   float d = length(coord - gl_FragCoord.xy) - radius;\n" +
                "   float t = clamp(d, 0.0, 1.0);\n" +
                "   if (t == 0.0) {\n" +
                "       vec2 c_textureSize = preview;\n" +
                "       vec2 c_onePixel = (1.0 / c_textureSize);\n" +
                "       vec2 uv = vTextureCoord;\n" +
                "       vec2 pixel = uv * c_textureSize + 0.5;\n" +

                "       vec2 frac = fract(pixel);\n" +
                "       pixel = (floor(pixel) / c_textureSize) - vec2(c_onePixel);\n" +

                "       vec4 tl = texture2D(sTexture, pixel + vec2(0.0         , 0.0));\n" +
                "       vec4 tr = texture2D(sTexture, pixel + vec2(c_onePixel.x, 0.0));\n" +
                "       vec4 bl = texture2D(sTexture, pixel + vec2(0.0         , c_onePixel.y));\n" +
                "       vec4 br = texture2D(sTexture, pixel + vec2(c_onePixel.x, c_onePixel.y));\n" +

                "       vec4 x1 = mix(tl, tr, frac.x);\n" +
                "       vec4 x2 = mix(bl, br, frac.x);\n" +
                "       gl_FragColor = mix(x1, x2, frac.y) * alpha;" +
                "   } else {\n" +
                "       gl_FragColor = vec4(1, 1, 1, alpha);\n" +
                "   }\n" +
                "}\n";
    }

    private String createFragmentShaderV2(Size previewSize) {
        if (SharedConfig.deviceIsLow() || !allowBigSizeCamera() || previewSize != null && Math.max(previewSize.getHeight(), previewSize.getWidth()) * 0.7f < MessagesController.getInstance(currentAccount).roundVideoSize) {
            return "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform float alpha;\n" +
                    "uniform vec2 preview;\n" +
                    "uniform vec2 resolution;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "   vec4 textColor = texture2D(sTexture, vTextureCoord);\n" +
                    "   gl_FragColor = vec4(textColor.rgb * alpha, alpha);\n" +
                    "}\n";
        }
        return "#extension GL_OES_EGL_image_external : require\n" +
                "precision highp float;\n" +
                "varying vec2 vTextureCoord;\n" + //uv
                "uniform vec2 resolution;\n" + //rendering texture
                "uniform vec2 preview;\n" + //original texture size
                "uniform float alpha;\n" +

                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "   vec2 c_textureSize = preview;\n" +
                "   vec2 c_onePixel = (1.0 / c_textureSize);\n" +
                "   vec2 uv = vTextureCoord;\n" +
                "   vec2 pixel = uv * c_textureSize + 0.5;\n" +
                "   vec2 frac = fract(pixel);\n" +
                "   pixel = (floor(pixel) / c_textureSize) - vec2(c_onePixel);\n" +
                "   vec4 tl = texture2D(sTexture, pixel + vec2(0.0         , 0.0));\n" +
                "   vec4 tr = texture2D(sTexture, pixel + vec2(c_onePixel.x, 0.0));\n" +
                "   vec4 bl = texture2D(sTexture, pixel + vec2(0.0         , c_onePixel.y));\n" +
                "   vec4 br = texture2D(sTexture, pixel + vec2(c_onePixel.x, c_onePixel.y));\n" +
                "   vec4 x1 = mix(tl, tr, frac.x);\n" +
                "   vec4 x2 = mix(bl, br, frac.x);\n" +
                "   gl_FragColor = mix(x1, x2, frac.y) * alpha;\n" +
                "}\n";
    }

    public class InstantViewCameraContainer extends InstantCameraViewBase.InstantViewCameraContainer {

        ImageReceiver imageReceiver;
        float imageProgress;

        public InstantViewCameraContainer(Context context) {
            super(context);
            InstantCameraView.this.setWillNotDraw(false);
        }

        @Override
        public void setImageReceiver(ImageReceiver imageReceiver) {
            if (this.imageReceiver == null) {
                imageProgress = 0;
            }
            this.imageReceiver = imageReceiver;
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (imageProgress != 1f) {
                imageProgress += 16 / 250.0f;
                if (imageProgress > 1f) {
                    imageProgress = 1f;
                }
                invalidate();
            }
            if (imageReceiver != null) {
                canvas.save();
                if (imageReceiver.getImageWidth() != textureViewSize) {
                    float s = textureViewSize / imageReceiver.getImageWidth();
                    canvas.scale(s, s);
                }
                canvas.translate(-imageReceiver.getImageX(), -imageReceiver.getImageY());
                float oldAlpha = imageReceiver.getAlpha();
                imageReceiver.setAlpha(imageProgress);
                imageReceiver.draw(canvas);
                imageReceiver.setAlpha(oldAlpha);
                canvas.restore();
            }
            holdToSend.draw(canvas, this); // NagramX (#video-hold-send): here, not onDraw, which the camera covers
        }
    }


    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && ev.getY() > getMeasuredHeight() - getPaddingBottom()) {
            return false;
        }
        holdToSend.onTouchEvent(ev, cameraContainer); // NagramX (#video-hold-send): observes only; pinch-to-zoom below still needs the DOWN

        if (ev.getAction() == MotionEvent.ACTION_DOWN && delegate != null) {
            if (videoPlayer != null && !holdToSend.ownsPreviewTaps()) { // NagramX (#video-hold-send): else the preview's taps are the hold's
                boolean mute = !videoPlayer.isMuted();
                videoPlayer.setMute(mute);
                if (muteAnimation != null) {
                    muteAnimation.cancel();
                }
                muteAnimation = new AnimatorSet();
                muteAnimation.playTogether(
                        ObjectAnimator.ofFloat(muteImageView, View.ALPHA, mute ? 1.0f : 0.0f),
                        ObjectAnimator.ofFloat(muteImageView, View.SCALE_X, mute ? 1.0f : 0.5f),
                        ObjectAnimator.ofFloat(muteImageView, View.SCALE_Y, mute ? 1.0f : 0.5f));
                muteAnimation.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (animation.equals(muteAnimation)) {
                            muteAnimation = null;
                        }
                    }
                });
                muteAnimation.setDuration(180);
                muteAnimation.setInterpolator(new DecelerateInterpolator());
                muteAnimation.start();
            } else {
                //baseFragment.checkRecordLocked(false);
            }
        }

        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN || ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            if (maybePinchToZoomTouchMode && !isInPinchToZoomTouchMode && ev.getPointerCount() == 2 && finishZoomTransition == null && recording) {
                pinchStartDistance = (float) Math.hypot(ev.getX(1) - ev.getX(0), ev.getY(1) - ev.getY(0));

                pinchStartZoom = getCurrentZoom();
                pinchScale = pinchStartZoom;

                pointerId1 = ev.getPointerId(0);
                pointerId2 = ev.getPointerId(1);
                isInPinchToZoomTouchMode = true;
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                AndroidUtilities.rectTmp.set(cameraContainer.getX(), cameraContainer.getY(), cameraContainer.getX() + cameraContainer.getMeasuredWidth(), cameraContainer.getY() + cameraContainer.getMeasuredHeight());
                maybePinchToZoomTouchMode = AndroidUtilities.rectTmp.contains(ev.getX(), ev.getY());
            }
            return true;
        } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE && isInPinchToZoomTouchMode) {
            int index1 = -1;
            int index2 = -1;
            for (int i = 0; i < ev.getPointerCount(); i++) {
                if (pointerId1 == ev.getPointerId(i)) {
                    index1 = i;
                }
                if (pointerId2 == ev.getPointerId(i)) {
                    index2 = i;
                }
            }
            if (index1 == -1 || index2 == -1) {
                isInPinchToZoomTouchMode = false;

                finishZoom();
                return false;
            }
            float scale = (float) Math.hypot(ev.getX(index2) - ev.getX(index1), ev.getY(index2) - ev.getY(index1)) / pinchStartDistance;
            if (useCamera2) {
                if (camera2SessionCurrent != null) {
                    float zoom = Utilities.clamp(pinchStartZoom * scale, camera2SessionCurrent.getMaxZoom(), camera2SessionCurrent.getMinZoom());
                    followZoom(getZoomControlValueFromCamera2(zoom), false);
                    pinchScale = zoom;
                    syncZoomControlView(pinchScale);
                }
            } else {
                float zoom = Utilities.clamp(pinchStartZoom + (scale - 1f), 1f, 0f);
                followZoom(zoom, false);
                pinchScale = zoom;
                syncZoomControlView(pinchScale);
            }
        } else if ((ev.getActionMasked() == MotionEvent.ACTION_UP || (ev.getActionMasked() == MotionEvent.ACTION_POINTER_UP && checkPointerIds(ev)) || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) && isInPinchToZoomTouchMode) {
            isInPinchToZoomTouchMode = false;
            finishZoom();
        }
        return true;
    }

    ValueAnimator finishZoomTransition;

    public void finishZoom() {
        if (finishZoomTransition != null) {
            return;
        }
        float fromZoom;
        if (useCamera2) {
            if (camera2SessionCurrent == null) return;
            fromZoom = getZoomControlValueFromCamera2(Utilities.clamp(pinchScale, camera2SessionCurrent.getMaxZoom(), camera2SessionCurrent.getMinZoom()));
        } else {
            fromZoom = Utilities.clamp(pinchScale, 1f, 0f);
        }
        animateZoom(fromZoom, lockedZoom, 350, CubicBezierInterpolator.DEFAULT);
    }

    // NagramX: every animated zoom change (tap, track tap, hold-to-zoom, pinch snap-back) runs through this one
    // animator, in slider-fraction space: the pinch gate above blocks concurrent writers, and a dual camera swap
    // mid-animation stays correct because each frame maps the fraction onto whatever session is current
    private void animateZoom(float fromFraction, float toFraction, long duration, TimeInterpolator interpolator) {
        cancelFinishZoomTransition();
        final float to = Utilities.clamp(toFraction, 1f, 0f);
        if (Math.abs(fromFraction - to) <= 0.0001f) {
            zoomGliding = false;
            return;
        }
        finishZoomTransition = ValueAnimator.ofFloat(fromFraction, to);
        finishZoomTransition.addUpdateListener(valueAnimator -> setZoomFraction((float) valueAnimator.getAnimatedValue()));
        finishZoomTransition.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation.equals(finishZoomTransition)) {
                    finishZoomTransition = null;
                }
                if (zoomGliding && !cancelled && (to <= 0f || to >= 1f)) {
                    zoomHaptic(HapticFeedbackConstants.CLOCK_TICK);
                }
                zoomGliding = false;
            }
        });
        finishZoomTransition.setDuration(duration);
        finishZoomTransition.setInterpolator(interpolator);
        finishZoomTransition.start();
    }

    private void setZoomFraction(float fraction) {
        lockedZoom = Utilities.clamp(fraction, 1f, 0f);
        zoomControlView.setZoom(lockedZoom, false);
        followZoom(lockedZoom, false);
        if (useCamera2 && camera2SessionCurrent != null) {
            showZoomLabel(zoomFractionToRatio(camera2SessionCurrent, lockedZoom));
        } else if (!useCamera2 && cameraSession != null && cameraSession.getMaxZoomRatio() > 1f) {
            showZoomLabel(camera1FractionToRatio(lockedZoom));
        }
    }

    // NagramX: the camera chases the requested zoom slightly behind the input. Touch events arrive faster than
    // frames, and pushing every raw value straight into the capture request lands as visible steps, so this
    // coalesces to one camera update per frame and low-passes the value (~100ms to converge). The knob and the
    // label still track the finger 1:1; only the camera lags.
    private void followZoom(float fraction, boolean immediate) {
        zoomFollowTarget = Utilities.clamp(fraction, 1f, 0f);
        if (immediate || zoomFollowCurrent < 0) {
            stopZoomFollower();
            zoomFollowCurrent = zoomFollowTarget;
            applyZoomNow(zoomFollowTarget, true);
            return;
        }
        if (!zoomFollowing) {
            zoomFollowing = true;
            zoomFollowLastTick = SystemClock.elapsedRealtime();
            postOnAnimation(zoomFollowerTick);
        }
    }

    private void stepZoomFollower() {
        if (!zoomFollowing) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        // time-normalized decay so the smoothing feel doesn't change with display refresh rate;
        // dt is clamped so a tick resumed after a stall doesn't snap
        final float dt = Math.min(64, now - zoomFollowLastTick);
        zoomFollowLastTick = now;
        final float alpha = 1f - (float) Math.pow(0.65f, dt / 16.67f);
        zoomFollowCurrent += (zoomFollowTarget - zoomFollowCurrent) * alpha;
        final boolean last = Math.abs(zoomFollowTarget - zoomFollowCurrent) < 0.003f;
        if (last) {
            zoomFollowCurrent = zoomFollowTarget;
            zoomFollowing = false;
        } else {
            postOnAnimation(zoomFollowerTick);
        }
        applyZoomNow(zoomFollowCurrent, last);
    }

    private void stopZoomFollower() {
        zoomFollowing = false;
        removeCallbacks(zoomFollowerTick);
    }

    private void applyZoomNow(float fraction, boolean force) {
        if (useCamera2) {
            final Camera2Session session = camera2SessionCurrent;
            if (session != null) {
                final float zoom = zoomFractionToRatio(session, fraction);
                // sensors quantize the crop rect anyway: skipping sub-0.4% ratio changes avoids pointless
                // repeating-request churn against the ~30fps capture stream
                if (!force && appliedZoomRatio > 0 && Math.abs(zoom / appliedZoomRatio - 1f) < 0.004f) {
                    return;
                }
                appliedZoomRatio = zoom;
                session.whenDone(() -> session.setZoom(zoom));
            }
        } else if (cameraSession != null) {
            cameraSession.setZoomRatio(camera1FractionToRatio(fraction));
        }
    }

    private void zoomStep(int direction) {
        if (isInPinchToZoomTouchMode) {
            return;
        }
        float step;
        if (useCamera2) {
            final Camera2Session session = camera2SessionCurrent;
            if (session == null) {
                return;
            }
            final float min = session.getMinZoom();
            final float max = session.getMaxZoom();
            // x1.25 per tap regardless of the sensor's range, instead of a quarter of the whole range at once
            step = max > min ? (float) (Math.log(1.25) / Math.log(max / min)) : 1f;
        } else {
            final float max = cameraSession == null ? 1f : cameraSession.getMaxZoomRatio();
            if (max <= 1f) {
                zoomHaptic(HapticFeedbackConstants.CLOCK_TICK);
                return;
            }
            step = (float) (Math.log(1.25) / Math.log(max));
        }
        final float target = Utilities.clamp(lockedZoom + direction * step, 1f, 0f);
        if (Math.abs(target - lockedZoom) <= 0.0001f) {
            zoomHaptic(HapticFeedbackConstants.CLOCK_TICK);
            return;
        }
        zoomHaptic(HapticFeedbackConstants.KEYBOARD_TAP);
        animateZoom(lockedZoom, target, zoomStepDuration(target - lockedZoom), CubicBezierInterpolator.EASE_OUT);
    }

    // NagramX (#video-hold-send): left/right flips the camera, up/down steps the zoom. Camera2 hops between the
    // lens stops (RoundLensPresets) when the camera has them, camera1 moves by a whole 1x; anything else falls back
    // to the zoom control's own step.
    private void onHoldSwipe(int swipe) {
        if (swipe == com.dazewell.gram.videonote.VideoHoldToSend.SWIPE_LEFT || swipe == com.dazewell.gram.videonote.VideoHoldToSend.SWIPE_RIGHT) {
            flipCamera();
            return;
        }
        final int direction = swipe == com.dazewell.gram.videonote.VideoHoldToSend.SWIPE_UP ? 1 : -1;
        if (isInPinchToZoomTouchMode) {
            return;
        }
        float target = -1f;
        if (useCamera2) {
            final Camera2Session session = camera2SessionCurrent;
            if (session == null) {
                return;
            }
            final float[] stops = session.isZoomRatioMode() ? com.dazewell.gram.helpers.RoundLensPresets.get(session.cameraId) : null;
            if (stops != null && stops.length >= 2) {
                final float current = zoomFractionToRatio(session, lockedZoom);
                float stop = -1f;
                for (float s : stops) { // ascending
                    if (direction > 0 ? s > current * 1.02f : s < current / 1.02f) {
                        stop = s;
                        if (direction > 0) {
                            break;
                        }
                    }
                }
                if (stop > 0f) {
                    target = getZoomControlValueFromCamera2(stop);
                } else {
                    zoomHaptic(HapticFeedbackConstants.CLOCK_TICK);
                    return;
                }
            }
        } else if (cameraSession != null && cameraSession.getMaxZoomRatio() > 1f) {
            final float max = cameraSession.getMaxZoomRatio();
            // the next whole zoom in the swipe's direction, so an off-integer start (pinch, rocker) skips none
            final float current = camera1FractionToRatio(lockedZoom);
            final float ratio = Utilities.clamp(direction > 0 ? (float) Math.floor(current * 1.02f) + 1f : (float) Math.ceil(current / 1.02f) - 1f, max, 1f);
            target = (float) (Math.log(ratio) / Math.log(max));
        }
        if (target < 0f) {
            zoomStep(direction);
        } else if (Math.abs(target - lockedZoom) <= 0.0001f) {
            zoomHaptic(HapticFeedbackConstants.CLOCK_TICK);
        } else {
            zoomHaptic(HapticFeedbackConstants.KEYBOARD_TAP);
            animateZoom(lockedZoom, target, zoomStepDuration(target - lockedZoom), CubicBezierInterpolator.EASE_OUT);
        }
    }

    private long zoomStepDuration(float delta) {
        return (long) (200 + 150 * Math.min(1f, Math.abs(delta) / 0.25f));
    }

    private void startZoomGlide() {
        if (isInPinchToZoomTouchMode) {
            return;
        }
        // end a possible in-flight tap animation first: its end listener resets zoomGliding
        cancelFinishZoomTransition();
        final float target = zoomGlideDirection > 0 ? 1f : 0f;
        final float distance = Math.abs(target - lockedZoom);
        if (distance <= 0.0001f) {
            zoomHaptic(HapticFeedbackConstants.CLOCK_TICK);
            return;
        }
        long fullRangeMs = 3000;
        float zoomRange = 0f;
        if (useCamera2 && camera2SessionCurrent != null) {
            zoomRange = camera2SessionCurrent.getMaxZoom() / camera2SessionCurrent.getMinZoom();
        } else if (!useCamera2 && cameraSession != null) {
            zoomRange = cameraSession.getMaxZoomRatio();
        }
        if (zoomRange > 1f) {
            // constant perceptual rate of ~1.7x per second, however many octaves the sensor has
            fullRangeMs = (long) (1000 * Math.log(zoomRange) / Math.log(1.7));
        }
        zoomGliding = true;
        zoomControlView.setButtonHeld(zoomGlideDirection);
        zoomHaptic(HapticFeedbackConstants.LONG_PRESS);
        final long duration = Math.max(200, (long) (distance * fullRangeMs));
        final float ramp = Math.min(0.5f, 200f / duration);
        animateZoom(lockedZoom, target, duration, t -> {
            // velocity ramps in over the first ~200ms so a hold doesn't lurch, then stays constant
            final float k = 1f / (1f - ramp / 2f);
            return t < ramp ? k * t * t / (2f * ramp) : k * (ramp / 2f + (t - ramp));
        });
    }

    private void stopZoomGlide() {
        zoomGliding = false;
        cancelFinishZoomTransition();
    }

    private void zoomHaptic(int feedback) {
        try {
            if (!NekoConfig.disableVibration.Bool()) {
                zoomControlView.performHapticFeedback(feedback);
            }
        } catch (Exception ignore) {}
    }

    private void showZoomLabel(float ratio) {
        zoomLabel.setText(String.format(Locale.US, "%.1fx", ratio));
        if (opened && zoomControlView.isZoomEnabled()) {
            if (!zoomLabelVisible) {
                zoomLabelVisible = true;
                zoomLabel.animate().alpha(1.0f).setDuration(120).start();
            }
            AndroidUtilities.cancelRunOnUIThread(zoomLabelHider);
            AndroidUtilities.runOnUIThread(zoomLabelHider, 800);
        }
    }

    private void hideZoomLabel() {
        zoomLabelVisible = false;
        zoomLabel.animate().alpha(0.0f).setDuration(180).start();
    }

    private void cancelZoomInteractions() {
        holdToSend.reset(); // NagramX (#video-hold-send): same open/close/pause/detach points a hold must not outlive
        AndroidUtilities.cancelRunOnUIThread(zoomGlideStarter);
        zoomGliding = false;
        cancelFinishZoomTransition();
        stopZoomFollower();
        AndroidUtilities.cancelRunOnUIThread(zoomLabelHider);
        zoomLabelVisible = false;
        zoomLabel.animate().cancel();
        zoomLabel.setAlpha(0.0f);
    }

    private void resetZoom() {
        cancelZoomInteractions();
        // NagramX: home is 1x, which past the ultrawide end of the slider isn't fraction 0. The camera about
        // to show may not exist yet (or be the one switched away from), so adaptZoomToSession finishes this
        zoomHomePending = true;
        lockedZoom = useCamera2 && camera2SessionCurrent != null ? getZoomControlValueFromCamera2(1f) : 0.0f;
        zoomControlView.setZoom(lockedZoom, false);
        applyLockedZoomToCamera();
    }

    private boolean zoomHomePending;

    // NagramX: called once camera2SessionCurrent is the camera about to show, before its zoom is applied.
    // carriedZoom is the ratio to keep across a flip, or 0 to leave the slider where it is
    private void adaptZoomToSession(float carriedZoom) {
        if (camera2SessionCurrent == null) {
            return;
        }
        final float target = zoomHomePending ? 1f : carriedZoom;
        zoomHomePending = false;
        if (target > 0f) {
            cancelFinishZoomTransition();
            lockedZoom = getZoomControlValueFromCamera2(target);
            zoomControlView.setZoom(lockedZoom, false);
        }
    }

    // NagramX: lens stops for the camera now showing; their slider spots depend on its zoom range
    private void updateZoomPresets(Camera2Session session, boolean hasZoom) {
        final boolean hadPresets = zoomControlView.hasPresets();
        applyZoomPresets(session, hasZoom);
        if (hadPresets != zoomControlView.hasPresets()) {
            updateTranslationY();
        }
    }

    private void applyZoomPresets(Camera2Session session, boolean hasZoom) {
        final float[] stops = hasZoom && session.isZoomRatioMode() ? com.dazewell.gram.helpers.RoundLensPresets.get(session.cameraId) : null;
        if (stops == null || stops.length < 2) {
            zoomControlView.setPresets(null, null);
            return;
        }
        final float[] fractions = new float[stops.length];
        final String[] labels = new String[stops.length];
        for (int i = 0; i < stops.length; i++) {
            fractions[i] = getZoomControlValueFromCamera2(stops[i]);
            labels[i] = com.dazewell.gram.helpers.RoundLensPresets.label(stops[i]);
        }
        zoomControlView.setPresets(fractions, labels);
    }

    private void updateZoomControlAvailability() {
        if (!useCamera2) {
            return;
        }
        final Camera2Session session = camera2SessionCurrent;
        final boolean hasZoom = session != null && session.getMaxZoom() > session.getMinZoom() * 1.01f;
        // keep the control laid out so its flip button stays; only the slider + rocker follow zoom
        zoomControlView.setZoomEnabled(hasZoom);
        if (session != null) {
            updateZoomPresets(session, hasZoom);
        }
        if (!hasZoom) {
            zoomLabelVisible = false;
            zoomLabel.animate().cancel();
            zoomLabel.setAlpha(0.0f);
        }
    }

    // NagramX: camera1 twin of zoomFractionToRatio; min is always 1x there
    private float camera1FractionToRatio(float fraction) {
        final float max = cameraSession == null ? 1f : cameraSession.getMaxZoomRatio();
        return max <= 1f ? 1f : (float) Math.pow(max, fraction);
    }

    public interface Delegate {

        View getFragmentView();
        void sendMedia(MediaController.PhotoEntry entry, VideoEditedInfo videoEditedInfo, boolean notify, int scheduleDate, int scheduleRepeatPeriod, boolean b1, long stars);

        // NagramX: infinite video message: state for the in-recording toggle. Unavailable covers both "this
        // chat can't take unattended segments" and "the segment ceiling is reached", since the button has
        // nothing useful to do in either case.
        default int getInfiniteRecordingState() {
            return INFINITE_RECORDING_UNAVAILABLE;
        }

        default int toggleInfiniteRecording() {
            return INFINITE_RECORDING_UNAVAILABLE;
        }

        // NagramX (#video-hold-send): the host's locked state and its locked-send button, for a hold on the circle
        default boolean isRecordLocked() {
            return false;
        }

        default void sendLockedRecording() {
        }

        // NagramX (#video-hold-send): the host's pause button, for a tap on the circle
        default void toggleRecordingPause() {
        }

        // NagramX (#video-hold-send): the same for the paused preview. hasVideoPreviewSend says the host sends from
        // it at all, which hands the preview's taps to the hold and its sound to the composer; story replies don't
        default boolean hasVideoPreviewSend() {
            return false;
        }

        default boolean canSendVideoPreview() {
            return false;
        }

        default void sendVideoPreview() {
        }

        // NagramX: infinite video message: send a finished segment while the recorder keeps going. The normal
        // sendMedia treats a round video as the end of the session and tears the camera down 3s later.
        default void sendMediaKeepRecording(MediaController.PhotoEntry entry, VideoEditedInfo videoEditedInfo, boolean notify, int scheduleDate, long stars) {
            sendMedia(entry, videoEditedInfo, notify, scheduleDate, 0, false, stars);
        }
        Activity getParentActivity();
        int getClassGuid();
        long getDialogId();

        // NagramX: current draft-guard generation, bumped by the host when a new recording starts or a
        // passcode-lock rebuild recreates the view. send() snapshots it so a stale finalize can be rejected
        // at delivery.
        default int getVideoDraftToken() {
            return 0;
        }

        default boolean isSecretChat() {
            return false;
        }

        default boolean isInScheduleMode() {
            return false;
        }
    }

    private float getCurrentZoom() {
        if (useCamera2) {
            return camera2SessionCurrent == null ? 1f : camera2SessionCurrent.getZoom();
        }
        return lockedZoom;
    }

    // NagramX: perceptual mapping: equal slider travel multiplies the zoom ratio equally, instead of the old
    // linear crop-factor mapping that packed 1x..2x into the first sliver of the track
    private float zoomFractionToRatio(Camera2Session session, float fraction) {
        final float min = session.getMinZoom();
        final float max = session.getMaxZoom();
        if (max <= min) {
            return min;
        }
        return (float) (min * Math.pow(max / min, fraction));
    }

    private float getZoomControlValueFromCamera2(float zoom) {
        final Camera2Session session = camera2SessionCurrent;
        if (session == null) {
            return lockedZoom;
        }
        final float min = session.getMinZoom();
        final float max = session.getMaxZoom();
        if (max <= min || zoom <= 0) {
            return 0f;
        }
        return Utilities.clamp((float) (Math.log(zoom / min) / Math.log(max / min)), 1f, 0f);
    }

    private void syncZoomControlView(float currentZoom) {
        if (zoomControlView == null || zoomControlView.isTouch()) {
            return;
        }
        if (useCamera2) {
            zoomControlView.setZoom(getZoomControlValueFromCamera2(currentZoom), false);
            showZoomLabel(currentZoom);
        } else {
            zoomControlView.setZoom(Utilities.clamp01(currentZoom), false);
            if (cameraSession != null && cameraSession.getMaxZoomRatio() > 1f) {
                showZoomLabel(camera1FractionToRatio(Utilities.clamp01(currentZoom)));
            }
        }
    }

    private void setLockedZoom(float zoom) {
        if (isInPinchToZoomTouchMode) {
            return;
        }
        zoom = Utilities.clamp(zoom, 1f, 0f);
        if (!zoomControlView.isDragging() && Math.abs(zoom - lockedZoom) > 0.05f) {
            // a big jump means a tap on the track: pull the knob back and ease over instead of teleporting
            final float from = lockedZoom;
            zoomControlView.setZoom(from, false);
            animateZoom(from, zoom, zoomStepDuration(zoom - from), CubicBezierInterpolator.EASE_OUT);
        } else {
            cancelFinishZoomTransition();
            setZoomFraction(zoom);
        }
    }

    private void cancelFinishZoomTransition() {
        if (finishZoomTransition != null) {
            finishZoomTransition.cancel();
            finishZoomTransition = null;
        }
    }

    private void applyLockedZoomToCamera() {
        followZoom(lockedZoom, true);
    }

    public void setUseFrontCamera(boolean useFront) {
        this.isFrontface = useFront;
        this.initialCameraFront = useFront;
    }
}
