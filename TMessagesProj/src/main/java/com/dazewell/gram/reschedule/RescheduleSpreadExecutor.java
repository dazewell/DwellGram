package com.dazewell.gram.reschedule;

import android.util.SparseIntArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Applies a bulk reschedule as a strictly sequential chain of messages.editMessage calls.
 * A fire-and-forget loop has a blind spot: with default request flags tgnet swallows
 * FLOOD_WAIT and silently retries minutes later, so the edit can land after the message
 * was already due (it then goes out late and off-order while the view looks correct).
 * Each request here runs with RequestFlagFailOnServerErrors so flood waits surface,
 * short ones are retried in place, and a closing messages.getScheduledHistory pass
 * checks the server really holds every new time before reporting the result.
 */
public final class RescheduleSpreadExecutor {

    public static final class Target {
        final int id;
        final int scheduleDate;
        final int repeatPeriod;
        // Full album child-id set captured when the target is built, while the fragment's group maps are
        // still valid. A later arm/verify pass must never re-expand the group from a live fragment: it can
        // mutate or become inaccessible after the fragment is destroyed, which would silently drop album
        // children from both the arm and the verification. For a non-grouped message this is just { id }.
        final int[] albumIds;

        public Target(int id, int scheduleDate, int repeatPeriod, int[] albumIds) {
            this.id = id;
            this.scheduleDate = scheduleDate;
            this.repeatPeriod = repeatPeriod;
            this.albumIds = albumIds;
        }
    }

    /**
     * One target's result, handed to the trigger finalization to reconcile against the server's
     * authoritative scheduled set. Neutral by design -- no eventschedule types cross this package
     * boundary. {@code applied} is false when the edit didn't demonstrably land, so the target is
     * not a candidate for arming. {@code repeatPeriod} is the message's native schedule-repeat period,
     * carried through so the finalization can refuse to arm a repeating message (the two features don't
     * compose, same rule the single-message path enforces).
     */
    public static final class TargetOutcome {
        public final int[] albumIds;
        public final int scheduleDate;
        public final int repeatPeriod;
        public final boolean applied;

        TargetOutcome(int[] albumIds, int scheduleDate, int repeatPeriod, boolean applied) {
            this.albumIds = albumIds;
            this.scheduleDate = scheduleDate;
            this.repeatPeriod = repeatPeriod;
            this.applied = applied;
        }
    }

    /**
     * Neutral seam for arming one shared event-schedule trigger across a bulk reschedule. Kept
     * primitive-only so the reschedule package never imports the eventschedule package: the executor
     * owns the request sequencing and hands over immutable outcome data; the implementation owns all
     * trigger state. Deferred atomic activation -- nothing is armed while the run is in flight; at
     * finalization the implementation reconciles first (a step that may yield to a storage hop) and
     * publishes the whole selection in one UI-thread turn only after that reconcile completes, so the
     * safety argument spans the hop rather than resting on a single synchronous turn.
     */
    public interface TriggerArmingHooks {
        /**
         * Once, on the UI thread, before the first edit request. The implementation holds back any
         * triggers the selected messages already carry, so a live trigger can't fire mid-run and then
         * read back as missing during verification. This alters runtime suppression ONLY and must never
         * durably remove an entry: suppression is process-local, so a process death before finalization
         * simply discards it and the durable trigger reloads armed. (An earlier design durably detached
         * the entry and kept restore snapshots to put it back; that lost the trigger outright on a
         * process death mid-run and was replaced by non-durable suppression -- do not reintroduce a
         * durable-removal step here.)
         */
        void onAdmission();

        /**
         * Once, after the closing scheduled-history read, on the request thread. {@code scheduledIds}
         * / {@code scheduledDates} are the server's current scheduled set (parallel arrays), empty
         * when {@code authoritative} is false. {@code generation} is this run's handle to its
         * (account, dialogId) generation: the implementation re-checks {@link RunGeneration#superseded()}
         * immediately before it commits the arm on the UI thread -- after the reconcile hop that can
         * yield, not frozen here where the window is still open -- and fails closed if a later run
         * overtook it (C3), then calls {@link RunGeneration#release()} once in its unconditional finally.
         * {@code rescheduleWrong}/{@code rescheduleTotal} carry the plain reschedule result so the
         * implementation can fold it into one bulletin. The implementation does its single
         * reconcile-create-refresh-notify pass on the UI thread.
         */
        void onFinalize(List<TargetOutcome> outcomes, int[] scheduledIds, int[] scheduledDates,
                        boolean authoritative, RunGeneration generation, int rescheduleWrong, int rescheduleTotal,
                        BaseFragment fragment);
    }

    /**
     * Neutral handle to one run's (account, dialogId) generation, handed to the trigger finalizer so
     * the overtaken-run comparison happens immediately before the arm commits rather than frozen early
     * in the executor. Primitive-only, so no eventschedule type crosses the package boundary.
     */
    public interface RunGeneration {
        /** True when a later run for this (account, dialogId) advanced the generation past this run. */
        boolean superseded();

        /** Release this run's generation. Call once, in finalization's unconditional finally. */
        void release();
    }

    /**
     * Neutral seam a plain run (no trigger arming) uses to tell the trigger store where its targets
     * actually landed, so a trigger armed earlier keeps its fallback time in step with the message.
     * Primitive-only, like {@link TriggerArmingHooks}. Called once per run on the UI thread with every
     * confirmed target (parallel arrays), whether or not the fragment is still alive. One batch rather
     * than a call per target, so the implementation can do its dialog-wide reconcile once.
     */
    public interface ScheduleMoveListener {
        void onSchedulesMoved(int[][] albumIds, int[] scheduleDates);
    }

    // Best-effort re-entry guard: one bulk run per dialog at a time. Deliberately NOT a safety
    // property for trigger activation -- see armRunSerial for that. This is the ONLY thing that can
    // refuse a plain reschedule, and the trigger feature leaves it exactly as it was.
    private static volatile long busyDialogId;

    // (account, dialogId) run generation. busyDialogId is cleared the instant the scheduled-history
    // response arrives, before the UI finalization runs, so a second run can be admitted in that
    // window and move the server dates this run is about to arm against. EVERY reschedule run advances
    // this generation at admission (see start()); only a trigger-enabled finalizer ever reads it, and it
    // reads it right before it commits the arm, failing closed if it was overtaken. A plain run only
    // advances it -- it never reads or branches on it -- so plain reschedule behaviour is unchanged.
    // The reader is EventScheduleBulkArmer, via the RunGeneration handle. Do not delete this as
    // "write-only": from inside this file the write looks unread, but the read lives in that finalizer
    // and is load-bearing -- removing it makes an overtaken trigger run arm from a stale snapshot again.
    private static final HashMap<String, Long> armRunSerial = new HashMap<>();
    private static long armRunCounter;

    private RescheduleSpreadExecutor() {
    }

    /**
     * The plain reschedule, reporting the confirmed new times to {@code moveListener} (see
     * {@link ScheduleMoveListener}; null reports nothing). Returns false when a previous run for this
     * dialog is still in flight.
     */
    public static boolean run(int currentAccount, long dialogId, ArrayList<Target> targets, BaseFragment fragment, ScheduleMoveListener moveListener) {
        return start(currentAccount, dialogId, targets, fragment, null, moveListener);
    }

    /**
     * Returns false when a previous run for this dialog is still in flight. When {@code hooks} is
     * non-null a shared trigger is armed over the whole selection at finalization (see
     * {@link TriggerArmingHooks}); when null this is the plain reschedule with no move listener.
     */
    public static boolean run(int currentAccount, long dialogId, ArrayList<Target> targets, BaseFragment fragment, TriggerArmingHooks hooks) {
        return start(currentAccount, dialogId, targets, fragment, hooks, null);
    }

    private static boolean start(int currentAccount, long dialogId, ArrayList<Target> targets, BaseFragment fragment,
                                 TriggerArmingHooks hooks, ScheduleMoveListener moveListener) {
        if (busyDialogId == dialogId) {
            return false;
        }
        busyDialogId = dialogId;
        // NagramX: advance the (account, dialogId) generation on EVERY run, placed AFTER the
        // busyDialogId admission check above so it can never gate or refuse a plain run -- here it is
        // deliberately write-only. Its sole purpose is that a concurrent trigger-enabled finalizer
        // (EventScheduleBulkArmer) can detect this run overtook it and fail closed; a plain run never
        // reads it, so plain reschedule behaviour is unchanged. Do not delete this because it looks
        // unread in this file -- the read is in that finalizer, one file away, and is load-bearing.
        final long serial = claimArmSerial(currentAccount, dialogId);
        if (hooks != null) {
            hooks.onAdmission();
        }
        // NagramX: index-aligned, carried through the whole run so verify() can tell "this exact
        // target was rejected pre-send" from "this target's id happens to already hold the right date".
        // Must be index-keyed, not id-keyed: target.id can duplicate across targets (see isSendable
        // rejection accounting below), and an id-keyed check would let one target's successful edit
        // mask a different, rejected target that happens to share its id.
        final boolean[] rejected = new boolean[targets.size()];
        sendNext(currentAccount, dialogId, targets, 0, 1, new ArrayList<>(), hooks == null ? null : new ArrayList<>(), hooks, moveListener, serial, fragment, rejected);
        return true;
    }

    private static String armKey(int account, long dialogId) {
        return account + "_" + dialogId;
    }

    private static synchronized long claimArmSerial(int account, long dialogId) {
        long serial = ++armRunCounter;
        armRunSerial.put(armKey(account, dialogId), serial);
        return serial;
    }

    private static synchronized boolean armRunSuperseded(int account, long dialogId, long serial) {
        Long current = armRunSerial.get(armKey(account, dialogId));
        return current == null || current != serial;
    }

    private static synchronized void clearArmSerial(int account, long dialogId, long serial) {
        String key = armKey(account, dialogId);
        Long current = armRunSerial.get(key);
        if (current != null && current == serial) {
            armRunSerial.remove(key);
        }
    }

    /**
     * True when {@code target} is safe to send: a positive {@code id} and every {@code albumIds}
     * member positive (null {@code albumIds} is invalid -- a target with no captured album identity
     * can't be verified safe). Deliberately a local copy of the same shape used elsewhere (album
     * readiness, single-message editability) rather than a call across it: this package must stay
     * primitive-only, see {@link TriggerArmingHooks}.
     */
    private static boolean isSendable(Target target) {
        if (target.id <= 0 || target.albumIds == null) {
            return false;
        }
        for (int id : target.albumIds) {
            if (id <= 0) {
                return false;
            }
        }
        return true;
    }

    private static void sendNext(int currentAccount, long dialogId, ArrayList<Target> targets, int index, int retriesLeft,
                                 ArrayList<Integer> failedIds, ArrayList<TargetOutcome> outcomes,
                                 TriggerArmingHooks hooks, ScheduleMoveListener moveListener, long serial, BaseFragment fragment, boolean[] rejected) {
        // NagramX: fail each invalid target closed rather than send a partial/invalid edit, in a loop
        // rather than a per-target recursive call -- a run with many consecutive invalid targets (e.g.
        // several still-sending album members in a row) must not grow the call stack. Albums are
        // all-or-nothing here because a rejected child can't carry the new schedule time on its own --
        // a half-moved group is worse than a group that didn't move at all. Checks target.id explicitly
        // rather than assuming albumIds always contains it, and treats a null albumIds as invalid too.
        int cursor = index;
        while (cursor < targets.size() && !isSendable(targets.get(cursor))) {
            final Target invalid = targets.get(cursor);
            failedIds.add(invalid.id);
            rejected[cursor] = true;
            if (outcomes != null) {
                outcomes.add(new TargetOutcome(invalid.albumIds, invalid.scheduleDate, invalid.repeatPeriod, false));
            }
            cursor++;
        }
        if (cursor >= targets.size()) {
            verify(currentAccount, dialogId, targets, failedIds, outcomes, hooks, moveListener, serial, fragment, rejected);
            return;
        }
        final int targetIndex = cursor;
        final Target target = targets.get(targetIndex);
        final TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.id = target.id;
        req.schedule_date = target.scheduleDate;
        req.flags |= 32768;
        if (target.repeatPeriod != 0) {
            req.schedule_repeat_period = target.repeatPeriod;
            req.flags |= TLObject.FLAG_18;
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            boolean applied = true;
            if (error == null) {
                if (response instanceof TLRPC.Updates) {
                    MessagesController.getInstance(currentAccount).processUpdates((TLRPC.Updates) response, false);
                } else {
                    // An unexpected type means the edit didn't demonstrably apply. Record it: if the
                    // verify pass can't read the schedule back it falls back to counting failedIds,
                    // and an unrecorded failure would report the whole run as applied.
                    failedIds.add(target.id);
                    applied = false;
                }
            } else if (error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                int wait = Utilities.parseInt(error.text);
                if (retriesLeft > 0 && wait <= 60) {
                    AndroidUtilities.runOnUIThread(() -> sendNext(currentAccount, dialogId, targets, targetIndex, retriesLeft - 1, failedIds, outcomes, hooks, moveListener, serial, fragment, rejected), (wait + 1) * 1000L);
                    return;
                }
                failedIds.add(target.id);
                applied = false;
            } else if (error.text == null || !error.text.equals("MESSAGE_NOT_MODIFIED")) {
                // MESSAGE_NOT_MODIFIED means the date already matches: that's a success here.
                failedIds.add(target.id);
                applied = false;
            }
            if (outcomes != null) {
                outcomes.add(new TargetOutcome(target.albumIds, target.scheduleDate, target.repeatPeriod, applied));
            }
            sendNext(currentAccount, dialogId, targets, targetIndex + 1, 1, failedIds, outcomes, hooks, moveListener, serial, fragment, rejected);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static void verify(int currentAccount, long dialogId, ArrayList<Target> targets, ArrayList<Integer> failedIds,
                               ArrayList<TargetOutcome> outcomes, TriggerArmingHooks hooks, ScheduleMoveListener moveListener, long serial, BaseFragment fragment, boolean[] rejected) {
        final TLRPC.TL_messages_getScheduledHistory req = new TLRPC.TL_messages_getScheduledHistory();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.hash = 0;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            busyDialogId = 0;
            final int total = targets.size();
            int wrong;
            final SparseIntArray serverDates = new SparseIntArray();
            final boolean authoritative = response instanceof TLRPC.messages_Messages && !(response instanceof TLRPC.TL_messages_messagesNotModified);
            if (authoritative) {
                // A scheduled message's date is its schedule time; a missing id means the
                // message already left the queue, which is just as off-plan as a stale date.
                for (TLRPC.Message message : ((TLRPC.messages_Messages) response).messages) {
                    serverDates.put(message.id, message.date);
                }
                wrong = 0;
                for (int i = 0; i < targets.size(); i++) {
                    // NagramX: a pre-rejected target (this exact index, never sent) must count as wrong
                    // outright rather than fall through to the id-keyed server-date compare below. Its own
                    // id can be positive and legitimately hold the requested date already -- coincidentally,
                    // or because another target elsewhere in this run shares the same id and its edit did
                    // land -- either of which would otherwise silently report a target we refused to send
                    // as successful. Index-keyed, not id-keyed, precisely because ids can duplicate.
                    if (rejected[i]) {
                        wrong++;
                        continue;
                    }
                    Target t = targets.get(i);
                    if (serverDates.get(t.id, -1) != t.scheduleDate) {
                        wrong++;
                    }
                }
            } else {
                wrong = failedIds.size();
            }
            final int wrongFinal = wrong;
            if (hooks != null) {
                // Parse the authoritative set into flat immutable arrays off the UI thread; the
                // finalization implementation reconciles and publishes on the UI thread and owns the
                // single bulletin (folding in wrongFinal/total), so the executor posts none of its own.
                final int[] ids = new int[serverDates.size()];
                final int[] dates = new int[serverDates.size()];
                for (int i = 0; i < serverDates.size(); i++) {
                    ids[i] = serverDates.keyAt(i);
                    dates[i] = serverDates.valueAt(i);
                }
                // NagramX: hand the generation to the finalizer rather than comparing it here. The
                // supersession check must happen immediately before the arm commits, AFTER the reconcile
                // hop that can yield -- comparing it here froze the window open. The finalizer releases
                // it in its unconditional finally.
                final long capturedSerial = serial;
                final RunGeneration generation = new RunGeneration() {
                    @Override
                    public boolean superseded() {
                        return armRunSuperseded(currentAccount, dialogId, capturedSerial);
                    }

                    @Override
                    public void release() {
                        clearArmSerial(currentAccount, dialogId, capturedSerial);
                    }
                };
                hooks.onFinalize(outcomes, ids, dates, authoritative, generation, wrongFinal, total, fragment);
                return;
            }
            // NagramX: plain reschedule advanced the generation at admission purely so a concurrent
            // trigger run can detect it moved the dates; nothing on this path reads it, so release it now.
            clearArmSerial(currentAccount, dialogId, serial);
            final int[] movedDates = moveListener != null
                    ? confirmedDates(targets, failedIds, serverDates, authoritative, rejected) : null;
            // The countdown only ever comes from the read-back, never from the times we asked for.
            int earliest = 0;
            if (authoritative && wrongFinal == 0) {
                for (Target t : targets) {
                    int d = serverDates.get(t.id, 0);
                    if (d > 0 && (earliest == 0 || d < earliest)) earliest = d;
                }
            }
            final int earliestFinal = earliest;
            AndroidUtilities.runOnUIThread(() -> {
                // Before the fragment check: the trigger store has to follow the move even when the
                // chat was closed mid-run, or a later reload prunes the trigger on its old time.
                if (movedDates != null) {
                    int n = 0;
                    for (int d : movedDates) if (d > 0) n++;
                    if (n > 0) {
                        final int[][] movedIds = new int[n][];
                        final int[] dates = new int[n];
                        for (int i = 0, k = 0; i < movedDates.length; i++) {
                            if (movedDates[i] <= 0) continue;
                            movedIds[k] = targets.get(i).albumIds;
                            dates[k++] = movedDates[i];
                        }
                        moveListener.onSchedulesMoved(movedIds, dates);
                    }
                }
                if (fragment == null || fragment.getParentActivity() == null) {
                    return;
                }
                if (wrongFinal == 0) {
                    String in = com.dazewell.gram.schedulecountdown.ScheduleCountdown.describeIn(currentAccount, earliestFinal);
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, in != null
                            ? LocaleController.formatPluralString("RescheduleAppliedCountdown", total, in)
                            : LocaleController.formatPluralString("RescheduleApplied", total)).show();
                } else {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.RescheduleVerifyFailed, wrongFinal, total)).show();
                }
            });
        });
    }

    /**
     * Index-aligned new time per target, 0 where it isn't confirmed. With an authoritative read the
     * server's date wins over the requested one, and a target whose id left the queue gets nothing;
     * without one, a target that wasn't rejected and didn't fail is taken at its requested time, the
     * same trust the single-message edit gives a commit.
     */
    private static int[] confirmedDates(ArrayList<Target> targets, ArrayList<Integer> failedIds,
                                        SparseIntArray serverDates, boolean authoritative, boolean[] rejected) {
        final int[] dates = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            if (rejected[i]) continue;
            Target t = targets.get(i);
            if (authoritative) {
                dates[i] = Math.max(0, serverDates.get(t.id, 0));
            } else if (!failedIds.contains(t.id)) {
                dates[i] = t.scheduleDate;
            }
        }
        return dates;
    }
}
