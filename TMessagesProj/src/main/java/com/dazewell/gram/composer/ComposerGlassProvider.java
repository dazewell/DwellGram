package com.dazewell.gram.composer;

import static org.telegram.messenger.AndroidUtilities.dpf2;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

import xyz.nextalone.nagram.NaConfig;
import com.dazewell.gram.helpers.InterfaceStyleController;

/**
 * NagramX: color provider for the composer glass family. ChatActivity keeps one default instance
 * for glass button surfaces, a composer instance for the input island, satellite close button,
 * and under-keyboard panel, and a suggestions instance for the mention/hashtag/inline-bot panel and
 * the bot "/" command list. Named (not anonymous) so it can implement BlurredBackgroundProvider on
 * top of BlurredBackgroundColorProviderThemed; BlurredBackgroundDrawable.setColorProvider only
 * picks up the shadow/stroke overrides below through that richer interface.
 */
public class ComposerGlassProvider extends BlurredBackgroundColorProviderThemed implements BlurredBackgroundProvider {
    public static final int ROLE_DEFAULT = 0;
    public static final int ROLE_COMPOSER = 1;
    public static final int ROLE_BUTTON = 2;
    // NagramX: the @mention/#hashtag/inline-bot results panel and the bot "/" command list - they float
    // above the chat list rather than sitting in the composer row, so they flatten under the same
    // applyComposer() gate but keep their shadow (see getShadowColor()) and key off their own panel colour.
    public static final int ROLE_SUGGESTIONS = 3;

    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;
    private final boolean gateOnBlurEnabled;
    private final int role;
    private final int backgroundColorId;

    /**
     * @param gateOnBlurEnabled whether getBackgroundColor() should fall back to an opaque panel
     *                          color when chat blur is disabled for this account/theme. The real
     *                          chat passes true; the settings-screen preview passes false, since it
     *                          always demonstrates the configured glass regardless of whether blur
     *                          happens to be off for the previewing account right now - it did not
     *                          gate on this before either, and gating it would be a behaviour change
     *                          nobody asked for in this pass.
     */
    public ComposerGlassProvider(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean gateOnBlurEnabled) {
        this(currentAccount, resourcesProvider, gateOnBlurEnabled, ROLE_DEFAULT);
    }

    public ComposerGlassProvider(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean gateOnBlurEnabled, int role) {
        this(currentAccount, resourcesProvider, gateOnBlurEnabled, role, Theme.key_chat_messagePanelBackground);
    }

    // NagramX: backgroundColorId lets ROLE_SUGGESTIONS key off key_windowBackgroundWhite (the
    // mention/bot-command dropdowns) instead of the composer panel colour every other role uses.
    public ComposerGlassProvider(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean gateOnBlurEnabled, int role, int backgroundColorId) {
        super(resourcesProvider, backgroundColorId);
        this.currentAccount = currentAccount;
        this.resourcesProvider = resourcesProvider;
        this.gateOnBlurEnabled = gateOnBlurEnabled;
        this.role = role;
        this.backgroundColorId = backgroundColorId;
    }

    // NagramX: theme-mode "dark", not perceived-brightness "dark" - used by getBackgroundColor() and,
    // for ROLE_COMPOSER/ROLE_BUTTON only, getShadowColor() below (NOT by the stroke getters further down,
    // and NOT by ROLE_SUGGESTIONS' own getShadowColor() branch, which deliberately keeps the inherited
    // isDark() instead - see their own comments for why). The inherited isDark()
    // (BlurredBackgroundColorProviderThemed's, perceived brightness of the panel background color) is
    // deliberately not used for the composer/button shadow: under a custom theme the panel color's
    // brightness can disagree with the theme's actual day/night mode, which would let the "dark theme
    // shadow stays 0" requirement leak a shadow in an actual dark theme with a bright custom panel color.
    private boolean isDarkTheme() {
        return resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
    }

    @Override
    public int getBackgroundColor() {
        NaConfig.migrateComposerGlassTransparency(isDarkTheme());
        if (isFlatMd3()) {
            return ColorUtils.setAlphaComponent(Theme.getColor(backgroundColorId, resourcesProvider), 255);
        }
        if (gateOnBlurEnabled && !BlurredBackgroundProviderImpl.checkBlurEnabled(currentAccount, resourcesProvider)) {
            return ColorUtils.setAlphaComponent(Theme.getColor(backgroundColorId, resourcesProvider), 255);
        }
        if (role == ROLE_SUGGESTIONS) {
            // NagramX: keeps the dropdown's original LiteMode-based glass alpha (the base class' own
            // formula) instead of the composer's user-configurable blur-strength slider - unrelated surface.
            return super.getBackgroundColor();
        }

        // NagramX: composer glass shares Interface Style's blur-strength value, capped to its old 0-50% pass-through band in NaConfig so the pill cannot disappear into the wallpaper.
        return Theme.multAlpha(Theme.getColor(backgroundColorId, resourcesProvider), NaConfig.interfaceStyleBlurAlpha());
    }

    private boolean isFlatMd3() {
        return (role == ROLE_COMPOSER || role == ROLE_SUGGESTIONS) && InterfaceStyleController.applyComposer()
                || role == ROLE_BUTTON && InterfaceStyleController.applyButtons();
    }

    // NagramX: light-theme shadow alpha bumped from the base class's 0x20000000 for the stronger 3D
    // read; dark theme stays disabled (0) - a deliberate choice, not an oversight, left for a separate
    // change once light theme has been judged on device.
    @Override
    public int getShadowColor() {
        if (role == ROLE_SUGGESTIONS) {
            // NagramX: these dropdowns float above the chat list/keyboard even when MD3 flattens the
            // fill, so they keep their pre-flatten shadow unconditionally - unchanged formula/predicate.
            return isDark() ? 0 : 0x20000000;
        }
        if (isFlatMd3()) {
            return 0;
        }
        return isDarkTheme() ? 0 : 0x30000000;
    }

    // NagramX: deliberately NOT isDarkTheme() - stroke keeps the base class's own predicate, isDark()
    // (perceived brightness of the panel background colour), matching BlurredBackgroundColorProviderThemed.
    // updateColors()'s own logic exactly. A stroke is an edge highlight whose contrast is against the
    // panel it sits on, not against the theme mode, so on a custom .attheme where a dark-mode theme has
    // an unusually bright panel colour, the stroke should still read against that bright panel - the base
    // class already gets this right and the brief never asked for stroke to change. Only shadow and
    // background follow theme day/night mode (isDarkTheme()), because dazewell's gate decision - dark
    // theme shadow stays 0 - is a statement about theme mode, not panel brightness. Computed live rather
    // than inherited from the cached fields so it can't go stale between updateColors() calls, same as
    // getBackgroundColor() above.
    @Override
    public int getStrokeColorTop() {
        if (isFlatMd3()) {
            return 0;
        }
        return isDark() ? 0x28FFFFFF : 0xFFFFFFFF;
    }

    @Override
    public int getStrokeColorBottom() {
        if (isFlatMd3()) {
            return 0;
        }
        return isDark() ? 0x14FFFFFF : 0xFFFFFFFF;
    }

    // NagramX: pinned to BlurredBackgroundDrawable's own upstream constructor defaults so implementing
    // BlurredBackgroundProvider (needed for the shadow below) doesn't also silently zero out the stroke -
    // a bare Java float field defaults to 0, which would delete the edge highlight on every surface this
    // provider feeds.
    @Override
    public float getStrokeWidthTop() {
        return dpf2(1);
    }

    @Override
    public float getStrokeWidthBottom() {
        return dpf2(2 / 3f);
    }

    // NagramX: stronger 3D shadow per dazewell's ask, architect-reviewed for the 75%-scale composer:
    // Paint.setShadowLayer's visible reach is ~2x radius (NinePatchBuilder's own blurPad budget), so
    // dpf2(3) stays inside the smallest real clearance at that scale (the row's own reserved band, dp(6)).
    // ROLE_SUGGESTIONS keeps BlurredBackgroundDrawable's own pre-existing defaults (dpf2(1)/dpf2(1/3f)) -
    // this role is only gaining a flatten gate, not the composer's separately-reviewed shadow geometry.
    @Override
    public float getShadowRadius() {
        return role == ROLE_SUGGESTIONS ? dpf2(1) : dpf2(3);
    }

    @Override
    public float getShadowDx() {
        return 0;
    }

    @Override
    public float getShadowDy() {
        return role == ROLE_SUGGESTIONS ? dpf2(1 / 3f) : dpf2(2 / 3f);
    }
}
