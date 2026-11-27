package com.dazewell.gram.webmessages;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

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
 * no session is made, the engine is never prewarmed, and a widget message keeps its placeholder without its
 * payload being unpacked.
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
     * start. Background thread.
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

    /** The user flipped the switch. UI thread. */
    public static void onToggled() {
        // Turning it off unloads every running widget now, and no new session can start.
        if (!enabled()) org.telegram.utils.twidget.WidgetMedia.onKillSwitch();
        org.telegram.messenger.Utilities.globalQueue.postRunnable(WebMessagesGate::syncEngineComponent);
    }
}
