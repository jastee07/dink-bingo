package dinkbingo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A claim failure is the moment a player most needs to know what went wrong, because a rare
 * drop may need the organizer to step in. Every one of them used to read
 * {@code Bingo: claim failed (error).} because the claim path switched on {@code status}, which
 * is the literal string {@code "error"} for all of them.
 */
class BingoErrorsTest {

    @Test
    void knownBackendErrorsNameTheThingToFix() {
        assertTrue(BingoErrors.describeClaimError("bad_token").contains("Event Token"),
            "a rejected token must point at the setting that is wrong");
        assertTrue(BingoErrors.describeClaimError("lock_timeout").contains("busy"));
        assertTrue(BingoErrors.describeClaimError("claim_id_conflict").contains("claim id"));
        assertTrue(BingoErrors.describeClaimError("bad_request").contains("Apps Script"),
            "a malformed request is nearly always an outdated deployment");

        // Whatever the reason, the player is told plainly that the drop did not count.
        for (String code : new String[] {"bad_token", "lock_timeout", "claim_id_conflict",
            "bad_json", "bad_request", "post_required", "unknown_action"}) {
            String message = BingoErrors.describeClaimError(code);
            assertTrue(message.startsWith("Bingo: "), code + " must read like every other line");
            assertTrue(message.contains("nothing was claimed"),
                code + " must say the drop did not count: " + message);
            assertFalse(message.contains(code),
                code + " must be translated, not echoed: " + message);
        }
    }

    @Test
    void bothSurfacesExplainTheSameReason() {
        // The panel and the chat line come from one table, so neither can be reworded alone.
        for (String code : new String[] {"bad_token", "lock_timeout", "bad_request"}) {
            assertFalse(BingoErrors.describeBoardError(code).startsWith("Backend error: "),
                code + " must be a recognized reason on the board path too");
            assertFalse(BingoErrors.describeClaimError(code).contains("refused the claim: "),
                code + " must be a recognized reason on the claim path too");
        }
    }

    @Test
    void unknownBackendTextIsBoundedAndSingleLine() {
        // A custom or future backend can return anything, including a stack trace.
        String hostile = "Exception\nat Code.gs:1\r\n\tat Code.gs:2 " + repeat("x", 500);

        for (String message : new String[] {
            BingoErrors.describeClaimError(hostile),
            BingoErrors.describeBoardError(hostile)
        }) {
            assertFalse(message.contains("\n"), "must not span chat lines: " + message);
            assertFalse(message.contains("\r"));
            assertFalse(message.contains("\t"));
            assertTrue(message.length() < 160, "must stay bounded: " + message.length());
            assertTrue(message.endsWith("…"), "overlong text is truncated: " + message);
        }
    }

    @Test
    void backendTextCannotBecomeSwingMarkup() {
        // The panel renders its reason in a Swing label, which interprets a string starting
        // with <html> as markup. Every path prefixes the backend value, so it never leads.
        String markup = "<html><b>totally fine</b></html>";
        assertFalse(BingoErrors.describeBoardError(markup).startsWith("<html"));
        assertFalse(BingoErrors.describeClaimError(markup).startsWith("<html"));
    }

    @Test
    void missingReasonDoesNotBlameThePlayersConnection() {
        // A response arrived and was refused, so "check your connection" would be misleading.
        assertFalse(BingoErrors.describeClaimError(null).contains("connection"));
        assertFalse(BingoErrors.describeClaimError("   ").contains("connection"));
        assertTrue(BingoErrors.describeClaimError(null).startsWith("Bingo: "));

        // A board fetch with no reason at all really is a failed round trip.
        assertEquals("Check your connection, then press Refresh",
            BingoErrors.describeBoardError(null));
    }

    @Test
    void transportExhaustionIsReportedSeparately() {
        // No response ever arrived, so there is no backend reason and nothing reached the
        // sheet. The player is told the drop was not claimed and that it can be retried.
        String message = BingoErrors.describeUnresolvedClaim("Twisted bow");
        assertTrue(message.contains("Twisted bow"));
        assertTrue(message.contains("not claimed"));
        assertTrue(message.contains("again"), "the retry behaviour must be stated: " + message);

        assertTrue(BingoErrors.describeUnresolvedClaim(null).contains("that drop"));
        assertTrue(BingoErrors.describeUnresolvedClaim("  ").contains("that drop"));
    }

    @Test
    void itemNamesAreBoundedToo() {
        // The item name comes from the board, which the organizer controls, so it gets the
        // same treatment rather than being trusted because it is not the error field.
        String message = BingoErrors.describeUnresolvedClaim("<html>" + repeat("y", 500));
        assertFalse(message.startsWith("<html"));
        assertTrue(message.length() < 200);
    }

    private static String repeat(String s, int times) {
        StringBuilder out = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            out.append(s);
        }
        return out.toString();
    }
}
