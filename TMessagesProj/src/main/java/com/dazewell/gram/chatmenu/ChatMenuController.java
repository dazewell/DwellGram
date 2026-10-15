package com.dazewell.gram.chatmenu;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import com.dazewell.gram.notifprofiles.NotificationProfileMenu;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * One chat screen's configurable buttons. Each button's availability stays written once, at the
 * site in ChatActivity that registers its ⋮ row; that site calls {@link #add} instead, and this
 * decides whether the button shows as this chat's header icon, as the row, or not at all.
 *
 * Header placement is a preference: when the chat has no header icons (centred title, suggestions)
 * a header button is registered as its ⋮ row instead. Call is the exception that is not owned
 * here: upstream's own icon and its ⋮ fallback row already behave this way, so ChatActivity only
 * asks {@link #inHeader} / {@link #hidden} before registering them.
 *
 * The layout is read once, so a chat keeps the buttons it opened with until it is reopened.
 */
public final class ChatMenuController {

    private final BaseFragment fragment;
    private final int account;
    private final long dialogId;
    private final int bellId;
    private final HashMap<String, Integer> ids = new HashMap<>();
    private final List<List<String>> layout = ChatMenuLayout.snapshot();
    private final HashMap<String, ActionBarMenuItem> icons = new HashMap<>();
    private final HashSet<String> available = new HashSet<>();
    private boolean titleHidden;

    /** {@code ids} are the ⋮ row ids, in {@link ChatMenuLayout#KEYS} order; the header icons reuse them, so onItemClick serves both. */
    public ChatMenuController(BaseFragment fragment, int account, long dialogId, int bellId, int... ids) {
        this.fragment = fragment;
        this.account = account;
        this.dialogId = dialogId;
        this.bellId = bellId;
        for (int i = 0; i < ChatMenuLayout.KEYS.length && i < ids.length; i++) {
            this.ids.put(ChatMenuLayout.KEYS[i], ids[i]);
        }
    }

    public boolean inHeader(String key) {
        return layout.get(ChatMenuLayout.HEADER).contains(key);
    }

    public boolean hidden(String key) {
        return layout.get(ChatMenuLayout.HIDDEN).contains(key);
    }

    /**
     * Registers this chat's header icons in layout order. Called on each side of upstream's Call
     * icon, since registration order is the order on screen: {@code beforeCall} takes the ones
     * ahead of Call (all of them when Call isn't in the header), the second call the rest.
     * Icons start hidden; {@link #updateHeader} shows the ones the chat has.
     */
    public void addHeaderIcons(ActionBarMenu menu, boolean beforeCall) {
        List<String> header = layout.get(ChatMenuLayout.HEADER);
        int callAt = header.indexOf(ChatMenuLayout.CALL);
        for (int i = 0; i < header.size(); i++) {
            boolean ours = callAt < 0 ? beforeCall : (beforeCall ? i < callAt : i > callAt);
            if (!ours) continue;
            String key = header.get(i);
            ActionBarMenuItem icon;
            if (ChatMenuLayout.ALERTS.equals(key)) {
                icon = NotificationProfileMenu.addBell(fragment, menu, bellId, account, dialogId);
            } else {
                icon = menu.addItem(ids.get(key), ChatMenuLayout.headerIcon(key), fragment.getResourceProvider());
                icon.setContentDescription(LocaleController.getString(ChatMenuLayout.titleRes(key)));
                icon.setVisibility(View.GONE);
            }
            icons.put(key, icon);
        }
    }

    public ActionBarMenuItem.Item add(ActionBarMenuItem headerItem, String key, int icon, CharSequence text) {
        if (!claim(key)) return null;
        return headerItem.lazilyAddSubItem(ids.get(key), icon, text);
    }

    public ActionBarMenuItem.Item add(ActionBarMenuItem headerItem, String key, Drawable icon, CharSequence text) {
        if (!claim(key)) return null;
        return headerItem.lazilyAddSubItem(ids.get(key), icon, text);
    }

    /** The ⋮ Alerts row. Registered even while the bell is the header icon: it stands in whenever the bell is hidden, as while search is open. */
    public ActionBarMenuItem.Item addAlerts(ActionBarMenuItem headerItem) {
        if (hidden(ChatMenuLayout.ALERTS)) return null;
        available.add(ChatMenuLayout.ALERTS);
        return NotificationProfileMenu.add(headerItem, ids.get(ChatMenuLayout.ALERTS), account, dialogId);
    }

    public boolean alertsRowWanted() {
        ActionBarMenuItem bell = icons.get(ChatMenuLayout.ALERTS);
        return bell == null || bell.getVisibility() != View.VISIBLE;
    }

    public ActionBarMenuItem bell() {
        return icons.get(ChatMenuLayout.ALERTS);
    }

    /** The bell shows the Muted state itself, so the title's small mute icon stands down while it's on screen. */
    public boolean bellShown() {
        ActionBarMenuItem bell = icons.get(ChatMenuLayout.ALERTS);
        return bell != null && bell.getVisibility() == View.VISIBLE;
    }

    /** True when the caller should register the ⋮ row: the button is available here and not shown as a header icon. */
    private boolean claim(String key) {
        if (hidden(key)) return false;
        available.add(key);
        return !icons.containsKey(key);
    }

    /** Hide title works once per chat; its header icon goes away like its row does. */
    public void consumeHideTitle() {
        titleHidden = true;
        ActionBarMenuItem icon = icons.get(ChatMenuLayout.HIDETITLE);
        if (icon != null) icon.setVisibility(View.GONE);
    }

    /**
     * Sorts the configurable ⋮ rows by the user's order among the slots they already occupy, so
     * Telegram's own items and every row's conditions stay where ChatActivity put them. Runs once,
     * before the menu's first layout. Rows that fell back from the header keep their own slot.
     */
    public void finish(ActionBarMenuItem headerItem) {
        ArrayList<ActionBarMenuItem.Item> list = headerItem == null ? null : headerItem.getLazyItems();
        if (list == null) return;
        List<String> menu = layout.get(ChatMenuLayout.MENU);
        HashMap<Integer, Integer> rank = new HashMap<>();
        for (Map.Entry<String, Integer> entry : ids.entrySet()) {
            int index = menu.indexOf(entry.getKey());
            if (index >= 0) rank.put(entry.getValue(), index);
        }
        ArrayList<Integer> slots = new ArrayList<>();
        ArrayList<ActionBarMenuItem.Item> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ActionBarMenuItem.Item item = list.get(i);
            if (item.viewType == ActionBarMenuItem.VIEW_TYPE_SUBITEM && rank.containsKey(item.id)) {
                slots.add(i);
                items.add(item);
            }
        }
        items.sort(Comparator.comparingInt(item -> rank.get(item.id)));
        for (int i = 0; i < slots.size(); i++) {
            list.set(slots.get(i), items.get(i));
        }
    }

    /**
     * Header icons follow the same states that hide upstream's Call icon: search open and preview.
     * Returns whether the bell appeared or went, since the title's mute icon depends on it.
     */
    public boolean updateHeader(boolean search, boolean preview, boolean topic, boolean inChat) {
        boolean bellWasShown = bellShown();
        for (Map.Entry<String, ActionBarMenuItem> entry : icons.entrySet()) {
            String key = entry.getKey();
            ActionBarMenuItem icon = entry.getValue();
            boolean shown = available.contains(key) && !search && !preview;
            if (ChatMenuLayout.ALERTS.equals(key)) {
                NotificationProfileMenu.updateBell(icon, account, dialogId, topic, shown && inChat);
                continue;
            }
            if (ChatMenuLayout.VIDEO.equals(key)) {
                TLRPC.UserFull full = dialogId > 0 ? MessagesController.getInstance(account).getUserFull(dialogId) : null;
                shown &= full != null && full.phone_calls_available && full.video_calls_available;
            } else if (ChatMenuLayout.HIDETITLE.equals(key)) {
                shown &= !titleHidden;
            }
            int visibility = shown ? View.VISIBLE : View.GONE;
            if (icon.getVisibility() != visibility) icon.setVisibility(visibility);
        }
        return bellShown() != bellWasShown;
    }

    /**
     * Reserves exactly the width of the action-bar icons on screen beside the title. Upstream's
     * fixed 52/92dp assumes one 48dp icon at most, while Glass overlaps icons to a 38dp pitch and
     * shifts the menu, so it over-reserves there and under-reserves for a second icon.
     */
    public static void applyTitleMargin(View avatarContainer, ActionBarMenu menu, boolean skip) {
        if (skip || avatarContainer == null || menu == null || menu.getVisibility() != View.VISIBLE
                || !(avatarContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        int width = 0;
        for (int i = 0, count = menu.getChildCount(); i < count; i++) {
            View child = menu.getChildAt(i);
            if (!(child instanceof ActionBarMenuItem) || child.getVisibility() != View.VISIBLE) continue;
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            width += lp.width > 0 ? lp.width : child.getMeasuredWidth();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                width += ((ViewGroup.MarginLayoutParams) lp).leftMargin + ((ViewGroup.MarginLayoutParams) lp).rightMargin;
            }
        }
        ((ViewGroup.MarginLayoutParams) avatarContainer.getLayoutParams()).rightMargin = Math.max(0, width - (int) menu.getTranslationX() + dp(4));
    }
}
