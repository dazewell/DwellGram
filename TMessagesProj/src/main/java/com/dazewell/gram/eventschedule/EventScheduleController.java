package com.dazewell.gram.eventschedule;

import static org.telegram.messenger.LocaleController.getString;

import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.radolyn.ayugram.utils.AyuState;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import tw.nekomimi.nekogram.utils.AlertUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runtime engine for event-triggered scheduled messages.
 *
 * <p>Everything hangs off the single new-message funnel ({@code onNewMessages}, called
 * from {@code MessagesController.updateInterfaceWithMessages}). A trigger arm is persisted
 * immediately (even before server ids are known), and later bound to server ids from the
 * scheduled-send ack metadata delivered through {@link NotificationCenter#messageReceivedByServer}.
 * Incoming messages are matched against bound arms and, on a hit, fire
 * {@code messages.sendScheduledMessages} early.
 *
 * <p>Queue state is intentionally process-local: runtime {@link EventScheduleEntry#state} and the
 * per-run retry budget are never persisted. After restart, entries reload as armed from
 * {@link EventScheduleStore}; each successful early send removes its row, so only not-yet-sent
 * entries remain to be matched again in queue order. A process death during a retry window drops
 * that pending retry post (and any stalled notification), but the scheduled fallback date still
 * delivers the message.
 */
public final class EventScheduleController {

    private static final class Pending {
        final int account;
        final EventScheduleEntry entry;
        final int scheduleDate;
        final long expiresAtElapsedMs;
        final long claimExpiresAtElapsedMs;
        long localGroupedId;

        Pending(int account, EventScheduleEntry entry, int scheduleDate,
                long expiresAtElapsedMs, long claimExpiresAtElapsedMs) {
            this.account = account;
            this.entry = entry;
            this.scheduleDate = scheduleDate;
            this.expiresAtElapsedMs = expiresAtElapsedMs;
            this.claimExpiresAtElapsedMs = claimExpiresAtElapsedMs;
        }
    }

    private static final class QueueState {
        final ArrayList<EventScheduleEntry> entries = new ArrayList<>();
        int token;
        int retriesLeft;
    }

    // Local scheduled echoes are posted inline from the send path, so claim eligibility is short.
    private static final long CLAIM_WINDOW_MS = 30000;
    // Bind window for associating send acks back to a just-armed trigger.
    private static final long BIND_WINDOW_SECONDS = 30L * 60L;
    // Retry at most once in the same run (2 total send attempts counting the first).
    private static final int SEND_ATTEMPTS_PER_RUN = 2;
    // Keep parity with RescheduleSpreadExecutor's "short wait" cutoff.
    private static final int MAX_RETRY_WAIT_SECONDS = 60;

    // Written only on the UI thread (arm/kill/ack/pending GC all run there).
    private static final Map<String, Pending> PENDING = new HashMap<>();
    private static final Map<String, QueueState> QUEUES = new HashMap<>();
    // Run-owned fire suppression for a bulk arm (issue #249): account+entry.key() -> the set of live run
    // tokens holding the trigger back. A bulk run suppresses each pre-existing trigger it admits so the
    // trigger can't fire mid-run and then read back missing during verification, WITHOUT durably removing
    // it -- a durable remove whose only in-memory copy is lost on process death would destroy a trigger the
    // user configured. This map is process-local, so a process death just discards it and the durable entry
    // reloads armed (runtime state is never persisted). Refcounted by run token so two overlapping runs that
    // both suppress one trigger keep it suppressed until the last releases; a run removes only its own
    // token. UI-thread only, like PENDING and QUEUES.
    private static final Map<String, Set<Object>> SUPPRESSED = new HashMap<>();
    // account -> entry keys with a durable reconcile in flight. Written only on the UI thread, like
    // PENDING. While a key is tracked here the expiry GC must leave the entry alone: a slow storage-queue
    // lookup may still heal it, so the delete waits for that lookup's own snapshot-guarded disposition.
    private static final Map<Integer, HashSet<String>> DURABLE_INFLIGHT = new HashMap<>();
    private static int nextQueueToken;
    private static volatile long pendingAccounts;
    private static volatile long warmedAccounts;
    private static final Object OBSERVED_LOCK = new Object();
    private static final ArrayList<Integer> OBSERVED = new ArrayList<>();

    private EventScheduleController() {}

    private static String pendingKey(int account, @NonNull String entryKey) {
        return account + "_" + entryKey;
    }

    private static String queueKey(int account, EventScheduleEntry entry) {
        return queueKey(account, entry.dialogId, entry.triggerKey());
    }

    static String queueKey(int account, long dialogId, String triggerKey) {
        return account + "_" + dialogId + "_" + triggerKey;
    }

    private static String suppressKey(int account, @NonNull String entryKey) {
        return account + "_" + entryKey;
    }

    private static boolean isSuppressed(int account, @NonNull String entryKey) {
        // releaseSuppression drops the map key when the last owner leaves, so a present key always means
        // a live owner still holds this trigger back.
        return SUPPRESSED.containsKey(suppressKey(account, entryKey));
    }

    // Package-private so the Message Triggers overview page can order its rows the same way the
    // engine actually fires them, instead of reimplementing this comparator.
    static final Comparator<EventScheduleEntry> QUEUE_ORDER = (a, b) -> {
        int result = Integer.compare(a.fallbackDate, b.fallbackDate);
        if (result == 0) result = Long.compare(a.createdAt, b.createdAt);
        if (result == 0) {
            int aId = a.serverIds.isEmpty() ? Integer.MAX_VALUE : a.serverIds.get(0);
            int bId = b.serverIds.isEmpty() ? Integer.MAX_VALUE : b.serverIds.get(0);
            result = Integer.compare(aId, bId);
        }
        return result;
    };

    private static boolean hasState(int account) {
        return EventScheduleStore.hasAny(account) || (pendingAccounts & (1L << account)) != 0;
    }

    private static final Runnable PENDING_GC = () -> {
        pruneExpiredPending();
        schedulePendingGc();
    };

    // Rebuild account bits from the pending map so the hot path can bail cheaply when no arm is pending.
    private static void refreshPendingBits() {
        long bits = 0;
        for (Pending pending : PENDING.values()) {
            bits |= (1L << pending.account);
        }
        pendingAccounts = bits;
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static long toElapsedDeadline(long epochSeconds) {
        long remainingMs = epochSeconds * 1000L - System.currentTimeMillis();
        return SystemClock.elapsedRealtime() + Math.max(remainingMs, 0L);
    }

    private static void addPending(int account, @NonNull EventScheduleEntry entry, int scheduleDate, long claimWindowMs) {
        long claimExpiresAtElapsedMs = claimWindowMs <= 0 ? 0 : SystemClock.elapsedRealtime() + claimWindowMs;
        PENDING.put(pendingKey(account, entry.key()),
                new Pending(account, entry, scheduleDate, toElapsedDeadline(entry.bindExpiresAt), claimExpiresAtElapsedMs));
        refreshPendingBits();
    }

    private static void removePending(int account, @NonNull String entryKey) {
        PENDING.remove(pendingKey(account, entryKey));
        refreshPendingBits();
    }

    private static void schedulePendingGc() {
        AndroidUtilities.cancelRunOnUIThread(PENDING_GC);
        long nearestDelay = Long.MAX_VALUE;
        long now = SystemClock.elapsedRealtime();
        for (Pending pending : PENDING.values()) {
            nearestDelay = Math.min(nearestDelay, Math.max(0L, pending.expiresAtElapsedMs - now));
        }
        if (nearestDelay == Long.MAX_VALUE) return;
        AndroidUtilities.runOnUIThread(PENDING_GC, nearestDelay);
    }

    private static void pruneExpiredPending() {
        if (PENDING.isEmpty()) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        long nowSec = nowSeconds();
        ArrayList<Pending> expired = new ArrayList<>();
        for (Pending pending : PENDING.values()) {
            if (pending.expiresAtElapsedMs <= nowElapsed) {
                expired.add(pending);
            }
        }
        if (expired.isEmpty()) return;
        for (Pending pending : expired) {
            EventScheduleEntry entry = pending.entry;
            removePending(pending.account, entry.key());
            if (!EventScheduleStore.contains(pending.account, entry.key())) continue;
            if (entry.serverIds.isEmpty()) {
                if (isDurableInFlight(pending.account, entry.key())) {
                    // A durable reconcile is resolving this entry; its guarded disposition (heal, or a
                    // snapshot-checked removal) owns the delete, so GC must not race ahead and destroy a
                    // trigger that is about to be healed. It stays in the store, tracked, until then.
                    continue;
                }
                EventScheduleStore.remove(pending.account, entry.key());
            } else if (entry.bindExpiresAt <= nowSec) {
                entry.bindExpiresAt = 0;
                EventScheduleStore.persist(pending.account, entry);
            }
        }
    }

    private static void restorePending(int account) {
        long nowSec = nowSeconds();
        ArrayList<EventScheduleEntry> entries = EventScheduleStore.forAccount(account);
        for (EventScheduleEntry entry : entries) {
            if (entry.bindExpiresAt <= 0) continue;
            if (entry.bindExpiresAt <= nowSec) {
                if (entry.serverIds.isEmpty()) {
                    // An unbound entry that carries durable correlation keys is not deleted on expiry
                    // here: the async warm reconcile below may still heal it to current server ids from
                    // randoms_v2, and a single failed read must not destroy a recoverable trigger. Its
                    // final disposition (heal, or the deferred fail-closed removal) is reconcileDurable's.
                    // Legacy entries with no randomIds keep the pre-existing expiry-removal behaviour.
                    if (entry.randomIds.isEmpty()) {
                        EventScheduleStore.remove(account, entry.key());
                    }
                } else {
                    entry.bindExpiresAt = 0;
                    EventScheduleStore.persist(account, entry);
                }
                continue;
            }
            addPending(account, entry, entry.fallbackDate, 0);
        }
        schedulePendingGc();
        reconcileDurable(account);
    }

    // The observer serves every account (the callback carries the account), so one instance
    // registered per account covers id remap and scheduled deletes for its whole lifetime.
    private static final NotificationCenter.NotificationCenterDelegate OBSERVER = (id, account, args) -> {
        if (id == NotificationCenter.messageReceivedByServer) {
            boolean scheduled = args.length > 6 && args[6] instanceof Boolean && (Boolean) args[6];
            if (!scheduled) return;
            long dialogId = args.length > 3 && args[3] instanceof Long ? (Long) args[3] : 0L;
            if (dialogId == 0L) return;
            int oldId = args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : 0;
            int newId = args.length > 1 && args[1] instanceof Integer ? (Integer) args[1] : 0;
            TLRPC.Message message = args.length > 2 && args[2] instanceof TLRPC.Message ? (TLRPC.Message) args[2] : null;
            if (message == null) return;
            long groupedId = args.length > 4 && args[4] instanceof Long ? (Long) args[4] : 0L;
            onIdRemap(account, oldId, newId, dialogId, groupedId);
        } else if (id == NotificationCenter.messagesDeleted) {
            boolean scheduled = args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2];
            if (!scheduled) return;
            @SuppressWarnings("unchecked")
            ArrayList<Integer> ids = (ArrayList<Integer>) args[0];
            long channelId = args[1] instanceof Long ? (Long) args[1] : 0L;
            EventScheduleStore.purgeIds(account, channelId, ids);
        }
    };

    /**
     * Warms the on-disk store for an account once per process (lock-free after the first call), so
     * triggers armed in a previous session re-arm after a restart. Called from the message funnel and
     * from the scheduled-view time decorator, whichever runs first.
     */
    public static void ensureWarm(int account) {
        if ((warmedAccounts & (1L << account)) != 0) return;
        warmedAccounts |= (1L << account);
        EventScheduleStore.ensureLoaded(account);
        restorePending(account);
        if (hasState(account)) ensureObserver(account);
    }

    static void ensureObserver(int account) {
        synchronized (OBSERVED_LOCK) {
            if (OBSERVED.contains(account)) return;
            OBSERVED.add(account);
        }
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter nc = NotificationCenter.getInstance(account);
            nc.addObserver(OBSERVER, NotificationCenter.messageReceivedByServer);
            nc.addObserver(OBSERVER, NotificationCenter.messagesDeleted);
        });
    }

    /** Called from the schedule sheet's confirm, before the message is actually sent. */
    public static String armPending(int account, long dialogId, @NonNull EventScheduleEntry entry, int scheduleDate) {
        entry.dialogId = dialogId;
        entry.fallbackDate = scheduleDate;
        entry.bindGroupedId = 0;
        entry.bindExpiresAt = nowSeconds() + BIND_WINDOW_SECONDS;
        entry.state = EventScheduleEntry.STATE_ARMED;
        EventScheduleStore.persist(account, entry);
        addPending(account, entry, scheduleDate, CLAIM_WINDOW_MS);
        schedulePendingGc();
        ensureObserver(account);
        return entry.key();
    }

    /** Drops a pending bind that never got a message (trigger turned off, or a stale edit). */
    public static void killPending(int account, String entryKey) {
        if (TextUtils.isEmpty(entryKey)) return;
        removePending(account, entryKey);
        if (EventScheduleStore.contains(account, entryKey)) {
            EventScheduleStore.remove(account, entryKey);
        }
        schedulePendingGc();
    }

    /** Trigger explicitly turned off: drop only still-unclaimed pending arms for this dialog. */
    public static void killUnclaimedForDialog(int account, long dialogId) {
        ArrayList<String> keys = new ArrayList<>();
        for (Pending pending : PENDING.values()) {
            EventScheduleEntry entry = pending.entry;
            if (pending.account != account || entry.dialogId != dialogId) continue;
            if (!entry.serverIds.isEmpty() || !entry.localIds.isEmpty()) continue;
            keys.add(entry.key());
        }
        for (String key : keys) {
            removePending(account, key);
            if (EventScheduleStore.contains(account, key)) {
                EventScheduleStore.remove(account, key);
            }
        }
        schedulePendingGc();
    }

    /**
     * Temporary compatibility path: arms a trigger on an entry whose server ids are already set. Does
     * NO ownership resolution -- it cannot see whether another entry already owns these ids -- so it is
     * not the gate for arming during an edit; that is {@link #resolveAndClaimForEdit}. No caller exists in
     * this tree. The apparent callers on the `2026-09-01-reschedule-trigger` branch are pre-#259 helper
     * code that this ownership gate supersedes, so they disappear when that branch integrates #259; #249
     * owns removing this method at its migration. No new caller should use it -- route arming through
     * {@link #resolveAndClaimForEdit}. Kept meanwhile so that a caller which does reach for it still can't
     * bind an in-flight id and produce a trigger that reports live but never fires (the #256 defect class).
     */
    public static boolean armExisting(int account, @NonNull EventScheduleEntry entry) {
        if (entry.hasInvalidIds() || entry.serverIds.isEmpty()) {
            return false;
        }
        entry.bindGroupedId = 0;
        entry.bindExpiresAt = 0;
        entry.state = EventScheduleEntry.STATE_ARMED;
        EventScheduleStore.persist(account, entry);
        ensureObserver(account);
        return true;
    }

    /**
     * Temporary compatibility path: replaces an already-armed entry's fields in place after an edit.
     * Like {@link #armExisting} it does NO ownership resolution, so it is not the edit gate --
     * {@link #resolveAndClaimForEdit} is. Same status as armExisting: no caller in this tree, the
     * `2026-09-01-reschedule-trigger` callers are pre-#259 code the gate supersedes, and removal is #249's
     * at migration. No new caller should use it.
     */
    public static boolean updateForEdit(int account, @NonNull EventScheduleEntry entry, int types, String pattern, boolean regex, int delaySeconds, int fallbackDate) {
        // Fail closed before touching any live state -- revision++ and removeFromQueue below mutate the
        // entry and the queue, so a rejection after them would leave a half-edited entry, worse than
        // what this guards. A bound entry keeps its negative localIds after remap, so hasInvalidIds
        // permits those.
        if (entry.hasInvalidIds() || entry.serverIds.isEmpty()) {
            return false;
        }
        entry.revision++;
        removeFromQueue(account, entry);
        entry.types = types;
        entry.setPatterns(java.util.Collections.singletonList(pattern));
        entry.regex = regex;
        entry.delaySeconds = delaySeconds;
        entry.fallbackDate = fallbackDate;
        entry.bindGroupedId = 0;
        if (!entry.serverIds.isEmpty()) {
            entry.bindExpiresAt = 0;
        }
        entry.state = EventScheduleEntry.STATE_ARMED;
        // Swaps in a fresh, uncompiled pattern-state for the new revision -- this reference
        // replacement (not a flag reset) is what guarantees a background compile still in
        // flight against the pre-edit state can never publish over it: see
        // EventScheduleEntry#resetPatternState / #compileAndPublish.
        entry.resetPatternState();
        EventScheduleStore.persist(account, entry);
        ensureObserver(account);
        return true;
    }

    /**
     * Bulk-arm admission (issue #249): hold one pre-existing trigger back from firing for the duration of
     * a run, without touching durable state. Ejects the entry from any live queue and resets its runtime
     * state to ARMED -- removeFromQueue leaves a WAITING entry's state unchanged, which would leave it
     * unable to fire even after release, since evaluate only re-queues ARMED entries.
     *
     * <p>Returns false when the entry is already SENDING: an issued send can't be recalled, so the caller
     * must treat that target as linearized before admission and reject it in verification.
     *
     * @param runToken identifies the run so overlapping runs refcount cleanly; pass the same token to
     *                 {@link #releaseSuppression}.
     */
    public static boolean suppressForBulk(int account, @NonNull EventScheduleEntry entry, @NonNull Object runToken) {
        if (entry.state == EventScheduleEntry.STATE_SENDING) {
            return false;
        }
        String sk = suppressKey(account, entry.key());
        Set<Object> owners = SUPPRESSED.get(sk);
        if (owners == null) {
            owners = new HashSet<>();
            SUPPRESSED.put(sk, owners);
        }
        owners.add(runToken);
        removeFromQueue(account, entry);
        entry.state = EventScheduleEntry.STATE_ARMED;
        return true;
    }

    /**
     * Release one run's hold on a trigger. It becomes eligible to fire again only once the last run
     * releases it. A no-op for a key this run never suppressed, and idempotent.
     */
    public static void releaseSuppression(int account, @NonNull String entryKey, @NonNull Object runToken) {
        String sk = suppressKey(account, entryKey);
        Set<Object> owners = SUPPRESSED.get(sk);
        if (owners == null) return;
        owners.remove(runToken);
        if (owners.isEmpty()) {
            SUPPRESSED.remove(sk);
        }
    }

    /**
     * Arms (or re-arms) a trigger while editing a scheduled message. Ownership is re-resolved at this
     * point across both id spaces, so a snapshot taken when the sheet opened cannot strand a pending
     * owner or duplicate it. Returns false when the store rejects the claim -- the message is already
     * owned by more than one entry (a pre-existing corruption), the id set is empty/non-positive, or the
     * captured generation no longer matches the slot's (a logout, and maybe a reuse, landed between intent
     * and this claim) -- in which case nothing is changed and the caller must not report success.
     */
    public static boolean commitEditArm(int account, int generation, long dialogId, int[] serverIds, int[] localIds,
                                        @NonNull EventScheduleConfig config, int fallbackDate) {
        EventScheduleStore.EditClaim claim = EventScheduleStore.resolveAndClaimForEdit(
                account, generation, dialogId, serverIds, localIds,
                config, fallbackDate, System.currentTimeMillis());
        if (claim.status == EventScheduleStore.EditClaim.Status.REJECTED_MULTI
                || claim.status == EventScheduleStore.EditClaim.Status.REJECTED_INVALID_IDS
                || claim.status == EventScheduleStore.EditClaim.Status.REJECTED_STALE) {
            return false;
        }
        if (claim.status == EventScheduleStore.EditClaim.Status.UPDATED_EXISTING) {
            // The edit changed the entry's fields (and thus its queueKey), so drop it from the bucket it
            // was queued under before the change; the captured trigger key names that old bucket.
            removeFromQueueByKey(account, queueKey(account, dialogId, claim.previousTriggerKey), claim.entry);
        }
        // The entry now carries server ids, so any lingering pending-bind record is stale -- drop it
        // rather than leave it for GC. Harmless (a no-op) for a freshly claimed entry.
        removePending(account, claim.entry.key());
        ensureObserver(account);
        return true;
    }

    /**
     * Bulk-arm one target through the same ownership gate as {@link #commitEditArm}, but driven from a
     * pre-built entry so the caller's batch-local createdAt sequence -- not wall-clock -- seeds a fresh
     * claim, and reporting the resolved entry's key rather than a bare boolean: on a merge that key is the
     * existing owner's, not the built entry's, and the caller needs it to release the right suppression
     * hold. Never removes an entry -- a merge updates the survivor in place, and on the merge path the
     * existing entry IS the survivor being armed, so a create-then-remove would delete what was just armed.
     * Returns the resolved entry's key on success (merged or freshly claimed), or null when the store
     * rejects the claim (multi-owner, an empty/non-positive id set, or a captured generation that no longer
     * matches the slot's -- a logout, and maybe a reuse, landed mid-run). {@code negativeLocalIds} carries
     * the album's local_id echoes so ownership is resolved across both id spaces (mirroring
     * {@link #commitEditArm}) -- a still-pending owner reachable only by local id must not be missed, or
     * a second trigger gets armed beside it.
     */
    public static String bulkArmSurvivor(int account, int generation, long dialogId, @NonNull EventScheduleEntry built, @Nullable int[] negativeLocalIds) {
        int[] serverIds = new int[built.serverIds.size()];
        for (int i = 0; i < serverIds.length; i++) {
            serverIds[i] = built.serverIds.get(i);
        }
        EventScheduleStore.EditClaim claim = EventScheduleStore.resolveAndClaimForEdit(
                account, generation, dialogId, serverIds, negativeLocalIds,
                new EventScheduleConfig(built.types, built.normalizedPatterns(), built.regex, built.delaySeconds),
                built.fallbackDate, built.createdAt);
        if (claim.status == EventScheduleStore.EditClaim.Status.REJECTED_MULTI
                || claim.status == EventScheduleStore.EditClaim.Status.REJECTED_INVALID_IDS
                || claim.status == EventScheduleStore.EditClaim.Status.REJECTED_STALE) {
            return null;
        }
        if (claim.status == EventScheduleStore.EditClaim.Status.UPDATED_EXISTING) {
            // The merge changed the entry's fields (and thus its queueKey), so drop it from the bucket it
            // was queued under before the change, named by the captured previous trigger key. Mirrors
            // commitEditArm; harmless when the entry was suppressed out of every queue at admission.
            removeFromQueueByKey(account, queueKey(account, dialogId, claim.previousTriggerKey), claim.entry);
        }
        // The entry now carries server ids, so any lingering pending-bind record is stale -- drop it rather
        // than leave it for GC. A no-op for a freshly claimed entry.
        removePending(account, claim.entry.key());
        ensureObserver(account);
        return claim.entry.key();
    }

    /** Turns a trigger off while editing: drops every owner of the message (bound or still pending). */
    public static void commitEditOff(int account, long dialogId, int[] serverIds, int[] localIds) {
        ArrayList<String> keys = EventScheduleStore.resolveOwnerKeysForEdit(account, dialogId, serverIds, localIds);
        for (String key : keys) {
            if (EventScheduleStore.contains(account, key)) {
                EventScheduleStore.remove(account, key);
            }
        }
    }

    /**
     * Keeps an existing single owner's derived schedule time in step when the user edits a scheduled
     * message's send time without touching the trigger controls. Never creates, removes, or
     * reconfigures a trigger -- a multi-owner conflict or no owner is left exactly as it is. Re-sorts
     * the owner's queue bucket so the fallback-time send order the overview promises stays correct
     * (resortQueueForEntry leaves a STATE_SENDING head alone -- its RPC owns the queue). When the time
     * actually moves it advances the owner's process-local scheduleRevision (C2): a schedule-only edit
     * is still a later single-message edit that an in-flight bulk arm must yield to, and the bulk
     * finalizer detects that by an exact scheduleRevision recheck. It bumps scheduleRevision, NOT
     * revision: revision is the send pipeline's staleness token (fire/retryHeadSend/onSendError), and
     * bumping it while a send RPC is outstanding makes a later error read as stale and stall the queue.
     * Bumped only on a real move, or a spurious bump would make the bulk arm needlessly reject an
     * untouched target.
     *
     * <p>INVARIANT: this method is the sole funnel for an untouched-trigger schedule move. The
     * scheduleRevision signal is only as complete as this funnel -- any future path that moves an
     * owner's fallbackDate MUST bump scheduleRevision here (or route through here), or a concurrent
     * bulk arm will miss the edit and arm from a stale snapshot. Its callers are the single-message
     * edit sheet and a plain bulk reschedule, through {@link #bulkMoveListener}.
     */
    public static void commitEditRefresh(int account, long dialogId, int[] serverIds, int[] localIds, int fallbackDate) {
        EventScheduleStore.OwnerSeed seed = EventScheduleStore.resolveOwnerSeedForEdit(account, dialogId, serverIds, localIds);
        boolean moved = seed.kind == EventScheduleStore.EditOwner.SINGLE
                && seed.entry != null && seed.entry.fallbackDate != fallbackDate;
        EventScheduleEntry entry = EventScheduleStore.refreshFallbackForEdit(
                account, dialogId, serverIds, localIds, fallbackDate);
        if (entry != null) {
            if (moved) {
                // Process-local, not persisted (refreshFallbackForEdit already persisted the moved
                // fallbackDate; scheduleRevision itself is never serialized -- see EventScheduleEntry).
                entry.scheduleRevision++;
            }
            resortQueueForEntry(account, entry);
        }
    }

    /**
     * Routes a plain bulk reschedule's confirmed moves through {@link #commitEditRefresh}, the funnel its
     * invariant names, so an existing trigger's fallback time follows each moved message. Without it the
     * Message Triggers overview keeps showing the old time and a reload prunes the trigger once that old
     * time passes. Like the single-message edit, it reconciles the dialog's durable orphans first, so an
     * orphan that owns a moved message is healed onto its server ids and refreshed with the rest rather
     * than healed later with the old time. The generation is captured when the run starts and checked
     * after that hop: a logout mid-run may clear and reuse the slot, and the late callback must not
     * rewrite what the new account owns for these ids.
     *
     * <p>The hop can also let a newer edit land first: the single-message sheet refreshes on commit, so
     * its date is newer than the server read this run's dates came from. Each owner's revision and
     * scheduleRevision are snapshotted before the hop and an owner is refreshed only if it is unchanged
     * after it; one that was re-armed, moved or created meanwhile keeps the newer value. A heal by the
     * reconcile itself keeps key and revision (EventScheduleStore.healDurable), so a healed orphan still
     * matches and is refreshed.
     */
    public static com.dazewell.gram.reschedule.RescheduleSpreadExecutor.ScheduleMoveListener bulkMoveListener(int account, long dialogId) {
        final int generation = EventScheduleStore.currentGeneration(account);
        return (albumIds, scheduleDates) -> {
            final HashMap<String, long[]> before = new HashMap<>();
            for (EventScheduleEntry e : EventScheduleStore.forDialog(account, dialogId)) {
                before.put(e.key(), new long[]{e.revision, e.scheduleRevision});
            }
            reconcileDialogThen(account, dialogId, () -> {
                if (EventScheduleStore.currentGeneration(account) != generation) return;
                for (int i = 0; i < albumIds.length; i++) {
                    EventScheduleStore.OwnerSeed seed = EventScheduleStore.resolveOwnerSeedForEdit(account, dialogId, albumIds[i], null);
                    if (seed.kind != EventScheduleStore.EditOwner.SINGLE || seed.entry == null) continue;
                    long[] was = before.get(seed.entry.key());
                    if (was == null || was[0] != seed.entry.revision || was[1] != seed.entry.scheduleRevision) continue;
                    commitEditRefresh(account, dialogId, albumIds[i], null, scheduleDates[i]);
                }
            });
        };
    }

    // --- Durable correlation reconcile (#261) -------------------------------------------------------

    private static void markDurableInFlight(int account, ArrayList<EventScheduleStore.EntrySnapshot> snaps) {
        HashSet<String> keys = DURABLE_INFLIGHT.get(account);
        if (keys == null) {
            keys = new HashSet<>();
            DURABLE_INFLIGHT.put(account, keys);
        }
        for (EventScheduleStore.EntrySnapshot s : snaps) keys.add(s.key);
    }

    private static void clearDurableInFlight(int account, String key) {
        HashSet<String> keys = DURABLE_INFLIGHT.get(account);
        if (keys != null && keys.remove(key) && keys.isEmpty()) DURABLE_INFLIGHT.remove(account);
    }

    private static boolean isDurableInFlight(int account, String key) {
        HashSet<String> keys = DURABLE_INFLIGHT.get(account);
        return keys != null && keys.contains(key);
    }

    /**
     * Warm-path reconcile: for every unbound entry that carries durable correlation keys, resolve those
     * random_ids back to current server ids through {@code randoms_v2} and either heal the entry or
     * apply the deferred expiry policy. Short-circuits with no storage hop when the account has no such
     * orphan, so a warm on an account of ordinary bound triggers costs nothing here.
     */
    private static void reconcileDurable(int account) {
        ArrayList<EventScheduleStore.EntrySnapshot> snaps = EventScheduleStore.collectUnboundRandomSnapshots(account);
        if (snaps.isEmpty()) return;
        postDurableLookup(account, snaps, null);
    }

    /**
     * Runs the one joined lookup on the storage queue and applies its result on the UI thread. The JOIN
     * onto {@code scheduled_messages_v2} is what keeps a fired-or-deleted scheduled message (whose
     * {@code randoms_v2} row outlives it) from being resurrected through a stale correlation. Only
     * immutable snapshots cross to the storage thread. Every entry looked up is first marked in-flight so
     * the expiry GC defers to this lookup's disposition rather than deleting a trigger mid-resolution.
     * Terminal delivery is guaranteed exactly once: the task is wrapped in try/catch (throw ->
     * LOOKUP_ERROR), and if the enqueue itself fails the same apply runs synchronously with LOOKUP_ERROR
     * -- never both. {@code onDone} (commit-time only) runs after the apply on the UI thread.
     */
    private static void postDurableLookup(int account, ArrayList<EventScheduleStore.EntrySnapshot> snaps, Runnable onDone) {
        markDurableInFlight(account, snaps);
        HashSet<Long> randomSet = new HashSet<>();
        HashSet<Long> dialogSet = new HashSet<>();
        for (EventScheduleStore.EntrySnapshot s : snaps) {
            dialogSet.add(s.dialogId);
            for (long r : s.randomIds) randomSet.add(r);
        }
        if (randomSet.isEmpty()) {
            applyDurableResolution(account, snaps, Collections.emptyMap(), false);
            if (onDone != null) onDone.run();
            return;
        }
        final String randomsIn = TextUtils.join(",", randomSet);
        final String dialogsIn = TextUtils.join(",", dialogSet);
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        boolean posted = storage.getStorageQueue().postRunnable(() -> {
            boolean error = false;
            HashMap<Long, ArrayList<Integer>> resolved = new HashMap<>();
            SQLiteCursor cursor = null;
            try {
                SQLiteDatabase db = storage.getDatabase();
                if (db == null) {
                    error = true;
                } else {
                    cursor = db.queryFinalized(String.format(Locale.US,
                            "SELECT r.random_id, r.mid FROM randoms_v2 r INNER JOIN scheduled_messages_v2 s ON s.mid = r.mid AND s.uid = r.uid WHERE r.uid IN (%s) AND r.random_id IN (%s)",
                            dialogsIn, randomsIn));
                    while (cursor.next()) {
                        long randomId = cursor.longValue(0);
                        int mid = cursor.intValue(1);
                        ArrayList<Integer> list = resolved.get(randomId);
                        if (list == null) {
                            list = new ArrayList<>();
                            resolved.put(randomId, list);
                        }
                        if (!list.contains(mid)) list.add(mid);
                    }
                }
            } catch (Throwable t) {
                error = true;
                FileLog.e("eventschedule: durable random_id lookup failed, failing closed", t);
            } finally {
                if (cursor != null) cursor.dispose();
            }
            final boolean lookupError = error;
            final HashMap<Long, ArrayList<Integer>> resolvedFinal = resolved;
            AndroidUtilities.runOnUIThread(() -> {
                applyDurableResolution(account, snaps, resolvedFinal, lookupError);
                if (onDone != null) onDone.run();
            });
        });
        if (!posted) {
            applyDurableResolution(account, snaps, Collections.emptyMap(), true);
            if (onDone != null) onDone.run();
        }
    }

    // UI thread: turn the classification into heal / expiry / inert actions on the live cache.
    private static void applyDurableResolution(int account, ArrayList<EventScheduleStore.EntrySnapshot> snaps,
                                               Map<Long, ArrayList<Integer>> resolved, boolean lookupError) {
        Map<String, EventScheduleStore.DurableResolution> verdicts =
                EventScheduleStore.classifyDurable(snaps, resolved, lookupError);
        long nowSec = nowSeconds();
        boolean pendingChanged = false;
        for (EventScheduleStore.EntrySnapshot snap : snaps) {
            EventScheduleStore.DurableResolution r = verdicts.get(snap.key);
            if (r == null) continue;
            switch (r.verdict) {
                case HEAL:
                    if (EventScheduleStore.healDurable(account, snap, r.healMids)) {
                        removePending(account, snap.key);
                        pendingChanged = true;
                        EventScheduleEntry healed = EventScheduleStore.findByKey(account, snap.key);
                        if (healed != null) resortQueueForEntry(account, healed);
                    }
                    // Verdict delivered (healed, or stale so the edit/live path now owns it): stop
                    // deferring GC for this key.
                    clearDurableInFlight(account, snap.key);
                    break;
                case PENDING_UNSENT:
                case MISSING:
                case REJECTED_UNRESOLVED:
                    // Snapshot-guarded so a slow result can't remove an entry that changed underneath it.
                    // An expired unbound orphan can't be rescued by the live path either
                    // (findPendingByLocalId searches only PENDING, which an expired entry has left), so
                    // dropping it is correct; a within-window entry is left as restorePending set it.
                    if (EventScheduleStore.removeIfExpiredUnbound(account, snap, nowSec)) {
                        removePending(account, snap.key);
                        pendingChanged = true;
                    }
                    clearDurableInFlight(account, snap.key);
                    break;
                case LOOKUP_ERROR:
                default:
                    // A failed read is non-destructive AND stays tracked: never expiry-remove on the
                    // strength of a read that did not complete, so leave the entry inert and in-flight for
                    // the commit-time reconcile or a later process to retry. Do not clear the in-flight
                    // mark -- that is what keeps GC off it.
                    break;
            }
        }
        if (pendingChanged) schedulePendingGc();
    }

    /** Synchronous predicate the edit-commit path uses to gate the storage hop (the common case skips it). */
    public static boolean needsCommitReconcile(int account, long dialogId) {
        return EventScheduleStore.hasUnboundRandomEntry(account, dialogId);
    }

    /**
     * Rare edit-commit path: an unbound durable orphan still sits in this dialog, so resolve it to
     * current server ids before deciding ownership, otherwise the edit acts on the wrong state -- arming
     * could create a second trigger beside the orphan, and turning off can't reach it (it has no current
     * server/local id to match). Holds only the plain edit tuple plus the intent-time store {@code generation}
     * across the storage hop -- no fragment, no delegate, no {@code proceed} -- and runs after the sheet has
     * dismissed; that carried generation is what lets {@link #finishCommitEdit} fail closed if the slot was
     * logged out (and maybe reused) mid-hop. On completion it either
     * applies the intent against the healed owner or, if any orphan stayed unresolved, fails closed for
     * both arm and off with a toast shown from {@link #finishCommitEdit} itself, so nothing outside the
     * controller is captured across the hop.
     */
    public static void reconcileThenCommitEdit(int account, int generation, long dialogId, int[] editIds, int[] editLocalIds,
                                               boolean userTouchedTrigger, boolean armed,
                                               @NonNull EventScheduleConfig config, int scheduleDate) {
        ArrayList<EventScheduleStore.EntrySnapshot> snaps = EventScheduleStore.collectUnboundRandomSnapshots(account, dialogId);
        if (snaps.isEmpty()) {
            finishCommitEdit(account, generation, dialogId, editIds, editLocalIds, userTouchedTrigger, armed, config, scheduleDate);
            return;
        }
        postDurableLookup(account, snaps, () ->
                finishCommitEdit(account, generation, dialogId, editIds, editLocalIds, userTouchedTrigger, armed, config, scheduleDate));
    }

    /**
     * Bulk-arm gate for a whole selection: reconcile this dialog's unbound durable orphans to current
     * server ids before the caller decides ownership over the selection, then run {@code onDone} on the UI
     * thread. Mirrors the single-message {@link #reconcileThenCommitEdit}, but stays generic -- the caller
     * (the bulk armer) does the ownership decision and the dialog-wide gate re-check itself in
     * {@code onDone}, since a bulk selection is dialog-scoped and an orphan that stays unresolved must
     * decline the WHOLE selection through the same {@link EventScheduleStore#hasUnboundRandomEntry} gate.
     * Runs {@code onDone} synchronously when the dialog has no orphan -- the common case, no storage hop.
     */
    public static void reconcileDialogThen(int account, long dialogId, @NonNull Runnable onDone) {
        ArrayList<EventScheduleStore.EntrySnapshot> snaps = EventScheduleStore.collectUnboundRandomSnapshots(account, dialogId);
        if (snaps.isEmpty()) {
            onDone.run();
            return;
        }
        postDurableLookup(account, snaps, onDone);
    }

    private static void finishCommitEdit(int account, int generation, long dialogId, int[] editIds, int[] editLocalIds,
                                         boolean userTouchedTrigger, boolean armed,
                                         @NonNull EventScheduleConfig config, int scheduleDate) {
        // The armed branch's own gate in resolveAndClaimForEdit rejects a stale claim, but the
        // schedule-only refresh (commitEditRefresh) and turn-off (commitEditOff) branches below reach the
        // store without it. On the async reconcile path this runnable can land after a logout that cleared
        // and reused the slot, where a refresh would rewrite -- and an off would delete -- whatever the new
        // account now owns for these ids. Fail closed for all three branches on a generation mismatch;
        // harmless on the synchronous path, where the generation captured at commit is still current.
        if (EventScheduleStore.currentGeneration(account) != generation) {
            return;
        }
        if (!userTouchedTrigger) {
            commitEditRefresh(account, dialogId, editIds, editLocalIds, scheduleDate);
            return;
        }
        // A durable orphan that could not be resolved to server ids still sits unbound in this dialog,
        // and it fails both intents equally. Arming now could create a second trigger beside it; turning
        // off can't remove it either, because commitEditOff matches server/local ids and the orphan has
        // neither current one (empty serverIds, stale negative localIds) -- the off would be a silent
        // no-op and a later warm heal could still fire the trigger the user just disarmed. So fail closed
        // for arm and off alike and tell the user their trigger settings were left unchanged. The toast is
        // a global, context-free AlertUtil call -- no fragment or delegate is held across the storage hop
        // to reach here.
        //
        // The block is deliberately DIALOG-WIDE, not message-scoped: an unresolved orphan (in particular
        // one pinned by a LOOKUP_ERROR, which stays tracked until a read succeeds) declines trigger arm
        // AND disarm for EVERY scheduled message in this chat, including edits to unrelated messages,
        // until the next successful reconcile clears it. That is forced, not a shortcut -- the orphan by
        // definition has no current id to compare an edit against, so the gate cannot narrow to one
        // message without reopening the wrong-owner hole this whole change exists to close. The schedule
        // time still changes on such an edit; only the trigger settings are declined.
        if (EventScheduleStore.hasUnboundRandomEntry(account, dialogId)) {
            AlertUtil.showToast(getString(R.string.EventScheduleTriggerUnconfirmed));
            return;
        }
        if (armed) {
            boolean claimed = commitEditArm(account, generation, dialogId, editIds, editLocalIds, config, scheduleDate);
            if (!claimed) {
                AlertUtil.showToast(getString(R.string.EventScheduleTriggerConflict));
            }
        } else {
            commitEditOff(account, dialogId, editIds, editLocalIds);
        }
    }

    public static void onNewMessages(int account, long dialogId, ArrayList<MessageObject> messages, boolean scheduled) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ArrayList<MessageObject> snapshot = messages == null ? null : new ArrayList<>(messages);
            AndroidUtilities.runOnUIThread(() -> onNewMessages(account, dialogId, snapshot, scheduled));
            return;
        }
        ensureWarm(account);
        if (!hasState(account) || messages == null || messages.isEmpty()) return;
        if (scheduled) {
            claim(account, dialogId, messages);
        } else {
            evaluate(account, dialogId, messages);
        }
    }

    private static void onIdRemap(int account, int oldId, int newId, long dialogId, long groupedId) {
        if (EventScheduleStore.findByMessage(account, dialogId, newId) != null) return;
        pruneExpiredPending();
        Pending pending = findPendingByLocalId(account, dialogId, oldId);
        if (pending == null) return;
        EventScheduleEntry entry = pending.entry;
        if (!EventScheduleStore.contains(account, entry.key())) {
            removePending(account, entry.key());
            schedulePendingGc();
            return;
        }
        if (groupedId != 0) {
            if (entry.bindGroupedId == 0) {
                entry.bindGroupedId = groupedId;
            } else if (entry.bindGroupedId != groupedId) {
                return;
            }
        } else if (!entry.serverIds.isEmpty()) {
            return;
        }
        if (!entry.serverIds.contains(newId)) {
            entry.serverIds.add(newId);
            EventScheduleStore.persist(account, entry);
        }
    }

    private static void claim(int account, long dialogId, ArrayList<MessageObject> messages) {
        pruneExpiredPending();
        ArrayList<EventScheduleEntry> changed = new ArrayList<>();
        for (MessageObject message : messages) {
            if (!message.isOutOwner() || message.messageOwner == null) continue;
            int localId = message.getId();
            if (localId >= 0) continue;
            int scheduleDate = message.messageOwner.date;
            long groupedId = message.messageOwner.grouped_id;
            Pending pending = findPendingForClaim(account, dialogId, scheduleDate, groupedId);
            if (pending == null) continue;
            EventScheduleEntry entry = pending.entry;
            if (groupedId != 0 && pending.localGroupedId == 0) {
                pending.localGroupedId = groupedId;
            }
            if (!entry.localIds.contains(localId)) {
                entry.localIds.add(localId);
                // Durable correlation key for this child. random_id is written to randoms_v2 and set on
                // the echo before it reaches us (SendMessagesHelper), so a zero here means an unexpected
                // send path -- keep the live localId binding but skip the durable key rather than persist
                // a zero that would later fail the reconcile's injectivity guard.
                long randomId = message.messageOwner.random_id;
                if (randomId != 0) {
                    if (!entry.randomIds.contains(randomId)) {
                        entry.randomIds.add(randomId);
                    }
                } else {
                    FileLog.e("eventschedule: claimed scheduled echo with zero random_id, dialog="
                            + dialogId + " local=" + localId);
                }
                if (!changed.contains(entry)) {
                    changed.add(entry);
                }
            }
        }
        for (EventScheduleEntry entry : changed) {
            EventScheduleStore.persist(account, entry);
        }
    }

    private static Pending findPendingByLocalId(int account, long dialogId, int localId) {
        if (localId >= 0) return null;
        for (Pending pending : PENDING.values()) {
            if (pending.account != account) continue;
            if (pending.entry.dialogId != dialogId) continue;
            if (pending.entry.localIds.contains(localId)) {
                return pending;
            }
        }
        return null;
    }

    private static Pending findPendingForClaim(int account, long dialogId, int scheduleDate, long groupedId) {
        long nowElapsed = SystemClock.elapsedRealtime();
        ArrayList<Pending> candidates = new ArrayList<>();
        for (Pending pending : PENDING.values()) {
            EventScheduleEntry entry = pending.entry;
            if (pending.claimExpiresAtElapsedMs <= 0 || pending.claimExpiresAtElapsedMs <= nowElapsed) continue;
            if (pending.account != account || entry.dialogId != dialogId || pending.scheduleDate != scheduleDate) continue;
            if (!acceptsClaim(pending, groupedId)) continue;
            candidates.add(pending);
        }
        if (candidates.isEmpty()) return null;
        Collections.sort(candidates, (a, b) -> {
            int result = Long.compare(a.entry.createdAt, b.entry.createdAt);
            if (result == 0) result = a.entry.key().compareTo(b.entry.key());
            return result;
        });
        return candidates.get(0);
    }

    private static boolean acceptsClaim(@NonNull Pending pending, long groupedId) {
        EventScheduleEntry entry = pending.entry;
        if (groupedId == 0) {
            return pending.localGroupedId == 0 && entry.serverIds.isEmpty() && entry.localIds.isEmpty();
        }
        if (pending.localGroupedId == 0) {
            return entry.serverIds.isEmpty() && entry.localIds.isEmpty();
        }
        return pending.localGroupedId == groupedId;
    }

    private static void evaluate(int account, long dialogId, ArrayList<MessageObject> messages) {
        ArrayList<EventScheduleEntry> entries = EventScheduleStore.forDialog(account, dialogId);
        if (entries.isEmpty()) return;
        for (MessageObject message : messages) {
            if (message.isOutOwner() || message.getId() <= 0 || message.messageOwner == null) continue;
            final String text = matchableText(message);
            for (EventScheduleEntry entry : entries) {
                final String key = entry.key();
                // Suppressed by an in-flight bulk arm (issue #249): held back from firing without a
                // durable remove. Checked here, in armWaiting, and again just before fire issues the RPC,
                // because a pattern match begun before admission can post armWaiting after it.
                if (entry.state != EventScheduleEntry.STATE_ARMED || entry.serverIds.isEmpty()
                        || isSuppressed(account, key)) continue;
                boolean typeSet = entry.types != 0;
                boolean patternSet = entry.hasAnyPattern();
                // OR: a matching type is enough on its own; otherwise the pattern can still match.
                if (typeSet && entry.matchesType(message, text)) {
                    armWaiting(account, entry, key, entry.revision);
                    continue;
                }
                if (!patternSet || text == null) continue;
                // Captured once, on the UI thread, before crossing to the background queue --
                // matching and any needed compile both run against this one immutable
                // snapshot, so they can't observe two different generations of the pattern
                // (see EventScheduleEntry#capturePatternState / #matchPatternIndex).
                final EventScheduleEntry.PatternState patternState = entry.capturePatternState();
                // A user regex has no timeout: keep it off the main thread (fork precedent: replace-text).
                Utilities.globalQueue.postRunnable(() -> {
                    if (entry.matchPatternIndex(patternState, text) < 0) return;
                    AndroidUtilities.runOnUIThread(() -> armWaiting(account, entry, key, patternState.revision));
                });
            }
        }
    }

    /**
     * The text a trigger matches against. A rich (post-format) message carries an empty
     * {@code message} and keeps everything in {@code rich_message}, which {@link MessageObject}
     * has already flattened into {@code messageText} -- anything else would be an app-synthesised
     * string (a service line, a restriction reason, a media title), which no user pattern should
     * be able to hit. Snapshotted to a String here on the UI thread, since the match runs on a
     * background queue and {@code messageText} is a mutable Spannable.
     */
    private static String matchableText(MessageObject message) {
        String text = message.messageOwner.message;
        if (!TextUtils.isEmpty(text)) return text;
        if (message.messageOwner.rich_message == null || message.messageText == null) return null;
        return message.messageText.toString();
    }

    private static void armWaiting(int account, EventScheduleEntry entry, String key, long revision) {
        if (entry.state != EventScheduleEntry.STATE_ARMED || entry.revision != revision
                || isSuppressed(account, key)
                || !EventScheduleStore.contains(account, key)) return;
        entry.state = EventScheduleEntry.STATE_WAITING;
        String queueKey = queueKey(account, entry);
        QueueState queueState = QUEUES.get(queueKey);
        boolean queueRunning = queueState != null;
        if (queueState == null) {
            queueState = new QueueState();
            QUEUES.put(queueKey, queueState);
        }
        ArrayList<EventScheduleEntry> queue = queueState.entries;
        boolean inserted = false;
        if (!queue.contains(entry)) {
            queue.add(entry);
            Collections.sort(queue, QUEUE_ORDER);
            inserted = true;
        }
        if (inserted && queueRunning && !queue.isEmpty() && queue.get(0) == entry) {
            // Deliberately unconditional: if this insert becomes the new head, re-drive now even if
            // the displaced head is already SENDING. A one-sided "skip while SENDING" guard here
            // without a matching non-head completion advance in removeFromQueue reintroduces stalls.
            advanceQueue(account, queueKey);
            return;
        }
        startQueue(account, queueKey);
    }

    private static void startQueue(int account, String queueKey) {
        QueueState queueState = QUEUES.get(queueKey);
        if (queueState == null || queueState.token != 0) return;
        advanceQueue(account, queueKey);
    }

    private static void advanceQueue(int account, String queueKey) {
        QueueState queueState = QUEUES.get(queueKey);
        ArrayList<EventScheduleEntry> queue = queueState == null ? null : queueState.entries;
        while (queue != null && !queue.isEmpty()) {
            EventScheduleEntry entry = queue.get(0);
            if (entry.state == EventScheduleEntry.STATE_WAITING && EventScheduleStore.contains(account, entry.key())) {
                int token = ++nextQueueToken;
                queueState.token = token;
                queueState.retriesLeft = SEND_ATTEMPTS_PER_RUN - 1;
                long revision = entry.revision;
                AndroidUtilities.runOnUIThread(() -> fire(account, entry, queueKey, token, revision), entry.delaySeconds * 1000L);
                return;
            }
            queue.remove(0);
        }
        if (queueState != null && queue.isEmpty()) {
            QUEUES.remove(queueKey);
        }
    }

    private static void removeFromQueue(int account, EventScheduleEntry entry) {
        removeFromQueueByKey(account, queueKey(account, entry), entry);
    }

    // A fallbackDate refresh keeps the entry in the same bucket -- triggerKey() excludes fallbackDate --
    // but can change its position within it, so re-sort to preserve fallback-time firing order. No
    // re-drive: a bucket only exists while it is being driven (armWaiting creates it and immediately
    // advances; advanceQueue removes it once drained), so a live bucket always has a pending fire
    // callback, and fire/retryHeadSend re-read queue.get(0) and hand off to the new head on their own.
    // A STATE_SENDING entry is the exception and must NOT be reordered: its sendScheduledMessages RPC
    // is outstanding and it owns the head, so moving it would let fire/retryHeadSend hand the queue to
    // a different entry and issue a second send while the first RPC is still in flight. Leave it in
    // place; it drops out of the queue on its own when the send completes (success removes it, error
    // re-arms and advances).
    private static void resortQueueForEntry(int account, EventScheduleEntry entry) {
        QueueState queueState = QUEUES.get(queueKey(account, entry));
        if (queueState == null) return;
        if (entry.state == EventScheduleEntry.STATE_SENDING) return;
        ArrayList<EventScheduleEntry> queue = queueState.entries;
        if (!queue.contains(entry)) return;
        Collections.sort(queue, QUEUE_ORDER);
    }

    // Same head-removal / advance behaviour as removeFromQueue, but against a caller-supplied bucket:
    // an edit changes the entry's triggerKey (and thus its live queueKey), so a post-edit removal must
    // use the trigger key captured before the mutation or it would look in the wrong bucket and leave
    // the entry stranded under its old key.
    private static void removeFromQueueByKey(int account, String queueKey, EventScheduleEntry entry) {
        QueueState queueState = QUEUES.get(queueKey);
        if (queueState == null) return;
        ArrayList<EventScheduleEntry> queue = queueState.entries;
        boolean wasHead = !queue.isEmpty() && queue.get(0) == entry;
        queue.remove(entry);
        if (queue.isEmpty()) {
            QUEUES.remove(queueKey);
        } else if (wasHead) {
            advanceQueue(account, queueKey);
        }
    }

    static void onEntryRemoved(int account, EventScheduleEntry entry) {
        removeFromQueue(account, entry);
        removePending(account, entry.key());
        schedulePendingGc();
    }

    /**
     * Account-slot teardown for {@code MessagesController#performLogout}, called on the UI thread beside
     * {@link EventScheduleStore#clearAccountState}. The store clear (and its generation bump) closes the
     * persisted/create side; this drops the controller's process-local runtime state for the slot so a
     * fresh login into the reused slot can't inherit the departed account's pending arms, fire queues,
     * suppression holds, in-flight-reconcile marks, or its warmed/pending "has state" bits. Runs entirely
     * on the UI thread, like every other mutation of these maps, so it never interleaves with an async
     * reconcile completion: that completion either already ran (and this wipes what it left) or runs after,
     * where the commit-edit continuation is rejected by the store's generation guard and the
     * durable-reconcile apply is rejected by its own snapshot identity check (revision plus random_id set)
     * against a slot this clear just emptied. The GC scheduled at the end is a fresh reschedule
     * off the surviving PENDING entries, so a cancelled fire for this slot never resurrects it.
     *
     * <p>Deliberately leaves the per-account observer registration (OBSERVED/{@code ensureObserver})
     * alone: it is idempotent and self-guarding (onIdRemap needs a live PENDING hit, purgeIds only
     * removes), and tearing it down here would risk a double-registration if the slot is reused, for no
     * correctness gain now that the state it observes is cleared.
     */
    public static void onAccountLoggedOut(int account) {
        long accountBit = 1L << account;
        ArrayList<String> stale = new ArrayList<>();
        // Pending arms carry their account on the Pending record; drop every one for this slot.
        for (Map.Entry<String, Pending> e : PENDING.entrySet()) {
            if (e.getValue().account == account) stale.add(e.getKey());
        }
        for (String key : stale) PENDING.remove(key);
        // Fire queues and run-owned suppression holds are keyed "account_..."; the "_" right after the
        // account digits makes a startsWith prefix unambiguous (account 1 never matches account 10's keys).
        String prefix = account + "_";
        stale.clear();
        for (String key : QUEUES.keySet()) {
            if (key.startsWith(prefix)) stale.add(key);
        }
        for (String key : stale) QUEUES.remove(key);
        stale.clear();
        for (String key : SUPPRESSED.keySet()) {
            if (key.startsWith(prefix)) stale.add(key);
        }
        for (String key : stale) SUPPRESSED.remove(key);
        // DURABLE_INFLIGHT is keyed by the account directly; a still-enqueued storage lookup for the slot
        // drains harmlessly. Its two writers carry no generation token and need none: both re-fetch by
        // snapshot key and refuse unless the live entry still matches the snapshot's revision AND its exact
        // random_id set (EventScheduleStore.healDurable/removeIfExpiredUnbound), which the clear above
        // emptied and a reused slot's own sends cannot reproduce -- random_ids, unlike negative local ids,
        // are not redrawn from a counter logout resets. The generation guard covers the other post-logout
        // continuation, finishCommitEdit's.
        DURABLE_INFLIGHT.remove(account);
        warmedAccounts &= ~accountBit;
        // Rebuild pendingAccounts off the surviving PENDING map (clears this slot's bit) and reschedule GC.
        refreshPendingBits();
        schedulePendingGc();
    }

    private static QueueState liveQueueState(String queueKey, int token) {
        QueueState queueState = QUEUES.get(queueKey);
        return queueState != null && queueState.token == token ? queueState : null;
    }

    private static void fire(int account, EventScheduleEntry entry, String expectedQueueKey, int token, long revision) {
        // The delay window may have outlived the entry (edited, deleted, or the fallback already fired).
        QueueState queueState = liveQueueState(expectedQueueKey, token);
        if (queueState == null) {
            return;
        }
        ArrayList<EventScheduleEntry> queue = queueState.entries;
        if (queue.isEmpty()) {
            return;
        }
        if (entry.state != EventScheduleEntry.STATE_WAITING || entry.revision != revision
                || !EventScheduleStore.contains(account, entry.key())) {
            // Token still matches, so this callback is the live queue owner and must re-drive.
            advanceQueue(account, expectedQueueKey);
            return;
        }
        if (queue.get(0) != entry) {
            // Same token means this callback is still the live queue driver, so hand off to the new head.
            advanceQueue(account, expectedQueueKey);
            return;
        }
        if (entry.serverIds.isEmpty()) {
            entry.state = EventScheduleEntry.STATE_ARMED;
            advanceQueue(account, expectedQueueKey);
            return;
        }
        if (isSuppressed(account, entry.key())) {
            // A bulk arm (issue #249) suppressed this trigger after it reached the queue head. Don't issue
            // the send: drop back to ARMED (the map still holds it, so it can't be re-queued until the run
            // releases it) and re-drive so the queue doesn't stall on a suppressed head.
            entry.state = EventScheduleEntry.STATE_ARMED;
            advanceQueue(account, expectedQueueKey);
            return;
        }
        entry.state = EventScheduleEntry.STATE_SENDING;
        sendHeadRequest(account, entry, expectedQueueKey, token, entry.revision);
    }

    private static void retryHeadSend(int account, EventScheduleEntry entry, String expectedQueueKey, int token, long sendRevision) {
        QueueState queueState = liveQueueState(expectedQueueKey, token);
        if (queueState == null) {
            if (entry.revision == sendRevision && entry.state == EventScheduleEntry.STATE_SENDING
                    && EventScheduleStore.contains(account, entry.key())) {
                entry.state = EventScheduleEntry.STATE_ARMED;
            }
            return;
        }
        ArrayList<EventScheduleEntry> queue = queueState.entries;
        if (queue.isEmpty()) {
            return;
        }
        if (entry.state != EventScheduleEntry.STATE_SENDING || entry.revision != sendRevision
                || !EventScheduleStore.contains(account, entry.key())) {
            // Token still matches, so this callback is still the queue owner and must re-drive.
            advanceQueue(account, expectedQueueKey);
            return;
        }
        if (queue.get(0) != entry) {
            // Same token means the queue is alive; hand off to the live head.
            entry.state = EventScheduleEntry.STATE_ARMED;
            advanceQueue(account, expectedQueueKey);
            return;
        }
        sendHeadRequest(account, entry, expectedQueueKey, token, sendRevision);
    }

    private static void sendHeadRequest(int account, EventScheduleEntry entry, String expectedQueueKey, int token, long sendRevision) {
        entry.state = EventScheduleEntry.STATE_SENDING;
        final long dialogId = entry.dialogId;
        final TLRPC.TL_messages_sendScheduledMessages req = new TLRPC.TL_messages_sendScheduledMessages();
        req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
        req.id.addAll(entry.serverIds);
        // Scheduled-delete channelId is channel-only (matches MessagesController's own scheduled post):
        // passing -dialogId for a DM or basic group makes ChatActivity.processDeletedMessages skip the
        // removal, leaving the message stuck in the scheduled view until reload.
        final long channelId = DialogObject.isChatDialog(dialogId)
                && ChatObject.isChannel(MessagesController.getInstance(account).getChat(-dialogId)) ? -dialogId : 0L;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error == null) {
                // Deliberately off the UI thread: tgnet invokes this callback on its own thread, and
                // these two calls mirror that existing threading contract before hopping to UI work.
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                for (int i = 0; i < req.id.size(); i++) {
                    AyuState.permitDeleteMessage(account, dialogId, req.id.get(i), true);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    boolean wasArmed = EventScheduleStore.contains(account, entry.key());
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.messagesDeleted, req.id, channelId, true, true);
                    if (wasArmed) {
                        // An edit can change the revision while this request is in flight, but the
                        // same scheduled IDs are still no longer eligible for another trigger.
                        EventScheduleStore.remove(account, entry);
                        // Local heads-up: the trigger fired while the user may not be looking at the chat.
                        EventScheduleNotifier.notifySent(account, dialogId);
                    }
                });
            } else {
                final int errorCode = error.code;
                final String errorText = error.text;
                AndroidUtilities.runOnUIThread(() ->
                        onSendError(account, entry, expectedQueueKey, token, sendRevision, dialogId, errorCode, errorText));
            }
        });
    }

    private static void onSendError(int account, EventScheduleEntry entry, String expectedQueueKey, int token,
                                    long sendRevision, long dialogId, int errorCode, String errorText) {
        QueueState queueState = liveQueueState(expectedQueueKey, token);
        boolean currentAttempt = entry.revision == sendRevision
                && entry.state == EventScheduleEntry.STATE_SENDING
                && EventScheduleStore.contains(account, entry.key());
        boolean retryableWait = errorCode >= 0 && isRetryableWaitError(errorText);
        int waitSeconds = retryableWait ? Utilities.parseInt(errorText) : 0;
        if (retryableWait && waitSeconds > 0 && waitSeconds <= MAX_RETRY_WAIT_SECONDS
                && queueState != null && queueState.retriesLeft > 0
                && currentAttempt) {
            queueState.retriesLeft--;
            AndroidUtilities.runOnUIThread(
                    () -> retryHeadSend(account, entry, expectedQueueKey, token, sendRevision),
                    (waitSeconds + 1) * 1000L
            );
            return;
        }

        if (errorCode >= 0 && isDropError(errorText)) {
            if (currentAttempt) {
                EventScheduleStore.remove(account, entry);
            }
            return;
        }

        if (!currentAttempt) {
            return;
        }
        entry.state = EventScheduleEntry.STATE_ARMED;
        if (queueState == null) {
            return;
        }
        advanceQueue(account, expectedQueueKey);
        // This branch re-arms a failed head, so at least that entry is still unsent.
        EventScheduleNotifier.notifyBatchStalled(account, dialogId);
    }

    private static boolean isRetryableWaitError(String errorText) {
        return errorText != null && (errorText.startsWith("SLOWMODE_WAIT_") || errorText.startsWith("FLOOD_WAIT_"));
    }

    private static boolean isDropError(String errorText) {
        return "MESSAGE_ID_INVALID".equals(errorText)
                || "MESSAGE_IDS_EMPTY".equals(errorText)
                || "PEER_ID_INVALID".equals(errorText)
                || "CHANNEL_INVALID".equals(errorText)
                || "CHANNEL_PRIVATE".equals(errorText)
                || "INPUT_USER_DEACTIVATED".equals(errorText);
    }
}
