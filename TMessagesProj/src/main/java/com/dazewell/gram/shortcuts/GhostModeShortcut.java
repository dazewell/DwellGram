package com.dazewell.gram.shortcuts;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.LaunchActivity;

import java.util.Collections;
import java.util.List;

import tw.nekomimi.nekogram.NekoConfig;

import xyz.nextalone.nagram.NaConfig;

/**
 * Launcher shortcut that opens the app with Ghost Mode turned on, labeled "Ayu Mode" so it doesn't advertise itself.
 * LaunchActivity.handleIntent does the switching.
 */
public final class GhostModeShortcut {

    public static final String ACTION = "enable_ayu_mode";
    private static final String SHORTCUT_ID = "ayu_mode";

    private GhostModeShortcut() {
    }

    public static String getLabel() {
        return com.dazewell.gram.helpers.ShortcutHelper.variantLabel(LocaleController.getString(R.string.AyuModeShortcut));
    }

    public static boolean isEnabled() {
        return NaConfig.INSTANCE.getGhostModeShortcut().Bool();
    }

    /** Runs before account switching or passcode deferral can resume online-status work. */
    public static void accept(Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        // Consume even a disabled pinned copy; unlock and activity recreation must not replay it.
        intent.setAction(Intent.ACTION_MAIN);
        if (!isEnabled()) {
            return;
        }
        // Preserve deliberate signal locks, including an online-on lock. This is not a toggle.
        NekoConfig.setGhostMode(true);
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                TL_account.updateStatus request = new TL_account.updateStatus();
                request.offline = true;
                ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {});
            }
        }
    }

    /** MediaDataController.buildShortcuts: the ids it keeps when pruning stale shortcuts. */
    public static void addShortcutId(List<String> wantedIds) {
        if (isEnabled()) {
            wantedIds.add(SHORTCUT_ID);
        }
    }

    /** MediaDataController.buildShortcuts, on its queue. Own try so a launcher rejecting it can't abort the rest. */
    public static void publish(boolean recreate, List<String> existingIds, int rank) {
        if (!isEnabled()) {
            return;
        }
        try {
            Intent intent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
            intent.setAction(ACTION);
            ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(ApplicationLoader.applicationContext, SHORTCUT_ID)
                    .setShortLabel(getLabel())
                    .setLongLabel(getLabel())
                    .setIcon(IconCompat.createWithBitmap(createIcon()))
                    .setRank(rank)
                    .setIntent(intent)
                    .build();
            if (recreate) {
                ShortcutManagerCompat.pushDynamicShortcut(ApplicationLoader.applicationContext, shortcut);
            } else if (existingIds.contains(SHORTCUT_ID)) {
                ShortcutManagerCompat.updateShortcuts(ApplicationLoader.applicationContext, Collections.singletonList(shortcut));
            } else {
                ShortcutManagerCompat.addDynamicShortcuts(ApplicationLoader.applicationContext, Collections.singletonList(shortcut));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // Rasterized: some OEM launchers (e.g. ColorOS) reject a vector resource passed straight to setIcon.
    public static Bitmap createIcon() {
        int size = AndroidUtilities.dp(48);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Drawable drawable = ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.shortcut_ayu, null);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }
}
