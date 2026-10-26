package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;

import com.dazewell.gram.hotkeys.HotkeysActivity;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UnifiedPushService;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UndoView;

import java.io.File;
import java.util.Locale;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellSelectBox;
import tw.nekomimi.nekogram.config.cell.ConfigCellText;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import com.dazewell.gram.ui.ConfigCellTextCheckPage;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextDetail;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextInput;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextInput2;
import com.dazewell.gram.helpers.SaveFileNameDialog;
import com.dazewell.gram.helpers.SaveFileNameHelper;
import tw.nekomimi.nekogram.utils.AndroidUtil;
import xyz.nextalone.nagram.NaConfig;
import com.dazewell.gram.helpers.InterfaceStyleController;
import com.dazewell.gram.ui.InterfaceStyleActivity;

@SuppressLint("RtlHardcoded")
@SuppressWarnings({"unused", "FieldCanBeLocal"})
public class NekoGeneralSettingsActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;

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
        return "general";
    }

    private ValueAnimator statusBarColorAnimator;

    private final CellGroup cellGroup = new CellGroup(this);

    // General
    private final AbstractConfigCell headerGeneral = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.General)));
    private final AbstractConfigCell customTitleRow = cellGroup.appendCell(new ConfigCellTextInput(null, NaConfig.INSTANCE.getCustomTitle(),
        // NagramX: CustomTitleHint's translations all embed the literal "Nagram X" untranslated; swap in the current brand name at render time instead of touching 15 locale files
        getString(R.string.CustomTitleHint).replace("Nagram X", getString(R.string.NagramX)), null,
        (input) -> input.isEmpty() ? (String) NaConfig.INSTANCE.getCustomTitle().defaultValue : input));
    private final AbstractConfigCell folderNameAsTitleRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getFolderNameAsTitle()));
    private final AbstractConfigCell customTitleUserNameRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getCustomTitleUserName()));
    private final AbstractConfigCell disableNumberRoundingRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.disableNumberRounding, "4.8K -> 4777"));
    private final AbstractConfigCell preferCommonGroupsTabRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getPreferCommonGroupsTab(), getString(R.string.PreferCommonGroupsTabNotice)));
    private final AbstractConfigCell physicalKeyboardHotkeysRow = cellGroup.appendCell(new ConfigCellTextCheckPage(NaConfig.INSTANCE.getPhysicalKeyboardHotkeys(), getString(R.string.PhysicalKeyboardHotkeysValue), () -> presentFragment(new HotkeysActivity())));
    private final AbstractConfigCell launcherShortcutsRow = cellGroup.appendCell(new ConfigCellText("LauncherShortcuts", () -> presentFragment(new com.dazewell.gram.shortcuts.LauncherShortcutsActivity())));
    private final AbstractConfigCell usePersianCalendarRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.usePersianCalendar, getString(R.string.UsePersianCalendarInfo)));
    private final AbstractConfigCell displayPersianCalendarByLatinRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.displayPersianCalendarByLatin));
    private final AbstractConfigCell showIdAndDcRow = cellGroup.appendCell(new ConfigCellSelectBox("ShowIdAndDc", NaConfig.INSTANCE.getIdDcType(), new String[]{
            getString(R.string.Disable),
            "Telegram API",
            "Bot API"
    }, null));
    private final AbstractConfigCell nameOrderRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NekoConfig.nameOrder, new String[]{
            getString(R.string.LastFirst),
            getString(R.string.FirstLast)
    }, null));
    private final AbstractConfigCell dividerGeneral = cellGroup.appendCell(new ConfigCellDivider());

    // Storage
    private final AbstractConfigCell headerStorage = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.StorageSettings)));
    private final AbstractConfigCell saveToChatSubfolderRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSaveToChatSubfolder()));
    private final AbstractConfigCell customSavePathRow = cellGroup.appendCell(new ConfigCellTextDetail(
            NekoConfig.customSavePath,
            getString(R.string.customSavePath),
            getString(R.string.customSavePathHint),
            this::sanitizeCustomSavePath,
            this::shouldShowCustomSavePathInputError,
            this::formatCustomSavePathDetail));
    private final AbstractConfigCell customFileNamesRow = cellGroup.appendCell(new ConfigCellTextDetail(
            NaConfig.INSTANCE.getCustomFileNamesPattern(),
            (view, position) -> openCustomFileNamesDialog(),
            null,
            false,
            getString(R.string.CustomFileNames),
            this::formatCustomFileNamesDetail,
            null,
            null,
            null));
    private final AbstractConfigCell blackRoundVideoCornersRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getBlackRoundVideoCorners(), getString(R.string.BlackRoundVideoCornersInfo)));

    private final AbstractConfigCell dividerStorage = cellGroup.appendCell(new ConfigCellDivider());

    // Connections
    private final AbstractConfigCell headerConnection = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.Connection)));
    private final AbstractConfigCell useIPv6Row = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.useIPv6));
    private final AbstractConfigCell disableProxyWhenVpnEnabledRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableProxyWhenVpnEnabled()));
    private final AbstractConfigCell defaultHlsVideoQualityRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getDefaultHlsVideoQuality(), new String[]{
            getString(R.string.QualityAuto),
            getString(R.string.QualityOriginal),
            getString(R.string.Quality1440),
            getString(R.string.Quality1080),
            getString(R.string.Quality720),
            getString(R.string.Quality144),
    }, null));
    private final AbstractConfigCell dnsTypeRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NekoConfig.dnsType, new String[]{
            getString(R.string.MapPreviewProviderTelegram),
            getString(R.string.NagramX),
            getString(R.string.DnsTypeSystem),
            getString(R.string.CustomDoH),
    }, null));
    private final AbstractConfigCell customDoHRow = cellGroup.appendCell(new ConfigCellTextInput2(null, NekoConfig.customDoH, "https://1.0.0.1/dns-query, https://...", null));
    private final AbstractConfigCell dividerConnection = cellGroup.appendCell(new ConfigCellDivider());

    // Map
    private final AbstractConfigCell headerMap = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.Map)));
    private final AbstractConfigCell useOSMDroidMapRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.useOSMDroidMap));
    private final AbstractConfigCell mapDriftingFixForGoogleMapsRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.mapDriftingFixForGoogleMaps));
    private final AbstractConfigCell mapPreviewRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NekoConfig.mapPreviewProvider, new String[]{
            getString(R.string.MapPreviewProviderTelegram),
            getString(R.string.MapPreviewProviderYandexNax),
            getString(R.string.MapPreviewProviderNobody)
    }, null));
    private final AbstractConfigCell dividerMap = cellGroup.appendCell(new ConfigCellDivider());

    // Folder
    private final AbstractConfigCell headerFolder = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.Folder)));
    private final AbstractConfigCell hideAllTabRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.hideAllTab, getString(R.string.HideAllTabAbout)));
    private final AbstractConfigCell doNotUnarchiveBySwipeRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDoNotUnarchiveBySwipe()));
    private final AbstractConfigCell openArchiveOnPullRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.openArchiveOnPull));
    private final AbstractConfigCell hideArchiveRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideArchive()));
    private final AbstractConfigCell ignoreUnreadCountRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getIgnoreUnreadCount(), new String[]{
            getString(R.string.Disable),
            getString(R.string.FilterMuted),
            getString(R.string.FilterAllChatsShort)
    }, null));
    private final AbstractConfigCell dividerFolder = cellGroup.appendCell(new ConfigCellDivider());

    // Dialogs
    private final AbstractConfigCell headerDialogs = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.DialogsSettings)));
    private final AbstractConfigCell sortByUnreadRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSortByUnread()));
    private final AbstractConfigCell hideDialogsSearchFieldRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideDialogsSearchField()));
    private final AbstractConfigCell disableDialogsFloatingButtonRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableDialogsFloatingButton()));
    private final AbstractConfigCell disableBotOpenButtonRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableBotOpenButton()));
    private final AbstractConfigCell mediaPreviewRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.mediaPreview));
    private final AbstractConfigCell dividerDialogs = cellGroup.appendCell(new ConfigCellDivider());

    // Appearance
    private final AbstractConfigCell headerAppearance = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.Appearance)));
    private final AbstractConfigCell showStickersInTopLevelRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getShowStickersRowToplevel()));
    private final AbstractConfigCell hidePremiumSectionRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHidePremiumSection()));
    private final AbstractConfigCell hideHelpSectionRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideHelpSection()));
    private final ConfigCellText interfaceStyleRow = (ConfigCellText) cellGroup.appendCell(new ConfigCellText("InterfaceStyle", interfaceStyleValue(), () ->
            presentFragment(new InterfaceStyleActivity())));
    private final AbstractConfigCell dividerAppearance = cellGroup.appendCell(new ConfigCellDivider());

    // Privacy
    private final AbstractConfigCell headerPrivacy = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.PrivacyTitle)));
    private final AbstractConfigCell hidePhoneRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.hidePhone));
    private final AbstractConfigCell disableSystemAccountRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.disableSystemAccount));
    private final AbstractConfigCell disableCrashlyticsCollectionRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableCrashlyticsCollection()));
    private final AbstractConfigCell dividerPrivacy = cellGroup.appendCell(new ConfigCellDivider());

    // Notifications
    private final AbstractConfigCell headerNotifications = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.Notifications)));
    private final AbstractConfigCell pushServiceTypeRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getPushServiceType(), new String[]{
            getString(R.string.PushServiceTypeInApp),
            getString(R.string.PushServiceTypeFCM),
            getString(R.string.PushServiceTypeUnified),
            getString(R.string.PushServiceTypeMicroG),
    }, null));
    private final AbstractConfigCell pushServiceTypeUnifiedGatewayRow = cellGroup.appendCell(new ConfigCellTextInput(null, NaConfig.INSTANCE.getPushServiceTypeUnifiedGateway(), UnifiedPushService.UP_GATEWAY_DEFAULT, null, (input) -> input.isEmpty() ? (String) NaConfig.INSTANCE.getPushServiceTypeUnifiedGateway().defaultValue : input));
    private final AbstractConfigCell pushServiceTypeInAppDialogRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getPushServiceTypeInAppDialog()));
    private final AbstractConfigCell disableNotificationBubblesRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.disableNotificationBubbles));
    private final AbstractConfigCell dividerNotifications = cellGroup.appendCell(new ConfigCellDivider());

    // AutoDownload
    private final AbstractConfigCell headerAutoDownload = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AutoDownload)));
    private final AbstractConfigCell win32Row = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.disableAutoDownloadingWin32Executable));
    private final AbstractConfigCell archiveRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.disableAutoDownloadingArchive));
    private final AbstractConfigCell dividerAutoDownload = cellGroup.appendCell(new ConfigCellDivider());

    public NekoGeneralSettingsActivity() {
        if (!shouldShowPersian()) {
            cellGroup.rows.remove(usePersianCalendarRow);
            cellGroup.rows.remove(displayPersianCalendarByLatinRow);
        }
        if (!com.dazewell.gram.videonote.RoundVideoMask.isSupported()) {
            cellGroup.rows.remove(blackRoundVideoCornersRow);
        }

        checkCustomDoHRows();
        checkMapDriftingFixRows();
        checkCustomTitleRows();
        checkPushServiceTypeRows();
        checkOpenArchiveOnPullRows();
        addRowsToMap(cellGroup);
    }

    @SuppressLint({"NewApi", "NotifyDataSetChanged", "UseCompatLoadingForDrawables"})
    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);

        listView.setAdapter(listAdapter);

        setupDefaultListeners();

        // Cells: Set OnSettingChanged Callbacks
        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            if (key.equals(NekoConfig.disableSystemAccount.getKey())) {
                if ((boolean) newValue) {
                    getContactsController().deleteUnknownAppAccounts();
                } else {
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        ContactsController.getInstance(a).checkAppAccount();
                    }
                }
            } else if (key.equals(NekoConfig.useOSMDroidMap.getKey())) {
                checkMapDriftingFixRows();
            } else if (key.equals(NaConfig.INSTANCE.getPushServiceType().getKey())) {
                PushListenerController.reconcilePushRegistration();
                if ((int) newValue == 0) {
                    AndroidUtil.setPushService(false);
                } else {
                    NaConfig.INSTANCE.getPushServiceTypeInAppDialog().setConfigBool(false);
                }
                checkPushServiceTypeRows();
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getPushServiceTypeInAppDialog().getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getPushServiceTypeUnifiedGateway().getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getDisableCrashlyticsCollection().getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getCustomTitleUserName().getKey())) {
                checkCustomTitleRows();
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getSortByUnread().getKey())) {
                getMessagesController().sortDialogs(null);
                getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload, true);
            } else if (key.equals(NaConfig.INSTANCE.getIgnoreUnreadCount().getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NekoConfig.hideAllTab.getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getHideArchive().getKey())) {
                checkOpenArchiveOnPullRows();
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getDisableBotOpenButton().getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NekoConfig.usePersianCalendar.getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NekoConfig.dnsType.getKey())) {
                checkCustomDoHRows();
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getDisableDialogsFloatingButton().getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getHidePremiumSection().getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getHideHelpSection().getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getShowStickersRowToplevel().getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getSaveToChatSubfolder().getKey())) {
                listAdapter.notifyItemChanged(cellGroup.rows.indexOf(customSavePathRow));
            } else if (key.equals(NaConfig.INSTANCE.getHideDialogsSearchField().getKey())) {
                parentLayout.rebuildFragments(0);
            }
        };

        return superView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            interfaceStyleRow.setValue(interfaceStyleValue());
            listAdapter.notifyItemChanged(cellGroup.rows.indexOf(interfaceStyleRow));
        }
    }

    private String interfaceStyleValue() {
        return !InterfaceStyleController.isMaterialDesign3()
                ? getString(R.string.InterfaceStyleLiquidGlass)
                : getString(R.string.StyleMaterialDesign3);
    }

    private void showUnifiedPushStatistics() {
        if (getParentActivity() == null) {
            return;
        }

        String txt;
        long num = UnifiedPushService.getNumOfReceivedNotifications();
        // NagramX: UnifiedPush* strings embed the literal "Nagram X" untranslated in every locale; fix up at render time like CustomTitleHint above
        if (num == 0) {
            txt = getString(R.string.UnifiedPushNeverReceivedNotifications).replace("Nagram X", getString(R.string.NagramX));
        } else {
            txt = LocaleController.formatString(
                    R.string.UnifiedPushLastReceivedNotification,
                    (SystemClock.elapsedRealtime() - UnifiedPushService.getLastReceivedNotification()) / 1000,
                    num
            ).replace("Nagram X", getString(R.string.NagramX));
        }
        txt += "\n\n" + LocaleController.formatString(R.string.UnifiedPushCurrentEndpoint, SharedConfig.pushString);

        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.PushServiceTypeUnified))
                .setMessage(txt)
                .setPositiveButton(getString(R.string.OK), null)
                .create());
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        AbstractConfigCell a = cellGroup.rows.get(position);
        if (a == pushServiceTypeUnifiedGatewayRow) {
            ItemOptions options = makeLongClickOptions(view);
            options.add(R.drawable.msg_stats, getString(R.string.Statistics), this::showUnifiedPushStatistics);
            addDefaultLongClickOptions(options, "general", position);
            showLongClickOptions(view, options);
            return true;
        }
        return false;
    }

    @Override
    public int getBaseGuid() {
        return 12000;
    }

    @Override
    public int getDrawable() {
        return R.drawable.msg_theme;
    }

    @Override
    public String getTitle() {
        return getString(R.string.General);
    }

    // impl ListAdapter
    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

    }

    private void checkCustomDoHRows() {
        boolean useDoH = NekoConfig.dnsType.Int() == NekoConfig.DNS_TYPE_CUSTOM_DOH;
        if (listAdapter == null) {
            if (!useDoH) {
                cellGroup.rows.remove(customDoHRow);
            }
            return;
        }
        if (useDoH) {
            final int index = cellGroup.rows.indexOf(dnsTypeRow);
            if (!cellGroup.rows.contains(customDoHRow)) {
                cellGroup.rows.add(index + 1, customDoHRow);
                listAdapter.notifyItemInserted(index + 1);
            }
        } else {
            int customDoHRowIndex = cellGroup.rows.indexOf(customDoHRow);
            if (customDoHRowIndex != -1) {
                cellGroup.rows.remove(customDoHRow);
                listAdapter.notifyItemRemoved(customDoHRowIndex);
            }
        }
    }

    private void checkMapDriftingFixRows() {
        boolean useOSMDroid = NekoConfig.useOSMDroidMap.Bool();
        if (listAdapter == null) {
            if (useOSMDroid) {
                cellGroup.rows.remove(mapDriftingFixForGoogleMapsRow);
            }
            return;
        }
        if (!useOSMDroid) {
            final int index = cellGroup.rows.indexOf(useOSMDroidMapRow);
            if (!cellGroup.rows.contains(mapDriftingFixForGoogleMapsRow)) {
                cellGroup.rows.add(index + 1, mapDriftingFixForGoogleMapsRow);
                listAdapter.notifyItemInserted(index + 1);
            }
        } else {
            int rowIndex = cellGroup.rows.indexOf(mapDriftingFixForGoogleMapsRow);
            if (rowIndex != -1) {
                cellGroup.rows.remove(mapDriftingFixForGoogleMapsRow);
                listAdapter.notifyItemRemoved(rowIndex);
            }
        }
        addRowsToMap(cellGroup);
    }

    private void checkCustomTitleRows() {
        boolean useUserName = NaConfig.INSTANCE.getCustomTitleUserName().Bool();
        if (listAdapter == null) {
            if (useUserName) {
                cellGroup.rows.remove(customTitleRow);
            }
            return;
        }
        if (!useUserName) {
            final int index = cellGroup.rows.indexOf(headerGeneral);
            if (!cellGroup.rows.contains(customTitleRow)) {
                cellGroup.rows.add(index + 1, customTitleRow);
                listAdapter.notifyItemInserted(index + 1);
            }
        } else {
            int rowIndex = cellGroup.rows.indexOf(customTitleRow);
            if (rowIndex != -1) {
                cellGroup.rows.remove(customTitleRow);
                listAdapter.notifyItemRemoved(rowIndex);
            }
        }
        addRowsToMap(cellGroup);
    }

    private void checkPushServiceTypeRows() {
        boolean useInApp = NaConfig.INSTANCE.getPushServiceType().Int() == 0;
        boolean useUnified = NaConfig.INSTANCE.getPushServiceType().Int() == 2;
        if (listAdapter == null) {
            if (!useInApp) {
                cellGroup.rows.remove(pushServiceTypeInAppDialogRow);
            }
            if (!useUnified) {
                cellGroup.rows.remove(pushServiceTypeUnifiedGatewayRow);
            }
            return;
        }
        if (useInApp) {
            final int index = cellGroup.rows.indexOf(pushServiceTypeRow);
            if (!cellGroup.rows.contains(pushServiceTypeInAppDialogRow)) {
                cellGroup.rows.add(index + 1, pushServiceTypeInAppDialogRow);
                listAdapter.notifyItemInserted(index + 1);
            }
        } else {
            int rowIndex = cellGroup.rows.indexOf(pushServiceTypeInAppDialogRow);
            if (rowIndex != -1) {
                cellGroup.rows.remove(pushServiceTypeInAppDialogRow);
                listAdapter.notifyItemRemoved(rowIndex);
            }
        }
        if (useUnified) {
            final int index = cellGroup.rows.indexOf(pushServiceTypeRow);
            if (!cellGroup.rows.contains(pushServiceTypeUnifiedGatewayRow)) {
                cellGroup.rows.add(index + 1, pushServiceTypeUnifiedGatewayRow);
                listAdapter.notifyItemInserted(index + 1);
            }
        } else {
            int rowIndex = cellGroup.rows.indexOf(pushServiceTypeUnifiedGatewayRow);
            if (rowIndex != -1) {
                cellGroup.rows.remove(pushServiceTypeUnifiedGatewayRow);
                listAdapter.notifyItemRemoved(rowIndex);
            }
        }
        addRowsToMap(cellGroup);
    }

    private void checkOpenArchiveOnPullRows() {
        boolean hideArchive = NaConfig.INSTANCE.getHideArchive().Bool();
        if (listAdapter == null) {
            if (hideArchive) {
                cellGroup.rows.remove(openArchiveOnPullRow);
            }
            return;
        }
        if (!hideArchive) {
            final int index = cellGroup.rows.indexOf(hideArchiveRow);
            if (!cellGroup.rows.contains(openArchiveOnPullRow)) {
                cellGroup.rows.add(index, openArchiveOnPullRow);
                listAdapter.notifyItemInserted(index);
            }
        } else {
            int rowIndex = cellGroup.rows.indexOf(openArchiveOnPullRow);
            if (rowIndex != -1) {
                cellGroup.rows.remove(openArchiveOnPullRow);
                listAdapter.notifyItemRemoved(rowIndex);
            }
        }
        addRowsToMap(cellGroup);
    }

    private boolean shouldShowPersian() {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null && locale.getLanguage().equals("fa");
    }

    private void openCustomFileNamesDialog() {
        SaveFileNameDialog.show(this, () -> {
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(cellGroup.rows.indexOf(customFileNamesRow));
            }
        });
    }

    private String formatCustomFileNamesDetail(String pattern) {
        if (!NaConfig.INSTANCE.getCustomFileNamesEnabled().Bool()) {
            return getString(R.string.CustomFileNamesOff);
        }
        return LocaleController.formatString(R.string.CustomFileNamesOnExample, SaveFileNameHelper.renderForDisplay(pattern) + ".mp4");
    }

    private String formatCustomSavePathDetail(String rawValue) {
        String folderName = rawValue == null ? "" : rawValue.trim();
        if (NaConfig.INSTANCE.getSaveToChatSubfolder().Bool()) {
            folderName = TextUtils.isEmpty(folderName) ? "<chat_name>" : folderName + File.separator + "<chat_name>";
        }
        return buildCustomSaveAbsolutePath(Environment.DIRECTORY_DOWNLOADS, folderName);
    }

    private String buildCustomSaveAbsolutePath(String directory, String folderName) {
        File root = Environment.getExternalStoragePublicDirectory(directory);
        if (TextUtils.isEmpty(folderName)) {
            return root.getAbsolutePath();
        }
        return new File(root, folderName).getAbsolutePath();
    }

    private boolean shouldShowCustomSavePathInputError(String input, String output) {
        if (TextUtils.isEmpty(input)) {
            return false;
        }
        String normalized = input.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        return !normalized.equals(output);
    }

    private String sanitizeCustomSavePath(String input) {
        if (TextUtils.isEmpty(input)) {
            return "";
        }
        String normalized = input.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.matches("^(?!\\.{1,2}$)[A-Za-z0-9._ -]{1,255}$")) {
            return normalized;
        }
        return (String) NekoConfig.customSavePath.defaultValue;
    }
}
