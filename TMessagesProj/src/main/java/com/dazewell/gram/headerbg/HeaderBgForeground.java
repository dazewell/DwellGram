package com.dazewell.gram.headerbg;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.util.SparseIntArray;
import android.view.View;
import android.widget.ImageView;

import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.FragmentContextView;
import org.telegram.ui.Components.NumberTextView;

import com.dazewell.gram.helpers.InterfaceStyleSolidHeader;
import com.dazewell.gram.helpers.PinnedPlayerRow;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * The chat header's and pinned bar's foreground colours over a header photo. The Classic solid header is
 * resolved first; the chat's text colour setting for the current theme then swaps the result for a light or dark neutral set.
 * Readers go through the set the drawer last pushed ({@link HeaderBgDrawer#applied}), never its live wish,
 * so everything read between two pushes matches what is already on screen.
 */
public final class HeaderBgForeground {

    static final int THEME = 0;
    static final int LIGHT = 1;
    static final int DARK = 2;
    // The pinned bar only: auto text over the Classic header surface the drawer paints under it.
    static final int CLASSIC = 3;

    // The CHAT_HEADER key set plus the pinned bar's. Online/typing stays at full strength so it still differs from "last seen".
    private static final SparseIntArray ON_DARK = new SparseIntArray();
    private static final SparseIntArray ON_LIGHT = new SparseIntArray();

    static {
        ON_DARK.put(Theme.key_actionBarDefaultIcon, 0xffffffff);
        ON_DARK.put(Theme.key_actionBarDefaultTitle, 0xffffffff);
        ON_DARK.put(Theme.key_actionBarDefaultSubtitle, 0xccffffff);
        ON_DARK.put(Theme.key_actionBarDefaultSelector, 0x33ffffff);
        ON_DARK.put(Theme.key_actionBarDefaultSearch, 0xffffffff);
        ON_DARK.put(Theme.key_actionBarDefaultSearchPlaceholder, 0x88ffffff);
        ON_DARK.put(Theme.key_chat_status, 0xffffffff);
        ON_DARK.put(Theme.key_chat_lockIcon, 0xffffffff);
        ON_DARK.put(Theme.key_chat_muteIcon, 0xb3ffffff);
        ON_DARK.put(Theme.key_chat_topPanelTitle, 0xffffffff);
        ON_DARK.put(Theme.key_chat_topPanelMessage, 0xccffffff);
        ON_DARK.put(Theme.key_chat_topPanelClose, 0xffffffff);

        ON_LIGHT.put(Theme.key_actionBarDefaultIcon, 0xde000000);
        ON_LIGHT.put(Theme.key_actionBarDefaultTitle, 0xde000000);
        ON_LIGHT.put(Theme.key_actionBarDefaultSubtitle, 0x8a000000);
        ON_LIGHT.put(Theme.key_actionBarDefaultSelector, 0x1a000000);
        ON_LIGHT.put(Theme.key_actionBarDefaultSearch, 0xde000000);
        ON_LIGHT.put(Theme.key_actionBarDefaultSearchPlaceholder, 0x61000000);
        ON_LIGHT.put(Theme.key_chat_status, 0xde000000);
        ON_LIGHT.put(Theme.key_chat_lockIcon, 0xde000000);
        ON_LIGHT.put(Theme.key_chat_muteIcon, 0x61000000);
        ON_LIGHT.put(Theme.key_chat_topPanelTitle, 0xde000000);
        ON_LIGHT.put(Theme.key_chat_topPanelMessage, 0x8a000000);
        ON_LIGHT.put(Theme.key_chat_topPanelClose, 0xde000000);
    }

    // The pinned strip's text and buttons, which the chat colours once when it builds the strip. Weak both ways:
    // the list button's click listener holds the chat, so a strong value would keep the chat as its own key alive.
    private static final WeakHashMap<ChatActivity, PinnedViews> pinned = new WeakHashMap<>();

    private HeaderBgForeground() {
    }

    private static HeaderBgDrawer drawer(ChatActivity fragment) {
        ActionBar actionBar = fragment != null ? fragment.getActionBar() : null;
        return actionBar != null ? actionBar.naxHeaderBg : null;
    }

    private static int map(int mode, int key, int color) {
        SparseIntArray table = mode == LIGHT ? ON_DARK : mode == DARK ? ON_LIGHT : null;
        int index = table != null ? table.indexOfKey(key) : -1;
        return index >= 0 ? table.valueAt(index) : color;
    }

    /** A chat header colour: the Classic header's or the theme's, unless the chat sets its header text. */
    public static int color(ChatActivity fragment, int key, int themedColor) {
        HeaderBgDrawer drawer = drawer(fragment);
        return map(drawer != null ? drawer.applied : THEME, key, InterfaceStyleSolidHeader.chatHeaderColor(key, themedColor));
    }

    /** A pinned bar colour: the theme's, unless the chat sets its pinned bar text. */
    public static int pinColor(ChatActivity fragment, int key, int themedColor) {
        HeaderBgDrawer drawer = drawer(fragment);
        int mode = drawer != null ? drawer.appliedPin : THEME;
        if (mode == CLASSIC) {
            int headerKey = key == Theme.key_chat_topPanelTitle ? Theme.key_actionBarDefaultTitle
                    : key == Theme.key_chat_topPanelMessage ? Theme.key_actionBarDefaultSubtitle
                    : key == Theme.key_chat_topPanelClose ? Theme.key_actionBarDefaultIcon : -1;
            return headerKey < 0 ? themedColor : InterfaceStyleSolidHeader.chatHeaderColor(headerKey, themedColor);
        }
        return map(mode, key, themedColor);
    }

    /**
     * A chat's audio player strip sits in the pinned bar's band, over the same photo, so it follows the pinned bar's text.
     * The play button goes neutral with the close button: the accent has no contrast guarantee on a photo.
     */
    public static int playerColor(Object host, boolean audioStrip, int key, int themedColor) {
        if (!audioStrip || !(host instanceof ChatActivity)) {
            return themedColor;
        }
        int pinKey = key == Theme.key_inappPlayerTitle ? Theme.key_chat_topPanelTitle
                : key == Theme.key_inappPlayerPerformer ? Theme.key_chat_topPanelMessage
                : key == Theme.key_inappPlayerClose || key == Theme.key_inappPlayerPlayPause ? Theme.key_chat_topPanelClose : -1;
        return pinKey < 0 ? themedColor : pinColor((ChatActivity) host, pinKey, themedColor);
    }

    /** For the auto-delete timer, which reads the title key through its own provider at draw time. */
    public static Theme.ResourcesProvider wrapChatHeader(Theme.ResourcesProvider base, ChatActivity fragment) {
        return InterfaceStyleSolidHeader.wrap(base, (key, color) -> color(fragment, key, color));
    }

    /** The shared mute or lock icon as a private copy that picks its colour as it draws. */
    public static Drawable titleIcon(ChatActivity fragment, Drawable icon, int colorKey) {
        if (icon == null || icon.getConstantState() == null) {
            return icon;
        }
        return new TitleIcon(icon.getConstantState().newDrawable().mutate(), fragment, colorKey);
    }

    /** From the end of the chat's pinned strip build, which every new strip goes through. */
    public static void attachPinned(ChatActivity fragment, NumberTextView counter, SimpleTextView[] names, SimpleTextView[] messages,
                                    ImageView listButton, ImageView closeButton, View playerRow) {
        PinnedViews views = new PinnedViews();
        views.counter = new WeakReference<>(counter);
        views.names = new WeakReference<>(names);
        views.messages = new WeakReference<>(messages);
        views.listButton = new WeakReference<>(listButton);
        views.closeButton = new WeakReference<>(closeButton);
        views.playerRow = new WeakReference<>(playerRow);
        pinned.put(fragment, views);
        if (drawer(fragment) != null) {
            applyPinned(fragment);
        }
    }

    /** Pushes the header's and the pinned bar's colours, from the theme delegate once the key-only descriptions have reset them. */
    public static void applyChatHeader(ChatActivity fragment) {
        ActionBar actionBar = fragment.getActionBar();
        HeaderBgDrawer drawer = actionBar != null ? actionBar.naxHeaderBg : null;
        if (drawer == null) {
            InterfaceStyleSolidHeader.applyChatHeader(actionBar, fragment.getAvatarContainer());
            return;
        }
        push(fragment, drawer, true, true);
    }

    /**
     * From the drawer when what it wants no longer matches the last push. Only the part that changed is pushed:
     * the pinned bar follows the selection fade, and a header push would replace selection's back button ripple.
     */
    static void push(ChatActivity fragment, HeaderBgDrawer drawer, boolean header, boolean pin) {
        if (pin) {
            drawer.appliedPin = drawer.wantedPin();
            applyPinned(fragment);
            // Not inside applyPinned: that returns early in a chat with no pinned message, and the player can still show.
            FragmentContextView player = fragment.getFragmentContextView();
            if (player != null) {
                player.updateColors();
            }
        }
        if (!header) {
            return;
        }
        drawer.applied = drawer.wanted();
        ActionBar actionBar = fragment.getActionBar();
        ChatAvatarContainer avatarContainer = fragment.getAvatarContainer();
        Theme.ResourcesProvider rp = fragment.getResourceProvider();
        actionBar.setItemsColor(color(fragment, Theme.key_actionBarDefaultIcon, Theme.getColor(Theme.key_actionBarDefaultIcon, rp)), false);
        actionBar.setItemsBackgroundColor(color(fragment, Theme.key_actionBarDefaultSelector, Theme.getColor(Theme.key_actionBarDefaultSelector, rp)), false);
        int title = color(fragment, Theme.key_actionBarDefaultTitle, Theme.getColor(Theme.key_actionBarDefaultTitle, rp));
        actionBar.setTitleColor(title);
        int search = color(fragment, Theme.key_actionBarDefaultSearch, Theme.getColor(Theme.key_actionBarDefaultSearch, rp));
        actionBar.setSearchTextColor(search, false);
        actionBar.setSearchTextColor(color(fragment, Theme.key_actionBarDefaultSearchPlaceholder, Theme.getColor(Theme.key_actionBarDefaultSearchPlaceholder, rp)), true);
        actionBar.setSearchCursorColor(search);
        if (avatarContainer == null) {
            return;
        }
        avatarContainer.getTitleTextView().setTextColor(title);
        // The subtitle is tagged with whichever of the two keys it is showing; anything else reads as the plain subtitle.
        View subtitle = avatarContainer.getSubtitleTextView();
        Object tag = subtitle != null ? subtitle.getTag() : null;
        if (tag instanceof Integer) {
            int key = (Integer) tag == Theme.key_chat_status ? Theme.key_chat_status : Theme.key_actionBarDefaultSubtitle;
            int color = color(fragment, key, Theme.getColor(key, rp));
            if (subtitle instanceof SimpleTextView) {
                ((SimpleTextView) subtitle).setTextColor(color);
            } else if (subtitle instanceof AnimatedTextView) {
                ((AnimatedTextView) subtitle).setTextColor(color);
            }
        }
        avatarContainer.updateColors();
        avatarContainer.updateTimeZonePill();
        // Both draw their colour lazily: the title icons and the timer.
        avatarContainer.getTitleTextView().invalidate();
        if (avatarContainer.getTimeItem() != null) {
            avatarContainer.getTimeItem().invalidate();
        }
    }

    private static void applyPinned(ChatActivity fragment) {
        PinnedViews views = pinned.get(fragment);
        if (views == null) {
            return;
        }
        Theme.ResourcesProvider rp = fragment.getResourceProvider();
        int title = pinColor(fragment, Theme.key_chat_topPanelTitle, Theme.getColor(Theme.key_chat_topPanelTitle, rp));
        int message = pinColor(fragment, Theme.key_chat_topPanelMessage, Theme.getColor(Theme.key_chat_topPanelMessage, rp));
        int close = pinColor(fragment, Theme.key_chat_topPanelClose, Theme.getColor(Theme.key_chat_topPanelClose, rp));
        NumberTextView counter = views.counter.get();
        if (counter != null) {
            counter.setTextColor(title);
        }
        // The chat swaps each pair by reference as the pinned message changes, so both are coloured.
        setTextColor(views.names.get(), title);
        setTextColor(views.messages.get(), message);
        setIconColor(views.listButton.get(), close);
        setIconColor(views.closeButton.get(), close);
        // The combined row's compact copy picks its colour up only when it draws.
        PinnedPlayerRow.invalidateCompact(views.playerRow.get());
    }

    private static void setTextColor(SimpleTextView[] views, int color) {
        if (views == null) {
            return;
        }
        for (SimpleTextView view : views) {
            if (view != null) {
                view.setTextColor(color);
            }
        }
    }

    private static void setIconColor(ImageView view, int color) {
        if (view != null) {
            view.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
        }
    }

    private static final class PinnedViews {
        WeakReference<NumberTextView> counter;
        WeakReference<SimpleTextView[]> names;
        WeakReference<SimpleTextView[]> messages;
        WeakReference<ImageView> listButton;
        WeakReference<ImageView> closeButton;
        WeakReference<View> playerRow;
    }

    // Tint calls from outside (theme descriptions recolour the title's side drawables) are ignored.
    private static final class TitleIcon extends DrawableWrapper {
        private final ChatActivity fragment;
        private final int colorKey;
        private int color;
        private boolean tinted;

        TitleIcon(Drawable copy, ChatActivity fragment, int colorKey) {
            super(copy);
            this.fragment = fragment;
            this.colorKey = colorKey;
        }

        @Override
        public void draw(Canvas canvas) {
            int c = color(fragment, colorKey, Theme.getColor(colorKey, fragment.getResourceProvider()));
            if (!tinted || c != color) {
                tinted = true;
                color = c;
                getDrawable().setColorFilter(new PorterDuffColorFilter(c, PorterDuff.Mode.MULTIPLY));
            }
            super.draw(canvas);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public void setTintList(ColorStateList tint) {
        }

        @Override
        public void setTintMode(PorterDuff.Mode tintMode) {
        }
    }
}
