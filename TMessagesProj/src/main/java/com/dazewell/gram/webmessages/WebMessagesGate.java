package com.dazewell.gram.webmessages;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import xyz.nextalone.nagram.NaConfig;

/**
 * The local switch for upstream's HTML widgets in messages ("web messages"): code a bot sends, run on this device.
 * Off unless the user turns it on, and refused in two places that do not depend on each other:
 *
 * <ul>
 * <li>The OS. {@code WidgetEngineService}, the isolated process every widget script runs in, ships disabled in the
 * manifest. Android will not bind or start a disabled component, so with the switch off no widget code can run
 * even if a Java path forgets to check.
 * <li>Upstream's own kill switch. {@code WidgetSandboxSession.disabledByConfig()} reads true while this is off, so
 * no session is made, the engine is never prewarmed, and a widget message shows {@link #drawOffPlaceholder}
 * without its payload being unpacked. A widget or wallet request already on its way when the switch goes off is
 * dropped where it would be sent.
 * </ul>
 *
 * App-wide, not per account: it decides whether this install runs widget code at all.
 */
public final class WebMessagesGate {

    private WebMessagesGate() {
    }

    /** Any thread. An absent or unreadable setting reads as off. */
    public static boolean enabled() {
        try {
            return NaConfig.INSTANCE.getWebMessages().Bool();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Brings the engine service's enabled state in line with the switch. The package manager keeps that state on
     * its own, so it can disagree with the setting after a settings reset or a restored backup: checked at every
     * start (background thread) and on a toggle (UI thread). Any thread.
     */
    public static void syncEngineComponent() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return;
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName engine = new ComponentName(context, org.telegram.utils.twidget.WidgetEngineService.class);
            int wanted = enabled() ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            if (pm.getComponentEnabledSetting(engine) != wanted) {
                pm.setComponentEnabledSetting(engine, wanted, PackageManager.DONT_KILL_APP);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** The error a widget request gets instead of going out while the switch is off. */
    public static TLRPC.TL_error refused() {
        TLRPC.TL_error error = new TLRPC.TL_error();
        error.code = 403;
        error.text = "WEB_MESSAGES_DISABLED";
        return error;
    }

    private static TextPaint placeholderPaint;

    /** What a widget message shows while the switch is off: a static note, nothing of the widget's own. UI thread. */
    public static void drawOffPlaceholder(Canvas canvas, int width, int height, boolean out, Theme.ResourcesProvider resourcesProvider) {
        int textWidth = width - AndroidUtilities.dp(24);
        if (textWidth <= 0 || height <= 0) return;
        if (placeholderPaint == null) {
            placeholderPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            placeholderPaint.setTextSize(AndroidUtilities.dp(14));
        }
        placeholderPaint.setColor(Theme.getColor(out ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText, resourcesProvider));
        String text = LocaleController.getString(R.string.WebMessagesOff) + '\n' + LocaleController.getString(R.string.WebMessagesOffHint);
        StaticLayout layout = new StaticLayout(text, placeholderPaint, textWidth, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
        canvas.save();
        canvas.translate((width - textWidth) / 2f, Math.max(0, (height - layout.getHeight()) / 2f));
        layout.draw(canvas);
        canvas.restore();
    }

    /** The user flipped the switch. UI thread. */
    public static void onToggled() {
        // Turning it off unloads every running widget now, and no new session can start.
        if (!enabled()) org.telegram.utils.twidget.WidgetMedia.onKillSwitch();
        // Here rather than queued: the setting already reads on, so a widget shown before a queued enable ran would
        // find the engine disabled and stay blank. One package-manager call, as the app icon picker makes on a tap.
        syncEngineComponent();
    }
}
