package tw.nekomimi.nekogram.helpers.remote;

import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import xyz.nextalone.nagram.NaConfig;

public class UpdateHelper extends BaseRemoteHelper {

    public static final int UPDATE_OFF = 0;
    public static final int UPDATE_CHANNEL_RELEASE = 1;
    public static final int UPDATE_CHANNEL_BETA = 2;
    private boolean updateAlways = false;

    public static UpdateHelper getInstance() {
        return InstanceHolder.instance;
    }

    public static void cleanAppUpdate() {
        if (SharedConfig.pendingAppUpdate != null && SharedConfig.pendingAppUpdate.document != null) {
            File path = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(SharedConfig.pendingAppUpdate.document, true);
            if (path != null && path.exists()) {
                Utilities.globalQueue.postRunnable(() -> {
                    try {
                        if (!path.delete()) path.deleteOnExit();
                    } catch (Exception ignored) {
                    }
                });
            }
        }
        SharedConfig.pendingAppUpdate = null;
        SharedConfig.saveConfig();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    @Override
    protected void onError(String text, Delegate delegate) {
        delegate.onTLResponse(null, text);
    }

    // NagramX: reads the fork's own channel, which CI posts only staging builds to. One tag per
    // package, so the old Release/Beta split and the debug tag no longer pick anything (#self-update).
    @Override
    protected String getTag() {
        return com.dazewell.gram.selfupdate.SelfUpdate.tag();
    }

    @Override
    protected boolean isSourceEnabled() {
        return com.dazewell.gram.selfupdate.SelfUpdate.isAvailable();
    }

    @Override
    protected long getChannelId() {
        return com.dazewell.gram.selfupdate.SelfUpdate.channelId();
    }

    @Override
    protected String getChannelUsername() {
        return com.dazewell.gram.selfupdate.SelfUpdate.channelUsername();
    }

    @SuppressWarnings("ConstantConditions")
    private Integer getPreferredAbiFile(Map<String, Integer> files) {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (files.containsKey(abi)) {
                return files.get(abi);
            }
        }
        return files.getOrDefault("universal", files.get("arm64-v8a"));
    }

    private Map<String, Integer> jsonToMap(JSONObject obj) {
        Map<String, Integer> map = new HashMap<>();
        List<String> abis = new ArrayList<>();
        abis.add("arm64-v8a");
        abis.add("universal");
        try {
            for (var abi : abis) {
                map.put(abi, obj.getInt(abi));
            }
        } catch (JSONException ignored) {
        }
        return map;
    }

    private Update getShouldUpdateVersion(List<JSONObject> responses) {
        int currentVersion = BuildConfig.VERSION_CODE;
        long buildTimestamp = BuildConfig.BUILD_TIMESTAMP;
        Update ref = null;
        for (var string : responses) {
            try {
                int remoteVersion = string.getInt("version_code");
                long remoteBuildTimestamp = string.optLong("build_timestamp", 0L);
                boolean shouldUpdate = false;
                if (remoteVersion > currentVersion) {
                    shouldUpdate = true;
                } else if (remoteVersion == currentVersion && remoteBuildTimestamp > buildTimestamp) {
                    shouldUpdate = true;
                }
                // NagramX: search order is posting order, and a dispatched staging run can post after
                // a newer one, so keep the newest qualifying build instead of the first. updateAlways
                // still takes the most recent post as-is.
                if (!updateAlways && ref != null && (remoteVersion < ref.versionCode
                        || (remoteVersion == ref.versionCode && remoteBuildTimestamp <= ref.buildTimestamp))) {
                    continue;
                }
                if (shouldUpdate || updateAlways) {
                    boolean takeFirst = updateAlways;
                    if (updateAlways) {
                        updateAlways = false;
                    }
                    // NagramX: sticker, message and url are optional in the fork's metadata.
                    ref = new Update(
                            string.optBoolean("can_not_skip", false),
                            string.getString("version"),
                            remoteVersion,
                            string.optInt("sticker", 0),
                            string.optInt("message", 0),
                            jsonToMap(string.getJSONObject("document")),
                            string.optString("url", null)
                    );
                    ref.buildTimestamp = remoteBuildTimestamp;
                    if (takeFirst) {
                        break;
                    }
                }
            } catch (JSONException ignored) {
            }
        }
        return ref;
    }

    private void getNewVersionMessagesCallback(Delegate delegate, Update json, HashMap<String, Integer> ids, TLObject response) {
        var update = new TLRPC.TL_help_appUpdate();
        update.version = json.version;
        // NagramX: writeString(null) silently writes nothing, so a post whose changelog message is
        // gone would persist a misaligned pending update. Default to no text.
        update.text = "";
        // NagramX: every staging build shares one version code, so the build timestamp is what
        // tells two builds apart. It rides in the otherwise unread id so the persisted pending
        // update can be judged by it (SelfUpdate.isStale). Seconds fit an int until 2038.
        update.id = (int) json.buildTimestamp;
        update.can_not_skip = json.canNotSkip;
        if (json.url != null) {
            update.url = json.url;
            update.flags |= 4;
        }
        if (response != null) {
            var res = (TLRPC.messages_Messages) response;
            getMessagesController().removeDeletedMessagesFromArray(getChannelId(), res.messages);
            var messages = new HashMap<Integer, TLRPC.Message>();
            for (var message : res.messages) {
                messages.put(message.id, message);
            }
            if (ids.containsKey("sticker")) {
                var sticker = messages.get(ids.get("sticker"));
                if (sticker != null && sticker.media != null) {
                    update.sticker = sticker.media.document;
                    update.flags |= 8;
                }
            }
            if (ids.containsKey("message")) {
                var message = messages.get(ids.get("message"));
                if (message != null) {
                    update.text = message.message;
                    update.entities = message.entities;
                }
            }
            if (ids.containsKey("document")) {
                var file = messages.get(ids.get("document"));
                if (file != null && file.media != null) {
                    update.document = file.media.document;
                    update.flags |= 2;
                }
            }
        }
        // NagramX: the update dialog dereferences the document, so a post whose APK is gone is no update.
        if (update.document == null) {
            delegate.onTLResponse(null, null);
            return;
        }
        delegate.onTLResponse(update, null);
    }

    @Override
    protected void onLoadSuccess(ArrayList<JSONObject> responses, Delegate delegate) {
        var update = getShouldUpdateVersion(responses);
        if (update == null) {
            delegate.onTLResponse(null, null);
            return;
        }
        var ids = new HashMap<String, Integer>();
        if (update.sticker != null && update.sticker > 0) {
            ids.put("sticker", update.sticker);
        }
        if (update.message != null && update.message > 0) {
            ids.put("message", update.message);
        }
        Integer documentId = update.document == null ? null : getPreferredAbiFile(update.document);
        if (documentId != null && documentId > 0) {
            ids.put("document", documentId);
        }
        if (ids.isEmpty()) {
            getNewVersionMessagesCallback(delegate, update, null, null);
        } else {
            var req = new TLRPC.TL_channels_getMessages();
            req.channel = getMessagesController().getInputChannel(getChannelId());
            req.id = new ArrayList<>(ids.values());
            getConnectionsManager().sendRequest(req, (response1, error1) -> {
                if (error1 == null) {
                    getNewVersionMessagesCallback(delegate, update, ids, response1);
                } else {
                    delegate.onTLResponse(null, error1.text);
                }
            });
        }
    }

    public void checkNewVersionAvailable(Delegate delegate) {
        checkNewVersionAvailable(delegate, false);
    }

    public void checkNewVersionAvailable(Delegate delegate, boolean updateAlways) {
        this.updateAlways = updateAlways;
        // NagramX: single point that enforces "off means no request". Covers every caller,
        // including the two that bypass LaunchActivity.checkAppUpdate (FileRefController and
        // BlockingUpdateView), so no update request leaves the device while checks are off.
        if (NaConfig.INSTANCE.getAutoUpdateChannel().Int() == UPDATE_OFF) {
            delegate.onTLResponse(null, null);
            return;
        }
        load(delegate);
    }

    private static final class InstanceHolder {
        private static final UpdateHelper instance = new UpdateHelper();
    }

    public static class Update {
        public Boolean canNotSkip;
        public String version;
        public Integer versionCode;
        public Integer sticker;
        public Integer message;
        public Map<String, Integer> document;
        public String url;
        public long buildTimestamp;

        public Update(Boolean canNotSkip, String version, int versionCode, int sticker, int message, Map<String, Integer> document, String url) {
            this.canNotSkip = canNotSkip;
            this.version = version;
            this.versionCode = versionCode;
            this.sticker = sticker;
            this.message = message;
            this.document = document;
            this.url = url;
        }
    }
}
