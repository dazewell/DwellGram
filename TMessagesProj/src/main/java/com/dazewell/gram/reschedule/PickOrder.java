package com.dazewell.gram.reschedule;

import android.util.SparseArray;

import org.telegram.messenger.MessageObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * The order scheduled messages were picked in, for the numbered selection checkboxes,
 * bulk Reschedule and Send Now. One instance per ChatActivity, so it is per account and
 * per chat by construction. Main thread only.
 *
 * <p>Advisory, never in lockstep with ChatActivity's selection: the one put/remove point
 * in addToSelectedMessages reports here (tap, drag, range, album and delete all funnel
 * through it), the many {@code clear()} sites don't. Reads skip
 * ids that are no longer selected and every re-add moves the id to the end, so a stale
 * entry can't misorder anything. Anything selected but never reported ranks last.
 *
 * <p>An album is one unit: it takes the rank of its earliest-picked selected member,
 * and its unit is resolved from the MessageObject itself (never from selection
 * membership), because Send Now and Reschedule both expand an album to members that
 * may not be selected.
 */
public final class PickOrder {

    /** Rank of anything selected but never reported, so it sorts after every picked unit. */
    public static final int UNRANKED = Integer.MAX_VALUE;

    private final ArrayList<Integer> ids = new ArrayList<>();

    public void onSelected(int id, int selectedCount) {
        // the first pick of a new selection: drop whatever earlier, cleared selections left behind
        if (selectedCount <= 1) {
            ids.clear();
        }
        ids.remove(Integer.valueOf(id));
        ids.add(id);
    }

    public void onDeselected(int id) {
        ids.remove(Integer.valueOf(id));
    }

    /** Select Range means "everything between, in order", so the whole selection is renumbered by time. */
    public void resetByTime(SparseArray<MessageObject> selected) {
        ArrayList<MessageObject> sorted = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            MessageObject m = selected.valueAt(i);
            if (m != null) sorted.add(m);
        }
        Collections.sort(sorted, (a, b) -> a.messageOwner.date != b.messageOwner.date
                ? Integer.compare(a.messageOwner.date, b.messageOwner.date)
                : Integer.compare(a.getId(), b.getId()));
        ids.clear();
        for (int i = 0; i < sorted.size(); i++) ids.add(sorted.get(i).getId());
    }

    /** 1-based ranks of the selected units, frozen at the moment they're taken. */
    public Ranks ranks(SparseArray<MessageObject> selected) {
        Ranks ranks = new Ranks();
        int next = 1;
        for (int i = 0; i < ids.size(); i++) {
            MessageObject m = selected.get(ids.get(i));
            if (m == null) continue;
            if (ranks.put(m, next)) next++;
        }
        return ranks;
    }

    /** The number to draw on a selected message's checkbox, or -1 for a plain tick. */
    public int numberOf(MessageObject message, SparseArray<MessageObject> selected) {
        int rank = ranks(selected).rankOf(message);
        return rank == UNRANKED ? -1 : rank;
    }

    /** Stable-sorts message ids by pick rank, so album members stay together and unranked ids keep their order at the end. */
    public void sortIds(ArrayList<Integer> messageIds, SparseArray<MessageObject> selected, SparseArray<MessageObject> messagesDict) {
        final Ranks ranks = ranks(selected);
        final HashMap<Integer, Integer> byId = new HashMap<>();
        for (int i = 0; i < messageIds.size(); i++) {
            int id = messageIds.get(i);
            MessageObject m = messagesDict.get(id);
            byId.put(id, m != null ? ranks.rankOf(m) : UNRANKED);
        }
        Collections.sort(messageIds, (a, b) -> Integer.compare(byId.get(a), byId.get(b)));
    }

    public static final class Ranks {
        private final HashMap<Long, Integer> groups = new HashMap<>();
        private final HashMap<Integer, Integer> singles = new HashMap<>();

        // keeps the first rank a unit gets; true when this put started a new unit
        boolean put(MessageObject m, int rank) {
            long gid = m.getGroupId();
            if (gid != 0) {
                if (groups.containsKey(gid)) return false;
                groups.put(gid, rank);
            } else {
                if (singles.containsKey(m.getId())) return false;
                singles.put(m.getId(), rank);
            }
            return true;
        }

        public int rankOf(MessageObject m) {
            Integer rank;
            long gid = m.getGroupId();
            rank = gid != 0 ? groups.get(gid) : singles.get(m.getId());
            return rank != null ? rank : UNRANKED;
        }

        /** Stable copy of {@code timeSorted} ordered by pick rank; unranked items keep time order at the end. */
        public <T extends MessageObject> ArrayList<T> sort(List<T> timeSorted) {
            ArrayList<T> out = new ArrayList<>(timeSorted);
            Collections.sort(out, (a, b) -> Integer.compare(rankOf(a), rankOf(b)));
            return out;
        }
    }
}
