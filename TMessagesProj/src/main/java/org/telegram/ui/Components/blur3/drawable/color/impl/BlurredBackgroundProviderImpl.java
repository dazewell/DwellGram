package org.telegram.ui.Components.blur3.drawable.color.impl;

import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Color;

import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;

public class BlurredBackgroundProviderImpl {
    public static BlurredBackgroundProvider mainTabs(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                final int colorTarget = Theme.getColor(Theme.key_glass_targetMainTabs, r);
                return solveSrcColor(colorBg, colorTarget, alpha);
            })
            .setStrokeColorTop(0x11000000, 0x06FFFFFF)
            .setStrokeColorBottom(0x20000000, 0x11FFFFFF)
            .setShadowColor(0x20000000, 0x04FFFFFF)
            .setShadowLayer(dpf2(2.667f), 0, dpf2(0.85f))
            .setStrokeWidth(dpf2(0.4f), dpf2(0.4f))
            .build();
    }

    // NagramX: It floats, so it takes the MD3 composer's shadow and, in dark themes where that shadow is lost, a
    // faint light edge the Glare switch gates like every other stroke. Frosted like the MD3 chat header while blur
    // is on, opaque otherwise.
    public static BlurredBackgroundProvider mainTabsBottomNavigation(int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                final int color = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && checkBlurEnabled(currentAccount, r)) {
                    return Theme.multAlpha(color, xyz.nextalone.nagram.NaConfig.interfaceStyleBlurAlpha());
                }
                return ColorUtils.setAlphaComponent(color, 255);
            })
            .setStrokeColorTop(0, 0x28FFFFFF)
            .setStrokeColorBottom(0, 0x14FFFFFF)
            .setShadowColor((r, isDark) -> ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelShadow, r), 77))
            .setShadowLayer(dpf2(4), 0, dpf2(2))
            .setStrokeWidth(dpf2(1), dpf2(1))
            .build();
    }

    // NagramX (#interface-style): gated overload for the attach sheet tab bar, the Statistics tabs
    // and MediaActivity's stories tabs. mainTabs() takes no account for checkBlurEnabled, so gating it in place would mean
    // threading one through its only other callers (MainTabsActivity, MainTabsPreviewCell), which
    // already branch on applyBottomNavigation() themselves and use mainTabs() as their own
    // glass-off side.
    public static BlurredBackgroundProvider sheetTabsBottomNavigation(int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        if (!com.dazewell.gram.helpers.InterfaceStyleController.applyBottomNavigation()) {
            return mainTabs(resourcesProvider);
        }
        return mainTabsBottomNavigation(currentAccount, resourcesProvider);
    }

    public static BlurredBackgroundProvider topPanel(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                final int colorTarget = Theme.getColor(Theme.key_glass_targetMainTopPanel, r);
                return solveSrcColor(colorBg, colorTarget, alpha);
            })
            .setStrokeColorTop(0x11000000, 0x06FFFFFF)
            .setStrokeColorBottom(0x20000000, 0x11FFFFFF)
            .setShadowColor(0x20000000, 0x04FFFFFF)
            .setShadowLayer(dpf2(2.667f), 0, dpf2(0.85f))
            .setStrokeWidth(dpf2(0.4f), dpf2(0.4f))
            .build();
    }

    // NagramX (#interface-style): gated overload for topPanel's two in-scope callers under Apply to ->
    // Buttons - the attach sheet's Audio search field and SelectAudioAlert's search field. A separate
    // method rather than gating topPanel in place, same reason as dialogsTopPanel/chatHeaderPanel:
    // topPanel has other unrelated callers (profile shared media, bookmarks) that must keep the plain
    // glass look.
    public static BlurredBackgroundProvider attachSheetSearchPanel(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean frostedSourceAvailable) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                if (com.dazewell.gram.helpers.InterfaceStyleController.applyButtons()) {
                    final int color = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                    if (frostedSourceAvailable && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && checkBlurEnabled(currentAccount, resourcesProvider)) {
                        return Theme.multAlpha(color, xyz.nextalone.nagram.NaConfig.interfaceStyleBlurAlpha());
                    }
                    return ColorUtils.setAlphaComponent(color, 255);
                }
                final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                final int colorTarget = Theme.getColor(Theme.key_glass_targetMainTopPanel, r);
                return solveSrcColor(colorBg, colorTarget, alpha);
            })
            .setStrokeColorTop((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x06FFFFFF : 0x11000000))
            .setStrokeColorBottom((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x11FFFFFF : 0x20000000))
            .setShadowColor((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x04FFFFFF : 0x20000000))
            .setShadowLayer(dpf2(2.667f), 0, dpf2(0.85f))
            .setStrokeWidth(dpf2(0.4f), dpf2(0.4f))
            .build();
    }

    // NagramX (#interface-style): gated overload for topPanel's other two in-scope callers under Apply
    // to -> Buttons - the attach sheet's gallery fast-scroll circle and drag-time date/letter tag
    // (ChatAttachAlert). Opaque, no rim, no shadow, same family as emojiViewButton; unlike
    // attachSheetSearchPanel above, a small floating pill never frosts.
    public static BlurredBackgroundProvider attachSheetPill(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                if (com.dazewell.gram.helpers.InterfaceStyleController.applyButtons()) {
                    return ColorUtils.setAlphaComponent(colorBg, 255);
                }
                final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                final int colorTarget = Theme.getColor(Theme.key_glass_targetMainTopPanel, r);
                return solveSrcColor(colorBg, colorTarget, alpha);
            })
            .setStrokeColorTop((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x06FFFFFF : 0x11000000))
            .setStrokeColorBottom((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x11FFFFFF : 0x20000000))
            .setShadowColor((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x04FFFFFF : 0x20000000))
            .setShadowLayer(dpf2(2.667f), 0, dpf2(0.85f))
            .setStrokeWidth(dpf2(0.4f), dpf2(0.4f))
            .build();
    }

    // NagramX: DialogsActivity needs an Interface Style branch without changing the many unrelated
    // generic topPanel consumers such as media pickers and shared-media tabs.
    public static BlurredBackgroundProvider dialogsTopPanel(int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                if (com.dazewell.gram.helpers.InterfaceStyleController.applyChatListTopBar()) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && checkBlurEnabled(currentAccount, resourcesProvider)) {
                        return Theme.multAlpha(Theme.getColor(Theme.key_actionBarDefault, r), xyz.nextalone.nagram.NaConfig.interfaceStyleBlurAlpha());
                    }
                    return ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_actionBarDefault, r), 255);
                }
                final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                final int colorTarget = Theme.getColor(Theme.key_glass_targetMainTopPanel, r);
                return solveSrcColor(colorBg, colorTarget, alpha);
            })
            .setStrokeColorTop((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyChatListTopBar() ? 0 : (isDark ? 0x06FFFFFF : 0x11000000))
            .setStrokeColorBottom((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyChatListTopBar() ? 0 : (isDark ? 0x11FFFFFF : 0x20000000))
            .setShadowColor((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyChatListTopBar() ? 0 : (isDark ? 0x04FFFFFF : 0x20000000))
            .setShadowLayer(dpf2(2.667f), 0, dpf2(0.85f))
            .setStrokeWidth(dpf2(0.4f), dpf2(0.4f))
            .build();
    }

    public static BlurredBackgroundProvider tabsPanelNoShadow(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) ->
                Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite, r), 0.88f))
            .setStrokeColorTop(0, 0)
            .setStrokeColorBottom(0, 0)
            .setShadowColor(0, 0)
            .build();
    }

    public static BlurredBackgroundProvider emojiViewButton(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                    final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                    return com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? ColorUtils.setAlphaComponent(colorBg, 255) : Theme.multAlpha(colorBg, alpha); // NagramX: opaque, a translucent fill shows its own blur
                })
                .setStrokeColorTop((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x28FFFFFF : 0xFFFFFFFF)) // NagramX: flat under MD3 Buttons
                .setStrokeColorBottom((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x14FFFFFF : 0xFFFFFFFF))
                .setShadowColor((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0 : 0x40000000))
                .setShadowLayer(dpf2(11 / 3f), 0, dpf2(2 / 3f))
                .setStrokeWidth(dpf2(0.5f), dpf2(0.5f))
                .build();
    }

    public static BlurredBackgroundProvider counterMini(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final float alpha = 0.075f;
                    final int colorBg = Color.BLACK;
                    return Theme.multAlpha(colorBg, alpha);
                })
                .setStrokeColorTop(0x60FFFFFF, 0x50FFFFFF)
                .setStrokeColorBottom(0x24000000, 0x24000000)
                .setShadowColor(0, 0)
                .setShadowLayer(0, 0, 0)
                .setStrokeWidth(dpf2(0.43f), dpf2(0.43f))
                .build();
    }

    public static BlurredBackgroundProvider messageMenuReactionsBackground(Theme.ResourcesProvider resourcesProvider) {
        return messageMenuBackground(resourcesProvider);
    }

    public static BlurredBackgroundProvider messageMenuBackground(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    if (!LiteMode.isEnabled(LiteMode.FLAG_CHAT_BLUR)) {
                        return Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground);
                    }
                    // NagramX: follow the Blur strength slider like the header and composer; upstream's fixed 82.5-85% read as flat.
                    return Theme.multAlpha(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground), xyz.nextalone.nagram.NaConfig.interfaceStyleBlurAlpha());
                })
                .setStrokeColorTop((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0 : 0x44FFFFFF)) // NagramX: flat under MD3 Buttons, keep only the shadow
                .setStrokeColorBottom((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0 : 0x22FFFFFF))
                .setShadowColor(0x38000000, 0)
                .setShadowLayer(dpf2(3.5f), 0, 0)
                .setStrokeWidth(dpf2(2 / 3f), dpf2(2 / 3f))
                .build();
    }

    public static BlurredBackgroundProvider scrimMenuBackground(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                final int colorBg = Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, r);
                if (com.dazewell.gram.helpers.InterfaceStyleController.applyButtons()) {
                    // NagramX: flat surface colour under MD3 Buttons; still follows the Blur strength slider when chat blur is on
                    if (!LiteMode.isEnabled(LiteMode.FLAG_CHAT_BLUR)) {
                        return colorBg;
                    }
                    return Theme.multAlpha(colorBg, xyz.nextalone.nagram.NaConfig.interfaceStyleBlurAlpha());
                }
                return Theme.multAlpha(colorBg, isDark ? 0.85f : 0.825f);
            })
            .setStrokeColorTop((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0 : 0x44FFFFFF)) // NagramX: flat under MD3 Buttons, keep only the shadow
            .setStrokeColorBottom((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0 : 0x22FFFFFF))
            .setShadowColor(0x26000000, 0)
            .setShadowLayer(dpf2(4f), 0, 0)
            .setStrokeWidth(dpf2(2 / 3f), dpf2(2 / 3f))
            .build();
    }

    // NagramX (#interface-style): gated in place, not an overload - every caller here is one of the
    // attach sheet's own search fields (Contacts, Quick replies), none shared elsewhere.
    public static BlurredBackgroundProvider attachMenuSearch(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean frostedSourceAvailable) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                    if (com.dazewell.gram.helpers.InterfaceStyleController.applyButtons()) {
                        if (frostedSourceAvailable && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && checkBlurEnabled(currentAccount, resourcesProvider)) {
                            return Theme.multAlpha(colorBg, xyz.nextalone.nagram.NaConfig.interfaceStyleBlurAlpha());
                        }
                        return ColorUtils.setAlphaComponent(colorBg, 255);
                    }
                    final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                    return Theme.multAlpha(colorBg, alpha);
                })
                .setStrokeColorTop((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x17FFFFFF : 0x17000000))
                .setStrokeColorBottom((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x17FFFFFF : 0x17000000))
                .setShadowColor((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x04FFFFFF : 0x11000000))
                .setShadowLayer(dpf2(2), 0, dpf2(1 / 3f))
                .setStrokeWidth(dpf2(0.4f), dpf2(0.4f))
                .build();
    }

    public static BlurredBackgroundProvider searchFloatingDate(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> 0x33000000)
                .setStrokeColorTop(0x17000000, 0x17FFFFFF)
                .setStrokeColorBottom(0x17000000, 0x17FFFFFF)
                .setShadowColor(0, 0)
                .setStrokeWidth(1, 1)
                .build();
    }

    public static BlurredBackgroundProvider bottomPanelChatActivity(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    if (!checkBlurEnabled(resourcesProvider)) {
                        return ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelBackground, r), 255);
                    }

                    final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                    final int colorBg = Theme.getColor(Theme.key_chat_messagePanelBackground, r);
                    return Theme.multAlpha(colorBg, alpha);
                })
                .setStrokeColorTop(0xFFFFFFFF, 0x28FFFFFF)
                .setStrokeColorBottom(0xFFFFFFFF, 0x14FFFFFF)
                .setShadowColor(0x20000000, 0)
                //.setShadowLayer(dpf2(10 / 3f), 0, dpf2(2 / 3f))
                .setStrokeWidth(dpf2(0.5f), dpf2(0.5f))
                .build();
    }

    // NagramX: split out of bottomPanelChatActivity (#671) rather than gating that shared factory in
    // place, because ComposerGlassProvider.channelButtons()'s fallback to bottomPanelChatActivity()
    // depends on it staying plain glass whenever Buttons is off, regardless of Composer's own setting.
    public static BlurredBackgroundProvider giftNoteComposerPanel(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    // NagramX: the gift note sheet's input panel is the chat composer under another name,
                    // so it frosts at the Blur strength slider under MD3 Composer (dazewell's call on #671,
                    // not Buttons); opaque otherwise, same as the non-MD3 "blur disabled" branch below.
                    if (com.dazewell.gram.helpers.InterfaceStyleController.applyComposer()) {
                        final int colorBg = Theme.getColor(Theme.key_chat_messagePanelBackground, r);
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && checkBlurEnabled(resourcesProvider)) {
                            return Theme.multAlpha(colorBg, xyz.nextalone.nagram.NaConfig.interfaceStyleBlurAlpha());
                        }
                        return ColorUtils.setAlphaComponent(colorBg, 255);
                    }
                    if (!checkBlurEnabled(resourcesProvider)) {
                        return ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelBackground, r), 255);
                    }

                    final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                    final int colorBg = Theme.getColor(Theme.key_chat_messagePanelBackground, r);
                    return Theme.multAlpha(colorBg, alpha);
                })
                .setStrokeColorTop((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyComposer() ? 0 : (isDark ? 0x28FFFFFF : 0xFFFFFFFF))
                .setStrokeColorBottom((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyComposer() ? 0 : (isDark ? 0x14FFFFFF : 0xFFFFFFFF))
                .setShadowColor((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyComposer() ? 0 : (isDark ? 0 : 0x20000000))
                //.setShadowLayer(dpf2(10 / 3f), 0, dpf2(2 / 3f))
                .setStrokeWidth(dpf2(0.5f), dpf2(0.5f))
                .build();
    }

    public static BlurredBackgroundProvider topPanelChatActivity(Theme.ResourcesProvider resourcesProvider) {
        return topPanelChatActivity(UserConfig.selectedAccount, resourcesProvider);
    }

    public static BlurredBackgroundProvider chatHeaderPanel(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean frostedSourceAvailable) {
        return topPanelChatActivity(currentAccount, resourcesProvider, true, frostedSourceAvailable, null);
    }

    // NagramX: the Classic/Day solid header is for the header alone; the pinned/join strip and the topic and
    // hashtag tabs also use chatHeaderPanel and keep their frosted theme colour. Selection mode keeps its usual header too.
    public static BlurredBackgroundProvider chatHeaderSurface(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean frostedSourceAvailable, org.telegram.ui.ActionBar.ActionBar actionBar) {
        return topPanelChatActivity(currentAccount, resourcesProvider, true, frostedSourceAvailable, actionBar);
    }

    // NagramX: account-aware overload so bubble chats (which run under a
    // notification's account, not the globally selected one) evaluate blur
    // eligibility against the right account's config.
    public static BlurredBackgroundProvider topPanelChatActivity(int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        return topPanelChatActivity(currentAccount, resourcesProvider, false, false, null);
    }

    private static BlurredBackgroundProvider topPanelChatActivity(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean flatMd3Chrome, boolean frostedSourceAvailable, org.telegram.ui.ActionBar.ActionBar solidHeaderActionBar) {
        // compact mode's glass follows the design: no white outline, a soft shadow and a tight one as its outline
        return new BlurredBackgroundProviderBuilder(resourcesProvider) {
                @Override
                public int getStrokeColorTop() {
                    return compactGlassStroke(super.getStrokeColorTop(), resourcesProvider);
                }

                @Override
                public int getStrokeColorBottom() {
                    return compactGlassStroke(super.getStrokeColorBottom(), resourcesProvider);
                }

                @Override
                public int getShadowColor() {
                    return Theme.isCompactMode(resourcesProvider) ? Theme.getColor(Theme.key_chat_compact_glassShadow, resourcesProvider) : super.getShadowColor();
                }

                @Override
                public float getShadowRadius() {
                    return Theme.isCompactMode(resourcesProvider) ? dpf2(2.25f) : super.getShadowRadius();
                }

                @Override
                public float getShadowDy() {
                    return Theme.isCompactMode(resourcesProvider) ? dpf2(1) : super.getShadowDy();
                }

                @Override
                public int getOutlineShadowColor() {
                    return Theme.isCompactMode(resourcesProvider) ? Theme.getColor(Theme.key_chat_compact_glassHairline, resourcesProvider) : 0;
                }

                @Override
                public int getOutlineColor() {
                    return Theme.isCompactMode(resourcesProvider) ? Theme.getColor(Theme.key_chat_compact_glassOutline, resourcesProvider) : 0;
                }

                @Override
                public float getOutlineWidth() {
                    return dpf2(0.6f);
                }
            }
                .setBackgroundColor((r, isDark) -> {
                    // NagramX: MD3 keeps the pre-Glass theme colour instead of the glass target tint.
                    final boolean md3Header = flatMd3Chrome && com.dazewell.gram.helpers.InterfaceStyleController.applyChatHeader();
                    if (md3Header && solidHeaderActionBar != null && !solidHeaderActionBar.isActionModeShowed() && com.dazewell.gram.helpers.InterfaceStyleSolidHeader.chatHeader()) {
                        return com.dazewell.gram.helpers.InterfaceStyleSolidHeader.chatHeaderSurface(Theme.getColor(isDark ? Theme.key_actionBarDefault : Theme.key_chat_topPanelBackground, r));
                    }
                    if (md3Header && frostedSourceAvailable && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && checkBlurEnabled(currentAccount, resourcesProvider)) {
                        return Theme.multAlpha(Theme.getColor(isDark ? Theme.key_actionBarDefault : Theme.key_chat_topPanelBackground, r), xyz.nextalone.nagram.NaConfig.interfaceStyleBlurAlpha());
                    }
                    if (md3Header || !checkBlurEnabled(currentAccount, resourcesProvider)) {
                        return ColorUtils.setAlphaComponent(Theme.getColor(isDark ?
                            Theme.key_actionBarDefault : Theme.key_chat_topPanelBackground, r), 255);
                    }

                    final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                    final int colorBg = Theme.getColor(Theme.key_chat_topPanelBackground, r);
                    return Theme.multAlpha(colorBg, alpha);
                })
                .setStrokeColorTop((r, isDark) -> flatMd3Chrome && com.dazewell.gram.helpers.InterfaceStyleController.applyChatHeader() ? 0 : (isDark ? 0x20FFFFFF : 0xFFFFFFFF))
                .setStrokeColorBottom((r, isDark) -> flatMd3Chrome && com.dazewell.gram.helpers.InterfaceStyleController.applyChatHeader() ? 0 : (isDark ? 0x14FFFFFF : 0xFFFFFFFF))
                .setShadowColor((r, isDark) -> flatMd3Chrome && com.dazewell.gram.helpers.InterfaceStyleController.applyChatHeader() ? 0 : (isDark ? 0 : 0x20000000))
                //.setShadowLayer(dpf2(10 / 3f), 0, dpf2(2 / 3f))
                .setStrokeWidth(dpf2(0.55f), dpf2(0.55f))
                .build();
    }

    // with the design's outline there is no white highlight on the glass
    private static int compactGlassStroke(int color, Theme.ResourcesProvider resourcesProvider) {
        if (!Theme.isCompactMode(resourcesProvider)) return color;
        return Color.alpha(Theme.getColor(Theme.key_chat_compact_glassOutline, resourcesProvider)) != 0 ? 0 : color;
    }

    // NagramX (#interface-style): gated in place, not an overload - every caller is one of the floating
    // back/menu/done pills (and lookalikes: tab bars, filter chips, map pills) on a simple-ActionBar screen,
    // same reasoning as attachMenuSearch above. frostedSourceAvailable is BaseFragmentSimpleGlass's structural
    // hasFrostedGlassSource(), not its live getFrostedGlassSource() - every caller captures this once.
    public static BlurredBackgroundProvider simpleActionBar(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean frostedSourceAvailable) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                if (com.dazewell.gram.helpers.InterfaceStyleController.applyButtons()) {
                    final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                    if (frostedSourceAvailable && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && checkBlurEnabled(currentAccount, resourcesProvider)) {
                        return Theme.multAlpha(colorBg, xyz.nextalone.nagram.NaConfig.interfaceStyleBlurAlpha());
                    }
                    return ColorUtils.setAlphaComponent(colorBg, 255);
                }
                final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.80f;
                final int colorBg = Theme.getColor(Theme.key_windowBackgroundGray, r);
                final int colorTarget = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                return solveSrcColor(colorBg, colorTarget, alpha);
            })
            .setStrokeColorTop((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x20FFFFFF : 0xFFFFFFFF))
            .setStrokeColorBottom((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x14FFFFFF : 0xFFFFFFFF))
            .setShadowColor((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0 : 0x16333333))
            .setShadowLayer(dpf2(24 / 3f), 0, dpf2(7f / 3f))
            .setStrokeWidth(dpf2(0.55f), dpf2(0.55f))
            .build();
    }

    /** A frosted sheet over the content, e.g. the list over the map: the content shows through its blur. */
    // NagramX (#interface-style): gated in place, same reasoning as simpleActionBar() above - its one
    // caller (LocationActivity's sheet) is itself a simple-ActionBar screen.
    public static BlurredBackgroundProvider frostedSheet(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean frostedSourceAvailable) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                if (com.dazewell.gram.helpers.InterfaceStyleController.applyButtons()) {
                    final int colorBg = Theme.getColor(Theme.key_dialogBackground, r);
                    if (frostedSourceAvailable && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && checkBlurEnabled(currentAccount, resourcesProvider)) {
                        return Theme.multAlpha(colorBg, xyz.nextalone.nagram.NaConfig.interfaceStyleBlurAlpha());
                    }
                    return ColorUtils.setAlphaComponent(colorBg, 255);
                }
                return Theme.multAlpha(Theme.getColor(Theme.key_dialogBackground, r), isDark ? 0.82f : 0.78f);
            })
            .setStrokeColorTop((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x20FFFFFF : 0xFFFFFFFF))
            .setStrokeColorBottom(0, 0)
            .setShadowColor((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0 : 0x16333333))
            .setShadowLayer(dpf2(24 / 3f), 0, -dpf2(2f / 3f))
            .setStrokeWidth(dpf2(0.55f), 0)
            .build();
    }

    public static BlurredBackgroundProvider topPanelChatActivityTags(Theme.ResourcesProvider resourcesProvider) {
        return topPanelChatActivityTags(UserConfig.selectedAccount, resourcesProvider, false);
    }

    // NagramX: same account-aware fix as topPanelChatActivity, for the
    // hashtag/tag search strip that shares its blur-eligibility check.
    public static BlurredBackgroundProvider topPanelChatActivityTags(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean frostedSourceAvailable) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                // NagramX: the tag/search strip is visually part of the chat header surface.
                final boolean md3Header = com.dazewell.gram.helpers.InterfaceStyleController.applyChatHeader();
                if (md3Header && frostedSourceAvailable && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && checkBlurEnabled(currentAccount, resourcesProvider)) {
                    return Theme.multAlpha(Theme.getColor(isDark ? Theme.key_actionBarDefault : Theme.key_chat_topPanelBackground, r), xyz.nextalone.nagram.NaConfig.interfaceStyleBlurAlpha());
                }
                if (md3Header || !checkBlurEnabled(currentAccount, resourcesProvider)) {
                    return ColorUtils.setAlphaComponent(Theme.getColor(isDark ?
                            Theme.key_actionBarDefault : Theme.key_chat_topPanelBackground, r), 255);
                }

                final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                final int colorBg = Theme.getColor(Theme.key_chat_topPanelBackground, r);
                return Theme.multAlpha(colorBg, alpha);
            })
            .setStrokeColorTop(0, 0)
            .setStrokeColorBottom(0, 0)
            .setShadowColor(0, 0)
            .setShadowLayer(0, 0, 0)
            .setStrokeWidth(0, 0)
            .build();
    }

    public static BlurredBackgroundProvider topPanelChatActivitySearchListBg(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final float alpha = 0.7f;
                    final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                    return Theme.multAlpha(colorBg, alpha);
                })
                .setStrokeColorTop(0, 0)
                .setStrokeColorBottom(0, 0)
                .setShadowColor(0, 0)
                .setShadowLayer(0, 0, 0)
                .setStrokeWidth(0, 0)
                .build();
    }

    public static BlurredBackgroundProvider bulletin(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                final int colorBg = Theme.getColor(Theme.key_undo_background, r);
                if (com.dazewell.gram.helpers.InterfaceStyleController.applyButtons()) {
                    // NagramX: opaque bar under MD3 Buttons; still follows the Blur strength slider when chat blur is on
                    if (!LiteMode.isEnabled(LiteMode.FLAG_CHAT_BLUR)) {
                        return ColorUtils.setAlphaComponent(colorBg, 255);
                    }
                    return Theme.multAlpha(colorBg, xyz.nextalone.nagram.NaConfig.interfaceStyleBlurAlpha());
                }
                final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                return Theme.multAlpha(colorBg, alpha);
            })
            //.setStrokeColorTop(0xFFFFFFFF, 0x28FFFFFF)
            //.setStrokeColorBottom(0xFFFFFFFF, 0x14FFFFFF)
            //.setShadowColor(0x20000000, 0)
            //.setShadowLayer(dpf2(10 / 3f), 0, dpf2(2 / 3f))
            .setStrokeWidth(dpf2(0.5f), dpf2(0.5f))
            .build();
    }

    /** A hint (HintView2): dark in both themes for its white text, an even 1px stroke along the edge and the arrow. */
    public static BlurredBackgroundProvider hint(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.82f : 0.76f;
                final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                final int colorTarget = 0xFF282828;
                return isDark ? Theme.multAlpha(0xFF111111, 0.9f) : solveSrcColor(colorBg, colorTarget, alpha);
            })
            .setStrokeColorTop(0x33FFFFFF, 0x33FFFFFF)
            .setStrokeColorBottom(0x1AFFFFFF, 0x1AFFFFFF)
            .setShadowColor(0x30333333, 0x30000000)
            .setShadowLayer(dpf2(6f), 0, 0)
            .setStrokeWidth(1, 1)
            .build();
    }

    public static BlurredBackgroundProvider inputFieldDialogActivity(Theme.ResourcesProvider resourcesProvider) {
        return topPanel(resourcesProvider);
    }

    // NagramX (#interface-style): gated overload for inputFieldDialogActivity's only two callers - the
    // attach sheet's caption islands and the forward picker's comment field (ChatAttachAlert, DialogsActivity).
    // Added as a separate method rather than gating inputFieldDialogActivity/topPanel in place, same reason as
    // dialogsTopPanel/chatHeaderPanel: topPanel has ~10 unrelated callers (Calls/topics banners, profile
    // shared media, bookmarks, attach audio, SelectAudioAlert) that must keep the plain glass look.
    public static BlurredBackgroundProvider sheetCaptionInput(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean frostedSourceAvailable) {
        if (!com.dazewell.gram.helpers.InterfaceStyleController.applyComposer()) {
            return inputFieldDialogActivity(resourcesProvider);
        }
        return com.dazewell.gram.composer.ComposerMd3Surface.sheetCaptionProvider(currentAccount, resourcesProvider, frostedSourceAvailable);
    }

    public static BlurredBackgroundProvider inputFieldShareAlert(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean frostedSourceAvailable) {
        // NagramX (#interface-style): gated in place, not an overload - every caller here is one of
        // ShareAlert's own floating islands (caption, picker-bottom row, timestamp row), none shared elsewhere.
        if (com.dazewell.gram.helpers.InterfaceStyleController.applyComposer()) {
            return com.dazewell.gram.composer.ComposerMd3Surface.sheetCaptionProvider(currentAccount, resourcesProvider, frostedSourceAvailable);
        }
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                    final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                    final int colorTarget = Theme.getColor(Theme.key_chat_messagePanelBackground, r);
                    return solveSrcColor(colorBg, colorTarget, alpha);
                })
                .setStrokeColorTop(0x28FFFFFF, 0x28FFFFFF)
                .setStrokeColorBottom(0x14FFFFFF, 0x14FFFFFF)
                .setShadowColor(0x20000000, 0)
                .setShadowLayer(dpf2(10 / 3f), 0, dpf2(2 / 3f))
                .setStrokeWidth(dpf2(1), dpf2(2 / 3f))
                .build();
    }

    public static BlurredBackgroundProvider photoViewer(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
                    final int colorBg = 0xFF000000;
                    final int colorTarget = 0xFF1A1A1A;
                    return 0; // solveSrcColor(colorBg, colorTarget, alpha);
                })
                .setStrokeColorTop(0x28FFFFFF, 0x28FFFFFF)
                .setStrokeColorBottom(0x14FFFFFF, 0x14FFFFFF)
                .setStrokeWidth(dpf2(2 / 3f), dpf2(2 / 3f))
                .build();
    }

    public static BlurredBackgroundProvider photoViewerMenu(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> 0x40000000)
                .setStrokeColorTop(0x28FFFFFF, 0x28FFFFFF)
                .setStrokeColorBottom(0x14FFFFFF, 0x14FFFFFF)
                .setStrokeWidth(dpf2(2 / 3f), dpf2(2 / 3f))
                .build();
    }

    // NagramX (#interface-style): gated overload for the photo editor's caption box, its mention/emoji
    // backdrops, the move-caption pill and the tools pill. Not an edit to photoViewer() in place: its other
    // callers (delete button, cover-edit, mute button, poll-attach controls, the paint tool's own sub-toolbar,
    // the poll-mode back button) keep the unconverted Glass look.
    public static BlurredBackgroundProvider photoViewerComposer(int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        if (!com.dazewell.gram.helpers.InterfaceStyleController.applyComposer()) {
            return photoViewer(resourcesProvider);
        }
        final boolean frosted = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && checkBlurEnabled(currentAccount, resourcesProvider);
        // A fixed dark tone, not a theme key - this chrome floats over arbitrary media and has to stay legible
        // over a bright photo regardless of the app's light/dark theme. 0xFF1A1A1A is the colorTarget photoViewer()
        // already names above but never uses (its fill is hard-coded transparent).
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> frosted
                        ? Theme.multAlpha(0xFF1A1A1A, com.dazewell.gram.composer.ComposerMd3Surface.frostAlpha())
                        : 0xFF1A1A1A)
                .setShadowColor((r, isDark) -> 0x40000000)
                .setShadowLayer(dpf2(10 / 3f), 0, dpf2(2 / 3f))
                .build();
    }

    // NagramX (#interface-style): the send button's blur drawable is a halo drawn behind the opaque accent
    // circle (ChatActivityEnterView's SendButton, 7dp bigger than the circle, painted first). A tonal fill
    // there would newly show as a ring around the button, so MD3 only drops the stroke; the fill stays the
    // same hard-coded-transparent return as photoViewer() above.
    public static BlurredBackgroundProvider photoViewerSendButtonRing(Theme.ResourcesProvider resourcesProvider) {
        if (!com.dazewell.gram.helpers.InterfaceStyleController.applyComposer()) {
            return photoViewer(resourcesProvider);
        }
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> 0)
                .build();
    }

    public static BlurredBackgroundProvider premiumButton(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                final int colorBg = Theme.getColor(Theme.key_dialogBackground, r);
                // NagramX: this confirm pill (Name colour, Premium preview, the gift-selection confirm
                // pill) is a button, not a panel, so under MD3 Buttons it goes fully opaque like
                // Md3ButtonColorProvider rather than frosting.
                if (com.dazewell.gram.helpers.InterfaceStyleController.applyButtons()) {
                    return ColorUtils.setAlphaComponent(colorBg, 255);
                }
                return Theme.multAlpha(colorBg, 0.78f);
            })
            .setStrokeColorTop((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x20FFFFFF : 0xFFFFFFFF))
            .setStrokeColorBottom((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x20FFFFFF : 0))
            .setShadowColor((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x04FFFFFF : 0x48000000))
            .setShadowLayer(dpf2(2 / 3f), 0, 0)
            .setStrokeWidth(dpf2(0.67f), dpf2(0.67f))
            .build();
    }

    public static BlurredBackgroundProvider shadow(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            // NagramX: the resale filter pills' own fill is this factory's blur source
            // (windowBackgroundWhite, ResaleGiftsFragment's iBlur3SourceColor), nearly the same colour as
            // the screen behind them, so flat under MD3 Buttons drops only the rim, keeping the shadow
            // as their one visible edge, like messageMenuBackground/scrimMenuBackground.
            .setStrokeColorTop((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x28FFFFFF : 0))
            .setStrokeColorBottom((r, isDark) -> com.dazewell.gram.helpers.InterfaceStyleController.applyButtons() ? 0 : (isDark ? 0x14FFFFFF : 0))
            .setShadowColor(0x30000000, 0x04FFFFFF)
            .setShadowLayer(dpf2(12 / 3f), 0, dpf2(1 / 3f))
            .setStrokeWidth(dpf2(0.4f), dpf2(0.4f))
            .build();
    }

    public static int solveSrcColor(int bgColor, int outColor, float alpha) {
        alpha = MathUtils.clamp(alpha, 0, 1);

        // Edge cases
        if (alpha <= 0f) {
            return Color.argb(0, 0, 0, 0);
        }
        if (alpha >= 1f) {
            return Color.argb(255, Color.red(outColor), Color.green(outColor), Color.blue(outColor));
        }

        final int bgR = Color.red(bgColor);
        final int bgG = Color.green(bgColor);
        final int bgB = Color.blue(bgColor);

        final int outR = Color.red(outColor);
        final int outG = Color.green(outColor);
        final int outB = Color.blue(outColor);

        final float invA = 1f - alpha;

        final int srcR = MathUtils.clamp(Math.round((outR - bgR * invA) / alpha), 0, 255);
        final int srcG = MathUtils.clamp(Math.round((outG - bgG * invA) / alpha), 0, 255);
        final int srcB = MathUtils.clamp(Math.round((outB - bgB * invA) / alpha), 0, 255);

        final int a8 = MathUtils.clamp(Math.round(alpha * 255f), 0, 255);

        return Color.argb(a8, srcR, srcG, srcB);
    }

    public static boolean checkBlurEnabled(Theme.ResourcesProvider resourcesProvider) {
        return checkBlurEnabled(UserConfig.selectedAccount, resourcesProvider);
    }

    public static boolean checkBlurEnabled(int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        final boolean isDark = resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
        final boolean isLight = !isDark;
        boolean blurEnabled = SharedConfig.chatBlurEnabled();
        if (blurEnabled && isLight) {
            if (MessagesController.getInstance(currentAccount).config.disableBlurInLightTheme.get()) {
                blurEnabled = false;
            }
        }
        if (blurEnabled && isDark) {
            if (MessagesController.getInstance(currentAccount).config.disableBlurInDarkTheme.get()) {
                blurEnabled = false;
            }
        }
        return blurEnabled;
    }
}
