package com.dazewell.gram.eventschedule;

import android.util.SparseIntArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dazewell.gram.reschedule.RescheduleSpreadExecutor;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Arms one shared event-schedule trigger across a bulk reschedule, using deferred atomic
 * activation: nothing is armed while the reschedule is in flight. At finalization the dialog's unbound
 * durable orphans are reconciled first -- a step that MAY yield to a storage hop -- and only once that
 * reconcile completes is the whole selection published in a single UI-thread turn. So the safety
 * argument is deliberately NOT "one synchronous turn": it rests on the admission suppression, the
 * deletion collector, the run's (account, dialogId) generation and each target's owner revision and
 * scheduleRevision all spanning that hop -- see finalizeOnUi for the full statement. Publication is
 * conditional: if an unbound durable orphan survives that reconcile the whole selection is declined --
 * dialog-scoped by definition, so transient and retryable rather than a per-target failure -- see
 * finalizeAfterReconcile for why the decline spans the dialog. Progressive arming is deliberately
 * avoided -- a trigger firing mid-run would enrol only the armed prefix and leave the rest needing a
 * second key phrase, which is the exact problem this feature exists to remove.
 *
 * <p>One instance per run. The bulk-reschedule handler builds it on the UI thread with the shared
 * trigger config, the dialog, and the full album child-id set of every selected message, then hands
 * it to {@link RescheduleSpreadExecutor#run} as its
 * {@link RescheduleSpreadExecutor.TriggerArmingHooks}.
 */
public final class EventScheduleBulkArmer implements RescheduleSpreadExecutor.TriggerArmingHooks {

    /**
     * Reveals the bolt on the armed rows at finalization. The armer never touches the fragment itself --
     * the visible-rows refresh it needs is a private ChatActivity overload -- so the fragment hands in a
     * lambda that carries the armed message ids and forces a re-measure of just those rows. Called once,
     * on the UI thread, only when the fragment is still alive and at least one target armed.
     */
    public interface TriggerRefresh {
        void revealArmed(@NonNull Set<Integer> armedServerIds);
    }

    /**
     * Parallel server/local id arrays for one selected message (or its whole album), captured on the UI
     * thread while the fragment's group maps are valid. Both spaces are carried because ownership is
     * resolved across both: a positive serverId (bound) or the negative local_id echo of a still-pending
     * arm. The fragment captures these raw; all trigger policy (readiness, armed count, ownership) lives
     * here in the fork-owned armer, not in the base file.
     */
    public static final class AlbumIdentity {
        public final int[] serverIds;
        public final int[] localIds;

        public AlbumIdentity(@NonNull int[] serverIds, @NonNull int[] localIds) {
            this.serverIds = serverIds;
            this.localIds = localIds;
        }

        /**
         * Builds the identity from a resolved message and its group -- {@code group} is null for a
         * non-grouped message or when the fragment's group map has no entry for it. Captures both id
         * spaces for every album child (a non-grouped message yields just its own server/local id). The
         * array allocation and per-child extraction live here, off the base file, so ChatActivity only
         * does the grouped-map lookup and hands the two objects over.
         */
        public static AlbumIdentity of(@NonNull MessageObject representative, @Nullable MessageObject.GroupedMessages group) {
            if (group != null && !group.messages.isEmpty()) {
                int n = group.messages.size();
                int[] serverIds = new int[n];
                int[] localIds = new int[n];
                for (int k = 0; k < n; k++) {
                    serverIds[k] = group.messages.get(k).getId();
                    localIds[k] = group.messages.get(k).messageOwner.local_id;
                }
                return new AlbumIdentity(serverIds, localIds);
            }
            return new AlbumIdentity(new int[]{representative.getId()}, new int[]{representative.messageOwner.local_id});
        }
    }

    /**
     * True only when every selected message (album members included) is already server-addressable. A
     * still-sending child has a non-positive server id; arming against it would bind the shared trigger
     * to an id the server never issued (the #256 defect class), so any non-positive member refuses the
     * chip for the whole selection. Policy kept here, off the base file, so the sheet only captures ids.
     */
    public static boolean selectionReady(@NonNull List<AlbumIdentity> selection) {
        for (AlbumIdentity a : selection) {
            for (int id : a.serverIds) {
                if (id <= 0) return false;
            }
        }
        return true;
    }

    /**
     * How many selected items already carry a trigger, so the sheet can warn that turning the chip on
     * overwrites them. Resolved across both id spaces (the same exact-id scan the arm path uses) so a
     * still-pending owner is counted too; in-memory over the store, no server round-trip.
     */
    public static int armedCount(int account, long dialogId, @NonNull List<AlbumIdentity> selection) {
        int armed = 0;
        for (AlbumIdentity a : selection) {
            if (!EventScheduleStore.resolveOwnerKeysForEdit(account, dialogId, a.serverIds, a.localIds).isEmpty()) {
                armed++;
            }
        }
        return armed;
    }

    private final int account;
    private final long dialogId;
    private final EventScheduleConfig config;
    // Full server/local album identity per selected message, snapshotted at build time (never
    // re-expanded from a live fragment). Used to resolve and suppress pre-existing owners across both id
    // spaces at admission and to seed the createdAt sequence above anything already persisted.
    private final List<AlbumIdentity> selection;
    // Reveals the bolt on the armed rows at finalization (nullable: an armer built without a fragment,
    // e.g. in a test, simply skips the refresh).
    @Nullable
    private final TriggerRefresh refresh;

    // Keys of the pre-existing entries this run is holding back. A set, not a map: finalization drains
    // these keys to release every hold this run placed -- driven by what we suppressed, not by what
    // survived -- so a target that later dropped out of the outcomes still gets released. (Revision
    // baselines for the later-edit-wins check live per album in admissionByAlbum, not here.) Nothing
    // durable is removed at admission: suppression is process-local, so a process death discards it and
    // the durable entry reloads armed, where a durable remove with only an in-memory copy would lose the
    // trigger.
    private final HashSet<String> suppressed = new HashSet<>();

    // C2: each album's ownership state as seen at admission, keyed by the album's canonical server-id
    // key (see albumKey): NONE / SINGLE(key+revision+scheduleRevision) / MULTI. Re-checked immediately
    // before arming and rejected on any change -- see ownershipUnchangedForArm.
    private final HashMap<String, AdmissionOwnership> admissionByAlbum = new HashMap<>();
    // Parallel local ids per album (same canonical key), so finalization can re-resolve ownership across
    // BOTH id spaces from an outcome that carries only server ids.
    private final HashMap<String, int[]> localIdsByAlbum = new HashMap<>();

    // Server ids of entries already SENDING at admission. An issued early send can't be recalled and
    // suppression can't hold it back, so its target is treated as linearized before the run and rejected
    // in verification rather than re-armed (I6).
    private final HashSet<Integer> sendingAtAdmissionIds = new HashSet<>();

    // Exact scheduled ids deleted while the run was in flight, collected on the UI thread. An entry
    // only survives finalization if none of its ids appear here (C2): a deletion can land after the
    // server snapshot, or before it but with its UI notification arriving before finalization.
    private final HashSet<Integer> deletedDuringRun = new HashSet<>();
    private boolean collecting;

    // Store logout generation for this run's slot, captured when the Row was built (before the schedule
    // picker, and before the >50-item confirm dialog this armer's admission runs on the far side of) and
    // carried in through the constructor. onAdmission compares it against the live generation and refuses a
    // stale run; a survivor armed at finalization also carries it, so a claim completing after a logout that
    // cleared and possibly reused the slot is rejected by the store rather than persisted into it. UI thread
    // only (admission and finalization), so it needs no synchronization of its own.
    private final int storeGeneration;

    // Set in onAdmission when the carried construction-time generation no longer matches the live slot: a
    // logout straddled the >50-item confirm dialog. A stale run registers no observer, suppresses nothing,
    // and arms nothing -- finalize folds it into failClosed and skips the reconcile storage hop entirely.
    private boolean staleSlot;

    // Built in the constructor, not as a field initializer, so its capture of account/dialogId reads
    // fields that are already assigned (a field initializer would run before the constructor body).
    private final NotificationCenter.NotificationCenterDelegate deletionCollector;

    public EventScheduleBulkArmer(int account, long dialogId, int intentGeneration, @NonNull EventScheduleConfig config, @NonNull List<AlbumIdentity> selection, @Nullable TriggerRefresh refresh) {
        this.account = account;
        this.dialogId = dialogId;
        this.storeGeneration = intentGeneration;
        this.config = config;
        this.selection = selection;
        this.refresh = refresh;
        this.deletionCollector = (id, acc, args) -> {
            if (id != NotificationCenter.messagesDeleted || acc != account) return;
            boolean scheduled = args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2];
            if (!scheduled) return;
            long channelId = args.length > 1 && args[1] instanceof Long ? (Long) args[1] : 0L;
            // Scheduled-delete channelId is channel-only; mirror EventScheduleStore.purgeIds' filter so
            // a delete in another chat can't invalidate this run.
            if (channelId != 0 && dialogId != -channelId) return;
            Object raw = args.length > 0 ? args[0] : null;
            if (!(raw instanceof ArrayList)) return;
            for (Object o : (ArrayList<?>) raw) {
                if (o instanceof Integer) deletedDuringRun.add((Integer) o);
            }
        };
    }

    @Override
    public void onAdmission() {
        // storeGeneration is the token captured when the Row was built, carried here through snapshot() ->
        // the reschedule delegate -> the constructor. Compare it against live BEFORE touching any state: the
        // >50-item confirm dialog is a bare AlertDialog the logout fragment swap never dismisses, so a logout
        // can land while it is up and this run's intent then predates a slot clear (and maybe a reuse). On a
        // mismatch, refuse the whole run here -- register no observer (so collecting stays false and nothing
        // leaks) and suppress nothing (so the new occupant's live triggers are never held back); finalize's
        // failClosed then surfaces the existing fail-closed bulletin, nothing new.
        if (EventScheduleStore.currentGeneration(account) != storeGeneration) {
            staleSlot = true;
            return;
        }
        EventScheduleStore.ensureLoaded(account);
        NotificationCenter.getInstance(account).addObserver(deletionCollector, NotificationCenter.messagesDeleted);
        collecting = true;
        for (AlbumIdentity album : selection) {
            String ak = albumKey(album.serverIds);
            localIdsByAlbum.put(ak, album.localIds);
            // Resolve EVERY owner of this album across BOTH id spaces (C1): a positive serverId (bound) or
            // the negative local_id echo of a still-pending arm. Matching only server ids -- as an earlier
            // draft did by passing null local ids -- misses a pending owner and lets a second durable
            // trigger be armed beside it, or leaves one owner of a multi-owner target unsuppressed and able
            // to fire mid-run.
            ArrayList<String> ownerKeys = EventScheduleStore.resolveOwnerKeysForEdit(account, dialogId, album.serverIds, album.localIds);
            ArrayList<EventScheduleEntry> owners = new ArrayList<>();
            for (String key : ownerKeys) {
                EventScheduleEntry live = EventScheduleStore.findByKey(account, key);
                if (live != null && !owners.contains(live)) owners.add(live);
            }
            for (EventScheduleEntry live : owners) {
                String key = live.key();
                if (suppressed.contains(key)) continue;
                // Hold the pre-existing trigger back for the run without touching durable state: it can't
                // fire mid-run and then read back missing during verification, but nothing is removed, so
                // a process death before finalization loses nothing (the entry reloads armed). A SENDING
                // entry can't be held back -- its send is already issued -- so record its ids instead, and
                // verification rejects that target rather than assuming the old trigger was suspended.
                if (EventScheduleController.suppressForBulk(account, live, this)) {
                    suppressed.add(key);
                } else {
                    sendingAtAdmissionIds.addAll(live.serverIds);
                }
            }
            // Classify this album's ownership PER CHILD via classifyAlbumOwnership -- do NOT derive it
            // from the aggregate `owners` list resolved just above for suppression. That list is a
            // collapsed set, and a distinct-owner count over it can't tell a fully-owned album from a
            // partially-owned one, which is exactly the mixture that must reject (why: classifyAlbumOwnership).
            admissionByAlbum.put(ak, classifyAlbumOwnership(album.serverIds, album.localIds));
        }
    }

    @Override
    public void onFinalize(List<RescheduleSpreadExecutor.TargetOutcome> outcomes, int[] scheduledIds, int[] scheduledDates,
                           boolean authoritative, RescheduleSpreadExecutor.RunGeneration generation, int rescheduleWrong, int rescheduleTotal, BaseFragment fragment) {
        AndroidUtilities.runOnUIThread(() ->
                finalizeOnUi(outcomes, scheduledIds, scheduledDates, authoritative, generation, rescheduleWrong, rescheduleTotal, fragment));
    }

    private void finalizeOnUi(List<RescheduleSpreadExecutor.TargetOutcome> outcomes, int[] scheduledIds, int[] scheduledDates,
                              boolean authoritative, RescheduleSpreadExecutor.RunGeneration generation, int rescheduleWrong, int rescheduleTotal, BaseFragment fragment) {
        if (staleSlot) {
            // onAdmission refused this run: the slot was logged out during the >50-item confirm dialog, so no
            // observer was registered and nothing was suppressed. There is no durable state to reconcile for a
            // departed (and maybe reused) slot, so skip the reconcile storage hop -- which would otherwise
            // snapshot the new occupant's entries -- and finalize directly. failClosed arms nothing and its
            // finally still releases this run's generation, so a later run for the dialog isn't compared
            // against a stale claim.
            finalizeAfterReconcile(outcomes, scheduledIds, scheduledDates, authoritative, generation, rescheduleWrong, rescheduleTotal, fragment);
            return;
        }
        // Safety argument for the deferred activation. Finalization MAY yield -- reconcileDialogThen below
        // does a storage hop whenever this dialog has unbound orphans, and after a HEAL that clears the
        // unbound condition the dialog gate is no longer up, so arming proceeds after that yield. So the
        // old "everything runs in one synchronous UI turn" premise does not hold and must not be assumed.
        // Atomicity instead rests on three things spanning the hop: the admission suppression stays in
        // place (nothing durable was removed), the deletion collector stays registered (a delete during
        // the hop is still recorded and still rejects its target), and -- rechecked in
        // finalizeAfterReconcile immediately after the reconcile and immediately before each mutation --
        // the run's (account, dialogId) generation (an overtaken run fails closed) and each target's exact
        // owner key + revision + scheduleRevision (a later single-message edit -- a trigger change or a
        // schedule-time move -- wins). Reconcile this dialog's unbound durable
        // orphans to current server ids first, then finalize; reconcileDialogThen always runs its callback
        // on the UI thread (synchronously when there are no orphans, the common case).
        EventScheduleController.reconcileDialogThen(account, dialogId, () ->
                finalizeAfterReconcile(outcomes, scheduledIds, scheduledDates, authoritative, generation, rescheduleWrong, rescheduleTotal, fragment));
    }

    private void finalizeAfterReconcile(List<RescheduleSpreadExecutor.TargetOutcome> outcomes, int[] scheduledIds, int[] scheduledDates,
                                        boolean authoritative, RescheduleSpreadExecutor.RunGeneration generation, int rescheduleWrong, int rescheduleTotal, BaseFragment fragment) {
        // C2 collector lifetime: removal happens here, before the fragment guard below, and this method
        // always runs. The executor's closing scheduled-history read always calls onFinalize (a tgnet
        // request always calls back, on success or error), onFinalize hops to finalizeOnUi with
        // runOnUIThread, and the reconcile hop above always runs its callback here regardless of fragment
        // state, so removal is not gated on the run "succeeding" or on the fragment being alive. A
        // backgrounded process still runs the callback and removes the observer; a killed process drops
        // the in-memory observer with everything else. The `collecting` flag makes the removal idempotent.
        // Each run owns its own armer instance and its own collector lambda, so this only ever unregisters
        // this run's observer, never a concurrent run's.
        if (collecting) {
            NotificationCenter.getInstance(account).removeObserver(deletionCollector, NotificationCenter.messagesDeleted);
            collecting = false;
        }

        final SparseIntArray serverDates = new SparseIntArray(scheduledIds.length);
        for (int i = 0; i < scheduledIds.length; i++) {
            serverDates.put(scheduledIds[i], scheduledDates[i]);
        }

        // NagramX: the run's (account, dialogId) generation is compared HERE -- immediately after the
        // reconcile hop and immediately before any mutation -- not frozen early in the executor. It exists
        // so an overtaken run fails closed: every reschedule run (plain or trigger-enabled) advances the
        // generation at admission, and if a later run advanced it past ours it moved the server dates this
        // run is about to arm against, so we must arm nothing. A plain run only ever advances it and never
        // reads it, so plain reschedule behaviour is unchanged. This comparison MUST stay right before the
        // arm loop -- that position is the fix (a run admitted after this point runs on the same UI thread
        // in the same callback, so it cannot interleave before we arm); moving it earlier reopens the
        // window it closes. The counter is written in RescheduleSpreadExecutor.start(); do not treat that
        // write as dead just because it looks unread there.
        final boolean superseded = generation.superseded();
        // Fail closed on supersession (a later run moved these dates under us), on a failed authoritative
        // read (we can't trust membership), or on a stale slot (onAdmission saw a logout straddle the confirm
        // dialog). Arm nothing new; the finally below still releases every hold this run placed -- none on the
        // stale path, since onAdmission suppressed nothing -- so the pre-existing triggers survive untouched.
        final boolean failClosed = superseded || !authoritative || staleSlot;

        // Dialog-wide decline: an unbound durable orphan that survived the reconcile above still sits in
        // this chat, and a bulk selection is dialog-scoped by definition, so it declines the WHOLE
        // selection -- nothing armed. This is a distinct outcome from every per-target not-armed reason
        // (which are permanent or message-specific): it is transient, retryable and dialog-wide, so it
        // carries its own bulletin line telling the user to try again shortly. Reporting "3 of 8 weren't
        // armed" when the truth is "none were, try again in a moment" is the exact UI-asserts-something-
        // -untrue failure this family of changes exists to prevent. See EventScheduleController's
        // single-message twin (finishCommitEdit) for why the block is dialog-wide and cannot narrow.
        final boolean dialogGate = !failClosed && EventScheduleStore.hasUnboundRandomEntry(account, dialogId);

        int armed = 0;
        int notArmed = 0;
        final HashSet<Integer> armedIds = new HashSet<>();
        try {
            if (!failClosed && !dialogGate) {
                // One batch-local createdAt sequence in milliseconds (createdAt is millis everywhere:
                // EventScheduleHelper assigns System.currentTimeMillis(), key() and QUEUE_ORDER both read
                // it), seeded above every persisted key so a freshly claimed entry can't collide.
                long nextCreatedAt = Math.max(System.currentTimeMillis(), maxCreatedAt() + 1);
                for (RescheduleSpreadExecutor.TargetOutcome o : outcomes) {
                    if (!o.applied) {
                        notArmed++;
                        continue;
                    }
                    // A repeating scheduled message can't carry a trigger -- the single-message path
                    // refuses it (armed = enabled && repeatPeriod == 0), because Premium repeat and
                    // early-trigger don't compose. The bulk path must not create a state the single path
                    // forbids, so a repeating target is left as a plain reschedule and counted not-armed.
                    if (o.repeatPeriod != 0) {
                        notArmed++;
                        continue;
                    }
                    // Every album child must be present at the applied date and none deleted mid-run; one
                    // missing child rejects the whole album target. A target whose old trigger was already
                    // SENDING at admission was linearized before the run -- its early send can't be
                    // recalled -- so it is rejected here rather than re-armed (I6).
                    if (!allScheduledAt(o.albumIds, o.scheduleDate, serverDates) || anyDeleted(o.albumIds)
                            || anySendingAtAdmission(o.albumIds)) {
                        notArmed++;
                        continue;
                    }
                    // A later single-message edit wins over this in-flight bulk arm (C2/C4). Re-resolve
                    // this album's ownership across both id spaces and require it to be EXACTLY what
                    // admission saw: still ownerless, or still the same single owner at the same revision
                    // and scheduleRevision. An owner created since admission (there was none, now there is),
                    // removed (turned off), replaced, re-authored (revision bumped), or whose schedule time
                    // moved (scheduleRevision bumped by an untouched-trigger edit) means the user acted
                    // mid-run, so yield rather than overwrite. A target that was multi-owned at admission is
                    // rejected outright here.
                    if (!ownershipUnchangedForArm(o.albumIds)) {
                        notArmed++;
                        continue;
                    }
                    EventScheduleEntry entry = buildEntry(o.albumIds, o.scheduleDate, nextCreatedAt++);
                    // armSurvivor merges the shared trigger into an existing owner in place, or claims a
                    // fresh entry when there is none -- it never removes an old entry, because on the
                    // merge path the "old" entry IS the survivor being armed. Ownership is resolved at
                    // commit across both id spaces over the full album child-id set, so pass this album's
                    // local ids too. A contested id rejects and the target is counted not-armed. Consume
                    // the result either way.
                    if (armSurvivor(entry, localIdsByAlbum.get(albumKey(o.albumIds)))) {
                        armed++;
                        // Reveal the bolt on exactly the rows that armed. albumIds are the message ids.
                        for (int id : o.albumIds) armedIds.add(id);
                    } else {
                        notArmed++;
                    }
                }
            }
        } finally {
            // I5: release every hold this run placed, unconditionally and driven by what we suppressed
            // (not by what survived), so a target dropped from the outcomes -- album collapse, dedup, a
            // per-target error, a fail-closed run, or the dialog-wide gate declining everything -- is
            // still released. Releasing is the safe direction: a wrongly-released trigger re-arms and
            // fires per its config, whereas a wrongly-retained hold silently never fires for the process
            // lifetime, the same invisible loss this redesign exists to prevent. A merged survivor keeps
            // its original key, so its hold is released here too -- correct, since the merged entry should
            // fire. armSurvivor already released the survivors it armed; this double release is a no-op.
            for (String key : suppressed) {
                EventScheduleController.releaseSuppression(account, key, this);
            }
            // NagramX: release this run's (account, dialogId) generation, unconditionally and exactly
            // once. It was held from admission through the reconcile hop so the superseded() check above
            // is meaningful; release it here whether we armed, failed closed, or the dialog gate declined
            // everything, so a later run for this dialog isn't compared against a stale claim.
            generation.release();
        }

        // Suppression release above is unconditional (I3/I5); only the user-facing refresh and bulletin
        // depend on the fragment still being alive.
        if (fragment != null && fragment.getParentActivity() != null) {
            // Bolt reveals only at finalization -- nothing is live until now, so this is the first honest
            // render. Refresh only the armed rows (forcing a re-measure so the bolt actually draws); skip
            // it entirely when nothing armed so no untouched row is needlessly rebuilt.
            if (refresh != null && !armedIds.isEmpty()) {
                refresh.revealArmed(armedIds);
            }
            showBulletin(fragment, failClosed, dialogGate, armed, notArmed, rescheduleWrong, rescheduleTotal);
        }
    }

    /**
     * The atomic, ownership-enforcing create-and-persist-with-ownership-check for one armed target. On an
     * existing owner of these ids it merges the shared trigger into that entry in place (never removing
     * it -- on the merge path the existing entry IS the survivor being armed, so a remove would delete
     * what was just armed); with no owner it claims a fresh entry; a contested id (multi-owner or an
     * empty/non-positive id set) rejects and the target is reported not armed. Ownership is resolved at
     * commit by exact identity across both id spaces over the full album child-id set, so no half-owned
     * entry is ever written and the caller still counts the outcome. Returns true only when the trigger is
     * now durably armed on the target.
     */
    private boolean armSurvivor(@NonNull EventScheduleEntry entry, @Nullable int[] negativeLocalIds) {
        String resolvedKey = EventScheduleController.bulkArmSurvivor(account, storeGeneration, dialogId, entry, negativeLocalIds);
        if (resolvedKey == null) {
            return false;
        }
        // Release this run's hold on the resolved entry so it can fire per its config now that it is the
        // live shared trigger. On a merge the resolved key is the existing owner's -- an in-place merge
        // leaves createdAt, and thus key(), unchanged -- which is exactly the key suppressed at admission,
        // so this drops the right hold. A fresh claim was never suppressed, so it is a harmless no-op
        // there. Idempotent, and the finalize finally-block releases every held key again, so the double
        // release is safe.
        EventScheduleController.releaseSuppression(account, resolvedKey, this);
        return true;
    }

    // C2: is this album's ownership still exactly what admission recorded? Re-runs the SAME per-child
    // classification (classifyAlbumOwnership) and compares to the admission snapshot. A NONE album must
    // still have every child ownerless; a SINGLE must still be every child the same owner key at the same
    // revision AND the same scheduleRevision; a MULTI (or an album with no admission snapshot) is never
    // armed. Any owner created, removed, replaced, re-authored (revision bumped), or whose schedule time
    // moved (scheduleRevision bumped) since admission, or a child that fell out of a formerly unanimous
    // owner, is a later single-message edit that wins over this bulk arm.
    //
    // Both revisions are process-local and reset to 0 on restart, so a capture from before a process
    // death would compare equal to one after it -- a false match. That cannot happen here: this
    // comparison and the admission capture both run within one run's lifetime, and the run cannot span a
    // process death because the armer instance, its suppression holds and the run generation are all
    // process-local and vanish with the process. A reader who moves either capture outside that single
    // run, or persists an entry between them, breaks this and must restore a persisted comparison key.
    private boolean ownershipUnchangedForArm(int[] albumServerIds) {
        AdmissionOwnership snap = admissionByAlbum.get(albumKey(albumServerIds));
        if (snap == null) return false;
        int[] localIds = localIdsByAlbum.get(albumKey(albumServerIds));
        AdmissionOwnership now = classifyAlbumOwnership(albumServerIds, localIds);
        switch (snap.kind) {
            case NONE:
                return now.kind == EventScheduleStore.EditOwner.NONE;
            case SINGLE:
                return now.kind == EventScheduleStore.EditOwner.SINGLE
                        && snap.ownerKey.equals(now.ownerKey)
                        && snap.ownerRevision == now.ownerRevision
                        && snap.ownerScheduleRevision == now.ownerScheduleRevision;
            default:
                return false;
        }
    }

    // Per-child ownership classification (C1). The rule -- every child ownerless, or every child the
    // same single owner -- must hold for EVERY album child, so the expression iterates the children
    // rather than counting a collapsed set: a distinct-owner count over the whole album can't answer it,
    // because an unowned child adds nothing to that count and so is invisible to the check meant to catch
    // it. Every child NONE -> NONE (a fresh create is safe); every child the same single owner -> SINGLE
    // (an in-place merge over the full set adds nothing, because that owner already holds every child).
    // Any owned/unowned mixture, differing owners across children, or a per-child multi-owner conflict
    // rejects the whole target -> MULTI. Each child is resolved by exact id across BOTH id spaces (the
    // arm path's scan), so a still-pending owner reachable only by its negative local_id counts too.
    private AdmissionOwnership classifyAlbumOwnership(int[] serverIds, @Nullable int[] localIds) {
        String commonKey = null;
        long commonRevision = 0;
        long commonScheduleRevision = 0;
        boolean anyOwned = false;
        boolean anyNone = false;
        for (int i = 0; i < serverIds.length; i++) {
            int[] childServer = {serverIds[i]};
            int[] childLocal = (localIds != null && i < localIds.length) ? new int[]{localIds[i]} : null;
            EventScheduleStore.OwnerSeed seed = EventScheduleStore.resolveOwnerSeedForEdit(account, dialogId, childServer, childLocal);
            if (seed.kind == EventScheduleStore.EditOwner.MULTI) {
                return new AdmissionOwnership(EventScheduleStore.EditOwner.MULTI, null, 0, 0);
            }
            if (seed.kind == EventScheduleStore.EditOwner.NONE) {
                anyNone = true;
                continue;
            }
            anyOwned = true;
            String key = seed.entry.key();
            if (commonKey == null) {
                commonKey = key;
                commonRevision = seed.entry.revision;
                commonScheduleRevision = seed.entry.scheduleRevision;
            } else if (!commonKey.equals(key)) {
                return new AdmissionOwnership(EventScheduleStore.EditOwner.MULTI, null, 0, 0);
            }
        }
        if (anyOwned && anyNone) {
            // Partial ownership -- some children owned, some not. Rejecting here is what stops the
            // commit-time merge from annexing the unowned children into the partial owner.
            return new AdmissionOwnership(EventScheduleStore.EditOwner.MULTI, null, 0, 0);
        }
        if (!anyOwned) {
            return new AdmissionOwnership(EventScheduleStore.EditOwner.NONE, null, 0, 0);
        }
        return new AdmissionOwnership(EventScheduleStore.EditOwner.SINGLE, commonKey, commonRevision, commonScheduleRevision);
    }

    // Canonical key for an album, order-independent, so a finalization outcome (which carries only
    // server ids) joins back to the admission snapshot and local-id map regardless of child order.
    private static String albumKey(int[] serverIds) {
        int[] sorted = serverIds.clone();
        Arrays.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (int id : sorted) sb.append(id).append(',');
        return sb.toString();
    }

    // One album's ownership as seen at admission (C2). SINGLE carries the exact owner key, revision and
    // scheduleRevision; NONE and MULTI carry none of them.
    private static final class AdmissionOwnership {
        final EventScheduleStore.EditOwner kind;
        final String ownerKey;
        final long ownerRevision;
        final long ownerScheduleRevision;

        AdmissionOwnership(EventScheduleStore.EditOwner kind, String ownerKey, long ownerRevision, long ownerScheduleRevision) {
            this.kind = kind;
            this.ownerKey = ownerKey;
            this.ownerRevision = ownerRevision;
            this.ownerScheduleRevision = ownerScheduleRevision;
        }
    }

    private EventScheduleEntry buildEntry(int[] albumIds, int scheduleDate, long createdAt) {
        EventScheduleEntry e = new EventScheduleEntry();
        e.dialogId = dialogId;
        for (int id : albumIds) e.serverIds.add(id);
        e.types = config.types;
        e.setPatterns(config.patterns);
        e.regex = config.regex;
        e.delaySeconds = config.delaySeconds;
        e.fallbackDate = scheduleDate;
        e.createdAt = createdAt;
        e.bindGroupedId = 0;
        e.bindExpiresAt = 0;
        e.state = EventScheduleEntry.STATE_ARMED;
        return e;
    }

    private long maxCreatedAt() {
        long max = 0;
        for (EventScheduleEntry e : EventScheduleStore.forDialog(account, dialogId)) {
            if (e.createdAt > max) max = e.createdAt;
        }
        return max;
    }

    private boolean anySendingAtAdmission(int[] albumIds) {
        for (int id : albumIds) {
            if (sendingAtAdmissionIds.contains(id)) return true;
        }
        return false;
    }

    private boolean allScheduledAt(int[] albumIds, int scheduleDate, SparseIntArray serverDates) {
        for (int id : albumIds) {
            if (serverDates.get(id, -1) != scheduleDate) return false;
        }
        return true;
    }

    private boolean anyDeleted(int[] albumIds) {
        for (int id : albumIds) {
            if (deletedDuringRun.contains(id)) return true;
        }
        return false;
    }

    private void showBulletin(BaseFragment fragment, boolean failClosed, boolean dialogGate, int armed, int notArmed, int rescheduleWrong, int rescheduleTotal) {
        final String base = rescheduleWrong == 0
                ? LocaleController.formatPluralString("RescheduleApplied", rescheduleTotal)
                : LocaleController.formatString(R.string.RescheduleVerifyFailed, rescheduleWrong, rescheduleTotal);
        final String triggerLine;
        if (dialogGate) {
            // Transient, retryable, dialog-wide -- distinct from every other not-armed reason. The whole
            // selection was declined because the chat is still confirming an earlier trigger; nothing
            // armed, so the wording tells the user to try again shortly rather than implying a partial.
            triggerLine = LocaleController.getString(R.string.EventScheduleBulkTriggerDialogBusy);
        } else if (failClosed) {
            triggerLine = LocaleController.getString(R.string.EventScheduleBulkTriggerNotApplied);
        } else if (notArmed > 0) {
            triggerLine = LocaleController.formatString(R.string.EventScheduleBulkTriggerPartial, armed, armed + notArmed);
        } else {
            triggerLine = LocaleController.formatPluralString("EventScheduleBulkTriggerArmed", armed);
        }
        final String message = base + "\n" + triggerLine;
        final int icon = (rescheduleWrong == 0 && !failClosed && !dialogGate && notArmed == 0) ? R.raw.chats_infotip : R.raw.error;
        BulletinFactory.of(fragment).createSimpleBulletin(icon, message).show();
    }
}
