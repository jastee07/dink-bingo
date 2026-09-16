package dinkbingo;

import org.jetbrains.annotations.Nullable;

/**
 * The one place a backend {@code error} value is turned into words for a player.
 * <p>
 * A board fetch and a claim fail for the same reasons, but they used to explain themselves in
 * different places. The panel had a bounded, sanitized mapping; the claim path switched on
 * {@code status}, which is the literal string {@code "error"} for every failure, so every one
 * of them rendered as {@code Bingo: claim failed (error).} That is the moment a player most
 * needs to know whether the token is wrong, the deployment is stale, or the sheet is broken,
 * because a rare drop may need the organizer to intervene.
 * <p>
 * Both phrasings for a reason live on the same enum constant, so neither surface can be
 * reworded without the other being right there.
 */
final class BingoErrors {

    /**
     * Backend reasons are free text from a deployment the player does not control, so an
     * unrecognized one is bounded before it reaches a label or the chat box.
     */
    private static final int MAX_REASON_LENGTH = 80;

    /**
     * Shared wording for the family of errors that all mean "this deployment does not
     * understand the request", which is nearly always an outdated or misconfigured Apps Script.
     */
    private static final String REQUEST_BOARD =
        "The backend rejected the request. Check the Apps Script deployment.";
    private static final String REQUEST_CLAIM =
        "the backend rejected the request, so nothing was claimed. "
            + "The Apps Script deployment is probably out of date.";

    private enum Reason {
        BAD_TOKEN(
            "bad_token",
            "Event token rejected. Check the token on the Config tab.",
            "your event token was rejected, so nothing was claimed. "
                + "Check the plugin's Event Token against the organizer's Config tab."),

        LOCK_TIMEOUT(
            "lock_timeout",
            "The backend is busy. Press Refresh to try again.",
            "the backend stayed busy, so nothing was claimed. "
                + "Tell the organizer if this keeps happening."),

        CLAIM_ID_CONFLICT(
            "claim_id_conflict",
            "The backend rejected the request. Check the Apps Script deployment.",
            "that claim id was already used for a different drop, so nothing was claimed. "
                + "Tell the organizer if this keeps happening."),

        BAD_JSON("bad_json", REQUEST_BOARD, REQUEST_CLAIM),
        BAD_REQUEST("bad_request", REQUEST_BOARD, REQUEST_CLAIM),
        POST_REQUIRED("post_required", REQUEST_BOARD, REQUEST_CLAIM),
        UNKNOWN_ACTION("unknown_action", REQUEST_BOARD, REQUEST_CLAIM);

        private final String code;
        private final String board;
        private final String claim;

        Reason(String code, String board, String claim) {
            this.code = code;
            this.board = board;
            this.claim = claim;
        }
    }

    private BingoErrors() {
    }

    /** Why the board could not be loaded, for the sidebar. */
    static String describeBoardError(@Nullable String backendError) {
        String error = normalize(backendError);
        if (error.isEmpty()) {
            return "Check your connection, then press Refresh";
        }
        Reason reason = lookup(error);
        return reason != null ? reason.board : "Backend error: " + summarize(error);
    }

    /**
     * Why a drop was not claimed, as a game-chat line.
     * <p>
     * Every message is a full sentence starting with {@code Bingo:} so it reads the same as an
     * accepted claim, and says plainly that nothing was claimed.
     */
    static String describeClaimError(@Nullable String backendError) {
        String error = normalize(backendError);
        if (error.isEmpty()) {
            // A response arrived and was refused, but named no reason. Treat it as a backend
            // problem rather than implying the player's connection is at fault.
            return "Bingo: the backend refused the claim without giving a reason.";
        }
        Reason reason = lookup(error);
        return "Bingo: " + (reason != null ? reason.claim
            : "the backend refused the claim: " + summarize(error));
    }

    /** No usable response ever arrived, so there is no backend reason to report. */
    static String describeUnresolvedClaim(@Nullable String itemName) {
        String item = itemName == null || itemName.trim().isEmpty()
            ? "that drop" : summarize(itemName);
        return "Bingo: couldn't reach the backend, so " + item
            + " was not claimed. It will be tried again if you get it again.";
    }

    /**
     * Whether a backend reason means the deployment did not understand the request at all,
     * which is what an Apps Script left on an old copy of {@code Code.gs} looks like from
     * here. Kept beside the wording so a new reason cannot be added to one and not the other.
     */
    static boolean isUnsupportedRequest(@Nullable String backendError) {
        Reason reason = lookup(normalize(backendError));
        return reason == Reason.BAD_JSON
            || reason == Reason.BAD_REQUEST
            || reason == Reason.POST_REQUIRED
            || reason == Reason.UNKNOWN_ACTION;
    }

    /** Whether a backend reason means the event token itself was refused. */
    static boolean isBadToken(@Nullable String backendError) {
        return lookup(normalize(backendError)) == Reason.BAD_TOKEN;
    }

    @Nullable
    private static Reason lookup(String error) {
        for (Reason reason : Reason.values()) {
            if (reason.code.equals(error)) {
                return reason;
            }
        }
        return null;
    }

    private static String normalize(@Nullable String backendError) {
        return backendError == null ? "" : backendError.trim();
    }

    /**
     * Collapses a backend reason to a single bounded line.
     * <p>
     * Callers always prefix the result, which keeps it from starting with {@code <html>} — Swing
     * would otherwise render a backend-controlled string as markup. Whitespace and control
     * characters collapse to single spaces so a multi-line stack trace cannot take over the
     * panel or spill across several chat lines. The untruncated value is already in the log.
     */
    static String summarize(String error) {
        StringBuilder out = new StringBuilder(error.length());
        boolean pendingSpace = false;
        for (int i = 0; i < error.length(); i++) {
            char c = error.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (out.length() + (pendingSpace ? 2 : 1) > MAX_REASON_LENGTH) {
                return out.append('…').toString();
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
        }
        return out.toString();
    }
}
