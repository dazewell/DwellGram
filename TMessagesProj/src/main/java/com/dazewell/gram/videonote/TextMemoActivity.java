package com.dazewell.gram.videonote;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

/**
 * The text memo: a compose card over the chat wallpaper (or, as TextMemoCardActivity, floating over the screen), in its own task. The chat it
 * goes to isn't named, only whether that person is online and has unread messages, so it never touches the app lock: a locked app stays locked, and closing returns to the launcher. Text left unsent by
 * anything but Discard or Send stays as that chat's real draft, and the next memo opens with it. A send clears the chat's
 * draft, as any send does. The memo never writes over a draft it didn't open with, and shows one only when the app and
 * the chat would open without the passcode, or when the memo itself left it in the last five minutes. It also keeps
 * its hands off a chat open in the app, whose composer would write its own text back over the memo's on its next pause.
 * A chat open in a bubble isn't checked. When the recipient can't receive the memo, the draft goes to Saved Messages, where the memo itself goes.
 */
public class TextMemoActivity extends Activity {

    private static final String KEY_MEMO_DRAFT_HASH = "draft_hash";
    private static final String KEY_MEMO_DRAFT_AT = "draft_at";
    private static final int REQUEST_PHOTOS = 1;
    private static final long MEMO_DRAFT_WINDOW_MS = 5 * 60 * 1000;

    private int account = -1;
    private FrameLayout root;
    // Only with a backdrop; the wallpaper is set on it
    private SizeNotifierFrameLayout wallpaperRoot;
    private boolean backdrop;
    private View scrim;
    private LinearLayout content;
    private LinearLayout hero;
    private RLottieImageView plane;
    private TextView hint;
    private LinearLayout card;
    private TextView title;
    private TextView subtitle;
    private org.telegram.ui.Components.CounterView unreadView;
    private MemoRecipientStatus status;
    private ImageView closeButton;
    private FrameLayout inputBubble;
    private GradientDrawable inputBackground;
    private EditTextBoldCursor field;
    private ImageView sendButton;
    private boolean canSend;
    private boolean dismissing;
    private boolean heroHidden;
    // 0 until the recipient is resolved; a send before that waits for it in pendingText
    private long dialogId;
    private String pendingText;
    // Photos attached to the memo, as cache file paths; they go out with the text as its caption and never become a draft
    private final ArrayList<String> photos = new ArrayList<>();
    private ArrayList<String> pendingPhotos;
    private ImageView attachButton;
    private LinearLayout photoStrip;
    private HorizontalScrollView photoScroll;
    // The system picker is up over this screen: onStop must not read that as being sent away
    private boolean picking;
    // Picks still being copied, in pick order: a send now would leave them behind, and a second pick would pass the cap
    private final ArrayList<TextMemoPhotos.Pick> pending = new ArrayList<>();
    // Picks of this batch that were left out (past the limit, refused, failed), told once when the last copy resolves
    private int skippedInBatch;
    private final java.util.HashMap<String, android.graphics.Bitmap> videoThumbs = new java.util.HashMap<>();
    // The chat's draft the memo opened with, or null. A write goes ahead only while the chat still holds this very object,
    // so a draft replaced meanwhile (another device, the chat in the app) is never written over.
    private TLRPC.DraftMessage adoptedDraft;
    // 0 to 1: the dot on the send button that says the chat holds a draft the memo didn't open with, which a send clears
    private float draftDot;
    // Left before the recipient resolved: saved as the draft from the resolve callback
    private String pendingDraft;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ApplicationLoader.postInitApplication();
        Window window = getWindow();
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        // A restore after process death or a relaunch from history must not reopen it, and nothing replays the intent
        boolean genuine = savedInstanceState == null && intent != null
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0 && TextMemoShortcut.isGenuine(intent);
        TextMemoShortcut.consume(intent);
        int selected = UserConfig.selectedAccount;
        if (!genuine || !UserConfig.getInstance(selected).isClientActivated()) {
            finish();
            return;
        }
        account = selected;

        backdrop = hasBackdrop();
        if (backdrop) {
            // No white flash if the wallpaper takes a moment
            window.setBackgroundDrawable(new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray)));
            WindowCompat.setDecorFitsSystemWindows(window, false);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The translucent theme alone left the task opaque on a ColorOS launcher, so whatever was on screen wasn't
            // drawn behind the card and a solid wall showed instead. Ask for it at runtime too.
            setTranslucent(true);
        }
        createView();
        setContentView(root);
        if (!backdrop) {
            // A floating window just the card's size, docked at the bottom; adjustResize keeps it above the keyboard
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
        applyColors();
        animateIn();
        // After the views exist: the callback often runs right here, when the recipient is already in memory
        VideoNoteTarget.resolveAsync(account, false, id -> {
            dialogId = id;
            if (pendingText != null) {
                sendTo(pendingText, pendingPhotos, id);
                pendingText = null;
                pendingPhotos = null;
            } else if (pendingDraft != null) {
                writeDraft(pendingDraft);
                pendingDraft = null;
            } else if (!dismissing && !isFinishing()) {
                restoreDraft();
                showDraftDot();
            }
            // The callback can land after a quick dismiss destroyed the activity, and observers started then would leak it
            if (!dismissing && !isFinishing() && !isDestroyed()) {
                status = new MemoRecipientStatus(account, id, this::updateStatus);
                status.start();
            }
        });
    }

    /** The wallpaper, scrim and plane behind the card. TextMemoCardActivity has none: the card floats over the screen. */
    protected boolean hasBackdrop() {
        return true;
    }

    private void createView() {
        if (backdrop) {
            createBackdrop();
        } else {
            root = new FrameLayout(this);
        }
        createCard();
    }

    private void createBackdrop() {
        // The chat wallpaper behind the card, as the video memo's shield shows: Telegram's own look, but no chat. A
        // see-through window was tried first; a launcher can drop its home screen for a second or two when it hands
        // over, and that showed through as black.
        wallpaperRoot = new SizeNotifierFrameLayout(this) {
            // There's no action bar here; left on, the wallpaper is clipped below where one would be
            @Override
            protected boolean isActionBarVisible() {
                return false;
            }

            @Override
            protected boolean isStatusBarVisible() {
                return false;
            }
        };
        root = wallpaperRoot;
        // Non-blocking: getCachedWallpaper waits on an in-flight load. On a cold start nothing has loaded it yet, so it
        // loads off the UI thread and arrives through didSetNewWallpapper; the window's gray shows until then.
        Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
        if (wallpaper != null) {
            wallpaperRoot.setBackgroundImage(wallpaper, Theme.isWallpaperMotion());
        } else {
            NotificationCenter.getGlobalInstance().addObserver(wallpaperObserver, NotificationCenter.didSetNewWallpapper);
            Theme.loadWallpaper(true);
        }
        // A tap outside closes an empty card like any dialog, but never throws away typed text
        root.setOnClickListener(v -> {
            if (field.length() == 0 && photos.isEmpty()) {
                dismiss(false);
            }
        });

        // Keeps the status icons legible over the wallpaper
        scrim = new View(this);
        root.addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 120, Gravity.TOP));

        // The wallpaper and scrim run edge to edge; everything else sits inside the system bars and above the keyboard
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            // Frozen while leaving: the keyboard hides at the start of an exit, and the card leaves on its own motion
            // rather than jumping down with the inset
            if (!dismissing) {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime() | WindowInsetsCompat.Type.displayCutout());
                content.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return WindowInsetsCompat.CONSUMED;
        });

        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        content.addView(hero, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        // Too little room above the card, with the keyboard up on a short screen: the plane steps aside
        hero.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> setHeroHidden(b - t < dp(120)));

        plane = new RLottieImageView(this);
        plane.setScaleType(ImageView.ScaleType.CENTER);
        plane.setAnimation(R.raw.plane_logo_plain, 40, 40);
        hero.addView(plane, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL));

        hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(12), dp(6), dp(12), dp(6));
        hint.setText(LocaleController.getString(R.string.TextMemoHint));
        hero.addView(hint, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));
    }

    private void createCard() {
        card = new LinearLayout(this);
        card.setClickable(true);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setElevation(dp(8));
        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        if (backdrop) {
            content.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 8, 8, 8));
        } else {
            // The margin is room for the shadow inside a window that is only as big as the card
            root.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 16, 16, 16, 16));
        }

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        // You, as the sender: the recipient stays off screen, but the card still has a face
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        BackupImageView avatar = new BackupImageView(this);
        avatar.setRoundRadius(dp(18));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        if (self != null) {
            avatarDrawable.setInfo(account, self);
            avatar.setForUserOrChat(self, avatarDrawable);
        } else {
            avatar.setImageDrawable(avatarDrawable);
        }
        header.addView(avatar, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        header.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 8, 0));

        title = new TextView(this);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(self != null ? UserObject.getUserName(self) : LocaleController.getString(R.string.TextMemoShortcutLabel));
        names.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        subtitle = new TextView(this);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        subtitle.setSingleLine(true);
        subtitle.setText(LocaleController.getString(R.string.TextMemoShortcutLabel));
        subtitle.setVisibility(self != null ? View.VISIBLE : View.GONE);
        names.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        // The recipient's unread count, the chat list's own pill
        unreadView = new org.telegram.ui.Components.CounterView(this, null);
        unreadView.setColors(Theme.key_chats_unreadCounterText, Theme.key_chats_unreadCounter);
        unreadView.setVisibility(View.GONE);
        // Fixed width: CounterView measures like a bare View, so wrap_content would take the whole row from the names
        unreadView.setGravity(Gravity.RIGHT);
        header.addView(unreadView, LayoutHelper.createLinear(44, 24, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        closeButton = new ImageView(this);
        closeButton.setScaleType(ImageView.ScaleType.CENTER);
        closeButton.setImageResource(R.drawable.ic_close_white);
        closeButton.setContentDescription(LocaleController.getString(R.string.Discard));
        closeButton.setOnClickListener(v -> dismiss(true));
        header.addView(closeButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        photoScroll = new HorizontalScrollView(this);
        photoScroll.setHorizontalScrollBarEnabled(false);
        photoScroll.setVisibility(View.GONE);
        photoStrip = new LinearLayout(this);
        photoStrip.setOrientation(LinearLayout.HORIZONTAL);
        photoScroll.addView(photoStrip, new FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        card.addView(photoScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 72, 0, 8, 0, 0));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        attachButton = new ImageView(this);
        attachButton.setScaleType(ImageView.ScaleType.CENTER);
        attachButton.setImageResource(R.drawable.msg_photos_solar);
        attachButton.setContentDescription(LocaleController.getString(R.string.TextMemoAddPhotos));
        attachButton.setOnClickListener(v -> pickPhotos());
        inputRow.addView(attachButton, LayoutHelper.createLinear(44, 44, Gravity.BOTTOM, 0, 0, 4, 4));

        // The input drawn like the composer's bubble, tinted with the send colour, and outlined harder while focused
        inputBubble = new FrameLayout(this);
        inputBackground = new GradientDrawable();
        inputBackground.setCornerRadius(dp(18));
        inputBubble.setBackground(inputBackground);
        inputBubble.setPadding(dp(14), dp(8), dp(14), dp(8));
        inputBubble.setMinimumHeight(dp(44));
        // Bottom edge level with the send circle, which sits 4dp inside its view
        inputRow.addView(inputBubble, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.BOTTOM, 0, 0, 0, 4));

        field = new EditTextBoldCursor(this);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setMinLines(1);
        field.setMaxLines(6);
        field.setVerticalScrollBarEnabled(true);
        // The composer's own flags, so the keyboard behaves as it does in any chat
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setHint(LocaleController.getString(R.string.TypeMessage));
        field.setCursorSize(dp(20));
        field.setCursorWidth(1.5f);
        field.setOnFocusChangeListener((v, hasFocus) -> animateOutline(hasFocus));
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSendButton(true);
            }
        });
        inputBubble.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        sendButton = new ImageView(this) {
            private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            // The draft dot on the circle's top end corner, ringed in the card colour to stand off the accent
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (draftDot <= 0f) {
                    return;
                }
                float cx = LocaleController.isRTL ? dp(10) : getWidth() - dp(10);
                float cy = dp(10);
                dotPaint.setColor(Theme.getColor(Theme.key_dialogBackground));
                canvas.drawCircle(cx, cy, dp(7) * draftDot, dotPaint);
                dotPaint.setColor(Theme.getColor(Theme.key_chats_draft));
                canvas.drawCircle(cx, cy, dp(5) * draftDot, dotPaint);
            }
        };
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setImageResource(R.drawable.ic_send);
        sendButton.setContentDescription(LocaleController.getString(R.string.Send));
        sendButton.setOnClickListener(v -> send());
        // 52dp around a 44dp circle, so its pop on waking stays inside its own bounds and nothing clips it
        inputRow.addView(sendButton, LayoutHelper.createLinear(52, 52, Gravity.BOTTOM, 4, 0, 0, 0));

        updateSendButton(false);
    }

    private void applyColors() {
        if (backdrop) {
            applyBackdropColors();
        }
        applyCardColors();
    }

    private void applyBackdropColors() {
        boolean dark = Theme.isCurrentThemeDark();
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false); // the scrim keeps the top dark enough for light icons
        controller.setAppearanceLightNavigationBars(!dark);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{dark ? 0x40000000 : 0x33000000, 0}));

        // The send colour, as on the card's send button. The flat service colour is only the fallback for the chat's darkened
        // wallpaper shader, and on a pale wallpaper it left the white plane barely visible.
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(Theme.getColor(Theme.key_chat_messagePanelSend));
        plane.setBackground(disc);
        // A fixed dark pill for the same reason: the flat service colour washed out over a pale wallpaper
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(14));
        pill.setColor(0x66000000);
        hint.setBackground(pill);
        hint.setTextColor(Color.WHITE);
    }

    private void applyCardColors() {
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setCornerRadius(dp(24));
        cardBackground.setColor(Theme.getColor(Theme.key_dialogBackground));
        card.setBackground(cardBackground);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        closeButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogTextGray2), PorterDuff.Mode.SRC_IN));
        closeButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        attachButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogTextGray2), PorterDuff.Mode.SRC_IN));
        attachButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));

        int accent = Theme.getColor(Theme.key_chat_messagePanelSend);
        inputBackground.setColor(Theme.multAlpha(accent, .10f));
        inputBackground.setStroke(dp(1), Theme.multAlpha(accent, .25f));
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setCursorColor(accent);

        GradientDrawable sendBackground = new GradientDrawable();
        sendBackground.setShape(GradientDrawable.OVAL);
        sendBackground.setColor(accent);
        sendButton.setBackground(new InsetDrawable(sendBackground, dp(4)));
        // The colour the record button's icon uses on the same accent, which a light accent needs over plain white
        sendButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelVoicePressed), PorterDuff.Mode.SRC_IN));
    }

    private void animateIn() {
        if (backdrop) {
            animateBackdropIn();
        }
        card.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                card.getViewTreeObserver().removeOnPreDrawListener(this);
                card.setTranslationY(card.getHeight() + dp(8));
                card.setScaleX(.96f);
                card.setScaleY(.96f);
                card.animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                return true;
            }
        });
        field.requestFocus();
        AndroidUtilities.showKeyboard(field);
    }

    private void animateBackdropIn() {
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(250).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
        plane.setAlpha(0f);
        plane.setTranslationY(dp(12));
        plane.setScaleX(.8f);
        plane.setScaleY(.8f);
        plane.animate().translationY(0).scaleX(1f).scaleY(1f).setStartDelay(100).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        plane.animate().alpha(1f).setStartDelay(100).setDuration(200).start();
        hint.setAlpha(0f);
        hint.animate().alpha(1f).setStartDelay(100).setDuration(200).start();
        // The plane hovers on a continuous loop, as on the QR login screen; a rest between loops read as a stall. Set on the
        // drawable: the view's own setAutoRepeat only applies to an animation loaded after it.
        if (plane.getAnimatedDrawable() != null) {
            plane.getAnimatedDrawable().setAutoRepeat(1);
        }
        plane.postDelayed(plane::playAnimation, 220);
    }

    private void playPlane() {
        if (plane.getAnimatedDrawable() != null) {
            plane.getAnimatedDrawable().setCurrentFrame(0, false);
        }
        plane.playAnimation();
    }

    private void setHeroHidden(boolean hidden) {
        if (hidden != heroHidden && !dismissing) {
            heroHidden = hidden;
            hero.animate().alpha(hidden ? 0f : 1f).setDuration(150).start();
        }
    }

    private void animateOutline(boolean focused) {
        int accent = Theme.getColor(Theme.key_chat_messagePanelSend);
        float from = focused ? 0f : 1f;
        ValueAnimator animator = ValueAnimator.ofFloat(from, 1f - from);
        animator.setDuration(150);
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            inputBackground.setStroke(AndroidUtilities.dp(1 + .5f * t), Theme.multAlpha(accent, .25f + .75f * t));
        });
        animator.start();
    }

    private void updateSendButton(boolean animated) {
        boolean hasText = (!TextUtils.isEmpty(getText()) || !photos.isEmpty()) && pending.isEmpty();
        if (hasText == canSend && animated) {
            return;
        }
        canSend = hasText;
        sendButton.setClickable(hasText);
        if (!animated) {
            sendButton.setAlpha(hasText ? 1f : .5f);
            sendButton.setScaleX(hasText ? 1f : .85f);
            sendButton.setScaleY(hasText ? 1f : .85f);
        } else if (hasText) {
            // A pop past full size as it wakes up
            sendButton.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f).setDuration(110).setInterpolator(CubicBezierInterpolator.EASE_OUT).withEndAction(() ->
                    sendButton.animate().scaleX(1f).scaleY(1f).setDuration(70).setInterpolator(CubicBezierInterpolator.EASE_IN).start()).start();
        } else {
            sendButton.animate().alpha(.5f).scaleX(.85f).scaleY(.85f).setDuration(150).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
    }

    private String getText() {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private void send() {
        String text = getText();
        if (dismissing || !pending.isEmpty() || text.isEmpty() && photos.isEmpty()) {
            return;
        }
        sendButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        // Sent now, not after the animation, so the message isn't held up by it
        ArrayList<String> sentPhotos = new ArrayList<>(photos);
        photos.clear(); // the send owns the files now; dismissal must not delete them
        if (dialogId != 0) {
            sendTo(text, sentPhotos, dialogId);
        } else {
            pendingText = text; // goes out from the resolve callback, which outlives this screen
            pendingPhotos = sentPhotos;
        }
        beginExit();
        // One thing moves at a time: the text lifts out of the card to the plane, the plane takes off, then the card drops
        sendButton.animate().alpha(.5f).scaleX(.85f).scaleY(.85f).setDuration(150).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        if (!text.isEmpty()) {
            flyToPlane(text);
        }
        field.setAlpha(0f);
        if (backdrop) {
            hint.animate().alpha(0f).setStartDelay(120).setDuration(150).start();
            AndroidUtilities.runOnUIThread(this::takeOff, 200);
        }
        exitCard(280);
    }

    // The text, as an outgoing bubble over the input, flies into the plane
    private void flyToPlane(String text) {
        try {
            TextView bubble = new TextView(this);
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            bubble.setMaxLines(4);
            bubble.setEllipsize(TextUtils.TruncateAt.END);
            bubble.setText(text);
            bubble.setTextColor(Theme.getColor(Theme.key_chat_messageTextOut));
            bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
            GradientDrawable bubbleBackground = new GradientDrawable();
            bubbleBackground.setCornerRadius(dp(16));
            bubbleBackground.setColor(Theme.getColor(Theme.key_chat_outBubble));
            bubble.setBackground(bubbleBackground);
            bubble.setElevation(dp(2));

            // Same box as the input; the root has no padding, so window coordinates relative to it place it exactly
            int[] rootAt = new int[2];
            int[] inputAt = new int[2];
            root.getLocationInWindow(rootAt);
            inputBubble.getLocationInWindow(inputAt);
            root.addView(bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            bubble.measure(View.MeasureSpec.makeMeasureSpec(inputBubble.getWidth(), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            float startX = inputAt[0] - rootAt[0];
            float startY = inputAt[1] - rootAt[1];
            float endX;
            float endY;
            if (backdrop) {
                int[] planeAt = new int[2];
                plane.getLocationInWindow(planeAt);
                endX = planeAt[0] - rootAt[0] + plane.getWidth() / 2f - bubble.getMeasuredWidth() / 2f;
                endY = planeAt[1] - rootAt[1] + plane.getHeight() / 2f - bubble.getMeasuredHeight() / 2f;
            } else {
                // No plane, and a window as small as the card would clip anything leaving it: it shrinks into the input
                endX = startX + (inputBubble.getWidth() - bubble.getMeasuredWidth()) / 2f;
                endY = startY + (inputBubble.getHeight() - bubble.getMeasuredHeight()) / 2f;
            }
            bubble.setTranslationX(startX);
            bubble.setTranslationY(startY);
            ValueAnimator flight = ValueAnimator.ofFloat(0f, 1f);
            flight.setDuration(280);
            flight.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
            flight.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                bubble.setTranslationX(startX + (endX - startX) * t);
                bubble.setTranslationY(startY + (endY - startY) * t);
                bubble.setScaleX(1f - .7f * t);
                bubble.setScaleY(1f - .7f * t);
                // Gone by the time it reaches the plane, over its last 80 ms
                bubble.setAlpha(Math.min(1f, (1f - t) * 280 / 80f));
            });
            flight.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    root.removeView(bubble);
                }
            });
            flight.start();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // A catch as the text lands, then the plane leaves toward the top end corner
    private void takeOff() {
        if (isFinishing()) {
            return;
        }
        float direction = LocaleController.isRTL ? -1 : 1;
        plane.animate().scaleX(1.12f).scaleY(1.12f).setStartDelay(0).setDuration(90).setInterpolator(CubicBezierInterpolator.EASE_OUT).withEndAction(() -> {
            playPlane();
            ValueAnimator flight = ValueAnimator.ofFloat(0f, 1f);
            flight.setDuration(260);
            flight.setInterpolator(CubicBezierInterpolator.EmphasizedAccelerate);
            flight.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                plane.setTranslationX(direction * dp(120) * t);
                plane.setTranslationY(-dp(150) * t);
                plane.setRotation(-12 * direction * t);
                plane.setScaleX(1.12f - .42f * t);
                plane.setScaleY(1.12f - .42f * t);
                plane.setAlpha(Math.min(1f, (1f - t) * 260 / 140f));
            });
            flight.start();
        }).start();
    }

    // Fills the field with the chat's draft when it's plain text the memo can show and send back unchanged
    private void restoreDraft() {
        MediaDataController drafts = MediaDataController.getInstance(account);
        TLRPC.DraftMessage draft = drafts.getDraft(dialogId, 0);
        if (draft == null || field.length() != 0 || !isPlainDraft(draft) || drafts.getDraftVoice(dialogId, 0) != null
                || !canShowDraft() && !isRecentMemoDraft(draft) || isChatOpen()) {
            return;
        }
        adoptedDraft = draft;
        field.setText(draft.message);
        field.setSelection(field.length());
    }

    /**
     * A send clears the chat's draft, so a draft the memo didn't open with (the app is locked, it isn't plain text, the
     * chat is open in the app) gets a dot on the send button. It says only that one is waiting, never what or for whom.
     * Leaving without sending keeps it: no write goes over a draft the memo didn't open with.
     */
    private void showDraftDot() {
        TLRPC.DraftMessage draft = MediaDataController.getInstance(account).getDraft(dialogId, 0);
        if (!(draft instanceof TLRPC.TL_draftMessage) || draft == adoptedDraft) {
            return;
        }
        sendButton.setContentDescription(LocaleController.getString(R.string.Send) + ". " + LocaleController.getString(R.string.TextMemoDraftWaiting));
        if (!sendButton.isLaidOut()) {
            // Known as the card opens: there from the first frame
            draftDot = 1f;
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(200);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        animator.addUpdateListener(a -> {
            draftDot = (float) a.getAnimatedValue();
            sendButton.invalidate();
        });
        animator.start();
    }

    // No reply, formatting, media, effect or anything else a plain text box would drop on its way back to the chat
    private static boolean isPlainDraft(TLRPC.DraftMessage draft) {
        return draft instanceof TLRPC.TL_draftMessage && !TextUtils.isEmpty(draft.message) && draft.reply_to == null
                && (draft.entities == null || draft.entities.isEmpty()) && draft.media == null && !draft.invert_media
                && draft.rich_message == null && draft.effect == 0 && draft.suggested_post == null;
    }

    /**
     * The memo opens over a locked app, so it shows a draft only when the app would open without the passcode right now,
     * and the chat itself isn't locked. AndroidUtilities.needShowPasscode's test, without the wasInBackground flag that a
     * call consumes.
     */
    private boolean canShowDraft() {
        // As ChatActivity has it: a locked chat already unlocked while the app stayed in the foreground is open
        if (com.dazewell.gram.chatlock.ChatLockController.isLocked(account, dialogId)
                && !com.dazewell.gram.chatlock.ChatLockController.isUnlocked(account, dialogId)) {
            return false;
        }
        if (SharedConfig.passcodeHash.length() == 0) {
            return true;
        }
        int uptime = (int) (SystemClock.elapsedRealtime() / 1000);
        return !SharedConfig.appLocked && SharedConfig.autoLockIn != 1
                && !(SharedConfig.autoLockIn != 0 && SharedConfig.lastPauseTime != 0 && SharedConfig.lastPauseTime + SharedConfig.autoLockIn <= uptime)
                && uptime + 5 >= SharedConfig.lastPauseTime;
    }

    /**
     * A draft the memo itself left in the last five minutes comes back even while the app or the chat is locked, so a
     * memo closed by accident isn't lost behind the passcode. Drafts typed in the chat stay locked. Only a hash of the chat
     * and the text is kept, with when it was left, never the text.
     */
    private boolean isRecentMemoDraft(TLRPC.DraftMessage draft) {
        try {
            SharedPreferences prefs = memoPrefs(account);
            long age = System.currentTimeMillis() - prefs.getLong(KEY_MEMO_DRAFT_AT, 0);
            String hash = prefs.getString(KEY_MEMO_DRAFT_HASH, null);
            return hash != null && age >= 0 && age <= MEMO_DRAFT_WINDOW_MS && hash.equals(memoDraftHash(dialogId, draft.message));
        } catch (Throwable e) {
            return false;
        }
    }

    // Empty text forgets it: a cleared draft has nothing to bring back
    private void rememberMemoDraft(String text) {
        String hash = text.isEmpty() ? null : memoDraftHash(dialogId, text);
        SharedPreferences.Editor editor = memoPrefs(account).edit();
        if (hash == null) {
            editor.clear();
        } else {
            editor.putString(KEY_MEMO_DRAFT_HASH, hash).putLong(KEY_MEMO_DRAFT_AT, System.currentTimeMillis());
        }
        editor.apply();
    }

    private static String memoDraftHash(long dialogId, String text) {
        try {
            return Utilities.bytesToHex(MessageDigest.getInstance("SHA-256").digest((dialogId + "\n" + text).getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable e) {
            return null;
        }
    }

    private static SharedPreferences memoPrefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("textmemo_" + account, 0);
    }

    /** VideoNoteTarget.clearAccountState, on logout. */
    static void clearAccountState(int account) {
        try {
            memoPrefs(account).edit().clear().apply();
        } catch (Throwable ignore) {
        }
    }

    // The chat open anywhere in the app: its composer writes its own text back as the draft when it next pauses
    private boolean isChatOpen() {
        LaunchActivity launch = LaunchActivity.instance;
        if (launch == null) {
            return false;
        }
        for (INavigationLayout layout : new INavigationLayout[]{launch.actionBarLayout, launch.getActionBarLayout(), launch.getRightActionBarLayout(), launch.getLayersActionBarLayout()}) {
            if (layout == null || layout.getFragmentStack() == null) {
                continue;
            }
            for (BaseFragment fragment : layout.getFragmentStack()) {
                if (fragment instanceof ChatActivity && fragment.getCurrentAccount() == account && ((ChatActivity) fragment).getDialogId() == dialogId) {
                    return true;
                }
            }
        }
        return false;
    }

    // Saves the text as the chat's draft, empty clearing it, unless the chat's draft changed since the memo opened
    private void writeDraft(String text) {
        if (dialogId == 0) {
            pendingDraft = text;
            return;
        }
        try {
            MediaDataController drafts = MediaDataController.getInstance(account);
            if (drafts.getDraft(dialogId, 0) == adoptedDraft && !isChatOpen()) {
                boolean memoText = adoptedDraft == null || !text.equals(adoptedDraft.message);
                drafts.saveDraft(dialogId, 0, text, null, null, adoptedDraft != null && adoptedDraft.no_webpage, 0);
                // A save while the picker is up isn't the last: the next write must still pass the identity check above
                adoptedDraft = drafts.getDraft(dialogId, 0);
                // Only text the memo wrote: a chat's own draft reopened and left as it was stays behind the lock
                if (memoText) {
                    rememberMemoDraft(text);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void sendTo(String text, List<String> sentPhotos, long dialogId) {
        try {
            if (sentPhotos != null && !sentPhotos.isEmpty()) {
                TextMemoPhotos.send(AccountInstance.getInstance(account), sentPhotos, text, dialogId);
                return;
            }
            SendMessagesHelper.prepareSendingText(AccountInstance.getInstance(account), text, dialogId, true, 0, 0, 0);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void pickPhotos() {
        if (dismissing || !pending.isEmpty() || photos.size() >= TextMemoPhotos.MAX) {
            return;
        }
        try {
            picking = true;
            startActivityForResult(TextMemoPhotos.pickIntent(), REQUEST_PHOTOS);
        } catch (Throwable e) {
            picking = false;
            FileLog.e(e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        picking = false;
        if (requestCode != REQUEST_PHOTOS || resultCode != RESULT_OK || dismissing || isFinishing()) {
            return;
        }
        ArrayList<TextMemoPhotos.Pick> picks = new ArrayList<>();
        int room = TextMemoPhotos.MAX - photos.size() - pending.size();
        for (Uri uri : TextMemoPhotos.urisOf(data)) {
            if (picks.size() < room) {
                picks.add(new TextMemoPhotos.Pick(uri));
            } else {
                skippedInBatch++;
            }
        }
        if (picks.isEmpty()) {
            reportSkipped();
            return;
        }
        pending.addAll(picks);
        refreshPhotoStrip();
        updatePending();
        TextMemoPhotos.copyAsync(picks, copyListener);
    }

    private final TextMemoPhotos.Listener copyListener = new TextMemoPhotos.Listener() {
        @Override
        public void onProgress(TextMemoPhotos.Pick pick, float progress) {
            pick.progress = progress;
            if (pick.ring != null && !pick.cancelled) {
                pick.ring.setNoProgress(false);
                pick.ring.setProgress(progress);
            }
        }

        @Override
        public void onDone(TextMemoPhotos.Pick pick, String path) {
            if (pick.cancelled || !pending.remove(pick) || dismissing || isFinishing()) {
                // Cancelled or left meanwhile: nothing will send it
                if (path != null) {
                    TextMemoPhotos.discard(java.util.Collections.singletonList(path));
                }
                return;
            }
            pick.ring = null;
            if (path == null) {
                skippedInBatch++;
            } else {
                photos.add(path);
            }
            refreshPhotoStrip();
            updatePending();
            reportSkipped();
        }
    };

    // Send and the photo button wait while any copy runs
    private void updatePending() {
        // A long copy is watched, not touched: a screen timeout would stop the memo and cancel it
        root.setKeepScreenOn(!pending.isEmpty());
        attachButton.setAlpha(pending.isEmpty() ? 1f : .5f);
        updateSendButton(true);
    }

    private void reportSkipped() {
        if (pending.isEmpty() && skippedInBatch > 0) {
            android.widget.Toast.makeText(this, LocaleController.formatString("TextMemoMediaSkipped", R.string.TextMemoMediaSkipped, skippedInBatch), android.widget.Toast.LENGTH_LONG).show();
            skippedInBatch = 0;
        }
    }

    // Every copy still running stops at its next chunk and deletes its partial file
    private void cancelPending() {
        for (TextMemoPhotos.Pick pick : pending) {
            pick.cancelled = true;
            pick.listener = null;
            pick.ring = null;
        }
        pending.clear();
        skippedInBatch = 0;
    }

    // A video's first frame, made off the UI thread once per file
    private void showVideoThumb(BackupImageView thumb, String path) {
        android.graphics.Bitmap known = videoThumbs.get(path);
        if (known != null) {
            thumb.setImageBitmap(known);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            android.graphics.Bitmap frame = null;
            try {
                frame = SendMessagesHelper.createVideoThumbnail(path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            android.graphics.Bitmap made = frame;
            AndroidUtilities.runOnUIThread(() -> {
                if (made != null && !isFinishing()) {
                    videoThumbs.put(path, made);
                    thumb.setImageBitmap(made);
                }
            });
        });
    }

    private void refreshPhotoStrip() {
        photoStrip.removeAllViews();
        photoScroll.setVisibility(photos.isEmpty() && pending.isEmpty() ? View.GONE : View.VISIBLE);
        for (String path : new ArrayList<>(photos)) {
            FrameLayout cell = new FrameLayout(this);
            BackupImageView thumb = new BackupImageView(this);
            thumb.setRoundRadius(dp(8));
            boolean video = TextMemoPhotos.isVideo(path);
            if (video) {
                showVideoThumb(thumb, path);
            } else {
                thumb.setImage(ImageLocation.getForPath(path), "64_64", (android.graphics.drawable.Drawable) null, null);
            }
            cell.addView(thumb, LayoutHelper.createFrame(64, 64, Gravity.BOTTOM | Gravity.START));
            if (video) {
                ImageView play = new ImageView(this);
                play.setScaleType(ImageView.ScaleType.CENTER);
                play.setImageResource(R.drawable.msg_round_play_m);
                cell.addView(play, LayoutHelper.createFrame(24, 24, Gravity.CENTER, 0, 4, 0, 0));
            }
            ImageView remove = new ImageView(this);
            remove.setScaleType(ImageView.ScaleType.CENTER);
            remove.setImageResource(R.drawable.ic_close_white);
            remove.setColorFilter(Color.WHITE);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0x99000000);
            remove.setBackground(new InsetDrawable(dot, dp(4)));
            remove.setContentDescription(LocaleController.getString(R.string.Delete));
            remove.setOnClickListener(v -> {
                if (!dismissing && photos.remove(path)) {
                    TextMemoPhotos.discard(java.util.Collections.singletonList(path));
                    refreshPhotoStrip();
                    updateSendButton(true);
                }
            });
            cell.addView(remove, LayoutHelper.createFrame(28, 28, Gravity.TOP | Gravity.END));
            photoStrip.addView(cell, LayoutHelper.createLinear(72, 72, 0, 0, 4, 0));
        }
        int accent = Theme.getColor(Theme.key_chat_messagePanelSend);
        for (TextMemoPhotos.Pick pick : new ArrayList<>(pending)) {
            FrameLayout cell = new FrameLayout(this);
            View tile = new View(this);
            GradientDrawable tileBackground = new GradientDrawable();
            tileBackground.setCornerRadius(dp(8));
            tileBackground.setColor(Theme.multAlpha(accent, .10f));
            tile.setBackground(tileBackground);
            cell.addView(tile, LayoutHelper.createFrame(64, 64, Gravity.BOTTOM | Gravity.START));
            RadialProgressView ring = new RadialProgressView(this);
            ring.setSize(dp(26));
            ring.setStrokeWidth(2.5f);
            ring.setProgressColor(accent);
            if (pick.progress >= 0) {
                ring.setNoProgress(false);
                ring.setProgress(pick.progress);
            }
            pick.ring = ring;
            cell.addView(ring, LayoutHelper.createFrame(32, 32, Gravity.CENTER, 0, 4, 0, 0));
            ImageView cancel = new ImageView(this);
            cancel.setScaleType(ImageView.ScaleType.CENTER);
            cancel.setImageResource(R.drawable.ic_close_white);
            cancel.setColorFilter(Color.WHITE);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0x99000000);
            cancel.setBackground(new InsetDrawable(dot, dp(4)));
            cancel.setContentDescription(LocaleController.getString(R.string.Delete));
            cancel.setOnClickListener(v -> {
                if (!dismissing && pending.remove(pick)) {
                    pick.cancelled = true;
                    pick.listener = null;
                    pick.ring = null;
                    refreshPhotoStrip();
                    updatePending();
                    reportSkipped();
                }
            });
            cell.addView(cancel, LayoutHelper.createFrame(28, 28, Gravity.TOP | Gravity.END));
            photoStrip.addView(cell, LayoutHelper.createLinear(72, 72, 0, 0, 4, 0));
        }
    }

    /**
     * Every way out but Send, where nothing flies, so it never looks sent. Discard (the close button) drops the text and
     * clears a draft the memo opened with; anything else keeps the text as the chat's draft.
     */
    private void dismiss(boolean discard) {
        if (dismissing) {
            return;
        }
        if (discard) {
            if (adoptedDraft != null) {
                writeDraft("");
            }
        } else {
            writeDraft(getText());
        }
        cancelPending();
        TextMemoPhotos.discard(photos);
        photos.clear();
        beginExit();
        field.setText("");
        if (backdrop) {
            hero.animate().alpha(0f).setDuration(120).start();
        }
        exitCard(0);
    }

    // Shared start of both exits: stop whatever the open or idle loop has running, freeze the insets, drop the keyboard (the card variant leaves both to finish())
    private void beginExit() {
        dismissing = true;
        sendButton.animate().cancel();
        if (backdrop) {
            plane.animate().cancel();
            hint.animate().cancel();
            AndroidUtilities.hideKeyboard(field);
        }
        // Without a backdrop the keyboard stays until finish(): the window sits on it, so hiding it now would drop the
        // window while the card is still leaving
        // A fallback in case an end action never runs
        root.postDelayed(() -> {
            if (!isFinishing()) {
                finish();
            }
        }, 800);
    }

    private void exitCard(long delay) {
        if (backdrop) {
            scrim.animate().alpha(0f).setStartDelay(delay + 20).setDuration(200).start();
        }
        card.animate().translationY(root.getHeight() - card.getTop()).scaleX(1f).scaleY(1f)
                .setStartDelay(delay).setDuration(220).setInterpolator(CubicBezierInterpolator.EmphasizedAccelerate)
                .withEndAction(() -> {
                    if (!isFinishing()) {
                        finish();
                    }
                }).start();
    }

    // Without a backdrop there is no full-screen view to tap, so a touch outside the card lands here
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!backdrop && root != null && event.getAction() == MotionEvent.ACTION_DOWN && field.length() == 0 && photos.isEmpty()
                && (event.getX() < card.getLeft() || event.getX() > card.getRight() || event.getY() < card.getTop() || event.getY() > card.getBottom())) {
            dismiss(false);
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (root == null) {
            super.onBackPressed();
            return;
        }
        dismiss(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A tap on the shortcut while this is up just keeps it; anything else closes it
        boolean genuine = TextMemoShortcut.isGenuine(intent);
        TextMemoShortcut.consume(intent);
        if (!genuine && root != null) {
            dismiss(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (account >= 0) {
            ConnectionsManager.getInstance(account).setAppPaused(false, false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (account >= 0) {
            ConnectionsManager.getInstance(account).setAppPaused(true, false);
        }
    }

    // Home, or anything else that hides the screen, closes it and keeps the text as the chat's draft
    @Override
    protected void onStop() {
        super.onStop();
        if (picking) {
            // The system picker covers this screen and the result comes back to it, but the user may leave from there
            // (Home, a call) and the text exists nowhere else: save it as the draft without finishing
            if (field != null && !dismissing) {
                writeDraft(getText());
            }
            return;
        }
        if (field != null && !dismissing) {
            dismissing = true;
            writeDraft(getText());
            cancelPending();
            TextMemoPhotos.discard(photos);
            photos.clear();
        }
        if (!isFinishing()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (status != null) {
            status.stop();
        }
        // Left from the picker and never came back: a send clears the list first, so what is left was never sent
        cancelPending();
        TextMemoPhotos.discard(photos);
        photos.clear();
        NotificationCenter.getGlobalInstance().removeObserver(wallpaperObserver, NotificationCenter.didSetNewWallpapper);
        super.onDestroy();
    }

    private void updateStatus() {
        if (status == null || subtitle == null || unreadView == null) {
            return;
        }
        String label = LocaleController.getString(R.string.TextMemoShortcutLabel);
        subtitle.setText(status.online ? LocaleController.formatString(R.string.MemoStatusJoin, label, LocaleController.getString(R.string.Online)) : label);
        if (status.online) {
            subtitle.setVisibility(View.VISIBLE);
        }
        unreadView.setColors(Theme.key_chats_unreadCounterText, status.muted ? Theme.key_chats_unreadCounterMuted : Theme.key_chats_unreadCounter);
        if (status.unread > 99) {
            unreadView.counterDrawable.setText("99+", true);
        } else if (status.unread > 0) {
            unreadView.setCount(status.unread, true);
        } else if (status.unreadMark) {
            unreadView.counterDrawable.setText("", true); // the bare dot the chat list draws for marked unread
        } else {
            unreadView.setCount(0, true);
        }
        unreadView.setContentDescription(status.unread > 0 ? LocaleController.formatPluralString("NewMessages", status.unread) : null);
        unreadView.invalidate();
    }

    // The wallpaper loaded after the screen opened. Nothing else needs recolouring: the disc and pill are fixed colours.
    private final NotificationCenter.NotificationCenterDelegate wallpaperObserver = (id, account, args) -> {
        if (id != NotificationCenter.didSetNewWallpapper || wallpaperRoot == null || isFinishing()) {
            return;
        }
        NotificationCenter.getGlobalInstance().removeObserver(this.wallpaperObserver, NotificationCenter.didSetNewWallpapper);
        Drawable loaded = Theme.getCachedWallpaperNonBlocking();
        if (loaded != null) {
            wallpaperRoot.setBackgroundImage(loaded, Theme.isWallpaperMotion());
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        AndroidUtilities.checkDisplaySize(this, newConfig);
    }
}
