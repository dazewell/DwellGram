# Self-update (`#self-update`)

The app's inherited updater (NekoX's `UpdateHelper`) reads a Telegram channel.
It now reads a channel you own, and only staging builds are posted to it.

## How it works

1. **Build.** `staging.yml` passes the `UPDATE_CHANNEL_ID` and
   `UPDATE_CHANNEL_USERNAME` repo variables to Gradle, which bakes them into
   `BuildConfig` together with a per-package tag: `updateOfficial` for
   `org.telegram.messenger.beta`, `updateUnofficial` for `nekox.messenger`. Both
   legs now share one build timestamp from the `resolve` job.
2. **Publish.** `Tools/scripts/upload.py` sends the APK album to
   `HELPER_BOT_TARGET` as before. For a **staging** build of `dev` only (a push, or a dispatch on `dev`;
   never a `build-apk` test build or a dispatch on another branch) it then:
   - copies the album, caption included, into the update channel, unless that is the same chat;
   - posts one message per package:
     `#updateOfficial {"version":"13.1.1-abc1234","version_code":1266,"build_timestamp":…,"message":<caption msg id>,"document":{"arm64-v8a":<APK msg id>}}`.
   A failure here is a warning in the run log; the APK delivery itself never fails on it.
3. **Check.** On resume (at most once per server `updateCheckDelay`, a day by default) or on
   *Check for Updates*, the app searches the channel for its own tag and offers
   the newest post whose `version_code` is higher, or equal with a newer
   `build_timestamp`. The APK and changelog come from the posts it points at. A
   saved pending update is dropped on launch once the installed build's own
   timestamp has caught up with it (the timestamp rides in the update's `id`).

Update Channel in Settings is **Off** or **Staging**, Off by default. A build
with no channel baked in (local, `ci.yml`, a fork) shows it greyed out as
Unavailable, as before. Emoji packs and link-preview rules still read nothing.

## What you need to do

1. **Create a channel.** Public is strongly recommended: the app resolves it by
   username, so any of your accounts can check without having opened it. A
   private channel works only for accounts that have joined and opened it.
   Making a public channel private later frees its username: run the script
   with only `-ChannelId` to clear the variable. Builds that still carry the
   old name reject it once someone else takes it, since the resolved channel
   must match the built-in id.
   Using the existing `HELPER_BOT_TARGET` chat is fine too; then nothing is copied.
2. **Add the helper bot** (the one behind `HELPER_BOT_TOKEN`) as an admin with
   *Post messages*.
3. **Set the variables.** They were created with placeholders (`0` and `-`),
   which keep the feature off. Put the real values in:

   ```powershell
   .\.github\scripts\setup-self-update.ps1 -ChannelUsername <username> -CheckBot
   ```

   That resolves the id, confirms the bot can post, and sets both variables.
   Without a username, pass `-ChannelId -100…` instead. `-Disable` puts the
   placeholders back. Or set them by hand under *Settings → Secrets and
   variables → Actions → Variables*.
4. **Ship one staging build** (merge anything to `dev`, or dispatch
   `staging.yml` on `dev`). Builds made before step 3 have no channel and can't
   update themselves, so install this one by hand once.
5. **Turn it on** in each install: Settings → Update Channel → Staging. An
   install that had Release or Beta picked before is already on.

## Checking it

- The `Upload staging` job log ends with
  `Published self-update metadata for <version> to <chat id>.`
- The channel holds two `#update…` posts after the APKs.
- An installed build from the previous staging run offers the new one from
  *Check for Updates*; the newest one says it is up to date.

Signing is unchanged: Android only installs an update signed with the same
key, so a wrong APK in the channel can't replace an install.
