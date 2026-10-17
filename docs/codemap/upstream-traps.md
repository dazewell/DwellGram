# Upstream traps

Non-obvious behaviour in base-fork code that has already bitten someone.
What the trap is, where it lives, and what it costs if you miss it.
Re-verify the citation before relying on it — see the README.

## The attach sheet is gone before the unlock rebuild runs

A fork feature that wants the attach sheet's state across an app lock cannot
read it in `ChatActivity.createView()`. The sheet is excluded from
`BaseFragment.onPause()`'s dialog dismissal by `dismissDialogOnPause`
(`ChatActivity.java:15613`), which makes it look like it survives to the
rebuild — it does not.

While the passcode view is showing, `LaunchActivity.onResume()` takes the
`else` branch and calls `actionBarLayout.dismissDialogs()`
(`LaunchActivity.java:7175`) → `INavigationLayout.dismissDialogs()`
(`INavigationLayout.java:281-287`) → `ChatActivity.dismissCurrentDialog()`
(`:32106`), which tears the sheet down with `dismissInternal()` (`:32109`).
That runs on the pause side of the lock. The unlock's
`rebuildFragments` → `createView()` comes later, and by then the sheet is no
longer showing.

Traced on device: at `createView` the alert object was still non-null but
`isShowing()` was already false, so a rescue gated on it never fired. Read the
sheet in `ChatActivity.onPause()` instead, where it is genuinely up.

*(Established 2026-09-21, `#attach-caption-guard`.)*

## A typed attachment caption only reaches the model through `applyCaption`

`PhotoViewer` keeps the caption being typed in its own `CaptionContainerView`,
and the only thing that writes it into the `PhotoEntry` (with its entities) is
the private `applyCaption()` (`PhotoViewer.java:10344`). Upstream calls it from
`closeCaptionEnter(true)` and from `toggleCaptionAbove()` (`:23903`) — both
user actions, neither of which an app lock performs.

`onPause()` looks like it covers this — it calls `closeCaptionEnter(true)`
behind `if (lastTitle != null)` (`PhotoViewer.java:19349`) — but `lastTitle` is
declared at `:1963` and the only assignment anywhere in the file sets it to
`null` (`:18038`), so the branch is dead code. Even with the guard passing,
`closeCaptionEnter` early-returns on `!isCaptionOpen()`, which the IME can make
false before `onPause` runs. Meanwhile `LaunchActivity.showPasscodeActivity`
closes the viewer outright (`LaunchActivity.java:1467-1469`), taking the text
with it.

The fork now applies at the top of `PhotoViewer.onPause()` (`:19341`), gated by
`shouldApplyCaptionOnPause()` (`:10317`). The gate matters: `applyCaption()`
auto-checks an unchecked item when the caption is non-empty (`:10370-10372`),
and a caption left on an item the user deliberately unchecked would otherwise
put that media back into the send set on any ordinary minimize.

A second trap sits inside that gate. `applyCaption()` stores the text only
after `MediaDataController.getEntities` has parsed markdown out of it
(`MediaDataController.java:7299`), so a typed `` `code` `` is stored as
`code`. Comparing an editor against the entry's stored caption therefore
reports a difference forever after the first apply, which is why
`AttachCaptionHelper.hasUnappliedCaption` derives the same canonical text and
entities before comparing.

*(Established 2026-09-20, `#attach-caption-guard`.)*

## `ChatAttachAlert.dismiss()` is not a teardown — it can leave the window up

`dismiss()` has two early returns that both leave the sheet on screen. The
first is `currentAttachLayout.onDismiss()` (`ChatAttachAlert.java:7005`), which
`ChatAttachAlertPhotoLayout` returns `true` from while its in-sheet camera is
open (`ChatAttachAlertPhotoLayout.java:4181`). The second diverts to a
"Discard selection?" confirmation and returns whenever anything is selected
(`ChatAttachAlert.java:7019-7026`).

That matters on any path that destroys the alert regardless of what `dismiss()`
did — a view rebuild, for instance — because the result is an ownerless sheet
sitting over the rebuilt screen. Use `dismissInternal()`, which routes straight
to `removeFromRoot()` → `super.dismissInternal()` (`ChatAttachAlert.java:6915`)
and is what `ChatActivity.onFragmentDestroy` already uses (`:3844`).

*(Established 2026-09-20, `#attach-caption-guard`.)*

## Copy replies need a peer, not a loaded preview

`messages.forwardMessages` has no `reply_to` field, so a real forward always
drops the source's own reply. The fork's copy dispatchers re-send instead
(`ChatActivity.java:16013,16054`), and `getOwnReply` rebuilds the target from
the `reply_to` header rather than requiring a loaded `replyMessageObject`
(`MessageHelper.java:1585-1638`). A staged drop-author forward that was merely
*scheduled* still went out as a real forward, losing its reply, until the
single-slot door was opened (`ChatActivity.java:16096-16103`).

`SendMessagesHelper.java:5152-5181` qualifies a cross-chat reply with
`reply_to_peer_id`; copying only a message id would address the destination's
own unrelated message. That same code redirects a **loaded forwarded** target to
its channel origin (`:5156-5162`), which is why the copy helper hands it a
detached reference carrying no forward header. A destination forum still needs
its own topic anchor (`:5173-5174`), dereferenced there unconditionally.

Note what the reconstructed peer does *not* buy. Every send builds its request
from the local header, `createReplyInput((TL_messageReplyHeader) newMsg.reply_to)`
(`SendMessagesHelper.java:5385,5444,5541`), and that header's `reply_to_peer_id`
is `peer2`, which upstream re-derives itself with
`getMessagesController().getPeer(replyToMsg.getDialogId())` (`:5154`).
`getPeer` picks channel over chat only when the chat is in cache
(`MessagesController.java:6100-6110`), so an uncached channel degrades to
`TL_peerChat` on the wire whatever peer the reference holds. Upstream's own
"reply in another chat" carries the same exposure, so fixing it belongs with
that code, not in the copy helper.

*(Established 2026-09-20, `#scheduled-reply-fix`.)*

## SectionsScrollView sectioning differs from RecyclerListView defaults

`SectionsScrollView.isSectionView(...)` excludes only views tagged
`RecyclerListView.TAG_NOT_SECTION` plus `TextInfoPrivacyCell`,
`ShadowSectionCell`, and `HintInnerCell` (`SectionsScrollView.java:36-41`).
Unlike `RecyclerListView`'s section checker, it does **not** auto-exclude
`CollapseTextCell` or `GraySectionCell`
(`RecyclerListView.java:3289`). So disclosure/header rows that should stay on
gray must be explicitly tagged `TAG_NOT_SECTION`; otherwise they get pulled
into white rounded cards.

Card width also comes from the first child in a section run: background bounds
use `from` child X/width (`SectionsScrollView.java:163-168`). If the first
section member has horizontal margins (for example a caption row with 21dp
insets), that narrower width becomes the whole card width. Margined captions
belong outside the card run (tagged out, or moved outside the grouped container)
to keep card edges aligned.

There is a known neighbour-visibility gap in `clipChild(...)`, but it is
**bounded and cosmetic-only** — it cannot produce a full-card clip. Child
gathering used for backgrounds skips `GONE` children
(`SectionsScrollView.java:107`), but `clipChild`'s own prev/next lookup reads
`contentView`'s full child list by raw index with no visibility gate
(`SectionsScrollView.java:190-194`). That gap does not touch the clip
rectangle itself: `AndroidUtilities.rectTmp` is computed once
(`SectionsScrollView.java:196-201`) and is byte-identical across all three
branches `clipChild` can take with it (`SectionsScrollView.java:202-219`).
`prev`/`next` decide only whether to skip clipping entirely
(the early return at `:205`) or which corners get rounded — bounded by the
16dp `sectionRadius`. A hidden neighbour also does not reliably push toward
MORE clipping: `isSectionView` (`:36-41`) returns `true` for a `GONE` view, so
the missing gate pushes `prev`/`next` MORE often true, which fires the no-clip
early return (`:205`) MORE often — but that early return can itself leave
either rounded corners where square ones were expected, or vice versa,
depending on which of the three branches (`:207-219`) would otherwise have
run. Don't assert a direction; the safe, established claim is only that the
effect is bounded to corner treatment and cannot erase a child.

*(Established 2026-09-07; corrected 2026-09-09, `#eventschedule`, after a
near-total vertical clip bug — a different mechanism entirely, below — was
first suspected to be this same gap and proven not to be.)*

## SectionsLinearLayout replays a stale clip after its parent SectionsScrollView resizes

`SectionsLinearLayout.drawChild` clips each child via the parent
`SectionsScrollView.clipChild(...)` and records that `canvas.clipPath(...)`
into the LinearLayout's OWN display list (`SectionsScrollView.java:228-235`
calling into `:185-220`) — but the clip rectangle's operands are the PARENT
`SectionsScrollView`'s `getScrollY()`/`getHeight()`
(`SectionsScrollView.java:196-200`). `onScrollChanged` already invalidates
both the scroll view and `contentView` when scroll changes
(`SectionsScrollView.java:79-87`) — that idiom covered the scroll operand but,
until this fix, nothing invalidated `contentView` when the PARENT's SIZE
changed instead. **This only bites when the content view's OWN frame stays
unchanged across that resize** — the scroll view has `isFillViewport = true`
(`BottomBuilder.kt:60`), so content SHORTER than the viewport is stretched to
fill it and gets dirtied normally by that layout pass; the hazard is specific
to content TALLER than the keyboard-open viewport, which fillViewport leaves
alone. Fixed by adding the missing half of the same idiom in an
`onSizeChanged` override (`SectionsScrollView.java:90-100`).

Observable signature, so a future investigation recognises it fast: the
card's white background is painted full-height (fresh, from the ScrollView's
own re-recorded `dispatchDraw`/`drawSectionsBackgrounds`) while the card's
CONTENTS are cut mid-row (stale, from the child's cached clip). The
draw-path trace above — the stale display list, the operands it was
recorded with, the missing invalidation — is what actually establishes the
diagnosis, independent of any touch. Separately, interacting with the
affected area was OBSERVED to repair it; that observation is consistent with
the diagnosis but doesn't itself prove no layout occurred, since the SeekBar
this card holds runs its own delegate on drag that updates a label and can
invalidate or re-lay-out itself (`EventScheduleHelper.java:1003-1012`) — a
generic "any touch invalidates the child" claim is not established. Confirmed
trigger: the Early-send bottom sheet's Delay card
(`EventScheduleHelper.java:966-1033`, reached via the only `sections=true`
`BottomBuilder` consumer in the tree, `EventScheduleHelper.java:656`) clipped
almost fully invisible after the IME hid and resized the sheet — the resize
mode is declared at `BottomSheet.java:218`, measured at `:591-657`, and
applied at `:1474-1476` and `:1570-1572` — while two pattern rows had pushed
the card below the keyboard-open viewport bottom.

*(Established 2026-09-09, `#eventschedule`.)*

## The enter view's measured height can drift from its laid-out height

`ChatActivity`'s fragment view measures `chatActivityEnterView` itself
(`ChatActivity.java:20252`) although its real parent is
`chatInputBubbleContainer` (`:8963`), so the view is measured with two different
specs in one pass. A later traversal can hit the view's measure cache for the
fragment's spec and restore an older size without calling `onMeasure`, while
the real parent skips measuring and laying it out. A device trace showed
`getMeasuredHeight()` drop from 459 to 416 mid-animation with `getHeight()` still
459 and no `onMeasure` or `onLayout` in between, and it stayed that way until the
next relayout. Why the fragment's spec resolves one line shorter is inferred,
not traced.

The reply panel's translation and the field's clip read `getHeight()` for that
reason, and `onLayout` re-places the panel once a new height lands
(`ChatActivityEnterView.java:5182`, `:18041`, `:19249`). Measure-time code still
wants the measured height; any other draw-time reader has to be traced before it
is switched. Signature: with a reply
open and the field growing, the reply content rides above its strip and the
bottom of the field (the MD3 tools row) is cut by the same amount until the next
keystroke that relays out.

*(Established 2026-09-24, `#interface-style-fix`.)*

## clipChild has no inverted-rect guard, unlike its background-drawing sibling

`drawSectionBackground` guards against an inverted `rectTmp` before using it
(`SectionsScrollView.java:169`: `if (rectTmp.bottom < rectTmp.top) return;`),
but `clipChild` builds the same kind of rect and hands it straight to
`Path.addRoundRect`/`canvas.clipPath` with no equivalent check
(`SectionsScrollView.java:196-219`). An inverted
rect there yields an empty `Path`, and clipping to an empty path erases the
child entirely rather than just mis-rounding a corner. Not the mechanism
behind the near-total-clip bug above (that one traced to a stale cached
clip, not an inverted rect computed at draw time) and not fixed here — scoped
out of that change by the reviewing architect as a separate, pre-existing
hazard.

*(Established 2026-09-09, `#eventschedule`; pre-existing and not fixed in
this branch.)*

## An app can never change an existing notification channel's importance in code; only the user can, via system settings

Android treats re-creating an existing `NotificationChannel` id as a no-op for importance, sound and vibration;
only the name and description can change. That is why the notification profiles use fixed, versioned channel ids
(`nax_np_v1_<account>_<tier>`, bump the version to change a preset: `NotificationProfiles.java:53`, `:376`) and why a
profile switch only picks a different existing channel at post time. The disguise cover owns no channel at all since
`#disguise-parity`: it posts on whatever channel `validateChannelId` picked for a normal notification
(`NotificationsController.java:5009-5015`, `NotificationCoverController.Behavior` at `NotificationCoverController.java:661`).
The one in-place edit it makes is a rename (`renameChatChannels`, `NotificationCoverController.java:694`), which relies on
the platform allowing a name change through `createNotificationChannel` (not yet device-verified).

*(Established 2026-09-07, `#disguise-alerting`; rewritten 2026-10-07, `#disguise-parity`.)*

## `GROUP_ALERT_SUMMARY` still mutes grouped children; an alerting child must skip it

In a grouped state only the summary alerts: upstream sets `GROUP_ALERT_SUMMARY` on every child
(`NotificationsController.java:5835`) and puts them on the shared OTHER channel. A cover child now does the same
(`NotificationCoverController.java:907`), and the cover summary carries the real channel and alert. A profile that
posts its summary on the silent channel but still wants its child to alert has to switch that child to `GROUP_ALERT_ALL`,
or grouping makes it inert. Quiet did this until it became Muted (Telegram's own mute); no profile needs it now.

*(Established 2026-09-07, `#disguise-alerting`; rewritten 2026-10-07; Quiet removed 2026-10-14, `#notif-profile-muted`.)*

## Cover alerting is upstream's `notifyDisabled`; the old cover-only growth gate is gone

Before `#disguise-parity` a cover had its own alert rules (`coverSilent`, a per-dialog suppression map mirroring
upstream, and a "new hidden member" growth gate with stored exact membership) and its own persona channels. All of that was
deleted: `showOrUpdateNotification` already folds notifyAboutLast, recording, mute, smart notifications, `sound_enabled_`
and the profile override into `notifyDisabled`, which feeds `validateChannelId(isSilent)` and `naxAlert`
(`NotificationsController.java:4526`), and a covered child that is not the newest chat cannot alert in a grouped state
anyway. Covers therefore re-alert wherever a normal notification would (repeat alarms, cold start). Two things remain
cover-specific: the tap/dismiss tokens and suppression state (written before `notify`, all under `COVER_STATE_LOCK`), and
the fact that Android can drop a notification at its global count limit without throwing from `notify(...)`, so only a Java
exception counts as a post failure. A legacy `nax_cover_v1_active_child_members_<dialogId>` key can still exist from older
installs; `clearDialogInteractionState` and `reconcile` clean it up.

*(Established 2026-09-07, `#disguise-alerting`; rewritten 2026-10-07, `#disguise-parity`.)*

## Editing `TMessagesProj/build.gradle` fails Sync guard check until its blob pin is bumped

Any fork-authored PR that edits `TMessagesProj/build.gradle` — even far from
the signing block, anywhere in the file — fails the `Sync guard check`
workflow with `signing-config build.gradle blob changed: <blob>`, because that
file is blob-pinned whole, not diffed at the hunk level. `Test-SignerBlobs`
compares the candidate's git blob hash for the whole file against a single
recorded value and fails on any mismatch (`.github/sync/sync-guard.ps1:303-308`),
and the pin it checks against lives in `.github/sync/pins.env` as the
`SIGNING_GRADLE_PATH`/`SIGNING_GRADLE_BLOB` pair (guard 13, signing identity).
`GRADLE_SURFACE` in the same file documents `build.gradle` as membership-only
for every other executable-surface file, but `pins.env`'s own comment on that
line says `build.gradle` is the one exception, blob-pinned instead of just
tracked — easy to miss because it reads like the opposite of a warning.

The fix is a second, separate commit in the same PR: recompute the blob with
`git rev-parse HEAD:TMessagesProj/build.gradle` at the final tree (never
hand-copy a hash out of a CI log) and update `SIGNING_GRADLE_BLOB` in
`pins.env` to match. There's direct precedent for this exact shape of commit:
`da79971452` ("repin signing gradle blob for the icon-comment edit
#dazegram-icons"), a one-line `pins.env` bump in its own commit, done after an
unrelated `build.gradle` comment edit tripped the same guard.

*(Established 2026-09-05.)*

## `NaConfig.notificationIcon` is a persisted index into an upstream-owned enumeration that has already diverged

The setting and `getNotificationIconResId()` came from upstream Nagram commit
`bc0f99fb0b` ("feat: add option of changing notification icon", Revincx,
2023-01-19 — `git show bc0f99fb0b --stat` shows it touching
`NotificationsController.java`, `NekoGeneralSettingsActivity.java`, and
`NaConfig.kt`). That commit introduced `getNotificationIconResId()` as
`private` and non-static, with 3 cases (`case 0` → `offical_notification`,
`case 1` → `nagram_notification`, `case 2` → `notification`, per that
commit's diff). The method's signature never diverged — this fork's version
is still `private int` (`NotificationsController.java:6531-6544`) — only its
value domain did. Upstream's three cases were `0` → `offical_notification`,
`1` → `nagram_notification`, `2` → `notification`, defaulting to
`offical_notification`. This fork's four are `0` → `notification`, `1` →
`nagramx_notification`, `2` → `nagram_notification`, `3` → `neko_notification`,
defaulting to `notification`. The divergence is therefore not a uniform
shift: `offical_notification` left the domain entirely; upstream's `case 2`
asset (`notification`) became this fork's `case 0` and its default; only
upstream's `case 1` asset (`nagram_notification`) moved down a slot, to
`case 2`; and `nagramx_notification` (`case 1`) and `neko_notification`
(`case 3`) are both new here. The backing config is
`TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/NaConfig.kt:256-260` (key
`"NotificationIcon"`, `configTypeInt`, default `1`), surfaced as 4 labels
(Telegram, NagramX, Nagram, NekoX) at `InterfaceStyleActivity.java:117-122`.

The trap: **the same stored integer already means a different icon in the two
codebases.** A user's persisted `1` is upstream's `nagram_notification` but
this fork's `nagramx_notification` — already a silent divergence, tolerated
because the fork never re-merges upstream's notification-icon UI wholesale.
The exposure is not conditional on this fork adding anything further. As
verified, upstream's domain ends at `case 2`, so the next value upstream
appends would be `case 3` — which this fork already uses for
`neko_notification`. A user who had picked NekoX would then silently get
upstream's new icon after a reconciliation merge, with no error and no
migration to catch it. Appending further fork-only values (`case 4` and
beyond) only widens the overlap. No later upstream commit
extending this domain has been verified, but nothing rules one out — extend
this behavior with a **new fork-owned `NaConfig` key** instead — one whose
value domain upstream has no way to write into — never by widening the value
domain of a key
upstream already owns and may extend.

*(Established 2026-09-05.)*

## `triggerKey()` is firing identity, queue bucket identity, and overview grouping identity

`EventScheduleController.queueKey(...)` composes queue identity as
`account + dialogId + triggerKey` (`EventScheduleController.java:120-125`), so
any change in `triggerKey()` repartitions the runtime send queues. The Message
Triggers screen groups rows by the same key (`MessageTriggersActivity.java:542-556`),
so it is also the overview grouping identity.

`EventScheduleEntry.triggerKey()` therefore has to stay order-independent and
injective for arbitrary user text. The current encoding canonicalizes the
normalized pattern set, sorts with `String` natural order, and length-prefixes
each element before concatenation (`EventScheduleEntry.java:163-174`). A
delimiter-only encoding or order-sensitive list key can collide unrelated
pattern sets, causing both wrong queue sharing and wrong UI grouping.

*(Established 2026-09-03.)*

## One `schedule_date` per forward batch

`TL_messages_forwardMessages` carries a single `schedule_date` field for the
**entire request**, set once on the request object
(`SendMessagesHelper.java:2628-2642`). Forwarding several messages at once
therefore lands every one of them on an identical scheduled timestamp — this
is the stock Telegram API request shape, not a fork defect. It is the root
cause of "I rescheduled one message and several others moved": the scheduled
list re-sorts around the resulting tie in `schedule_date`, which looks like
several messages moved together when only one send actually changed.
Distinct per-message times require distinct forward requests.

*(Established 2026-09-02.)*

## MessageDrawable's static motion background is a foreign invalidate producer

`MessageDrawable` keeps a static `MotionBackgroundDrawable[] motionBackground`
(`ActionBar/MessageDrawable.java:65`), enables `postInvalidateParent` on those
instances (`:231`, `:251`), and consumes them as a shader source
(`getBitmapShader` at `:256`) with bounds updates (`:286`) rather than drawing
that drawable as the chat wallpaper. In the drawable implementation,
`postInvalidateParent` posts the global `invalidateMotionBackground`
notification (`Components/MotionBackgroundDrawable.java:352`) and self-reposts
its own animation runnable every 16ms while active (`:361`).

That makes MessageDrawable a second app-wide producer of
`invalidateMotionBackground` events that are unrelated to the current chat
wallpaper motion — a bubble-animation tick and a real wallpaper-motion tick are
indistinguishable by notification id alone. ThemePreview's observer branch
remains arg-agnostic (`ThemePreviewActivity.java:3599`), so the payload is safe
for existing preview behavior.

*(Established 2026-09-04.)*

## Forwarding aliases the source message's media object

The forward path assigns the new local placeholder's media straight from the
source message rather than copying it —
`newMsg.media = msgObj.messageOwner.media` (`SendMessagesHelper.java:2398`).
`updateMediaPaths`, which reconciles the placeholder once the server confirms
the send, then mutates that same `TLRPC.MessageMedia` object in place — see
the photo-size and live-photo writes at `SendMessagesHelper.java:8759-8760`
and `:8780-8782`. Because of the alias, those writes land on the **original
forwarded message's media too**, not just the copy's.

This is upstream code, not a fork addition. Filed as
[#267](https://github.com/dazewell/DwellGram/issues/267). The copy-send route
avoids it because it builds fresh media from a local path instead of aliasing
(`tw/nekomimi/nekogram/helpers/MessageHelper.createSendingMediaInfo`).

*(Established 2026-09-02.)*

## A shared `extension` variable leaks across a mixed document batch

`SendMessagesHelper.prepareSendingMedia`'s "send as documents" flush declares
one `extension` variable outside its per-item loop
(`SendMessagesHelper.java:10980`), reassigns it per item as each document is
queued (e.g. `:11534-11552`), and then passes its **final leftover value**
as the `mime` argument to every `prepareSendingDocumentInternal` call for the
whole batch (`SendMessagesHelper.java:11718`). A batch of documents with
different extensions therefore gets the wrong MIME type on most of its
members — and since audio-attribute extraction inside
`prepareSendingDocumentInternal` branches on the mime it's handed
(`SendMessagesHelper.java:9377-9396`), a mixed-extension album can also lose
audio attributes it should have kept.

*(Established 2026-09-02.)*

## Sending a captured album as documents can split it by media type

`prepareSendingDocumentInternal` partitions a `groupId` by media type: when
the current item's derived `docType` differs from the previous item's, it
calls `finishGroup` and rotates to a fresh `groupId`
(`SendMessagesHelper.java:9646-9650`, types assigned at `:9631-9645`). That's
correct behaviour for an arbitrary multi-file share where the caller wants
photos and non-previewable files kept in separate groups. It's **wrong** for
a captured source album being resent as documents, where group membership
should follow the original album rather than get re-split by type. Passing
`docType == null` for that call suppresses the rotation, since the guard at
`:9646-9650` requires a non-null `docType` to fire.

*(Established 2026-09-02.)*

## `canEditMessageScheduleTime` has no `id <= 0` guard

`MessageObject.canEditMessageScheduleTime`
(`MessageObject.java:11768-11783`) has no check on `message.id`, unlike its
siblings `canEditMessageAnytime` (`:11745-11766`, bails on `message.id < 0` at
`:11746`) and `canEditMessage` (starting `:11785`, same bail at `:11792`).

This is **not currently exploitable**: an outgoing message with `id <= 0`
that isn't a send-error resolves to `MESSAGE_TYPE_INVALID` in
`ChatActivity.getMessageType` (`ChatActivity.java:20672-20696`), and
`processRowSelect` refuses to select any row whose type is below
`MESSAGE_TYPE_MEDIA` — which `MESSAGE_TYPE_INVALID` (`-1`) is
(`ChatActivity.java:21294`). The scheduled-message Reschedule path never
reaches a message in that state **for a directly-selected single message**;
the bulk path has a separate, unproven gap covered by its own entry below
("Bulk reschedule's album expansion bypasses the selection type check").
Recorded here as a latent gap with its shadowing guard so a future change to
`getMessageType` or `processRowSelect` doesn't silently reopen it.

*(Established 2026-09-02, citations refreshed 2026-09-03.)*

## `ShareAlert.darkTheme` (`= forCall`) is not VoIP-exclusive — it's just a misleading name

`ShareAlert.darkTheme` is assigned `= forCall` in the constructor
(`ShareAlert.java:452`). It has nothing to do with the app's light/dark theme
setting, but it is **not** exclusive to the VoIP call-invite screen either —
an earlier version of this entry claimed that, and it was wrong. Two callers
pass `forCall = true`: `GroupCallActivity.java:6722` (the VoIP group-call
invite share, with `copyLink2` non-null so `linkToCopy[1] != null`, reaching
the `dp(111)` header-height branch — not dead code) and
`PhotoViewer.java:8780` (ordinary photo/video sharing from the media viewer,
with `copyLink2 == null`, so it stays on the `dp(58)` branch despite
`forCall == true`). Every other `ShareAlert` construction site, including the
`ChatActivity` channel-post share arrow (`ChatActivity.java:42773`), passes
`forCall = false`.

The header-height ternary that appears throughout the file
(`dp(darkTheme && linkToCopy[1] != null ? 111 : 58)`, e.g.
`ShareAlert.java:1170`) branches on `darkTheme`, so a dark-looking screenshot
of the share sheet still tells you nothing about which branch is live — that
part holds. It just doesn't mean the caller is a VoIP screen; check the
actual constructor call and its `copyLink2` argument.

*(Established 2026-09-03.)*

## `allowSelectChildAtPosition`'s `y` is grid-local in the non-fullscreen case; adding `systemInsets.top` double-counts it there

`ShareAlert`'s `gridView` and `searchGridView` both override
`allowSelectChildAtPosition(x, y)` to gate taps below the header
(`ShareAlert.java:1168`, `:1253`).

**Non-fullscreen (`isFullscreen == false`) — the only case any current caller
reaches:** `containerView.onMeasure` sets `getPaddingTop()` to
`systemInsets.top`, gated by `if (!isFullscreen)` (`ShareAlert.java:699-703`),
and `onLayout` places every `Gravity.TOP` child, including the grid, at
`getPaddingTop() + topOffset` (`ShareAlert.java:855`). So in this case the
grid's `y` already has the status-bar inset netted out before the guard ever
runs, and adding `+ systemInsets.top` to the threshold double-counts it —
pushing the tap dead band down over the entire first avatar row, a silent
miss with no visual feedback that reads to a user as "the app ignored my tap"
rather than as an error.

**Fullscreen (`isFullscreen == true`):** the `setPadding` call above is
skipped entirely, so `getPaddingTop()` doesn't carry `systemInsets.top` and
the coordinate math differs from the case above. No current caller constructs
`ShareAlert` with `fullScreen = true` — checked every `new ShareAlert(...)`
and `ShareAlert.createShareAlert(...)` call site in the tree — so this branch
is presently unexercised. Worth knowing if a future caller ever does pass
`fullScreen = true`: the fix here was scoped to the reachable
(non-fullscreen) case only.

The identical `+ systemInsets.top` term is *correct* two hundred lines away,
in `containerView`'s own `onDraw` (`ShareAlert.java:928`, `:930`): there it
converts a grid-local `scrollOffsetY` into `containerView`'s own canvas space,
which is not padding-translated. NagramX's fix removes the extra term at both
`allowSelectChildAtPosition` call sites, leaving `y >= dp(...)` with nothing
added.

*(Established 2026-09-03.)*

## Vendored `update to <version>` commits are single-parent squashes, not merges

Commits like `37bd22c0f4` ("update to 12.7.0 (6740)") that bulk-vendor an
upstream Telegram release have a single parent (`628eabc372`) rather than
being a 3-way merge. A fork fix living in a file one of these commits
rewrites is therefore **silently overwritten with no merge conflict to flag
it** — there's nothing to alert the next vendoring pass that a line it's about
to replace was deliberately changed. `ShareAlert.java` alone has been
rewritten by six such bumps since 2025-11.

Concretely, for the exact hook this investigation touched
(`ShareAlert.java:1169-1170`): `37bd22c0f4`'s diff shows it *replacing*
`+ AndroidUtilities.statusBarHeight` with `+ systemInsets.top` in
`allowSelectChildAtPosition` — re-expressing an inset term that was already
there under a different API, not introducing one from a bare `dp(...)`
threshold. A vendoring commit rewriting a line doesn't announce whether it's
carrying a term forward, changing its source, or dropping fork-added
behaviour; only reading the actual diff tells you which. This is why a
one-token fork fix in a hot upstream file needs a `// NagramX:` comment
explaining the *why*: the comment is the only thing that survives to tell a
future investigator the line was intentional, since the diff itself won't.

*(Established 2026-09-03.)*

## Bulk reschedule's album expansion bypasses the selection type check

The single-message reschedule path is gated: `ChatActivity.getMessageType`
returns `MESSAGE_TYPE_INVALID` for a not-yet-reconciled outgoing message
(`id <= 0`, not a send error, `ChatActivity.java:20672-20696`), and
`processRowSelect` refuses to select anything below `MESSAGE_TYPE_MEDIA`
(`ChatActivity.java:21294`) — see the matching dead-end entry. The bulk
`RescheduleSpreadExecutor` path does **not** inherit that gate the same way.

`resolveRescheduleItems` picks an album's representative as the **minimum-id**
member of the group (`ChatActivity.java:37756-37762`,
`if (group.messages.get(k).getId() < first.getId()) first = ...`) — not the
message the user actually selected, and with no positivity check on that
comparison. A still-sending sibling carries a negative local id, which sorts
below every positive server id, so it can become `first` (and therefore
`target.id`) outright.

Separately, `EventScheduleBulkArmer.AlbumIdentity.of`
(`EventScheduleBulkArmer.java:79-91`) captures every `group.messages.get(k).getId()`
into `serverIds` (→ `RescheduleSpreadExecutor.Target.albumIds`) by iterating
the live group map directly, with no `getMessageType`/selectability check per
member — it only ever sees the *representative* that passed selection, not
each sibling. A non-positive sibling id can therefore land in `albumIds` even
when the representative itself is positive and was validly selected.

Reachability of either case through the shipped UI is **unproven either
way** — album sends have not been observed acking asynchronously enough to
leave one sibling negative while another is already positive — this is
recorded as a live gap, not a confirmed defect. `RescheduleSpreadExecutor.sendNext`
guards against both shapes directly (`target.id <= 0` and any non-positive
`target.albumIds` member) rather than relying on this selection-level gating,
since the gating above was never proven to reach this executor's inputs.

*(Established 2026-09-03.)*

## Release builds strip `Log.v` and `Log.d`

`TMessagesProj/proguard-rules.pro:173-176` has an `-assumenosideeffects` block
for `android.util.Log` that lists `v(...)` and `d(...)`, so R8 removes every
`Log.v` and `Log.d` call from the minified release variant. `Log.e`, `Log.i`
and `Log.w` are not listed and survive. Any diagnostic that has to appear on a
real device must use one of those three — a `Log.d` line compiles fine and then
emits nothing once installed.

The **local debug compile gate cannot catch this**:
`:TMessagesProj:compileDebugJavaWithJavac` builds the non-minified debug
variant, where the rule does not apply and `Log.d` works. The stripping only
happens in the minified release build that `staging.yml` produces — which is
the only variant that ever reaches a phone. So a `Log.d` diagnostic passes the
gate, passes CI, installs, and is silent, with nothing upstream of the device
to flag it.

Cost the `#repost-spread` instrumentation a full device test cycle: the
`NAX_SPREAD_DIAG` logging was written with `Log.d`, produced zero logcat output
on the installed staging APK, and had to be reissued at `Log.e`. Referenced by
[PR #270](https://github.com/dazewell/DwellGram/pull/270).

*(Established 2026-09-02.)*

## Notifications post from two independent builders — fixing one leaks the other

`NotificationsController` renders a message notification twice over: the account
**summary/group** is built in `showOrUpdateNotification` (the `mBuilder` InboxStyle
around `NotificationsController.java:4392-4423`, posted as `mainNotification` inside
`showExtraNotifications`), and each **per-dialog child** is built separately in
`showExtraNotifications` (`:4908`+). Both read real `pushMessages` content. Anything
that means to suppress a chat's real name/sender/text has to intercept **both**: a
child-only change still leaks every real line through the summary's InboxStyle. The
disguised-cover engine resolves the exact covered-dialog set in a **preflight** at the
top of `showOrUpdateNotification`, before any real identity/content is read
(`:4140`+), then threads that one immutable set + grouping into `showExtraNotifications`,
which builds a fresh generic summary instead of the real one when any covered dialog is
present (`naxBuildCoverSummary` at `:5878`) and swaps each covered child for a fresh
tagged builder, routing on `naxCoveredSet.contains(dialogId)` (`:5038`+).

## Popup notifications are a third leak surface, separate from summary + children

`popupMessages` is fed from `addToPopupMessages(...)` during new/edit processing,
outside the summary/child builder flow (`NotificationsController.java:947-977`,
`:1196-1200`, `:1253-1256`). A cover implementation that only swaps summary/child
notifications still leaks covered content through popup windows unless this path is
blocked too.

The hardened cover path now blocks covered members at source
(`NotificationCoverController.blocksPopupMessage(...)` in `addToPopupMessages`,
`NotificationsController.java:949-952`) and also purges already-queued popup rows in
the covered preflight pass (`NotificationsController.java:4194-4210`) so enabling
disguise mid-stream cannot leave stale covered popup cards behind.

*(Established 2026-09-03.)*

## Android 16 auto-groups a lone disguised notification about 3 s after it posts, even with an explicit group key

On the OPPO (Android 16, 2026-10-07) a disguised Loud or Quiet cover was followed ~3 s later by a
`notification_autogrouped` event and a `key_group_key` adjustment to `g:Aggregate_AlertingSection` in the event log
(`adb logcat -d -b events`), including when the cover carried an explicit group key with no summary
(`groupKey=nax_cover_solo_9`, 09:26, build 0c0603c33e; that key was then removed as ineffective). A real conversation notification
(shortcut, locus id, MessagingStyle) posted at the same time was not auto-grouped, and neither were Passive covers (minimum
importance). The user sees a double watch vibration on disguised Loud and Quiet chats and one vibration on a normal chat; the
auto-group is the only difference seen in the log, so it is the suspected cause. The fix makes a cover a
persona conversation (`conversationSupported`, `conversationId`, the shortcut and MessagingStyle in `postChild`,
`NotificationCoverController.java:748`, `:763`, `:816`) gated exactly like the real child. Confirmed on the same phone
(2026-10-07, 11:50-11:51): three messages ~15 s and ~30 s apart to a disguised Loud chat gave three `notification_enqueue` and three
`notification_alert` events on `naxcover_9_<dialog>` and no `notification_autogrouped` anywhere in the event log (watch vibration not
re-observed that round). The shortcut, locus id and person key are one random
id per (account, dialog), rotated on uncover, so they never name the real dialog; uncovering and logout also remove the shortcut and
any conversation channel the user created (`clearConversation`, `NotificationCoverController.java:778`). Separately, upstream re-posts
a normal notification on the `silent` channel about 5 s after the alert, which is why its status-bar icon disappears on that phone.

*(Established 2026-10-07, `#disguise-parity`.)*

## `validateChannelId` creates a chat-named OS channel; a covered chat must pass the persona name and be renamed

`validateChannelId(lastDialogId, ...)` can create a per-chat channel (only when the chat has custom sound, importance, LED or
vibration, or the in-app variant) whose name is the real chat title, set once at creation
(`NotificationsController.java:4120-4122`). Covers now call it too, with the persona label as the name when the newest chat is
covered (`NotificationsController.java:5009-5015`). A channel that already exists keeps its old title, so
`NotificationCoverController` renames every `<account>channel_<dialogId>_*` channel of a covered chat in place: on each rebuild
(`collectCovered`, `NotificationCoverController.java:334`), when disguise is enabled and when the persona changes
(`:235`, `:251`). Uncovering a chat leaves the persona name (the safe direction). The channel id format is private to
`validateChannelId`; if it changes, `renameChatChannels` silently stops matching. Also note upstream's
`deleteNotificationChannelInternal` builds `org.telegram.key<dialogId>` (`NotificationsController.java:3484`), without the
`_<topic>_<soundHash>` suffix `validateChannelId` stores (`:3911-3913`), so it looks like it never finds the channel (inferred
from reading, never run).

*(Established 2026-09-03; rewritten 2026-10-07, `#disguise-parity`.)*

## `minSdk` is 27, so the `SDK_INT <= 19` notification branch is dead

`build.gradle:36` pins `minSdk = 27`. The `Build.VERSION.SDK_INT <= 19` early-return in
`showExtraNotifications` (`NotificationsController.java:4944`) and the other
`<= 19` guards never execute on a shipped build; don't spend effort covering them, and
treat `<= 27` conditions as "always true on the oldest supported device."

*(Established 2026-09-03.)*

## Process-global spoiler atlas publishes one full animation generation per pass

`SpoilerEffectBitmapFactory` is a process-global singleton atlas producer
(`SpoilerEffectBitmapFactory.java:26-47`), and `SpoilerEffect.draw` is the path
that submits active bounds into that producer (`SpoilerEffect.java:323,329`).
Text surfaces use this path through spoiler clip-out + draw in `SimpleTextView`
(`SimpleTextView.java:1210-1218,1247-1248`). Pinned-bar media thumbs use the
same path via the top-panel `BackupImageView` overlay's embedded
`SpoilerEffect` (`ChatActivity.java:12765-12791,30841-30843`). In-message media
particles are a separate renderer (`SpoilerEffect2`) in `ChatMessageCell`
(`ChatMessageCell.java:15399-15405`) and are not controlled here.

Current invariant: every accepted background publish is one full-atlas
generation. The update runnable now allocates background bitmap/canvas once,
then on each accepted pass erases the full bitmap (when reusing) and runs one
unconditional `doDraw(backgroundCanvas, fullRegion)` before publish copy and
UI-thread shader swap (`SpoilerEffectBitmapFactory.java:146-168`).

Why partial repaint cannot be coherent on this atlas: producer-side simulation
advances by cell intersection with the trigger union (`Rect.intersects` in
`SpoilerEffectBitmapFactory.doDraw`, `SpoilerEffectBitmapFactory.java:96-104`),
while particle admission is clip-relative with a damage margin
(`SpoilerEffect.java:342,369-372,464`). At the same time, `applyClip` maps view
bounds into wrapped atlas coordinates and unions wrapped segments
(`SpoilerEffectBitmapFactory.java:120-130`). Clipping rasterization to only a
subset of those intersected cells necessarily mixes generations across adjacent
texels and overlapping mapped bounds.

Cost facts that stay true regardless of clip strategy:
`Utilities.copyBitmaps(backgroundBitmap, nextBufferBitmap)` is already a full
bitmap copy on every accepted pass (`SpoilerEffectBitmapFactory.java:163`);
native implementation copies the full pixel payload (`image.cpp:1249-1334`,
contiguous path `memcpy(rowBytes * height)` at `image.cpp:1327`).
Full draw also already exists on first UI paint and on LiteMode restore
(`SpoilerEffectBitmapFactory.java:79,86`), so mechanism B aligns publish with
the existing full-generation paths instead of introducing a new one.

The dirty-union trigger drop is intentionally not fixed here. `checkUpdate` +
`applyClip` + `clipRegion` remain trigger-only (`SpoilerEffectBitmapFactory.java:112-130`),
and the callback still clears that union each frame (`SpoilerEffectBitmapFactory.java:136-139`).
With full-atlas publish, missed trigger unions may reduce temporal smoothness
but no longer produce spatially mixed generations.

Threading hazard to keep: `isRunning` is cleared only after the UI publish hop
sets `currentBitmapBuffer` and shader (`SpoilerEffectBitmapFactory.java:165-168`).
Clearing it earlier would allow a new pass to start writing while the previous
buffer index is still pending publication.

*(Established 2026-09-04.)*

## The `transtale` -> `translate` package rename means upstream `transtale/*` changes must be ported, and upstream callers of `transtale` symbols silently fail to compile

Fork commit `0887abcd02` ("chore: fix typos & optimize imports") renamed the
translation package `tw.nekomimi.nekogram.transtale` ->
`tw.nekomimi.nekogram.translate` (`git show --stat 0887abcd02` shows the git
rename explicitly as `.../nekogram/{transtale => translate}/Translator.kt`).
Our live translator is
`TMessagesProj/src/main/java/tw/nekomimi/nekogram/translate/Translator.kt`; the
old `transtale/Translator.kt` path no longer exists on `dev`. So any upstream
change to a file under `transtale/` arrives as a modify/delete conflict
(deleted on our side), and any upstream code that *calls* a symbol added under
`transtale/` will not resolve against our tree until the symbol is ported into
`translate/` by hand.

This bit the 2026-09-06 sync of `NextAlone/Nagram` onto anchor
`b03d83df87`: upstream added `Translator.translateShowAlert(...)` to its
`transtale/Translator.kt` and called it from a bot-button long-press menu at
`981806a992:TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java:41468`
(the immutable sync snapshot -- cite that, not the moving `nagram/dev` ref,
which drifts on every upstream push and rots the line number).
Because the merge produced no conflict at that call site (it landed inside a
`ChatActivity` region that did conflict, but the call itself was conflict-free
text), taking upstream's `ChatActivity` hunk without porting the helper would
have pushed a `dev` that does not compile -- the same silent non-compile class
`.github/sync/pins.env` records from the 12.10.1 sync. The fix was to port
`translateShowAlert` into `translate/Translator.kt`, adapted to the fork's
reworked translate API (no `TranslateDb.currentTarget` cache; `AlertUtil`
progress/copy/failure dialogs), and take the `ChatActivity` call.

The standing rule this leaves for every future sync: whenever upstream touches
a `transtale/*` symbol, compare the incoming change against the candidate's
`translate/*` implementation **and its callers**, and port anything missing.
Don't lean on a one-shot presence check like `git grep translateShowAlert
origin/dev` -- that grep succeeds forever once this PR lands and would falsely
reassure even if a *later* upstream change to the same helper went unported.
The failure is silent at merge time (upstream callers of `transtale` symbols
compile against upstream but not against our renamed tree), so it only surfaces
at the compile gate; that is the check to trust, not a grep.

*(Established 2026-09-06, during the NextAlone/Nagram sync reconciliation, snapshot `981806a992`.)*

## The aggregated group summary notification bridges every pushed chat's message text to Wear, even when a chat's own child notification is `setLocalOnly`

`NotificationsController.showExtraNotifications(...)` posts one per-dialog child
notification per chat plus, when `useSummaryNotification` is true — API 27
(`O_MR1`) and below unconditionally, otherwise only when more than one non-story
message dialog is pushed (`sortedDialogs.size() > (storyPushMessages.isEmpty() ? 1 : 2)`,
`NotificationsController.java:4958`) —
a single aggregate summary built from `notificationBuilder` — the `mBuilder`
first assembled back in `showOrUpdateNotification`. That summary's `InboxStyle`
is not a count: it adds up to 10 lines of real `getStringForMessage(...)` output
— sender names and message text drawn from every pushed dialog
(`NotificationsController.java:4443`) — and it is **never** given `setLocalOnly`.
The trap: giving a per-dialog child `setLocalOnly(true)` (encrypted chats have
always done this, `:5834`; the `#wear-messages` per-chat "Show on Watch" toggle
does it at `:5838`) does **not** stop that chat's text riding to a paired Wear OS
watch, because the *summary* is a separate `Notification` without `setLocalOnly`.
So a per-chat "keep this off the watch" control governs the child but not the
shared summary; whenever Android builds that summary it can still preview a
switched-off chat on the watch — and on API 27 and below it builds one even for a
single unread chat, so a lone switched-off chat is not fully hidden there either.

This is upstream behaviour, and it is exactly how Telegram already treats secret
chats: their child is local-only (`:5834`) while the aggregate summary that
previews them is not. **`#wear-messages` deliberately does not fix it** — the
shipped feature is the two per-dialog hooks only (child `setLocalOnly` at the
shared builder chokepoint `:5838`, and the disguised-cover child card in
`NotificationCoverController.postChild:734`). The accepted, product-approved
consequence is the summary leak above. Three cheaper summary fixes were tried
across review and rejected, so do not re-derive them:

- **Gate the whole summary local-only when any pushed chat is watch-off** (an
  early revision's batch-wide `naxAnyWatchOff` scan). Wrong: the fork's disguised
  "cover" children still use `GROUP_ALERT_SUMMARY` on the upstream-silent path
  (`NotificationCoverController.java:724-799`), and older builds routed every
  covered child through that same silent grouped path; blanking the summary
  therefore silenced watch *alerting* for watch-on chats in those grouped
  cases the moment one unrelated chat was switched off — a real on-device
  regression with "Disguise notification" on.
- **Re-arm the children with `GROUP_ALERT_CHILDREN`** so they alert without the
  summary. Dead: children fall back to `OTHER_NOTIFICATIONS_CHANNEL`, which is
  created with sound/vibration/lights disabled (`:292-296`), so flipping alert
  ownership to the children mutes everything anyway.
- **Redact the summary content** to a safe-representative form. Viable but not
  bought: it needs a safe representative across both the `InboxStyle` branch and
  the non-inbox branch, plus title, ticker, person, actions, channel and the Wear
  dismissal id — and Samsung skips the inbox summary path entirely (`allowSummary`
  false, `:4366-4367`), so the redaction would have to cover a second
  construction path too. Deferred by product decision, not overlooked.

Eliminating the leak properly needs a different grouping/summary design. Until
then, the per-chat hooks are honest about what they do — they keep a chat's *own*
notification off the watch — and `FEATURES.md` states the summary limitation
plainly rather than implying full suppression.

## Two independent "any overlap hides the Quote button" gates, one of them a private upstream method

`EditTextBoldCursor.shouldShowQuoteButton()` (`EditTextBoldCursor.java:1213-1236`, private, pure
upstream — git log shows only version-bump commits, zero `// NagramX:` markers before this entry)
decides whether the platform's own text-selection popup (`FloatingToolbar`, wired via
`floatingToolbar.setQuoteShowVisible(this::shouldShowQuoteButton)`, `EditTextBoldCursor.java:1194`)
shows a Quote item at all. Before `#toggle-formatting` it hid the item the moment *any*
`QuoteSpan.QuoteStyleSpan` overlapped the selection, so a quote could be added but never removed
from that surface. `ComposerFormattingActions.isQuoteAvailable(Editable,int,int)`
(`ComposerFormattingActions.java:229-241`, fork-owned) enables/disables the glass composer
toolbar's Quote button on the *identical* condition, independently. Both had to be relaxed in the
same change to make quote toggle-off reachable outside the header overflow menu and the
`Ctrl+Shift+.` hotkey (the two paths that already called `makeSelectedQuote()` directly): now
enabled/shown when there's no overlap (add) OR the selection exactly contains one quote block
(remove), still hidden/disabled only for a partial/non-containing overlap. The
`EditTextBoldCursor.java` edit is the one sanctioned upstream-file touch for this change — everything
else lives in `EditTextCaption.java` and the fork-owned `ComposerFormattingActions.java`.

*(Established 2026-09-06, `#toggle-formatting`.)*

Key-format note for a future edit in this area: `WearBridgeHelper` owns the
`nax_wear_<dialogId>` format, but `ProfileNotificationsActivity` repeats the
`"nax_wear_" + dialogId` literal inline for both its read and its write, so a
rename of the key must touch the settings screen too, not just the helper.

*(Established 2026-09-06, during the #wear-messages build; three review rounds on the summary region ended by dropping all summary suppression to the two per-chat hooks only.)*

## `VideoEditedInfo`'s serialised form round-trips on every send, and `parseString` infers `muted` from `bitrate == -1`

The `ve` string is not just a draft/restore mechanism -- the full
serialise/parse cycle runs on the ordinary send path:
`SendMessagesHelper.java:4824` -> `:4969` -> `:5269` -> `MessageObject.java:4473`
-> `MediaController.java:6846`. Crucially `parseString` derives `muted` from the
legacy bitrate slot (`VideoEditedInfo.java:590-599`) rather than storing it, so
any field added to the format is subject to that inference on every send, not
only on restore. Miss this and a change that sets `muted` in memory while also
writing a real bitrate will have the mute silently discarded at parse time,
because a non-`-1` bitrate re-derives `muted = false`. Conversion runs off the
parsed object (`scheduleVideoConvert(message.obj)`,
`SendMessagesHelper.java:6628` -> `MediaController.java:6592`), so the
in-memory value never reaches the transcoder. This is why the silent-video
feature forces the legacy slot to `-1` whenever muted and carries the real
bitrate in a separate version-12 extension block.

*(Established 2026-09-06, during the silent-video feature build, PR #300.)*

## `MediaController.makeVideoBitrate`'s early return skips its own `maxBitrate` clamp

At `MediaController.java:7018-7021`, `minBitrate` is computed and then
`if (originalBitrate < minBitrate) return remeasuredBitrate;` returns *before*
reaching the `maxBitrate` clamp further down (`:7061-7062`). The helper is not
guaranteed to return a value under its own maximum. A 4096x4096 source at
35 Mbps resized to 3840x3840 gives `minBitrate` = 36.16 Mbps and returns
32,812,500 -- above the 28.4 Mbps `VIDEO_BITRATE_2160` tier. This only escapes
for a near-square source whose long edge exceeds 3840; below that the top tier
takes the same-dimension branch instead. Any code that assumes the returned
bitrate sits inside the range the app normally generates will be wrong on
large near-square sources. Two of three reviewers on PR #300 asserted the
same-dimension branch was the only path that could produce an unclamped
bitrate; that is false, and only a direct read of the early return settled it.

*(Established 2026-09-06, during the silent-video feature build, PR #300.)*

## `needConvert()` returns false on `bitrate == -2`, and no conversion means audio is never stripped

`VideoEditedInfo.needConvert()` (`VideoEditedInfo.java:777-780`) returns false
when `bitrate == -2` (the "Original" quality selection, set in
`PhotoViewer.updateWidthHeightBitrateForCompression` around `:22055-22058`).
When it returns false, `prepareSendingMedia` leaves `path` pointing at the
original file (`SendMessagesHelper.java:11455-11460`) and no conversion is
scheduled (`:6620-6628`). The transcoder is the only thing that strips the
audio track, so a muted clip that reaches the send path carrying `-2` uploads
the untouched source with its sound intact. Treat "muted" and
"`bitrate == -2`" as a combination that must never be serialised together. It
is currently unreachable only by accident -- the mute tap normalises the tier
via `getCurrentVideoEditedInfo()` forcing `selectedCompression = 1`
(`PhotoViewer.java:10229-10231`), any editor exit re-stores the normalised
value, and attach-sheet show resets entries
(`ChatAttachAlertPhotoLayout.java:3916` -> `:1919` ->
`MediaController.java:534-556`). None of those are load-bearing by design, so
any change that makes a pre-mute quality tier survive a mute must first ensure
`-2` cannot reach a muted record.

*(Established 2026-09-06, during the silent-video feature build, PR #300.)*

## Rounded grouped settings cards already exist upstream as `RecyclerListView.setSections()` — don't hand-roll them

The rounded, flat, shadowless grouped-card look that every fork settings page
wears is one call, not a layout you build: `listView.setSections(true)`. The
one-arg overload resolves to `setSections(dp(12), dp(16), true)` and thence to
the DEFAULT section-exclusion predicate (`RecyclerListView.java:3284-3296`),
so card boundaries fall wherever an excluded cell (`TextInfoPrivacyCell`,
`ShadowSectionCell`, ...) sits and are auto-detected live per frame. Both fork
settings base classes already call the same one-arg form
(`BaseNekoSettingsActivity.java:146`, `BaseNekoXSettingsActivity.java:125`), so
a page only misses the treatment if it extends `BaseFragment` directly and
never opted in. Prefer the one-arg overload over the explicit-parameter form:
the latter re-declares the exclusion predicate in fork code, a fork-local copy
of an upstream list that drifts silently on the next sync.

The trap sits next door: `RecyclerListView` also takes `forcedSections`, which
pins card ranges to STATIC adapter positions. It is safe only where drag stays
inside one section — `FiltersSetupActivity` uses it for exactly that
(`FiltersSetupActivity.java:1162-1176`, intra-section reorder). On a page whose
drag crosses section boundaries it fails twice at once, because a forced range
also SUPPRESSES auto-detection for its positions in the draw loop
(`RecyclerListView.java:3529-3534`). If the page mutates its item list live
mid-drag (as a cross-zone reorder does), the forced range holds pre-drag
positions for the whole gesture: it draws a card at the wrong bounds AND hides
the correct auto-detected one, self-correcting only on release — a defect that
never fails CI and never shows in a static screenshot. On any such page,
`setSections(true)` alone is the whole answer; auto-detection is correct, live
and free.

*(Established 2026-09-06, adopting the treatment on `ComposerLayoutActivity`.)*

## `SlideIntChooseView` swallows the seek bar's drag-end signal

`SeekBarView` exposes exactly two delegate callbacks (`SeekBarView.java:96-97`): `onSeekBarDrag(boolean stop, float progress)` and a default `onSeekBarPressed(boolean pressed)`. There is **no** `onSeekBarReleased` — release is `onSeekBarPressed(false)`. And the terminal drag callback is asymmetric across the two ways a gesture can end: every `setSeekBarDrag(...)` in the terminal branch sits inside `if (getAction() == ACTION_UP)` (`:271`), firing `onSeekBarDrag(stop=true, …)` at `:280` for the non-two-sided slider, so **`ACTION_UP` delivers a final drag callback but `ACTION_CANCEL` delivers none at all** — a cancelled gesture only reaches `onSeekBarPressed(false)` (`:286`), the same release signal `ACTION_UP` also sends after its drag callback. Press is `onSeekBarPressed(true)` from the move handler (`:313`). `SlideIntChooseView`'s anonymous delegate (`SlideIntChooseView.java:95-115`) throws all of that away: it ignores `stop`, overrides neither pressed callback, narrows the reported callback to `Utilities.Callback<Integer>` (a bare value, no drag-state), keeps `seekBarView` private with no accessor, and — because `setReportChanges(true)` is on for the composer's Toolbar-size slider (`SlideIntChooseView.java:94`) — fires continuously on every step crossed during a drag with no way for a caller to tell a mid-drag step from the last one. So a consumer that needs "the drag ended" cannot get it from this widget, and a consumer that needs "the gesture was cancelled" gets nothing from the drag callback at all.

The fork-only workaround, used by `ComposerLayoutActivity` for the packing-row settle, is to override `dispatchTouchEvent` on the fragment's own root `FrameLayout` (`ComposerLayoutActivity.java:287`) and treat `ACTION_UP`/`ACTION_CANCEL` there as the gesture-end edge. `SlideIntChooseView` calls `requestDisallowInterceptTouchEvent(true)` on `ACTION_DOWN` (`:86-89`), which suppresses the `RecyclerView`'s *interception* but never *dispatch*, so the root still sees every event including the terminal one. Do **not** reach for a debounce/quiet-interval timer instead — users hold this slider still to read the live preview, so a timer fires mid-gesture with the finger down. And do **not** try to add a `getSeekBarView()` accessor or an `onSeekBarPressed` forward to fix it at source: `SlideIntChooseView` and `SeekBarView` carry zero fork edits and are touched by nearly every upstream bump, so an edit there is a permanent rebase tax.

*(Established 2026-09-06, #composer-spacing.)*

## `SlideIntChooseView.getProgress` and `getValue` are not inverses when `betweenSteps > 1`

`SlideIntChooseView` maps a value to a track position with `getProgress` (`:186-197`) and a track position back to a value with `getValue` (`:199-207`), but the two use different arithmetic. `getProgress` computes `Math.round(...) / options.betweenSteps` (`:192`), and because `Math.round(float)` returns `int` and `Options.betweenSteps` is declared `int` (`:345`), that is an **integer division**: for any value that falls between two anchors, the sub-anchor term truncates to `0`, so the value collapses onto its *lower* anchor's track position. `getValue` divides the analogous term by `(float) options.betweenSteps` (`:204`), a float division, so it *does* return real sub-anchor values. The round trip therefore fails: `getValue` hands out `89`, but `getProgress(89)` returns the track position of `85`, not of `89`.

With `betweenSteps == 1` the division is `/1` and both are exact, so this only bites a caller that configures a step table with anchors more than one unit apart and a `betweenSteps > 1` to subdivide them. Two distinct symptoms follow, both silent:

- **Thumb desyncs from its own label on rebind.** `set()` (`:172-184`) drives the thumb with `setProgress(getProgress(value))` while `updateTexts` prints the exact `value`. A between-anchor value lands the thumb on the lower anchor while the label reads the true number. A user's own drag hides this (the thumb rests at the raw finger position and is never re-derived); the first *rebind* of the row exposes it.
- **`setMinValueAllowed` misdraws the dimmed floor band.** It sets `minProgress` to `getProgress(floor)` (`:230`), so a floor that is not itself an anchor truncates: a floor of `88` over anchors `{85,90,95,100}` yields `getProgress(88)=0` and **no band renders**; a floor of `93` yields `getProgress(93)=0.333` (the position of `90`) so the band renders but **stops short** of the real floor.

The fix is fork-side and does not touch the widget: list every reachable value as its own anchor and set `betweenSteps = 1`, so `getProgress` has nothing to truncate. In the composer layout editor this was `SPACING_STEPS`/`SPACING_BETWEEN_STEPS` in `ComposerLayoutActivity`, moved from `{85,90,95,100}` + `5` to the full `85..100` + `1`. Do not "simplify" such a table back to sparse anchors with a larger `betweenSteps` — it reintroduces both symptoms.

The thumb-vs-label desync above is not limited to the `betweenSteps > 1` case — it is the normal resting state of *any* `SlideIntChooseView` after a drag, because the underlying `SeekBarView` never canonicalizes the released position to a discrete step. On `ACTION_UP`, when the touch lands off the thumb, it *does* reassign `thumbX` to the raw finger pixel, clamped to `minThumbX()`/`getMeasuredWidth() - selectorWidth` (`SeekBarView.java:259-262`), before reporting the released progress to its delegate — `setSeekBarDrag(true, thumbX / (w - selectorWidth))` (`SeekBarView.java:280`) — but nothing rounds that position to a step. The one place `thumbX` gets rounded at all doesn't reach the stored field: `onDraw` shadows it into a local (`int thumbX = this.thumbX;`, `SeekBarView.java:496`) before rounding that local when `delegate.needVisuallyDivideSteps()` is true (`:503-505`), which `SlideIntChooseView` hard-codes to `false` (`SlideIntChooseView.java:124-126`) anyway — so even a caller that flipped that gate would only be rounding a draw-time copy, never correcting the field it draws from next frame. Meanwhile the label is driven independently by `updateTexts(value, true)` from the drag callback (`SlideIntChooseView.java:110`). So a thumb released between two representable values rests off-detent while the label already shows the nearest value, and stays there until something *rebinds* the row (`set()` → `setProgress(getProgress(value), false)`, `:172-184`). With `betweenSteps == 1` that rebind lands the thumb exactly on the label's value, which is what `ComposerLayoutActivity`'s gesture-end `snapSliderRows()` relies on to snap all four composer sliders on `ACTION_UP`/`ACTION_CANCEL`.

*(Established 2026-09-06, #composer-spacing.)*

## `SlideIntChooseView.setMinValueAllowed` corrects the thumb upward only, never back down

`SlideIntChooseView.setMinValueAllowed` (`:222-233`) enforces its floor purely through `seekBarView.setMinProgress(getProgress(value))`, and the enforcement is one-directional. `SeekBarView.setProgress(float, boolean)` clamps the new thumb position against `minThumbX()` (`SeekBarView.java:411-412`), and `minThumbX()` (`:353-355`) is derived from `minProgress` — the value left over from the *previous* call. `setMinProgress` (`:229-234`) only re-applies progress when `getProgress() < minProgress`, i.e. it corrects the thumb **upward** only. So *raising* the floor moves a too-low thumb up, but *lowering* the floor never moves a too-high thumb back down.

The consequence bites any caller that rebinds one of these sliders with both a new value and a lower floor in a single `set()` + `setMinValueAllowed()` pair, when `set()` runs first: `set()` drives `setProgress` while `minProgress` is still the old, higher floor, so the thumb is clamped to the old floor's position even though `updateTexts` moves the printed label to the new value. Label and thumb then disagree until something else rebinds the row. In the composer layout editor this surfaced as: Toolbar size 100%→75%→100% across two gestures left Icon spacing reading 85 with the thumb pinned hard right (`ComposerLayoutActivity`'s `TYPE_SPACING` bind). The fix, all fork-side, is to drop the allowed minimum to the slider's own floor **before** `set()` so the value lands unclamped, then raise it to the real floor **after** — never edit `SeekBarView`/`SlideIntChooseView` for it.

*(Established 2026-09-06, #composer-spacing.)*

## `Tools/scripts/requirements.txt` is installed by every publish build, so anything added there taxes the APK pipeline

`staging.yml` runs `python -m pip install -r Tools/scripts/requirements.txt` immediately before invoking `upload.py`, the script that posts a finished build to Telegram (`.github/workflows/staging.yml:454-456`). That requirements file exists for `upload.py` alone — it pins the pyrogram client and its crypto extension, nothing else. Any dependency added to it is therefore downloaded and installed on **every** publish build, including builds that never touch the tool that needs it.

This is not visible from `requirements.txt` itself, which looks like a general-purpose manifest for `Tools/scripts/`. It is not. When the README wall compositor needed Pillow, the dependency went into a separate `Tools/scripts/requirements-images.txt` specifically to keep it off the build path; that file carries the reasoning inline, but only someone already editing it would see it.

Before adding a Python dependency anywhere under `Tools/scripts/`, decide which file it belongs in: the CI-installed one, or a separate manifest installed by hand. Getting it wrong costs build minutes on every publish, permanently, for no benefit.

*(Established 2026-09-06, #docs, PR #294.)*

## `Path.is_absolute()` is `False` for Windows drive-relative paths, so confinement checks need `path.anchor`

A path like `C:foo.png` has a drive but no root, so Python reports `Path("C:foo.png").is_absolute()` as `False` on Windows. Joining it does **not** behave like a relative path, though: `PureWindowsPath.__truediv__` replaces the drive component, so `out_dir / "C:foo.png"` silently discards `out_dir` entirely.

Any guard written as "reject absolute paths, then join" therefore lets drive-relative values straight through and writes outside the directory it was meant to confine. The correct test is `path.anchor`, which is `"C:"` for exactly these values and empty for a genuine relative path.

Both path guards in the wall compositor check `.anchor` rather than relying on `is_absolute()` alone — `_confine_source` for panel sources (`Tools/scripts/compose_walls.py:157-171`) and `_require_plain_png_filename` for wall outputs (`Tools/scripts/compose_walls.py:187-200`). The output guard shipped with only the `is_absolute()` check first and was caught in review; the source guard had the same gap and was closed in the same pass.

*(Established 2026-09-06, #docs, PR #294.)*
## isGhostModeActive() returns true vacuously when all five ghost toggles are locked

Established 2026-09-09 (#ghost-hold). `isGhostModeActive()` `continue`s past any
toggle whose `Locked` companion is set, so if all five are locked the loop body
never runs and it returns its initial `true` (`NekoConfig.java:305-319`).
`setGhostMode` also skips locked items (`NekoConfig.java:321-330`), so in that
state Ghost is permanently on and `toggleGhostMode()` is a no-op. It is not
reachable through the UI today: `GhostModeActivity.onItemLongClick` refuses a
fifth lock (`getGhostModeLockedCount() >= 4`), so at least one toggle is always
unlocked and Ghost stays turn-off-able. Any feature that makes "Ghost never
turns off" harmful (Ghost Hold, whose queue would become unflushable) must rely
on that 4-lock cap, or handle the vacuous-true case itself.

## getUnsentMessages queries scheduled_messages_v2 too, and checkUnsentMessages has two callers

Established 2026-09-09 (#ghost-hold). `MessagesStorage.getUnsentMessages` runs a
second cursor over `scheduled_messages_v2` selecting `mid < 0 AND send_state = 1`
(`MessagesStorage.java:8726`), so local unsent scheduled rows are pulled into the
resend path alongside `messages_v2` rows, and `processUnsentMessages` feeds them
to its scheduled retry loop (`SendMessagesHelper.java:9126`+). This runs not just
at startup: `checkUnsentMessages()` is called from `ApplicationLoader.java:308`
**and** from inside `processSentMessage` (`SendMessagesHelper.java:1811`), i.e.
every time the unsent queue drains during normal use. The current fork-store
design does NOT park held rows here — held rows live in the fork-owned
`ghosthold_<account>.db` and are injected as display-only objects, so a
steady-state drain finds nothing of ours (see `ui-to-code.md`'s "A local
negative-id row in scheduled_messages_v2 renders in the Scheduled list" entry;
the pre-rebuild design that persisted held rows as negative-id
`scheduled_messages_v2` rows was discarded as unsafe). The `scheduled_messages_v2`
drain guards that remain — `processUnsentMessages` skips held rows
(`SendMessagesHelper.java:9147`) and `retrySendMessage` refuses them
(`SendMessagesHelper.java:1766`) — survive for exactly one window: a legacy-upgrade
launch where `checkUnsentMessages()` runs the auto-resend loop before
`checkOnProcessStart()` has migrated the old marked rows out of the stock tables.
In that window a still-marked legacy row could otherwise auto-send while Ghost is
on (a P1 leak); migration makes the guards unreachable after the first
post-upgrade launch. So the stock-table guards are legacy-migration protection,
not defence of a live store — do not read this entry as licence to park held rows
in `scheduled_messages_v2`, and do not delete the fork store on the belief that
they live there.

*(Corrected 2026-09-11, #ghost-hold, PR #347: the original wording said the live
design parks held rows in `scheduled_messages_v2` — it does not; that was the
discarded pre-rebuild design.)*

## AutoDeleteMediaTask's file pin is in-memory only and dies on restart

Established 2026-09-09 (#ghost-hold). `AutoDeleteMediaTask` keeps its "don't
delete this file yet" set in an in-memory structure with `lockFile`/`unlockFile`
(`AutoDeleteMediaTask.java:17`, `:249-274`). Both the age pass and the
over-limit pass exclude paths present in that set
(`AutoDeleteMediaTask.java:76-130`, `:142-190`, `:220-236`), but nothing
persists the set, so a process restart drops every pin. The task runs at most
once per 24 hours (`AutoDeleteMediaTask.java:21-25`); that cadence is not a
retention guarantee after the process-memory protection is gone.

The media cache itself is built from Android's external or internal cache
directory (`AndroidUtilities.java:2627-2674`,
`ImageLoader.java:2416-2428`), and Clear Cache directly walks the selected
`FileLoader.MEDIA_DIR_CACHE` directory (`CacheControlActivity.java:1022-1074`).
Neither path provides indefinite ownership. App-private files under
`ApplicationLoader.getFilesDirFixed()` are outside those media-cache sweeps
(`ApplicationLoader.java:188-212`), but a private copy alone does not make the
stock uploader read those bytes; see the photo upload-path trap below.

*(Photo-retention implications re-verified 2026-09-13, #ghost-hold, after
PR #363 was closed unmerged.)*

## Local photo upload re-resolves the largest PhotoSize; attachPath is not its byte identity

Local photo preparation creates a small size and then a full-size entry
(`SendMessagesHelper.java:9365-9394`). The outgoing placeholder records an
`attachPath` from the last size's location (`SendMessagesHelper.java:4749-4753`),
but the delayed upload keeps the last `PhotoSize` separately
(`SendMessagesHelper.java:5623-5628`). When upload runs, it resolves that
`PhotoSize` again through `FileLoader.getPathToAttach(...)` and passes the
resulting path to `uploadFile(...)`; it does not read `Message.attachPath` or
verify the file's bytes first (`SendMessagesHelper.java:6624-6642`).
`FileLoader` keys and constructs the upload operation from that path string
alone (`FileLoader.java:401-427`, `FileUploadOperation.java:98-104`).

Locally generated sizes use `volume_id = Integer.MIN_VALUE` and a negative
`SharedConfig.getLastLocalId()` value, then write into the media cache under a
name built from that pair (`ImageLoader.java:4183-4233`).
`FileLoader.getPathToAttach(...)` routes that location back to
`MEDIA_DIR_CACHE` (`FileLoader.java:1374-1385`) and
`getAttachFileName(...)` repeats the same `volume_id_local_id.jpg` identity
(`FileLoader.java:1696-1702`). A retry therefore owns only that shared cache
name, not an immutable association with the bytes originally generated there.

*(Established 2026-09-13, #ghost-hold, while preserving the failure evidence
from closed PR #363.)*

## SharedConfig local photo ids can rewind onto an existing cache name

`SharedConfig.getLastLocalId()` only decrements the process-memory counter
(`SharedConfig.java:535-539`). Persistence is a separate broader config write
(`SharedConfig.java:489`), and startup reloads the last persisted value
(`SharedConfig.java:563`), so a hard kill can reissue ids consumed since the
last save. `ImageLoader` then derives the cache filename from the reissued
volume/local pair and opens that path for output
(`ImageLoader.java:4183-4233`).

If that name still represents a held photo retry, ordinary stock photo
generation can replace its bytes. The retry later follows the shared
`PhotoSize` name to `uploadFile(...)` without a byte-identity check
(`SendMessagesHelper.java:6624-6642`, `FileLoader.java:401-427`,
`FileUploadOperation.java:98-104`), so it can upload an unrelated photo.
Refusing to overwrite an existing target avoids that direction but does not
make restore-to-the-same-id safe; the discarded design is recorded in
`dead-ends.md`.

*(Established 2026-09-13, #ghost-hold, from current-tree mechanics and the
collision found during PR #363 review.)*

## Sync guard check validates protected pins against the branch tree, not the PR merge ref

Established 2026-09-09 (#ghost-hold). The `protected pins vs HEAD` step of the
sync guard reads each pinned blob from the branch's own tree
(`.github/sync/sync-guard.ps1`, `protected-paths.tsv`), so a branch cut before a
repin lands on `dev` stays red on that check until `dev` is merged forward into
it — merging the branch's own PR does **not** retroactively clear it. Confirmed
from PR #325 (repinned `README.md` `d48354cf…` → `d9373e96…` on `dev`) and PR
#324 (cut beforehand, still failing on head `330451f9de` after #325 merged). The
natural assumption — "the merge ref has both the new pin and the new file, so
it'll sort itself out" — is wrong, and re-deriving it costs a full CI round each
time. This is a recurring shape here, not a one-off: `git log` already carries
`repin signing gradle blob…` commits and a `document the build.gradle
signing-blob pin trap` entry for the same class of problem. The fix is a
`#tag`-exempt merge of `origin/dev` into the feature branch once its tree is
clean.

## canEditMessageScheduleTime lacks the negative-id guard its sibling canEditMessage has

Established 2026-09-10 (#ghost-hold). `MessageObject.canEditMessage(...)` bails
out for a local, unsent row: it returns `false` when `message.id < 0`
(`MessageObject.java:11792`), so the general "Edit" action correctly hides
itself for any negative-id scheduled row. Its sibling
`canEditMessageScheduleTime(...)` (`MessageObject.java:11768-11782`) has **no**
such check — it returns `true` for a DM / megagroup / creator row regardless of
id sign. So the scheduled-list "Edit schedule time" action offers itself for a
purely-local negative-id row and, on tap, issues `TL_messages_editMessage`
against an id the server has never seen. For an ordinary failed-schedule row
that is a harmless failed request; for a Ghost Hold row it is a server round
trip while Ghost is on — the exact exposure the feature exists to prevent.

This is a pre-existing upstream asymmetry, not something Ghost Hold introduced —
any negative-id scheduled row hits it. Ghost Hold guards **only its own held
rows** at the call site (`ChatActivity.java`, the `OPTION_EDIT_SCHEDULE_TIME`
block, mirroring the existing `!isHeld` Send Now guard) and deliberately does
**not** touch `canEditMessageScheduleTime` itself: fixing the upstream method
would widen the diff into shared base-fork code for a bug that costs a failed
request, not data loss. Recorded so the next person who wonders why the held-row
guard exists — or who trusts `canEditMessageScheduleTime` to mirror
`canEditMessage`'s id check — does not burn an investigation on it.

## GhostModeActivity posts mainUserInfoChanged on the selected account, not the fragment's

Established 2026-09-10 (#ghost-hold). `GhostModeActivity` posts
`NotificationCenter.mainUserInfoChanged` on
`NotificationCenter.getInstance(UserConfig.selectedAccount)` — at
`GhostModeActivity.java:153` (the Ghost Hold toggle notice path) and,
pre-existing since before `#ghost-hold`, at `:207` and `:211` (the
`showGhostInDrawer` / `showGhostModeStatus` toggles). The fragment itself
observes via `getNotificationCenter()`, i.e. its own `currentAccount`
(`:90`). So if this screen is ever reached on a non-selected account, the post
lands on a different center than the observer and the rows/held-count refresh is
missed. In practice the screen is opened on the selected account, so the two
coincide and the observer fires.

This is a **pre-existing, fork-wide pattern**, not something Ghost Hold
introduced — the two sibling posts predate it and use the same
`selectedAccount` center. A code review flagged the `:153` post in isolation;
"fixing" only that one line would leave the file internally inconsistent with
its two neighbours three lines down and would be a drive-by edit on pre-existing
code. If the pattern is wrong it is wrong in three places and is a separate
change with its own justification. Recorded so the next reviewer who spots the
`:153` post does not re-raise it as a Ghost Hold defect.
## randoms_v2 never receives a positive mid for a send-now message

Established 2026-09-10 (#ghost-hold). The `random_id -> mid` correlation that
`SendMessagesHelper`'s `alreadySent` path relies on is only usable for messages
that stay scheduled server-side; for a send-now message it can never fire. Three
facts together:

- The mid rewrite in `updateMessageStateAndIdInternal` that writes the confirmed
  server id back into `randoms_v2` is gated `_oldId < 0 && scheduled == 1`
  (`MessagesStorage.java:13923`). A send-now message is `scheduled == 0`, so it
  never enters this branch.
- The `scheduled == 0` id-remap branch (`MessagesStorage.java:14061`+) rewrites
  `messages_v2`, `messages_topics`, `media_v4`, `media_topics` and
  `dialogs.last_mid`, but **not** `randoms_v2`.
- Post-confirmation `putMessages` cannot backfill it either: an incoming server
  message carries `random_id == 0` (`MessagesStorage.java:12248`), and the
  `randoms_v2` insert is gated on a non-zero random id (`:12825`), so the insert
  is skipped.

Cost if missed: any design that keys a held/pending message off `randoms_v2`
expecting to later read back its positive mid on the primary send-now path is
building on a row that is never written. This is the verified fact that ruled
out the original Ghost Hold storage design (a stock `scheduled_messages_v2` row
with a negative id and `send_state = 1`) and drove the rebuild onto fork-owned
state — making it work would have required editing stock
`updateMessageStateAndIdInternal`, changing message-receipt behaviour for every
chat in the app.

## Grouped media carries `groupId` before every sibling reaches the send funnel

The grouped-media preparation paths mint or retain a group id before dispatching
any sibling (`SendMessagesHelper.java:11070-11076`). Each supported grouped branch
then writes that id into the sibling's params before its UI-thread
`sendMessage(SendMessageParams)` call: documents at `:9686-9720`, search media at
`:11244-11264`, videos at `:11521-11565`, and photos at `:11748-11780`. By the
time the single Ghost Hold hook runs, every sibling is already identifiable as
grouped; `GhostHoldController.isHoldableSend` refuses a non-zero `groupId` at
`GhostHoldController.java:236-240`, so it cannot admit only part of an album.

Cost if missed: admitting one sibling would separate it from the in-memory group
completion state and leave a partially held, partially sent album.

*(Established 2026-09-12, #ghost-hold.)*

## `SendMessageParams.sendAnimationData` is non-null on every ordinary composer send

`ChatActivityEnterView` builds a fresh `MessageObject.SendAnimationData` for a
normal (non-forwarding) text send before constructing the params
(`ChatActivityEnterView.java:9627-9629`), and `of(...)` stores it verbatim
(`SendMessagesHelper.java:12537`). It is a transient UI fly-in hint — the
composer's on-screen x/y/width/height — carrying no part of the sent message,
but it is set on ~100% of composer sends. Any allowlist/denylist that treats an
unrecognised non-default `SendMessageParams` field as "not an ordinary text
send" must exclude `sendAnimationData` (and `updateStickersOrder`, a local
recent-emoji reorder flag, `ChatActivityEnterView.java:9649`) or it rejects
every real message.

Cost if missed: Ghost Hold's fail-closed backstop `onlyPersistedFieldsSet`
refused to hold anything with a non-default unknown field, so `sendAnimationData`
made it refuse **every** composer send — the message went straight to the network
with Ghost on. Cost a full device cycle to surface because the leak is silent and
the send otherwise looks normal (`GhostHoldController.java:389-396`, 2026-09-10).

## `-keep class org.telegram.messenger.* { *; }` DOES keep nested-class members

The single-`*` keep rule (`proguard-rules.pro:9`) is often assumed not to match
a nested class such as `org.telegram.messenger.SendMessagesHelper$SendMessageParams`
(`$` mistaken for a package boundary). It does: in the shipped minified APK
`377d89c` (`org.telegram.messenger.beta`, staging = release R8 config), all 54
instance fields of that class keep their **original** names (`message`, `caption`,
`sendAnimationData`, …) in the DEX — verified with `dexdump` on the exact
installed artifact. So reflection over `Field.getName()` on this class happens to
survive R8 today. It is still not safe to rely on: a keep rule narrowed to
`**`-vs-`*` or dropping `{ *; }` would silently rename these fields and break
name-based reflection with no compile error, which is why Ghost Hold moved its
field check onto compile-checked `p.<field>` references plus a name-independent
declared-field **count** guard (`GhostHoldController.java:353-420`, 2026-09-10).

Cost if missed: this fact killed hypothesis H1 (that R8 renamed the fields and
disabled Ghost Hold's backstop in the minified build only). It did not; the real
cause was the `sendAnimationData` trap above. Re-deriving this costs a minified
build + DEX inspection.

## `SendMessageParams.of(MessageObject)` restores the reply header but NOT entities

On the retry/re-drive path (`retryMessageObject != null`) the outgoing text
request pulls its two formatting-bearing fields from two different places, and
only one of them is the stored message:

- **Reply header rides on the stored message.** `sendMessage` sets
  `newMsg = retryMessageObject.messageOwner` (`SendMessagesHelper.java:4547`) and
  builds `reqSend.reply_to` from `newMsg.reply_to`
  (`SendMessagesHelper.java:5443-5444` via
  `createReplyInput(TL_messageReplyHeader)` at `:227-240`, which also reads
  `reply_to_peer_id` when `flags & 1` is set). So whatever reply header is on the
  stored row is what gets sent -- `of(MessageObject)` passing `replyToMsg = null`
  (`SendMessagesHelper.java:12453`) does not lose it.
- **Entities ride on the params, which `of()` nulls.** The local `entities` used
  to build `reqSend.entities` comes from `sendMessageParams.entities`
  (`SendMessagesHelper.java:4399`, then `reqSend.entities = entities` at `:5462`),
  and `of(MessageObject)` passes `entities = null`
  (`SendMessagesHelper.java:12453`). `newMsg.entities` is never read back into the
  request on this path (the Pangu block at `:5243-5256` only ever re-derives from
  the already-null local, and its `newMsg.entities = entities` write is guarded on
  the local being non-empty, so it does not restore anything either). So a
  re-drive built purely from `of(mo)` ships with **no entities** -- bold, links,
  mentions and custom-emoji all silently gone -- even though the reply header
  survives.

Cost if missed: any redrive/retry that reconstructs a send via
`SendMessageParams.of(MessageObject)` and assumes "it copies everything off the
stored message" is half right. Ghost Hold's flush hit exactly this: the held
message stored its entities on the blob but flushed as plain text until the
re-drive explicitly restored `p.entities = m.entities`
(`GhostHoldController.java:1065-1073`, the `dispatchFreshItem` `of(mo)` block,
2026-09-10, #ghost-hold). The reply header needed no such restore, which is what makes the
asymmetry a trap -- testing a reply-with-formatting would show the reply intact
and the formatting gone, pointing at the wrong half.

## `process-rules.yml`'s tag job name is declared unquoted, so its real status-check context is the truncated string `Every commit carries a`, and the required-checks ruleset pins that exact truncation

`process-rules.yml` declares its tag job as `name: Every commit carries a #tag`, unquoted (`.github/workflows/process-rules.yml:28`). In YAML a space-preceded `#` starts a comment, so everything from `#tag` on is discarded and the job's real name — and therefore the **GitHub status-check context** it reports under — is the truncated literal `Every commit carries a` (22 chars, trailing space trimmed). Verified live with `gh pr view 334 --json statusCheckRollup --jq '.statusCheckRollup[].name'`, which returns exactly `Every commit carries a` (2026-09-10).

The no-bypass ruleset that gates merges into `dev` requires **that exact truncated string** as its one status check: ruleset `22861936` (`dev required checks (no bypass)`), `rule=required_status_checks`, context `Every commit carries a`, `strict=false`, `bypass_actors: []` — all confirmed via `gh api repos/dazewell/DwellGram/rulesets/22861936` (2026-09-10). **Quoting the job name to "fix" the YAML would rename the check context to `Every commit carries a #tag`; the required context `Every commit carries a` would then never report, and every PR into `dev` would sit forever waiting on a check that no longer runs — a hard deadlock against the live ruleset.** If the name is ever corrected, the ruleset's required context must be updated **first**, in a separate step, or in lockstep. This is why the tag job is deliberately out of scope for any "tidy the workflow YAML" change.

The reason this ruleset is *separate* from the pre-existing `dev` ruleset rather than a rule added to it: ruleset `18550420` (`dev no-force no-delete`; until 2026-09-25 named `… + Copilot review` and carrying a `copilot_code_review` rule, removed so reviews are only ever requested by hand) carries `bypass_actors` including `RepositoryRole 5` (admin) at `bypass_mode: always` (plus a DeployKey and an Integration, same mode) — confirmed via `gh api repos/dazewell/DwellGram/rulesets/18550420` (2026-09-10). An agent runs under dazewell's admin token, so any required check added to `18550420` would be bypassed for exactly the actor it is meant to bind. Ruleset `22861936` has an **empty** `bypass_actors` list, which is why it is a *separate* ruleset: the required `Every commit carries a` context therefore applies to every actor, the admin token included, so no merge an agent can issue lands a commit that fails the tag check. That — tag integrity — is the **only** property this ruleset enforces. It does **not** enforce root-session identity, the named in-chat approval, gate freshness at merge time, or the `.github/sync/**` exclusion; each of those rests entirely on the agent-process prose, with no platform control behind it. So do not read `22861936` as the safety net for conditional agent merge authority as a whole — it is the safety net for exactly one property, and the rest of the authority is only as strong as the prose that describes it. (What `gh pr merge --admin` would do against an empty-`bypass_actors` ruleset has not been tested here, so this file makes no claim either way; the `--admin`/`--auto` prohibition is a rule the agent process imposes on itself, not an assertion about platform behaviour.)

One live behaviour to expect around all this: GitHub computes `mergeable` and `mergeStateStatus` **asynchronously**, and they are distinct fields — `mergeable` settles to `MERGEABLE`/`CONFLICTING`, while `CLEAN` is a value of `mergeStateStatus`. After ruleset `22861936` was created, open PRs read `mergeable: UNKNOWN` (and `mergeStateStatus: UNKNOWN`) for minutes before `mergeable` settled to `MERGEABLE` and `mergeStateStatus` to `CLEAN`, and a PR that had already MERGED read `UNKNOWN` indefinitely (2026-09-10). So `mergeable: MERGEABLE` is never a freshness guarantee — it answers "does this textually merge", not "is this green" — and the instant any merge moves `dev`, every other open PR's `mergeStateStatus` drops back to `UNKNOWN` until a background job recomputes it. Gate on `mergeStateStatus == CLEAN` plus a head-pinned green head check, re-read each time by polling `mergeStateStatus` itself, never on a cached or just-observed `mergeable`.

*(Established 2026-09-10, #docs.)*

## Event-schedule per-account state survives logout unless explicitly torn down, and a BottomSheet picker can straddle the logout

Account slot indices are reused: logging out of an account and logging into a
new one reuses the same numeric slot. Every `com.dazewell.gram.eventschedule`
store keys off that slot, not a stable identity, so nothing is cleared for free
on logout. `MessagesController.performLogout` is the single teardown chokepoint
(`MessagesController.java:16388-16402`, beside the `PasscodeHelper`/preset
clears). Two distinct leak shapes must be closed there, needing different
mechanisms:

- **Field-level teardown** for state a stale read would match: each store's
  `clearAccountState(account)` (cache, loaded flag, prefs, and
  `EventScheduleStore.nonEmptyAccounts` bit), plus
  `EventScheduleController.onAccountLoggedOut(account)` for its runtime maps
  (`PENDING`, `QUEUES`/`SUPPRESSED`, `DURABLE_INFLIGHT`, `warmedAccounts`,
  `pendingAccounts`). Miss one and the new account matches the departed
  account's armed triggers on the hot new-message path.

- **Generation-guarded continuations** for writes already *in flight* when
  logout ran. Each store carries a `GENERATION` counter bumped inside
  `clearAccountState`; an action captures the token at intent and the store
  rejects a write whose token no longer matches. Placement matters: the guard
  lives at the store *writer* (`EventScheduleStore.resolveAndClaimForEdit`), not
  the dispatcher, because the bulk-arm path (`EventScheduleBulkArmer` ->
  `RescheduleSpreadExecutor.run` -> `onFinalize` -> `finalizeOnUi` ->
  `reconcileDialogThen` -> `armSurvivor` -> `bulkArmSurvivor`) reaches
  `resolveAndClaimForEdit`/`persist` **without** ever passing through
  `postDurableLookup` -- a token captured only in `postDurableLookup` would
  never fire on that path.

The subtle half: the schedule **picker** is a directly-shown `BottomSheet`
(`AlertsCreator.createScheduleDatePickerDialog`, `AlertsCreator.java:4406`),
never registered as the fragment's `visibleDialog`, so
`LaunchActivity.switchToAvailableAccountOrLogout`'s fragment swap does **not**
dismiss it on logout -- the same structural gap #330 documented for the child
sheet via `presetLogoutObserver` (`EventScheduleHelper.java:945-952`). So a
generation captured *when the picker opens*, or at the top of `commit()`, can
read the already-bumped post-logout value, making every downstream check a
tautology. The tokens are therefore captured at `EventScheduleHelper.Row`
**construction** -- before the picker is shown (`EventScheduleHelper.java:277-279`,
`:417-422`) -- and re-verified fail-closed in `snapshot()` and `commit()`
(`EventScheduleHelper.java:1632`, `:1646`). `snapshot()` returning null also
stops any `EventScheduleBulkArmer` being built for a departed slot, and
`armPending -> EventScheduleStore.persist` carries no token of its own, so that
`commit()` gate is the only thing covering it.

The picker is not the last non-dismissed surface on the bulk path. A selection
of more than 50 messages defers the whole reschedule behind a **second** bare
`AlertDialog` confirmation (`ChatActivity.java:37750-37756`), built directly and
likewise never a `visibleDialog`. `EventScheduleBulkArmer.onAdmission` runs
*inside* that dialog's positive-button callback, so a token re-read there sees
the post-logout value just as the picker case does -- one window later. The
construction-time `storeGeneration` therefore travels the whole way: `snapshot()`
packs it into an `EventScheduleHelper.TriggerArmIntent` carrier
(`EventScheduleHelper.java:1635`) threaded through the reschedule delegate
(`AlertsCreator.java:4312`) into the armer, which compares the carried token at
admission (`EventScheduleBulkArmer.java:213`) and fails closed before it
registers an observer or suppresses the new occupant's triggers. Lesson: a
generation captured before *any* directly-shown dialog must be carried to the
actual mutation site, never re-derived past the dialog.

A reused slot also gets installed a *second* way -- `LoginActivity.onAuthSuccess`
(`LoginActivity.java:1710`), which already carries its own fork per-slot reset
(`PasscodeHelper.clearAccountState`, `:1715`) -- so it is fair to ask whether the
event-schedule clears belong there too, or behind some shared hook both paths
share. They do not: `performLogout` stays the sole chokepoint that matters for
*our* state, because a slot can never reach a login picker without it having run
first. Every slot-picking login entry point gates on
`!UserConfig.isClientActivated()`, i.e. `currentUser == null`; `currentUser` is
nulled in exactly one place, `UserConfig.clearConfig()`; and every
`clearConfig()` call site outside `onAuthSuccess` itself is either inside
`performLogout` or immediately followed by `performLogout(0)` in the same
UI-thread runnable (the native auth-key-unregistered path and the SESSION_REVOKE
push path both do `clearConfig()` then `performLogout(0)`). So the
remote-session-revoked-while-closed case still tears our state down before any
picker sees the slot as free. Extending `MessagesController.cleanup()` into a
shared clear-hook was considered and **rejected**: it is upstream's generic
runtime reset, called from two sites including `onAuthSuccess` itself
(`LoginActivity.java:1716`), so a persisted store-clear parked behind it becomes
a data-loss bug the day upstream adds a non-logout caller -- its call-site set is
upstream's to grow, not ours to police.

*(Established 2026-09-10, `#eventschedule`, PR #338 -- closing the logout leak
across `EventScheduleLastSetup`, `EventScheduleStore`, and the controller, on
top of the `EventSchedulePresetStore` fix in #330.)*

## `TL_ephemeral.TL_sendMessage` is not a text RPC -- it is the merged wrapper for both `messages.sendMessage` and `messages.sendMedia`

The name reads like a text-only send, and it is not. When a send has an
ephemeral receiver bot set, `EphemeralMessagesHelper#beforeSendingFinalRequest`
rewrites the outgoing request into a single `TL_ephemeral.TL_sendMessage`
regardless of what it started as: the `TL_messages_sendMessage` branch
(`EphemeralMessagesHelper.java:141`) sets `newRequest.media = null` (`:155`),
and the `TL_messages_sendMedia` branch (`:182`) copies `newRequest.media =
request.media` straight across (`:196`). The class declares `public
TLRPC.InputMedia media` (`TL_ephemeral.java:343`) precisely because of that
second branch, and
`FileRefController.java:161-163` shows it routinely carrying paid media and
polls. The ephemeral receiver itself is resolved for every send including
media, from the caption where there is one
(`SendMessagesHelper.java:4439-4450`).

Cost of missing it: any code classifying outgoing requests by TL class that
files this class under "text" will silently mis-handle ephemeral photo, video,
document, poll and paid-media sends. Silent in the literal sense: no crash, no
log, nothing to notice. If the text/media distinction actually matters, the
discriminator is `media == null`, which is what the two branches above
establish; but prefer not to classify user intent by request type at all,
since the reverse case bites equally -- a typed message with a resolved link
preview leaves as `TL_messages_sendMedia` carrying `TL_inputMediaWebPage`
(`SendMessagesHelper.java:5363-5380`), not as `TL_messages_sendMessage`.

This is a warning for a future classifier, not a description of existing code.
The fork's nearest thing to one, the Ghost Mode send warning
(`GhostSendWarningHelper.java`), deliberately does **not** classify: it
allowlists request classes as "this produces a message" and then resolves the
destination chat, and it draws no text/media distinction anywhere. That design
was chosen in review precisely because of this trap.

*(Established 2026-09-10, #ghost-type-warning -- found in design review, before
the mis-classification reached code.)*

## Ghost Hold: held rows ride the Scheduled-list bulk actions without a per-action guard

**Status (2026-09-11): resolved by an entrance guard — the present-tense
description in the next two paragraphs is the pre-fix behaviour, kept for the
reasoning. See "First fixed with a shared send boundary … then superseded by an
entrance guard" below for what actually ships now.**

A held Ghost Hold row is a real `TYPE_TEXT` `MessageObject` with a negative
local id, and it renders in the Scheduled list like any other row, so it is
selectable into multi-select -- `addToSelectedMessages`
(`ChatActivity.java:20877`) adds it to `selectedMessagesIds` and, because the
type is text, to `selectedMessagesCanCopyIds` too. That means a bulk action the
Scheduled action mode exposes acts on it unless either its own held guard or its
scheduled-mode visibility gate excludes it. Send-Now
(`confirmSendNowSelectedMessages`), the single-row context menu, and
edit-schedule-time each guard it individually; the reschedule spread did not
until it was excluded at the `resolveRescheduleItems` chokepoint
(`ChatActivity.java:37877`).

Getting the *reachable* set right matters, because two of the send-capable
overflow items are already hidden in scheduled mode and three others are not.
`nkbtn_savemessage` and plain `nkbtn_repeat` are added to the overflow at
`ChatActivity.java:11505-11506` but their visibility is set to `canForward`
(`ChatActivity.java:21051`, `:21054`), and `canForward` is
`chatMode != MODE_SCHEDULED && ...` (`ChatActivity.java:21036`) -- so both are
**hidden** on the Scheduled list and cannot act on a held row there. The three
that *are* reachable and unguarded, and would push held text to the server now,
are: `combine_message` (visibility gated only on copyable selection,
`ChatActivity.java:11531`; handler `:4333`), `nkbtn_repeatascopy` (visibility
`canSendMessage && (!noforwards || canSendMessagesAsCopy(...))` with no
scheduled gate, `ChatActivity.java:21057`; handler `:48257` ->
`doRepeatMessage` `:49107` -> `sendMessagesAsCopy`), and the scheduled
`forward` overflow item (added at `ChatActivity.java:11521` under
`getActionBarButtonForward()`, enabled via
`canSendMessagesAsCopy(getSelectedMessages1())` at
`ChatActivity.java:21151`). The lesson: guarding held rows action-by-action
is the wrong shape, because the held row is admitted to the *selection* upstream
of every action; the durable fix is to keep held rows out of the send-capable
selection at one boundary, not to chase each new action.

**First fixed with a shared send boundary (`naxExcludeHeldFromSend`,
`ChatActivity.java:37203`), then superseded by an entrance guard.** The boundary
filtered `isHeld` rows out of each send assembler, but that is exit-filtering and
it kept losing: it missed the reply/quote payload channel (the message-preview
repopulation that puts straight into `selectedMessagesIds`) and off-screen range
selection, which calls `addToSelectedMessages` directly and bypasses the
`getMessageType`/`processRowSelect` gate. The durable fix is to seal the
*entrance*: a held row never enters the selection model at all. But "the
selection model" is not one field -- it is every container that holds selected
message ids, and there is more than one door into that room. The main
multi-select model (`selectedMessagesIds`, plus the canCopy/canStar subsets that
ride with it) has two insertion points -- `addToSelectedMessages`
(`ChatActivity.java`) and the message-preview reply/quote repopulation -- and both
now return/skip on `isHeld`; `canSelect` refuses held rows too so the drag/range
route is clean before it ever reaches `addToSelectedMessages`. Separately, the
message **preview** keeps its own selected-id set
(`MessagePreviewParams.forwardMessages.selectedIds`) that `beforeMessageSend`
reads directly through `getSelectedMessages` -- independent of
`selectedMessagesIds` -- so that container is sealed at the single `showFieldPanel`
forward funnel every `showFieldPanelForForward` caller passes through, filtering
`messageObjectsToForward` through `naxExcludeHeldFromSend` before `updateForward`
builds it. The reply/link preview sets (`replyMessage.selectedIds`,
`linkMessage.selectedIds`) are single-target/webpage-derived and structurally
cannot hold a held row, so they need no seal -- listed only so the container set
is complete. With every entrance sealed, no selection-consuming route (forward,
quote, reply, copy, combine, repeat-as-copy, draft publication, range select) can
carry a held row, by construction rather than enumeration. The pre-existing
per-action `!isHeld` guards (Send Now, reschedule, edit-schedule-time) and
`naxExcludeHeldFromSend` at the assemblers are left as harmless defense-in-depth;
they are now redundant with the entrance guard.

Because a held row is no longer selectable, its only action -- **delete** -- comes
from the single-row context menu's cancel path (`getMessageType` returns
`MESSAGE_TYPE_INVALID` for the `id <= 0` out row, which populates the cancel item
when `isSending()`), routed through `cancelSendingMessage` -> `deleteMessages` ->
the fork `messagesDeleted` observer that removes the durable record. That delete
path depends on `isSending()`, which depends on `send_state = SENDING` -- see the
reload trap below.

**Reload trap: `send_state` is client-only and not in the serialized blob.** A
freshly held row carries `send_state = SENDING` (`GhostHoldController` sentinel
build), but the render injection decodes the stored blob and `send_state` is not
part of it, so a row shown on a later launch came back as `NONE`. `isSending()`
was then false, `getMessageType` classified it invalid, and the single-row
cancel/delete affordance was never populated -- so a held message could not be
deleted after an app restart until flush. Stock restores `send_state` from its own
column on the scheduled read (`MessagesStorage.java:9012-9014`); the render
injection now does the equivalent on the decoded display object before building
the `MessageObject`. Durable membership stays governed by `STATE_HELD`, never by
`send_state`.

*(Established 2026-09-10, `#ghost-hold`, during the ghost-hold-audit branch
superseding PR #336. The shared send boundary landed 2026-09-10 on the
ghost-hold-selection branch; superseded 2026-09-11 by the selection-model entrance
guard and the reload `send_state` restore on the ghost-hold-selectability branch,
`#ghost-hold`. Round-2 completion 2026-09-11, PR #347: the entrance guard was
extended to the preview's separate `forwardMessages.selectedIds` container after a
review found `beforeMessageSend` reads it directly, and the scheduled-count
publication was corrected to thread the initiating session token through its
callbacks rather than recapture `sessionEpoch` at publish time -- a callback that
began under an older session could otherwise sample the new epoch and pass the
recheck, posting a stale count into whoever now owns the reused account slot.)*

## Ghost Hold: legacy migration can resurrect a message deleted mid-migration (accepted, #346)

The one-time legacy migration (`GhostHoldController.migrateAccount`) collects
pre-fork-store held rows on the **storage** queue, then inserts them into the fork
store on the **fork** queue. The deletion path is the other half of the trap:
`MessagesController.deleteMessages` posts `messagesDeleted`
(`MessagesController.java:9545-9569`) immediately after enqueueing the stock
delete, and the fork `messagesDeleted` observer removes the matching fork record
on the fork queue. If a user deletes a legacy held row *during* migration, the
observer can run before the migration insert -- it finds no fork row yet, so its
removal no-ops and the delete signal is lost; the migration insert then re-adds
the collected blob and the row comes back. "I deleted it and it came back" is a
durable user-intent violation, but closing it race-free is storage-lifecycle work
the safety bundle scopes out: a bare existence check at insert time cannot
distinguish "not yet inserted" from "deleted" (both absent), the stock table is
storage-queue-owned and unreadable from the fork queue, and REPLACE-on-insert
resurrects even if the observer is ordered first. A correct fix needs a
migration-scoped deletion **tombstone** that survives to be rechecked at insert
time. Bounded and left documented rather than built: migration is one-time per
account on first upgrade (idempotent, `accountInited`-guarded), the window is
milliseconds on that first start, and the resurrected row stays a normal deletable
held row -- no leak, loss, or crash. Belongs to issue #346.

*(Established 2026-09-11, `#ghost-hold`, PR #347 round-2 review. Contested the
prescribed fork-queue recheck as not race-free without a tombstone; documented as
an accepted #346 limitation instead.)*

## Ghost Hold: logout purge cannot run on a doubly-broken teardown

`GhostHoldStore.deleteDatabaseFileOnQueue` (`GhostHoldStore.java:490` onward)
invalidates a logged-out account's held rows by purging them in place, then
unlinking the file, so a same-user relogin inherits nothing (`enforceOwner`
only purges on an owner *mismatch*, and a same-user reopen is not one). That
holds as long as *either* the in-place purge *or* the unlink succeeds. It does
not on the doubly-rare case where the DB is both unopenable (the on-demand
`db()` at `GhostHoldStore.java:502` throws, so the purge block is skipped) *and*
undeletable (the unlink then fails on a WAL/SHM lock or permission). On that one
path the owner stamp and rows survive on disk and a same-user relogin can reload
them. Closing it properly needs an out-of-band tombstone that forces a purge on
the next open regardless of owner -- storage-redesign territory the safety
bundle scopes out, and doubly rare on top. Accepted and documented rather than
fixed. (Separately, the held-count refresh in
`GhostModeActivity.refreshHeldCount` (`settings/GhostModeActivity.java:120`) is
an async `countHeld` callback fired from `onResume`; a stale count can flash for
one frame after a rapid resume -- below the severity floor, recorded, left as
is.)

*(Established 2026-09-10, `#ghost-hold`, ghost-hold-audit branch superseding
PR #336.)*

## Parent replacement and workflow-free sources are stricter than normal sync

The configured source and anchor topology is data, despite the legacy names:
`NAGRAM_REPO` / `NAGRAM_BRANCH` point to `DrKLO/Telegram` `master`,
`ANCHOR_SRC` names the Telegram commit, and `OLD_NBASE` names the locally
authored snapshot carrying its tree (`.github/sync/pins.env:14-31`). Parent
replacement is not a `sync-land` case: normal land validation requires the
source to descend from the already-pinned anchor
(`.github/sync/sync-guard.ps1:464-468`).

A source with no workflow tree requires `WORKFLOW_POLICY=none`, an exact
header-only manifest, and rejection of any workflow path. Treating an empty
manifest as a relaxed `manifest` policy would remove the guard precisely when
the source changes shape (`.github/sync/sync-guard.ps1:185-201`;
`.github/sync/workflow-manifest.tsv:1`).

The expected-red window is diagnostic, not a bypass: before the human moves
`nbase`, candidate pins name the new snapshot while live `origin/nbase` still
names the old one, and the live tree still contains workflows that policy
`none` forbids. The fixture compares the live ref to candidate `OLD_NBASE`
before constructing its real candidate
(`.github/workflows/sync-guard-check.yml:154-157`). The attended procedure and
its exact recovery ordering live only in `.github/sync/README.md`.

Moving `nbase` starts a separate `push` run but does not rerun or associate a
new `pull_request` check with an existing PR; the workflow has distinct `push`
and `pull_request` events (`.github/workflows/sync-guard-check.yml:23-25`).
Select the post-move PR verdict by event, exact head, workflow identity, and PR
association rather than treating an unrelated push run as evidence. The exact
guard context is `Guard self-test, wiring, and real fixture`
(`.github/workflows/sync-guard-check.yml:32`).

*(Established 2026-09-13, `#infra`; re-verify the cited lines before use.)*

## `squash_merge_commit_message: COMMIT_MESSAGES` plus an un-overridden squash message is what keeps `#slug` tags alive on `dev` — and no CI check guards either

The repo lands PRs by **squash merge** (`allow_merge_commit: false`, `allow_squash_merge: true`, 2026-09-10). A squash writes one new commit onto `dev` and discards the PR branch's commits — the very commits the tag job in `process-rules.yml` validated. So whether the `#<slug>` tag reaches `dev` at all rests on **two** things, not one: the `squash_merge_commit_message` setting **and** the squash message being left at its default rather than overridden at merge time. With `COMMIT_MESSAGES` (the current, correct value) GitHub builds the *default* squash body from every branch commit's message — **including each commit's subject line, rendered as a `* <subject>` bullet** — so a tag that lives only in a commit *subject* still lands in the squash body and survives (verified 2026-09-10). But that is only the default: flipping the setting to `PR_BODY` or `BLANK`, **or** overriding the body at merge time (the merge UI's editable message, or `gh pr merge --body`/`--subject` — see the merge-command note in `.claude/skills/nagramx-branch-flow/SKILL.md`), can drop the tags, so a later merge **can** land a tag-less commit on `dev`. **No CI check catches this** — the tag job runs against the PR branch, which was tagged; it never sees the squash GitHub writes afterward. The failure is silent and permanent in the `dev` log.

What the `dev` log actually shows (2026-09-10):

- **Only three of the last 25 merged PRs were squash-merged at all**: #338 (11 commits, tags **preserved**), #335 (12 commits, tags **preserved**), and #334 (10 commits, tags **lost**). Every other PR in that window landed as a **merge commit** and is tag-exempt, so the squash path is the only one that can silently drop a tag.
- **All three squashes were multi-commit, so commit count is not the discriminator.** #334 did **not** lose its tag for being "a single-commit PR whose body omitted it" — `refs/pull/334/head` has **10 commits, every one carrying `#docs` in its subject**. The squash nonetheless landed as `becfe09f63` with subject = the PR title (`Carry a stalled session's outstanding authorized work into its replacement (#334)`) and a **completely empty body**; its only hashtag is the bare PR number `#334`, which is explicitly not a `#<slug>` tag, so `git log --grep '#<slug>'` will never find it. #335's `599baff6ee` and #338's squash both kept their `#<slug>` tags in the concatenated body via `COMMIT_MESSAGES` — #335's subject `remind at typing time that ghost mode doesn't cover sending (#335)` is itself untagged, but the `* <subject>` bullets in its body still contain `#ghost-type-warning`.
- **Why #334's body came out empty is unresolved — two candidate causes, and the evidence does not decide between them.** Either (a) `squash_merge_commit_message` was **not yet** `COMMIT_MESSAGES` when #334 merged, or (b) the squash message was **hand-edited or cleared at merge time** — via the merge UI's editable message **or** a `gh pr merge --body`/`--subject` override — for that one PR. Merge times: #334 `02:46:55Z`, #335 `02:54:25Z`, #338 `03:26:17Z` — #334 landed its empty body **eight minutes before** #335 landed with its tags intact, which fits either a setting corrected in that window or a manual edit on #334 alone. **Do not pick one.** A future reader who "fixes" this by mandating the tag in the PR *body* would be chasing the wrong cause: subject-only tags already survive under `COMMIT_MESSAGES` today, per the `* <subject>` behaviour above.

Two consequences worth stating. First, the tag job passing on a PR is **not** evidence that what lands on `dev` is tagged — the tag survives the squash only when the setting is `COMMIT_MESSAGES` *and* the message is left un-overridden, which is why the *Land a change* preflight in `.claude/skills/nagramx-branch-flow/SKILL.md` stop-and-reports if `squash_merge_commit_message` is not `COMMIT_MESSAGES` and its merge command passes neither `--body` nor `--subject`. Second, `squash_merge_commit_title: PR_TITLE` makes the **PR title the permanent `dev` commit subject**, so a vague or AI-referencing title is a permanent defect in the log, not cosmetic.

The branch that produced the squash is auto-deleted (`delete_branch_on_merge: true`), but its full pre-squash range is **not** lost: `refs/pull/<N>/head` is permanent and survives the deletion. Verified — #335's branch `2026-09-10-ghost-type-warning` is gone from `origin`, yet `git fetch origin refs/pull/335/head` still returns tip `6465b2fda8` (12 commits, all tagged). That ref is the recovery path for a range you need after the branch is gone. Verified with `git show -s --format='%B' becfe09f63` / `599baff6ee`, the `refs/pull/334/head` and `refs/pull/335/head` commit counts (10 and 12), and the `refs/pull/335/head` fetch on `origin` (2026-09-10).

*(Established 2026-09-10, #docs.)*

## Stock deletion notifications carry no producer-session identity

A fork observer that keys off `sessionEpoch` (or the store generation) at the
moment a stock `messagesDeleted` notification is *delivered* is pinning the
wrong session whenever that delivery is delayed across a logout. Stock's
deletion notification carries only the deleted ids, no token identifying which
session produced the delete, so there is nothing at delivery time that can tell
"this delete belongs to the user who is now logged in" apart from "this delete
belongs to the user who logged out while it was in flight."

Where it bites in this fork: `GhostHoldObserver.didReceivedNotification`
handles `messagesDeleted` (`GhostHoldController.java:1937`) and captures its
session at delivery -- `final int deleteSession = sessionEpoch.get(account)`
(`GhostHoldController.java:1963`) -- then hops to the fork queue via
`store.runOwned(...)`, which pins the store generation at post time
(`:1965`, `GhostHoldStore.java:113-137`). `appDidLogout` bumps `sessionEpoch`
synchronously (`GhostHoldController.java:1918-1927`) and enqueues the store
teardown that advances the generation. Both guards therefore read *delivery*-
time state. A stock `messagesDeleted` for a negative (local) id that is posted
before logout but delivered after a full logout + teardown + relogin (stock can
defer a post to the UI-thread handler) is delivered post-relogin, so both
guards sample the *new* session and pass. `UserConfig.clearConfig()` resets
`lastSendMessageId`, so the reused slot mints the same negative ids, and
`selectOnQueue(mid)` (`GhostHoldStore.java:441`) can match a genuine held row
of the new user -- which `deleteManyOnQueue` (`GhostHoldController.java:1978`)
then deletes, and `postScheduledCount(account, d, deleteSession)` (`:1980`)
publishes against.

Consequence: the new session's held row is deleted. This is the one member of
the logout/relogin teardown-race family that fails **unsafe** -- it destroys a
row rather than the family's usual safe direction of leaving rows held (nothing
sent, nothing leaked). Bound: a very narrow window (a deletion post delayed
across an entire logout/relogin), and the loss is a local held draft only --
never a message that was sent, and nothing leaks to the server or to the wrong
account's transport.

Why no local fix exists -- three refutations, so this is not re-litigated:
1. A **delivery-time recheck** of `sessionEpoch` reads only the new session
   (session B); it is "too late" because delivery already happened under B, so
   the recheck passes.
2. **Recording the owning user on the row and comparing** does not discriminate
   either: the row `selectOnQueue` finds genuinely belongs to session B (the new
   user reused the same negative id), so an owner check confirms rather than
   rejects the delete.
3. A **fork-owned monotonic id space** to avoid the collision would risk
   colliding with stock's own negative ids for real unsent messages.
   The only sound fix is a producer-time session/generation stamp carried from
   stock `MessagesController` / `MessagesStorage` into the notification -- a
   cross-file storage-lifecycle change, i.e. the same observable "store ready /
   producer token" primitive #346 already scopes out. Not patchable at the
   observer.

Filed as #349, scoped alongside the #346 "store ready" work; part of the
logout/session-teardown-race family with #343 and #346.

*(Established 2026-09-11, #ghost-hold.)*

## The MODE_SCHEDULED sort collapses to input order for held rows — and its id tie-break is inverted for local ids

The stock scheduled-list sort in `MessagesController.processLoadedMessages` — the
upstream original that the `#ghost-hold` load override (below, `:12401-12415`)
replaced — was:

```java
if (o1.messageOwner.date == o2.messageOwner.date && o1.getId() >= 0 && o2.getId() >= 0) return o2.getId() - o1.getId();
return o2.messageOwner.date - o1.messageOwner.date;
```

Two traps live here for Ghost Hold's held rows. Any group of held rows that
**share an identical date** trips them, because held rows carry negative local
ids. That covers three cases: the **plain-hold sentinel** `GHOST_HELD_DATE_SENTINEL`
= `0x7FFFFFFD` (`GhostHoldController.java:76`, applied at `:532`); the
**send-when-online sentinel** `0x7FFFFFFE`, which `persistHeld` stores verbatim
(`GhostHoldController.java:532`) for a held "send when online" and which is likewise
a shared literal, not a real timestamp; and **two timed holds that fall in the same
wall-clock second**. A held row whose date is genuinely distinct from every other
row's re-sorts to its own date position and neither trap touches it — the traps are
about the *tie*, not about any one sentinel value.

1. **The tie-break never fires between same-date held rows.** The `getId() >= 0` guard
   excludes negative local ids, so for any two held rows on the same date the comparator
   falls
   through to `o2.date - o1.date`, which is `0` — they tie. `Collections.sort`
   is a documented-stable sort, so the on-screen order of each tied run is
   decided entirely by the order they were fed in, i.e. the append order in
   `GhostHoldController.injectHeldScheduled`. The fork no longer leaves that to
   stable-sort accident: the `#ghost-hold` load fix **overrides this comparator**
   (`MessagesController.java:12401-12415`, using an immutable `HeldOrderView`
   snapshot rank built once at `:12400` — never per comparison, which would make
   the sort O(n²log n)) so a tie that *involves* a held row is resolved by
   flush-snapshot rank instead of append order, and an already-present held member
   re-shown on reload (e.g. an `0x7FFFFFFE` "until online" hold) is ranked too
   rather than left to a stable-sort accident. The stock trap still bites anyone
   who removes that override — an upstream bump that made the comparator start
   tie-breaking held rows would silently revert the load ordering with no compile
   error and no symptom visible without a device — so the override, not the stable
   sort, is now what makes the load order deterministic.

2. **Dropping the `getId() >= 0` guard does NOT fix it — it inverts it.** Local
   send ids *decrement* (`UserConfig.getNewMessageId`), so a later hold has a more
   negative id. `o2.getId() - o1.getId()` is descending id, which for decrementing
   ids puts the **oldest** hold first (index 0 = screen bottom) — reproducing the
   exact reversed order, now baked into one of the hottest base files. The
   tempting one-line comparator "fix" is wrong, not merely fragile.

## MessagesController's load sort and ChatActivity.processNewMessages are two independent orderings of the same scheduled list

A held row reaches the Scheduled list two different ways, ordered by two different
pieces of code — a fix to one does nothing to the other:

- **Load path:** open the Scheduled list → `MessagesController.processLoadedMessages`
  calls `GhostHoldController.injectHeldScheduled` (`MessagesController.java:12385`)
  then the fork-overridden scheduled comparator (`:12401-12415`).
- **Live path:** a successful hold publishes the row via
  `updateInterfaceWithMessages(peer, obj, 1)` (`GhostHoldController.java:667`) →
  `didReceiveNewMessages` → `ChatActivity.processNewMessages`'s own placement loop
  (`ChatActivity.java:27981-28112`). That loop breaks on `lastMessage.date < obj.date`
  or `date ==` with both ids `> 0`; for same-date-tied negative-id held rows neither
  branch can fire *between held siblings*, so the loop cannot order a live-arriving
  hold against the existing held rows — it stops at the first lower-dated row (a real
  scheduled row or date header) or falls through to `messages.size()`, and either way
  the block ends up newest-first unless a hook forces otherwise.

Both mechanisms produce the identical reversed block by different means, so any
change to held-row display order must account for both. The `#ghost-hold`
scheduled-order fix routes both through **one ordering contract** — a held row's
rank is its position in the flush snapshot (`cachedForDialog()`, oldest-first), and
because the list is reverse-stacked a higher rank (newer hold) must land at a lower
array index (screen bottom = oldest-at-top). Rank is exposed by an immutable
`GhostHoldController.HeldOrderView` (`GhostHoldController.java:1653`, built by
`heldOrderView`, `:1675`), applied per tying-date group so the plain (`0x7FFFFFFD`)
and online (`0x7FFFFFFE`) buckets order independently and a genuinely-distinct-dated
row is left to the stock date sort. The two adapters differ only in substrate:
- **Load** (`GhostHoldController.injectHeldScheduled`, `:1500`) appends the whole
  snapshot to the pre-sort `objects` list newest-first (descending rank) and lets the
  overridden comparator settle the ties (see the override above) — held sorts above a
  genuine same-date row.
- **Live** (`GhostHoldController.placeLiveHeldRow`, declared `:1731`, called once from
  `ChatActivity.java:28122`) computes the arriving row's insertion index directly from
  its rank relative to the rows already on screen: below any older held sibling, above
  any newer held sibling, and on the message side of the day header. It self-gates on
  `chatMode == MODE_SCHEDULED` (defence in depth behind the stock scheduled filter; see
  the MODE_SAVED trap below) and on the row being a held member of the snapshot (a
  missing member fails closed to stock placement, never to comparing ids). It orders
  held rows only; it does **not** move a genuine same-date row, so a genuine same-date
  row can render on a different side of the held block live vs cold — an accepted
  residual, documented below.

*(Established 2026-09-11, #ghost-hold. Generalised 2026-09-12 from the plain
sentinel to any tied-date held group; live path rebuilt onto the shared
HeldOrderView oracle the same day.)*

## The Scheduled "until online" date header carries different dates on the load and live paths — match it by dateKey, never by numeric date

The Scheduled list builds its per-day date header in two places, and they disagree
on the header's `date` for a send-when-online day:

- **Load path** (`ChatActivity.java:23241-23262`): for `MODE_SCHEDULED` with
  `obj.messageOwner.date == 0x7ffffffe` it stamps the header `dateMsg.date = 0x7ffffffe`
  (`:23253`); otherwise it computes Calendar midnight of the row's day.
- **Live path** (`ChatActivity.java:28210-28227`): it sets the "until online" *label*
  for `0x7ffffffe` (`:28212`) but has **no** matching date branch — it unconditionally
  runs the Calendar midnight computation (`:28227`).

So the same "until online" header carries `0x7ffffffe` when cold-loaded and a midnight
timestamp when created live. Any code that pairs a message with its header by
`header.messageOwner.date` will therefore pass on cold load and silently fail live.
Match the header structurally instead — `isDateObject` AND NOT `isVideoConversionObject`
(a video-conversion "processing" row also sets `isDateObject`; see the `isDateObject` entry
below for why it must be excluded here) plus the day-granular `dateKey`
(`MessageObject.java:1964`, computed from `Calendar.DAY_OF_YEAR`, so `0x7FFFFFFD`,
`0x7FFFFFFE` and that day's midnight all share one `dateKey`). `placeLiveHeldRow`
does exactly this to keep a held row on the message side of its header.

The asymmetry also feeds the live insertion loop's id tie-break (next entry): a date
header is a `MessageObject` with id `0`, which fails that loop's `> 0` test, so a
same-date row can *skip the header itself*. How far it skips differs by which build
path made the header — a load-built "until online" header carries `0x7ffffffe` and
ties the row's date (the row skips it and travels further up), while a live-built one
carries midnight (lower, so the row stops just past it). So header-relative live
placement of a genuine row is not even uniform across cold-load vs live-arrival.

*(Established 2026-09-12, #ghost-hold.)*

## The live Scheduled insertion loop is blind to negative-id rows — an accepted genuine-vs-held residual in the online bucket

The stock live insertion loop in `ChatActivity.processNewMessages`
(`ChatActivity.java:28090`) only tie-breaks two equal-date rows when **both** carry
ids `> 0`. Ghost Hold's held rows carry negative local ids, so they are invisible to
that tie-break — the live-path twin of the load comparator trap at the top of this
file. This has a consequence the `#ghost-hold` fix deliberately does **not** remove:

A genuine same-date row the user scheduled — most obviously a "send when online"
message on date `0x7FFFFFFE` — meets the same-date held run and satisfies **neither**
the loop's id tie-break **nor** a lower-date break (the held rows share its date), so
it skips the entire held run and — via the header id-`0` skip above — can skip the day
header too, landing **above** the held block. This is **not** limited to positive-id
genuine rows: `SendMessagesHelper` assigns a fresh local **negative** id before
applying `scheduleDate`, and a user-scheduled local media or unsupported-text send is a
genuine row that keeps that negative id, so it too fails the loop's `> 0` tie-break and
hits the same split. (A positive-id genuine row finds the held rows' ids negative; a
negative-id genuine row fails the `> 0` test itself. rankOf still returns -1 for it —
it is not a held mid — so `placeLiveHeldRow` leaves it in stock placement either way.)
The cold-load override (`MessagesController.java:12401`) instead sorts a genuine
same-date row **below** the held run. So one genuine send-when-online row can sit on
**opposite sides** of the held block live vs cold.

The live adapter (`placeLiveHeldRow`) places held rows only and must not move a
user-scheduled row, so this divergence is **accepted**, not fixed: reaching parity
would require moving a genuine row the user scheduled, which is out of scope. It is
cosmetic — no send-order, data, or crash impact — affects only the `0x7FFFFFFE` and
same-second timed-hold buckets (the only ones that can share a date with a genuine
row), and **self-corrects on the next reload**, which runs the deterministic
override.

Crucially, the **plain-hold bucket** (`0x7FFFFFFD`) has **no** genuine counterpart —
a user cannot schedule a message onto the plain-hold sentinel date — so its held run
is correct on **both** paths. That is the bug dazewell reported (holding 1, 2, 3
rendered 3, 2, 1) and it is **fully fixed**; the residual above is a different,
pre-existing, rarely-hit case, not the reported bug.

*(Established 2026-09-12, #ghost-hold.)*

## `isDateObject` does not mean "day header" — a video-conversion row wears the same flag and can share an online-held row's exact date

Header-detection that keys only on `isDateObject` is a trap, because in the Scheduled
`messages` list that flag has **two** producers, not one. Enumerating every
`isDateObject = true` assignment that can reach this list:

- **Day header, load path** — `ChatActivity.java:23266` (`isDateObject = true`; the
  paired `type = TYPE_DATE` is at `:23264`).
- **Day header, live path** — `ChatActivity.java:28231` (`isDateObject = true`; the
  paired `type = TYPE_DATE` is at `:28229`).
- **Video-conversion "processing" row** — `ChatActivity.java:23384`, which also sets
  `isVideoConversionObject = true` and, for a `video_processing_pending` send-when-online
  video, `dateMsg.date = 0x7FFFFFFE` (`:23370-23371`) — the **exact date and `dateKey`**
  of an online-bucket held row. It is a content-side marker, not a boundary.

The other `isDateObject` producers never reach a Scheduled list: the discussion-thread
header (`ChatActivity.java:22966`) is thread-mode only, the filtered-search header
(`:10368`) targets `chatAdapter.filteredMessages`, the channel-admin-log header
(`MessageObject.java:2092`) is the admin-log screen, and `SharedMediaLayout.java:9239`
is the shared-media grid. So **`isVideoConversionObject` is the sole non-day-header
`isDateObject` producer in this list** — excluding it is the complete rule, not a
patch over one instance.

Consequence for anything positioning a row against "the header": a walk that treats
every `isDateObject` row as the boundary will, for a send-when-online video, both miss
the conversion row in a same-date content scan and — if it keeps the last matching
`dateKey` — anchor a boundary clamp to the conversion row instead of the real header,
which can push a row somewhere neither cold nor live would put it. `placeLiveHeldRow`
guards with `isDateObject && !isVideoConversionObject` so only a true day boundary is a
boundary; the conversion row falls through as an ordinary (id-`0`, `rankOf` -1)
below-row, matching where cold load leaves it (beside its video, below the held run).
The load comparator is unaffected: date headers and conversion rows are added by
`ChatActivity` *after* `MessagesController.java:12385` runs, so the comparator never
walks them.

*(Established 2026-09-12, #ghost-hold.)*

## Ghost Hold: `updateStateOnQueue` re-puts an existing key so its `master` position survives a state change, while `insertOnQueue` remove-then-puts so it appends

`GhostHoldStore.master` is a `LinkedHashMap<Integer, HeldRecord>`
(`GhostHoldStore.java:78`), and its **iteration order is the one source of both orderings
that matter** for Ghost Hold: the flush/send order (`selectByStateOnQueue` iterates
`master.values()`, `GhostHoldStore.java:424-428`) and the Scheduled-list rank (`publish()`
rebuilds the per-dialog snapshot by iterating `master.values()`,
`GhostHoldStore.java:307-317`, which `cachedForDialog` hands to `heldOrderView` — rank is
snapshot position). So anything that changes `master`'s iteration order silently changes
both what the user sees and what order the messages send in.

The two mutators touch that order differently, and the difference is load-bearing:

- `insertOnQueue` (`:320`) does `master.remove(rec.mid)` **then** `master.put(rec.mid, rec)`
  (`:339-340`). On a `LinkedHashMap` a remove-then-put moves the key to the **end** of
  iteration order — a new hold appends, which is what makes a freshly held message the
  newest (highest rank).
- `updateStateOnQueue` (`:350`) does a bare `master.put(mid, old.withState(newState))`
  (`:357`) with **no** preceding remove. A `LinkedHashMap` in its default (insertion-order,
  not access-order) mode does **not** reorder on a re-put of an existing key, so the record
  keeps its position.

Why it matters here: a HELD→FLUSHING→HELD round trip (flush claims a row, then reverts it —
`revertToHeld` / `completeHandoff` / startup reconcile) goes through `updateStateOnQueue`
both ways, so the reverted row lands back in its **original** flush position, not at the end.
Had the revert gone through an insert-style remove-then-put, a row that briefly flipped to
FLUSHING and back would jump to newest and both invert its send order and reorder its
Scheduled-list rank. The live-adapter fix that classifies a rankless (transiently-FLUSHING)
held row as an older sibling (`GhostHoldController.placeLiveHeldRow`) leans on this: it is
only safe to assume such a row is older because its position — and therefore its rank once it
reverts — is preserved across the state flip. If a future change ever routes a state update
through a remove-then-put, or flips `master` to access-order, that assumption breaks silently.

*(Established 2026-09-12, #ghost-hold.)*

`ChatActivity.didReceiveNewMessages` (`ChatActivity.java:24096-24115`) guards the
mode-mismatch case with:

```java
if (mode != chatMode && chatMode != MODE_SAVED && chatMode != MODE_SUGGESTIONS) {
    ...
    return;
}
...
processNewMessages(arr);
```

The `return` is **skipped when `chatMode == MODE_SAVED`** (or `MODE_SUGGESTIONS`),
so an event published with `mode == MODE_SCHEDULED` (`1`) still falls through to
`processNewMessages` while the open fragment is the Saved-messages timeline. Ghost
Hold publishes a successful hold via `updateInterfaceWithMessages(peer, obj, 1)`
(`GhostHoldController.java:667`), and a hold sent from Saved Messages is holdable, so
its live row does reach `processNewMessages` there. But the real boundary that keeps a
Saved-Messages hold out of the Scheduled placement is one level lower: the per-message
scheduled filter at `ChatActivity.java:27958`
(`if (obj.scheduled != (chatMode == MODE_SCHEDULED)) continue;`) drops a held row —
published with `scheduled = true` (`GhostHoldController.java:664`) — on any
non-Scheduled timeline **before** the `#ghost-hold` `placeLiveHeldRow` hook (called at
`ChatActivity.java:28122`, downstream of that filter) is reached. So a held row cannot
in fact arrive at that hook in `MODE_SAVED` today. The hook still gates on
`chatMode == MODE_SCHEDULED`, but as **defence in depth**, not as the boundary: it
guards a *future* hook added inside `processNewMessages` on a path that keys only on
the message (a held row, a sentinel date) and bypasses the `:27958` filter — that one
*would* fire on the ordinary Saved timeline. The trap for the next person is that
reaching `processNewMessages` in `MODE_SAVED` is real, but reaching this PR's hook with
a held row is not; do not conflate the two.

*(Established 2026-09-12, #ghost-hold.)*

## `headerItem` is null in `MODE_SCHEDULED`, and creating one switches on unrelated menu behaviour

A Scheduled list has no overflow menu to add a row to. `headerItem` is assigned
in exactly two places, gated on
`((chatMode == 0 && (threadMessageId == 0 || isTopic)) || chatMode == MODE_SUGGESTIONS)`
(`ChatActivity.java:5017-5022`) and on `chatMode == MODE_EDIT_BUSINESS_LINK`
(`ChatActivity.java:5280`). Neither admits `MODE_SCHEDULED`, so in a Scheduled
list the field stays null and that screen's action bar carries only the back
arrow and title.

The trap is the obvious workaround. Relaxing the gate at `:5017` so a Scheduled
list gets a `headerItem` does not just add an empty menu — several unrelated
base-file features treat `headerItem != null` as "this screen has an overflow
menu" and start populating it. `checkLeaveChannelButton()` guards only on
`headerItem == null || chatMode == MODE_SAVED` (`ChatActivity.java:49273-49274`)
and is reached from the `chatInfoDidLoad` handler at `ChatActivity.java:24808`,
which is not chatMode-gated — so a "Leave channel" / "Delete and exit" row
appears in the Scheduled screen's overflow. `updateBotButtons()`
(`ChatActivity.java:21555`) has the same shape, as does the `BuildConfig.DEBUG`
"Dump Canvas" item.

So a fork action that belongs on the Scheduled list needs its own fork-owned
`ActionBarMenuItem` field created in a `MODE_SCHEDULED` branch of the menu
block — never an assignment to the base `headerItem` field. That is also the
smaller footprint of the two.

Note which existing fork pattern to copy, because the two are easy to confuse.
The `nkheaderbtn_*` ids are **not** the precedent: they are
`headerItem.lazilyAddSubItem(...)` calls that hang off the shared base field
(`ChatActivity.java:457-498` for the ids, `:5182-5239` for the calls), so they
are the very pattern this entry warns against and they exist only on screens
that already have a `headerItem`. The precedent is `nkactionbarbtn_*`
(`ChatActivity.java:453-455`, `:492-493`), which are fork-owned ids added
straight to a menu with `addItemWithWidth` (`:11475-11509`) and dispatched from
the fork's own `onItemClick` branch (`:4412-4414`) — including
`nkactionbarbtn_send_now`, which already lives on the Scheduled list's
selection action mode.

*(Established 2026-09-12 while designing #332, which was closed won't-do before
any code was written. The trap is a property of `dev`, not of that change.)*

## `onDoubleTap`'s top-level guard relied on a stale schedule-mode check `hasDoubleTap` had already made redundant per-action

`ChatActivity`'s double-tap dispatch is split across two callbacks on the same
anonymous `RecyclerListView.OnItemClickListenerExtended`
(`ChatActivity.java:2041`): `hasDoubleTap` decides whether an action is armed,
`onDoubleTap` dispatches it. `RecyclerListView` only invokes `onDoubleTap` for
a view/position from inside the `if` whose condition is
`hasDoubleTap(view, position)` on that same view/position, immediately before
(`RecyclerListView.java:1108-1120`) — there is exactly one call site and no
other invoker, so `onDoubleTap` can never run for an action `hasDoubleTap` did
not just approve for that exact message.

`hasDoubleTap` already excludes schedule mode per action where it must:
reactions check `!isInScheduleMode()` (`ChatActivity.java:2128`); REPLY,
SAVE, REPEAT and REPEAT_AS_COPY check `chatMode != MODE_SCHEDULED`, either
directly or via the `allowChatActions` local it reads
(`ChatActivity.java:2136` for `allowChatActions`, `:2166-2177` for the four
`case` branches that use it or check it inline). EDIT and DELETE correctly
have no schedule exclusion there, but by two different mechanisms, not one:
`allowEdit` forwards the message's own `scheduled` field through
`canEditMessage(currentChat)` -> `canEditMessage(..., scheduled)`
(`ChatActivity.java:2142`, `MessageObject.java:11714-11715`), while
`allowDelete` instead passes an externally-computed `chatMode == MODE_SCHEDULED`
straight into `canDeleteMessage(inScheduleMode, chat)`
(`ChatActivity.java:2156`, `MessageObject.java:11859`) — the message's own
`scheduled` flag never enters that call.

Despite that, `onDoubleTap`'s own top-level guard carried a second,
independent `isInScheduleMode()` check that blocked EDIT and DELETE too, even
though `hasDoubleTap` had just armed them. This guard is not a 2025 addition —
it dates back to the original reactions-only double-tap feature
(`9e740dfd4d`, 2021-12-30), when `onDoubleTap` had no dispatch switch at all
and reactions were the only action it could take. EDIT, DELETE and TRANSLATE
were added as dispatch `case`s over the following years without anyone
revisiting that original top-level guard to reconcile it with their
finer-grained needs. The 2025-01-28 merge-cleanup commit `aba0a372d8e6` some
investigations attribute this widening to did no such thing — diffing it
shows its parent already carried the identical blanket guard on the
multi-action dispatcher; that commit only added a `!(view instanceof
ChatMessageCell) ||` check and removed an already-redundant nested
reactions-only guard one level down. Don't cite it as the drift point. The
trap is that a guard clause can look redundant-but-harmless and actually be
silently suppressing an already-approved action, and that its actual age is
easy to misjudge from a superficially-relevant nearby commit.

The fix (`#doubletap-schedule-fix`) removes the redundant top-level check and
relies solely on `hasDoubleTap` as the single source of truth for schedule-mode
gating. TRANSLATE/TRANSLATE_LLM are the one exception: `hasDoubleTap` has no
schedule check on that branch either, but the long-press menu's single-message
options list never offers translate in schedule mode — its whole non-forward
options block, translate included, is wrapped in `if (chatMode !=
MODE_SCHEDULED)` opened at `ChatActivity.java:51346` and closed at `:51441`
(`nkbtn_translate`/`nkbtn_translate_llm` added at `:51426`/`:51432`) — so those
two `onDoubleTap` cases carry their own explicit `isInScheduleMode()` check
instead of relying on `hasDoubleTap`. (The same menu-building method has a
second, structurally similar `nkbtn_translate` block around `:51656`, but that
one sits in the secret-chat branch, not the schedule-mode question this trap
is about — don't conflate the two if re-reading this area.)

*(Established 2026-09-14/15, `#doubletap-schedule-fix`; provenance corrected
2026-09-15 after architect review disproved the original 2025-01-27/28
widening claim against git history.)*

## A same-dialog `ChatActivity` subview auto-opens after any scheduled-mode send, and its own `onFragmentDestroy` looks like leaving the chat

Sending any message with a non-zero schedule date -- an explicit Schedule
pick, or the Send-When-Online sentinel `0x7FFFFFFE` -- makes the
`didReceiveNewMessages` handler auto-open a **new** `ChatActivity` fragment
for the **same dialog** in `MODE_SCHEDULED`, via `openScheduledMessages(...)`,
whenever the current fragment isn't already in that mode
(`ChatActivity.java:24123-24137` -> `:14481-14513`). The parent `MODE_DEFAULT`
fragment stays on the back stack underneath it
(`presentFragment(fragment, false)` at `:14513`).

The trap: that auto-opened subview has the *same* `getDialogId()` as the
chat the user is actually still in. When the user backs out of it, its own
`onFragmentDestroy()` fires -- and any fork hook there that clears
per-dialog state on "leaving the chat" fires too, even though the user never
left. `#remember-send-action`'s reset-on-leave hook did exactly this: it
disarmed the just-armed Schedule/SendWhenOnline action because the destroy
hook lacked the `chatMode == MODE_DEFAULT` guard its sibling
`onBecomeFullyVisible()` already carried (`:29369-29381`). The destroy hook
is now guarded the same way, at `:3822-3824` (commit `272a7a6d0f` added the
guard to what was previously an unconditional call at that same location).
Silent send never produces a `MODE_SCHEDULED`
message, so it never triggered the auto-navigation and was unaffected --
which is what made the symptom look schedule/send-when-online-specific
rather than a general destroy-hook gap.

Any `ChatActivity.onFragmentDestroy()` hook that means "the user left this
chat" must gate on `chatMode == MODE_DEFAULT`, not just fire on every destroy
-- a same-dialog subview (Scheduled Messages, Pinned, Search, Suggestions)
being destroyed is not the user leaving. The manual header-menu route into
Scheduled Messages hits the identical parent-below-child stack shape, so the
guard covers both the auto-navigated and manually-opened cases with one
condition.

*(Established 2026-09-17, `#remember-send-action`.)*

## `Theme.createSelectorDrawable`'s press circle is a fixed dp radius that ignores the view it is on

On API 23+ (`minSdk` is 27, so always) the `RIPPLE_MASK_CIRCLE_20DP` branch
hands the `RippleDrawable` a **null mask** and then calls
`setRadius(radius <= 0 ? dp(20) : radius)`
(`Theme.java:5039-5040`, `:5102-5107`). Null mask means unbounded: the press
circle is not clipped to the view, and its radius is whatever raw dp was passed.
It does not scale with the view, so shrinking or growing a button changes only
how much empty space sits between the circle and the cell edge.

That is how the composer panel ended up with two circle sizes that both looked
wrong. Upstream buttons take the one-argument overload
(`Theme.java:5022-5024`), so 20dp -> a 40dp circle; the fork's own toolbar
buttons passed `dp(16)` -> a 32dp circle. With the panel's cell at 41dp the
32dp circles sat 9dp apart and the gap widened further at larger toolbar sizes,
because the cell scaled and the radius did not.

The fix direction, if this comes up again: derive the radius from the geometry
rather than typing a dp. `ComposerToolbarLayout.panelSelector(int)` sizes it as
`minCellDp(scale()) / 2`, so the circle is exactly the tightest cell the row can
pack to.

*(Established 2026-09-18, `#composer-spacing`.)*

## `SlideIntChooseView.setMinValueAllowed`'s unreachable band is indistinguishable from the fill

`setMinValueAllowed(v)` forwards `getProgress(v)` to
`SeekBarView.setMinProgress` (`SlideIntChooseView.java:223-231`), and
`SeekBarView` then splits the track into a segment from `minProgress` to the
thumb and a segment from the left edge to `minProgress`
(`SeekBarView.java:529-533`). On a dark theme the second segment reads as
ordinary fill, not as a disabled range.

So when the thumb is already sitting on the floor, the whole left stretch is
painted and the thumb simply refuses to move any further left. There is nothing
on screen that says why, and the slider looks jammed. It is worse when the
left-endpoint label is substituted with the floor rather than the array's first
anchor, because the label then claims a value the thumb cannot reach.

Prefer building the anchor array from the live floor so the array's own minimum
*is* the floor and the reachable range fills the track — see
`ComposerLayoutActivity.spacingSteps()`. Keep at least two anchors: a
one-element array sends `getProgress` down its no-interval fallback
(`SlideIntChooseView.java:196`) and divides by a zero range.

*(Established 2026-09-18, `#composer-spacing`.)*

## `BlurredBackgroundDrawable`'s four-arg `setRadius` leaves the glass shader square

There are two radius arrays. `radii` shapes the clip path and the outline;
`shaderRadii` is fed to the liquid-glass `RuntimeShader`'s `radius` uniform
(`BlurredBackgroundDrawableRenderNode.java:122` -> `LiquidGlassEffect.java:93`),
which is what rounds the refracted edge on API 33+ when
`LiteMode.FLAG_LIQUID_GLASS` is on (`ChatActivity.java:3008-3016`). The one-arg
`setRadius(float)` writes both (`BlurredBackgroundDrawable.java:102-109`). The
four-arg `setRadius(tl, tr, br, bl)` writes only `radii`
(`BlurredBackgroundDrawable.java:111-120`) -- it neither raises `shaderRadii`
nor clears a value another overload left there. The five-arg overload just below
it does write both, which is what makes the four-arg look like an oversight
rather than a choice.

So a caller that reaches for the four-arg overload alone leaves `shaderRadii` at
the `new float[8]` zeros it was constructed with. Upstream's forum pill is such
a caller (`ActionBar.java:239`), so a forum header draws a square refraction
edge under a rounded clip. It is subtle, and invisible below API 33 or with
liquid glass off, which is why it has survived.

`#title-pill-fix` deliberately did **not** repair that. It writes `shaderRadii`
on the centered forum pill only, through the one-arg overload, because there the
pill is supposed to match a non-forum chat exactly; and since the four-arg write
cannot clear it again, it calls `setRadius(0)` on the way back out so the
non-centered pill stays byte-identical to upstream
(`ActionBar.updateGlassForumRadius`, `ActionBar.java:287-307`). Fixing the gap
properly means fixing the four-arg overload, which changes every caller of it at
once and wants its own branch and its own device build.

*(Established 2026-09-19, `#title-pill-fix`.)*

## "Immediately" auto-lock outranks the returning-intent grace period

`AndroidUtilities.needShowPasscode` tests `SharedConfig.autoLockIn == 1` as its
own OR arm, **before** the arm that requires `lastPauseTime != 0`
(`AndroidUtilities.java:3482-3483`). `LaunchActivity.onActivityResult` zeroes
`lastPauseTime` on any returning activity result
(`LaunchActivity.java:6785-6790`), which is upstream's grace period for a camera
intent, a share sheet or a file picker. So every timed value -- including the
fork's 5/10/15/30 second ones -- skips the lock when you come back from one of
those, while "Immediately" locks anyway. The short values are therefore
*better*-behaved in intent flows than "Immediately", not worse; what they lock
on is a plain home/recents excursion.

`lastPauseTime` and the comparison clock are both
`(int) (SystemClock.elapsedRealtime() / 1000)` (`LaunchActivity.java:8333`,
`AndroidUtilities.java:3477`), so sub-minute timeouts are exact to the second;
the scheduled lock runnable pads by 1000ms
(`LaunchActivity.java:8369`), which covers the truncation.

Nothing validates `autoLockIn` on load (`SharedConfig.java:559`), so an
unsupported value can reach the picker from a hand-edited settings backup.
Upstream left the wheel at position 0 in that case and wrote `0` ("Disabled")
back out on confirm; `AutoLockHelper.index` falls back to the 1-hour default
instead.

*(Established 2026-09-20, `#passcode-autolock-seconds`.)*

## A second manifest-enabled `<activity-alias>` gives existing users two launcher entries

Confirmed on-device 2026-09-20 on DwellGramX: after an update that shipped a new
launcher alias with `android:enabled="true"`, the launcher showed **two** entries
for the app, the previously chosen icon and the new one.

The launcher reads component enabled state when the package is installed, long
before any app code runs, so nothing in `ApplicationLoader.onCreate` can prevent
it. Nor does anything there clean it up afterwards: the repair in
`LauncherIconController.tryFixLauncherIconIfNeeded`
(`LauncherIconController.java:31-70`, called at `ApplicationLoader.java:401`)
counts a `DEFAULT` component as live only for `MANIFEST_DEFAULT`, so the new
alias is invisible to it, the count stays at one and no `setIcon()` runs. Both
halves are deliberate — see the comment on that branch. Counting it correctly
would only collapse the duplicate after the user next opens the app, which is
already too late to be worth relying on, so the invariant below is the fix
rather than a smarter counter.

The state that produces it: `setIcon` writes explicit `ENABLED`/`DISABLED` for
every entry in `LauncherIcon` **as it exists at the time it runs**
(`LauncherIconController.java:78-85`). An alias added in a later version was not
in that loop, so it stays at `COMPONENT_ENABLED_STATE_DEFAULT` forever, and
`DEFAULT` means *whatever the manifest says*. Give that new alias
`android:enabled="true"` and every user who had ever picked an icon now has two
live components: their explicit one plus the new default. Users who never picked
are unaffected, which is what makes this easy to miss in testing — a fresh
install looks correct.

So a per-variant or per-version default cannot be expressed by flipping which
alias the manifest enables. Keep exactly one alias enabled in the manifest,
permanently, and apply any preferred default from code, gated on *every* alias
still being at `DEFAULT` — that condition is what distinguishes "never chose"
from "chose this one", and it is the only safe trigger for overriding.

*(Established 2026-09-20, `#ribbon-icons`.)*

## The round-video scrim outlives the camera when a send transition fires on a later recording

Upstream, `roundVideoRecordBackground`, the full-screen scrim behind the
round-video recorder, is driven by one `BoolAnimator`, and only two things move
it: `InstantCameraView.startAnimation(open)` through the anonymous subclass in
`checkInstantCameraView` (`ChatActivity.java:12092-12095`), and
`ChatActivity.sendMedia` lowering it (`ChatActivity.java:39067`). The two paths
that actually retire the camera after a send, the fly-into-the-bubble transition
and the 3-second fallback, call `hideCamera(true)` and `setVisibility(INVISIBLE)`
directly (`ChatActivity.java:41525-41529`, `:39300-39304`) and never lower the
scrim. That is harmless only while they run on the camera that just sent.

Upstream, they can run on a *later* recording. A just-sent round message sits in
`animatingMessageObjects` until a bind finds `getTextureView() != null`
(`ChatActivity.java:41443-41444`), and it is removed only when
`applyAnimation || chatListItemAnimator == null` (`:41671-41672`), so a bind
without a camera leaves it there. The next bind of that cell while any new camera
is open plays the transition on the new camera. Starting a recording makes that
bind immediate when something is playing: `showCamera` stops the player
(`InstantCameraView.java:1051`) before it creates the texture view (`:1097`), and
`messagePlayingDidReset` rebinds every visible round cell
(`ChatActivity.java:25236-25251`). The camera is flown away and hidden within
~400 ms, but the composer keeps its recording controls: removing the texture view
stops the camera thread through `onSurfaceTextureDestroyed`, and nothing on that
path posts `recordStopped` (only `InstantCameraView.java:851`, `:1414` and
`:1540` do). Send or cancel then no-ops, because `send()` and `cancel()` both
return early on a null texture view (`:1330`, `:1531-1533`) before reaching
`startAnimation(false)`. Nothing is sent, and the scrim is left over an idle
composer.

A scrim left behind after Send, with no circle, means the texture view was
already null. `textureView` is nulled only in `hideCamera` (`:1609`), and the only
callers besides the close animation (which lowers the scrim first) are these two
paths and the pause-resume at `:874`. A resume whose `showCamera(true)` fails
leaves the view visible with the placeholder circle, which is not what this
looks like.

A round message is bound without a camera whenever the 3-second fallback
(`ChatActivity.java:39082-39086`) closes the camera before the message is
inserted, which a slow `prepareSendingVideo` can cause (it blocks on
`MessagesStorage.getSentFile`, `MessagesStorage.java:10103-10109`). The fork adds
a deterministic source: sending a restored `#video-draft-guard` draft goes
through `send(4)` with no texture view by design (`InstantCameraView.java:1330`).
Its fallback timer, like a scheduled send's, can also hit a recording opened
within 3 s, either still pending or during its 300 ms animation. Code-verified;
not yet caught on a device.

The fork guards it, `#round-video-stuck-overlay-fix`. The transition also
requires the pending close, which only a real send arms (`ChatActivity.java:41443-41444`).
The one camera opener, `needStartRecordVideo(0)`, cancels a close left with no
texture to retire (`:2777-2782`). The fallback skips its animation when there is
no texture (`:39278`). A round entry that missed its transition is dropped
without the composer calls (`:41666-41670`). The scrim follows the camera's
visibility (`:12098-12105`) and is re-synced after a view rebuild (`:8995`).

A start inside the retirement window, while the sent camera's texture is still up,
no-ops in `showCamera` (`InstantCameraView.java:1001-1004`) yet used to put the
composer into recording, with `cameraFile` still naming the just-sent file
(the early return comes before `:1058`). The same instance is reused for the
next recording, so anything driven against it then lands on the send: `cancel()`
deleted the file (`:1557`) and could cancel its still-finishing recorder, a pause
resumed into that recorder, and a second `send(4)` resent the file. Back-press
(`ChatActivity.java:37811`) and the story-reply close (`PeerStoriesView.java:7899`)
reach the same `cancel()` with no new press at all. And the texture going away is
not the end of it: the recorder is only released by its teardown post
(`InstantCameraView.java:4081`), and until then its pending posts read
`videoFile`, `file`, `size` and `recordedTime` live (`:4045`, `handleStopRecording`
from `:3813`), which a new recording's `startRecording` (`:2916`) and `showCamera`
reset.

The fork guards it, `#round-video-restart-guard-fix`, with two flags on the view.
`cameraFileHandedOff`, set where a file goes to a send, makes `send`, `togglePause`,
the surface re-attach (`:1107`) and `cancel()`'s destructive half (`:1545-1553`)
leave that file alone, and the two ChatActivity close paths retire only a camera
carrying it (`ChatActivity.java:39283`, `:41444`). `encoderTeardownPending`, set on
every terminal stop and cleared by the teardown, a skipped stop (`:2401-2407`) or a
failed prepare (`:2572`), holds a fresh `showCamera` off (`:1005`). The composer
refuses the press up front while either the texture or that flag is up
(`ChatActivityEnterView.java:19227`, called from `:1005`, `:5366` and `:19243`).
The window press was checked on a device.

Upstream `togglePause` also unpaused the recorder before its audio thread came
back. That thread exits on pause without re-posting its stop, so a stop between
resume and the first frame waited in `handleStopRecording` (`:3859`) for a
re-post that never came, and the teardown never ran. The fork leaves the
recorder paused until `prepareEncoder(true)` restarts audio (`:4168`). A stop
that reaches it in that gap takes the paused single pass and tears down; one
whose resumed camera never drew reaches nothing, as upstream. A send there is
dropped, and a cancel there leaves the paused recorder behind, so the next
recording resumes into its deleted file (`initGL` keeps it, `prepareEncoder(true)`
skips `createMuxer`, `:4201-4202`). Forwarding that stop, or pausing again on a surface loss,
was tried and dropped: the first races the pause preview post that reads
`previewFile`, the second can resume a queued start with no camera. Code-verified.

*(Established 2026-09-26, investigating a stuck overlay on a video-message start.)*

## Removing a view from its own focus-loss callback during a focus handoff can leave two views focused

`ViewGroup.requestChildFocus` unfocuses the old child and only then assigns
`mFocused = child` (android-37 `ViewGroup.java:876-882`). If the old child's
`OnFocusChangeListener` removes it from its parent synchronously,
`removeViewInternal` still sees it as `mFocused` (`ViewGroup.java:5738-5740`)
and calls `rootViewRequestFocus()` unconditionally, touch mode or not
(`ViewGroup.java:5773-5778`). The root picks a new focus holder (in the trigger
sheet, the first pattern field); the outer call then overwrites `mFocused`
without unfocusing it. Both
views keep `isFocused()` and both cursors blink, and nothing can clear the
orphan.

The trigger sheet hit this when a blank pattern row auto-removed on focus
loss. The fix posts the removal instead
(`EventScheduleHelper.java:1203-1213`).

*(Established 2026-09-26, `#trigger-cursor-fix`.)*

## The chat top panel sits 5dp under the header, and the header hairline must be drawn at the header's depth

`checkUi_topPanelPositions` lifts `topPanelLayout` (player strip, pinned
message, join bar) by a stock `dp(5)` so it tucks under the header
(`ChatActivity.java:13654-13655`). Liquid Glass hides this inside its 7dp
padding; MD3 zeroes that padding (`:52279`), so the strip's top 5dp sit under
the header. A translucent MD3 header matches the strip and hides it; the solid
Classic/Day header shows it as a clipped strip, so the lift is dropped only
there.

The MD3 header hairline is drawn in `drawChild` straight after the header
group's topmost child (`:19545-19547`, anchor at `:19639-19641`): `actionBar`,
or `actionBarSearchTags` when shown, the only member added after it (`:8665`,
`:9926`). Drawn after `super.dispatchDraw` it sat above every overlay added
later, such as the round-video scrim (`:8994`) and forward options
(`:12556`); special-casing each one was the old fix.

The chat preview's action bar does not occupy the status bar
(`BaseFragment.java:333`), so upstream's `inPreviewMode ? statusBarHeight`
term in its header-bottom formulas (`:19699`, `:41562`) lands a status bar too low;
the hairline reads the bar's own bottom instead. Upstream also drops the
preview bar by `dp(1)` (`:20558-20560`), which bares a wallpaper strip above a
flat MD3 bar, so the drop is kept only for the glass pill.

*(Established 2026-09-27, `#solid-header-panel-fix`, `#round-video-divider-fix`; reworked 2026-09-28, `#separator-overlay-fix`.)*

## Round video records on Camera1 by default, and Camera2 there zooms by crop from 1x

`InstantCameraView.useCamera2` (`InstantCameraView.java:232`) comes from
`SharedConfig.isUsingCamera2` (`SharedConfig.java:1968-1969`), which follows
the server flag `androidDisableRoundCamera2`, default **true**
(`MessagesController.java:1820`), unless the Debug menu forced a value. The
fork's N-Settings switch `VideoMessagesCamera2` is OR-ed in only while that
Debug force is unset, and a force stays set once the Debug row is touched. So
round video usually runs on Camera1, whose `Parameters.getZoomRatios()`
(`CameraSession.java:255`) starts at 1x on the main sensor.

Upstream Camera2 round zoom is a `SCALER_CROP_REGION` with `getMinZoom()`
fixed at 1 (`Camera2Session.java:399`). The fork's ratio mode
(`setRoundVideo`, `:413`; `CONTROL_ZOOM_RATIO` at `:543`) is opt-in per
session so the Stories camera, which maps zoom over `[getMinZoom, getMaxZoom]`
(`CameraSessionWrapper.java:148`), keeps the crop path. On an OPPO Find X9 Pro
(MediaTek, logical rear camera 0 over physical 2/3/4, ratio range 0.6–20), the
crop region already switches to the telephoto at about 3.1x. Ratios below 1x
reach the ultrawide, but the round stream then smears a band of edge columns,
so the ratio floor stays at 1x on MediaTek chipsets
(`RoundLensPresets.ultrawideAllowed`, read in `setRoundVideo`). Other
chipsets are untested.

Upstream Camera2 also records round video on `TEMPLATE_RECORD` with a
`VIDEO_RECORD` intent and a 30–60 fps range. On the same phone's front camera
(id 1, 4096×3072, ratio range 1–4) that makes the HAL read the sensor out as
16:9 (`rawCropRegion` 4096×2304), while Camera1's shim, sending a `PREVIEW`
intent, gets 4:3 (2048×1536). Both streams are square at zoom 1 with the full
crop region and stabilization off, so Camera2 framed about 1.33× tighter.
Round sessions keep the preview template, intent and fps (`:520`, `:537`).

*(Established 2026-09-27, `#video-zoom`, PR #439; device facts from
`dumpsys media.camera` and ADB traces. Front readout 2026-09-30,
`#round-camera2-fov-fix`.)*

## Navigation hooks see a fragment before it has read its arguments

`LaunchActivity.needPresentFragment` / `needAddFragmentToStack` are called from
`ActionBarLayout.presentFragment` / `addFragmentToStack` *before*
`fragment.onFragmentCreate()` (`ActionBarLayout.java:2131`, `:2529`). A
`ChatActivity` passing through them still has `dialog_id == 0` and
`chatMode == 0`, so `getDialogId()` there never matches anything. Read the
Bundle instead (`getArguments().getLong("user_id")`, `"chat_id"`,
`"chatMode"`).

Missed once: the video memo shortcut's own Saved Messages push looked like
"navigating away" and locked the app the instant it opened, which a traced
device run showed as a lock with no chat bound.

*(Established 2026-09-28, `#video-note-shortcut`.)*

## ChatActivity lays ordinary children out below its action bar

A plain `MATCH_PARENT` child added to `ChatActivity.contentView` does not start
at the top. `ChatActivityFragmentView.onLayout` pushes every `Gravity.TOP`
child that isn't the action bar down by the action bar's height
(`ChatActivity.java:20504-20507`). Only the special-cased views reset
`childTop` before `child.layout(...)` (`:20564`), e.g.
`isFullSizeIgnoreInsersChild`, `chatInputViewsContainer` and
`instantCameraView`. An overlay meant to cover the header is therefore short
by exactly the header, and the header stays tappable above it.

`#video-note-shortcut`'s shield overrides its own `layout()` to take
`(0, 0, parentWidth, b)` rather than touching that list.

*(Established 2026-09-28, `#video-note-shortcut`.)*

## Opening a chat for an arbitrary user id: the gate passes it, ChatActivity blocks on it

`MessagesController.checkCanOpenChat` only checks a restriction reason when the
user is already in memory. An uncached id returns `true` untouched
(`MessagesController.java:23151-23152`). `ChatActivity.onFragmentCreate` then
reads the user from the database on the storage queue while the UI thread waits
on a `CountDownLatch` with no timeout, and returns `false` if the row is missing
(`ChatActivity.java:3162-3178`). The push then fails with nothing on screen and
no fallback.

A launcher or intent path that pushes a stored user id has to resolve the user
itself first. `#video-note-shortcut`'s `VideoNoteTarget.resolve` does a bounded
storage-queue read and `putUser`s the result, so ChatActivity never latches.

*(Established 2026-09-28, `#video-note-shortcut`.)*

## A people-only chat picker: the attach-bot chooser, whose search and recent strip leak

`DialogsActivity` with `dialogsType = DIALOGS_TYPE_START_ATTACH_BOT`, `onlySelect`,
and `allowUsers` as the only `allow*` flag set lists existing chats with people:
non-deleted, not bots, not yourself (`DialogsActivity.java:11448-11458`), under
the title *Choose User* (`:3664-3665`). `DIALOGS_TYPE_USERS_ONLY` looks like the
obvious choice but keeps bots.

Search is looser. `DialogsSearchAdapter.filter` only sorts users from bots,
groups and channels by the `allow*` flags (`DialogsSearchAdapter.java:287-300`),
so deleted accounts pass, and local search offers Saved Messages in this picker
(`MessagesStorage.java:18384-18390`). The recent-contacts strip above the list
shows whenever `allowUsers` is set (`DialogsSearchAdapter.java:1709-1711`) and
skips `filter()` entirely. Service accounts (777000 and friends) pass even the
list filter. So the delegate must validate every pick, and return `false` to
keep the picker open.

*(Established 2026-09-28, `#video-note-shortcut`.)*

## LaunchActivity's passcode gate sees intents its action chain then ignores

`handleIntent` runs the passcode gate (`LaunchActivity.java:1588-1589`) before
the check that skips any intent relaunched from recents or handed back to a
recreated activity (`:1671-1672`; `restore` is `savedInstanceState != null`
from `onCreate`, `:686`). A task started by a launcher shortcut keeps that
shortcut's intent as its root, and a recreated activity gets it back from the
system with action and extras intact, so clearing the action in-process (as
`VideoNoteShortcut.accept` does) does not stop the replay. Anything the gate
decides from the intent has to refuse `restore` and
`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` itself.

Missed once: the video memo shortcut's lock bypass fired on the replayed
intent, nothing opened to use it, and the real tap that followed was refused
and got the passcode before recording.

*(Established 2026-09-29, `#video-memo-passcode-fix`.)*
## A LayoutTransition on a RecyclerView item stops the list placing it

`ViewGroup.layout()` returns without laying out while the view's own
`LayoutTransition` is running a change animation (`isChangingLayout()`). Put
one with `CHANGING` enabled on a RecyclerView item and any adapter change
during that animation (`notifyItemChanged`, `notifyItemRangeInserted`) can't
move the item. The list records a stale post-layout top and runs a move
animation to it. The item flies across the screen, then snaps back when the
transition ends. Put the transition on an inner frame instead
(`LauncherShortcutsActivity.java:331-357`). Settings pages trigger this through
`ConfigCellSelectBox.onClick`, which calls `notifyItemChanged`
(`ConfigCellSelectBox.java:118`).

Missed once: the launcher-shortcuts preview flew in from below when the
video memo camera or switch changed. A logcat trace showed the item at
`top=1734` animating its `translationY` from `-1063` to `0` while its real top
was `671`.

*(Established 2026-09-30, `#video-note-shortcut`.)*
## A requestLayout from inside ChatActivity's measure pass is lost

`ChatActivityFragmentView.onMeasure` measures `chatActivityEnterView` early
(`ChatActivity.java:20333`), then later in the same pass calls `checkInsets()`
(`ChatActivity.java:20469`). A child that calls `requestLayout()` from that later
point, after it was already measured, gets nothing. Its ancestors still carry the
pass's layout flag, so the request stops there and no new traversal is
scheduled. The child keeps its stale measurement until something unrelated
lays the screen out again. Post the request instead when `isLayoutRequested()`
is already true (`ChatActivityEnterView.java:7745`).

Missed once: the fullscreen input settled 136px short of the header in a chat
with a pinned message. Its height budget grew when the pinned bar hid, but the
update arrived from `checkInsets()` mid-pass. The NAX_SMOKE trace showed the
field measured at budget 1063 and never re-measured after the budget became 1189.

*(Established 2026-10-02, `#fullscreen-input`.)*

## A SeekBarView value set before a sheet opens draws at the wrong spot

`SeekBarView` keeps its thumb in pixels. A value set while it has no width is
held and applied once, in the first `onMeasure` that sees a non-zero width
(`SeekBarView.java:388-390,430`), and never re-scaled after that.
`BottomSheet.show()` measures the sheet in advance with AT_MOST specs
(`BottomSheet.java:1591-1594`). A slider row that is a vertical `LinearLayout`
under AT_MOST shrinks to its widest child and re-measures the bar at that
narrow width, so the held value is spent there. On the real pass the thumb stays
at the narrow-width pixel, and only a drag corrects it. Re-call `setProgress`
from an `OnLayoutChangeListener` on the bar; it only invalidates, so it can't loop.
`doOnLayout` is not a substitute: it waits for the next layout, which a later
programmatic change (a Reset) never triggers.

*(Established 2026-10-03, `#header-avatar-bg`.)*

## Round video Camera2 sessions have no fixed slot per camera

`InstantCameraView.camera2Sessions` is laid out three ways. A dual pair puts
the starting camera in slot 0 (`InstantCameraView.java:1101`, read back through
`isFrontface == initialCameraFront` at `:1111`, `:1678`). A pair whose second
camera failed to open drops `bothCameras` but leaves the survivor in slot 0
(`:1111-1113`). A single session, and the `#round-dual-camera-fix` fallback,
slot by facing, front 0 and rear 1 (`:1117`, `:1690`, `:1748`). Any fixed index
for "the rear camera" is wrong in one of them; the torch picks its session by
`Camera2Session.isFront()` instead (`:537`).

*(Established 2026-10-03, `#round-camera2-flash-fix`.)*

## A chat-list row only rebuilds its text when its redraw key changes

Two gates stand between a state change and a `DialogCell`'s text.

`DialogCell.buildLayout()` returns early unless `DialogUpdateHelper.update()`
reports a change (`DialogCell.java:1258-1262`). That key covers only the message,
read/unread counts, draft, typing, size, pin and call state (`:6393-6466`).
Anything else the row's text depends on stays stale until an app restart — even
across `notifyDataSetChanged()`. The hidden preview folds a hash of its
rendered text into the key (`:6418-6419`).

`DialogCell.update(mask)` handles `UPDATE_MASK_STATUS` by re-fetching the user
and invalidating only when the online dot flips (`:3453-3457`). Setting
`rebuildLayout` there does nothing: unless `continueUpdate` is set, the method
invalidates and returns at `:3606-3610`, before the `buildLayout()` at `:3841`.
Status-dependent text must set `continueUpdate = true` (`:3459-3461`).

*(Established 2026-10-03, `#hide-last-message`.)*

## A see-through window on ColorOS: what it takes, and what the launcher does anyway

The text memo's second shortcut, "Text memo · floating" (`#text-memo-shortcut`, PR #472
and its follow-up), floats just the card over whatever is on screen. On the OPPO/ColorOS
test device that works only with all of these together:

- A **floating** theme, not a merely translucent one. A translucent theme alone
  opened a full-screen opaque task and showed black behind the card until a
  runtime conversion landed a second or two later. The theme is a child of
  `Theme.TMessages.Transparent` (`styles.xml:52-63`, `values-v21/styles.xml:116-127`,
  the one that wins at minSdk 27), in `styles_nax.xml:14`.
- `windowDisablePreview` in that theme (`styles_nax.xml:15`). ColorOS adds a
  starting window even for a translucent activity and it draws black until the
  first frame.
- `Activity.setTranslucent(true)` at runtime on API 30+
  (`TextMemoActivity.java:174`); the theme alone left `dumpsys` showing the task
  `translucent=false` behind a transparent window.
- `window.setLayout(MATCH_PARENT, WRAP_CONTENT)` plus `Gravity.BOTTOM` after
  `setContentView` (`TextMemoActivity.java:180-181`) for a card-sized window;
  `adjustResize` then lifts it above the keyboard.

Translucency cannot be switched on an open activity, and an `<activity-alias>`
does not take its own theme, so the two looks are two manifest activities
(`AndroidManifest.xml:604-622`) and each has its own launcher shortcut, both
always offered (`TextMemoShortcut.java:32`, `:71`). A setting that picked one
would have to rewrite the published shortcut's intent, which
`buildShortcuts()` doesn't do below API 30 (`MediaDataController.java:5070`)
while the id set is unchanged (`:5101-5103`); two ids avoid that. A card-sized window clips
anything leaving it, so the send animation shrinks into the input
(`TextMemoActivity.java:655`), and the keyboard is left up until `finish()`
(`:1114-1115`) because hiding it would move the window under a leaving card.

What no window flag fixes: the ColorOS launcher stops drawing its home screen
for about 1-2 s when it starts a shortcut, so anything see-through shows black
there. Lawnchair shows no black behind the card (dazewell, 2026-10-04); other
launchers and the lock screen are untested. That is why the wallpaper one stays
the first shortcut and the floating card is offered beside it, not instead. Evidence
is visual, from the device, not from logs.

*(Established 2026-10-04, `#text-memo-shortcut`.)*

The memo finishes itself in `onStop` (`TextMemoActivity.java:1188` is the guard), so any screen opened over it ends it. Photos and videos (`#text-memo-photos`) use the system `ACTION_GET_CONTENT` picker, and `picking` skips that finish while it is up and saves the text as the draft instead. A picked uri is only readable while the memo lives, so each is copied to the cache at pick time and the memo holds paths. The copy is our own loop (`TextMemoPhotos.java:143`) because `MediaController.copyFileToCache` has no progress or cancel hook; it stops at the next chunk when its pick is cancelled. Media is never part of the chat's draft; leaving without sending deletes the copies, and a sent copy stays in the sharing cache until it is cleared, which a video makes costly (so videos are capped at 1 GB, and what removes them is Telegram's cache size limit, only if one is set (the default is none, `AutoDeleteMediaTask.java:142`): its age-based pass skips subfolders, `AutoDeleteMediaTask.java:84`, but the size pass recurses into them, `:158`).

*(Established 2026-10-05, `#text-memo-photos`.)*

## SimpleTextView.getTextStartX() is in the parent's coordinates

`getTextStartX()` returns `(int) getX() + offsetX + textOffsetX`
(`SimpleTextView.java:796`). That is where the text starts in the **parent's**
coordinates, not inside the view. Code that walks up the hierarchy adding each
view's `getX()` and then adds `getTextStartX()` counts the view's own position
twice. The header pills attempt did exactly that: a backing behind the chat
title drew about 55dp to the right of the name. Subtract `getX()` to get the
offset inside the view.

*(Established 2026-10-09, `#header-avatar-bg`.)*

## Sending a round video from a scrolled-up chat leaves the camera frozen over the list

`sendMedia` arms a 3 s fallback that retires the camera circle
(`ChatActivity.java:39184-39188`). Normally the new round cell binds within
milliseconds and cancels it, flying the circle into the bubble. Sent from a
chat scrolled up, the row is inserted off-screen, no cell binds, and the fallback
fires: the full-screen camera view stays up and swallows touches for about 3 s.

Cause: `processNewMessages` asks for a jump to the newest message
(`moveScrollToLastMessage(true)`, `:28559`, defined `:17466`), but the fork's input
island pin (`#fullscreen-input`, `checkUi_chatListViewPaddings`, `:13603-13637`)
re-issues `scrollToPositionWithOffset` to the old position every frame while the
composer resizes, which is what the recording UI leaving does. It arms only when
the list is not at position 0, which is why a send from the bottom is fine. The
jump was therefore consumed without moving the list, and the pin is also a plain
`scrollToPositionWithOffset`, so nothing else in the layout flags shows it.

`#round-send-hang-fix` clears the pin in `moveScrollToLastMessage` and does not arm
a new one while `forceScrollToTop` is set (`:13603`). Any new code that re-asserts a
scroll position per frame has to yield to that jump.
The animated `chatScrollHelper` jumps (`scrollToLastMessage`, the postponed scroll) do not go
through `moveScrollToLastMessage`, so a pin already active when one starts is not cleared.

*(Established 2026-10-11, `#round-send-hang-fix`.)*

## A chat opened behind a cover still marks its history read

`updateMessagesVisiblePart` reads from what the list holds, not from what the user
can see, and it does so in two places. The per-cell loop consumes unread poll votes
and reactions, the fork's hidden-reaction filter included (`ChatActivity.java:18018`, `:18032`, `:18035` for message
cells, `:18069`, `:18072` for action cells). The read check below it calls
`markDialogAsRead` (`:18484`, calls at `:18521` and `:18531`). Neither looks at
overlays, only at a paused chat, a preview or `ignoreSetOnline`. A resumed chat drawn
over by something else, like the video memo's locked-app shield, is read locally and
on the server. `#video-note-shortcut` hoists `VideoNoteShortcut.hidesHistory` into
one local (`:17865`) and gates all of them. Another overlay needs the same.
`markDialogAsReadNow` in `onPause` (`:32689`) only flushes a read already queued.

*(Established 2026-10-12, `#video-note-shortcut`.)*

## A keyboard asked for at activity start is silently dropped

`InputMethodManager.showSoftInput` (`AndroidUtilities.showKeyboard`) is ignored until
the IME has bound to the focused field. That rules out `onCreate`, and also
`onWindowFocusChanged`, because `ViewRootImpl` dispatches that callback before the
IME's post-focus binding. A `postDelayed` only works by landing after that binding.
For an activity that should open with the keyboard, set `stateVisible` in its
`windowSoftInputMode` and focus the field in `onCreate`. The text memo does this
(`AndroidManifest.xml:612`, `:622`; `TextMemoActivity.java:521`). `stateVisible`
acts only on forward navigation, so coming back from a picker doesn't reopen it.

*(Established 2026-10-12, `#text-memo-fix`.)*

## CheckBoxBase draws its number from the canvas top, not from its bounds

`CheckBoxBase` centres its circle on `bounds`, but upstream drew a number (`setNum`,
`setChecked(int, …)`) at a baseline of `dp(y)` from the canvas top, tuned for a 24dp
box. That only works because every upstream number caller goes through `CheckBox2`,
which sets bounds at (0,0) (`CheckBox2.java:106`). The chat bubble's selection box sits
at the bubble's bottom at size 21 (`ChatMessageCell.java:21126`), so a number there
landed mid-bubble. `#reschedule-order` offsets it by `bounds.top` and re-centres for
size (`CheckBoxBase.java:623`), pixel-identical for size 24. Any new number on a
`CheckBoxBase` placed off (0,0) depends on that line.

*(Established 2026-10-13, `#reschedule-order`.)*

## Opening scheduled messages at a message: "message_id" can land on the wrong one

`ChatActivity`'s `message_id` argument loads around that id and then, on the loaded
batch, scrolls to the **first** message whose id is ≤ the target
(`ChatActivity.java:23662`), walking the batch in list order (`:23332`). That
assumes list order follows id order. A scheduled list is ordered by date, both from
the cache (`MessagesStorage.java:9009`) and in the re-sort every load goes through
(`MessagesController.java:12401`). Any message scheduled out of creation order breaks
the link, so the jump can stop on a different message and skip the highlight. To open
a scheduled chat at an exact message, wait for that fragment's own `messagesDidLoad`
(match its `classGuid`), then for its open animation (`openAnimationEnded`) and an
idle list, and only then call `scrollToMessageId(id, 0, true, 0, true, 0)`, which
looks the id up exactly and highlights it. Called straight after the first load, the
list stayed at the bottom on a device. The Message Triggers page does this
(`MessageTriggersActivity.java:186`, `:231`).

*(Established 2026-10-12, `#eventschedule`.)*

## A scrolled composer draft draws above the field, and only the fade hides it

`EditTextBoldCursor.onDraw` zeroes `mScrollY` around `super.onDraw` (`EditTextBoldCursor.java:879`) and
has the first `getExtendedPaddingTop`/`Bottom` calls return 0 and `-scrollY` (`:726-741`, armed at `:888`),
so `TextView` never clips the top: the lines a long draft has scrolled past are drawn above the edit text.
Nothing in the composer clips them except `ChatActivityEnterView.drawChild`'s cut at the input row's top
(`ChatActivityEnterView.java:5185`) and `drawMessageEditText`'s layer and 14dp top fade (`:19041`). Any edge
for that clip or fade has to be the input row's top, which the MD3 field is drawn from too
(`ComposerMd3Surface.java:627`). The fade used to sit at `messageEditText.getY() + animatedTop`, and
`animatedTop` stays wherever it was while `waitingForSendingMessageLoad` holds back its reset animator
(`ChatActivity.java:8829`), so the fade could land across a line in the middle of the field and leave the
real top as a hard cut. It is now anchored to the row (`ChatActivityEnterView.java:19050-19060`).

That row top reads `animatorInputFieldHeight`, but `drawMessageEditText` runs inside the edit container's display
list, and `onFactorChanged`'s own `invalidate()` (`:19246`) re-records only the enter view. So a height change must also invalidate
the container (`ChatActivityEnterView.java:19220`), or the cut keeps the factor from its last recording. Expanding
the input showed this: with the collapsed row's top baked in, short drafts vanished and longer ones lost their top
lines until a new line or a scroll redrew the container (traced on device, `#composer-text-clip-fix`, PR 529).
Anything else this method reads from outside the container needs the same treatment.

*(Established 2026-10-15, `#composer-text-clip-fix`.)*

## Only one bulletin is visible app-wide, and an undo bar's delayed action fires when another replaces it

`Bulletin.show()` hides whatever bulletin is currently visible, on any screen (`Bulletin.java:286`). An
`UndoButton`'s delayed action runs when its bar is detached without an undo (`:2328`). So a bar that commits on
timeout, such as delete-unless-undone, commits early the moment any other toast appears. Run the commit on your
own timer and keep the button for undo only. To tell a swipe-away from being pushed off, check
`Bulletin.getVisibleBulletin()` one tick after your hide listener fires. `hide()` clears it at once (`:436`), and
a displacing bar has taken it by then.

Two more ways a bar can't be put anywhere:
- A `BulletinWindow` can't take touches: it is created `FLAG_NOT_TOUCHABLE` (`:2541`), so its buttons do nothing.
- A bar on `LaunchActivity.frameLayout` only gets the navigation-bar offset (`LaunchActivity.java:754`), so in a
  chat it sits over the composer.

Placement comes from the host screen's own delegate (`Bulletin.java:332`). A bar meant to follow the user across
screens has to be re-made on the top screen (`StashOffer.java`).

*(Established 2026-10-16, `#stash`.)*
