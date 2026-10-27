package com.dazewell.gram.pinnedsettings;

import static org.telegram.messenger.LocaleController.getString;

import android.net.Uri;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import tw.nekomimi.nekogram.helpers.SettingsHelper;
import xyz.nextalone.nagram.NaConfig;

/**
 * N-Settings rows pinned to the main Settings screen. An entry is the same (screen, row) pair the
 * nasettings link carries, so opening one goes through {@link SettingsHelper#processDeepLink} and
 * lands on the row exactly as a copied link would. Everything here runs on the main thread.
 */
public final class PinnedSettings {

    public static final int MAX = 8;

    // Above every hard-coded id SettingsActivity.onClick switches on, so a pinned row never hits one of its cases
    private static final int ID_BASE = 0x7A000000;
    private static final int ID_HEADER = ID_BASE - 1;
    private static final Object HEADER = new Object();

    // Bumped on every change, so a Settings screen that is already built knows to refill when it resumes
    private static int version;
    private static final Map<UniversalAdapter, Integer> filledVersion = new WeakHashMap<>();

    private PinnedSettings() {
    }

    private static final class Entry {
        final String prefix;
        final String key;

        Entry(String prefix, String key) {
            this.prefix = prefix;
            this.key = key;
        }

        String token() {
            return prefix + ":" + key;
        }

        // UItem diffing compares item.object, and every fill builds new entries, so without this each update re-inserts every row
        @Override
        public boolean equals(Object o) {
            return o instanceof Entry other && token().equals(other.token());
        }

        @Override
        public int hashCode() {
            return token().hashCode();
        }
    }

    // Malformed tokens and anything past the cap are dropped here rather than rewritten in storage
    private static List<Entry> read() {
        ArrayList<Entry> list = new ArrayList<>();
        String raw = NaConfig.INSTANCE.getPinnedSettings().String();
        if (TextUtils.isEmpty(raw)) {
            return list;
        }
        ArrayList<String> seen = new ArrayList<>();
        for (String token : raw.split(",")) {
            int colon = token.indexOf(':');
            if (colon <= 0 || colon == token.length() - 1) {
                continue;
            }
            Entry entry = new Entry(token.substring(0, colon), token.substring(colon + 1));
            if (!isValidPart(entry.prefix) || !isValidPart(entry.key) || seen.contains(entry.token())) {
                continue;
            }
            seen.add(entry.token());
            list.add(entry);
            if (list.size() >= MAX) {
                break;
            }
        }
        return list;
    }

    private static void write(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (Entry entry : list) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.token());
        }
        NaConfig.INSTANCE.getPinnedSettings().setConfigString(sb.toString());
        version++;
    }

    private static boolean isValidPart(String s) {
        return !TextUtils.isEmpty(s) && s.indexOf(',') < 0 && s.indexOf(':') < 0;
    }

    private static int indexOf(List<Entry> list, String prefix, String key) {
        for (int i = 0; i < list.size(); i++) {
            Entry entry = list.get(i);
            if (entry.prefix.equals(prefix) && entry.key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    // Same test the settings search uses: a row whose key has no string of its own cannot be named, so it is not pinnable
    private static String title(String key) {
        if (TextUtils.isEmpty(key) || TextUtils.isDigitsOnly(key)) {
            return null;
        }
        String title = LocaleController.nullable(getString(key));
        return TextUtils.isEmpty(title) ? null : title;
    }

    private static String screenTitle(String prefix) {
        switch (prefix) {
            case "general":
                return getString(R.string.General);
            case "chat":
                return getString(R.string.Chat);
            case "experimental":
                return getString(R.string.Experimental);
            case "translator":
                return getString(R.string.TranslatorSettings);
            case com.dazewell.gram.ui.InterfaceStyleActivity.SETTINGS_KEY:
                return getString(R.string.InterfaceStyle);
            case com.dazewell.gram.shortcuts.LauncherShortcutsActivity.SETTINGS_KEY:
                return getString(R.string.LauncherShortcuts);
            default:
                return null;
        }
    }

    private static int screenIcon(String prefix) {
        switch (prefix) {
            case "general":
                return R.drawable.msg_theme;
            case "chat":
                return R.drawable.msg_discussion;
            case "experimental":
                return R.drawable.msg_fave;
            case "translator":
                return R.drawable.ic_translate;
            default:
                return R.drawable.msg_settings;
        }
    }

    /** Long-press menu item on an N-Settings row: pin it, or unpin it if it already is. */
    public static void addOptions(BaseFragment fragment, ItemOptions options, String prefix, String key) {
        if (!isValidPart(prefix) || !isValidPart(key) || title(key) == null) {
            return;
        }
        if (indexOf(read(), prefix, key) >= 0) {
            options.add(R.drawable.msg_unpin, getString(R.string.PinnedSettingsUnpin), () -> unpin(fragment, prefix, key, null));
        } else {
            options.add(R.drawable.msg_pin, getString(R.string.PinnedSettingsPin), () -> pin(fragment, prefix, key));
        }
    }

    private static void pin(BaseFragment fragment, String prefix, String key) {
        List<Entry> list = read();
        if (indexOf(list, prefix, key) >= 0) {
            return;
        }
        if (list.size() >= MAX) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, getString(R.string.PinnedSettingsFull)).show();
            return;
        }
        if (list.isEmpty()) {
            // A first pin should be seen, so a group someone collapsed back when it was last emptied opens again
            NaConfig.INSTANCE.getPinnedSettingsCollapsed().setConfigBool(false);
        }
        list.add(new Entry(prefix, key));
        write(list);
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_pin, getString(R.string.PinnedSettingsPinned)).show();
    }

    private static void unpin(BaseFragment fragment, String prefix, String key, UniversalAdapter adapter) {
        List<Entry> list = read();
        int index = indexOf(list, prefix, key);
        if (index < 0) {
            return;
        }
        list.remove(index);
        write(list);
        refresh(adapter);
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_unpin, getString(R.string.PinnedSettingsUnpinned), getString(R.string.Undo), () -> {
            List<Entry> current = read();
            if (indexOf(current, prefix, key) >= 0 || current.size() >= MAX) {
                return;
            }
            current.add(Math.min(index, current.size()), new Entry(prefix, key));
            write(current);
            refresh(adapter);
        }).show();
    }

    private static void refresh(UniversalAdapter adapter) {
        if (adapter != null) {
            adapter.update(true);
        }
    }

    /** Appends the pinned group right under the N-Settings row, inside its card. Adds nothing when nothing is pinned. */
    public static void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (adapter != null) {
            filledVersion.put(adapter, version);
        }
        ArrayList<UItem> rows = new ArrayList<>();
        for (Entry entry : read()) {
            String title = title(entry.key);
            String screen = screenTitle(entry.prefix);
            if (title == null || screen == null) {
                continue;
            }
            UItem item = SettingsActivity.SettingCell.Factory.of(ID_BASE | (entry.token().hashCode() & 0xFFFFFF),
                    IconBackgroundColors.GRAY.top, IconBackgroundColors.GRAY.bottom, screenIcon(entry.prefix), title, null, screen);
            item.object = entry;
            rows.add(item);
        }
        if (rows.isEmpty()) {
            return;
        }
        boolean collapsed = NaConfig.INSTANCE.getPinnedSettingsCollapsed().Bool();
        UItem header = SettingsActivity.SettingCell.Factory.of(ID_HEADER,
                IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom, R.drawable.msg_pin,
                getString(R.string.PinnedSettings), null, rows.size() + (collapsed ? " ▾" : " ▴"));
        header.object = HEADER;
        items.add(header);
        if (!collapsed) {
            items.addAll(rows);
        }
    }

    /** A Settings screen built before a pin changed in N-Settings refills on its way back. */
    public static void onResume(UniversalAdapter adapter) {
        if (adapter == null) {
            return;
        }
        Integer filled = filledVersion.get(adapter);
        if (filled == null || filled != version) {
            adapter.update(false);
        }
    }

    /** @return true when the item was a pinned row and has been handled. */
    public static boolean onClick(BaseFragment fragment, UniversalRecyclerView listView, UItem item) {
        if (item == null) {
            return false;
        }
        if (item.object == HEADER) {
            boolean collapsed = NaConfig.INSTANCE.getPinnedSettingsCollapsed().Bool();
            NaConfig.INSTANCE.getPinnedSettingsCollapsed().setConfigBool(!collapsed);
            version++;
            refresh(listView != null ? listView.adapter : null);
            return true;
        }
        if (item.object instanceof Entry entry) {
            if (fragment.getParentActivity() == null) {
                return true;
            }
            // A row that is gone or hidden on its screen still opens the screen, just without the highlight
            SettingsHelper.processDeepLink(fragment.getParentActivity(),
                    Uri.parse("https://t.me/nasettings/" + entry.prefix + "?r=" + Uri.encode(entry.key)),
                    fragment::presentFragment, () -> {});
            return true;
        }
        return false;
    }

    /** @return true when the item was a pinned row and has been handled. */
    public static boolean onLongClick(BaseFragment fragment, UniversalRecyclerView listView, UItem item, View view) {
        if (item == null || !(item.object instanceof Entry entry)) {
            return false;
        }
        UniversalAdapter adapter = listView != null ? listView.adapter : null;
        ItemOptions.makeOptions(fragment, view)
                .add(R.drawable.msg_unpin, getString(R.string.PinnedSettingsUnpin), () -> unpin(fragment, entry.prefix, entry.key, adapter))
                .show();
        return true;
    }
}
