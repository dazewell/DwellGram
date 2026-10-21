package com.dazewell.gram.bulletinstack;

import android.view.View;
import android.view.ViewParent;

import org.telegram.ui.Components.Bulletin;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

// #bulletin-stacking. Telegram shows one bulletin at a time: a new one hides whatever is up. A bulletin marked
// with keep() is left in place instead, and a newer bottom bulletin in the same container sits right above it.
// Upstream bulletins aren't marked, so their replace-each-other behaviour (and an undo bar committing when it is
// replaced) is unchanged.
//
// The stack is two high at most. A third bulletin can't stack on one that already has a bulletin under it, so it
// replaces that upper one and lands on the kept bar - nothing ever has to be spliced out of the middle.
// Only bulletins in the same container stack: global() can hand back a sheet's container or a fresh window,
// and lifting a bar above one in another window would leave it floating over nothing.
//
// Links live until the lower or upper bar's exit animation ends (detach), so the upper keeps following the lower
// while it slides out, and a hide listener can still ask what was stacked on its bar. The links are weak both
// ways: a strong value pointing at another key would pin the pair for good if an exit never finished. UI thread only.
public final class BulletinStack {

    private static final Set<Bulletin> kept = Collections.newSetFromMap(new WeakHashMap<>());
    private static final WeakHashMap<Bulletin, WeakReference<Bulletin>> below = new WeakHashMap<>();
    private static final WeakHashMap<Bulletin, WeakReference<Bulletin>> above = new WeakHashMap<>();

    private BulletinStack() {
    }

    /** Lets a newer bottom bulletin in the same container stack above this one rather than hide it. */
    public static Bulletin keep(Bulletin bulletin) {
        if (bulletin != null) {
            kept.add(bulletin);
        }
        return bulletin;
    }

    public static Bulletin below(Bulletin bulletin) {
        return get(below, bulletin);
    }

    public static Bulletin above(Bulletin bulletin) {
        return get(above, bulletin);
    }

    private static Bulletin get(WeakHashMap<Bulletin, WeakReference<Bulletin>> links, Bulletin bulletin) {
        WeakReference<Bulletin> link = bulletin != null ? links.get(bulletin) : null;
        return link != null ? link.get() : null;
    }

    // Called from Bulletin.show in place of hiding the visible bulletin: hides until what is left can be stacked
    // on, or nothing is left. Show then takes the visible slot as before.
    public static void makeRoom(Bulletin incoming, boolean top, View container) {
        // a hide listener may put another bulletin up synchronously, so bound the loop rather than trust it to end
        for (int i = 0; i < 4; i++) {
            Bulletin visible = Bulletin.getVisibleBulletin();
            if (visible == null || visible == incoming) {
                return;
            }
            if (!top && canStackOn(visible, container)) {
                below.put(incoming, new WeakReference<>(visible));
                above.put(visible, new WeakReference<>(incoming));
                return;
            }
            visible.hide();
            if (Bulletin.getVisibleBulletin() == visible) {
                return;
            }
        }
    }

    private static boolean canStackOn(Bulletin visible, View container) {
        if (!kept.contains(visible) || below(visible) != null || above(visible) != null || !visible.isShowing()) {
            return false;
        }
        Bulletin.Layout layout = visible.getLayout();
        if (layout == null || layout.top) {
            return false;
        }
        ViewParent parentLayout = layout.getParent();
        return parentLayout != null && parentLayout.getParent() == container;
    }

    // Called as a bulletin starts hiding: the bar under it stops carrying it, and comes back as the visible one
    // if it is still up. The hiding bulletin keeps its own link, so it slides out from where it is.
    public static Bulletin release(Bulletin hiding) {
        Bulletin under = below(hiding);
        if (under == null) {
            return null;
        }
        above.remove(under);
        return under.isShowing() ? under : null;
    }

    // Called once a bulletin's exit has finished: whatever sat on it drops into the space it leaves.
    public static void detach(Bulletin gone) {
        below.remove(gone);
        kept.remove(gone);
        Bulletin upper = above(gone);
        above.remove(gone);
        if (upper != null) {
            below.remove(upper);
            if (upper.getLayout() != null) {
                upper.getLayout().updatePosition();
            }
        }
    }

    /** How far a stacked bulletin must rise above its own placement to clear the bar under it. */
    public static float lift(Bulletin bulletin, View layout, float translation) {
        Bulletin under = below(bulletin);
        View underLayout = under != null ? under.getLayout() : null;
        if (underLayout == null || layout.getHeight() <= 0 || underLayout.getHeight() <= 0) {
            return 0;
        }
        return Math.max(0, layout.getBottom() + translation - (underLayout.getTop() + underLayout.getTranslationY()));
    }

    // After a bar moves, the one stacked on it moves with it. Upward only, so it can't recurse.
    public static void follow(Bulletin bulletin) {
        Bulletin upper = above(bulletin);
        if (upper != null && upper.getLayout() != null) {
            upper.getLayout().updatePosition();
        }
    }

    // A screen repositions only its visible bulletin (a chat does it by hand on every composer change), which is
    // the upper one when two are stacked. Moving the lower one instead carries both. True when it took over.
    public static boolean updateBase(Bulletin bulletin) {
        Bulletin under = below(bulletin);
        if (under == null || under.getLayout() == null) {
            return false;
        }
        under.getLayout().updatePosition();
        return true;
    }
}
