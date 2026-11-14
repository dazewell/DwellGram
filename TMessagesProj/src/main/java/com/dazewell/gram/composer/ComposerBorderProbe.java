package com.dazewell.gram.composer;

import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import org.telegram.messenger.BuildConfig;
import org.telegram.ui.Components.ChatActivityEnterView;

/** Temporary: where the composer text is clipped against where the field is painted. Numbers only. */
final class ComposerBorderProbe {
    private static final String TAG = "NAX_SMOKE_composer-text-border-fix";
    private static boolean begun;
    private static boolean logged;
    private static long last;
    private static final int[] a = new int[2];
    private static final int[] b = new int[2];
    private static final RectF field = new RectF();

    static void log(View host, ChatActivityEnterView enterView, Path inputFieldPath, android.graphics.Rect pill, float pad) {
        if (host == null || enterView == null || enterView.getEditField() == null) return;
        final View edit = enterView.getEditField();
        if (!begun) {
            begun = true;
            Log.i(TAG, "NAX_SMOKE_composer-text-border-fix BEGIN build=" + BuildConfig.BUILD_VERSION_STRING + " app=" + BuildConfig.APPLICATION_ID);
        }
        final int lines = enterView.getEditField().getLineCount();
        if (lines <= 1) {
            if (logged) {
                logged = false;
                Log.i(TAG, "NAX_SMOKE_composer-text-border-fix END");
            }
            return;
        }
        if (lines < 4) return;
        final long now = SystemClock.elapsedRealtime();
        if (now - last < 1500) return;
        last = now;
        logged = true;
        host.getLocationInWindow(a);
        final int hostTop = a[1];
        inputFieldPath.computeBounds(field, true);
        edit.getLocationInWindow(b);
        final View row = (View) edit.getParent();
        final int[] r = new int[2];
        row.getLocationInWindow(r);
        enterView.getLocationInWindow(a);
        Log.i(TAG, "NAX_SMOKE_composer-text-border-fix GEOM"
                + " lines=" + lines
                + " islandTop=" + (int) (pill.top - pad + hostTop)
                + " fieldTop=" + (int) (field.top + hostTop)
                + " fieldBottom=" + (int) (field.bottom + hostTop)
                + " pillTop=" + (pill.top + hostTop)
                + " pillBottom=" + (pill.bottom + hostTop)
                + " enterTop=" + a[1] + " enterH=" + enterView.getHeight() + " enterTy=" + enterView.getTranslationY()
                + " rowTop=" + r[1] + " rowH=" + row.getHeight()
                + " editTop=" + b[1] + " editH=" + edit.getHeight() + " editTy=" + edit.getTranslationY()
                + " padT=" + edit.getPaddingTop() + " padB=" + edit.getPaddingBottom()
                + " scrollY=" + edit.getScrollY()
                + " canUp=" + edit.canScrollVertically(-1) + " canDown=" + edit.canScrollVertically(1));
    }

    private ComposerBorderProbe() {
    }
}
