package com.dazewell.gram.helpers;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** View-local vertical shortcut to the composer's existing long-click action. */
public final class SendMenuGesture {
    public interface TouchHandler {
        boolean onTouchEvent(MotionEvent event);
    }

    private boolean tracking;
    private boolean consumed;
    private float downX;
    private float downY;

    public void onLongClick() {
        tracking = false;
    }

    public boolean onTouchEvent(View view, MotionEvent event, boolean enabled, TouchHandler original) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            consumed = false;
            boolean accepted = original.onTouchEvent(event);
            tracking = accepted && enabled;
            downX = event.getX();
            downY = event.getY();
            return accepted;
        }
        if (consumed) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) consumed = false;
            return true;
        }
        if (tracking && (action == MotionEvent.ACTION_POINTER_DOWN || !enabled)) {
            cancel(event, original);
            return true;
        }
        if (tracking && action == MotionEvent.ACTION_MOVE) {
            float dx = Math.abs(event.getX() - downX);
            float dy = Math.abs(event.getY() - downY);
            int threshold = 2 * ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
            if (dy > threshold && dy > dx) {
                // Cancel before opening: releasing this drag must never reach the send click.
                cancel(event, original);
                android.util.Log.i("NagramX", "NAX_SMOKE_remember-send-action PATH vertical-drag");
                view.performLongClick();
                return true;
            }
            if (dx > threshold && dx >= dy) tracking = false;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) tracking = false;
        return original.onTouchEvent(event);
    }

    private void cancel(MotionEvent event, TouchHandler original) {
        tracking = false;
        consumed = event.getActionMasked() != MotionEvent.ACTION_UP
                && event.getActionMasked() != MotionEvent.ACTION_CANCEL;
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        original.onTouchEvent(cancel);
        cancel.recycle();
    }
}
