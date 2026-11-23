package com.dazewell.gram.videonote;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.InstantCameraView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The composer's record controls column on the right: pause, the view-once "(1)" above it where the chat offers
 * view-once, and the round video recorder's own buttons above those, the flip, the compact toggle and Spoiler.
 *
 * Pause and "(1)" are drawn by ChatActivityEnterView.ControlsView. The recorder's buttons are views, so they move out
 * of the recorder into a host this column keeps directly above ControlsView, and the host takes ControlsView's alpha
 * and visibility. All four then come in, move and leave together: on lock, with a slide to cancel, on send and on a
 * cancel, where the recorder itself is already gone while the composer is still animating its stack out.
 * ControlsView reports where it drew the stack, how many chips it drew and how far it is scaled every time it draws,
 * and the buttons take the slots above at that scale.
 *
 * The column is packed: each button takes the next slot above whatever is in the column below it, so a missing one
 * leaves no gap. ControlsView counts its own chips; a recorder button is in the column while it is VISIBLE, and a GONE
 * or INVISIBLE one gives its slot up. A camera-only button (the flip) fades while the composer shows a recorded
 * preview but keeps its slot, which the preview's sound chip takes, so the toggle above doesn't move.
 *
 * Buttons show only while the stack is up for a round video: armed when the stack draws or the recorder lays out
 * with the recorder showing, its file not yet handed to a send, and the stack not faded out, and disarmed when the
 * stack fades out, hides or leaves the window. A voice recording on the same stack has no live recorder, so it
 * doesn't get them. Upstream sometimes only
 * fades the stack to alpha 0 and leaves it VISIBLE (after a preview is sent or deleted), so the fade is the signal,
 * not GONE. One per ControlsView, so per chat. UI thread only.
 */
public final class RecordControlsColumn {

    // the recorder's buttons, in their order up the column
    private static final int FLIP = 0;
    private static final int COMPACT_TOGGLE = 1;
    private static final int SPOILER = 2; // RoundSpoiler's, GONE where the chat doesn't offer it
    private static final int SLOTS = 3;
    // a member scaled below this is not there for a touch; the flip's press scale is 0.9
    private static final float SHOWN = 0.01f;
    private static final float EPSILON = 0.01f;

    private final View column;
    private final Host host;
    private final View[] members = new View[SLOTS];
    private final boolean[] cameraOnly = new boolean[SLOTS];
    // what was last written to each member, so a frame that moved nothing writes nothing
    private final float[] placedX = new float[SLOTS], placedY = new float[SLOTS], placedScale = new float[SLOTS];
    // the preview's fade, as the sound chip's own (VideoPreviewSoundChip)
    private final AnimatedFloat previewing;
    private final ViewTreeObserver.OnPreDrawListener onPreDraw = () -> {
        relayout();
        return true;
    };
    private ViewTreeObserver observer;
    // the round video recorder whose buttons these are, for whether it is showing
    private View recorder;
    // the recorder's host, for the Spoiler button's per-frame refresh
    private Object delegate;

    // the stack is up for a round video, so the buttons show; see the class comment for when
    private boolean armed;
    // false until ControlsView draws for this recording, so a new one doesn't reuse the last one's final frame
    private boolean drawn;
    private float cx, pauseCenterY, step, scale, preview;
    private int drawnChips = 1;

    public RecordControlsColumn(View column) {
        this.column = column;
        previewing = new AnimatedFloat(column, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        host = new Host(column);
        host.setVisibility(View.GONE); // ControlsView starts GONE too
        host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                disarm(); // the chat is closing or rebuilding; its window's observer mustn't keep this
            }
        });
    }

    // ControlsView, once it is in its parent: the host goes right above it. Never from a layout pass.
    public void attachHost() {
        if (!(column.getParent() instanceof ViewGroup) || host.getParent() == column.getParent()) {
            return;
        }
        AndroidUtilities.removeFromParent(host);
        final ViewGroup parent = (ViewGroup) column.getParent();
        parent.addView(host, parent.indexOfChild(column) + 1, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        host.setVisibility(column.getVisibility());
        host.setAlpha(column.getAlpha());
    }

    // ControlsView's own alpha, which the composer fades the stack out with
    public void onColumnAlpha(float alpha) {
        host.setAlpha(alpha);
        if (alpha < SHOWN) {
            disarm(); // faded out: every teardown of the stack ends here, GONE or not
        }
    }

    public void onColumnVisibility(int visibility) {
        host.setVisibility(visibility);
        if (visibility != View.VISIBLE) {
            disarm();
        }
    }

    // The recording is over for the stack: the next one, or a voice recording, starts without the buttons.
    private void disarm() {
        if (!armed && observer == null) {
            return;
        }
        armed = false;
        drawn = false;
        stopFollowing();
        relayout();
    }

    // A recorder whose file went to a send stays showing until the send transition or its fallback closes it, up to
    // seconds, and a voice recording can start on the stack meanwhile. It never arms the column again; one that is
    // already armed keeps it for the stack's exit.
    private void maybeArm() {
        if (!armed && recorder != null && recorder.isShown()
                && !(recorder instanceof InstantCameraView && ((InstantCameraView) recorder).isFileHandedOff())
                && column.getVisibility() == View.VISIBLE && column.getAlpha() >= SHOWN && host.isAttachedToWindow()) {
            armed = true;
            startFollowing();
        }
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
     * toggle may be null. Called whenever the recorder lays itself out; recorder is the view they come from, which
     * has to be showing for them to.
     */
    public static boolean place(Object delegate, View flip, View toggle, View recorder) {
        final RecordControlsColumn column = of(delegate);
        if (column == null || column.host.getParent() == null) {
            return false;
        }
        column.join(flip, FLIP, true);
        if (toggle != null) {
            column.join(toggle, COMPACT_TOGGLE, false);
        }
        if (recorder instanceof InstantCameraView) {
            column.join(((InstantCameraView) recorder).spoiler.button(delegate), SPOILER, false); // live and in the preview
        }
        column.delegate = delegate;
        column.recorder = recorder;
        column.maybeArm();
        column.relayout();
        return true;
    }

    public static boolean shown(View member) {
        return member.getScaleX() >= SHOWN;
    }

    // True once view has moved into a column's host, which then owns its position, scale and alpha.
    public static boolean owns(View member) {
        return member.getParent() instanceof Host;
    }

    /**
     * ControlsView, each time it draws the stack. cx is the column's centre line, pauseTop the top of pause's slot
     * (the one "(1)" stacks on), chipSize a chip's height, gap the space between two, scale the stack's own,
     * drawnChips how many slots from pause up it fills itself (pause, and "(1)" where it is offered), and preview
     * whether it is showing a recorded preview rather than the live camera.
     */
    public void update(float cx, float pauseTop, float chipSize, float gap, float scale, int drawnChips, boolean preview) {
        this.cx = cx;
        this.pauseCenterY = pauseTop + chipSize / 2f;
        this.step = chipSize + gap;
        this.scale = scale;
        this.drawnChips = Math.max(1, drawnChips);
        this.preview = previewing.set(preview); // invalidates ControlsView while it fades, so this keeps coming
        drawn = true;
        maybeArm();
        relayout();
    }

    // Moves member out of the recorder into the host. A recorder rebuilt for the same chat takes the slot over.
    private void join(View member, int slot, boolean camera) {
        if (members[slot] == member && member.getParent() == host) {
            return;
        }
        if (members[slot] != null && members[slot] != member) {
            AndroidUtilities.removeFromParent(members[slot]);
        }
        members[slot] = member;
        cameraOnly[slot] = camera;
        final ViewGroup.LayoutParams lp = member.getLayoutParams();
        final int w = lp != null ? lp.width : ViewGroup.LayoutParams.WRAP_CONTENT;
        final int h = lp != null ? lp.height : ViewGroup.LayoutParams.WRAP_CONTENT;
        AndroidUtilities.removeFromParent(member);
        host.addView(member, new FrameLayout.LayoutParams(w, h, Gravity.LEFT | Gravity.TOP));
        placedX[slot] = placedY[slot] = Float.NaN;
        // the column alone shows it from here: none of the recorder's fades, and hidden until the stack draws
        member.setAlpha(1f);
        placedScale[slot] = 0f;
        member.setScaleX(0f);
        member.setScaleY(0f);
    }

    // ControlsView can be moved without redrawing (layout, insets, a translation), so the buttons follow it every
    // frame while a recording has them, not only when it draws
    private void startFollowing() {
        stopFollowing();
        observer = host.getViewTreeObserver();
        observer.addOnPreDrawListener(onPreDraw);
    }

    private void stopFollowing() {
        if (observer != null) {
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(onPreDraw);
            }
            observer = null;
        }
    }

    private void relayout() {
        if (!armed || !drawn) {
            for (int slot = 0; slot < SLOTS; slot++) {
                if (members[slot] != null && members[slot].getParent() == host) {
                    setScale(slot, members[slot], 0f);
                }
            }
            return;
        }
        if (recorder instanceof InstantCameraView) {
            ((InstantCameraView) recorder).spoiler.refresh(delegate); // view-once or Infinite turned on clears it
        }
        // ControlsView and the host share a parent; both are read fresh, translations included
        final float columnX = column.getX() - host.getX() + cx, columnY = column.getY() - host.getY() + pauseCenterY;
        int next = drawnChips; // the lowest free slot, counted up from pause
        for (int slot = 0; slot < SLOTS; slot++) {
            final View member = members[slot];
            if (member == null || member.getParent() != host || member.getVisibility() != View.VISIBLE) {
                continue; // out of the column, so the buttons above close up
            }
            final int position = next++;
            if (member.getWidth() == 0) {
                continue;
            }
            final float w = member.getWidth(), h = member.getHeight();
            // the slot's centre in the host, kept inside it: a high slot can leave it with the keyboard up
            final float centerX = Math.max(w / 2f, Math.min(columnX, host.getWidth() - w / 2f));
            final float centerY = Math.max(h / 2f, Math.min(columnY - position * step, host.getHeight() - h / 2f));
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
            setScale(slot, member, cameraOnly[slot] ? scale * (1f - preview) : scale);
        }
    }

    private static final class Host extends FrameLayout {
        Host(View column) {
            super(column.getContext());
            setClipChildren(false);
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false; // a fade with the stack mustn't allocate a full-screen layer
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
