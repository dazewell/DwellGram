package com.dazewell.gram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.utils.glass.source.GlassSourceColor;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsLayout;

import tw.nekomimi.nekogram.helpers.MainTabsHelper;
import com.dazewell.gram.helpers.InterfaceStyleController;

/**
 * Interface Style's preview of the bottom navigation: the real tabs, configured the way MainTabsActivity.createView
 * configures them. Keep the two in step when the bar's look changes there.
 * Built once, because a new Chats tab plays its fill animation; {@link #update()} pushes the current settings through
 * the bar's own setters. The backdrop is the window colour the real bar's glass source paints first, not the
 * wallpaper, which it never sits over.
 */
@SuppressLint("ViewConstructor")
public class MainTabsPreviewCell extends FrameLayout {

    private static final int VERTICAL_PADDING = 8;

    private final Theme.ResourcesProvider resourcesProvider;
    private final int currentAccount;
    private final GlassSourceColor backgroundSource = new GlassSourceColor();
    private final BlurredBackgroundDrawableViewFactory backgroundFactory = new BlurredBackgroundDrawableViewFactory(backgroundSource);
    private final MainTabsLayout tabsView;
    private final GlassTabView[] tabs;
    private final GlassTabView contactsTab;
    private final GlassTabView settingsTab;
    private final GlassTabView callsTab;

    public MainTabsPreviewCell(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.currentAccount = currentAccount;
        this.resourcesProvider = resourcesProvider;

        tabsView = new MainTabsLayout(context, resourcesProvider);
        tabsView.setClipChildren(false);
        GlassTabView chatsTab = GlassTabView.createMainTab(context, resourcesProvider, GlassTabView.TabAnimation.CHATS, R.string.MainTabsChats);
        contactsTab = GlassTabView.createMainTab(context, resourcesProvider, GlassTabView.TabAnimation.CONTACTS, R.string.MainTabsContacts);
        settingsTab = GlassTabView.createMainTab(context, resourcesProvider, GlassTabView.TabAnimation.SETTINGS, R.string.Settings);
        callsTab = GlassTabView.createMainTab(context, resourcesProvider, GlassTabView.TabAnimation.CALLS, R.string.MainTabsCalls);
        GlassTabView profileTab = GlassTabView.createAvatar(context, resourcesProvider, currentAccount, R.string.MainTabsProfile);
        tabs = new GlassTabView[]{chatsTab, contactsTab, settingsTab, callsTab, profileTab};
        for (GlassTabView tab : tabs) {
            tabsView.addView(tab);
            tabsView.setViewVisible(tab, true, false);
        }
        chatsTab.setSelected(true, false);
        addView(tabsView);

        setClipChildren(false);
        // Decorative: the rows below it say the same thing to a screen reader
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        update();
    }

    public void update() {
        final boolean md3 = InterfaceStyleController.applyBottomNavigation();
        final boolean rounded = InterfaceStyleController.roundedBottomNavigation();
        final boolean compact = MainTabsHelper.isMainTabsHideTitleStyle();
        final int margin = MainTabsHelper.getMainTabsMargin();
        final int tabsViewWidth = MainTabsHelper.getTabsViewWidth();

        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

        if (md3) {
            final int indicatorTop = dp(MainTabsHelper.getMd3NavigationTabTop());
            final int side = dp(margin + MainTabsHelper.getMd3NavigationContentPadding());
            tabsView.setPadding(side, dp(margin) + indicatorTop, side, dp(margin) + indicatorTop);
        } else {
            final int padding = dp(margin + 4);
            tabsView.setPadding(padding, padding, padding, padding);
        }
        tabsView.setMaxWidth(md3 ? dp(MainTabsHelper.getMd3NavigationWidth() + margin * 2) : dp(328 + DialogsActivity.MAIN_TABS_MARGIN * 2));
        tabsView.setFillWidth(md3);
        tabsView.setRoundedNavigation(rounded);

        final int indicator = rounded ? GlassTabView.NAVIGATION_INDICATOR_ROUNDED : md3 ? GlassTabView.NAVIGATION_INDICATOR_MD3 : GlassTabView.NAVIGATION_INDICATOR_GLASS;
        for (GlassTabView tab : tabs) {
            tab.setMainTabsCompact(compact);
            tab.setNavigationIndicator(indicator);
            tab.updateColorsLottie();
        }
        final boolean callsVisible = UserConfig.getInstance(currentAccount).showCallsTab;
        tabsView.setViewVisible(settingsTab, !callsVisible, false);
        tabsView.setViewVisible(callsTab, callsVisible, false);
        tabsView.setViewVisible(contactsTab, !MainTabsHelper.isContactsTabHidden(), false);

        final int sideMargin = md3 ? MainTabsHelper.MD3_NAVIGATION_SIDE_GAP - margin : 0;
        tabsView.setLayoutParams(LayoutHelper.createFrame(tabsViewWidth < 0 ? LayoutHelper.MATCH_PARENT : tabsViewWidth,
                MainTabsHelper.getMainTabsHeightWithMargins(), Gravity.CENTER, sideMargin, 0, sideMargin, 0));

        // A new drawable each time: the provider differs between the pill and the MD3 panel
        backgroundSource.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        BlurredBackgroundDrawable background = backgroundFactory.create(tabsView, md3
                ? BlurredBackgroundProviderImpl.mainTabsBottomNavigation(currentAccount, resourcesProvider)
                : BlurredBackgroundProviderImpl.mainTabs(resourcesProvider));
        background.setRadius(dp(md3 ? MainTabsHelper.getMd3NavigationRadius() : MainTabsHelper.getMainTabsHeight() / 2f));
        background.setPadding(dp(md3 ? margin : margin - 0.334f));
        tabsView.setBackground(background);
        tabsView.invalidate();
    }

    // As tall as the tallest bar, the MD3 panel, so hiding titles never moves the switch under the finger
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int height = dp(MainTabsHelper.MD3_NAVIGATION_HEIGHT + MainTabsHelper.MD3_NAVIGATION_LIFT * 2 + VERTICAL_PADDING * 2);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    // No tab ever sees a touch, and returning false from onTouchEvent leaves the gesture to the list, so it still scrolls
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }
}
