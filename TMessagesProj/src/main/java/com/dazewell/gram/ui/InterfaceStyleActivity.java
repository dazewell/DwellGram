package com.dazewell.gram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Color;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.RadioButtonCell;
import org.telegram.ui.Cells.SlideIntChooseView;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BatteryDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UndoView;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellSelectBox;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.config.cell.WithKey;
import tw.nekomimi.nekogram.settings.BaseNekoXSettingsActivity;
import xyz.nextalone.nagram.NaConfig;
import com.dazewell.gram.helpers.InterfaceStyleController;

/**
 * N-Settings -> General -> Interface style. The look-and-feel options moved here from General, which keeps a pointer.
 * The rows are rebuilt in one place, {@link #rebuildRows()}, because the MD3 block, the composer row, the rounded
 * navigation row, the classic-day row and the main tabs rows all come and go with other settings.
 */
public class InterfaceStyleActivity extends BaseNekoXSettingsActivity {

    public static final String SETTINGS_KEY = "interface_style";
    public static final String ROW_KEY_STYLE = "Style";

    private static final int BLUR_STRENGTH_MIN = 0;
    private static final int BLUR_STRENGTH_MAX = 100;
    private static final int[] BLUR_STRENGTH_STEPS = {
            0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100
    };

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerStyle = bind(new ConfigCellHeader(getString(R.string.InterfaceStyleHeaderStyle)));
    // Only this radio carries the "Style" key: a second one would overwrite it in the row map and show up in search as an unresolved title
    private final ConfigCellCustom liquidGlassRow = bind(new ConfigCellCustom(ROW_KEY_STYLE, ConfigCellCustom.CUSTOM_ITEM_InterfaceStyleRadio, true));
    private final ConfigCellCustom materialDesign3Row = bind(new ConfigCellCustom(null, ConfigCellCustom.CUSTOM_ITEM_InterfaceStyleRadio, true));
    private final AbstractConfigCell dividerStyle = bind(new ConfigCellDivider());

    private final AbstractConfigCell headerBlurStrength = bind(new ConfigCellHeader(getString(R.string.InterfaceStyleBlurStrength)));
    private final AbstractConfigCell blurStrengthRow = bind(new ConfigCellCustom(NaConfig.INSTANCE.getInterfaceStyleBlurStrength().getKey(), ConfigCellCustom.CUSTOM_ITEM_InterfaceStyleSlider, false));
    private final AbstractConfigCell blurStrengthInfoRow = bind(new ConfigCellCustom(null, CellGroup.ITEM_TYPE_TEXT, false));

    private final AbstractConfigCell headerApplyTo = bind(new ConfigCellHeader(getString(R.string.InterfaceStyleApplyToHeader)));
    private final AbstractConfigCell applyChatHeaderRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getInterfaceStyleApplyChatHeader(), getString(R.string.InterfaceStyleApplyChatHeaderInfo)));
    private final AbstractConfigCell applyChatListTopBarRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getInterfaceStyleApplyChatListTopBar(), getString(R.string.InterfaceStyleApplyChatListTopBarInfo)));
    private final AbstractConfigCell applyButtonsRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getInterfaceStyleApplyButtons(), getString(R.string.InterfaceStyleApplyButtonsInfo)));
    private final AbstractConfigCell applyComposerRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getInterfaceStyleApplyComposer(), getString(R.string.InterfaceStyleApplyComposerInfo)));
    private final AbstractConfigCell applyBottomNavigationRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getInterfaceStyleApplyBottomNavigation(), getString(R.string.InterfaceStyleApplyBottomNavigationInfo)));
    private final AbstractConfigCell roundedNavigationRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getInterfaceStyleRoundedNavigation(), getString(R.string.InterfaceStyleRoundedNavigationInfo)));
    private final AbstractConfigCell panelDividersRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getInterfaceStylePanelDividers(), getString(R.string.InterfaceStylePanelDividersInfo)));
    private final AbstractConfigCell dividerApplyTo = bind(new ConfigCellDivider());

    private final AbstractConfigCell headerPanelColors = bind(new ConfigCellHeader(getString(R.string.InterfaceStylePanelColorsHeader)));
    private final AbstractConfigCell matchClassicDayHeaderRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getInterfaceStyleMatchClassicDayHeader(), getString(R.string.InterfaceStyleMatchClassicDayHeaderInfo)));
    private final AbstractConfigCell dividerPanelColors = bind(new ConfigCellDivider());

    // Moved here from General
    private final AbstractConfigCell headerAppearance = bind(new ConfigCellHeader(getString(R.string.Appearance)));
    private final AbstractConfigCell hideDividersRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideDividers()));
    private final AbstractConfigCell strokeOnViewsRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getStrokeOnViews()));
    private final AbstractConfigCell disableAvatarBlurRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableAvatarBlur()));
    private final AbstractConfigCell switchStyleRow = bind(new ConfigCellSelectBox("SwitchStyle", NaConfig.INSTANCE.getSwitchStyle(), new String[]{
            getString(R.string.Default),
            getString(R.string.StyleModern),
            getString(R.string.StyleMaterialDesign3)
    }, null));
    private final AbstractConfigCell sliderStyleRow = bind(new ConfigCellSelectBox("SliderStyle", NaConfig.INSTANCE.getSliderStyle(), new String[]{
            getString(R.string.Default),
            getString(R.string.StyleModern),
            getString(R.string.StyleMaterialDesign3)
    }, null));
    private final AbstractConfigCell typefaceRow = bind(new ConfigCellTextCheck(NekoConfig.typeface));
    private final AbstractConfigCell iconReplacementsRow = bind(new ConfigCellSelectBox("IconReplacements", NaConfig.INSTANCE.getIconReplacements(), new String[]{
            getString(R.string.Default),
            getString(R.string.IconReplacementSolar),
    }, null));
    private final AbstractConfigCell alwaysShowDownloadIconRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getAlwaysShowDownloadIcon()));
    private final AbstractConfigCell notificationIconRow = bind(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getNotificationIcon(), new String[]{
            getString(R.string.MapPreviewProviderTelegram),
            getString(R.string.NagramX),
            getString(R.string.Nagram),
            getString(R.string.NekoX)
    }, null));
    private final AbstractConfigCell tabletModeRow = bind(new ConfigCellSelectBox(null, NekoConfig.tabletMode, new String[]{
            getString(R.string.TabletModeDefault),
            getString(R.string.TabletModeOn),
            getString(R.string.TabletModeOff)
    }, null));
    private final AbstractConfigCell dividerAppearance = bind(new ConfigCellDivider());

    private final AbstractConfigCell headerTitleAndDecoration = bind(new ConfigCellHeader(getString(R.string.InterfaceStyleHeaderTitleAndDecoration)));
    private final AbstractConfigCell centerActionBarTitleRow = bind(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getCenterActionBarTitleType(), new String[]{
            getString(R.string.CenterActionBarTitleOff),
            getString(R.string.CenterActionBarTitleOn),
            getString(R.string.SettingsOnly),
            getString(R.string.ChatsOnly)
    }, null));
    private final AbstractConfigCell actionBarDecorationRow = bind(new ConfigCellSelectBox(null, NekoConfig.actionBarDecoration, new String[]{
            getString(R.string.DependsOnDate),
            getString(R.string.Snowflakes),
            getString(R.string.Fireworks),
            getString(R.string.DecorationNone),
    }, null));
    private final AbstractConfigCell chatDecorationRow = bind(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getChatDecoration(), new String[]{
            getString(R.string.DependsOnDate),
            getString(R.string.Snowflakes),
            getString(R.string.DecorationNone),
    }, null));
    private final AbstractConfigCell dividerTitleAndDecoration = bind(new ConfigCellDivider());

    private final AbstractConfigCell headerMainTabs = bind(new ConfigCellHeader(getString(R.string.MainTabsSettingsHeader)));
    private final AbstractConfigCell mainTabsPreviewRow = bind(new ConfigCellCustom(null, ConfigCellCustom.CUSTOM_ITEM_InterfaceStyleNavPreview, false));
    private final AbstractConfigCell hideTitlesRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getMainTabsHideTitles()));
    private final AbstractConfigCell hideContactsRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getMainTabsHideContacts()));
    private final AbstractConfigCell hideBottomNavigationBarRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideBottomNavigationBar()));
    private final AbstractConfigCell dividerMainTabs = bind(new ConfigCellDivider());

    private final AbstractConfigCell headerFolder = bind(new ConfigCellHeader(getString(R.string.Folder)));
    private final AbstractConfigCell folderTabsPreviewRow = bind(new ConfigCellCustom(null, ConfigCellCustom.CUSTOM_ITEM_InterfaceStyleFolderPreview, false));
    private final AbstractConfigCell tabsTitleTypeRow = bind(new ConfigCellSelectBox(null, NekoConfig.tabsTitleType, new String[]{
            getString(R.string.TabTitleTypeText),
            getString(R.string.TabTitleTypeIcon),
            getString(R.string.TabTitleTypeMix)
    }, null));
    private final AbstractConfigCell tabStyleStrokeRow = bind(new ConfigCellTextCheck(NaConfig.INSTANCE.getTabStyleStroke()));
    private final AbstractConfigCell dividerFolder = bind(new ConfigCellDivider());

    private final AbstractConfigCell infoRow = bind(new ConfigCellCustom(null, CellGroup.ITEM_TYPE_TEXT, false));

    // The rows that were on this page before the move: every click on them is refused while power saving blocks the style
    private final Set<AbstractConfigCell> powerSaverGatedRows = new HashSet<>(Arrays.asList(
            liquidGlassRow, materialDesign3Row, applyChatHeaderRow, applyChatListTopBarRow, applyButtonsRow, applyComposerRow,
            applyBottomNavigationRow, roundedNavigationRow, panelDividersRow, matchClassicDayHeaderRow));
    // The keys whose change repaints the whole interface; the rest below are the keys that came over from General
    private final Set<String> styleKeys = new HashSet<>(Arrays.asList(
            NaConfig.INSTANCE.getInterfaceStyleApplyChatHeader().getKey(),
            NaConfig.INSTANCE.getInterfaceStyleApplyChatListTopBar().getKey(),
            NaConfig.INSTANCE.getInterfaceStyleApplyButtons().getKey(),
            NaConfig.INSTANCE.getInterfaceStyleApplyComposer().getKey(),
            NaConfig.INSTANCE.getInterfaceStyleApplyBottomNavigation().getKey(),
            NaConfig.INSTANCE.getInterfaceStyleRoundedNavigation().getKey(),
            NaConfig.INSTANCE.getInterfaceStylePanelDividers().getKey(),
            NaConfig.INSTANCE.getInterfaceStyleMatchClassicDayHeader().getKey()));

    private ListAdapter listAdapter;
    private boolean interfaceStyleRebuildPending;

    // NagramX: moved from General with the centered-title row. The title is centered globally, so the animation is the same from here.
    private Parcelable recyclerViewState;
    private boolean wasCentered;
    private boolean wasCenteredAtBeginning;
    private float centeredMeasure = -1;

    public InterfaceStyleActivity() {
        // Kept in step with the bool: the select is only a refinement of it
        if (!NaConfig.INSTANCE.getCenterActionBarTitle().Bool()) {
            NaConfig.INSTANCE.getCenterActionBarTitleType().setConfigInt(0);
        }
        wasCentered = isCentered();
        wasCenteredAtBeginning = wasCentered;
        // Settings search reads the row map off a bare instance, so the rows exist before the view does
        rebuildRows();
    }

    private <T extends AbstractConfigCell> T bind(T cell) {
        cell.bindCellGroup(cellGroup);
        return cell;
    }

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @Override
    protected String getSettingsPrefix() {
        return SETTINGS_KEY;
    }

    @Override
    public int getBaseGuid() {
        return 16000;
    }

    @Override
    public int getDrawable() {
        return R.drawable.msg_theme;
    }

    @Override
    public String getTitle() {
        return getString(R.string.InterfaceStyle);
    }

    @Override
    public View createView(Context context) {
        NaConfig.migrateComposerGlassTransparency(Theme.isCurrentThemeDark());

        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> onSettingChanged(key);

        return superView;
    }

    @Override
    protected void onConfigImported(String key, Object value) {
        onSettingChanged(key);
        // A click on these two selects rebuilds the fragments from ConfigCellSelectBox; an import skips that
        if ((key.equals(NaConfig.INSTANCE.getChatDecoration().getKey()) || key.equals(NekoConfig.tabsTitleType.getKey())) && parentLayout != null) {
            parentLayout.rebuildFragments(0);
        }
    }

    // The MD3 radio has no key of its own in the row map (see liquidGlassRow) and the blur slider is not an enabled row, so the
    // base long-press would offer a numeric link or none. Both radios copy the "Style" link, as they did before the page moved.
    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        AbstractConfigCell row = position >= 0 && position < cellGroup.rows.size() ? cellGroup.rows.get(position) : null;
        String key;
        if (row == liquidGlassRow || row == materialDesign3Row) {
            key = ROW_KEY_STYLE;
        } else if (row == blurStrengthRow) {
            key = NaConfig.INSTANCE.getInterfaceStyleBlurStrength().getKey();
        } else {
            return false;
        }
        ItemOptions options = makeLongClickOptions(view);
        options.add(R.drawable.msg_link2, getString(R.string.CopyLink), () -> {
            AndroidUtilities.addToClipboard(String.format(Locale.getDefault(), "https://%s/nasettings/%s?r=%s", getMessagesController().linkPrefix, SETTINGS_KEY, key));
            BulletinFactory.of(this).createCopyLinkBulletin().show();
        });
        showLongClickOptions(view, options);
        return true;
    }

    // True for a row this page has, even one hidden at the moment (the main tabs pair goes with the bottom bar), so an old link to it still lands here
    public boolean ownsRow(String key) {
        if (getRowMapReverse().containsValue(key)) {
            return true;
        }
        for (AbstractConfigCell row : Arrays.asList(hideTitlesRow, hideContactsRow)) {
            if (row instanceof WithKey withKey && key.equals(withKey.getKey())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        // The day theme or Liquid Glass can change while another page is on top, and with it which rows exist
        updateRows();
    }

    @Override
    public void onPause() {
        super.onPause();
        flushInterfaceStyleRebuild();
    }

    @Override
    public void onFragmentDestroy() {
        flushInterfaceStyleRebuild();
        super.onFragmentDestroy();
    }

    @Override
    protected void updateRows() {
        rebuildRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    // The one place cellGroup.rows is written: the same cell instances, in order, every condition read in one pass
    private void rebuildRows() {
        boolean md3 = InterfaceStyleController.isMaterialDesign3();
        liquidGlassRow.setEnabled(LiteMode.isLiquidGlassSupported());

        ArrayList<AbstractConfigCell> rows = cellGroup.rows;
        rows.clear();
        rows.add(headerStyle);
        rows.add(liquidGlassRow);
        rows.add(materialDesign3Row);
        rows.add(dividerStyle);

        rows.add(headerBlurStrength);
        rows.add(blurStrengthRow);
        rows.add(blurStrengthInfoRow);

        if (md3) {
            rows.add(headerApplyTo);
            rows.add(applyChatHeaderRow);
            rows.add(applyChatListTopBarRow);
            rows.add(applyButtonsRow);
            if (InterfaceStyleController.COMPOSER_STYLE_AVAILABLE) {
                rows.add(applyComposerRow);
            }
            rows.add(applyBottomNavigationRow);
            if (NaConfig.INSTANCE.getInterfaceStyleApplyBottomNavigation().Bool()) {
                rows.add(roundedNavigationRow);
            }
            rows.add(panelDividersRow);
            rows.add(dividerApplyTo);
            if (InterfaceStyleController.MATCH_CLASSIC_DAY_HEADER_AVAILABLE && isClassicOrDayTheme()) {
                rows.add(headerPanelColors);
                rows.add(matchClassicDayHeaderRow);
                rows.add(dividerPanelColors);
            }
        }

        rows.add(headerAppearance);
        rows.add(hideDividersRow);
        rows.add(strokeOnViewsRow);
        rows.add(disableAvatarBlurRow);
        rows.add(switchStyleRow);
        rows.add(sliderStyleRow);
        rows.add(typefaceRow);
        rows.add(iconReplacementsRow);
        rows.add(alwaysShowDownloadIconRow);
        rows.add(notificationIconRow);
        rows.add(tabletModeRow);
        rows.add(dividerAppearance);

        rows.add(headerTitleAndDecoration);
        rows.add(centerActionBarTitleRow);
        rows.add(actionBarDecorationRow);
        rows.add(chatDecorationRow);
        rows.add(dividerTitleAndDecoration);

        rows.add(headerMainTabs);
        if (!NaConfig.INSTANCE.getHideBottomNavigationBar().Bool()) {
            rows.add(mainTabsPreviewRow);
            rows.add(hideTitlesRow);
            rows.add(hideContactsRow);
        }
        rows.add(hideBottomNavigationBarRow);
        rows.add(dividerMainTabs);

        rows.add(headerFolder);
        rows.add(folderTabsPreviewRow);
        rows.add(tabsTitleTypeRow);
        rows.add(tabStyleStrokeRow);
        rows.add(dividerFolder);

        // Last on purpose: CellGroup.needSetDivider reads the row after a cell
        rows.add(infoRow);

        addRowsToMap(cellGroup);
    }

    private void onSettingChanged(String key) {
        if (styleKeys.contains(key)) {
            reloadInterfaceStyle();
            if (key.equals(NaConfig.INSTANCE.getInterfaceStyleMatchClassicDayHeader().getKey())
                    && !NaConfig.INSTANCE.getInterfaceStyleApplyChatHeader().Bool()
                    && !NaConfig.INSTANCE.getInterfaceStyleApplyChatListTopBar().Bool()) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.InterfaceStyleMatchClassicDayHeaderNoTarget)).show();
            }
        } else if (key.equals(NekoConfig.actionBarDecoration.getKey())
                || key.equals(NaConfig.INSTANCE.getHideDividers().getKey())
                || key.equals(NaConfig.INSTANCE.getIconReplacements().getKey())
                || key.equals(NekoConfig.typeface.getKey())
                || key.equals(NaConfig.INSTANCE.getAlwaysShowDownloadIcon().getKey())
                || key.equals(NaConfig.INSTANCE.getNotificationIcon().getKey())
                || key.equals(NekoConfig.tabletMode.getKey())) {
            showRestartTooltip();
        } else if (key.equals(NaConfig.INSTANCE.getSwitchStyle().getKey()) || key.equals(NaConfig.INSTANCE.getSliderStyle().getKey())) {
            // The rebuild makes a fresh list, so the saved position goes onto the new layout manager, read after the rebuild
            RecyclerView.LayoutManager layoutManager = listView.getLayoutManager();
            if (parentLayout != null && layoutManager != null) {
                recyclerViewState = layoutManager.onSaveInstanceState();
                parentLayout.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
                listView.getLayoutManager().onRestoreInstanceState(recyclerViewState);
            }
        } else if (key.equals(NaConfig.INSTANCE.getCenterActionBarTitleType().getKey())) {
            NaConfig.INSTANCE.getCenterActionBarTitle().setConfigBool(NaConfig.INSTANCE.getCenterActionBarTitleType().Int() != 0);
            animateActionBarUpdate();
        } else if (key.equals(NaConfig.INSTANCE.getHideBottomNavigationBar().getKey())) {
            updateRows();
            if (parentLayout != null) {
                parentLayout.rebuildFragments(0);
            }
        } else if (key.equals(NaConfig.INSTANCE.getMainTabsHideTitles().getKey()) || key.equals(NaConfig.INSTANCE.getMainTabsHideContacts().getKey())) {
            // The rebuild below skips this page, so the preview is rebound here
            updateRows();
            if (parentLayout != null) {
                parentLayout.rebuildFragments(0);
            }
        } else if (key.equals(NaConfig.INSTANCE.getTabStyleStroke().getKey()) || key.equals(NekoConfig.tabsTitleType.getKey())) {
            // The rebuild of the other fragments skips this page, so the preview is refreshed here. Not updateRows():
            // rebinding the select row under its still-closing popup flashes the menu at the top of the screen
            if (listView != null) {
                for (int i = 0; i < listView.getChildCount(); i++) {
                    if (listView.getChildAt(i) instanceof FolderTabsPreviewCell) {
                        ((FolderTabsPreviewCell) listView.getChildAt(i)).update();
                    }
                }
            }
            getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
        }
    }

    private void showRestartTooltip() {
        if (tooltip != null) {
            tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
        }
    }

    @Override
    protected void handleCellClick(View view, int position, float x, float y) {
        if (position >= 0 && position < cellGroup.rows.size() && powerSaverGatedRows.contains(cellGroup.rows.get(position)) && LiteMode.isPowerSaverApplied()) {
            BulletinFactory.of(this).createSimpleBulletin(new BatteryDrawable(.1f, Color.WHITE, Theme.getColor(Theme.key_dialogSwipeRemove), 1.3f), getString(R.string.LiteBatteryRestricted)).show();
            return;
        }
        super.handleCellClick(view, position, x, y);
    }

    @Override
    protected void onCustomCellClick(View view, int position, float x, float y) {
        AbstractConfigCell row = cellGroup.rows.get(position);
        if (row == liquidGlassRow) {
            if (LiteMode.isLiquidGlassSupported()) {
                setLiquidGlassEnabled(true);
            }
        } else if (row == materialDesign3Row) {
            setLiquidGlassEnabled(false);
        }
    }

    private boolean liquidGlassSelected() {
        return !InterfaceStyleController.isMaterialDesign3();
    }

    private void setLiquidGlassEnabled(boolean enabled) {
        if (enabled == LiteMode.isEnabledSetting(LiteMode.FLAG_LIQUID_GLASS)) {
            return;
        }
        LiteMode.toggleFlag(LiteMode.FLAG_LIQUID_GLASS, enabled);
        reloadInterfaceStyle();
    }

    private void reloadInterfaceStyle() {
        updateRows();
        interfaceStyleRebuildPending = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
    }

    private void flushInterfaceStyleRebuild() {
        if (!interfaceStyleRebuildPending) {
            return;
        }
        interfaceStyleRebuildPending = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
    }

    private static boolean isClassicOrDayTheme() {
        Theme.ThemeInfo themeInfo = Theme.getCurrentTheme();
        return themeInfo != null && ("Blue".equals(themeInfo.name) || "Day".equals(themeInfo.name));
    }

    private static SlideIntChooseView.Options blurStrengthOptions() {
        return SlideIntChooseView.Options.make(0, BLUR_STRENGTH_STEPS, 1,
                (type, value) -> value + "%");
    }

    private static int currentBlurStrength() {
        int percent = NaConfig.INSTANCE.getInterfaceStyleBlurStrength().Int();
        return Math.max(BLUR_STRENGTH_MIN, Math.min(BLUR_STRENGTH_MAX, percent));
    }

    private boolean isCentered() {
        return NaConfig.INSTANCE.getCenterActionBarTitle().Bool() && NaConfig.INSTANCE.getCenterActionBarTitleType().Int() != 3;
    }

    private void animateActionBarUpdate() {
        boolean centered = isCentered();
        if (wasCentered == centered) {
            return;
        }
        if (actionBar != null) {
            SimpleTextView titleTextView = actionBar.getTitleTextView();
            if (centeredMeasure == -1) {
                centeredMeasure = actionBar.getMeasuredWidth() / 2f - titleTextView.getTextWidth() / 2f - dp((AndroidUtilities.isTablet() ? 80 : 72));
            }
            titleTextView.animate().translationX(centeredMeasure * (centered ? 1 : 0) - (wasCenteredAtBeginning ? Math.abs(centeredMeasure) : 0)).setDuration(150).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    wasCentered = centered;
                    reloadUI(0);
                    LaunchActivity.makeRipple(centered ? (actionBar.getMeasuredWidth() / 2f) : 0, 0, centered ? 1.3f : 0.1f);
                }
            }).start();
        } else {
            reloadUI(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
        }
    }

    private void reloadUI(int flags) {
        RecyclerView.LayoutManager layoutManager = listView.getLayoutManager();
        if (layoutManager != null) {
            recyclerViewState = layoutManager.onSaveInstanceState();
            parentLayout.rebuildFragments(flags);
            layoutManager.onRestoreInstanceState(recyclerViewState);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();
        ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (listView == null) {
                return;
            }
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (child instanceof SlideIntChooseView) {
                    ((SlideIntChooseView) child).updateColors();
                } else if (child instanceof MainTabsPreviewCell) {
                    ((MainTabsPreviewCell) child).update();
                } else if (child instanceof FolderTabsPreviewCell) {
                    ((FolderTabsPreviewCell) child).update();
                }
            }
        };
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{RadioButtonCell.class, SlideIntChooseView.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteGrayText));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteValueText));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_player_progress));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{RadioButtonCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{RadioButtonCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CHECKBOX, new Class[]{RadioButtonCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackground));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{RadioButtonCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackgroundChecked));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        return themeDescriptions;
    }

    private class ListAdapter extends BaseListAdapter {

        ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == ConfigCellCustom.CUSTOM_ITEM_InterfaceStyleRadio) {
                return new RadioButtonCell(mContext);
            } else if (viewType == ConfigCellCustom.CUSTOM_ITEM_InterfaceStyleSlider) {
                SlideIntChooseView view = new SlideIntChooseView(mContext, null);
                view.setSnapToValue(true);
                return view;
            } else if (viewType == ConfigCellCustom.CUSTOM_ITEM_InterfaceStyleNavPreview) {
                return new MainTabsPreviewCell(mContext, currentAccount, getResourceProvider());
            } else if (viewType == ConfigCellCustom.CUSTOM_ITEM_InterfaceStyleFolderPreview) {
                return new FolderTabsPreviewCell(mContext, currentAccount, getResourceProvider());
            }
            return null;
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AbstractConfigCell row = cellGroup.rows.get(position);
            if (row == liquidGlassRow) {
                RadioButtonCell cell = (RadioButtonCell) holder.itemView;
                if (LiteMode.isLiquidGlassSupported()) {
                    cell.setTextAndValue(getString(R.string.InterfaceStyleLiquidGlass), true, liquidGlassSelected());
                    cell.setAlpha(1.0f);
                } else {
                    cell.setTextAndValueAndCheck(getString(R.string.InterfaceStyleLiquidGlass), getString(R.string.InterfaceStyleLiquidGlassUnsupported), true, false);
                    cell.setAlpha(0.5f);
                }
            } else if (row == materialDesign3Row) {
                RadioButtonCell cell = (RadioButtonCell) holder.itemView;
                cell.setTextAndValue(getString(R.string.StyleMaterialDesign3), false, !liquidGlassSelected());
                cell.setAlpha(1.0f);
            } else if (row == blurStrengthRow) {
                SlideIntChooseView cell = (SlideIntChooseView) holder.itemView;
                cell.setLabel(getString(R.string.InterfaceStyleBlurStrengthAccDescr));
                cell.set(currentBlurStrength(), blurStrengthOptions(), value -> {
                    if (value == NaConfig.INSTANCE.getInterfaceStyleBlurStrength().Int()) {
                        return;
                    }
                    NaConfig.INSTANCE.getInterfaceStyleBlurStrength().setConfigInt(value);
                    interfaceStyleRebuildPending = true;
                });
            } else if (row == blurStrengthInfoRow) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(getString(R.string.InterfaceStyleBlurStrengthInfo));
                cell.setFixedSize(0);
            } else if (row == mainTabsPreviewRow) {
                ((MainTabsPreviewCell) holder.itemView).update();
            } else if (row == folderTabsPreviewRow) {
                ((FolderTabsPreviewCell) holder.itemView).update();
            } else if (row == infoRow) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(getString(R.string.InterfaceStyleInfo));
                cell.setFixedSize(0);
            }
        }
    }
}
