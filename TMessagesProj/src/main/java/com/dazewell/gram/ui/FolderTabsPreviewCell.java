package com.dazewell.gram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.utils.glass.source.GlassSourceColor;

import tw.nekomimi.nekogram.NekoConfig;
import com.dazewell.gram.helpers.InterfaceStyleController;
import com.dazewell.gram.helpers.InterfaceStyleSolidHeader;

/**
 * Interface Style's preview of the folder tabs: the real FilterTabsView with made-up folders, configured the way
 * DialogsActivity builds it. Keep the two in step when the bar's look changes there.
 * Built once; {@link #update()} rebuilds the tabs only when the title type or hide-All-tab changes, as a Tab caches its
 * title and icon width when created, and otherwise just redraws, which is all the stroke needs. The backdrop is the
 * window colour under the glass pill, or the top bar's own surface when it is flat.
 */
@SuppressLint("ViewConstructor")
public class FolderTabsPreviewCell extends FrameLayout {

    private static final int VERTICAL_PADDING = 8;
    private static final int BAR_HEIGHT = 36 + 7 + 7;

    private final Theme.ResourcesProvider resourcesProvider;
    private final Theme.ResourcesProvider barResourcesProvider;
    private final int currentAccount;
    private final GlassSourceColor backgroundSource = new GlassSourceColor();
    private final BlurredBackgroundDrawableViewFactory backgroundFactory = new BlurredBackgroundDrawableViewFactory(backgroundSource);
    private final FilterTabsView tabsView;
    private int builtTitleType = -1;
    private boolean builtHideAllTab;

    public FolderTabsPreviewCell(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.currentAccount = currentAccount;
        this.resourcesProvider = resourcesProvider;
        // The real bar reads its colours through this scoped provider
        barResourcesProvider = InterfaceStyleSolidHeader.wrapChatListTopBar(resourcesProvider);

        tabsView = new FilterTabsView(context, barResourcesProvider);
        tabsView.setPadding(0, dp(7), 0, dp(7));
        // Set before the first addTab: a Tab reads the delegate for its counter as soon as it is added
        tabsView.setDelegate(new FilterTabsView.FilterTabsViewDelegate() {
            @Override
            public void onPageSelected(FilterTabsView.Tab tab, boolean forward) {
            }

            @Override
            public void onPageScrolled(float progress) {
            }

            @Override
            public void onSamePageSelected() {
            }

            @Override
            public int getTabCounter(int tabId) {
                return 0;
            }

            @Override
            public boolean didSelectTab(FilterTabsView.TabView tabView, boolean selected) {
                return false;
            }

            @Override
            public boolean isTabMenuVisible() {
                return false;
            }

            @Override
            public void onDeletePressed(int id) {
            }

            @Override
            public void onPageReorder(int fromId, int toId) {
            }

            @Override
            public boolean canPerformActions() {
                return false;
            }
        });
        addView(tabsView);

        setClipChildren(false);
        // Decorative: the rows below it say the same thing to a screen reader
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        update();
    }

    public void update() {
        final boolean flat = InterfaceStyleController.applyChatListTopBar();

        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

        final int titleType = NekoConfig.tabsTitleType.Int();
        final boolean hideAllTab = NekoConfig.hideAllTab.Bool();
        if (titleType != builtTitleType || hideAllTab != builtHideAllTab) {
            builtTitleType = titleType;
            builtHideAllTab = hideAllTab;
            rebuildTabs(hideAllTab);
        }

        final int side = flat ? 0 : 4;
        tabsView.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, BAR_HEIGHT, Gravity.CENTER_VERTICAL, side, 0, side, 0));
        if (flat) {
            tabsView.setBlurredBackground(null);
            tabsView.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefault, barResourcesProvider));
        } else {
            // A new drawable each time, as the bottom bar preview does: it is cheap and subscribes to nothing
            backgroundSource.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
            BlurredBackgroundDrawable background = backgroundFactory.create(tabsView, BlurredBackgroundProviderImpl.dialogsTopPanel(currentAccount, barResourcesProvider));
            background.setRadius(dp(18));
            background.setPadding(dp(6.666f));
            tabsView.setBlurredBackground(background);
        }
        tabsView.updateColors();
        tabsView.invalidate();
    }

    private void rebuildTabs(boolean hideAllTab) {
        tabsView.removeTabs();
        if (!hideAllTab) {
            tabsView.addTab(0, 0, LocaleController.getString(R.string.FilterAllChats), null, false, true, false);
        }
        tabsView.addTab(1, 1, LocaleController.getString(R.string.FilterContacts), "\uD83D\uDC64", false, false, false);
        tabsView.addTab(2, 2, LocaleController.getString(R.string.FilterGroups), "\uD83D\uDC65", false, false, false);
        tabsView.addTab(3, 3, LocaleController.getString(R.string.FilterBots), "\uD83E\uDD16", false, false, false);
        tabsView.finishAddingTabs(false);
        tabsView.requestLayout();
    }

    // Fixed, so a title type change never moves the row under the finger
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(BAR_HEIGHT + VERTICAL_PADDING * 2), MeasureSpec.EXACTLY));
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
