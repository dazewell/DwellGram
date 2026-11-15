---
applyTo: "TMessagesProj/src/main/java/org/telegram/**"
---

# You are editing a base-fork file

Everything under `org.telegram.**` comes from upstream Telegram for Android.
This fork rebases onto upstream, and **every line you add here is a line that
has to be re-applied by hand at the next merge.** Treat the diffstat as the
cost.

- **Add as few lines as possible.** The target is a handful of injected lines
  per file, not a refactor. If you find yourself restructuring an upstream
  method, you are in the wrong file — put the logic in a fork-owned feature
  class and call into it.
- **Prefer a fully-qualified call over a new import.** A new import line is one
  more conflict hunk; `com.dazewell.gram.chatlock.ChatLockController.isLocked(id)`
  inline is zero.
- **Mark every injection `// NagramX:`** with the non-obvious *why*, not the
  what. The next person reading it is resolving a conflict and needs to know
  whether the hunk still applies.
- **Hook the chokepoint, not the call sites.** Find the one place all paths
  funnel through. Every way of opening a chat funnels through
  `ChatActivity.onFragmentCreate()`.
- **Don't reformat, re-order, or tidy anything you did not have to touch.**
  Whitespace churn in an upstream file is a conflict with no upside.
- **Never delete upstream code to make room.** Guard it instead.
- **`Log.v` and `Log.d` are stripped from release builds** by
  `proguard-rules.pro` via `-assumenosideeffects`. If a log line has to survive
  onto a device, it must be `Log.e` / `Log.i` / `Log.w`.

Common hook points, for reference: `ChatActivity` (in-chat overflow menu via
`headerItem.lazilyAddSubItem`), `Cells/DialogCell` (chat-list row rendering),
`DialogsActivity` (`showChatPreview` is the long-press peek), `LaunchActivity`
(app entry, intents, app-lock), `messenger/SharedConfig` (passcode, app-lock
state).

Full rules: `AGENTS.md` and the `repo-workflow` skill.
