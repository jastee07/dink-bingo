package dinkbingo;

import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The readiness report is read by a player who is already stuck, so the thing worth testing is
 * the wording and the states: a check that could not run must never read as one that passed,
 * and a failing one must say what to do next.
 */
class SystemCheckTest {

    /** Everything working. This is the only status allowed to read as ready. */
    @Test
    void aWorkingSetupReportsReady() {
        SystemCheck check = SystemCheck.evaluate(ready().build());

        assertEquals(SystemCheck.State.READY, check.getState());
        assertEquals("ready", check.summary());
        for (SystemCheck.Row row : check.getRows()) {
            assertEquals(SystemCheck.State.READY, row.getState(),
                row.getName() + " should be ready");
        }
    }

    /**
     * The one the plugin could never explain before: an organizer rotates the token mid-event
     * and every drop silently stops counting.
     */
    @Test
    void aRejectedTokenIsNamedAndTellsThePlayerWhereToGetTheNewOne() {
        SystemCheck check = SystemCheck.evaluate(ready()
            .fetch(SystemStatus.Fetch.REJECTED)
            .backendError("bad_token")
            .build());

        SystemCheck.Row token = row(check, "Event token");
        assertEquals(SystemCheck.State.FAILED, token.getState());
        assertNotNull(token.getAction());
        assertTrue(token.getAction().contains("Config tab"), token.getAction());

        // The round trip worked, so the connection is not what to go and look at.
        assertEquals(SystemCheck.State.READY, row(check, "Backend").getState());
        // And nothing proves the deployment is out of date, so it is not accused of being so.
        assertEquals(SystemCheck.State.NOT_CHECKED, row(check, "Backend version").getState());
    }

    /** An Apps Script left on an old copy of Code.gs, which reads as a broken token otherwise. */
    @Test
    void aDeploymentThatDoesNotUnderstandTheRequestIsReportedAsAVersionProblem() {
        SystemCheck check = SystemCheck.evaluate(ready()
            .fetch(SystemStatus.Fetch.REJECTED)
            .backendError("unknown_action")
            .build());

        SystemCheck.Row version = row(check, "Backend version");
        assertEquals(SystemCheck.State.FAILED, version.getState());
        assertTrue(version.getAction().contains("re-deploy"), version.getAction());

        // The token was never refused, so it is not reported as though it had been.
        assertEquals(SystemCheck.State.NOT_CHECKED, row(check, "Event token").getState());
    }

    /** The most common setup mistake, and the one the player cannot fix themselves. */
    @Test
    void anRsnMissingFromTheTeamsTabSaysExactlyWhatToAskFor() {
        SystemCheck check = SystemCheck.evaluate(ready()
            .board(board(null, true))
            .build());

        SystemCheck.Row team = row(check, "Team");
        assertEquals(SystemCheck.State.FAILED, team.getState());
        assertTrue(team.getAction().contains("Jake"), team.getAction());
        assertTrue(team.getAction().contains("Teams tab"), team.getAction());
    }

    /**
     * A closed event is not a broken setup: everything works and nothing will be claimed,
     * which is a warning and reads as one.
     */
    @Test
    void aClosedEventWarnsRatherThanFailing() {
        SystemCheck check = SystemCheck.evaluate(ready()
            .board(board("Team One", false))
            .build());

        assertEquals(SystemCheck.State.WARNING, check.getState());
        assertEquals(SystemCheck.State.WARNING, row(check, "Event").getState());
        assertTrue(row(check, "Event").getDetail().contains("Closed"));
        // Nothing is being submitted while that is true, and the report says so.
        assertEquals(SystemCheck.State.WARNING, row(check, "Claim detection").getState());
    }

    /** A failed round trip is the one case that really is about the player's connection. */
    @Test
    void anUnreachableBackendFailsWithoutPassingAnythingItCouldNotCheck() {
        SystemCheck check = SystemCheck.evaluate(ready()
            .fetch(SystemStatus.Fetch.UNREACHABLE)
            .board(null)
            .build());

        SystemCheck.Row backend = row(check, "Backend");
        assertEquals(SystemCheck.State.FAILED, backend.getState());
        assertTrue(backend.getAction().contains("connection"), backend.getAction());

        // Never checked is never the same as passed.
        assertEquals(SystemCheck.State.NOT_CHECKED, row(check, "Event token").getState());
        assertEquals(SystemCheck.State.NOT_CHECKED, row(check, "Team").getState());
        assertEquals(SystemCheck.State.NOT_CHECKED, row(check, "Board").getState());
    }

    /**
     * Rows left over from an earlier fetch are still useful, but presenting them as current is
     * how a player ends up trusting a board that stopped updating an hour ago.
     */
    @Test
    void aBoardThatIsNoLongerLiveIsNotReportedAsLoaded() {
        SystemCheck check = SystemCheck.evaluate(ready()
            .fetch(SystemStatus.Fetch.REJECTED)
            .backendError("bad_token")
            .detectionEnabled(false)
            .build());

        SystemCheck.Row board = row(check, "Board");
        assertEquals(SystemCheck.State.WARNING, board.getState());
        assertTrue(board.getDetail().contains("no longer live"), board.getDetail());

        SystemCheck.Row detection = row(check, "Claim detection");
        assertEquals(SystemCheck.State.FAILED, detection.getState());
        assertTrue(detection.getDetail().contains("Suspended"), detection.getDetail());
    }

    /**
     * A reason the wording does not recognise still has to reach the player, but it comes from
     * a deployment they do not control, so it is bounded and never leads.
     */
    @Test
    void anUnrecognisedBackendReasonIsBoundedAndCannotLead() {
        StringBuilder shouting = new StringBuilder("<html>");
        for (int i = 0; i < 40; i++) {
            shouting.append("wat\nwat ");
        }
        SystemCheck check = SystemCheck.evaluate(ready()
            .fetch(SystemStatus.Fetch.REJECTED)
            .backendError(shouting.toString())
            .build());

        SystemCheck.Row backend = row(check, "Backend");
        assertEquals(SystemCheck.State.WARNING, backend.getState());
        String action = backend.getAction();
        assertTrue(action.startsWith("Backend error: "), action);
        assertFalse(action.contains("\n"), action);
        assertTrue(action.length() < 120, "reason should be bounded: " + action.length());
    }

    /** Nothing is sent anywhere until a backend is set, and that is the first thing to fix. */
    @Test
    void anUnusableBackendUrlIsTheFirstProblemAndStopsTheRestBeingGuessedAt() {
        for (BackendUrlState state : new BackendUrlState[]{
            BackendUrlState.UNSET, BackendUrlState.INVALID, BackendUrlState.INSECURE}) {

            SystemCheck check = SystemCheck.evaluate(ready().backendUrl(state)
                .fetch(SystemStatus.Fetch.NOT_CHECKED)
                .board(null)
                .build());

            SystemCheck.Row url = row(check, "Backend URL");
            assertEquals(SystemCheck.State.FAILED, url.getState(), state.name());
            assertNotNull(url.getAction(), state.name());
            assertEquals("Backend URL", firstFailing(check).getName(), state.name());
            assertEquals(SystemCheck.State.NOT_CHECKED, row(check, "Backend").getState());
        }
    }

    /** The configured URL never reaches the screen; it names the organizer's deployment. */
    @Test
    void theReportNeverEchoesTheBackendUrlOrTheToken() {
        SystemCheck check = SystemCheck.evaluate(ready().build());
        for (SystemCheck.Row row : check.getRows()) {
            String text = row.getDetail() + " " + row.getAction();
            assertFalse(text.contains("http"), row.getName() + ": " + text);
            assertFalse(text.contains("s3cret"), row.getName() + ": " + text);
        }
    }

    /**
     * Dink missing is not a broken setup. The sheet decides a claim, so tiles still count --
     * what is lost is the announcement, and saying "failed" would send a player to fix the
     * wrong thing during an event.
     */
    @Test
    void aMissingOrDisabledDinkWarnsBecauseTilesStillCount() {
        for (SystemStatus.Presence presence : new SystemStatus.Presence[]{
            SystemStatus.Presence.MISSING, SystemStatus.Presence.DISABLED}) {

            SystemCheck.Row dink = row(SystemCheck.evaluate(ready().dink(presence).build()), "Dink");
            assertEquals(SystemCheck.State.WARNING, dink.getState(), presence.name());
            assertTrue(dink.getAction().contains("still claimed"), dink.getAction());
        }
    }

    /** A running Dink still cannot be called proven, because Dink acknowledges nothing. */
    @Test
    void aRunningDinkPointsAtTheSettingAndTheTestRatherThanClaimingDelivery() {
        SystemCheck.Row dink = row(SystemCheck.evaluate(ready().build()), "Dink");
        assertEquals(SystemCheck.State.READY, dink.getState());
        assertTrue(dink.getAction().contains("External Plugin"), dink.getAction());
        assertTrue(dink.getAction().contains("Test Dink"), dink.getAction());
    }

    /** Loot Tracker off costs some drop sources and not others, and the row says which. */
    @Test
    void lootTrackerOffSaysWhichDropsStopBeingSeen() {
        SystemCheck.Row loot = row(SystemCheck.evaluate(
            ready().lootTracker(SystemStatus.Presence.MISSING).build()), "Loot Tracker");

        assertEquals(SystemCheck.State.WARNING, loot.getState());
        assertTrue(loot.getDetail().contains("chest"), loot.getDetail());
        assertTrue(loot.getAction().contains("NPC drops"), loot.getAction());
    }

    /** A plugin list that could not be read is admitted to, not guessed at. */
    @Test
    void anUnreadablePluginListReportsNotCheckedRatherThanMissing() {
        SystemCheck check = SystemCheck.evaluate(ready()
            .dink(SystemStatus.Presence.UNKNOWN)
            .lootTracker(SystemStatus.Presence.UNKNOWN)
            .build());

        assertEquals(SystemCheck.State.NOT_CHECKED, row(check, "Dink").getState());
        assertEquals(SystemCheck.State.NOT_CHECKED, row(check, "Loot Tracker").getState());
        assertEquals("not checked", check.summary());
    }

    /** Not logged in is a reason the checks could not run, not a reason they passed. */
    @Test
    void beingLoggedOutLeavesTheAccountChecksUnchecked() {
        SystemCheck check = SystemCheck.evaluate(ready()
            .loggedIn(false)
            .rsn(null)
            .board(null)
            .fetch(SystemStatus.Fetch.NOT_CHECKED)
            .build());

        SystemCheck.Row name = row(check, "RuneScape name");
        assertEquals(SystemCheck.State.NOT_CHECKED, name.getState());
        assertTrue(name.getAction().contains("Log in"), name.getAction());
        assertEquals(SystemCheck.State.NOT_CHECKED, row(check, "Team").getState());
    }

    /** A fetch in flight is its own state, so a slow backend does not read as a broken one. */
    @Test
    void aFetchInFlightReportsCheckingRatherThanAFailure() {
        SystemCheck check = SystemCheck.evaluate(ready()
            .fetch(SystemStatus.Fetch.CHECKING)
            .board(null)
            .build());

        assertEquals(SystemCheck.State.CHECKING, row(check, "Backend").getState());
        assertEquals("checking…", check.summary());
    }

    /** The button carries this, so it has to tell a player whether anything is left to fix. */
    @Test
    void theSummaryCountsProblemsAheadOfWarnings() {
        assertEquals("1 warning", SystemCheck.evaluate(
            ready().dink(SystemStatus.Presence.MISSING).build()).summary());

        assertEquals("2 warnings", SystemCheck.evaluate(ready()
            .dink(SystemStatus.Presence.MISSING)
            .lootTracker(SystemStatus.Presence.MISSING)
            .build()).summary());

        // A problem outranks any number of warnings, because it is what to go and fix.
        assertEquals("1 problem", SystemCheck.evaluate(ready()
            .dink(SystemStatus.Presence.MISSING)
            .detectionEnabled(false)
            .build()).summary());
    }

    /** Every failing row has to leave the player with something to do. */
    @Test
    void everyFailureAndWarningCarriesARemedy() {
        SystemStatus[] broken = {
            ready().backendUrl(BackendUrlState.UNSET).build(),
            ready().backendUrl(BackendUrlState.INVALID).build(),
            ready().backendUrl(BackendUrlState.INSECURE).build(),
            ready().tokenSet(false).build(),
            ready().fetch(SystemStatus.Fetch.UNREACHABLE).board(null).build(),
            ready().fetch(SystemStatus.Fetch.REJECTED).backendError("bad_token").build(),
            ready().fetch(SystemStatus.Fetch.REJECTED).backendError("unknown_action").build(),
            ready().board(board(null, true)).build(),
            ready().board(board("Team One", false)).build(),
            ready().detectionEnabled(false).build(),
            ready().dink(SystemStatus.Presence.MISSING).build(),
            ready().lootTracker(SystemStatus.Presence.DISABLED).build(),
            ready().loggedIn(false).rsn(null).board(null).build(),
        };
        for (SystemStatus status : broken) {
            for (SystemCheck.Row row : SystemCheck.evaluate(status).getRows()) {
                if (row.getState() == SystemCheck.State.FAILED
                    || row.getState() == SystemCheck.State.WARNING) {
                    assertNotNull(row.getAction(),
                        row.getName() + " is " + row.getState() + " with nothing to do about it");
                    assertFalse(row.getAction().trim().isEmpty(), row.getName());
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /** A status with everything working, for tests to break one thing at a time. */
    private static SystemStatus.Builder ready() {
        return SystemStatus.builder()
            .backendUrl(BackendUrlState.OK)
            .tokenSet(true)
            .loggedIn(true)
            .rsn("Jake")
            .fetch(SystemStatus.Fetch.LOADED)
            .board(board("Team One", true))
            .detectionEnabled(true)
            .dink(SystemStatus.Presence.RUNNING)
            .lootTracker(SystemStatus.Presence.RUNNING);
    }

    private static BingoBoard board(@Nullable String team, boolean eventOpen) {
        BingoItem item = new BingoItem(1, "Twisted bow");
        BingoTile tile = new BingoTile(
            "bow", "Twisted bow", 1, 1, 0,
            Collections.singletonList(item), Collections.emptyList(),
            false, null, null, null);
        return new BingoBoard(team, Collections.singletonList(tile), eventOpen);
    }

    private static SystemCheck.Row row(SystemCheck check, String name) {
        for (SystemCheck.Row row : check.getRows()) {
            if (row.getName().equals(name)) {
                return row;
            }
        }
        throw new AssertionError("No row named " + name);
    }

    private static SystemCheck.Row firstFailing(SystemCheck check) {
        for (SystemCheck.Row row : check.getRows()) {
            if (row.getState() == SystemCheck.State.FAILED) {
                return row;
            }
        }
        throw new AssertionError("Nothing failed");
    }
}
