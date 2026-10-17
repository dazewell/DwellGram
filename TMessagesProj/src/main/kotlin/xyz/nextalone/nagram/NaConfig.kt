package xyz.nextalone.nagram

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.SharedConfig
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.config.ConfigItem
import tw.nekomimi.nekogram.config.ConfigItemKeyLinked
import tw.nekomimi.nekogram.llm.utils.UrlNormalizer
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream


object NaConfig {
    // Video message playback modes (see videoMessagesPlayMode)
    const val VIDEO_PLAY_ONCE = 0
    const val VIDEO_PLAY_ALL = 1
    const val VIDEO_REPEAT_ONE = 2

    @Volatile
    private var initialized = false

    @JvmStatic
    fun getPreferences(): SharedPreferences {
        return NekoConfig.getPreferences()
    }

    @JvmStatic
    fun init() {
        if (initialized) return
        synchronized(sync) {
            if (initialized) return
            if (ApplicationLoader.applicationContext == null) return

            loadConfig(false)
            updatePreferredTranslateTargetLangList()
            fixConfig()
            if (!BuildVars.LOGS_ENABLED) {
                showRPCError.setConfigBool(false)
            }
            initialized = true
        }
    }

    val sync = Any()
    private var configLoaded = false
    private val configs = ArrayList<ConfigItem>()

    // Configs
    val showTextBold =
        addConfig(
            "TextBold",
            ConfigItem.configTypeBool,
            true
        )
    val showTextItalic =
        addConfig(
            "TextItalic",
            ConfigItem.configTypeBool,
            true
        )
    val showTextMono =
        addConfig(
            "TextMonospace",
            ConfigItem.configTypeBool,
            true
        )
    val showTextStrikethrough =
        addConfig(
            "TextStrikethrough",
            ConfigItem.configTypeBool,
            true
        )
    val showTextUnderline =
        addConfig(
            "TextUnderline",
            ConfigItem.configTypeBool,
            true
        )
    val showTextQuote =
        addConfig(
            "TextQuote",
            ConfigItem.configTypeBool,
            true
        )
    val showTextSpoiler =
        addConfig(
            "TextSpoiler",
            ConfigItem.configTypeBool,
            true
        )
    val showTextCreateLink =
        addConfig(
            "TextLink",
            ConfigItem.configTypeBool,
            true
        )
    val showTextCreateMention =
        addConfig(
            "TextCreateMention",
            ConfigItem.configTypeBool,
            true
        )
    val showTextCreateDate =
        addConfig(
            "TextCreateDate",
            ConfigItem.configTypeBool,
            true
        )
    val showTextRegular =
        addConfig(
            "TextRegular",
            ConfigItem.configTypeBool,
            true
        )
    val showTextSelectAll =
        addConfig(
            "TextSelectAll",
            ConfigItem.configTypeBool,
            true
        )
    val showTextTranslate =
        addConfig(
            "TextTranslate",
            ConfigItem.configTypeBool,
            true
        )
    val textStyleOrder =
        addConfig(
            "TextStyleOrder",
            ConfigItem.configTypeString,
            "translate,bold,italic,mono,code,strike,underline,quote,spoiler,link,mention,date,regular"
        )
    val chatTimeZoneInsertTemplate =
        addConfig(
            "ChatTimeZoneInsertTemplate",
            ConfigItem.configTypeString,
            ""
        )
    val chatTimeZoneInsertRangeTemplate =
        addConfig(
            "ChatTimeZoneInsertRangeTemplate",
            ConfigItem.configTypeString,
            ""
        )
    val chatTimeZoneInsertLanguage =
        addConfig(
            "ChatTimeZoneInsertLanguage",
            ConfigItem.configTypeString,
            ""
        )
    val combineMessage =
        addConfig(
            "CombineMessage",
            ConfigItem.configTypeInt,
            0
        )
    val noiseSuppressAndVoiceEnhance =
        addConfig(
            "NoiseSuppressAndVoiceEnhance",
            ConfigItem.configTypeBool,
            false
        )
    val showNoQuoteForward =
        addConfig(
            "NoQuoteForward",
            ConfigItem.configTypeBool,
            false
        )
    val showRepeatAsCopy =
        addConfig(
            "RepeatAsCopy",
            ConfigItem.configTypeBool,
            false
        )
    val doubleTapAction =
        addConfig(
            "DoubleTapAction",
            ConfigItem.configTypeInt,
            3
        )
    val doubleTapActionOut =
        addConfig(
            "DoubleTapActionOut",
            ConfigItem.configTypeInt,
            8
        )
    val showCopyPhoto =
        addConfig(
            "CopyPhoto",
            ConfigItem.configTypeBool,
            false
        )
    val showReactions =
        addConfig(
            "Reactions",
            ConfigItem.configTypeBool,
            true
        )
    val customTitle =
        addConfig(
            "CustomTitle",
            ConfigItem.configTypeString,
            // NagramX: kept in sync by hand with the resValue 'NagramX' default in TMessagesProj/build.gradle
            if (BuildConfig.APPLICATION_ID.endsWith(".beta")) "DwellGram" else "DwellGramX"
        )
    val dateOfForwardedMsg =
        addConfig(
            "DateOfForwardedMsg",
            ConfigItem.configTypeBool,
            false
        )
    val showMessageID =
        addConfig(
            "ShowMessageID",
            ConfigItem.configTypeBool,
            false
        )
    val showRPCError =
        addConfig(
            "ShowRPCError",
            ConfigItem.configTypeBool,
            false
        )
    val zalgoFilter =
        addConfig(
            "ZalgoFilter",
            ConfigItem.configTypeBool,
            false
        )
    val alwaysShowDownloadIcon =
        addConfig(
            "AlwaysShowDownloadIcon",
            ConfigItem.configTypeBool,
            false
        )
    val customEditedMessage =
        addConfig(
            "CustomEditedMessage",
            ConfigItem.configTypeString,
            ""
        )
    val disableProxyWhenVpnEnabled =
        addConfig(
            "DisableProxyWhenVpnEnabled",
            ConfigItem.configTypeBool,
            false
        )
    val notificationIcon =
        addConfig(
            "NotificationIcon",
            ConfigItem.configTypeInt,
            1
        )
    val showSetReminder =
        addConfig(
            "SetReminder",
            ConfigItem.configTypeBool,
            false
        )
    // NagramX: #stash
    val showStash =
        addConfig(
            "Stash",
            ConfigItem.configTypeBool,
            false
        )
    val showOnlineStatus =
        addConfig(
            "ShowOnlineStatus",
            ConfigItem.configTypeBool,
            false
        )
    val showFullAbout =
        addConfig(
            "ShowFullAbout",
            ConfigItem.configTypeBool,
            true
        )
    val typeMessageHintUseGroupName =
        addConfig(
            "TypeMessageHintUseGroupName",
            ConfigItem.configTypeBool,
            false
        )
    val showSendAsUnderMessageHint =
        addConfig(
            "ShowSendAsUnderMessageHint",
            ConfigItem.configTypeBool,
            false
        )
    val hideBotButtonInInputField =
        addConfig(
            "HideBotButtonInInputField",
            ConfigItem.configTypeBool,
            false
        )
    val chatDecoration =
        addConfig(
            "ChatDecoration",
            ConfigItem.configTypeInt,
            0
        )
    val doNotUnarchiveBySwipe =
        addConfig(
            "DoNotUnarchiveBySwipe",
            ConfigItem.configTypeBool,
            false
        )
    val defaultDeleteMenu =
        addConfig(
            "DefaultDeleteMenu",
            ConfigItem.configTypeInt,
            0
        )
    val defaultDeleteMenuBanUsers =
        addConfig(
            "DeleteBanUsers",
            defaultDeleteMenu,
            3,
            false
        )
    val defaultDeleteMenReportSpam =
        addConfig(
            "DeleteReportSpam",
            defaultDeleteMenu,
            2,
            false
        )
    val defaultDeleteMenuDeleteAll =
        addConfig(
            "DeleteAll",
            defaultDeleteMenu,
            1,
            false
        )
    val defaultDeleteMenuDoActionsInCommonGroups =
        addConfig(
            "DoActionsInCommonGroups",
            defaultDeleteMenu,
            0,
            false
        )
    val disableStories =
        addConfig(
            "DisableStories",
            ConfigItem.configTypeBool,
            false
        )
    val useLocalQuoteColorData =
        addConfig(
            "useLocalQuoteColorData",
            ConfigItem.configTypeString,
            ""
        )
    val useLocalEmojiStatusData =
        addConfig(
            "useLocalEmojiStatusData",
            ConfigItem.configTypeString,
            ""
        )
    val disableMarkdown =
        addConfig(
            "DisableMarkdown",
            ConfigItem.configTypeBool,
            false
        )
    val showSmallGIF =
        addConfig(
            "ShowSmallGIF",
            ConfigItem.configTypeBool,
            false
        )
    val disableClickCommandToSend =
        addConfig(
            "DisableClickCommandToSend",
            ConfigItem.configTypeBool,
            false
        )
    val disableDialogsFloatingButton =
        addConfig(
            "DisableDialogsFloatingButton",
            ConfigItem.configTypeBool,
            false
        )
    val centerActionBarTitle =
        addConfig(
            "CenterActionBarTitle",
            ConfigItem.configTypeBool,
            false
        )
    val showQuickReplyInBotCommands =
        addConfig(
            "ShowQuickReplyInBotCommands",
            ConfigItem.configTypeBool,
            false
        )
    val pushServiceType =
        addConfig(
            "PushServiceType",
            ConfigItem.configTypeInt,
            1
        )
    val pushServiceTypeInAppDialog =
        addConfig(
            "PushServiceTypeInAppDialog",
            ConfigItem.configTypeBool,
            false
        )
    val pushServiceTypeUnifiedGateway =
        addConfig(
            "PushServiceTypeUnifiedGateway",
            ConfigItem.configTypeString,
            ""
        )
    val pushServiceTypeUnifiedSimple =
        addConfig(
            "PushServiceTypeUnifiedSimple",
            ConfigItem.configTypeString,
            ""
        )
    val pushServiceTypeUnifiedWebPushPrivateKey =
        addConfig(
            "PushServiceTypeUnifiedWebPushPrivateKey",
            ConfigItem.configTypeString,
            ""
        )
    val pushServiceTypeUnifiedWebPushPublicKey =
        addConfig(
            "PushServiceTypeUnifiedWebPushPublicKey",
            ConfigItem.configTypeString,
            ""
        )
    val pushServiceTypeUnifiedWebPushAuthSecret =
        addConfig(
            "PushServiceTypeUnifiedWebPushAuthSecret",
            ConfigItem.configTypeString,
            ""
        )
    val sendMp4DocumentAsVideo =
        addConfig(
            "SendMp4DocumentAsVideo",
            ConfigItem.configTypeBool,
            true
        )
    val disableChannelMuteButton =
        addConfig(
            "DisableChannelMuteButton",
            ConfigItem.configTypeBool,
            false
        )
    val swipeToNextWithinFolder =
        addConfig(
            "SwipeToNextWithinFolder",
            ConfigItem.configTypeBool,
            false
        )
    val disablePreviewVideoSoundShortcut =
        addConfig(
            "DisablePreviewVideoSoundShortcut",
            ConfigItem.configTypeBool,
            true
        )
    val regexFiltersEnabled =
        addConfig(
            "RegexFilters",
            ConfigItem.configTypeBool,
            false
        )
    val regexFiltersData =
        addConfig(
            "RegexFiltersData",
            ConfigItem.configTypeString,
            "[]"
        )
    val regexFiltersEnableInChats =
        addConfig(
            "RegexFiltersEnableInChats",
            ConfigItem.configTypeBool,
            false
        )
    val regexChatFiltersData =
        addConfig(
            "RegexChatFiltersData",
            ConfigItem.configTypeString,
            "[]"
        )
    val regexFiltersExcludedDialogs =
        addConfig(
            "RegexFiltersExcludedDialogs",
            ConfigItem.configTypeString,
            "[]"
        )
    val blockedChannelsData =
        addConfig(
            "BlockedChannelsData",
            ConfigItem.configTypeString,
            "[]"
        )
    val customFilteredUsersData =
        addConfig(
            "CustomFilteredUsersData",
            ConfigItem.configTypeString,
            "[]"
        )
    val showTimeHint =
        addConfig(
            "ShowTimeHint",
            ConfigItem.configTypeBool,
            false
        )
    val searchHashtagDefaultPageChannel =
        addConfig(
            "SearchHashtagDefaultPageChannel",
            ConfigItem.configTypeInt,
            0
        )
    val searchHashtagDefaultPageChat =
        addConfig(
            "SearchHashtagDefaultPageChat",
            ConfigItem.configTypeInt,
            0
        )
    val enablePanguOnSending =
        addConfig(
            "EnablePanguOnSending",
            ConfigItem.configTypeBool,
            false
        )
    val defaultHlsVideoQuality =
        addConfig(
            "DefaultHlsVideoQuality",
            ConfigItem.configTypeInt,
            0
        )
    val disableBotOpenButton =
        addConfig(
            "DisableBotOpenButton",
            ConfigItem.configTypeBool,
            false
        )
    val customTitleUserName =
        addConfig(
            "CustomTitleUserName",
            ConfigItem.configTypeBool,
            false
        )
    val enhancedVideoBitrate =
        addConfig(
            "EnhancedVideoBitrate",
            ConfigItem.configTypeBool,
            false
        )
    val ActionBarButtonReply =
        addConfig(
            "Reply",
            ConfigItem.configTypeBool,
            false
        )
    val ActionBarButtonEdit =
        addConfig(
            "Edit",
            ConfigItem.configTypeBool,
            true
        )
    val ActionBarButtonSelectBetween =
        addConfig(
            "SelectBetween",
            ConfigItem.configTypeBool,
            true
        )
    val ActionBarButtonCopy =
        addConfig(
            "Copy",
            ConfigItem.configTypeBool,
            true
        )
    val ActionBarButtonForward =
        addConfig(
            "Forward",
            ConfigItem.configTypeBool,
            true
        )
    val playerDecoder =
        addConfig(
            "VideoPlayerDecoder",
            ConfigItem.configTypeInt,
            1
        )

    // NagramX
    val enableSaveDeletedMessages =
        addConfig(
            "EnableSaveDeletedMessages",
            ConfigItem.configTypeBool,
            false
        )
    val enableSaveEditsHistory =
        addConfig(
            "EnableSaveEditsHistory",
            ConfigItem.configTypeBool,
            false
        )
    val saveLocalLastSeen =
        addConfig(
            "SaveLocalLastSeen",
            ConfigItem.configTypeBool,
            false
        )
    val messageSavingSaveMedia =
        addConfig(
            "MessageSavingSaveMedia",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPrivateChats =
        addConfig(
            "SaveMediaInPrivateChats",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPublicChannels =
        addConfig(
            "SaveMediaInPublicChannels",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPrivateChannels =
        addConfig(
            "SaveMediaInPrivateChannels",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPublicGroups =
        addConfig(
            "SaveMediaInPublicGroups",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPrivateGroups =
        addConfig(
            "SaveMediaInPrivateGroups",
            ConfigItem.configTypeBool,
            true
        )
    val saveDeletedMessagesInPrivateChats =
        addConfig(
            "SaveDeletedMessagesInPrivateChats",
            ConfigItem.configTypeBool,
            true
        )
    val saveDeletedMessagesInPublicChannels =
        addConfig(
            "SaveDeletedMessagesInPublicChannels",
            ConfigItem.configTypeBool,
            true
        )
    val saveDeletedMessagesInPrivateChannels =
        addConfig(
            "SaveDeletedMessagesInPrivateChannels",
            ConfigItem.configTypeBool,
            true
        )
    val saveDeletedMessagesInPublicGroups =
        addConfig(
            "SaveDeletedMessagesInPublicGroups",
            ConfigItem.configTypeBool,
            true
        )
    val saveDeletedMessagesInPrivateGroups =
        addConfig(
            "SaveDeletedMessagesInPrivateGroups",
            ConfigItem.configTypeBool,
            true
        )
    val saveEditsHistoryInPrivateChats =
        addConfig(
            "SaveEditsHistoryInPrivateChats",
            ConfigItem.configTypeBool,
            true
        )
    val saveEditsHistoryInPublicChannels =
        addConfig(
            "SaveEditsHistoryInPublicChannels",
            ConfigItem.configTypeBool,
            true
        )
    val saveEditsHistoryInPrivateChannels =
        addConfig(
            "SaveEditsHistoryInPrivateChannels",
            ConfigItem.configTypeBool,
            true
        )
    val saveEditsHistoryInPublicGroups =
        addConfig(
            "SaveEditsHistoryInPublicGroups",
            ConfigItem.configTypeBool,
            true
        )
    val saveEditsHistoryInPrivateGroups =
        addConfig(
            "SaveEditsHistoryInPrivateGroups",
            ConfigItem.configTypeBool,
            true
        )
    val saveDeletedMessageForBot =
        addConfig(
            "SaveDeletedMessageForBot", // save in bot chats
            ConfigItem.configTypeBool,
            false
        )
    val saveDeletedMessageForBotUser =
        addConfig(
            "SaveDeletedMessageForBotUser", // all messages from bot
            ConfigItem.configTypeBool,
            false
        )
    val customDeletedMark =
        addConfig(
            "CustomDeletedMark",
            ConfigItem.configTypeString,
            ""
        )
    val hidePremiumSection =
        addConfig(
            "HidePremiumSection",
            ConfigItem.configTypeBool,
            false
        )
    val hideHelpSection =
        addConfig(
            "HideHelpSection",
            ConfigItem.configTypeBool,
            true
        )
    val llmApiUrl =
        addConfig(
            "LlmApiUrl",
            ConfigItem.configTypeString,
            ""
        )
    val llmApiKey =
        addConfig(
            "LlmApiKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmModelName =
        addConfig(
            "LlmModelName",
            ConfigItem.configTypeString,
            ""
        )
    val llmSystemPrompt =
        addConfig(
            "LlmSystemPrompt",
            ConfigItem.configTypeString,
            ""
        )
    val llmUserPrompt =
        addConfig(
            "LlmUserPrompt",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderPreset =
        addConfig(
            "LlmProviderPreset",
            ConfigItem.configTypeInt,
            0
        )
    val llmProviderOpenAIKey =
        addConfig(
            "LlmProviderOpenAIKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOpenAIModel =
        addConfig(
            "LlmProviderOpenAIModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderGeminiKey =
        addConfig(
            "LlmProviderGeminiKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderGeminiModel =
        addConfig(
            "LlmProviderGeminiModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderXAIKey =
        addConfig(
            "LlmProviderXAIKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderXAIModel =
        addConfig(
            "LlmProviderXAIModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderGroqKey =
        addConfig(
            "LlmProviderGroqKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderGroqModel =
        addConfig(
            "LlmProviderGroqModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderDeepSeekKey =
        addConfig(
            "LlmProviderDeepSeekKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderDeepSeekModel =
        addConfig(
            "LlmProviderDeepSeekModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderCerebrasKey =
        addConfig(
            "LlmProviderCerebrasKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderCerebrasModel =
        addConfig(
            "LlmProviderCerebrasModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOllamaCloudKey =
        addConfig(
            "LlmProviderOllamaCloudKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOllamaCloudModel =
        addConfig(
            "LlmProviderOllamaCloudModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOpenRouterKey =
        addConfig(
            "LlmProviderOpenRouterKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOpenRouterModel =
        addConfig(
            "LlmProviderOpenRouterModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderVercelAIGatewayKey =
        addConfig(
            "LlmProviderVercelAIGatewayKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderVercelAIGatewayModel =
        addConfig(
            "LlmProviderVercelAIGatewayModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderVertexKey =
        addConfig(
            "LlmProviderVertexKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderVertexModel =
        addConfig(
            "LlmProviderVertexModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmTemperature =
        addConfig(
            "LlmTemperature",
            ConfigItem.configTypeFloat,
            0.7f
        )
    val llmUseContext =
        addConfig(
            "LlmUseContext",
            ConfigItem.configTypeBool,
            false
        )
    val llmContextSize =
        addConfig(
            "LlmContextSize",
            ConfigItem.configTypeInt,
            2
        )
    val llmUseContextInAutoTranslate =
        addConfig(
            "LlmUseContextInAutoTranslate",
            ConfigItem.configTypeBool,
            false
        )
    val translucentDeletedMessages =
        addConfig(
            "TranslucentDeletedMessages",
            ConfigItem.configTypeBool,
            true
        )
    val enableSeparateArticleTranslator =
        addConfig(
            "EnableSeparateArticleTranslator",
            ConfigItem.configTypeBool,
            false
        )
    val articleTranslationProvider =
        addConfig(
            "ArticleTranslationProvider",
            ConfigItem.configTypeInt,
            1
        )
    val disableCrashlyticsCollection =
        addConfig(
            "DisableCrashlyticsCollection",
            ConfigItem.configTypeBool,
            false
        )
    val showStickersRowToplevel =
        addConfig(
            "ShowStickersRowToplevel",
            ConfigItem.configTypeBool,
            true
        )
    val hideShareButtonInChannel =
        addConfig(
            "HideShareButtonInChannel",
            ConfigItem.configTypeBool,
            false
        )
    val preferredTranslateTargetLang =
        addConfig(
            "PreferredTranslateTargetLang",
            ConfigItem.configTypeString,
            ""
        )
    val telegramUIAutoTranslate =
        addConfig(
            "TelegramUIAutoTranslate",
            ConfigItem.configTypeBool,
            true
        )
    val translatorMode =
        addConfig(
            "TranslatorMode",
            ConfigItem.configTypeInt,
            0 // 0: off; 1: manual only; 2: all
        )
    val translatorModeWithOriginalMigrated =
        addConfig(
            "TranslatorModeWithOriginalMigrated",
            ConfigItem.configTypeBool,
            false
        )
    val centerActionBarTitleType =
        addConfig(
            "CenterActionBarTitleType",
            ConfigItem.configTypeInt,
            1 // 0: off; 1: always on; 2: settings only; 3: chats only
        )
    val hideArchive =
        addConfig(
            "HideArchive",
            ConfigItem.configTypeBool,
            false
        )
    val confirmAllLinks =
        addConfig(
            "ConfirmAllLinks",
            ConfigItem.configTypeBool,
            false
        )
    val useDeletedIcon =
        addConfig(
            "UseDeletedIcon",
            ConfigItem.configTypeBool,
            true
        )
    val useEditedIcon =
        addConfig(
            "UseEditedIcon",
            ConfigItem.configTypeBool,
            true
        )
    val saveToChatSubfolder =
        addConfig(
            "SaveToChatSubfolder",
            ConfigItem.configTypeBool,
            false
        )
    val customFileNamesEnabled =
        addConfig(
            "CustomFileNamesSwitch",
            ConfigItem.configTypeBool,
            false
        )
    val customFileNamesPattern =
        addConfig(
            "CustomFileNames",
            ConfigItem.configTypeString,
            "{date}_{time}"
        )
    val silentMessageByDefault =
        addConfig(
            "SilentMessageByDefault",
            ConfigItem.configTypeBool,
            false
        )
    val folderNameAsTitle =
        addConfig(
            "FolderNameAsTitle",
            ConfigItem.configTypeBool,
            false
        )
    val translatorKeepMarkdown =
        addConfig(
            "TranslatorKeepMarkdown",
            ConfigItem.configTypeBool,
            true
        )
    val googleTranslateExp =
        addConfig(
            "GoogleTranslateExp",
            ConfigItem.configTypeBool,
            true
        )
    val springAnimationCrossfade =
        addConfig(
            "SpringAnimationCrossfade",
            ConfigItem.configTypeBool,
            true
        )
    val dontAutoPlayNextVoice =
        addConfig(
            "DontAutoPlayNextVoice",
            ConfigItem.configTypeBool,
            false
        )
    val videoMessagesPlayMode =
        addConfig(
            "VideoMessagesPlayMode",
            ConfigItem.configTypeInt,
            VIDEO_PLAY_ALL
        )
    val videoMessagesMuted =
        addConfig(
            "VideoMessagesMuted",
            ConfigItem.configTypeBool,
            false
        )
    val videoMessagesResetZoomOnSwitch =
        addConfig(
            "VideoMessagesResetZoomOnSwitch",
            ConfigItem.configTypeBool,
            false
        )
    val videoMessagesCamera2 =
        addConfig(
            "VideoMessagesCamera2",
            ConfigItem.configTypeBool,
            false
        )
    val videoMessagesWarningVibration =
        addConfig(
            "VideoMessagesWarningVibration",
            ConfigItem.configTypeInt,
            1 // 0: off; 1: light; 2: medium; 3: strong
        )
    val videoMessagesCutVibration =
        addConfig(
            "VideoMessagesCutVibration",
            ConfigItem.configTypeInt,
            2 // 0: off; 1: light; 2: medium; 3: strong
        )
    val videoMessagesHoldToSend =
        addConfig(
            "VideoMessagesHoldToSend",
            ConfigItem.configTypeBool,
            true
        )
    // How far the hold-to-send hint has been learned: sends counted up to its cap, and whether a swipe was used
    val videoMessagesHoldToSendSends =
        addConfig(
            "VideoMessagesHoldToSendSends",
            ConfigItem.configTypeInt,
            0
        )
    val videoMessagesSwipeLearned =
        addConfig(
            "VideoMessagesSwipeLearned",
            ConfigItem.configTypeBool,
            false
        )
    val infiniteRecordingCeiling =
        addConfig(
            "InfiniteRecordingCeiling",
            ConfigItem.configTypeInt,
            // persisted minutes; 0 is the settings row's Unlimited sentinel -- see
            // ChatActivityEnterView.getInfiniteVideoMaxSegments() for how it's read
            10
        )
    val videoNoteShortcut =
        addConfig(
            "VideoNoteShortcut",
            ConfigItem.configTypeBool,
            false
        )
    val videoNoteShortcutCamera =
        addConfig(
            "VideoNoteShortcutCamera",
            ConfigItem.configTypeInt,
            0 // 0: front; 1: rear; 2: both, as two shortcuts
        )
    val textMemoShortcut =
        addConfig(
            "TextMemoShortcut",
            ConfigItem.configTypeBool,
            false
        )
    val newConversationShortcut =
        addConfig(
            "NewConversationShortcut",
            ConfigItem.configTypeBool,
            true // Telegram's own shortcut, shipped always-on before it had a switch
        )
    val ghostModeShortcut =
        addConfig(
            "GhostModeShortcut",
            ConfigItem.configTypeBool,
            true // shipped always-on before it had a switch
        )
    val messageColoredBackground =
        addConfig(
            "MessageColoredBackground",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemBoostGroup =
        addConfig(
            "ChatMenuItemBoostGroup",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemLinkedChat =
        addConfig(
            "ChatMenuItemLinkedChat",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemToBeginning =
        addConfig(
            "ChatMenuItemToBeginning",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemGoToMessage =
        addConfig(
            "ChatMenuItemGoToMessage",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemHideTitle =
        addConfig(
            "ChatMenuItemHideTitle",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemViewDeleted =
        addConfig(
            "ChatMenuItemViewDeleted",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemClearDeleted =
        addConfig(
            "ChatMenuItemClearDeleted",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemDeleteOwnMessages =
        addConfig(
            "ChatMenuItemDeleteOwnMessages",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemNotifProfileBell =
        addConfig(
            "ChatMenuItemNotifProfileBell",
            ConfigItem.configTypeBool,
            true
        )
    // Empty means never edited: ChatMenuLayout derives it from the ChatMenuItem* / Shortcuts* switches above.
    val chatMenuLayout =
        addConfig(
            "ChatMenuLayout",
            ConfigItem.configTypeString,
            ""
        )
    val mediaViewerMenuItemForward =
        addConfig(
            "MediaViewerMenuItemForward",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemNoQuoteForward =
        addConfig(
            "MediaViewerMenuItemNoQuoteForward",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemCopyFrame =
        addConfig(
            "MediaViewerMenuItemCopyFrame",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemCopyPhoto =
        addConfig(
            "MediaViewerMenuItemCopyPhoto",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemSetProfilePhoto =
        addConfig(
            "MediaViewerMenuItemSetProfilePhoto",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemScanQRCode =
        addConfig(
            "MediaViewerMenuItemScanQRCode",
            ConfigItem.configTypeBool,
            true
        )
    val hideReactions =
        addConfig(
            "HideReactions",
            ConfigItem.configTypeBool,
            false
        )
    val performanceClass =
        addConfig(
            "PerformanceClass",
            ConfigItem.configTypeInt,
            0
        )
    val transcribeProvider =
        addConfig(
            "TranscribeProvider",
            ConfigItem.configTypeInt,
            0
        )
    val transcribeProviderCfAccountID =
        addConfig(
            "TranscribeProviderCfAccountID",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderCfApiToken =
        addConfig(
            "TranscribeProviderCfApiToken",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderCfLanguage =
        addConfig(
            "TranscribeProviderCfLanguage",
            ConfigItem.configTypeInt,
            0
        )
    val transcribeProviderCfVadFilter =
        addConfig(
            "TranscribeProviderCfVadFilter",
            ConfigItem.configTypeBool,
            true
        )
    val transcribeProviderCfConditionOnPreviousText =
        addConfig(
            "TranscribeProviderCfConditionOnPreviousText",
            ConfigItem.configTypeBool,
            false
        )
    val transcribeProviderCfHallucinationSilenceThreshold =
        addConfig(
            "TranscribeProviderCfHallucinationSilenceThreshold",
            ConfigItem.configTypeFloat,
            0f
        )
    val transcribeProviderGeminiApiKey =
        addConfig(
            "TranscribeProviderGeminiApiKey",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderGroqApiKey =
        addConfig(
            "TranscribeProviderGroqApiKey",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderGroqModel =
        addConfig(
            "TranscribeProviderGroqModel",
            ConfigItem.configTypeInt,
            0
        )
    val transcribeProviderGroqLanguage =
        addConfig(
            "TranscribeProviderGroqLanguage",
            ConfigItem.configTypeInt,
            0
        )
    val transcribeProviderOpenAiApiBase =
        addConfig(
            "TranscribeProviderOpenAiApiBase",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderOpenAiModel =
        addConfig(
            "TranscribeProviderOpenAiModel",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderOpenAiApiKey =
        addConfig(
            "TranscribeProviderOpenAiApiKey",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderOpenAiPrompt =
        addConfig(
            "TranscribeProviderOpenAiPrompt",
            ConfigItem.configTypeString,
            ""
        )
    val showReplyInPrivate =
        addConfig(
            "ReplyInPrivate",
            ConfigItem.configTypeBool,
            false
        )
    val personalReplies =
        addConfig(
            "PersonalReplies",
            ConfigItem.configTypeBool,
            true
        )
    val transcribeProviderGeminiPrompt =
        addConfig(
            "TranscribeProviderGeminiPrompt",
            ConfigItem.configTypeString,
            ""
        )
    val hideDividers =
        addConfig(
            "HideDividers",
            ConfigItem.configTypeBool,
            false
        )
    val iconReplacements =
        addConfig(
            "IconReplacements",
            ConfigItem.configTypeInt,
            0
        )
    val showCopyAsSticker =
        addConfig(
            "CopyPhotoAsSticker",
            ConfigItem.configTypeBool,
            false
        )
    val showAddToStickers =
        addConfig(
            "AddToStickers",
            ConfigItem.configTypeBool,
            false
        )
    val showAddToFavorites =
        addConfig(
            "AddToFavorites",
            ConfigItem.configTypeBool,
            true
        )
    val showTranslateMessageLLM =
        addConfig(
            "TranslateMessageLLM",
            ConfigItem.configTypeBool,
            false
        )
    val shortcutsAdministrators =
        addConfig(
            "ChannelAdministrators",
            ConfigItem.configTypeBool,
            false
        )
    val shortcutsRecentActions =
        addConfig(
            "EventLog",
            ConfigItem.configTypeBool,
            true
        )
    val shortcutsStatistics =
        addConfig(
            "Statistics",
            ConfigItem.configTypeBool,
            false
        )
    val shortcutsPermissions =
        addConfig(
            "ChannelPermissions",
            ConfigItem.configTypeBool,
            false
        )
    val shortcutsMembers =
        addConfig(
            "GroupMembers",
            ConfigItem.configTypeBool,
            false
        )
    val leftBottomButton =
        addConfig(
            "LeftBottomButtonAction",
            ConfigItem.configTypeInt,
            0
        )
    val showTextMonoCode =
        addConfig(
            "TextMonoCode",
            ConfigItem.configTypeBool,
            true
        )
    val showCopyLink =
        addConfig(
            "CopyLink",
            ConfigItem.configTypeBool,
            true
        )
    val preferCommonGroupsTab =
        addConfig(
            "PreferCommonGroupsTab",
            ConfigItem.configTypeBool,
            true
        )
    val groupedMessageMenu =
        addConfig(
            "GroupedMessageMenu",
            ConfigItem.configTypeBool,
            true
        )
    val autoUpdateChannel =
        addConfig(
            "AutoUpdateChannel",
            ConfigItem.configTypeInt,
            0 // 0: off; 1: release; 2: beta
        )
    val premiumItemEmojiStatus =
        addConfig(
            "PremiumItemEmojiStatus",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemEmojiInReplies =
        addConfig(
            "PremiumItemEmojiInReplies",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemCustomColorInReplies =
        addConfig(
            "PremiumItemCustomColorInReplies",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemCustomWallpaper =
        addConfig(
            "PremiumItemCustomWallpaper",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemVideoAvatar =
        addConfig(
            "PremiumItemVideoAvatar",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemStarInReactions =
        addConfig(
            "PremiumItemStarInReactions",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemStickerEffects =
        addConfig(
            "PremiumItemStickerEffects",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemBoosts =
        addConfig(
            "PremiumItemBoosts",
            ConfigItem.configTypeBool,
            true
        )
    val switchStyle =
        addConfig(
            "SwitchStyle",
            ConfigItem.configTypeInt,
            0 // 0: default; 1: Modern; 2: MD3
        )
    val sliderStyle =
        addConfig(
            "SliderStyle",
            ConfigItem.configTypeInt,
            0 // 0: default; 1: Modern; 2: MD3
        )
    // NagramX (#interface-style): the main style choice is LiteMode.FLAG_LIQUID_GLASS.
    // Per-surface MD3 masks are consumed only by render branches that already support them;
    // MD3 colours must come from theme keys, not SwitchStyle/Monet.
    val interfaceStyleApplyChatHeader =
        addConfig(
            "InterfaceStyleApplyChatHeader",
            ConfigItem.configTypeBool,
            true
        )
    val interfaceStyleApplyChatListTopBar =
        addConfig(
            "InterfaceStyleApplyChatListTopBar",
            ConfigItem.configTypeBool,
            true
        )
    val interfaceStyleApplyButtons =
        addConfig(
            "InterfaceStyleApplyButtons",
            ConfigItem.configTypeBool,
            true
        )
    val interfaceStyleApplyComposer =
        addConfig(
            "InterfaceStyleApplyComposer",
            ConfigItem.configTypeBool,
            true
        )
    val composerLayoutStyle =
        addConfig(
            "ComposerLayoutStyle",
            ConfigItem.configTypeInt,
            0 // 0 floating island, 1 docked panel; anything else reads as floating
        )
    val interfaceStyleApplyBottomNavigation =
        addConfig(
            "InterfaceStyleApplyBottomNavigation",
            ConfigItem.configTypeBool,
            true
        )
    val interfaceStyleRoundedNavigation =
        addConfig(
            "InterfaceStyleRoundedNavigation",
            ConfigItem.configTypeBool,
            false
        )
    val interfaceStyleBlurStrength =
        addConfig(
            "InterfaceStyleBlurStrength",
            ConfigItem.configTypeInt,
            50 // percent; slider range 0..100 in 10% steps when MD3 surfaces consume it
        )
    val interfaceStyleMatchClassicDayHeader =
        addConfig(
            "InterfaceStyleMatchClassicDayHeader",
            ConfigItem.configTypeBool,
            false // off by default, unlike the Apply to rows: it replaces the 12.4.0 Classic look
        )
    val interfaceStylePanelDividers =
        addConfig(
            "InterfaceStylePanelDividers",
            ConfigItem.configTypeBool,
            true
        )
    val ignoreUnreadCount =
        addConfig(
            "IgnoreUnreadCount",
            ConfigItem.configTypeInt,
            getIgnoreMutedCountLegacy()
        )
    val markdownParser =
        addConfig(
            "MarkdownParser",
            ConfigItem.configTypeInt,
            NekoConfig.MARKDOWN_PARSER_NEKO
        )
    val quickScheduleButton =
        addConfig(
            "QuickScheduleButton",
            ConfigItem.configTypeBool,
            false
        )
    // NagramX (#combined-pinned-player): pinned message and audio player share one top row.
    val combinePinnedWithPlayer =
        addConfig(
            "CombinePinnedWithPlayer",
            ConfigItem.configTypeBool,
            false
        )
    // NagramX (#remember-send-action): master switch for the whole feature. Off both disarms
    // whatever is currently remembered (read in getEligibleArmedSendAction) and disables the four
    // toggles below on their settings page -- disabled, not hidden, so turning it back on doesn't
    // require re-discovering four separate rows.
    val rememberSendActionMaster =
        addConfig(
            "RememberSendActionMaster",
            ConfigItem.configTypeBool,
            true
        )
    // NagramX (#remember-send-action): whether long-pressing Send and picking each alternate
    // action arms it for the next send. App-wide, not per-account and not per-chat.
    val rememberSendActionSilent =
        addConfig(
            "RememberSendActionSilent",
            ConfigItem.configTypeBool,
            true
        )
    val rememberSendActionSendWhenOnline =
        addConfig(
            "RememberSendActionSendWhenOnline",
            ConfigItem.configTypeBool,
            true
        )
    val rememberSendActionSchedule =
        addConfig(
            "RememberSendActionSchedule",
            ConfigItem.configTypeBool,
            true
        )
    val rememberSendActionResetOnLeave =
        addConfig(
            "RememberSendActionResetOnLeave",
            ConfigItem.configTypeBool,
            true
        )
    val composerToolbarLayout =
        addConfig(
            "ComposerToolbarLayout",
            ConfigItem.configTypeString,
            ""
        )
    val composerLayoutMigrated =
        addConfig(
            "ComposerToolbarLayoutMigrated",
            ConfigItem.configTypeBool,
            false
        )
    val composerToolbarScale =
        addConfig(
            "ComposerToolbarScale",
            ConfigItem.configTypeInt,
            100 // percent; slider range 75..125 in 5% steps (see ComposerLayoutActivity)
        )
    val composerKeyboardGap =
        addConfig(
            "ComposerKeyboardGap",
            ConfigItem.configTypeInt,
            0 // dp; slider range 0..24, added only while the keyboard or emoji panel is open (see ComposerToolbarLayout)
        )
    @JvmStatic
    fun interfaceStyleBlurAlpha(): Float {
        val percent = interfaceStyleBlurStrength.Int().coerceIn(0, 100) / 2f
        return 1f - percent / 100f
    }

    @Volatile
    private var composerGlassTransparencyMigrated = false

    // NagramX: caller supplies theme mode because Theme may not be ready during NaConfig init.
    @JvmStatic
    fun migrateComposerGlassTransparency(preferDark: Boolean) {
        if (composerGlassTransparencyMigrated) {
            return
        }
        synchronized(sync) {
            if (composerGlassTransparencyMigrated) {
                return
            }
            val preferredComposerGlassKey = if (preferDark) "ComposerGlassDark" else "ComposerGlassLight"
            val fallbackComposerGlassKey = if (preferDark) "ComposerGlassLight" else "ComposerGlassDark"
            val hasLegacyComposerGlass = getPreferences().contains(preferredComposerGlassKey) || getPreferences().contains(fallbackComposerGlassKey)
            val legacyComposerGlassValue =
                legacyComposerGlassValue(preferredComposerGlassKey)
                    ?: legacyComposerGlassValue(fallbackComposerGlassKey)
                    ?: if (hasLegacyComposerGlass) 25 else null
            if (!getPreferences().contains(interfaceStyleBlurStrength.key) && legacyComposerGlassValue != null) {
                interfaceStyleBlurStrength.setConfigInt(
                    (legacyComposerGlassValue * 2).coerceIn(0, 100)
                )
            }
            if (hasLegacyComposerGlass) {
                getPreferences().edit {
                    remove("ComposerGlassLight")
                    remove("ComposerGlassDark")
                }
            }
            composerGlassTransparencyMigrated = true
        }
    }

    private fun legacyComposerGlassValue(key: String): Int? {
        if (!getPreferences().contains(key)) {
            return null
        }
        // NagramX: old fork builds could leave this key present with a non-int value.
        return try {
            getPreferences().getInt(key, 25).coerceIn(0, 50)
        } catch (e: ClassCastException) {
            FileLog.e(e)
            null
        }
    }

    val inputTextSize =
        addConfig(
            "InputTextSize",
            ConfigItem.configTypeInt,
            18 // dp; slider range 14..20 (see ComposerLayoutActivity)
        )
    val physicalKeyboardHotkeys =
        addConfig(
            "PhysicalKeyboardHotkeys",
            ConfigItem.configTypeBool,
            true
        )
    val defaultScheduledTime =
        addConfig(
            "DefaultScheduledTime",
            ConfigItem.configTypeInt,
            10
        )
    val rememberScheduleOffset =
        addConfig(
            "RememberScheduleOffset",
            ConfigItem.configTypeBool,
            false
        )
    val rememberedScheduleOffset =
        addConfig(
            "RememberedScheduleOffset",
            ConfigItem.configTypeInt,
            0 // minutes; 0 means nothing remembered yet
        )
    val keepTranslatorPreferences =
        addConfig(
            "KeepTranslatorPreferences",
            ConfigItem.configTypeBool,
            false
        )
    val usePinnedReactionsChats =
        addConfig(
            "UsePinnedReactionsChats",
            ConfigItem.configTypeBool,
            false
        )
    val pinnedReactionsChats =
        addConfig(
            "PinnedReactionsChats",
            ConfigItem.configTypeString,
            "[]"
        )
    val usePinnedReactionsChannels =
        addConfig(
            "UsePinnedReactionsChannels",
            ConfigItem.configTypeBool,
            false
        )
    val pinnedReactionsChannels =
        addConfig(
            "PinnedReactionsChannels",
            ConfigItem.configTypeString,
            "[]"
        )
    val hideStoriesFromHeader =
        addConfig(
            "HideStoriesFromHeader",
            ConfigItem.configTypeBool,
            true
        )
    val disableAvatarBlur =
        addConfig(
            "DisableAvatarBlur",
            ConfigItem.configTypeBool,
            false
        )
    val disableInAppBrowserGestures =
        addConfig(
            "DisableInAppBrowserGestures",
            ConfigItem.configTypeBool,
            false
        )
    val idDcType =
        addConfig(
            "IdDcType",
            ConfigItem.configTypeInt,
            1
        )
    val fixLinkPreview =
        addConfig(
            "FixLinkPreview",
            ConfigItem.configTypeBool,
            true
        )
    val showAddToBookmark =
        addConfig(
            "ShowAddToBookmark",
            ConfigItem.configTypeBool,
            false
        )
    val sortByUnread =
        addConfig(
            "SortByUnread",
            ConfigItem.configTypeBool,
            false
        )
    val cameraInVideoMessages =
        addConfig(
            "CameraInVideoMessages",
            ConfigItem.configTypeInt,
            1 // 0: front; 1: rear; 2: ask
        )
    val showCopyFrame =
        addConfig(
            "MessageMenuCopyFrame",
            ConfigItem.configTypeBool,
            false
        )
    val deleteChatForBothSides =
        addConfig(
            "DeleteChatForBothSides",
            ConfigItem.configTypeBool,
            true
        )
    val backAnimationStyle =
        addConfig(
            "BackAnimationStyle",
            ConfigItem.configTypeInt,
            0 // 0: Classic, 1: Spring, 2: Predictive Back
        )
    val mainTabsHideTitles =
        addConfig(
            "MainTabsHideTitles",
            ConfigItem.configTypeBool,
            false
        )
    val mainTabsHideContacts =
        addConfig(
            "MainTabsHideContacts",
            ConfigItem.configTypeBool,
            false
        )
    val showNotificationPreviewWhenLocked =
        addConfig(
            "ShowNotificationPreviewWhenLocked",
            ConfigItem.configTypeBool,
            false
        )
    val strokeOnViews =
        addConfig(
            "StrokeOnViews",
            ConfigItem.configTypeBool,
            true
        )
    val hideBottomNavigationBar =
        addConfig(
            "HideBottomNavigationBar",
            ConfigItem.configTypeBool,
            false
        )
    val hideDialogsSearchField =
        addConfig(
            "HideDialogsSearchField",
            ConfigItem.configTypeBool,
            false
        )
    val deepLTranslateKey =
        addConfig(
            "DeepLTranslateKey",
            ConfigItem.configTypeString,
            ""
        )
    val tabStyleStroke =
        addConfig(
            "TabStyleStroke",
            ConfigItem.configTypeBool,
            false
        )
    val addCommaAfterMention =
        addConfig(
            "AddCommaAfterMention",
            ConfigItem.configTypeBool,
            false
        )
    val templateMessagesData =
        addConfig(
            "TemplateMessagesData",
            ConfigItem.configTypeString,
            "[]"
        )
    val albumCaptionPerPhoto =
        addConfig(
            "AlbumCaptionPerPhoto",
            ConfigItem.configTypeBool,
            false
        )
    val videoMessageFlashBrightness =
        addConfig(
            "VideoMessageFlashBrightness",
            ConfigItem.configTypeFloat,
            1f
        )

    val preferredTranslateTargetLangList = ArrayList<String>()
    fun updatePreferredTranslateTargetLangList() {
        AndroidUtilities.runOnUIThread({
            preferredTranslateTargetLangList.clear()
            val str = preferredTranslateTargetLang.String().trim()

            if (str.isEmpty()) return@runOnUIThread

            val languages = str.replace('-', '_').split(",")
            if (languages.isEmpty() || languages[0].trim().isEmpty()) return@runOnUIThread

            languages.forEach { lang ->
                preferredTranslateTargetLangList.add(lang.trim().lowercase())
            }
        }, 1000)
    }

    private fun getIgnoreMutedCountLegacy(): Int {
        return when {
            getPreferences().getBoolean(
                "IgnoreFolderCount", false
            ) -> NekoConfig.DIALOG_FILTER_EXCLUDE_ALL

            getPreferences().getBoolean(
                "IgnoreMutedCount", true
            ) -> NekoConfig.DIALOG_FILTER_EXCLUDE_MUTED

            else -> NekoConfig.DIALOG_FILTER_EXCLUDE_NONE
        }
    }

    private fun fixConfig() {
        if (ApplicationLoader.applicationContext == null) {
            return
        }
        try {
            fixConfigInternal()
        } catch (e: Exception) {
            // NagramX: a cosmetic migration failing must never brick app startup
            FileLog.e("fixConfig failed", e)
        } catch (e: LinkageError) {
            // e.g. ExceptionInInitializerError from a broken migrated static initializer
            FileLog.e("fixConfig failed", e)
        }
    }

    private fun fixConfigInternal() {
        if (!translatorModeWithOriginalMigrated.Bool()) {
            if (getPreferences().contains(translatorMode.key)) {
                translatorMode.setConfigInt(
                    when (translatorMode.Int()) {
                        0 -> 1
                        1 -> 0
                        else -> 0
                    }
                )
            }
            translatorModeWithOriginalMigrated.setConfigBool(true)
        }
        if (translatorMode.Int() !in 0..2) {
            translatorMode.setConfigInt(0)
        }
        com.dazewell.gram.composer.ComposerLayout.migrate()
        if (!getPreferences().contains(idDcType.key) && !getPreferences().getBoolean(
                "ShowIdAndDc", true
            )
        ) {
            idDcType.setConfigInt(0)
        }
        if (!getPreferences().contains(cameraInVideoMessages.key)) {
            val legacyRear = getPreferences().getBoolean("RearVideoMessages", false)
            cameraInVideoMessages.setConfigInt(if (legacyRear) 1 else 0)
        }
        if (!getPreferences().contains(backAnimationStyle.key) &&
            getPreferences().contains("SpringAnimation")
        ) {
            val legacySpring = getPreferences().getBoolean("SpringAnimation", false)
            if (legacySpring) {
                backAnimationStyle.setConfigInt(1) // SPRING
            }
            getPreferences().edit { remove("SpringAnimation") }
        }
        if (!getPreferences().contains(strokeOnViews.key)) {
            strokeOnViews.changed(SharedConfig.getDevicePerformanceClass() != SharedConfig.PERFORMANCE_CLASS_LOW)
        }

        val mainPreferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
        if (!mainPreferences.contains("photoHighQualityDefault") && getPreferences().contains("SendHighQualityPhoto")) {
            val highQuality = getPreferences().getBoolean("SendHighQualityPhoto", true)
            mainPreferences.edit {
                putBoolean("photoHighQualityDefault", highQuality)
            }
            SharedConfig.photoHighQualityDefault = highQuality
        }

        val currentLlmApiUrl = llmApiUrl.String()
        val normalizedLlmApiUrl = UrlNormalizer.normalizeBaseUrl(currentLlmApiUrl)
        if (normalizedLlmApiUrl != currentLlmApiUrl) {
            llmApiUrl.setConfigString(normalizedLlmApiUrl)
        }
    }

    private fun resetInvalidConfig(o: ConfigItem, e: RuntimeException) {
        val key = if (o is ConfigItemKeyLinked) o.keyLinked.key else o.key
        FileLog.e("Invalid config value for $key", e)
        o.value = o.defaultValue
        getPreferences().edit { remove(key) }
    }

    private fun addConfig(
        k: String, t: Int, d: Any?
    ): ConfigItem {
        val a = ConfigItem(
            k, t, d
        )
        configs.add(
            a
        )
        return a
    }

    @Suppress("SameParameterValue")
    private fun addConfig(
        k: String, t: ConfigItem, d: Int, e: Any?
    ): ConfigItem {
        val a = ConfigItemKeyLinked(
            k,
            t,
            d,
            e,
        )
        configs.add(
            a
        )
        return a
    }

    fun loadConfig(
        force: Boolean
    ) {
        synchronized(
            sync
        ) {
            if (configLoaded && !force) {
                return
            }
            if (ApplicationLoader.applicationContext == null) {
                return
            }
            for (i in configs.indices) {
                val o = configs[i]
                try {
                    if (o.type == ConfigItem.configTypeBool) {
                        o.value = getPreferences().getBoolean(
                            o.key, o.defaultValue as Boolean
                        )
                    }
                    if (o.type == ConfigItem.configTypeInt) {
                        o.value = getPreferences().getInt(
                            o.key, o.defaultValue as Int
                        )
                    }
                    if (o.type == ConfigItem.configTypeLong) {
                        o.value = getPreferences().getLong(
                            o.key, (o.defaultValue as Long)
                        )
                    }
                    if (o.type == ConfigItem.configTypeFloat) {
                        o.value = getPreferences().getFloat(
                            o.key, (o.defaultValue as Float)
                        )
                    }
                    if (o.type == ConfigItem.configTypeString) {
                        o.value = getPreferences().getString(
                            o.key, o.defaultValue as String
                        )
                    }
                    if (o.type == ConfigItem.configTypeSetInt) {
                        val ss = getPreferences().getStringSet(
                            o.key, HashSet()
                        )
                        val si = HashSet<Int>()
                        for (s in ss!!) {
                            si.add(
                                s.toInt()
                            )
                        }
                        o.value = si
                    }
                    if (o.type == ConfigItem.configTypeMapIntInt) {
                        val cv = getPreferences().getString(
                            o.key, ""
                        )
                        // Log.e("NC", String.format("Getting pref %s val %s", o.key, cv));
                        if (cv!!.isEmpty()) {
                            o.value = HashMap<Int, Int>()
                        } else {
                            try {
                                val data = Base64.decode(
                                    cv, Base64.DEFAULT
                                )
                                val ois = ObjectInputStream(
                                    ByteArrayInputStream(
                                        data
                                    )
                                )
                                o.value = ois.readObject() as HashMap<*, *>
                                if (o.value == null) {
                                    o.value = HashMap<Int, Int>()
                                }
                                ois.close()
                            } catch (_: Exception) {
                                o.value = HashMap<Int, Int>()
                            }
                        }
                    }
                    if (o.type == ConfigItem.configTypeBoolLinkInt) {
                        o as ConfigItemKeyLinked
                        o.changedFromKeyLinked(getPreferences().getInt(o.keyLinked.key, 0))
                    }
                } catch (e: ClassCastException) {
                    resetInvalidConfig(o, e)
                } catch (e: NumberFormatException) {
                    resetInvalidConfig(o, e)
                }
            }
            configLoaded = true
        }
    }

    fun getConfigTypes(): Map<String, Int> {
        synchronized(sync) {
            return configs.associate { it.key to it.type }
        }
    }

    init {
        init()
    }

}
