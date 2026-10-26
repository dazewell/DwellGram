# DwellGram features

Extra bits I've added on top of [NagramX](https://github.com/risin42/NagramX). Most are on out of the box. Where something has a setting, I've said so.

<!-- House style: an entry is a definition, not a writeup. Max 70 words of prose
     under the ### heading (images and tables don't count); most sit around 35.
     What it does and where it is, then its setting and default. No edge cases,
     no failure behaviour, no rationale. Extending a feature means folding it
     into the existing entry and re-counting, never a second heading. -->

## Chats and privacy

### Per-chat time zones <!-- #timezones -->

Set a time zone for any personal chat or group from its profile edit view. The chat header and contacts list then show the peer's local time as a clock pill; tap it for a time converter you can drop into the message box as a formatted line. Scheduling a message to a chat with a zone set adds *My time* / *Peer's time* tabs to the picker.

<img height="200" alt="time zone picker sheet listing selectable IANA time zones under a search field" src="docs/images/features/timezones-1.png" />
<img height="200" alt="chat list row showing a peer's name with a clock pill giving their local time" src="docs/images/features/timezones-2.png" />

### Customized privacy <!-- #customized-privacy --> <!-- #hide-last-message --> <!-- #require-password --> <!-- #disguise-alerting --> <!-- #disguise-parity -->

Each chat's ⋯ menu has one `Chat privacy` item, opening a sheet with `Hide last message`, `Require password`, and `Disguise notifications` — cover persona selection plus a preview button. A hidden chat's list preview shows your own text, the header's last-seen status or member count, the unread count, or just the message type. Covered notifications behave like any other, differing only in text and tap, and add Mark as read.

<img height="260" alt="Chat privacy sheet with Hide last message and Require password switched on, a custom placeholder text row between them, and the Disguise notifications card below" src="docs/images/features/chat-privacy-sheet.png" />

### Privacy profiles <!-- #privacy-profiles --> <!-- #passcode-autolock-seconds -->

Save sets of auto-lock timeouts under Nagram Settings → Passcode and switch between them, each with its own icon and colour. Activate a profile for now, for a stretch of time, or until a specific moment; long-press the Settings tab for a quick switcher. Telegram's own auto-lock picker gains 5, 10, 15 and 30 second steps, which profiles can use too.

### Passcode setup safety <!-- #passcode-setup-safety -->

A Panic Code that matches an unlock code is a security risk, so setup now requires yours to be unique — it can't match your app passcode or any account's passcode. The setup screens also name which code you're setting (App, Panic, or Account). Codes set before this check existed may clash, so the settings screen will prompt you to re-set your Panic Code.

### Reply threads in private chats <!-- #personal-replies --> <!-- #reply-threads --> <!-- #thread-upwards --> <!-- #thread-in-chat -->

Private chats now show a reply glyph and count next to a message's timestamp, bringing group-style reply threads to 1-to-1 chats. *View Thread* in the message menu, on any reply or replied-to message, swaps the history for its whole thread, landing on the one you picked, without leaving the chat. *View in chat* on a thread message jumps to it in the real history.

<img height="150" alt="private chat message bubble showing a reply glyph and count next to its timestamp" src="docs/images/features/reply-threads.png" />

### Message bookmarks <!-- #bookmarks-300 -->

Bookmarks come from NagramX; this fork raises the per-chat cap from 30 to 300. Saving from the message menu, per-account storage, and inclusion in settings backup and restore all work as they did.

### Save scope by chat type <!-- #save-scope -->

Tap the *Save Deleted Messages* or *Save Edits History* row under N-Settings → Experimental (the switch end still toggles it) to pick which chats it covers: private chats, public or private channels, public or private groups. All five are on by default. Anything already saved stays viewable.

### Edits history shows what changed <!-- #edits-history-diff -->

Edits History (message menu, with *Save Edits History* on under N-Settings → Experimental) comes from NagramX. Here it ends with the current version, and each version after the first shows its change from the one before: removed words struck through, added words bold and underlined. Copy still copies the version's own text.

### Ayu Mode shortcut <!-- #ayu-mode -->

A launcher shortcut (long-press the app icon, or pin it to your home screen) that opens the app with Ghost Mode already on. Tapping it flips every Ghost toggle you haven't locked and pushes you offline, then opens as normal. On by default; switch it off under N-Settings → General → Launcher shortcuts.

### Unofficial shortcut names <!-- #shortcut-variant-prefix -->

DwellGramX puts an X in front of its own launcher shortcut names (*X Ayu Mode*, *X Video memo*, *X Text memo*), including the entries in shortcut pickers, so both builds can sit on one phone. DwellGram keeps the plain names.

### New conversation shortcut switch <!-- #new-conversation-shortcut -->

Telegram's *New conversation* launcher shortcut can be switched off under N-Settings → General → Launcher shortcuts → Built in, for launchers that only show a few shortcuts. On by default.

### Hold messages while Ghost Mode is on <!-- #ghost-hold -->

Turn on **Hold Messages** under Settings → Ghost Mode, or from the Send button's long-press menu while Ghost is on (off by default), and most plain text messages, contacts and static locations stay on this device. They wait in the chat's Scheduled list marked "Held — not sent". Turning Ghost Mode or Hold Messages off asks before releasing them; future-scheduled items keep their schedule. Unsupported sends go through normally.

<img height="260" alt="the Hold Messages toggle in its Ghost Mode section with the explanatory footer beneath it" src="docs/images/features/ghost-hold-setting.png" />
<img height="260" alt="a chat's Scheduled list with three held messages, each captioned Held — not sent" src="docs/images/features/ghost-hold-scheduled.png" />
<img height="260" alt="the Send held messages? confirmation shown when Ghost Mode is switched off with messages still held" src="docs/images/features/ghost-hold-turn-off.png" />
<img height="260" alt="a bulletin reading Held — won't send until Ghost Mode is off, shown above the composer straight after a send was held" src="docs/images/features/ghost-hold-bulletin.png" />

### Ghost icon stays put under stories <!-- #ghost-icon -->

With Ghost Mode on, the ghost indicator next to the chat list title stays visible even when contacts' stories collapse the header.

### Your last seen under the Ayu Mode switch <!-- #own-last-seen -->

With Ghost Mode on, the Ayu Mode switch in the chats ⋮ menu and the Settings tab's long-press menu shows your last seen underneath, as Telegram has it for you, e.g. *seen at 14:05*. Settings → Ghost Mode → *Show my last seen*, on by default.

### Ghost send warning <!-- #ghost-send-warning --> <!-- #ghost-type-warning -->

Ghost Mode hides read receipts, typing and online status, but does not hold sends by itself. With Hold Messages off, the first time you type in a chat each Ghost session you get a heads-up, and that chat then stays quiet for the session. With it on, held text, contacts and static locations use the Scheduled-list caption as feedback; unsupported sends still warn before the quiet period starts.

### Clear Message Database removes only this install's media <!-- #clear-db-own-media -->

Clear Message Database now removes only the media this install has database rows for, preventing a second install sharing the Downloads folder from having its media wiped.

### Keep a chat's messages off your watch <!-- #wear-messages -->

Each chat's Notifications screen (open a chat → its name → Notifications) has a **Show on Watch** switch under Message Preview, on by default. Turn it off and that chat's message notifications stop reaching a paired Wear OS watch, while the phone notification is unaffected. It isn't available on secret chats — set it on a forum's main chat to cover every topic.

### Notification profile per chat <!-- #notification-profiles --> <!-- #notif-profile-muted -->

Each chat has a **Notification profile**: **Loud** (Telegram's usual alerts, the default), **Passive** (shade only: no status-bar icon, lock screen, sound, vibration, popup or watch) or **Muted** (Telegram's own mute, on every device). Set it on the chat's Notifications screen or with the header bell, which replaces the title's mute icon: tap to cycle, long-press to pick. Not on forum topics.

### Chat header and menu order <!-- #chat-menu-order -->

N-Settings → Chats → Chat menu, or a long-press on ⋮ in a chat, places each chat button in the Header (up to two icons), the ⋮ menu or Hidden: drag between sections, or tap to hide or restore. Alerts is the header bell or a ⋮ row. A header button that can't show in a chat appears in ⋮ instead. Changes save immediately and show once you leave.

### Notification schedule per chat <!-- #notif-profile-schedules -->

Give a chat's notification profile a schedule: in the profile picker (long-press the header bell, ⋮ Alerts, or the Notifications screen) tap **Schedule…** and add rules with days, start, end and a profile (Loud or Passive). The rule that started last wins. A manual pick lasts until the next rule starts or ends; **Outside schedule** sets the profile between rules. A clock on the bell shows a schedule is in charge.

### Timed notification profile <!-- #notif-profile-timer -->

In the profile picker (long-press the header bell, ⋮ Alerts, or the Notifications screen) tap the clock beside a profile, then pick a duration from 15 minutes to 8 hours or **Until…** a time in the next 24 hours. The profile overrides any schedule until it ends, then the schedule resumes. Picking another profile or **Cancel timer** ends it early.

### Swipe to next channel within the folder <!-- #next-channel-in-folder -->

Swiping up at the end of a channel normally jumps to the next unread channel, and once the current folder runs out it moves on to other folders and the archive. N-Settings → Chat → Channels → *Swipe to Next Within Folder* keeps it in the folder you opened the channel from. Off by default.

## Composer and input

### Composer toolbar <!-- #composer-toolbar --> <!-- #composer-bubbles --> <!-- #toggle-formatting -->

The compose field sits in a glass text pill with Send or mic at its trailing end, and a row of action bubbles below it — Quote, Spoiler, Select All and Clear — which enable when there's text to act on.

Style buttons (Bold, Italic, Monospace, Strikethrough, Underline, Spoiler, Quote, Code) toggle off if you re-apply them to already-styled text, here or in the platform's selection popup.

<img height="180" alt="compose field with a phrase selected, so the action bubbles in the row below render enabled rather than greyed out" src="docs/images/features/composer-toolbar-live.png" />

### Send and mic inside the input <!-- #composer-input -->

Send and the mic sit inside the text pill, drawn slightly in from its rounded end so a thin ring of glass shows around them. The Material Design 3 composer puts them at the end of its button row instead.

### Docked composer <!-- #docked-composer -->

The Material Design 3 composer can run as a flat panel edge to edge along the bottom, resting straight on the keyboard instead of floating as an island. The field, reply strip and selection buttons share one corner radius. Pick *Composer style* → Floating or Docked at the top of Chat settings → Composer Toolbar, where the preview shows it. Defaults to Floating.

### One-row composer at rest <!-- #composer-inline-rest -->

With the keyboard down and the compose box empty, the composer folds into one row: Leading buttons, field, Trailing buttons, mic. Opening the keyboard grows it back to two rows. It comes with the MD3 composer, in both *Composer style* options (Chat settings → Composer Toolbar).

### Composer toolbar layout editor <!-- #composer-layout --> <!-- #composer-layout-tap-toggle --> <!-- #composer-leading-2slot -->

The button row under the compose box is yours to arrange via chat settings. You can place any action in any zone (Leading, Scrolling, Trailing, Hidden). The Leading zone is capped at two slots. Tapping a row in Hidden or Scrolling toggles it straight to the other section without dragging. Hold any button on the live toolbar for about one second to open this editor directly.

<img height="260" alt="layout editor listing draggable toolbar buttons under a Leading zone capped at two slots, a drop hint, and the start of the Scrolling zone" src="docs/images/features/composer-layout-zones.png" />

### Attach button stays visible while typing <!-- #composer-attach-pinned -->

The attach paperclip remains visible on the toolbar even when the field is full of text, rather than swapping into the header overflow menu.

### Cut, Copy, Paste buttons <!-- #composer-clipboard-actions -->

Cut, Copy, and Paste are available as composer toolbar buttons, added through the layout editor.

### Composer toolbar size <!-- #composer-scale --> <!-- #composer-spacing -->

The toolbar row can be scaled from 75% to 125% in 5% steps. Its buttons sit packed close together without shrinking.

### Gap above keyboard <!-- #composer-keyboard-gap -->

Adds space between the composer and the keyboard or emoji panel while either is open. With both closed the composer stays where it was. Set it from 0 to 24 dp under Chat settings → Composer Toolbar → Gap above keyboard. Defaults to 0.

### Interface style <!-- #interface-style --> <!-- #composer-transparency --> <!-- #rounded-navigation -->

NagramX Settings → General → Interface Style picks Liquid Glass or Material Design 3, restyling chat headers, chat-list top bar, buttons, bottom navigation and composer, each with an Apply to toggle. Also blur, switch and slider styles, typeface, icons, tablet mode, notification icon, title centering, decorations, the bottom bar and folder tabs, each with a live preview. Rounded navigation and Match Classic and Day header color are off by default.

### Quick schedule button <!-- #quick-schedule -->

The calendar icon is a one-tap schedule shortcut, instead of long-pressing Send.

### Remembered send action <!-- #remember-send-action -->

Long-press Send and pick silent, send-when-online, or schedule; it repeats on your next send, with a badge on Send showing which. Tap the checked option again to drop it. The same menu has a Remember switch up top, on by default: tap to turn the feature on or off without closing the menu, long-press to open Chat Settings → Remembered send action for the per-type and leave-chat-reset toggles.

### Template messages <!-- #template-messages -->

Saved messages you can send in a couple of taps. Add the Templates button in Chat Settings → Composer Toolbar, then tap it in a chat and pick one: with an empty field it sends right away, otherwise it goes in at the cursor. Hold a template to insert it instead. Write and order them in Chat Settings → Template messages.

### Double-tap edit keeps the caret on the tapped word <!-- #double-tap-edit-cursor -->

When a double-tap edit action opens a sent text message for editing, the cursor lands on the tapped word instead of the end of the field. It only applies to text messages, and falls back to the end if the mapped offset is stale or invalid. Captions and the long-press edit menu keep their current behavior.

### Floating input controls <!-- #input-satellites -->

The floating send-column layout is gone. The glass-fill helper stays with the close button in reply, edit, forward, and link-preview panels.

### Message input text size <!-- #input-text-size --> <!-- #composer-emoji-scale -->

The text you type in the compose box has its own size, separate from chat bubbles. Set it with the Message input text size slider at the top of Chat settings → Composer Toolbar, where the preview shows sample text at the chosen size. Custom animated emoji scale with it. Defaults to 18.

### Physical keyboard hotkeys <!-- #keyboard-hotkeys -->

Matches Telegram Desktop bindings for BT/USB keyboards. Does nothing on software keyboards. Can be disabled in settings. Style shortcuts toggle off when re-run on already-styled text, same as the Composer toolbar buttons above.

| Shortcut | Action |
|---|---|
| `Esc` | Cancel reply/edit, close search, go back |
| `Up` (empty input) | Edit last sent message |
| `Ctrl+F` | Search in current chat / chat list |
| `Ctrl+W` | Close current chat |
| `Ctrl+PgDn` / `Ctrl+PgUp` | Next / previous chat |
| `Ctrl+Alt+Home` / `End` | First / last chat |
| `Ctrl+Shift+↓` / `↑` | Next / previous folder |
| `Ctrl+0` | Saved Messages |
| `Ctrl+1`..`8` | Pinned chat 1–8 |
| `Ctrl+9` | Archive |
| `Ctrl+J` | Contacts |
| `Ctrl+L` | Lock app (when passcode is set) |
| `Ctrl+M` | Minimize app |
| `Ctrl+R` | Mark current chat as read |
| `Alt+Enter` | Schedule message (Enter confirms the picker) |
| `Alt+↑` / `↓` | Step reply target older / newer; past newest clears |
| `Alt+;` | Emoji search: type to filter, arrows to pick, Enter inserts, Ctrl+Enter sends, Esc closes |
| `↑` / `↓` / `Enter` (suggestions open) | Navigate the inline `:emoji` / `@` / `#` / `/` autocomplete; keep typing to filter, Enter inserts the highlighted one |
| `Ctrl+B` / `I` / `U` / `K` | Bold / italic / underline / link |
| `Ctrl+Shift+X` / `M` / `P` / `N` | Strikethrough / monospace / spoiler / plain |
| `Ctrl+Shift+.` | Quote (works at cursor without a selection too) |

### Cite <!-- #cite -->

Select text in a message and tap *Cite* to drop it into your input box as a quote block. Unlike a regular Reply, it just becomes part of what you're typing, letting you cite multiple messages and answer them in one draft.

<img height="180" alt="composer preview of quoted text pulled in from a message via Cite" src="docs/images/features/cite.png" />

### Scheduled message triggers <!-- #reschedule --> <!-- #eventschedule --> <!-- #reschedule-order -->

Selected scheduled messages show pick numbers, which *Send Now* follows; *Reschedule* spreads them from a base time and interval, in pick or current order. *Send on event* arms from the schedule picker or *Reschedule*: one trigger watches message type, text, or both, and sends early, each keeping its fallback schedule. Presets save setups. Chats nav ⋯ → *Message Triggers* lists armed triggers: tap to jump there; hold, swipe to select, then delete.

<img height="260" alt="bulk Reschedule sheet with a base time, per-message interval, and a delay slider" src="docs/images/features/reschedule.png" />
<img height="260" alt="Send on event trigger editor with By message type and Or by text collapsible sections" src="docs/images/features/trigger-editor.png" />
<img height="260" alt="Message Triggers list showing armed triggers with their message-type and text conditions" src="docs/images/features/message-triggers-list.png" />
<img height="260" alt="the Presets card listing one saved preset by name with a summary of its conditions, above a Save current as preset row" src="docs/images/features/trigger-presets-card.png" />

### Countdown after scheduling <!-- #scheduled-countdown -->

After you schedule or reschedule a message, a bulletin says how long until it sends, such as *Sends in 3 days, 11 hours and 2 minutes*. When you schedule several at once, like an album or a forwarded set, it counts to the first. Always on.

### Remember the schedule offset <!-- #schedule-remember -->

Tap the bookmark icon in the schedule picker to remember your schedule offset — new messages then default to that offset instead of 10 minutes ahead. Reschedule and Edit schedule pickers get the same delay slider, but keep opening on the message's existing time unless you drag it. Turn the bookmark off to revert to normal behavior.

### Schedule picker shortcuts <!-- #schedule-quick-picks --> <!-- #schedule-wheel-bounds -->

The schedule picker's month wheel, left of the days, jumps whole months, keeping the day and time. The day wheel stays inside that month, and no wheel loops past its ends. `:00` `:30` `:59` pills sit beside the minutes. Pills above the wheels offer the next round hour, midnight, noon and other set times within 24 hours, in the peer's zone on the *Peer's time* tab. Always on.

### Status and unread count in the scheduled header <!-- #scheduled-header-status --> <!-- #scheduled-header-unread -->

The Scheduled messages header keeps the chat's subtitle: online, last seen or typing for a person, members and online count for a group, subscribers for a channel. A pill after the title counts the chat's unread messages, grey when muted. Always on.

### Tidier scheduled selection bar <!-- #scheduled-selection-toolbar -->

When you select messages in the scheduled view, the top bar keeps just Send Now, Reschedule, and Delete, tucking Copy and Forward into the overflow menu.

<img height="90" alt="scheduled-view selection toolbar showing Send Now, Reschedule, Delete and an overflow menu" src="docs/images/features/scheduled-selection-toolbar.png" />

### Forward scheduled messages <!-- #scheduled-forward --> <!-- #scheduled-reply-fix -->

Telegram's forward API cannot forward a message that hasn't been sent yet. The selection bar's ⋯ → Forward re-sends the picked scheduled messages instead, so they arrive as new scheduled messages, keeping their replies to the originals across cloud chats. Media must be in the app's cache to be re-sent. Polls, locations, and contacts cannot be forwarded this way.

### Repost as Copy <!-- #repost-reply --> <!-- #repost-spread --> <!-- #scheduled-reply-fix -->

*Repost as Copy* comes from NagramX — turn it on in settings (it's off by default) and it re-sends a message without a "Forwarded from" header, re-uploading the media. Copies keep the original reply and quote, and offer to delete the originals once sent. A scheduled *NoQuote* forward is re-sent as a copy too when it replies to something, so the reply survives; several are spaced three minutes apart.

<img height="150" alt="Reposted as a copy confirmation bar with a Delete action for the original messages" src="docs/images/features/repost-copy.png" />

### Stash <!-- #stash -->

*Stash*, in the message menu or the selection bar's ⋯, moves messages into this chat's Scheduled list: pick a time and they're re-sent there without a sender, several spaced three minutes apart. Then, like *Repost as Copy*, a bar offers to *Delete* the originals for 15 seconds, following you into Scheduled while you check; letting it go keeps them. Off by default; turn it on under N-Settings → Chat → Message Menu.

### Pin or number several messages at once <!-- #bulk-actions -->

Select messages to *Pin all* (applies your pin choice to the whole selection) or *Reply with numbers* to create an indexed table of contents via numbered replies.

<img height="260" alt="chat with several messages replied-to in sequence, each carrying a small index number" src="docs/images/features/bulk-actions.png" />

### Fullscreen message input <!-- #fullscreen-input -->

Tap the outward chevrons to grow the input between the chat header and the keyboard, taking over the pinned message bar's space. Tap the inward chevrons to return to normal height.

<img height="260" alt="message input expanded to fill the space between the chat header and the keyboard, with a text formatting row along the bottom" src="docs/images/features/fullscreen-input-on.png" />

### Don't lose typed text on an accidental back <!-- #discard-guard --> <!-- #attach-caption-guard -->

A swipe-back gesture while composing a scheduled or edited message will hold and prompt for confirmation so you don't accidentally discard it. Unsaved text in these states also survives an app-lock or if you minimize and return to the app. Captions typed for photos, videos or files come back in the message box after an app-lock (every photo's, with *Caption each photo separately* on); the attachment must be picked again.

## Media and camera

### Caption each photo separately <!-- #album-caption -->

Each photo or video in the attach sheet keeps its own caption; the sheet's caption field edits the first one's. One caption shows under the album; several show only when each photo is opened. Turn on *Caption each photo separately* under N-Settings → Chat → Media; off by default.

### Recording mode hint <!-- #media-tooltip-repeat -->

The hint that explains how to switch between voice and round-video recording appears only a few times, then stays out of your way.

### Video message playback modes <!-- #video-playback-modes -->

A button in the player bar cycles how round video messages play — play once, play all, or repeat one.

### Mute video messages <!-- #video-mute -->

A mute button next to the playback-mode toggle silences round video messages.

### Closed captions on video messages <!-- #video-cc -->

A CC button above the transcribe button provides live, line-by-line captioning over the video. Providers that return timings (Groq, Cloudflare) get captions that line up exactly with the speech; others get evenly-paced text. Captions only ever come from a transcription you explicitly ask for, so they never trigger unasked transcriptions or use surprise API credit.

<img height="260" alt="round video message playing with its caption line rendered in a bubble below it" src="docs/images/features/video-cc-playback.png" />

### External microphone toggle in video message popup <!-- #external-mic -->

When camera mode is set to Ask, the camera choice popup has an External Microphone toggle to record through a headset instead of the built-in mic.

### Infinite video message <!-- #infinite-video -->

*Infinite Recording* stitches 60-second round video message segments end to end instead of stopping at the usual cap. Toggle it from the camera overlay while recording — off by default, and unavailable during slow mode, paid messages, secret chats, or view-once. N-Settings → Chat → Camera → *Infinite Recording cap* sets how long it can run before it stops itself: 10 (default), 15, 20, 30, 60 minutes, or Unlimited.

### Warning before a round video message hits its limit <!-- #video-limit-warning -->

Round video recordings warn you before they end. Configured via N-Settings → Chat → Camera, you get a light warning buzz 5 seconds before the cutoff, and a medium cutoff buzz when it actually lands.

### Smoother video message zoom <!-- #video-zoom -->

The zoom control under the round video camera has a full-range slider and step buttons. Turn on N-Settings → Chat → Camera → *Newer camera for video messages* (off by default) to reach the telephoto and ultrawide lenses, with tappable zoom stops like 0.6x, 1x, 3x and 6x above the slider. MediaTek phones stop at 1x.

### Front flash brightness for video messages <!-- #front-flash-brightness -->

Turn on the flash while recording a round video with the front camera and a pill grows up out of the flash button. Drag it to set how bright the screen flash is, from 30% to full. It starts at full and keeps your last setting for the next recording.

### Scrub the video message preview <!-- #video-scrub -->

The preview you get after recording a round video message has a playback cursor you can drag to scrub through the video.

<img height="90" alt="post-recording preview strip with a discard button, a filmstrip carrying a draggable playback cursor at its midpoint, and a send button" src="docs/images/features/video-scrub-preview.png" />

### Bigger recorder pause and once buttons <!-- #recorder-controls -->

The pause button and the view-once "(1)" toggle are larger and lifted slightly higher off the send button to prevent accidental sends.

### Video message gestures <!-- #video-hold-send -->

While a round video records hands-free or waits paused in its preview, tap it to pause or resume, or hold it until it buzzes and let go to send. Slide off to back out. While recording, swipe up or down to zoom and left or right to flip cameras. The hint above it shrinks after a few sends. N-Settings → Chat → Camera → *Video Message Gestures*, on by default.

### Don't lose an unsent video message <!-- #video-draft-guard --> <!-- #video-pause-grace -->

A round video message you've recorded but haven't sent survives backing out of the chat, switching apps mid-recording, or the chat locking behind a passcode — the clip waits in the preview, trimmed the way you left it, for up to a day. After a teardown it returns as the trim strip and send button, not the round preview. A pause under two seconds (wireless Android Auto) won't stop recording.

### Video and text memo shortcuts <!-- #video-note-shortcut --> <!-- #text-memo-shortcut --> <!-- #text-memo-photos --> <!-- #text-memo-big-video --> <!-- #memo-shortcut-picker -->

Turn on *Video memo shortcut* or *Text memo shortcut* under N-Settings → General → Launcher shortcuts (off by default) and long-pressing the icon offers *Video memo*, a hands-free round video on the chosen *Camera*, or *Text memo*, a message box with photos and videos up to 1 GB that keeps unsent text as the chat's draft. Both go to Saved Messages or your *Recipient*, even locked. Shortcut Maker-style apps list them too.

### Memo recipient status <!-- #memo-recipient-status -->

A text memo shows *online* next to its label, and an unread badge, when your *Recipient* is online or has messages you haven't read. A video memo recorded while the app is locked shows both at the top of the screen. Always on.

### Custom file names for saved media <!-- #custom-file-names -->

Turn on *Custom File Names* (N-Settings → General → Storage) to save videos, voice, and round messages under the message's send date and time — `20260101_173812.mp4` by default — instead of Telegram's generic `video.mp4`. The setting's dialog lets you customize the pattern with `{date}`, `{time}`, and `{name}` (the sender's original filename), with a live preview as you type. Saved photos are unaffected.

### Black corners on saved round videos <!-- #round-video-black-corners -->

Round videos you save to disk (select them, then *Save to downloads*) come out pure black outside the circle, instead of whatever corners the sender's app baked in. The saved copy is re-encoded; the one in Telegram stays as it was. N-Settings → General → Storage → *Black Corners on Saved Round Videos*, on by default, Android 10 and up.

### Send a muted gallery video as a real video <!-- #silent-video -->

Muting a video in the gallery editor before you send it used to force it into a low-quality looping GIF. Now the quality button stays live after you mute — tap it once and the clip becomes an ordinary silent video instead, sent at a quality you pick from the usual SD/HD sheet, with a real duration and scrubber. Unmute and mute again to go back to GIF.

## Transcription

### Whisper transcription controls <!-- #whisper-transcription -->

The Whisper (Workers AI) provider settings have options to skip silent audio and disable previous context to prevent repeated or made-up phrases.

### Groq transcription provider <!-- #groq-transcription-provider -->

A fast, free voice-to-text option that runs Whisper on Groq. Audio uploads have a 25 MB ceiling on the free tier.

### Retry or switch a transcription provider <!-- #transcribe-retry -->

If multiple providers are configured, the Retry option on a transcription becomes "Retry with…". You can also long-press the transcription button on a voice or round video message to stop a running attempt and open the provider list.

<img height="220" alt="Retry with... menu listing the configured transcription providers" src="docs/images/features/transcribe-retry.png" />

## Appearance

### Extera themes <!-- #extera-themes -->

Extera Light and Extera Dark bring [exteraGram](https://github.com/exteraSquad/exteraGram)'s look to DwellGram. The design is exteraSquad's. Only the palettes were rebuilt here — reverse-engineered rather than copied, and shipped as Monet-token theme assets — so the look, and the credit for it, stay theirs.

<img height="260" alt="chat rendered in the Extera Light theme" src="docs/images/features/extera-light.png" />
<img height="260" alt="the same chat rendered in the Extera Dark theme" src="docs/images/features/extera-dark.png" />

### Monet wallpaper pattern <!-- #monet-pattern-tile -->

You can apply a chat pattern over your live Material You color. Turn it off by opening the tile and clearing the pattern.

<img height="260" alt="chat background pattern tinted with the current Material You accent color" src="docs/images/features/monet-pattern.png" />

### Ribbon app icons <!-- #ribbon-icons -->

Three launcher icons drawn for the fork, not recoloured stock art: a strip folded into a chat bubble, with a cut end at the top right and a folded tail. *Ribbon* is amber on graphite, *Ribbon Dawn* warm cream, *Ribbon Amber* dark on amber. Pick one under Chat Settings → App Icon; all three carry a themed-icon layer. DwellGramX (Unofficial) starts on Ribbon Amber; DwellGram (Official) starts on Blue.

### Fixed app icon matches the build's default <!-- #app-icon-fallback -->

On DwellGramX (Unofficial), the app's fixed system-level icon — the one Android shows in the app switcher, permission dialogs and the notification header — is the *Ribbon Amber* art the build also starts on. DwellGram (Official) keeps its usual Blue icon. This icon is fixed at build time, so it does not follow your Chat Settings → App Icon choice.

### Centered chat title <!-- #title-pill-fix -->

The chat title and its status line sit centered in the action bar, in a pill that hugs the text rather than spanning the bar. Forum topics get the same rounded ends as any other chat. N-Settings → General → Interface Style → *Use Centered Title*, off by default, with options for everywhere, chats only, or settings screens only.

### Shorter last-seen time <!-- #seen-at -->

In English, a person's status reads **seen at 14:05** or **seen Oct 03 at 14:05** instead of "last seen …", leaving the chat title more room in the header. Always on.

### Pinned message and player in one row <!-- #combined-pinned-player -->

When music, a voice message or a round video plays in a chat with a pinned message, the two bars share one row: a shortened pinned message on the left, the player on the right, its progress line across both. Chat Settings → Combine pinned message with player, off by default.

### Chat photo header background <!-- #header-avatar-bg -->

A chat can show its photo behind the Material Design 3 chat header and pinned bar. The chat's ⋮ menu → *Header background* opens a sheet to position the photo and set opacity, blur, tint, gradient and scrim. Status bar icons follow the photo; *Header text* and *Pinned bar text* (also the player's) pick light or dark text per theme. Off by default; in Glass the sheet links to Interface Style.

### Stacked bulletins <!-- #bulletin-stacking -->

Another bulletin that appears while the Stash bar or the scheduling countdown is up sits above it instead of replacing it, and each bar can be swiped away on its own. Always on.

<!-- Retired entries, plus sync-reconciliation and superseded feature slugs that have no catalog entry of their own.
     The behaviour still ships; it is documented in README instead of here.
     Slugs kept so old commits stay greppable and the catalog check keeps passing. -->
<!-- #nagram-sync -->
<!-- #tab-outline -->
<!-- #dazegram-icons -->
<!-- #update-checks-off -->
<!-- #metadata-channel-off -->
<!-- #solid-themes -->
<!-- The Transcription entries above were split from one commit tagged #transcribe-retry;
     #whisper-transcription and #groq-transcription-provider are new slugs for that split. -->
