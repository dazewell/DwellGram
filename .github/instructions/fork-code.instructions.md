---
applyTo: "TMessagesProj/src/main/java/{com/dazewell,com/radolyn,tw/nekomimi,xyz/nextalone}/**"
---

# You are editing fork-owned code

These packages belong to the fork, so the upstream-merge pressure that
constrains `org.telegram.**` does not apply. This is where new logic belongs.

- **Self-contained feature classes.** A feature gets its own package — e.g.
  `com.dazewell.gram.<feature>` — exposing a small surface the base file can
  call in one line. `hidelastmessage` and `chatlock` are the reference shapes;
  mirror the nearest existing feature rather than inventing a new layout.
- **Reuse before you build.** Grep for an existing controller, helper, dialog or
  cell that already does the thing. The passcode screen is
  `org.telegram.ui.Components.PasscodeView` and is already used standalone.
- **Config goes in an existing surface**, never a new one:
  `xyz.nextalone.nagram.NaConfig` (read as `NaConfig.INSTANCE.getX().Bool()`),
  `tw.nekomimi.nekogram.NekoConfig`, or `org.telegram.messenger.SharedConfig`.
- **Per-account state is keyed per account.** Never-synced feature state lives
  in a `<feature>_<account>` `SharedPreferences` file. A feature that ignores
  the account index is a bug on a multi-account install, which is the normal
  case here.
- **Fall back, don't migrate.** When a stored value's range or format changes,
  clamp or default it *at the read site*. No versioned migration, no
  grandfathering. The app must never crash on an absent, unparseable or
  out-of-range stored value.
- **Java 17 / Kotlin, legacy Android views.** No Compose, no Hilt, no Room.
  Match the surrounding file's language and style.
- **Threading is explicit.** UI work on the main thread, storage and network
  off it. State written from more than one thread needs a stated
  interleaving story before it is written, not after review finds it.

Full rules: `AGENTS.md` and the `repo-workflow` skill.
