package com.dazewell.gram.videonote;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;

/**
 * The memo shortcuts for apps that list an app's exported activities (Shortcut Maker, Panels, gesture launchers) or its
 * CREATE_SHORTCUT entries, which can't see launcher shortcuts and can't start the hash-gated intents they carry. One
 * invisible activity behind four aliases, one per memo, each its own entry in those lists. Started, it builds the same
 * intent the launcher shortcut does, with the current hash, so the memos behave exactly as they do from the launcher:
 * the app lock included. Off while the memo's setting is off.
 */
public class MemoPickerActivity extends Activity {

    private static final String ALIAS_PREFIX = "com.dazewell.gram.videonote.MemoPicker";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // A cold process starts here: NaConfig and the hash load in postInitApplication, and until then the switches read false
        ApplicationLoader.postInitApplication();
        super.onCreate(savedInstanceState);
        Intent self = getIntent();
        if (self != null && Intent.ACTION_CREATE_SHORTCUT.equals(self.getAction())) {
            // The picked entry points back at this alias, so the hash is read when it is tapped, not frozen into the
            // saved shortcut, where the next rotation would break it
            ComponentName alias = getComponentName();
            Intent result = new Intent();
            result.putExtra(Intent.EXTRA_SHORTCUT_INTENT, new Intent(Intent.ACTION_MAIN).setComponent(alias));
            result.putExtra(Intent.EXTRA_SHORTCUT_NAME, com.dazewell.gram.helpers.ShortcutHelper.variantLabel(org.telegram.messenger.LocaleController.getString(labelOf(alias.getClassName()))));
            result.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(this, iconOf(alias.getClassName())));
            setResult(RESULT_OK, result);
        } else {
            Intent target = createTarget(getComponentName().getClassName());
            if (target != null) {
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(target);
                } catch (Throwable e) {
                    org.telegram.messenger.FileLog.e(e);
                }
            }
        }
        finish();
    }

    // Null when the memo is off or the hash isn't minted yet; the launcher shortcuts wait on the same hash. Built on tap,
    // so a video alias opens whichever screen the video memo is set to, whatever its saved label says.
    private static Intent createTarget(String alias) {
        if (SharedConfig.directShareHash == null) {
            return null;
        }
        switch (alias.substring(ALIAS_PREFIX.length())) {
            case "VideoFront":
                return VideoNoteShortcut.isEnabled() ? VideoNoteShortcut.createIntent(false) : null;
            case "VideoRear":
                return VideoNoteShortcut.isEnabled() ? VideoNoteShortcut.createIntent(true) : null;
            case "TextFull":
                return TextMemoShortcut.isEnabled() ? TextMemoShortcut.createIntent(false) : null;
            case "TextCard":
                return TextMemoShortcut.isEnabled() ? TextMemoShortcut.createIntent(true) : null;
            default:
                return null;
        }
    }

    private static int labelOf(String alias) {
        switch (alias.substring(ALIAS_PREFIX.length())) {
            case "VideoFront":
                return R.string.VideoNoteShortcutLabelFrontLong;
            case "VideoRear":
                return R.string.VideoNoteShortcutLabelRearLong;
            case "TextCard":
                return R.string.TextMemoShortcutLabelCard;
            default:
                return R.string.TextMemoShortcutLabelFull;
        }
    }

    private static int iconOf(String alias) {
        return alias.substring(ALIAS_PREFIX.length()).startsWith("Video") ? R.drawable.shortcut_memo_video : R.drawable.shortcut_memo_text;
    }
}
