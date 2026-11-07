package com.dazewell.gram.search;

import android.util.Log;

import org.telegram.messenger.BuildConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.atomic.AtomicInteger;

/** Temporary request-path probes; never include search text or peer metadata. */
public final class DmSearchDiagnostics {
    private static final String TAG = "DmSearch";
    private static final String MARKER = "NAX_SMOKE_dm-search-filters";
    private static final AtomicInteger NEXT_REQUEST = new AtomicInteger();

    private DmSearchDiagnostics() {}

    public static int begin(TLRPC.TL_messages_search request, int account) {
        int token = NEXT_REQUEST.incrementAndGet();
        Log.i(TAG, MARKER + " BEGIN request=" + token + " scenario=date-range-request build=" + BuildConfig.BUILD_VERSION_STRING
                + " application=" + BuildConfig.APPLICATION_ID + " account=" + account);
        boolean lower = request.min_date != 0;
        boolean upper = request.max_date != 0;
        String senderChoice = request.from_id == null ? "ANY"
                : request.peer != null && request.from_id.user_id == request.peer.user_id ? "PEER" : "SELF";
        Log.i(TAG, MARKER + " PATH request=" + token + " emptyQuery=" + (request.q == null || request.q.isEmpty())
                + " senderPresent=" + (request.from_id != null)
                + " senderChoice=" + senderChoice
                + " lowerPresent=" + lower + " upperPresent=" + upper
                + " intervalValid=" + (!lower || !upper || request.min_date < request.max_date)
                + " typeEmpty=" + (request.filter == null || request.filter instanceof TLRPC.TL_inputMessagesFilterEmpty));
        return token;
    }

    public static void end(int token, TLObject response, TLRPC.TL_error error) {
        if (error == null && response instanceof TLRPC.messages_Messages) {
            TLRPC.messages_Messages messages = (TLRPC.messages_Messages) response;
            int total = response instanceof TLRPC.TL_messages_messages ? messages.messages.size() : messages.count;
            Log.i(TAG, MARKER + " PATH success request=" + token + " messages=" + messages.messages.size() + " total=" + total);
        } else {
            Log.i(TAG, MARKER + " FORBIDDEN failure request=" + token + " code=" + (error == null ? 0 : error.code)
                    + " kind=" + errorKind(error));
        }
        Log.i(TAG, MARKER + " END request=" + token + " scenario=date-range-request");
    }

    private static String errorKind(TLRPC.TL_error error) {
        if (error == null || error.text == null) return "OTHER";
        switch (error.text) {
            case "SEARCH_QUERY_EMPTY":
            case "QUERY_EMPTY":
            case "FROM_PEER_INVALID":
            case "INPUT_FILTER_INVALID":
            case "PEER_ID_INVALID":
            case "PEER_ID_NOT_SUPPORTED":
                return error.text;
            default:
                return "OTHER";
        }
    }
}
