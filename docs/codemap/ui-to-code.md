# UI → code map

"When the user taps X, the code that runs is Y." Re-verify the citation
before relying on it — see the README.

## Interface Style writes the existing Liquid Glass flag and gates MD3 panels

NagramX Settings → General's Interface Style row opens
`InterfaceStyleActivity` from `NekoGeneralSettingsActivity`
(`NekoGeneralSettingsActivity.java:182-183`). The page does not own a second
style enum: `InterfaceStyleController.isMaterialDesign3()` derives MD3 from the
existing Liquid Glass setting and support gate
(`InterfaceStyleController.java:17-18`). The page writes Liquid Glass with
`LiteMode.toggleFlag(...)` and reloads the interface
(`InterfaceStyleActivity.java:485-491`).

The shared Blur strength row is visible in both styles because composer glass
already consumes it (`InterfaceStyleActivity.java:337-341,517-520`;
`ComposerGlassProvider.java:74-75`; `NaConfig.kt:1485-1489`). MD3-only rows are
visible only after the Material Design 3 radio is selected
(`InterfaceStyleActivity.java:341-360`). The Apply to rows expose Chat header,
Chat list top bar, Buttons, Composer, Bottom navigation, and Panel dividers;
the Composer row is assigned only while `COMPOSER_STYLE_AVAILABLE` is true,
which it now is (`InterfaceStyleActivity.java:346-348`;
`InterfaceStyleController.java:33-36`). Like every other Apply to row, its
stored default is on (`NaConfig.kt:1467-1471`). The Panel colors row, Match
Classic and Day header color, shows only on MD3 with Classic or Day selected as
the day theme and defaults off (`InterfaceStyleActivity.java:355-359`;
`NaConfig.kt:1491-1495`); its render path is the next entry.

Panel dividers are a shipped MD3 row: `NaConfig` stores
`interfaceStylePanelDividers`, and `InterfaceStyleController.panelDividers()`
gates the render paths (`NaConfig.kt:1497-1501`;
`InterfaceStyleController.java:48-50`; `InterfaceStyleActivity.java:353`).
It draws hairlines coloured as MD3 outline-variant (the local surface blended 12% towards `key_windowBackgroundWhiteBlackText`, `InterfaceStyleController.java:62-70`, because Night/AMOLED `key_divider` is pure black) at
Dialogs' captured top-surface bottom after `super.dispatchDraw(...)`, and under
ChatActivity's header group after `super.dispatchDraw(...)`
(`DialogsActivity.java:1177-1182`; `ChatActivity.java:19608-19616`). The
floating bottom navigation never meets content edge to edge, so it draws none.

Chat/action-bar surfaces use the existing account-aware
`topPanelChatActivity(...)` colour logic, but `ChatActivity` calls the
chat-only `chatHeaderPanel(...)` wrapper so MD3 can also remove glass
stroke/shadow without changing Community/Admin/SearchTags users of the generic
provider (`BlurredBackgroundProviderImpl.java:197-220`;
`ChatActivity.java:5394`). `ActionBar` still stays in glass mode for title and
status layout, but the chat-only setup flag makes MD3 draw the main glass
drawable full-bounds and suppress the separate back/menu pills
(`ActionBar.java:207,242-245`, `:2387-2464`).

Dialogs MD3 is owned by `DialogsActivity.ContentView`, not by each row.
`getDialogsTopSurfaceColorKey()` selects the pre-Glass theme role for normal and
Search modes, while `drawChild(...)` paints one translucent frosted surface
above scrolling rows and below the top controls when the RenderEffect source is
available; unsupported or blur-disabled paths retain the opaque fallback.
`updateContextViewPosition()` supplies its animated tab extent, trimmed by the
same `dp(5)` the list's top padding subtracts, and not by the search field's
tab lift, since rows start there in both states at rest (during animations the surface is the shorter of the two) (`DialogsActivity.java:2181-2194`)
(`DialogsActivity.java:592-601`, `:949-990`, `:6849-6918`). That trim leaves a
gap above a visible player or location panel: its flat background starts at the
panel's `dp(21)` top padding less its `-14` layout margin, 5 dp past the trimmed
surface with folder tabs and 7 dp without them. While a panel shows, the surface extends to the panel top instead
(`DialogsActivity.java:5077`, `:5885`, `:6909-6918`). The
search/folder rows do not install their own MD3 backgrounds; search-type tabs
keep only a full-bounds child clip, the search field keeps its rounded control
background, and the independently animated temporary panel reuses the same
dialogs provider (`DialogsActivity.java:4970-4991`, `:5072-5078`,
`:5422-5434`; `SearchTabsAndFiltersLayout.java:15-59`;
`BlurredBackgroundProviderImpl.java:75-95`).
Chat-side strips use the same chat-header flag through `ChatActivity` and the
component flat hooks (`ChatActivity.java:8629-8638`, `:9907-9913`,
`:11165-11168`, `:52168-52176`;
`ChatActivityTopPanelLayout.java:37-89`;
`DialogsActivityTopPanelLayout.java:36-82`; `TopicsTabsView.java:485-498`).
Pinned-message content uses explicit MD3 keylines at creation and at both
runtime image/no-image updates. Its close, list, progress and action states
also derive their drawn trailing edge from the header avatar keyline while
retaining the clickable icons' 36×48dp targets; Liquid Glass keeps the original
geometry (`ChatActivity.java:12723-12730`, `:12832-12955`,
`:30986-31014`).

Chat header and tag-search providers use the same shared blur-strength alpha
for translucent MD3 surfaces and fall back to opaque theme roles below the
RenderEffect/API/blur gate (`BlurredBackgroundProviderImpl.java:211-250`;
`NaConfig.kt:1589-1592`). The filter-tab tonal pill remains a separate
follow-up slice.

*(Established 2026-09-22, during `#interface-style`.)*

## Match Classic and Day header color: literal pre-12.4.0 colours, handed only to two consumers

12.4.0 (`918447ef5b`) made Classic's `key_actionBarDefault` white and its icons
black, so the old blue cannot come from any palette key.
`InterfaceStyleSolidHeader` holds the pre-12.4.0 values and a
render gate on `Theme.getActiveTheme()`, not `getCurrentTheme()`, which is the
day-theme choice and stays "Blue" under auto-night (`InterfaceStyleSolidHeader.java:78-88`;
`Theme.java:6586-6587,6621-6622`). Classic accents go through the palette's own
hue shift, `Theme.changeColorAccent(ThemeInfo, ...)` (`InterfaceStyleSolidHeader.java:122-129`).

Chat header: the surface comes from `chatHeaderSurface(...)`, used only by the
header and its status-bar composite (`ChatActivity.java:5406,19166`;
`BlurredBackgroundProviderImpl.java:211-213,229-230`). `chatHeaderPanel(...)` is
**not** header-private: the pinned/join strip and the hashtag and topic tabs use it
too (`ChatActivity.java:8658,9932,11213-11214`). Foregrounds go through
`ChatAvatarContainer.getThemedColor` for avatar containers whose `parentFragment`
is set, which only ChatActivity does (`ChatAvatarContainer.java:2040-2043`).
They are also pushed onto the ActionBar after `setupGlass` and again from
`selectedBackgroundDelegate` (`ChatActivity.java:5410,46490-46494`). The mute and
lock icons are shared static drawables, so the header gets private copies that pick
their colour as they draw (`ChatActivity.java:21781,21785`) and ignore outside tints:
a key-only description recolours the title's side drawables with the subtitle key
(`ChatActivity.java:46608`, `ThemeDescription.java:411`). The auto-delete timer draws its ring and label
from `key_actionBarDefaultTitle` through its own provider (`TimerDrawable.java:160,168`),
so it gets a scoped one (`ChatAvatarContainer.java:456-457`). A provider-level hook on `themeDelegate` is
the wrong chokepoint: `MessagePreviewView` reads the same title keys through it.
All of these route through `HeaderBgForeground` (`#header-avatar-bg`), which resolves
Classic first and then, when the chat's header photo has a *Header text* or *Pinned bar text*
set for the current theme (light or dark), swaps in that neutral set. Lookups read the set last pushed, never the drawer's
live wish, so reads between pushes match the screen. The pinned strip's text and
buttons are coloured once when the strip is built and then only by ThemeDescriptions,
never as the pinned message changes, so the strip hands its views over at the end of
that build (`ChatActivity.java:13123`).

Anything painted *behind* the MD3 chat header but under its title and icons goes
in ActionBar's flat branch, right after `glassDrawable.draw` and before
`super.dispatchDraw` (`ActionBar.java:2395-2399`); the per-chat photo background
(`#header-avatar-bg`) hangs off that as the `naxHeaderBg` field. Glass never
takes the branch. Two traps there: `rebuildAllFragmentViews` swaps in a new
ActionBar while the ChatActivity lives on (`ActionBarLayout.java:3366-3381`), so
per-header state belongs to the ActionBar's attach/detach, not the fragment; and
the action-mode bar is created INVISIBLE at alpha 1 (`ActionBar.java:883`), so
`getActionModeFactor()` (`:811`) reads 1 until selection mode has been shown once.
The pinned/call/translate strips under the header are one `ChatActivityTopPanelLayout`,
a sibling of the ActionBar in `contentView` (`ChatActivity.java:8663-8665`); its
equivalent spot is after `backgroundDrawable.draw` (`ChatActivityTopPanelLayout.java:141`),
in panel coordinates, so map to the header with the two views' `getX/getY` difference.
Under the photo the strip paints the header's surface, not its own, or the Classic blue
header and white strip split the photo into two tints; its auto text then follows the
Classic header too (`HeaderBgDrawer.java:330-333,486-493`, `HeaderBgForeground.java:102-107`).
Its status bar icons are decided in `ChatActivity.isLightStatusBar()` (`ChatActivity.java:48406`),
which falls back to `shouldHaveLightStatusBarIcons`, set from the wallpaper and header composite
(`:19174`); `LaunchActivity.checkSystemBarColors` (`LaunchActivity.java:1152`) re-asks the top fragment,
so anything that changes the pixels behind the status bar calls that.

Chat list: DialogsActivity has no provider, so its `getThemedColor` override
maps the top-bar keys (`DialogsActivity.java:593-607`). The title keys are left
out because the item-options popup reads `key_actionBarDefaultTitle` through the
same method (`DialogsActivity.java:14115`); titles are pushed by
`applyChatListTopBar` from createView and `cellDelegate`
(`DialogsActivity.java:5664,12584`). FilterTabsView gets a scoped provider and
DialogStoriesCell, which otherwise bypasses the fragment when its provider is
null, routes through it (`DialogsActivity.java:3752`;
`DialogStoriesCell.java:2249-2256`). My Story's "+" disc is `key_telegram_color`, so the stories table maps it to white (`DialogStoriesCell.java:1937`; `InterfaceStyleSolidHeader.java:71`). The frosted branch is skipped and the
paint follows the search blend (`DialogsActivity.java:962-967,977-978`), and
`isLightStatusBar()` follows the surface, or white while searching (`:13182-13185`). The main list is a tab of MainTabsActivity, which asks the visible tab only once it has a view and otherwise falls back to its own white action bar (`ViewPagerActivity.java:217-219`), so createView asks for a re-check (`InterfaceStyleSolidHeader.java:204-211`). The NagramX title span and the ActionBar subtitle overlay read the logo key through their own providers, so both get the scoped one (`TypefaceHelper.java:161-162`; `DialogsActivity.java:3271-3272`). The downloads icon is a child view drawn from `Theme.getColor` with no provider, so `setItemsColor` never reaches it and it maps its own colour (`DownloadProgressIcon.java:63-67`).

Selection mode keeps its 12.4.0 look. In chats the MD3 action mode is drawn straight on the header surface, so `chatHeaderSurface` stays themed while action mode shows, and ActionBar refreshes its cached glass colour on show and hide (`ActionBar.java:937,1110`). In the chat list the tabs switch to `profile_tab*` keys meant for white (`DialogsActivity.java:10463`), so the surface blends to white by `progressToActionMode` as it does for search (`:600`), and frosts again while selecting, matching the header above it (`:967`).

Both fragments need the re-push because their header ThemeDescriptions are
key-only: `ActionBarLayout.setThemeAnimationValue` writes every description
from the shared palette, then runs the delegates (`ActionBarLayout.java:3100-3131`).

*(Established 2026-09-25, during `#interface-style`.)*

## MD3 chat-list filter tabs strengthen only FilterTabsView's selected pill

`FilterTabsView.drawSelector(...)` is the folder-tab row's selected-indicator
draw path. The MD3 chat-list top-bar gate leaves the existing 28dp stadium,
theme colour, sizing and animation in place, but raises the borderless selected
pill alpha through `InterfaceStyleController.filterTabSelectorAlpha(...)` when
`applyChatListTopBar()` is true; `tabStyleStroke` still keeps the existing
full-opacity stroked chip (`FilterTabsView.java:1644-1663`;
`InterfaceStyleController.java:17-26,50-55`).

Dialogs search tabs opt into the same alpha through their own
`ViewPagerFixed.TabsView` flag. The flag defaults off, so the shared bubble
selector keeps hashtag/search and bookmark tabs on their existing
`tabStyleStroke` behavior (`DialogsActivity.java:7824-7826`;
`ViewPagerFixed.java:1509,1545-1546,2066-2082`). Other tab strips keep their
own `tabStyleStroke` behavior independently (`ScrollSlidingTextTabStrip.java:772-777`).

*(Established 2026-09-22, during `#interface-style`.)*

## Bottom navigation floats as an MD3 panel through the Liquid Glass pill's own slot

The Bottom navigation setting reaches the real global navigation through
`MainTabsActivity.createView()` and the shared geometry helper
(`MainTabsActivity.java:352-374`, `:438-472`; `MainTabsHelper.java:14-36`).
MD3 reuses the Liquid Glass attachment: the background sits on `tabsView`,
drawn `getMainTabsMargin()` in from it, so the panel is 64dp tall with or
without titles. That margin is 12dp under MD3 (`MainTabsHelper.java:110-113`),
and every consumer already measures to it: list padding, the Dialogs FAB,
bulletins and the blur-capture rect (`MainTabsActivity.java:221-223`;
`DialogsActivity.java:3118-3119`, `:14727-14728`), so they clear the lifted
panel with no code of their own. Negative side margins put the panel 9dp from
the screen, capped at the pill's 344dp; with few tabs it narrows so the edge
indicators keep the same gap from the panel's side as from its top
(`MainTabsHelper.java:72-97`; `MainTabsLayout.java:191-204`). With titles the
label is fixed at the 10sp pass, 2dp under a 56×32 indicator
(`MainTabsLayout.java:148-149`; `GlassTabView.java:155-164`, `:175-182`).
Panel, long-press card and indicator are rounded rectangles of 18, 14 and 10dp,
each 4dp inside the one around it, so they nest at the edge tabs
(`MainTabsHelper.java:28-36`; `MainTabsActivity.java:469`). A stadium around
the 56×32 icon-only indicator, inside a near-square card, read as ovals on
device and was dropped for these (`4b6b7c61da`). Tabs are padded symmetrically so ItemOptions,
which centres the card on the tab, centres it on the panel
(`MainTabsActivity.java:1511-1533`). There is no display-corner nesting: by
geometry, 18dp corners 12dp above the inset clear a display corner up to about
44dp even with no nav inset, but that is not device-checked.
`mainTabsBottomNavigation()` frosts at the Blur strength alpha while blur is
enabled for the account and is opaque otherwise, with the composer's shadow and
a dark-only light edge that `BlurredBackgroundDrawable` hides with the Glare
switch like every other stroke (`BlurredBackgroundProviderImpl.java:37-55`;
`BlurredBackgroundDrawable.java:701-702`). `mainTabs()` stays the attach and
statistics provider. The wrapper passes touches in the side gaps through to the
list (`MainTabsActivity.java:438-448`). Liquid Glass keeps its stadium pill and
tab geometry.

Rounded navigation, an MD3 sub-toggle shown only while Bottom navigation is on
(`InterfaceStyleActivity.java:350-352`; `InterfaceStyleController.java:43-46`),
keeps this slot, lift and provider and changes shape and labels. Its whole-tab
highlight is a different stadium from the dropped one. The panel radius
becomes half its height, tabs abut 4dp inside it at 80dp each within the same cap
(`MainTabsHelper.java:50-52`, `:76-79`, `:86-88`), and a tab-wide stadium one
inset inside the panel is the highlight, the long-press card and the drag
selector (`MainTabsLayout.java:98-111`, `:360`, `:386`;
`MainTabsActivity.java:1521-1528`). The layout draws that highlight itself and
slides it from the current page's tab towards the next page's by the current
page's offset, clamped to one page (`getPositionAnimated()` turns back on an
overdrag), on taps and swipes, resting on the selected tab once the pager stops; `GlassTabView`
draws none in this mode (`MainTabsActivity.java:833`, `:864`, `:992-1021`;
`MainTabsLayout.java:392-393`; `GlassTabView.java:170`). Titled tabs stay 48dp,
so the highlight reaches 4dp past them each way. It is drawn before
`super.dispatchDraw`, because a tab drawing past its own 48dp is cut flat by
`tabsView`'s default `clipToPadding` whatever `clipChildren` says, as the first
build showed. Icon-only tabs grow to 56dp so all of it is tappable
(`MainTabsHelper.java:55-60`). Labels keep the glass position and larger
passes, in regular weight, medium when selected (`GlassTabView.java:160`,
`:276-283`; `MainTabsLayout.java:149`).

*(Established 2026-09-22, during `#interface-style`; rounded navigation 2026-09-26, `#rounded-navigation`.)*

## Interface Style's Main Tabs preview is the real bar, built outside MainTabsActivity

The preview row under Interface Style → Main Tabs is `MainTabsPreviewCell`, the
real `MainTabsLayout` and `GlassTabView`s configured as `MainTabsActivity.createView()`
configures them (`MainTabsPreviewCell.java:48-72`, `:74-119`; `MainTabsActivity.java:352-374`,
`:453-472`). None of the three needs the activity: `MainTabsLayout` takes a context
and a resources provider, the tabs come from `GlassTabView.createMainTab`/`createAvatar`,
and the observers, counters and badges all live in the activity. Leaving the factory's
`setSourceRootView` unset means no `ViewPositionWatcher` is subscribed. The backdrop
is a `windowBackgroundWhite` colour source, the colour the real bar's glass source paints
first (`MainTabsActivity.java:185`, `:217`): the bar never sits over the wallpaper.
A colour or bitmap source gets no refraction, which is applied only to
`BlurredBackgroundDrawableRenderNode` (`BlurredBackgroundDrawableViewFactory.java:96-102`),
so in the preview Liquid Glass and MD3 with Bottom navigation off draw near-identical pills.

Two traps shaped it. A Chats tab plays its fill animation whenever it is first
selected, `animated` or not (`GlassTabView.java:430-442`), so the cell is built once and
`update()` pushes settings through the bar's own setters; rebuilding per bind replays it
on every toggle. `rebuildFragments(0)` skips the top fragment (`ActionBarLayout.java:3374-3376`),
so Hide titles and Hide contacts rebind the page themselves (`InterfaceStyleActivity.java:434-436`).

A matching hero (chat header, list row, composer) was rejected: `HeaderBgDrawer` is
private and `ChatActivity`-bound (`HeaderBgDrawer.java:108`), `ChatActivityEnterView` needs a
`ChatActivity` (`ChatActivityEnterView.java:2798`), and every surface provider reads the
`InterfaceStyleController` statics, so showing the unselected style means duplicating them.

*(Established 2026-10-04, during `#interface-style`.)*

## Interface Style's Folder preview is the real FilterTabsView with made-up folders

The row under Interface Style → Folder is `FolderTabsPreviewCell`, a real `FilterTabsView`
built with the same scoped provider as the chat list (`FolderTabsPreviewCell.java:52`;
`DialogsActivity.java:3749`). It needs no fragment or `DialogFilter`: `addTab` takes plain
values (`FilterTabsView.java:1417`). Two traps. The delegate is dereferenced unchecked while a
tab is added (`FilterTabsView.java:152`), so a stub goes in before the first `addTab`
(`FolderTabsPreviewCell.java:57`). A `Tab` blanks its title for the Icon type when created
(`FilterTabsView.java:140`), so a title type change rebuilds the tabs and only the stroke
setting can be a redraw (`FolderTabsPreviewCell.java:106-136`). Never call `toggleAllTabs`: it
reads the real folders of `UserConfig.selectedAccount` (`FilterTabsView.java:2219`). Glass gets the
blurred pill, flat MD3 a plain surface (`FolderTabsPreviewCell.java:122-130`;
`DialogsActivity.java:5417-5423`).

*(Established 2026-10-04, during `#interface-style`.)*

## Interface Style rows with no real-view preview: action bar, chat decoration, Apply to, blur

Four rows have no preview because the real surface cannot be hosted without a fragment.
The action-bar decoration is painted in `ActionBar.drawChild` against the title view's text
bounds (`ActionBar.java:462`, `:480-484`, `:495`), so only a look-alike is possible, and the
page's own bar already shows it. The chat decoration is drawn on the chat's backdrop by
`SizeNotifierFrameLayout.checkSnowflake` (`SizeNotifierFrameLayout.java:562-569`), again
look-alike only. The "Apply to" group restyles five different surfaces with no single view, and
the two that can be hosted already have previews. Blur strength is a live `SlideIntChooseView`
(`InterfaceStyleActivity.java:79`, `SlideIntChooseView.java:48`), and a preview needs content
scrolling behind a blurred surface. A switch or slider style preview duplicates the
`ConfigCellTextCheck` switches and the blur seekbar the page already draws, since both read
the style on every draw (`Switch.java:625-626`, `SeekBarView.java:458`).

*(Established 2026-10-04, `#interface-style`; costed by recon, nothing built.)*

## MD3 Composer paints one island from ChatInputViewsContainer

The Composer Apply to switch reaches the real chat through one draw hook.
`ChatActivity` gives its own `ChatInputViewsContainer` a `ComposerMd3Surface`
only while `applyComposer()` is true, attaches its frost, and binds the enter
view, the channel buttons and the selection bar to it (`ChatActivity.java:5413-5416`,
`:9361-9363`). `dispatchDraw` then hands the frame to the surface instead of
drawing the island and under-keyboard glass. It still sets both drawables'
bounds, because the in-app keyboard clip and touch capture read them, and touch
capture also covers the painted island (`ChatInputViewsContainer.java:43`,
`:338-357`, `:360-368`, `:461`). `DialogsActivity` and `GiftMessageBottomSheet` never get a
surface, so they keep their glass.

The surface paints one island: the 44dp text row and the tools row
plus 2dp all round, radius 13dp, with a soft shadow in
`key_chat_messagePanelShadow` and, in dark themes, a faint light edge
(`ComposerMd3Surface.java:81`, `:402-709`). It sits 9dp from each screen side;
its children are padded 4dp in from the container, so the stock 38dp send
circle clears the island's side and top by the same 5dp (`:96-100`). The channel
run, the selection bar and the plain pill (search, the bottom overlay text)
each give the island a rect, and the cross-fade weights ease between them
(`:430-466`). Resting on the nav bar it docks as a sheet: it keeps its width
and top, runs down to the screen's bottom edge and its bottom corners go
square, so no edge crosses the display's rounded corners. `dockFactor()` measures the lift as the container's bottom inset less
the resting system-bar and cutout inset `WindowInsetsStateHolder` builds it from.
The shape switches between sheet and island on a 300ms ease-out `AnimatedFloat`
with hysteresis: it starts floating about 5dp into the lift, so the morph
plays alongside the keyboard, and docks again halfway back down that 48dp
(`ComposerMd3Surface.java:326-344`, `:472-478`; `WindowInsetsStateHolder.java:91-92`). Like the MD3 chat header it is
frosted when the chat has a frosted source and blur is enabled for the account,
keeping 70% of the Blur strength setting, and opaque otherwise (`:183-249`). It
gets its own factory over that source because the shared frosted factory can
hand out drawables with the Liquid Glass shader
(`BlurredBackgroundDrawableViewFactory.java:81-85`).

The *Composer style* select (Floating / Docked) sits at the top of Composer Toolbar, only while the
MD3 composer applies (`ComposerLayoutActivity.java:391-395`, `:842`); it rebuilds the preview and leaves the chats
to the reloadInterface posted on leaving. `InterfaceStyleController.dockedComposer()`
(`InterfaceStyleController.java:40-41`) is read once in the surface's constructor (`ComposerMd3Surface.java:161`).
Docked there is no morph: `sheetShape` is never created, `liftAboveStock()` returns a constant 2dp above the
nav bar or keyboard (`:352`, `:375-377`), and `reachAboveStock()` goes negative because the panel sits below
the stock pill (`:383-385`). `draw()` hands off to `drawDocked()` (`:467`, `:565`), which keeps the weighted
run rect for the channel and selection tone and widens the panel to the container's full width, down to just
under the keyboard top, by the resting nav inset the keyboard hides (`keyboardShare()`, `:332`). Field, strip
and nested selection buttons use 12dp (`:113`); the top gap grows to 5dp and the children's side padding drops
to 2dp (`:120-122`).

Floating, MD3 adds 2dp to the stock 9dp lift, so the island clears the
keyboard and emoji panel by the same 9dp as the screen sides; in the Floating style's resting sheet,
`liftAboveStock()` eases the tools row down to 4dp above the nav bar (`ComposerMd3Surface.java:104-106`,
`:349-358`, `:363-365`; `ChatInputViewsContainer.java:272-275`;
`ChatActivityEnterView.java:18923-18928`). The island reaches `topOverhang()`
above the pill (`ComposerMd3Surface.java:368-370`), so `getInputBubbleHeight()`
adds it and everything laid out against the composer, including the message
list padding, clears it (`ChatInputViewsContainer.java:284-291`;
`ChatActivity.java:13503-13505`). The expanded input's budget takes off the
extra lift, that reach and the pinned panel's height, less headroom the stock
budget already leaves, and follows the pinned panel's animation. The round-video
recorder's bottom chrome and its overlay add the same reach
(`ComposerMd3Surface.java:379-385`, `:391-392`; `ChatActivity.java:10042-10045`, `:4852-4855`,
`:20293-20295`, `:20309-20311`).

The pull-to-next hint ("Pull up to go to the next unread channel") is text only,
centred between the two `top`/`bottom` values it is given
(`ChatPullingDownDrawable.java:752`). The pull fades every bottom view out
(`ChatActivity.java:52343-52345`), and the island collapses to the plain pill.
With the composer toolbar on, `inputBubbleHeight` still counts the tools row, but
the pill as drawn leaves it out (`ChatInputViewsContainer.java:347`). The hint
therefore takes its bottom from `getInputBubbleDrawnBottom()`, not
`getInputBubbleBottom()`, or the text lands on the pill's bottom edge, in Liquid
Glass as well as MD3 (`ChatInputViewsContainer.java:304-306`; `ChatActivity.java:20082`).

Which channel the pull goes to comes from `getNextUnreadDialog`: the current
filter's (or folder's) list first, then, with `searchNext`, every other filter
and every other folder including the archive (`ChatPullingDownDrawable.java:685-713`).
The folder context travels with the pull through the `dialog_folder_id` /
`dialog_filter_id` bundle args, so a cross-folder hop moves the next pull there
too (`ChatActivity.java:12583-12584`). *Swipe to Next Within Folder* turns
`searchNext` off in the drawable's lookup (`ChatPullingDownDrawable.java:165`).

Channel and selection runs tone the island itself (`ComposerMd3Surface.java:518-523`).
With MD3 Buttons on, the selection bar's Reply and Forward instead become the run's
fields: an opaque field tone at an 11dp radius, 2dp inside an untoned island, set once at
`bind` through `ChatActivityBlurredRoundButton.setNestedBackground`
(`ComposerMd3Surface.java:180`, `:801-839`; `ChatActivityBlurredRoundButton.java:246-259`).
Opaque, because a translucent drawable fill shows its own wallpaper blur, not the island.
Nested, they fade in place rather than fly in from outside the island
(`ChatActivityActionsButtonsLayout.java:183-184`).
The input gets a 42dp field with a concentric 10dp radius, an on-surface tint at 8% (14% in
dark themes) laid over the island like the reply strip's 6%, so the frost shows
through both, and no focus ring (`:54-59`, `:262-265`, `:693-696`). The recorded-preview
trash is a Lottie whose stripes are recoloured by layer name to `key_chat_messagePanelBackground`
to fake holes, and its resting stripes share those names, so stock it rests as a hollow can.
A frosted field has no single colour to match, so on the field they read as bars; under MD3 the
chat's own enter view paints stripes and can in one composer icon tint, a plain striped glyph
(`ChatActivityEnterView.java:13306-13308`; `ComposerMd3Surface.java:781-795`;
`res/raw/chat_audio_record_delete_2.json`, precomp `Box Grey`). The reply strip shares its column, is centred
on the upstream close button and carries its accent as a separate rounded bar
(`:678-689`); under MD3 the close button draws without its glass disc
(`InputSatellites.java:97-102`). In a channel the Join/Mute fill was never the
Buttons provider's. It is the island glass shrunk to the button run by
`setInputBubbleOffsets` (`ChatActivity.java:9356`), because
`ChatActivityChannelButtonsLayout.setupDrawableForContainer()` (`:155`) is only
called from `ChannelAdminLogActivity.java:1511`. Dropping the island glass
without painting that run is what blanked channel chats in #409. The reply strip
stops short of the send column using the existing `getComposerPrimaryEndInset()`, on
the left under `LocaleController.isRTL` (`ComposerMd3Surface.java:630-646`;
`ChatActivityEnterView.java:19196`). The field no longer does: under both MD3 styles the
send column sits at the end of the tools row (next section). The reply strip reads `getTopViewHeight()`
and `getTopViewEnterProgress()` (`ComposerMd3Surface.java:427-428`), while the
reply content is placed against the enter view's laid-out height; see
upstream-traps, "The enter view's measured height can drift". The circle keeps its stock 44dp slot, but a press in the 2dp band around it is shifted into the slot for the whole gesture, giving a 48dp target (`ComposerMd3Surface.java:287-317`; `ChatInputViewsContainer.java:440-449`).

The tools row keeps its Liquid Glass geometry unless MD3 is on. Then size runs
the row, cell, state layer and glyph linearly through 40/48/56dp rows at
75/100/125%, cells are packed 4dp narrower than the row, and groups sit 8dp
apart (`ComposerToolbarLayout.java:71-77`, `:419-432`, `:438-463`, `:482-491`).
The Scrolling zone keeps fixed cells, fades its edges inside the viewport and
scrolls normally: fitting its cells to the viewport made smaller spacing look
looser (`:131-132`). A 24dp hairline seams it from the leading group, and from
the trailing group while it overflows (`:1100-1127`). `attachGlass` still clears
the bubbles under MD3, with the island painted behind them (`:226-229`). The
layout editor draws the same frosted island and tonal field
(`ComposerLayoutActivity.java:1474-1476`, `:1333-1334`). Buttons-role surfaces
keep their own provider (`ChatActivity.java:4202`).
*(Established 2026-09-22, during `#interface-style`.)*

## MD3 composer folds into one row at rest

`ComposerInlineRest` owns it, one per chat enter view under MD3 (`ComposerInlineRest.java:69`,
created at `ChatActivityEnterView.java:2940`). Under both styles the send column sits at the end of
the tools row, centred on it (`ComposerInlineRest.java:110`, `ChatActivityEnterView.java:4395`), and the
toolbar's end padding reserves it (`ComposerToolbarLayout.java:845`). Both styles fold: with the field
empty and no keyboard, panel, fullscreen input or rich draft, the field moves into the tools row between the
leading and trailing zones and the Scrolling viewport goes INVISIBLE at width 0, its buttons untouched
(`ComposerInlineRest.java:159`; `ComposerToolbarLayout.java:835`, `:974`). The decision rides
`checkBotMenu()`, which every text, keyboard and panel change already calls (`ChatActivityEnterView.java:5150`),
and `applyComposerTextGeometry()` hands it the field (`:11306`). A row that would leave the field under 96dp,
or a send-as avatar or bot pill at the head of the Scrolling zone, keeps two rows (`ComposerInlineRest.java:33`,
`:268`; `ComposerToolbarLayout.java:170`).

The fold has no clock. The margin flip re-measures the edit container, `animatorInputFieldHeight` eases the
island, at 450ms ease-out for a fold instead of its stock 250ms (`ComposerInlineRest.java:38`, `:118`;
`ChatActivityEnterView.java:2871`), and the field's draw offset (`:2911`), the tools inset
(`:19185`) and the surface's field tone (`ComposerMd3Surface.java:676`, `:696`) all read that animation's
remaining share (`ComposerInlineRest.java:206`). The morph must not end inside that frame's height tick: the
`#fullscreen-input` history pin reads `ComposerInlineRest.morphing()` further down the same synchronous call
(`ChatActivity.java:13633`), and ending first let it arm on the last frame and hold history still against the
keyboard. It ends in `settleIfDone()`, after `checkUi_IslandTotalHeight()` (`ChatActivityEnterView.java:19303-19306`;
`ComposerInlineRest.java:227`, `:232`). Docked needs nothing of its own: the panel takes its top from the
island's and keeps its bottom on the keyboard or screen edge (`ComposerMd3Surface.java:570`), so it folds from the
top. A record, review or bot panel replacing the tools row is a third layout the fold does not model: the fold
holds still while it is up, since a flip on the GONE field would move no height and only run out on the timeout
(`ComposerInlineRest.java:159`), the enter view re-decides once when the row returns (`ChatActivityEnterView.java:12586`),
and the surface draws the field tone and send slot from the column geometry meanwhile
(`ComposerMd3Surface.java:635-636`). The layout editor preview does not show the fold yet.
*(Established 2026-10-24, `#composer-inline-rest`, PR 549.)*

## MD3 Buttons reaches buttons through shared providers and targeted hooks

The Buttons switch is read by two shared providers and a few targeted hooks, all flat under
`applyButtons()`: opaque own theme colour, no stroke, no shadow.
`ComposerGlassProvider` ROLE_BUTTON (`ChatActivity.java:4202`) feeds the side,
action and channel buttons and the round-video recorder, which forwards it to
its flash/infinite row and zoom chips (`ChatActivity.java:12068`;
`InstantCameraView.java:418-447`, `:489-494`). The recording lock and view-once chips build
their own `Md3ButtonColorProvider` over `key_chat_messagePanelVoiceLockBackground`
(`ChatActivityEnterView.java:1839`); the deleted-messages and bookmarks screens
give one to their page-down button over `key_chat_messagePanelBackground`
(`AyuViewDeleted.java:503`; `BookmarksActivity.java:486`). It skips `BlurredBackgroundProvider`, so
Liquid Glass keeps the drawable's default stroke and shadow. The glyphs share
`key_glass_defaultIcon`, the side buttons' tint (`ChatActivityBlurredRoundButton.java:169`);
the recorder's hardcoded white/grey glyphs switch to it through
`Md3ButtonColorProvider.glyphColor` (`InstantCameraView.java:667-670`;
`Md3ButtonColorProvider.java:58-60`). The Dialogs story/camera sub-FAB, the
only `isSubButton` `FragmentFloatingButton` (`DialogsActivity.java:4999`), keeps
its opaque fill and drops its own stroke and shadow in place
(`FragmentFloatingButton.java:80-92`); `Md3ButtonColorProvider.elevate` gives
it the main FAB's 0.5dp `translationZ` (`:72-75`), outlined to the drawable's
padded circle rather than the 48dp bounds (`:100`;
`Md3ButtonColorProvider.java:67-72`). EmojiView's backspace, search, type-tab
and sticker-settings buttons all take `BlurredBackgroundProviderImpl.emojiViewButton`
from the view's own constructor, so every host gets them
(`EmojiView.java:2916`, `:2942-2970`); that provider goes opaque
`key_windowBackgroundWhite` with no stroke or shadow in place
(`BlurredBackgroundProviderImpl.java:97-110`).

Story controls get their own `Md3ButtonColorProvider` from
`PeerStoriesView.buttonColorProvider()` (`PeerStoriesView.java:8557-8562`), same key
and 0.8 alpha as the shared provider at `:549`, which stays glass because it
also draws the reply field and emoji keyboard (`:565-566`). The comment,
paid-reaction, mute and side-control buttons take it (`:3089`, `:3099`, `:3122`,
`:3695`). The first three paint a hardcoded opaque `0xFF20242A`, which the paid
reaction blends to gold as it fills (`PaidReactionButton.java:448-452`), and read only
the stroke from the provider (`CommentButton.java:60-61`; `StrokeDrawable.java:51-55`),
so for them MD3 just drops the stroke; the side controls go opaque
`key_chat_messagePanelBackground`, which the stories' dark provider sets to that
same `0xFF20242A` (`DarkThemeResourceProvider.java:90`). No glyph needs
moving: the comment and mute `0xFFD2D3D4` (`CommentButton.java:68`;
`MuteButton.java:75`) is exactly the stories' `key_glass_defaultIcon`, 80% white
(`DarkThemeResourceProvider.java:89`), composited over that fill. Still glass:
PhotoViewer's zero-fill rings (`BlurredBackgroundProviderImpl.java:333-344`).
*(Established 2026-09-25, during `#interface-style`.)*

## BottomBuilder section cards are opt-in and isolated to Early Send

`BottomBuilder` now has a fourth constructor arg `sections` defaulting `false`,
so existing callers keep the old layout path by default
(`BottomBuilder.kt:32-43`). In default mode, root/content behavior stays the
same (`LinearLayout` root under a plain `ScrollView`); in sections mode only,
the builder swaps to `SectionsLinearLayout` + `SectionsScrollView`
(`BottomBuilder.kt:45-67`).

Section-mode-only chrome is also gated there: the button strip is tagged
`RecyclerListView.TAG_NOT_SECTION`, the strip background is gray, and
`create()` applies gray sheet/nav-bar background (`BottomBuilder.kt:70-82`,
`:300-306`). In sections mode, `addTitle(...)` wraps the builder-owned
`HeaderCell` in a full-width `FrameLayout`; the inner `HeaderCell` is tagged
out while the wrapper remains the section member (`BottomBuilder.kt:107-122`).
`EventScheduleHelper` is the only caller opting in:
`new BottomBuilder(context, true, Theme.getColor(Theme.key_windowBackgroundGray), true)`
(`EventScheduleHelper.java:656`).

The wrapper's own bottom padding is what controls the card's bottom inset, never a
margin: `SectionsScrollView.drawSectionBackground` only reads a **margin** on the
non-content-view side of a section run (`SectionsScrollView.java:146-148`,
`:150-155`), so a margin on the wrapper's own trailing edge (`bottomMargin`,
`BottomBuilder.kt:120-122`) never reaches the rounded rect — only the wrapper's
*measured height*, padding included, does. That's why the 15dp bottom inset added
under `EventScheduleArmed`'s subtitle (`BottomBuilder.kt:118`) is `headerContainer`
padding, not a `HeaderCell` margin: `HeaderCell.setBottomMargin(...)` writes
`bottomMargin` onto **both** the title and subtitle `LayoutParams`
(`HeaderCell.java:137-142`), stacking on top of the subtitle's existing 4dp
`topMargin` (`HeaderCell.java:101`) and moving the title→subtitle gap that was
never meant to change.

*(Updated 2026-09-08.)*

## The composer Send button has two long-press menus, not one

`ChatActivityEnterView.onSendLongClick` (`ChatActivityEnterView.java:5763`)
branches on `isStories || (empty text && a pending forward is attached)`
(`ChatActivityEnterView.java:5768`). When true, it builds a cached
`ActionBarPopupWindow`/`ActionBarMenuSubItem` popup (`sendPopupLayout`,
built once and reused across long-presses). Everything else — ordinary typed
text in an in-app chat, which is what most users hit — falls through to a
second, completely separate menu built fresh every time from
`ItemOptions`/`MessageSendPreview` (`ChatActivityEnterView.java:6199`
onward). The two duplicate the same three rows (schedule, send-when-online,
silent) with near-identical eligibility conditions computed independently in
each branch — a change to the ordinary composer's long-press menu only needs
the `ItemOptions` branch; the `ActionBarPopupWindow` branch only fires for
Stories or an empty-caption forward-in-progress, both edge cases relative to
"the composer's Send button" as most features describe it.

*(Updated 2026-09-17.)*

### Both branches now arm all three per-action rows, not just `ItemOptions`

`#remember-send-action`'s master switch is inserted into **both** branches of
`onSendLongClick` (starts `ChatActivityEnterView.java:5763`) — the `ItemOptions`
menu (built from `ItemOptions.makeOptions` at `:6196`) and the cached
`sendPopupLayout` popup. The three per-action rows (schedule/send-when-online/
silent) also now arm in **both** branches: the cached popup's three pre-existing
buttons (`actionScheduleButton`, `sendWhenOnlineButton`, the plain
send-without-sound row) originally only called `sendMessageInternal(...)`, with
no `arm()` call at all — a scope decision from the feature's first round, on
the reasoning that this popup was an edge case (Stories/empty-caption-forward
only). That gap became a real defect once the master toggle started appearing
in this same menu (round above): a user picking Schedule from here reasonably
expects the pick to be remembered like the other menu, and it silently wasn't.
Fixed by adding the identical `canArmRememberedAction(action)` +
`RememberedSendAction.arm(...)` + `updateSendButtonArmedState()` sequence to
each of the three cached-popup handlers, gated exactly like their `ItemOptions`
counterparts (silent re-reads `SilentMessageByDefault` live at click time, so its
arm decision — and the row's label/icon, refreshed in the same on-open block as
the master switch above — can't go stale against a config change made while
this popup instance stays cached across opens; the "send with sound" direction
means it must not arm SILENT).

The two branches remain otherwise **not equivalent**: the cached popup's
schedule handler still drops `scheduleRepeatPeriod` (passes `0`, pre-existing,
out of scope) where `ItemOptions`'s passes it through, and the cached rows still
have **no checked state and no tap-to-disarm** — arming from there is possible,
but clearing an armed slot from that menu is only ever the master switch's
`disarmAll()`, never a re-tap on the same row. That's a deliberate, stated
asymmetry (adding checked state to a popup built once and reused across opens
is real scope growth), not an oversight — see the comment at the master row's
click handler.

*(Updated 2026-09-17.)*

### The Remember master toggle is the one thing deliberately wired into both

`#remember-send-action`'s master switch is inserted into **both** branches of
`onSendLongClick` (starts `ChatActivityEnterView.java:5763`) — the `ItemOptions`
menu (built from `ItemOptions.makeOptions` at `:6196`) and the cached
`sendPopupLayout` popup — while the three per-action rows
(schedule/send-when-online/silent) now arm in both branches too, per the
section above. The two branches do **not** share one master-row implementation: the
`ItemOptions` branch calls `createRememberMasterRow` (`:19341`), a helper built
around `ItemOptions`/`MessageSendPreview` dismissal; the cached popup builds its
own row directly off `createPopupSwitchRow` (`:5806`) and wires its own
click/long-click handlers inline, because dismissing that popup is a plain
`sendPopupWindow.dismiss()`, not the other branch's `messageSendPreview`
teardown. Changing one branch's master-row behavior does not touch the other's
code path. Arming itself is gated separately, at all six `arm()` call sites
(three rows per branch), by `canArmRememberedAction(action)` (right after
`getEligibleArmedSendAction()`) so the master switch is enforced where the
armed slot is written, not only where it's later read back.

This isn't an oversight of the split: the cached popup's own non-Stories case
(empty-caption forward-in-progress) is `isChat == true`, so it's in scope for
`isRememberSendActionContextEligible()` the same as the `ItemOptions` case,
and the armed slot/badge/repeat are process-wide state, not
per-menu — a user could arm silent send from the `ItemOptions` menu in one
long-press and then reach this cached popup on the very next one (once they
start an empty-caption forward), where it would otherwise have no control
to see or turn off what's armed. The cached popup's Stories case still
excludes the row on its own: `isRememberSendActionContextEligible()` gates
on `!isStories`, so `isRememberSendActionContextEligible()` alone decides
whether the row is added in both branches, with no separate Stories check
needed.

*(Updated 2026-09-17.)*


## Send on event card membership and collapse behavior

The *Send early on event* sheet now uses a local `DisclosureHeaderCell`
subclass of `TextSettingsCell` for both collapsible group headers, with summary
value text and explicit accessibility state text
(`EventScheduleHelper.java:274-346`). The disclosure cue is an inline
`ColoredImageSpan(R.drawable.arrow_more)` appended to the title text and rotated
with the same 340ms `EASE_OUT_QUINT` curve when expanded/collapsed, so the cue
measures with the title in the stock `TextSettingsCell` layout path.
These disclosure rows are section
members (not tagged out), so each group header sits inside its card with its
controls directly beneath it.

Card ownership now has permanent boundaries that do not depend on descendant
visibility: an intro/type boundary spacer immediately after `addTitle(...)`,
the existing permanent type/text spacer, and a permanent text/delay spacer
after `patternInfo` (`EventScheduleHelper.java:658-661`, `:707-710`, `:748-751`).
With those boundaries, composition stays stable as four primary cards: intro title
card, type card (header + five type rows), text card (header + `patternArea` +
regex row), and delay card (`EventScheduleHelper.java:657`, `:668-702`,
`:712-740`, `:1031-1033`). If trigger state is already enabled, the optional
remove action still appears after those four cards with its own tagged gray
spacer (`EventScheduleHelper.java:1035-1044`).

`patternInfo` remains a `TextInfoPrivacyCell` after regex, outside card
grouping by `SectionsScrollView.isSectionView(...)` class exclusion
(`EventScheduleHelper.java:745-747`; `SectionsScrollView.java:36-41`). Text
collapse still toggles `patternArea`, regex, and `patternInfo` visibility, but
the permanent boundaries keep text and delay as separate card runs even when
the text descendants are `GONE` (`EventScheduleHelper.java:893-901`;
`SectionsScrollView.java:91-98`).

Divider behavior is now explicit: header dividers draw only while expanded,
type rows clear the last divider in the group, and regex is always the text
card's last row with no bottom divider (`EventScheduleHelper.java:885-890`).
Delay UI remains wrapped in a full-width `FrameLayout` so sections treat it as
one card, and the remove separator now uses literal `12` dp units (no double-dp)
(`EventScheduleHelper.java:1031-1039`).

Hidden-group validation behavior remains in the same code path: Done expands a
collapsed text group before showing row-level invalid-regex feedback, and
no-condition failure expands actionable groups before the existing toast
(`EventScheduleHelper.java:1067-1108`).

*(Updated 2026-09-07.)*

## Covered notification channel and alert decision

A covered chat's notification is posted by `NotificationCoverController.postChild(...)`
(`NotificationCoverController.java:816`) from the covered branch of `showExtraNotifications`
(`NotificationsController.java:5144-5147`); the real child is never built. Everything that is behaviour comes in through
`NotificationCoverController.Behavior` (`:661`): channel (the newest chat's `validateChannelId` result, or
`OTHER_NOTIFICATIONS_CHANNEL` when grouped), group, whether this child may alert (`naxAlert && dialogId == lastDialogId`), the
chat's notification profile, the real icon and colour, message date, last message id and whether "Mark as read" is allowed
(`NotificationsController.java:5009-5015`, `:5144-5147`). The summary is `naxBuildCoverSummary(...)`
(`NotificationsController.java:5024`) on the same channel. Only the title, text, count and tap/dismiss tokens are cover-owned.
Passive applies through `NotificationProfiles.apply` inside `postChild`; Muted is Telegram's own mute and needs no reroute.

*(Rewritten 2026-10-07, `#disguise-parity`.)*

## Double-tap edit keeps the caret on the tapped word

The double-tap edit flow starts in `RecyclerListView`: the gesture detector fires
`onDoubleTap(...)` with RecyclerListView-local coordinates, then `ChatActivity`
translates the tap into the message cell and arms a message-scoped pending offset
before the `OPTION_EDIT` branch runs (`org/telegram/ui/Components/RecyclerListView.java:1090-1122`,
`org/telegram/ui/ChatActivity.java:2188-2293`). `setEditingMessageObject` consumes
and unconditionally clears that armed offset at entry, before any early return, so
a stale offset can never leak into an unrelated `setFieldText` call
(`org/telegram/ui/Components/ChatActivityEnterView.java:12270-12286`). Only the two
edit-fill call sites -- the inline call and the 200ms-delayed field-refresh runnable
-- route through the edit-only `setFieldTextForEdit(...)` entry point, which carries
the pre-transform rendered text and offset alongside the field text
(`org/telegram/ui/Components/ChatActivityEnterView.java:12389-12427`, `:12882-12884`).
That entry point validates the offset against the field before applying it -- the
selection is rejected and falls back to end-of-text if the rendered text no longer
matches what the field contains after `restoreFormatedDateEntities(...)`, or if the
offset is out of bounds; every other `setFieldText` caller always lands at
end-of-text with no dependency on this state at all
(`org/telegram/ui/Components/ChatActivityEnterView.java:12882-12904`).

The tap offset itself is taken from the text layout hit test in `ChatMessageCell`,
matching the existing word-boundary behavior and clamping emoji spans before the
word walk (`org/telegram/ui/Cells/ChatMessageCell.java:2451-2510`,
`org/telegram/ui/Cells/TextSelectionHelper.java:252-270`,
`org/telegram/ui/Components/FormattedDateSpan.java:105-121`).

*(Established 2026-09-14.)*

## Chat privacy overflow row owns both per-chat privacy controls

The in-chat overflow menu now has one `Chat privacy` row (`nkheaderbtn_chat_privacy`)
that opens `ChatPrivacySheet.show(...)` (`org/telegram/ui/ChatActivity.java:498`,
`:5168`, `:48085-48086`).

Inside that sheet, `Hide last message` toggles
`HideLastMessageController.setHidden(...)`, and the `Placeholder text` value row
opens a mode picker — custom text, chat status, unread count, message type —
whose `Custom text` choice then opens `HideLastMessageDialog.showPlaceholderEditor(...)`
(`com/dazewell/gram/chatprivacy/ChatPrivacySheet.java:175`, `:181-185`, `:272`).
The mode lives beside the custom text in `hidelastmessage_<account>` under
`m<dialogId>`, and `HideLastMessagePreview.resolve(...)` renders it for the row
(`com/dazewell/gram/hidelastmessage/HideLastMessageController.java:124-153`;
`HideLastMessagePreview.java:59`).

`Require password` state is read from the persisted lock flag via
`ChatLockController.isFlagged(...)` (not `isLocked(...)`), so a stored flag is
still shown when the global app passcode is absent
(`com/dazewell/gram/chatlock/ChatLockController.java:70-80`;
`com/dazewell/gram/chatprivacy/ChatPrivacySheet.java:123-151`, `:186-194`).
When turned on with a passcode present, the sheet keeps the existing one-way
coupling: it auto-enables hide only when hide was off, preserving a custom
placeholder, and shows the existing enabled bulletin (`ChatPrivacySheet.java:197-204`).

*(Updated 2026-10-03, `#hide-last-message`.)*

## Hide last message is a chat-list override, not a search-result override

`DialogCell` renders both dialog previews and individual message results.
`DialogsAdapter.java:1023` binds the normal chat list through
`DialogCell.setDialog(TLRPC.Dialog, ...)`, which sets `isDialogCell = true`
(`org/telegram/ui/Cells/DialogCell.java:751-768`). The hide override gates the
whole placeholder/thumbnail/paint block on that flag (`DialogCell.java:2765-2771`).

Search binds message objects through overloads that set the flag to false
(`DialogCell.java:862-894`; `org/telegram/ui/Adapters/DialogsSearchAdapter.java:2140-2150`;
`org/telegram/ui/FilteredSearchView.java:1088-1094`). The same overload serves
in-chat search, including Saved Messages (`org/telegram/ui/Adapters/MessagesSearchAdapter.java:232-259`),
universal search rows (`org/telegram/ui/Components/UniversalAdapter.java:1002-1013`),
and Saved Messages search results (`org/telegram/ui/Components/SharedMediaLayout.java:10162-10169`).
Topic search uses
`setForumTopic`, which also clears the flag (`org/telegram/ui/TopicsFragment.java:3861`;
`DialogCell.java:281-293`). Do not infer search context from highlighted words:
media-only results need the same separation without a text match.

This boundary also leaves topic-list previews inside an opened forum,
profile-channel message previews, and the Saved Messages dialog list
undisguised: those are message-object bindings,
not normal chat-list rows (`TopicsFragment.java:3032-3036`;
`org/telegram/ui/Cells/ProfileChannelCell.java:217`;
`org/telegram/ui/Components/SharedMediaLayout.java:9933-9938`).

*(Established 2026-09-13.)*

## Chat privacy card membership and stock bulletin placement

`ChatPrivacySheet` now builds content on `SectionsLinearLayout` and wraps it
in `SectionsScrollView`, with gray sheet/nav-bar backgrounds applied after
`builder.create()` and before `showDialog()` (`ChatPrivacySheet.java:53`,
:268-297`). Card membership is split by tags: title, notifications header,
and `How covers work` disclosure are marked `TAG_NOT_SECTION`; card 1 is
hide/placeholder/require-password and card 2 is disguise/cover/preview
(`ChatPrivacySheet.java:61`, `:79`, `:96-99`).

The sections scroll migration now explicitly attaches `content` as
`MATCH_PARENT x WRAP_CONTENT` under the `SectionsScrollView`, matching the
builder sections path (`ChatPrivacySheet.java:271`).

The notifications behavior wiring is unchanged in ownership: switch -> 
`setEnabled(...)` + notifications rebuild, cover picker -> `setPersona(...)` +
rebuild, preview -> `postPreview(...)` + bulletin feedback
(`ChatPrivacySheet.java:211-242`;
`tw/nekomimi/nekogram/helpers/PopupHelper.java:32-54`).

Require password / Disguise / Preview bulletins now call
`BulletinFactory.of(sheetRef[0].container, rp).createSimpleBulletin(...).show()`
directly under the existing `sheetRef[0] != null` guards, with no custom
delegate, offset host, or row-anchor wrapper (`ChatPrivacySheet.java:194-203`,
`:212-220`, `:236-243`).

Cover config is stored in the account's notifications `SharedPreferences`
(`MessagesController.getNotificationsSettings(account)`), keyed
`nax_cover_v1_enabled_<dialogId>` / `nax_cover_v1_persona_<dialogId>`
(`com/dazewell/gram/chatprivacy/NotificationCoverController.java:68-77`, `:214-244`). The cover no longer creates
channels; the old `nax_cover_v1_channel_*` ids are only deleted at logout (`deleteChannels`, `:1428`).

*(Updated 2026-10-07, `#disguise-parity`.)*

## Tokenized broadcast interaction path for covered notifications

All cover interactions now use immutable **broadcast** PendingIntents into the
non-exported `NotificationDismissReceiver`: child tap/dismiss, summary tap/dismiss,
and preview tap all resolve via the same tokenized receiver path in
`NotificationCoverController.handleInteraction(...)`.

Android 12+ blocks a notification broadcast receiver from launching the chat
activity directly. Supporting cover-tap open-chat would therefore require a
dedicated transparent activity bridge; by product decision, that complexity was
dropped and covers are Hollow-only, so broadcast is the only interaction
transport now needed
(`NotificationCoverController.java:686-725`, `:745-793`, `:796-828`, `:860-955`, `:995-1068`;
`org/telegram/messenger/NotificationDismissReceiver.java:27-33`).

*(Established 2026-09-03.)*

## Covered-chat clear hooks and accepted open-attempt behavior

`ChatActivity` routes cover-clear attempts through
`clearCoveredNotificationsIfVisible()`, called from the chat lifecycle path
that also sets `openedDialogId`, from `onBecomeFullyVisible`, and from the
post-chat-lock-unlock callback
(`org/telegram/ui/ChatActivity.java:3755-3759`, `:3776-3784`, `:29244`,
`:32179`). In that helper, current code checks `MODE_DEFAULT`, nonzero
`dialog_id`, `chatLockPasscodeView == null`, and app-passcode flags before
calling `NotificationsController.suppressVisibleCoveredDialog(dialog_id)`
(`ChatActivity.java:3776-3784`).

`suppressVisibleCoveredDialog(...)` posts onto `notificationsQueue`; the cover
controller suppression path runs there and then triggers notification rebuild
(`org/telegram/messenger/NotificationsController.java:3313-3318`;
`com/dazewell/gram/chatprivacy/NotificationCoverController.java:560-593`).

Accepted on-device behavior: attempting to open a protected covered chat may
consume the current cover before successful visibility (including cancelled or
failed unlock). This map documents the fork-owned clear hooks above; it does
not attribute or alter upstream read-state behavior, which is tracked
separately in issue #286.

*(Established 2026-09-03.)*

## Selection bar left button ("NoQuote")

The button at the bottom-left of the message-selection action bar is the
**configurable left action button**, not an overflow menu item. It defaults
to the NoQuote forward action, present out of the box with no setting turned
on:

- Action constants: `ChatsHelper.LEFT_BUTTON_*`
  (`tw/nekomimi/nekogram/helpers/ChatsHelper.java:42-47`).
- Which action is active: `NaConfig.leftBottomButton`
  (`NaConfig.kt:1314-1319`, `LeftBottomButtonAction`), **default `0` =
  `LEFT_BUTTON_NOQUOTE`**.
- Label: `ChatsHelper.getLeftButtonText` (`ChatsHelper.java:91-99`) — the
  default case returns `NoQuoteForwardShort`.
- Click handling: `ChatsHelper.makeReplyButtonClick`
  (`ChatsHelper.java:134-167`). The `LEFT_BUTTON_NOQUOTE` case sets
  `ChatActivity.noForwardQuote = true` and calls `openForward(false)`
  (`ChatsHelper.java:157-166`).

**This is a different door from `nkbtn_forward_noquote`**, the item in the
selection *overflow* menu, gated by `NaConfig.showNoQuoteForward`
(`NaConfig.kt:171-176`, config key `NoQuoteForward`, **default `false`**).
Conflating the always-present left button with this off-by-default menu item
produced two wrong conclusions in one session before this was written down.

`LEFT_BUTTON_DIRECT_SHARE` is the only left-button action that reaches
`ShareAlert`, via `createShareAlertSelected`
(`ChatsHelper.java:142-144`). Forwarding through the NoQuote button does
**not** go through `ShareAlert`.

*(Established 2026-09-02.)*

## Single tap on a scheduled message

A single tap on a row in the scheduled-messages list opens the **message
context menu**, not selection mode. `ChatActivity`'s item click listener only
routes to row-selection (`processRowSelect`) when the action bar is already
showing selection mode; otherwise it falls through to `createMenu(view,
true, false, x, y, false)`
(`org/telegram/ui/ChatActivity.java:2053-2077`).

`createMenu` clears any previously-checked selection before building the menu
— it resets `selectedObject`/`selectedObjectGroup`/`forwardingMessage` and
empties `selectedMessagesIds`/`selectedMessagesCanCopyIds`/
`selectedMessagesCanStarIds` for both message-list slots
(`ChatActivity.java:33060-33070`). A stale multi-selection from before the tap
cannot leak into the single-message menu that opens.

The "Reschedule" item in that per-message menu is
`ChatActivity.OPTION_EDIT_SCHEDULE_TIME` (`ChatActivity.java:1403`, handled at
`:36713`), string `MessageScheduleEditTime`. It is a **different feature**
from the toolbar's bulk reschedule button below.

*(Established 2026-09-02.)*

## Long-press Send opens the schedule sheet via `ChatActivityEnterView`, not the forward picker

Long-pressing the message input bar's Send button in a chat opens the schedule
sheet through `ChatActivityEnterView.onSendLongClick`
(`ChatActivityEnterView.java:5567`), whose "Schedule Message" popup item calls
`AlertsCreator.createScheduleDatePickerDialog`
(`ChatActivityEnterView.java:5622`). That call enters the 4-arg overload at
`AlertsCreator.java:4410` and funnels through five more delegating overloads —
`:4430` → `:4434` → `:4438` → `:4456` → `:4474` — into the terminal
implementation at `:4480`, where every schedule sheet is actually built.

**This is a different door from long-pressing Send in the forward chat-picker
(`DialogsActivity`).** The picker has its own long-press-Send handler,
`DialogsActivity.onSendLongClick` (`DialogsActivity.java:12224`), whose own
"Schedule Message" item (`:12291-12324`) calls the *same* `AlertsCreator.java:4410`
entry overload directly from the picker's own `writeButton`. The two are easy to
conflate — both are "long-press Send, choose Schedule" from the user's point of
view — but they are wired to different widgets, and only one of them fires for
a given gesture.

**A forward to a single chosen chat is staged into that chat's own input bar,
not sent from the picker.** `ChatActivity.openForward`
(`ChatActivity.java:13645-13730`) presents `DialogsActivity` as a
`DIALOGS_TYPE_FORWARD` picker; selecting a single destination chat that isn't
already open calls back into `ChatActivity.didSelectDialogs`
(`ChatActivity.java:36966`), which — for the plain single-chat, no-comment,
not-scheduled case — opens a new `ChatActivity` for that dialog and calls
`showFieldPanelForForward(true, fmessages)` on it
(`ChatActivity.java:37112-37143`) instead of sending immediately. That queues
the forward into the new chat's own field panel, so the Send button the user
then long-presses belongs to the target chat's `ChatActivityEnterView`, not the
picker's `writeButton`. This is why the picker's own schedule path can look
correct in review — it compiles, it is wired to a real menu item — and still
never execute for this gesture: the picker has already closed and handed off
before the user reaches the button it owns.

**Measured, not inferred.** Instrumented build `3a55877cb1` (confirmed
installed as `org.telegram.messenger.beta`, `versionName=12.10.1-3a55877`,
with the diagnostic literals verified present in the pulled `base.apk`'s DEX)
was exercised by selecting messages, using the fork's left NoQuote button,
choosing a target chat, and long-pressing Send → Schedule. The only captured
`NAX_SPREAD_DIAG` output was a `Throwable` at the
`createScheduleDatePickerDialog` chokepoint whose top frames were
`ChatActivityEnterView.lambda$onSendLongClick$63` funnelling into
`AlertsCreator.createScheduleDatePickerDialog`. No forward-picker presentation
logged during that same run either, consistent with the picker path not being
the one exercised for this gesture.

**Why it matters:** a spread-interval feature was built and reviewed against
the picker's schedule path (`DialogsActivity`), passed two architect rounds and
three independent final-state reviews, and did not work on device — because
none of those reviews could establish which code path a real long-press-Send
gesture actually takes on this device. That requires a stack trace from an
installed build, not a reading of the diff.

*(Established 2026-09-02, PR #270.)*

## Forward picker (`DIALOGS_TYPE_FORWARD`) has six presentation sites

`ChatActivity` opens the forward chat-picker (`DialogsActivity` with
`dialogsType == DIALOGS_TYPE_FORWARD`) from **six** places, each building its
own argument `Bundle`:

- `:4013` — quote-reply picker (single message).
- `:5807` — reply-to-author quote picker (single message).
- `:12327` — `selectAnotherChat` (`:12300`), the forward **preview's** "select
  another chat". Multi-message; populates `selectedMessagesIds[0]` (`:12322`)
  and syncs `noForwardQuote = messagePreviewParams.hideForwardSendersName`
  (`:12306`). **This is the route reached via "Hide sender's name"** — that
  toggle is a preview control, so stock Forward + hide-sender lands here, not in
  `openForward`.
- `:13728` — `openForward` (`:13651`), the selection bar's Forward / left
  NoQuote button. Multi-message.
- `:35941` — context-menu single-message forward (`OPTION_FORWARD`); sets
  `forwardingMessage`.
- `:36267` — context-menu reply-to-author (`OPTION_REPLY`).

The `#repost-spread` spread-interval gate needs the forward slot count. It was
first threaded as a Bundle int written by **only `openForward`**, so the other
five presentations — `selectAnotherChat` included — reached the gate with the
count defaulting to 0 and the interval row never appeared. The durable lesson:
**adding a value to one presentation of a shared screen has to enumerate the
others.** It is now computed at gate time from the delegate's live selection
(`DialogsActivityDelegate.getForwardSpreadSlotCount`, overridden in
`ChatActivity`) using the same selection logic the dispatch forwards, so no
presentation can reach the gate with a stale count.

*(Established 2026-09-02, PR #270.)*

## Channel post share arrow: two different code paths for "quick share sheet"

A single tap on the share arrow under a channel post opens
`ChatMessageCell.Delegate.didPressSideButton` (`ChatActivity.java:42733`), which
constructs `new ShareAlert(...)` (`ChatActivity.java:42773`) with `fullScreen`
and `forCall` both `false`. This is the bottom sheet with a "Send to..." search
field, a 4-column avatar grid, and a COPY LINK footer.

A **long-press-and-drag** on the same arrow is a completely different widget:
`didQuickShareStart` (`ChatActivity.java:42652`) opens
`QuickShareSelectorOverlayLayout` (`org/telegram/ui/Components/quickforward/`),
a hand-drawn popup with its own bespoke hit-testing. It shares no code with
`ShareAlert`.

A bug report describing "the quick share sheet" can mean either, and recon
cannot tell which from the report alone — the two have no code in common, so
guessing wrong burns a whole investigation cycle in the wrong files. The
distinguishing marks: `ShareAlert` has the search field and COPY LINK footer;
`QuickShareSelectorOverlayLayout` does not.

*(Established 2026-09-03.)*

## Bulk reschedule toolbar button

The fork's selection-toolbar "Reschedule" button (`nkactionbarbtn_reschedule`,
`ChatActivity.java:493`) only shows in scheduled-message mode with at least
one row checked (`ChatActivity.java:11492`). Its click handler calls
`performRescheduleSpreadSelectedMessages()` (`ChatActivity.java:4341-4342`),
which collects **every currently-selected id across both message-list slots**
(`ChatActivity.java:37447-37454`) before building the reschedule preview. It
operates on the whole live checkbox set, unlike the single-message
`OPTION_EDIT_SCHEDULE_TIME` above, which only ever touches the one message the
menu was opened on.

*(Established 2026-09-02.)*

## Message Triggers time column, and who keeps it current

The time on each Message Triggers row is the stored `entry.fallbackDate`
(`MessageTriggersActivity.java:619`), not the message's live schedule date, and
the same field prunes the trigger on reload once it is 5 minutes past
(`EventScheduleStore.java:88`). So a path that moves a scheduled message
without re-arming its existing trigger must route the new time through
`EventScheduleController.commitEditRefresh` (`EventScheduleController.java:551`).
The untouched-trigger single-message edit does it directly. A plain bulk
reschedule does it through `bulkMoveListener` (`EventScheduleController.java:584`,
passed at `ChatActivity.java:38145`), fed the server-confirmed dates
(`RescheduleSpreadExecutor.java:413`). Arming paths write the date as they arm
instead, and a trigger-enabled bulk run does so only on the targets it arms.

*(Established 2026-10-12.)*

## Delay slider and Remember toggle: mode is carried explicitly, not inferred from slider presence

Every schedule sheet's terminal builder (`AlertsCreator.java:4480`) computes
one `naxReschedule` boolean (`:4490`, `isEditSchedule || reschedule != null`)
that both doors above — bulk Reschedule and single-message Edit schedule time —
set `true`. Whether the delay slider block appears at all is gated at `:4761`:
shown for a plain new-message sheet whenever `ScheduleTimeHelper.shouldUseDefaultSchedule`
is true, and shown for a reschedule/edit sheet unless `currentDate` is the
send-when-online sentinel (`0x7FFFFFFE`) — the one case with no real timestamp
to compute a "from now" delay against.

- `ScheduleTimeHelper.RememberToggle.isReschedule` (`ScheduleTimeHelper.java:144`,
  set from the constructor call at `AlertsCreator.java:4691`) drives the
  Remember hint's wording and its show-on-toggle-on condition
  (`ScheduleTimeHelper.java:301`, `:195`) and the slider block's own title
  (`getDelayTitle`, `:313`, called from both the block's initial construction
  at `:365` and its Remember-toggle refresh at `:401`) — Remembered delay when
  Remember is on, otherwise Delay on a reschedule/edit sheet or Default delay
  on a new-message one.
- `addDefaultScheduleSlider`'s own `rescheduleMode` parameter
  (`ScheduleTimeHelper.java:350`, passed as `naxReschedule` from
  `AlertsCreator.java:4771`) gates persistence inside `onSeekBarDrag`
  (`:420-436`): reschedule mode never writes `NaConfig.defaultScheduledTime`
  and never calls `remember.set(false, true)` (which would toggle Remember off
  and clear the remembered offset) — it only refreshes the header and moves
  this sheet's own wheels to `now + selected delay`.

Both flags are always set consistently from the same `naxReschedule` at the
one call site, but they exist as two separate carriers on purpose: one is a
display concern (title/hint text), the other is a persistence concern (what a
drag is allowed to write). Collapsing them back into a single "does this sheet
have a slider" check was the exact bug this fix closed — the slider used to be
the only thing standing in for "is this a reschedule sheet" at all, which broke
the moment reschedule sheets got a slider too.

*(Established 2026-09-04.)*

## Composer Toolbar screen: a `TYPE_INFO` row *is* the gray gap between slider groups

`ComposerLayoutActivity`'s row list (`buildItems()`, `ComposerLayoutActivity.java:397`)
has no dedicated divider row between one slider group (Toolbar size, Icon
spacing, Transparency) and the next. The gray separation comes for free from
the footer row itself: `TYPE_INFO` binds to a bare `TextInfoPrivacyCell` with
no background set (`onBindViewHolder` bind path around `ComposerLayoutActivity.java:653`,
row creation `:599-600`), while every other row type in this screen paints
`key_windowBackgroundWhite` over the fragment's `key_windowBackgroundGray`
background (`:336`, rows at `:593,597,609,613,617`). So removing a `TYPE_INFO`
row doesn't just remove a footer, it also removes the gray seam after it —
two slider groups sharing one footer render as one continuous white block,
which is why the Light/Dark glass sliders were merged onto a single shared
footer under Dark rather than each keeping its own.

*(Established 2026-09-06; citations re-verified against `5b73e5a15c`.)*

## Composer Toolbar screen: `footerText()`'s `default:` arm is a live case, not an error fallback

`footerText(int zone)` (`ComposerLayoutActivity.java:779-797`) returns a
`CharSequence` and switches on either a negative slider-group id or a
`ComposerButtons.ZONE_*` constant. Its `default:` arm returns
`LocaleController.getString(R.string.ComposerLayoutInfo)` — the *resolved*
string, not a bare `@StringRes int`, since the whole method resolves each arm
to a `CharSequence` — and that arm is not a guard for
an impossible value, it's the real, reachable case for `ZONE_HIDDEN`, which
has no explicit `case` label of its own. Deleting or repurposing a `case`
label in this switch without checking what falls through to `default:` is
silent: nothing crashes or fails to compile, a footer just renders under the
wrong row. This is why the Light/Dark glass-transparency merge kept both
`case GROUP_GLASS_LIGHT:` and `case GROUP_GLASS_DARK:` as explicit fall-through
labels sharing one return, instead of deleting one and letting it land in
`default:`.

*(Established 2026-09-06; footerText signature and disclosure updated 2026-09-06 for #composer-spacing; the Icon spacing arm removed 2026-10-17, #composer-keyboard-gap.)*

## Tapping a formatting button on already-styled text toggles it off, via `makeSelectedX`, not `toggleStyleForSelection`

Four surfaces reach the composer's rich-text formatting: the platform text-selection popup
(`ChatActivity.fillActionModeMenu` → `ActionMode.Callback` → `EditTextCaption.performMenuAction(int)`,
`EditTextCaption.java:1243-1273`), the fork's glass composer toolbar
(`ComposerFormattingActions.apply()` → `editText.performMenuAction(...)`), the chat header's
overflow formatting menu (`ChatActivity.java:4609-4671`, calls `makeSelectedBold()` etc.
**directly**, bypassing `performMenuAction` entirely), and physical-keyboard shortcuts split across
two independent paths (`ChatActivityEnterView`'s own `dispatchKeyEvent` →
`toggleStyleForSelection(int)` for one set of keys, and `EditTextCaption.onKeyShortcut` →
`HotkeyController.handleTextStyleShortcut` → `performMenuAction` for another, see
`HotkeyController.java:248-294`). All but the direct-dispatch keyboard path funnel into the
`makeSelectedBold`/`Italic`/`Mono`/`Strike`/`Underline`/`Spoiler`/`Quote`/`Code` methods in
`EditTextCaption.java` — that shared point, not `performMenuAction`, is where `#toggle-formatting`
added toggle-off, because it's the only chokepoint all three non-hotkey-only surfaces share.

`EditTextCaption.toggleStyleForSelection(int)` (`EditTextCaption.java:388-419`) is a **different,
older, keyboard-only** toggle mechanism — an upstream import from commit `80c604047e` ("update to
12.9.0 (6966)", 2026-07-17), wired only to `ChatActivityEnterView`'s `dispatchKeyEvent` path. It was
never reachable from the popup, the toolbar, or the header menu, and `#toggle-formatting`
deliberately does **not** route the six flag-based styles through it for the other three surfaces:
it reads raw `getSelectionStart()/End()` and ignores the `selectionStart`/`selectionEnd` override
those three surfaces rely on, it clears every other inline style when adding Mono (the popup's
plain `makeSelectedMono()` merge does not), and its `addStyle` call hardcodes
entity-intersection=true where the popup/toolbar path respects `allowTextEntitiesIntersection`
(secret chats). Instead, each `makeSelectedX` gained its own removal branch
(`removeStyleIfFullyApplied(int)`, `EditTextCaption.java:175-196`, for the six flag-based styles;
inline containment checks in `makeSelectedQuote`/`makeSelectedCode` for Quote and the language-tagged
Code block) that detects "already fully applied" and removes instead of adding, reusing
`getCurrentStyle(int,int)` (`EditTextCaption.java:966-1000`) and the span-splitting
`addStyle`/`removeStyle` primitives (`EditTextCaption.java:1004-1067`) `toggleStyleForSelection`
already used — so both toggle mechanisms share their span-detection primitives without sharing
selection-resolution or merge semantics. Don't assume a future formatting change can just call
`toggleStyleForSelection` to get toggle behaviour on the popup/toolbar/header-menu surfaces; it
can't, for the reasons above.

*(Established 2026-09-06, `#toggle-formatting`.)*

## A local negative-id row in scheduled_messages_v2 renders in the Scheduled list for free

Established 2026-09-09 (#ghost-hold). A message written to
`scheduled_messages_v2` with a negative `mid` and `send_state = 1` loads and
renders in a chat's Scheduled list with no extra plumbing. Ghost Hold does NOT
use this as its store: held rows live in the fork-owned `ghosthold_<account>.db`
and are injected as display-only objects at
`MessagesController.processLoadedMessages`. The original pre-rebuild design did
persist held rows as negative-id scheduled rows; that path was discarded as
unsafe (it mixed fork state into shared upstream tables), so don't reintroduce
it. This upstream behaviour still matters because it is why a leftover legacy row,
or the stock twin a send-now handoff writes, renders without extra plumbing.
The chain: `MessagesStorage.getMessagesInternal`'s scheduled branch selects
every row for the dialog with no `send_state`/id filter and the id scrub is
gated `message.id > 0` (`MessagesStorage.java:9008`, `:9015`), so a negative-id
row survives load; `MessagesController.processLoadedMessages` skips `id < 0`
when hashing `getScheduledHistory` (`MessagesController.java:12229-12231`), so a
held row can't corrupt scheduled-cache validation; `ChatActivity`'s
`messagesDidLoad` clears the list only for `MODE_DEFAULT`/`MODE_SUGGESTIONS`
(`ChatActivity.java:22475`), so a server scheduled refresh merges rather than
wipes; and server sync deletes only `mid > 0` (`MessagesStorage.java:16269`), so
the local row is not swept. The in-bubble "held" caption is the one thing not
free — it's a fork branch in `ChatMessageCell`'s time-string block
(`ChatMessageCell.java:18692`+).

## isGhostModeActive() is a derived predicate, and the five toggles are flipped individually

Established 2026-09-09 (#ghost-hold). `NekoConfig.isGhostModeActive()` is not a
stored flag: it returns true when any of five independent `ConfigItem` toggles
is on and unlocked (`NekoConfig.java:305-319`). `GhostModeActivity.onItemClick`
flips each one directly (`sendReadMessagePackets` etc.) via `toggleConfigBool()`
without ever calling `toggleGhostMode()` (`GhostModeActivity.java` toggle
handlers). So anything that needs to react to Ghost turning off must watch the
derived true→false edge, not hook `toggleGhostMode()` — a user unchecking the
last active toggle turns Ghost off without that method ever running.

## ChatActivityEnterView is shared by five surfaces; only ChatActivity wires a real fragment and dialogId

`ChatActivityEnterView` is instantiated from five call sites, but only one of
them gives it a real hosting fragment and a real chat to key state on:

- `ChatActivity.java:8645` — passes `this` as the `fragment` constructor
  argument (stored in the `parentFragment` field, declared
  `ChatActivityEnterView.java:811` as `ChatActivity parentFragment`) and later
  (`ChatActivity.java:8894`) calls `setDialogId(long, int)`
  (`ChatActivityEnterView.java:8240`) with the real chat's dialogId. This is
  the only call site where `parentFragment` is ever non-null and `dialog_id`
  (`ChatActivityEnterView.java:812`) is ever the actual open chat.
- `DialogsActivity.java:5075` (forward/share comment field),
  `Gifts/GiftMessageBottomSheet.java:179`, `PopupNotificationActivity.java:317`,
  and `Stories/PeerStoriesView.java:3202` (story reply box) all pass `null` for
  the fragment argument, so `parentFragment` stays null in every one of them —
  `dialog_id` in these instances is either left at 0 or set to something other
  than a real open chat, depending on the surface.

`ChatActivityEnterView` itself already relies on this split throughout — see
the many `parentFragment == null ? ... : parentFragment....` guards scattered
through the class (e.g. `:3993`, `:5262`, `:5504`, `:6549`), each treating a
null `parentFragment` as "this instance isn't the real chat composer." Any new
feature hooking a shared callback on this widget — the typing-time Ghost Mode
reminder added under `#ghost-type-warning` is one example
(`ChatActivityEnterView.java`'s `TextWatcher.onTextChanged`, guarded on
`parentFragment != null` before doing anything chat-specific) — must gate on
`parentFragment != null` the same way, or it will silently also fire from the
share-comment field, the gift-message sheet, the popup-notification reply box,
or the story reply box, none of which represent an actual open chat.

The corollary matters just as much, and cost a design round to work out: those
four surfaces send real messages. Only one of the five instantiations passes a
fragment (`ChatActivity.java:8645`); the other four pass `null` —
`DialogsActivity.java:5075` (share/forward sheet comment),
`Gifts/GiftMessageBottomSheet.java:179`, `Stories/PeerStoriesView.java:3202`
(story reply) and `PopupNotificationActivity.java:317` (notification quick
reply). So a feature hooked on this widget covers only chat-composer sends by
construction, and can never see a story reply, a notification reply, or a
share-sheet comment — nor, since they don't go through any composer at all, a
forward, gallery media, a bot keyboard button, or
`SendMessagesHelper`'s automatic retry of unsent messages on reconnect
(`SendMessagesHelper.java:9095-9114`). Anything that needs to observe *every*
outgoing message needs a hook at the send or network layer, not here; the pair
of Ghost Mode warnings is split along exactly this line.

*(Established 2026-09-10, #ghost-type-warning; corollary added 2026-09-10 in
the same feature's warning-tuning change.)*

## The Scheduled list is reverse-stacked: index 0 of the loaded list renders at the screen bottom

The in-chat message list (Scheduled list included) is laid out by a
`GridLayoutManagerFixed` built with `reverseLayout = !reversed`, and `reversed`
defaults `false` (`ChatActivity.java:7303`, field default `ChatActivity.java:2976`),
so the effective `reverseLayout` is `true`: the item at `messages` index 0 draws
at the **bottom** of the screen and higher indices climb upward. On a normal
(append) load, loaded objects are appended into `messages` in array order after the
load-time sort (`ChatActivity.java:23353-23361`), so the first element of the sorted
list is the bottom-most row on screen. A forward load (`load_type == 1`) reaches the
SAME final order by a different route, not a reversed one: it first reverses the whole
batch (`Collections.reverse(messArr)`, `ChatActivity.java:22977`) and then PREPENDS each
object at index 0 (`:23353`) instead of appending. Reverse-then-prepend-each composes
back to the original sorted order -- `[a,b,c]` -> reverse -> `[c,b,a]` -> prepend c, then
b, then a -> `[a,b,c]` -- so a forward load lands identically to an append load. The
index-0-is-bottom orientation holds either way.

This is the orientation fact behind the Ghost Hold held-row ordering: to make a
block read oldest-at-top / newest-at-bottom, the newest row must sit at the
**lowest** index, not the highest. Getting this backwards produces a fix that
looks right in the code and is still reversed on the device.

*(Established 2026-09-11, #ghost-hold.)*

## Composer zone constants do not match their on-screen labels

The composer toolbar layout editor has four zones. **Only one of the four
constants is named for the label it produces.** Do not infer a zone's label
from its constant, or the reverse.

| Constant | On-screen label | Same? |
|---|---|---|
| `ZONE_START` (`ComposerButtons.java:20`) | "Leading — up to 2" | no |
| `ZONE_MIDDLE` (`ComposerButtons.java:21`) | "Scrolling" | no |
| `ZONE_END` (`ComposerButtons.java:22`) | "Trailing" | no |
| `ZONE_HIDDEN` (`ComposerButtons.java:23`) | "Hidden" | yes |

`ZONE_MIDDLE` is the dangerous one. `START`/`END` are obviously positional and
nobody mistakes them for user-facing words, but "Middle" reads like a label —
so it is the one that gets written down as if it were.

The header mapping is made in `headerTitle(int zone)`
(`ComposerLayoutActivity.java:766-777`), which resolves `ZONE_MIDDLE` to
`R.string.ComposerZoneScrolling` (`:771`) = "Scrolling"
(`strings_nax.xml:657`).

Footers do **not** map one-to-one the way headers do. `footerText(int zone)`
gives explicit cases only to the three visible zones —
`ComposerZoneLeadingInfo`/`ScrollingInfo`/`TrailingInfo` at
`ComposerLayoutActivity.java:788-793`. `ZONE_HIDDEN` has no case of its own and
falls through to `default:`, returning the screen-level
`R.string.ComposerLayoutInfo` (`:794-795`), so the Hidden zone shows the
generic screen description rather than anything about itself. The same switch
also answers non-zone group ids (`GROUP_GLASS_LIGHT`/`DARK`, `:785-787`), so it
is not a pure zone lookup and must not be read as one.

**Neither switch ever names `ZONE_HIDDEN`.** Both reach it through `default:` —
`:774-775` for the header, `:794-795` for the footer. The header's default
happens to return the Hidden string, so the mapping is right today by
coincidence of ordering rather than by a case that states it. The consequence
is that a fifth zone constant would silently title itself "Hidden" and take the
generic footer, with nothing failing to compile and no case to update; the two
switches would need editing before `ZONE_COUNT` (`ComposerButtons.java:24`)
were raised.

The "up to 2" in the Leading label comes from `START_CAPACITY = 2`, declared in
`ComposerButtons.java:36`, and is enforced at
`ComposerLayoutActivity.java:645-646`. Nothing about `ZONE_START` carries the
cap, so it is not readable from the zone constant alone.

The trap is that the source's own comments use the internal vocabulary — see
"Only Middle/Trailing/Hidden ever produce this row"
(`ComposerLayoutActivity.java:649`) — so reading the code leaves you fluent in
names no user ever sees. That is how `FEATURES.md` came to document the zones
as "Leading, Middle, Trailing, Hidden": the internal name leaked into a
user-facing catalog and sat there uncorrected, because the catalog carried no
figure of that screen to contradict it — raw captures of it exist, but nothing
put the screen and the prose side by side until one was committed. Writing
about this screen, use the labels; reading the code, expect the constants.

*(Established 2026-09-13, #docs.)*

## Chat header icons and ⋮ rows are placed by one per-chat controller

`ChatMenuController` is created for every chat mode before the header block,
because the ⋮ block also runs where the header block doesn't (centred title,
suggestions) (`org/telegram/ui/ChatActivity.java:5032`). Header icons register
on each side of upstream's Call icon, since registration order is screen order
(`:5037`, `:5051`). Each configurable ⋮ site calls `nkChatMenu.add(...)` under
its own conditions; `finish` then sorts those rows among the slots they occupy
in `ActionBarMenuItem.lazyList`, which nothing lays out until the first
`toggleSubMenu` (`:5328`; `org/telegram/ui/ActionBar/ActionBarMenuItem.java:827`,
`:2762`, `:2773`). Header-icon visibility funnels through `nkUpdateChatMenu`
(`ChatActivity.java:32313`).

Upstream's fixed 52/92dp title margin assumes 48dp icons, but Glass gives each
menu item -5dp side margins (`org/telegram/ui/ActionBar/ActionBarMenu.java:666`),
so the margin is recomputed from the visible items after upstream's block
(`ChatActivity.java:20298`).

*(Established 2026-10-07.)*
