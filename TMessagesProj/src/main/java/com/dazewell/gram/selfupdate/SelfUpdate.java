package com.dazewell.gram.selfupdate;

import org.telegram.messenger.BuildConfig;
import org.telegram.tgnet.TLRPC;

import tw.nekomimi.nekogram.helpers.remote.UpdateHelper;
import xyz.nextalone.nagram.NaConfig;

/**
 * The fork-owned update source: a Telegram channel the staging pipeline posts each staging
 * build's APKs and metadata to. Each package reads only its own tag, so Official and Unofficial
 * never offer each other's APK.
 *
 * The channel is baked in at build time from the UPDATE_CHANNEL_* repo variables. A build without
 * them (local, ci.yml, a fork) has no source, and the updater stays unavailable as before.
 */
public final class SelfUpdate {

    private SelfUpdate() {
    }

    public static boolean isAvailable() {
        return BuildConfig.UPDATE_CHANNEL_ID > 0;
    }

    /** Available and switched on in Update Channel. */
    public static boolean isEnabled() {
        return isAvailable() && NaConfig.INSTANCE.getAutoUpdateChannel().Int() != UpdateHelper.UPDATE_OFF;
    }

    public static long channelId() {
        return BuildConfig.UPDATE_CHANNEL_ID;
    }

    public static String channelUsername() {
        return BuildConfig.UPDATE_CHANNEL_USERNAME;
    }

    /**
     * Whether a persisted pending update is this build or older. Upstream compares version
     * names, which here end in a commit hash, so that comparison is effectively random between
     * two staging builds. UpdateHelper stores the build timestamp in the update's id instead.
     */
    public static boolean isStale(TLRPC.TL_help_appUpdate update) {
        return update.id <= BuildConfig.BUILD_TIMESTAMP;
    }

    /** The hashtag this package's metadata posts carry; upload.py writes the same two. */
    public static String tag() {
        return BuildConfig.UPDATE_TAG;
    }
}
