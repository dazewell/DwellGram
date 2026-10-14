package com.dazewell.gram.headerbg;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ChatObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Components.ChatActivityTopPanelLayout;

import com.dazewell.gram.helpers.InterfaceStyleController;
import com.dazewell.gram.helpers.InterfaceStyleSolidHeader;

import java.lang.ref.WeakReference;

/**
 * Paints a chat's photo behind the MD3 flat chat header. ActionBar calls {@link #draw} right after
 * it paints the flat surface and before its children, so the title and icons stay on top and Glass,
 * which never takes that branch, can't show it. The flat strip panel under the header calls
 * {@link #drawPanel} the same way, so the photo carries on under the pinned message.
 * <p>One instance per ActionBar, owned by that ActionBar's field. The image and the avatar observer
 * follow the ActionBar's own window attachment rather than the fragment, because a theme or language
 * rebuild replaces the ActionBar while the fragment lives on.
 */
public final class HeaderBgDrawer implements NotificationCenter.NotificationCenterDelegate, View.OnAttachStateChangeListener {

    private final int account;
    private final long peerId;
    private final ChatActivity fragment;
    private final ActionBar actionBar;
    private final Theme.ResourcesProvider resourcesProvider;
    private final ImageReceiver imageReceiver;
    public final HeaderBgSettings settings;
    private boolean hasPhoto;

    /** The pinned bar's height: with the panel extension on, the photo always covers this much below the header. */
    private static final int PANEL_RESERVE_DP = 48;

    private ChatActivityTopPanelLayout panel;
    private final Fade headerFade = new Fade();
    private final Fade panelFade = new Fade();
    private final Fade scrimFade = new Fade();
    private int filterHue = Integer.MIN_VALUE, filterColor, filterStrength, filterDesaturate;
    // Shared by the receiver and the blurred copy so the two paths never tint differently.
    private ColorFilter photoFilter;

    // A small blurred copy of the receiver's bitmap, rebuilt on the main thread when the photo or the
    // level changes. Replaced copies are dropped, never recycled: a frame still in flight may draw one.
    private Bitmap blurred;
    private WeakReference<Bitmap> blurSource;
    private int blurSourceW, blurSourceH, blurLevel;
    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    // Where the photo lands in header coordinates; filled by frame() just before each use.
    private final RectF frameRect = new RectF();

    // Whether the status bar icons should be dark over what this header draws behind them, or null to
    // leave them to the chat. Filled by draw(); lightStatusBar() re-checks the live conditions, so a value
    // cached here never outlives the photo being drawn.
    private Boolean statusLight;
    private WeakReference<Bitmap> statusSource;
    private final HeaderBgSettings statusLook = new HeaderBgSettings();
    private int statusSurface, statusWidth, statusHeight, statusBarHeight, statusScrim;
    private boolean statusRtl;
    private ColorFilter statusFilter;
    // What the chat composites behind the status bar (its header surface over the wallpaper), handed over
    // by the chat as it recomputes it. Under a frosted header this is not the plain surface colour.
    private int statusBase;
    private boolean hasStatusBase;
    // draw() only runs for the flat MD3 header; stamped there and cleared on detach.
    private boolean drawn;
    // Whether the photo is fully shown behind the header. Written by the draw pass and cleared on detach;
    // held while selection mode covers the header, which has its own colours and fades the photo out and back.
    private boolean shown;
    // The foreground sets (HeaderBgForeground's THEME, LIGHT or DARK) the header and the pinned bar were last
    // pushed with. Written only by HeaderBgForeground's pushes and read by every colour lookup, so whatever is
    // read between two pushes matches what is on screen. Kept on detach: the views keep the colours.
    int applied = HeaderBgForeground.THEME;
    int appliedPin = HeaderBgForeground.THEME;
    private boolean repushPosted;
    /** Run after the icon choice changes, for the open sheet, whose own window draws the status bar meanwhile. */
    public Runnable onStatusIconsChanged;

    private HeaderBgDrawer(ChatActivity fragment, ActionBar actionBar) {
        this.account = fragment.getCurrentAccount();
        this.fragment = fragment;
        this.actionBar = actionBar;
        this.resourcesProvider = fragment.getResourceProvider();
        TLRPC.User user = fragment.getCurrentUser();
        this.peerId = user != null ? user.id : -fragment.getCurrentChat().id;
        this.settings = HeaderBgSettings.load(account, fragment.getDialogId());
        imageReceiver = new ImageReceiver(actionBar);
        imageReceiver.setCurrentAccount(account);
        imageReceiver.setCrossfadeWithOldImage(true);
        // The receiver only redraws the ActionBar; the panel repaints itself when a photo lands.
        imageReceiver.setDelegate((receiver, set, thumb, memCache) -> invalidatePanel());
        loadPhoto();
        actionBar.addOnAttachStateChangeListener(this);
        if (actionBar.isAttachedToWindow()) {
            onViewAttachedToWindow(actionBar);
        }
    }

    /**
     * Chats keyed to one peer's photo. Saved Messages, Replies and Verify draw a glyph, and saved or
     * monoforum sub-chats would key one setting to a header showing someone else. Forum topics and
     * comment threads share their chat's dialog id, so they show the chat's photo and setting.
     */
    public static boolean eligible(ChatActivity fragment) {
        if (fragment.getChatMode() != ChatActivity.MODE_DEFAULT || fragment.isInPreviewMode()
                || fragment.isInBubbleMode() || fragment.isInsideContainer) {
            return false;
        }
        TLRPC.User user = fragment.getCurrentUser();
        if (user != null) {
            return !UserObject.isUserSelf(user) && !UserObject.isReplyUser(user) && user.id != UserObject.VERIFY;
        }
        TLRPC.Chat chat = fragment.getCurrentChat();
        return chat != null && !ChatObject.isMonoForum(chat);
    }

    /** From createView: attaches a drawer only when this chat has the background turned on. */
    public static void install(ChatActivity fragment) {
        if (HeaderBgSettings.isEnabled(fragment.getCurrentAccount(), fragment.getDialogId())) {
            obtain(fragment);
        }
    }

    /** The drawer on the fragment's current ActionBar, created on demand for the sheet. */
    public static HeaderBgDrawer obtain(ChatActivity fragment) {
        ActionBar actionBar = fragment.getActionBar();
        if (actionBar == null || !eligible(fragment)) {
            return null;
        }
        if (actionBar.naxHeaderBg == null) {
            actionBar.naxHeaderBg = new HeaderBgDrawer(fragment, actionBar);
        }
        return actionBar.naxHeaderBg;
    }

    public boolean hasPhoto() {
        return hasPhoto;
    }

    public void invalidate() {
        actionBar.invalidate();
        invalidatePanel();
    }

    private void invalidatePanel() {
        if (panel != null) {
            panel.invalidate();
        }
    }

    // The strips under the header are the ActionBar's sibling in the chat's content view.
    private ChatActivityTopPanelLayout findPanel() {
        if (!(actionBar.getParent() instanceof ViewGroup)) {
            return null;
        }
        ViewGroup parent = (ViewGroup) actionBar.getParent();
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof ChatActivityTopPanelLayout) {
                return (ChatActivityTopPanelLayout) parent.getChildAt(i);
            }
        }
        return null;
    }

    private TLObject peer() {
        MessagesController mc = MessagesController.getInstance(account);
        return peerId > 0 ? mc.getUser(peerId) : mc.getChat(-peerId);
    }

    private void loadPhoto() {
        TLObject peer = peer();
        ImageLocation big = peer != null ? ImageLocation.getForUserOrChat(account, peer, ImageLocation.TYPE_BIG) : null;
        hasPhoto = big != null;
        if (!hasPhoto) {
            imageReceiver.clearImage();
            return;
        }
        // The small photo is what the header avatar already shows, so it is usually cached and
        // stands in until the big one arrives.
        ImageLocation small = ImageLocation.getForUserOrChat(account, peer, ImageLocation.TYPE_SMALL);
        imageReceiver.setImage(big, null, small, "50_50", (String) null, peer, 0);
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        // The observer was off while another screen covered the chat; pick up a photo changed meanwhile.
        loadPhoto();
        imageReceiver.onAttachedToWindow();
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.updateInterfaces);
        panel = findPanel();
        if (panel != null) {
            panel.naxHeaderBg = this;
            panel.invalidate();
        }
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        imageReceiver.onDetachedFromWindow();
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.updateInterfaces);
        if (panel != null && panel.naxHeaderBg == this) {
            panel.naxHeaderBg = null;
        }
        panel = null;
        blurred = null;
        blurSource = null;
        drawn = false;
        shown = false;
        statusLight = null;
        statusSource = null;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.updateInterfaces || args.length == 0 || !(args[0] instanceof Integer)) {
            return;
        }
        int mask = (Integer) args[0];
        if ((mask & (MessagesController.UPDATE_MASK_AVATAR | MessagesController.UPDATE_MASK_CHAT_AVATAR)) != 0) {
            loadPhoto();
            invalidate();
        }
    }

    public void draw(Canvas canvas, int width, int height) {
        // The receiver's crossfade, the selection-mode fade and any move of the bar only redraw the
        // ActionBar; the panel follows each header frame so it never keeps a stale one.
        if (settings.enabled && settings.extendPanel) {
            invalidatePanel();
        }
        drawn = true;
        View actionMode = actionBar.getActionMode();
        if (actionMode == null || actionMode.getVisibility() != View.VISIBLE) {
            shown = alpha() >= 1f;
        }
        updateStatusIcons(width, height);
        float alpha = alpha();
        if (alpha <= 0f || width <= 0 || height <= 0) {
            return;
        }
        canvas.save();
        canvas.clipRect(0, 0, width, height);
        paint(canvas, width, height, alpha, headerFade, surfaceColor(true));
        canvas.restore();
    }

    // Over the whole framed picture like the gradient, so with the panel extension it runs on under the pinned bar.
    // The header and the panel share the one shader, since nothing in it differs between them. Null when there is none.
    private Paint scrim(int width, int cover) {
        if (!settings.scrim || settings.scrimStrength <= 0) {
            return null;
        }
        return scrimFade.update(width, cover, settings.scrimFrom, scrimColor(), settings.scrimStrength,
                settings.scrimCurve, settings.scrimStart, settings.scrimEnd);
    }

    /**
     * The scrim's opaque colour. Auto goes against the header text the current theme shows, read from the setting
     * rather than the status bar probe, so the probe, which includes the scrim, never chases its own result.
     */
    public int scrimColor() {
        if (settings.scrimColor == HeaderBgSettings.SCRIM_BLACK) {
            return 0xff000000;
        }
        if (settings.scrimColor == HeaderBgSettings.SCRIM_WHITE) {
            return 0xffffffff;
        }
        int text = settings.text(false, isDark());
        boolean lightText;
        if (text == HeaderBgSettings.TEXT_LIGHT) {
            lightText = true;
        } else if (text == HeaderBgSettings.TEXT_DARK) {
            lightText = false;
        } else {
            int title = InterfaceStyleSolidHeader.chatHeaderColor(Theme.key_actionBarDefaultTitle,
                    Theme.getColor(Theme.key_actionBarDefaultTitle, resourcesProvider));
            lightText = AndroidUtilities.computePerceivedBrightness(title) > 0.5f;
        }
        return lightText ? 0xff000000 : 0xffffffff;
    }

    /**
     * The same photo in the header's coordinates, clipped to the panel's background, so the header
     * and the strips under it read as one picture.
     */
    public void drawPanel(Canvas canvas, View panel, int left, int right, float bottom, float panelAlpha) {
        if (!settings.extendPanel || right <= left || bottom <= 0) {
            return;
        }
        float alpha = alpha() * panelAlpha;
        int width = actionBar.getWidth();
        int height = actionBar.getHeight();
        if (alpha <= 0f || width <= 0 || height <= 0) {
            return;
        }
        canvas.save();
        canvas.clipRect(left, 0, right, bottom);
        // The Classic header is blue over a white strip; under the photo the strip takes the header's
        // surface instead, or the one picture shows as two tints split at the header's edge.
        int surface = surfaceColor(true);
        if (surface != surfaceColor(false)) {
            canvas.drawColor(ColorUtils.setAlphaComponent(surface, (int) (255 * alpha)));
        }
        canvas.translate(actionBar.getX() - panel.getX(), actionBar.getY() - panel.getY());
        paint(canvas, width, height, alpha, panelFade, surface);
        canvas.restore();
    }

    // 0 when nothing should show; otherwise how far the selection-mode fade lets it through.
    private float alpha() {
        if (!settings.enabled || !hasPhoto || imageReceiver.getBitmapWidth() <= 0 || imageReceiver.getBitmapHeight() <= 0
                || !InterfaceStyleController.applyChatHeader()) {
            return 0f;
        }
        // Selection mode swaps in its own bar; fade with it instead of popping. The bar is created
        // invisible at full alpha, so its alpha only counts while it is actually visible.
        View actionMode = actionBar.getActionMode();
        return actionMode != null && actionMode.getVisibility() == View.VISIBLE ? 1f - actionBar.getActionModeFactor() : 1f;
    }

    // Fills out with the photo's rect in header coordinates and returns the height it covers. Sized from
    // the receiver's bitmap, not the smaller blurred copy, so every path frames the photo the same.
    private int frame(int width, int height, RectF out) {
        int bw = imageReceiver.getBitmapWidth();
        int bh = imageReceiver.getBitmapHeight();
        // The reserve is fixed rather than the panel's live height, so the header's framing never
        // moves as the pinned bar comes and goes.
        int cover = settings.extendPanel ? height + AndroidUtilities.dp(PANEL_RESERVE_DP) : height;
        float scale = Math.max(width / (float) bw, cover / (float) bh) * settings.zoom / 100f;
        float dw = bw * scale;
        float dh = bh * scale;
        // Offsets pan within the overflow only, so no edge of the photo ever shows.
        float slackX = (dw - width) / 2f;
        float slackY = (dh - cover) / 2f;
        float x = -slackX - slackX * settings.offsetX / 100f;
        float y = -slackY - slackY * settings.offsetY / 100f;
        out.set(x, y, x + dw, y + dh);
        return cover;
    }

    private void paint(Canvas canvas, int width, int height, float alpha, Fade fade, int surface) {
        int cover = frame(width, height, frameRect);
        updateFilter();
        Bitmap blurredPhoto = settings.blur > 0 ? blurredPhoto() : null;
        if (blurredPhoto != null) {
            blurPaint.setAlpha((int) (255 * settings.opacity / 100f * alpha));
            blurPaint.setColorFilter(photoFilter);
            canvas.drawBitmap(blurredPhoto, null, frameRect, blurPaint);
        } else {
            imageReceiver.setImageCoords(frameRect.left, frameRect.top, frameRect.width(), frameRect.height());
            imageReceiver.setAlpha(settings.opacity / 100f * alpha);
            imageReceiver.draw(canvas);
        }
        if (settings.gradient && settings.gradientStrength > 0) {
            // A vertical fade runs over the whole framed picture, so with the reserve it reaches the pinned bar too.
            Paint paint = fade.update(width, cover, settings, surface);
            paint.setAlpha((int) (255 * alpha));
            canvas.drawPaint(paint);
        }
        Paint scrim = scrim(width, cover);
        if (scrim != null) {
            scrim.setAlpha((int) (255 * alpha));
            canvas.drawPaint(scrim);
        }
    }

    /** From the chat each time it recomputes the colour behind its status bar. */
    public void setStatusBarBase(int color) {
        if (!hasStatusBase || color != statusBase) {
            statusBase = color;
            hasStatusBase = true;
            invalidate();
        }
    }

    /** The chat's status bar icon choice while this header's photo is what sits behind them; null otherwise. */
    public static Boolean lightStatusBar(ActionBar actionBar) {
        HeaderBgDrawer drawer = actionBar != null ? actionBar.naxHeaderBg : null;
        if (drawer == null || !drawer.drawn || drawer.statusLight == null || drawer.alpha() < 1f || !actionBar.getOccupyStatusBar()) {
            return null;
        }
        return drawer.statusLight;
    }

    // Rebuilt only when what lands behind the status bar can have changed: the bitmap drawn, any look
    // setting, the filter, the surface, the size or the layout direction.
    private void updateStatusIcons(int width, int height) {
        Boolean light = null;
        Bitmap source = null;
        if (alpha() >= 1f && actionBar.getOccupyStatusBar() && width > 0 && height > 0 && AndroidUtilities.statusBarHeight > 0) {
            updateFilter();
            source = settings.blur > 0 ? blurredPhoto() : null;
            if (source == null) {
                source = imageReceiver.getBitmap();
            }
        }
        if (source == null || source.isRecycled()) {
            statusSource = null;
        } else {
            // The chat's composite when it has handed one over, otherwise the opaque surface alone.
            int surface = hasStatusBase ? ColorUtils.setAlphaComponent(statusBase, 255) : surfaceColor(true);
            // Auto's colour follows the theme without any setting changing, so it is compared on its own.
            int scrim = settings.scrim ? scrimColor() : 0;
            if (statusScrim == scrim && statusSource != null && statusSource.get() == source && statusLook.sameAs(settings) && statusSurface == surface
                    && statusWidth == width && statusHeight == height && statusBarHeight == AndroidUtilities.statusBarHeight
                    && statusRtl == LocaleController.isRTL && statusFilter == photoFilter) {
                light = statusLight;
            } else {
                statusSource = new WeakReference<>(source);
                statusLook.copyFrom(settings);
                statusSurface = surface;
                statusWidth = width;
                statusHeight = height;
                statusBarHeight = AndroidUtilities.statusBarHeight;
                statusRtl = LocaleController.isRTL;
                statusFilter = photoFilter;
                statusScrim = scrim;
                light = probeStatusBar(source, width, height, surface);
            }
        }
        boolean statusChanged = light == null ? statusLight != null : !light.equals(statusLight);
        statusLight = light;
        // Compared with what was pushed, so a push missed while detached or undone by a theme change is made up.
        boolean repush = (wanted() != applied || wantedPin() != appliedPin) && !repushPosted;
        if (!statusChanged && !repush) {
            return;
        }
        repushPosted |= repush;
        // Posted out of the draw pass. It re-asks whichever fragment is on top, so it is harmless when this chat is not.
        AndroidUtilities.runOnUIThread(() -> {
            if (repush) {
                repushPosted = false;
                // A theme rebuild may have given the chat a new bar meanwhile; that bar's own drawer pushes.
                if (actionBar.naxHeaderBg == this && fragment.getActionBar() == actionBar) {
                    HeaderBgForeground.push(fragment, this, wanted() != applied, wantedPin() != appliedPin);
                }
            }
            if (statusChanged) {
                LaunchActivity activity = LaunchActivity.instance;
                if (activity != null) {
                    activity.checkSystemBarColors(true, true, false);
                }
                if (onStatusIconsChanged != null) {
                    onStatusIconsChanged.run();
                }
            }
        });
    }

    /** The header's foreground set for now; selection mode holds the last one, since it covers the header. */
    int wanted() {
        return shown && settings.enabled && hasPhoto ? settings.text(false, isDark()) : HeaderBgForeground.THEME;
    }

    /** The pinned bar's, from the live fade: selection mode fades the photo under the panel too, not only the header's. */
    int wantedPin() {
        if (alpha() < 1f || !settings.extendPanel) {
            return HeaderBgForeground.THEME;
        }
        int text = settings.text(true, isDark());
        // Auto over the Classic surface drawPanel paints reads like the header: the theme's pinned colours are for white.
        return text == HeaderBgSettings.TEXT_AUTO && surfaceColor(true) != surfaceColor(false) ? HeaderBgForeground.CLASSIC : text;
    }

    // The app's current light or dark theme, which picks the light or the dark half of the text settings.
    private boolean isDark() {
        return resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
    }

    // Paints the status bar rows small, as the header does, and judges their average the way the chat
    // judges its own header. Null on any failure, which leaves the icons to the chat.
    private Boolean probeStatusBar(Bitmap source, int width, int height, int surface) {
        Bitmap readable = null;
        try {
            int probeWidth = 32;
            float k = probeWidth / (float) width;
            int probeHeight = Math.max(1, Math.round(AndroidUtilities.statusBarHeight * k));
            Bitmap probe = Bitmap.createBitmap(probeWidth, probeHeight, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(probe);
            c.scale(k, k);
            c.drawColor(surface);
            int cover = frame(width, height, frameRect);
            readable = source.getConfig() == Bitmap.Config.HARDWARE ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
            Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG);
            photo.setAlpha(255 * settings.opacity / 100);
            photo.setColorFilter(photoFilter);
            c.drawBitmap(readable, null, frameRect, photo);
            if (settings.gradient && settings.gradientStrength > 0) {
                Paint fade = headerFade.update(width, cover, settings, surface);
                fade.setAlpha(255);
                c.drawPaint(fade);
            }
            Paint scrim = scrim(width, cover);
            if (scrim != null) {
                scrim.setAlpha(255);
                c.drawPaint(scrim);
            }
            int[] pixels = new int[probeWidth * probeHeight];
            probe.getPixels(pixels, 0, probeWidth, 0, 0, probeWidth, probeHeight);
            long r = 0, g = 0, b = 0;
            for (int p : pixels) {
                r += android.graphics.Color.red(p);
                g += android.graphics.Color.green(p);
                b += android.graphics.Color.blue(p);
            }
            int n = pixels.length;
            int average = android.graphics.Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
            return AndroidUtilities.computePerceivedBrightness(average) > 0.721f;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (readable != null && readable != source) {
                readable.recycle();
            }
        }
    }

    private void updateFilter() {
        int hue = settings.tintHue;
        int color = tintColor(hue, resourcesProvider);
        if (hue == filterHue && color == filterColor && settings.tintStrength == filterStrength && settings.desaturate == filterDesaturate) {
            return;
        }
        filterHue = hue;
        filterColor = color;
        filterStrength = settings.tintStrength;
        filterDesaturate = settings.desaturate;
        boolean tint = hue != HeaderBgSettings.TINT_AUTO && filterStrength > 0;
        int tintColor = ColorUtils.setAlphaComponent(color, 255 * filterStrength / 100);
        if (filterDesaturate == 0) {
            photoFilter = tint ? new PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_ATOP) : null;
        } else {
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(1f - filterDesaturate / 100f);
            if (tint) {
                // SRC_ATOP with the tint, written as a matrix so it can follow the desaturation.
                float a = android.graphics.Color.alpha(tintColor) / 255f;
                float k = 1f - a;
                matrix.postConcat(new ColorMatrix(new float[]{
                        k, 0, 0, 0, android.graphics.Color.red(tintColor) * a,
                        0, k, 0, 0, android.graphics.Color.green(tintColor) * a,
                        0, 0, k, 0, android.graphics.Color.blue(tintColor) * a,
                        0, 0, 0, 1, 0}));
            }
            photoFilter = new ColorMatrixColorFilter(matrix);
        }
        imageReceiver.setColorFilter(photoFilter);
    }

    // While blurred, the receiver's thumb-to-big crossfade isn't drawn; the blur moves to the big photo once it lands.
    private Bitmap blurredPhoto() {
        Bitmap source = imageReceiver.getBitmap();
        if (source == null || source.isRecycled()) {
            blurred = null;
            blurSource = null;
            return null;
        }
        if (blurSource != null && blurSource.get() == source && blurSourceW == source.getWidth()
                && blurSourceH == source.getHeight() && blurLevel == settings.blur) {
            // Null here means this exact source failed once; it draws unblurred rather than retrying every frame.
            return blurred;
        }
        blurred = null;
        blurSource = new WeakReference<>(source);
        blurSourceW = source.getWidth();
        blurSourceH = source.getHeight();
        blurLevel = settings.blur;
        try {
            // The source is the image cache's shared bitmap: it is only ever read, into a fresh bitmap
            // that alone is blurred in place. Never upscaled, so a small thumb stays small.
            Bitmap readable = source.getConfig() == Bitmap.Config.HARDWARE ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
            int longest = Math.max(blurSourceW, blurSourceH);
            int target = Math.max(1, Math.min(longest, Math.round(256 / (1 + blurLevel / 50f))));
            int w = Math.max(1, Math.round(blurSourceW * target / (float) longest));
            int h = Math.max(1, Math.round(blurSourceH * target / (float) longest));
            Bitmap work = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(work);
            c.scale(w / (float) blurSourceW, h / (float) blurSourceH);
            c.drawBitmap(readable, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
            if (readable != source) {
                readable.recycle();
            }
            Utilities.stackBlurBitmap(work, 1 + blurLevel / 8);
            blurred = work;
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return blurred;
    }

    /** Resolved on every use, so the Theme and hue tints follow a theme switch. */
    public static int tintColor(int hue, Theme.ResourcesProvider resourcesProvider) {
        if (hue == HeaderBgSettings.TINT_THEME) {
            return Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        }
        return hue >= 0 ? Theme.getColor(Theme.keys_avatar_background[hue], resourcesProvider) : 0;
    }

    // The colour the surface under the photo is painted with, opaque, so the fade lands on it without
    // a seam. Only the header asks for the solid header colour; the panel picks it up while the photo covers it.
    private int surfaceColor(boolean header) {
        int color = InterfaceStyleController.chatHeaderSurfaceColor(resourcesProvider);
        if (header && InterfaceStyleSolidHeader.chatHeader() && !actionBar.isActionModeShowed()) {
            color = InterfaceStyleSolidHeader.chatHeaderSurface(color);
        }
        return ColorUtils.setAlphaComponent(color, 255);
    }

    /** One cached fade shader in the header's coordinates; the header and the panel each keep one. */
    private static final class Fade {
        // Enough stops that no curve shows banding between them across a header's width.
        private static final int STOPS = 16;

        final Paint paint = new Paint();
        private final int[] colors = new int[STOPS];
        private final float[] positions = new float[STOPS];
        // The two stripe shapes run the fade twice, mirrored about the middle.
        private final int[] stripeColors = new int[STOPS * 2];
        private final float[] stripePositions = new float[STOPS * 2];
        private int w, h, from = -1, color, strength, curve, start, end;
        private boolean rtl;

        Paint update(int width, int height, HeaderBgSettings s, int surface) {
            return update(width, height, s.gradientFrom, surface, s.gradientStrength, s.gradientCurve, s.gradientStart, s.gradientEnd);
        }

        Paint update(int width, int height, int fromSide, int opaque, int fadeStrength, int fadeCurve, int fadeStart, int fadeEnd) {
            boolean isRtl = LocaleController.isRTL;
            if (width == w && height == h && fromSide == from && opaque == color && fadeStrength == strength
                    && fadeCurve == curve && fadeStart == start && fadeEnd == end && isRtl == rtl) {
                return paint;
            }
            w = width;
            h = height;
            from = fromSide;
            color = opaque;
            strength = fadeStrength;
            curve = fadeCurve;
            start = fadeStart;
            end = fadeEnd;
            rtl = isRtl;
            // Settings keep the ends apart already; this only keeps the stops strictly increasing.
            float a = start / 100f;
            float b = Math.max(end, start + HeaderBgSettings.MIN_FADE_SPAN) / 100f;
            if (from == HeaderBgSettings.FROM_EDGES || from == HeaderBgSettings.FROM_CENTER) {
                // Each half runs the fade over half the height: from both edges in, or from the middle out.
                boolean edges = from == HeaderBgSettings.FROM_EDGES;
                for (int i = 0; i < STOPS; i++) {
                    int upper = edges ? i : STOPS - 1 - i;
                    float t = upper / (STOPS - 1f);
                    float half = (a + (b - a) * t) / 2f;
                    stripePositions[i] = edges ? half : 0.5f - half;
                    stripeColors[i] = fadeColor(t);
                    int lower = edges ? STOPS - 1 - i : i;
                    float u = lower / (STOPS - 1f);
                    float halfLower = (a + (b - a) * u) / 2f;
                    stripePositions[STOPS + i] = edges ? 1f - halfLower : 0.5f + halfLower;
                    stripeColors[STOPS + i] = fadeColor(u);
                }
                paint.setShader(new LinearGradient(0, 0, 0, height, stripeColors, stripePositions, Shader.TileMode.CLAMP));
                return paint;
            }
            float x0 = 0, y0 = 0, x1 = 0, y1 = 0;
            if (from == HeaderBgSettings.FROM_TOP) {
                y1 = height;
            } else if (from == HeaderBgSettings.FROM_BOTTOM) {
                y0 = height;
            } else if (rtl) {
                x0 = width;
            } else {
                x1 = width;
            }
            for (int i = 0; i < STOPS; i++) {
                float t = i / (STOPS - 1f);
                positions[i] = a + (b - a) * t;
                colors[i] = fadeColor(t);
            }
            // CLAMP holds the solid first stop before the start and the clear last one after the end.
            paint.setShader(new LinearGradient(x0, y0, x1, y1, colors, positions, Shader.TileMode.CLAMP));
            return paint;
        }

        private int fadeColor(float t) {
            return ColorUtils.setAlphaComponent(color, Math.round(255 * strength / 100f * (1f - ease(curve, t))));
        }

        // How far the fade has cleared at t of its run, from 0 to 1.
        private static float ease(int curve, float t) {
            switch (curve) {
                case HeaderBgSettings.CURVE_EASE_IN:
                    return t * t;
                case HeaderBgSettings.CURVE_EASE_OUT:
                    return 1f - (1f - t) * (1f - t);
                case HeaderBgSettings.CURVE_SMOOTH:
                    return t * t * (3f - 2f * t);
                default:
                    return t;
            }
        }
    }
}
