package com.dazewell.gram.diagnostics;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.UserConfig;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Consumer;

// Temporary observations: no message, dialog, media or draft contents enter this trace.
public final class ScheduledCalendarDiagnostics {
    private static final String MARKER = "NAX_SMOKE_scheduled_calendar";
    private static final String TAG = "NaxScheduledCalendar";
    private static final AtomicInteger NEXT_TOKEN = new AtomicInteger();
    private static final AtomicIntegerArray MILESTONES = new AtomicIntegerArray(UserConfig.MAX_ACCOUNT_COUNT);
    private final int token = NEXT_TOKEN.incrementAndGet();
    public final int account;
    private final HashMap<String, String> previous = new HashMap<>();
    private int count = -1;
    private int lines;
    private int stacks;
    private boolean ended;

    public ScheduledCalendarDiagnostics(int account) {
        this.account = account;
        write("BEGIN scenario=scheduled-calendar build=" + BuildConfig.BUILD_VERSION_STRING
                + " package=" + BuildConfig.APPLICATION_ID);
    }

    public void capture(String path, int newCount, String state, View button, String toolbar) {
        if (ended) return;
        if (newCount >= -1) count = newCount;
        // Geometry is read only on discrete paths, never from layout/draw or every animation frame.
        String snapshot = "count=" + count + " " + state + " button=" + geometry(button) + " " + toolbar;
        ViewParent parent = button == null ? null : button.getParent();
        for (int depth = 0; depth < 5 && parent instanceof View; depth++) {
            View ancestor = (View) parent;
            snapshot += " parent" + depth + "=" + geometry(ancestor);
            parent = ancestor.getParent();
        }
        if (snapshot.equals(previous.put(path, snapshot))) return;
        if (lines >= 400) return;
        write("PATH=" + path + " " + snapshot);
        if (++lines == 400) write("LIMIT snapshots=400; later absent paths are inconclusive");
        if (path.startsWith("WRITER") && button != null && button.getTag() != null
                && (button.getVisibility() != View.VISIBLE || button.getAlpha() == 0f
                || button.getScaleX() == 0f || button.getScaleY() == 0f) && stacks < 8) {
            Log.i(TAG, prefix() + "COMPETING tagged-hidden-writer", new Throwable());
            if (++stacks == 8) write("LIMIT stacks=8; later writers have no call stack");
        }
    }

    public boolean isEnded() {
        return ended;
    }

    public void end() {
        if (ended) return;
        write("END composer-destroyed snapshots=" + lines);
        ended = true;
        previous.clear();
    }

    // These are account-scoped collateral, not evidence of ordering for a particular chat.
    public static void milestone(int account, String path, int count, boolean scheduled) {
        int index = MILESTONES.incrementAndGet(account);
        if (index <= 80) {
            Log.i(TAG, MARKER + " account=" + account + " COLLATERAL=" + path
                    + " count=" + count + " scheduled=" + scheduled);
        }
        if (index == 80) Log.i(TAG, MARKER + " account=" + account + " LIMIT collateral=80");
    }

    private String prefix() {
        return MARKER + " account=" + account + " composer=" + token + " ";
    }

    private void write(String message) {
        Log.i(TAG, prefix() + message);
    }

    private static String geometry(View view) {
        if (view == null) return "absent";
        return "{v=" + view.getVisibility() + ",tag=" + (view.getTag() != null)
                + ",a=" + view.getAlpha() + ",sx=" + view.getScaleX() + ",sy=" + view.getScaleY()
                + ",measured=" + view.getMeasuredWidth() + "x" + view.getMeasuredHeight()
                + ",box=" + view.getLeft() + "," + view.getTop() + "," + view.getRight() + "," + view.getBottom()
                + ",tx=" + view.getTranslationX() + ",ty=" + view.getTranslationY()
                + ",attached=" + view.isAttachedToWindow() + ",shown=" + view.isShown()
                + ",layoutRequested=" + view.isLayoutRequested() + "}";
    }

    public static class Button extends ImageView {
        private final Consumer<String> observer;

        public Button(Context context, Consumer<String> observer) {
            super(context);
            this.observer = observer;
        }

        private void changed(String path) {
            // View's constructor can dispatch setters before the observer has been assigned.
            if (observer != null) observer.accept(path);
        }

        @Override
        public void setVisibility(int visibility) {
            int before = getVisibility();
            super.setVisibility(visibility);
            if (before != visibility) changed("WRITER-visibility");
        }

        @Override
        public void setTag(Object tag) {
            boolean before = getTag() != null;
            super.setTag(tag);
            if (before != (tag != null)) changed("WRITER-tag");
        }

        private static int band(float value) {
            return value <= 0f ? 0 : value < 0.5f ? 1 : value < 1f ? 2 : 3;
        }

        @Override
        public void setAlpha(float alpha) {
            int before = band(getAlpha());
            super.setAlpha(alpha);
            if (before != band(alpha)) changed("WRITER-alpha-band");
        }

        @Override
        public void setScaleX(float scale) {
            int before = band(getScaleX());
            super.setScaleX(scale);
            if (before != band(scale)) changed("WRITER-scaleX-band");
        }

        @Override
        public void setScaleY(float scale) {
            int before = band(getScaleY());
            super.setScaleY(scale);
            if (before != band(scale)) changed("WRITER-scaleY-band");
        }
    }
}
