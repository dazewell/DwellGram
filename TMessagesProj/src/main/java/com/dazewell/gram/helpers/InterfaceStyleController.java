package com.dazewell.gram.helpers;

import android.graphics.Paint;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;

import xyz.nextalone.nagram.NaConfig;

public class InterfaceStyleController {
    private static final int FILTER_TAB_SELECTOR_ALPHA_LEGACY = 31;
    private static final int FILTER_TAB_SELECTOR_ALPHA_MD3 = 46;
    private static final int FILTER_TAB_SELECTOR_ALPHA_STROKE = 255;

    public static boolean isMaterialDesign3() {
        return !(LiteMode.isLiquidGlassSupported() && LiteMode.isEnabledSetting(LiteMode.FLAG_LIQUID_GLASS));
    }

    public static boolean applyChatHeader() {
        return isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStyleApplyChatHeader().Bool();
    }

    public static boolean applyChatListTopBar() {
        return isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStyleApplyChatListTopBar().Bool();
    }

    public static boolean applyButtons() {
        return isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStyleApplyButtons().Bool();
    }

    public static final boolean COMPOSER_STYLE_AVAILABLE = true;

    public static boolean applyComposer() {
        return COMPOSER_STYLE_AVAILABLE && isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStyleApplyComposer().Bool();
    }

    // The MD3 composer as a flat full-width panel on the bottom edge and the keyboard, instead of a floating island.
    public static boolean dockedComposer() {
        return applyComposer() && NaConfig.INSTANCE.getInterfaceStyleDockedComposer().Bool();
    }

    public static boolean applyBottomNavigation() {
        return isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStyleApplyBottomNavigation().Bool();
    }

    // A stadium panel whose selected highlight covers the whole tab, label included.
    public static boolean roundedBottomNavigation() {
        return applyBottomNavigation() && NaConfig.INSTANCE.getInterfaceStyleRoundedNavigation().Bool();
    }

    public static boolean panelDividers() {
        return isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStylePanelDividers().Bool();
    }

    // Solid Classic/Day chat header and chat-list top bar, rendered by InterfaceStyleSolidHeader.
    public static final boolean MATCH_CLASSIC_DAY_HEADER_AVAILABLE = true;

    public static int filterTabSelectorAlpha(boolean strokeStyle) {
        if (strokeStyle) {
            return FILTER_TAB_SELECTOR_ALPHA_STROKE;
        }
        return applyChatListTopBar() ? FILTER_TAB_SELECTOR_ALPHA_MD3 : FILTER_TAB_SELECTOR_ALPHA_LEGACY;
    }

    private static final float PANEL_DIVIDER_ON_SURFACE_BLEND = 0.12f;
    private static final Paint panelDividerPaint = new Paint();

    // MD3 outline-variant: the surface the line sits on, nudged 12% towards its text colour.
    // Theme dividers are pure black in Night/AMOLED, which is far too loud for a panel edge.
    public static Paint panelDividerPaint(int surfaceColor, Theme.ResourcesProvider resourcesProvider) {
        final int onSurface = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        panelDividerPaint.setColor(ColorUtils.blendARGB(ColorUtils.setAlphaComponent(surfaceColor, 255), ColorUtils.setAlphaComponent(onSurface, 255), PANEL_DIVIDER_ON_SURFACE_BLEND));
        return panelDividerPaint;
    }

    public static int chatHeaderSurfaceColor(Theme.ResourcesProvider resourcesProvider) {
        final boolean isDark = resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
        return Theme.getColor(isDark ? Theme.key_actionBarDefault : Theme.key_chat_topPanelBackground, resourcesProvider);
    }
}
