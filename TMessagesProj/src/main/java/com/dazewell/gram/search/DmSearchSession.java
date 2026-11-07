package com.dazewell.gram.search;

/** UI-thread state owned by one account's MediaDataController. */
public final class DmSearchSession {
    private int guid;
    private long dialogId;
    private long generation;
    private DmSearchCriteria criteria = DmSearchCriteria.EMPTY;
    private Runnable emptyQueryRejected;
    private Runnable requestFailed;

    public void configure(int guid, long dialogId, DmSearchCriteria criteria, Runnable rejected, Runnable failed) {
        this.guid = guid;
        this.dialogId = dialogId;
        this.criteria = criteria == null ? DmSearchCriteria.EMPTY : criteria;
        emptyQueryRejected = rejected;
        requestFailed = failed;
        generation++;
    }

    public Snapshot peek(int guid, long dialogId) {
        return owns(guid, dialogId) ? new Snapshot(generation, criteria, emptyQueryRejected, requestFailed) : null;
    }

    public Snapshot begin(int guid, long dialogId) {
        if (!owns(guid, dialogId)) {
            configure(guid, dialogId, DmSearchCriteria.EMPTY, null, null);
        }
        return new Snapshot(++generation, criteria, emptyQueryRejected, requestFailed);
    }

    public boolean owns(int guid, long dialogId) {
        return this.guid == guid && this.dialogId == dialogId;
    }

    public boolean isCurrent(Snapshot snapshot) {
        return snapshot.generation == generation;
    }

    public void close() {
        configure(0, 0, DmSearchCriteria.EMPTY, null, null);
    }

    public static final class Snapshot {
        private final long generation;
        public final DmSearchCriteria criteria;
        public final Runnable emptyQueryRejected;
        public final Runnable requestFailed;

        private Snapshot(long generation, DmSearchCriteria criteria, Runnable emptyQueryRejected, Runnable requestFailed) {
            this.generation = generation;
            this.criteria = criteria;
            this.emptyQueryRejected = emptyQueryRejected;
            this.requestFailed = requestFailed;
        }
    }
}
