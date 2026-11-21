package com.dazewell.gram.videonote;

import android.view.View;

import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;

/**
 * The composer's record controls column on the right: pause at slot 0, the view-once "(1)" at 1, and the round video
 * recorder's own buttons above them, the flip at 2 and the compact toggle at 3. Pause and "(1)" are drawn by
 * ChatActivityEnterView.ControlsView, while the recorder's buttons are views in another parent. So ControlsView reports
 * where it drew the stack and how far it is scaled every time it draws, and this puts the recorder's buttons on their
 * slots at that same scale. They come in on lock, drop with a slide to cancel and shrink away on send, as pause and
 * "(1)" do, and the slots stay put whether or not view-once is available.
 *
 * One per ControlsView, so per chat. The geometry is kept in ControlsView's own coordinates and mapped through both
 * views' window positions on every placement, since a keyboard or a reply strip can move ControlsView without it
 * redrawing; the recorder calls place() from its own layout passes for that. UI thread only.
 */
public final class RecordControlsColumn {

    public static final int FLIP = 2;
    public static final int COMPACT_TOGGLE = 3;
    private static final int SLOTS = 4;
    // a member scaled below this is not there for a touch; the flip's press scale is 0.9
    private static final float SHOWN = 0.01f;
    private static final float EPSILON = 0.01f;

    private final View column;
    private final View[] members = new View[SLOTS];
    // what was last written to each member, so a frame that moved nothing writes nothing
    private final float[] placedX = new float[SLOTS], placedY = new float[SLOTS], placedScale = new float[SLOTS];
    private final int[] at = new int[2];

    // false until ControlsView draws while shown, so a new recording doesn't reuse the last one's final frame
    private boolean drawn;
    private float cx, pauseCenterY, step, scale;

    public RecordControlsColumn(View column) {
        this.column = column;
    }

    // The chat composer's column, or null where there is none (a story reply, or before its controls exist).
    public static RecordControlsColumn of(Object delegate) {
        if (!(delegate instanceof ChatActivity)) {
            return null;
        }
        final ChatActivityEnterView enter = ((ChatActivity) delegate).getChatActivityEnterView();
        return enter != null && enter.controlsView != null ? enter.controlsView.recordColumn : null;
    }

    /**
     * The recorder puts its buttons in delegate's column, or returns false when there is none and they stay its own.
     * toggle may be null. Called whenever the recorder lays itself out.
     */
    public static boolean place(Object delegate, View flip, View toggle) {
        final RecordControlsColumn column = of(delegate);
        if (column == null) {
            return false;
        }
        column.join(flip, FLIP);
        if (toggle != null) {
            column.join(toggle, COMPACT_TOGGLE);
        }
        column.relayout();
        return true;
    }

    public static boolean shown(View member) {
        return member.getScaleX() >= SHOWN;
    }

    /**
     * ControlsView, each time it draws the stack. cx is the column's centre line, pauseTop the top of pause's slot
     * (the one "(1)" stacks on), chipSize a chip's height, gap the space between two, scale the stack's own.
     */
    public void update(float cx, float pauseTop, float chipSize, float gap, float scale) {
        this.cx = cx;
        this.pauseCenterY = pauseTop + chipSize / 2f;
        this.step = chipSize + gap;
        this.scale = scale;
        drawn = true;
        relayout();
    }

    // A recorder rebuilt for the same chat takes the slot over from the old one's button.
    private void join(View member, int slot) {
        if (members[slot] == member) {
            return;
        }
        members[slot] = member;
        placedX[slot] = placedY[slot] = Float.NaN;
        // hidden until the column draws, as pause and "(1)" are before a lock
        placedScale[slot] = 0f;
        member.setScaleX(0f);
        member.setScaleY(0f);
    }

    private void relayout() {
        if (!column.isShown()) {
            // between recordings ControlsView is gone without a last frame; the next one starts hidden
            drawn = false;
            for (int slot = 0; slot < SLOTS; slot++) {
                if (members[slot] != null) {
                    setScale(slot, members[slot], 0f);
                }
            }
            return;
        }
        if (!drawn) {
            return;
        }
        column.getLocationInWindow(at);
        final float columnX = at[0] + cx, columnY = at[1] + pauseCenterY;
        for (int slot = 0; slot < SLOTS; slot++) {
            final View member = members[slot];
            if (member == null || member.getWidth() == 0 || !member.isAttachedToWindow() || !(member.getParent() instanceof View)) {
                continue;
            }
            final View parent = (View) member.getParent();
            parent.getLocationInWindow(at);
            final float w = member.getWidth(), h = member.getHeight();
            // the slot's centre in the parent, kept inside it: a high slot can leave it with the keyboard up
            final float centerX = Math.max(w / 2f, Math.min(columnX - at[0], parent.getWidth() - w / 2f));
            final float centerY = Math.max(h / 2f, Math.min(columnY - slot * step - at[1], parent.getHeight() - h / 2f));
            final float x = centerX - (member.getLeft() + w / 2f);
            final float y = centerY - (member.getTop() + h / 2f);
            if (!(Math.abs(x - placedX[slot]) <= EPSILON)) {
                placedX[slot] = x;
                member.setTranslationX(x);
            }
            if (!(Math.abs(y - placedY[slot]) <= EPSILON)) {
                placedY[slot] = y;
                member.setTranslationY(y);
            }
            setScale(slot, member, scale);
        }
    }

    // Written only when the column's scale changes, so a settled button's own press animation keeps its scale. A
    // release animation can still land a hidden button back on 1 after the last write; hiding wins over that.
    private void setScale(int slot, View member, float s) {
        if (Math.abs(s - placedScale[slot]) <= EPSILON / 10f && !(s < SHOWN && member.getScaleX() >= SHOWN)) {
            return;
        }
        placedScale[slot] = s;
        member.setScaleX(s);
        member.setScaleY(s);
    }
}
