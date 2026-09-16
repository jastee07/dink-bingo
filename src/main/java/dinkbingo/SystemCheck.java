package dinkbingo;

import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns a {@link SystemStatus} snapshot into the rows of the readiness report.
 * <p>
 * Event-day failures are nearly always configuration spread across RuneLite, this plugin,
 * Dink, the Apps Script deployment, and the organizer's sheet. Until now the sidebar could
 * only prove that a board loaded, which answers one link in that chain; everything else was
 * found out by getting a rare drop and watching nothing happen.
 * <p>
 * Every row is derived from state the plugin already holds, so the report is a read: running
 * it cannot touch Claims, Audit, Items, Teams, or Config, and it makes no request the ordinary
 * board refresh does not already make.
 * <p>
 * Pure and free of Swing on purpose -- the wording and the states are the part worth testing,
 * and they are tested here rather than through a rendered panel.
 */
@Getter
public final class SystemCheck {

    /**
     * How a single check came out.
     * <p>
     * {@link #NOT_CHECKED} is deliberately distinct from {@link #READY}: a check that could
     * not run yet must never read as one that passed, which is the whole failure mode this
     * view exists to remove.
     */
    public enum State {
        NOT_CHECKED,
        CHECKING,
        READY,
        /** Usable, but something will not work the way the player expects. */
        WARNING,
        FAILED
    }

    /** One line of the report: what was checked, how it came out, and what to do about it. */
    @Getter
    public static final class Row {

        private final String name;
        private final State state;
        private final String detail;

        /** What the player should do, or null when there is nothing to do. */
        @Nullable
        private final String action;

        private Row(String name, State state, String detail, @Nullable String action) {
            this.name = name;
            this.state = state;
            this.detail = detail;
            this.action = action;
        }

        static Row ready(String name, String detail) {
            return new Row(name, State.READY, detail, null);
        }

        static Row ready(String name, String detail, String action) {
            return new Row(name, State.READY, detail, action);
        }

        static Row checking(String name, String detail) {
            return new Row(name, State.CHECKING, detail, null);
        }

        static Row notChecked(String name, String detail) {
            return new Row(name, State.NOT_CHECKED, detail, null);
        }

        static Row notChecked(String name, String detail, String action) {
            return new Row(name, State.NOT_CHECKED, detail, action);
        }

        static Row warning(String name, String detail, String action) {
            return new Row(name, State.WARNING, detail, action);
        }

        static Row failed(String name, String detail, String action) {
            return new Row(name, State.FAILED, detail, action);
        }
    }

    private final List<Row> rows;

    private SystemCheck(List<Row> rows) {
        this.rows = Collections.unmodifiableList(rows);
    }

    /**
     * Ordered most fundamental first, so the first failing row is the one to fix: a rejected
     * token is not worth reading while the URL is still unset.
     */
    public static SystemCheck evaluate(SystemStatus status) {
        List<Row> rows = new ArrayList<>(11);
        rows.add(backendUrlRow(status));
        rows.add(backendRow(status));
        rows.add(tokenRow(status));
        rows.add(versionRow(status));
        rows.add(playerRow(status));
        rows.add(teamRow(status));
        rows.add(eventRow(status));
        rows.add(boardRow(status));
        rows.add(detectionRow(status));
        rows.add(dinkRow(status));
        rows.add(lootTrackerRow(status));
        return new SystemCheck(rows);
    }

    /** The worst state any row is in, which is how ready the player actually is. */
    public State getState() {
        State worst = State.READY;
        for (Row row : rows) {
            if (severity(row.getState()) > severity(worst)) {
                worst = row.getState();
            }
        }
        return worst;
    }

    /**
     * The report in a few words, for the button that opens it.
     * <p>
     * Counts rather than a single state, because "1 problem" tells a player whether the thing
     * they just fixed was the only one.
     */
    public String summary() {
        int failed = 0;
        int warnings = 0;
        boolean checking = false;
        boolean unchecked = false;
        for (Row row : rows) {
            switch (row.getState()) {
                case FAILED:
                    failed++;
                    break;
                case WARNING:
                    warnings++;
                    break;
                case CHECKING:
                    checking = true;
                    break;
                case NOT_CHECKED:
                    unchecked = true;
                    break;
                default:
                    break;
            }
        }
        if (failed > 0) {
            return count(failed, "problem");
        }
        if (warnings > 0) {
            return count(warnings, "warning");
        }
        if (checking) {
            return "checking…";
        }
        return unchecked ? "not checked" : "ready";
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static int severity(State state) {
        switch (state) {
            case FAILED:
                return 4;
            case WARNING:
                return 3;
            case CHECKING:
                return 2;
            case NOT_CHECKED:
                return 1;
            default:
                return 0;
        }
    }

    // ------------------------------------------------------------------
    // rows
    // ------------------------------------------------------------------

    /**
     * Never echoes the configured value. The URL identifies the organizer's deployment and a
     * readiness view is the screenshot a player posts in a clan chat when they are stuck.
     */
    private static Row backendUrlRow(SystemStatus status) {
        switch (status.getBackendUrl()) {
            case OK:
                return Row.ready("Backend URL", "Set");
            case INVALID:
                return Row.failed("Backend URL", "Not a URL",
                    "Check Backend URL for a typo or a stray space. It should start with "
                        + "https:// and end in /exec.");
            case INSECURE:
                return Row.failed("Backend URL", "Not HTTPS",
                    "Nothing is sent over a plain HTTP URL. Ask the organizer for the "
                        + "https:// version of the web app link.");
            default:
                return Row.failed("Backend URL", "Not set",
                    "Paste the organizer's Apps Script web app link -- it ends in /exec -- "
                        + "into Backend URL.");
        }
    }

    /**
     * Whether the backend can be reached at all, which is a different question from whether it
     * liked the request. A refusal still proves the round trip works, so the reasons a player
     * can act on are reported by the rows below rather than counted twice here.
     */
    private static Row backendRow(SystemStatus status) {
        if (!status.getBackendUrl().isUsable()) {
            return Row.notChecked("Backend", "Not checked — no usable Backend URL");
        }
        switch (status.getFetch()) {
            case CHECKING:
                return Row.checking("Backend", "Contacting the backend…");
            case LOADED:
                return Row.ready("Backend", "Answered");
            case UNREACHABLE:
                return Row.failed("Backend", "No response",
                    "Check your connection, then run the checks again. If it keeps failing, "
                        + "ask the organizer whether the deployment has moved.");
            case REJECTED:
                if (BingoErrors.isBadToken(status.getBackendError())
                    || BingoErrors.isUnsupportedRequest(status.getBackendError())) {
                    // Named by the token or version row, which say what to do about it.
                    return Row.ready("Backend", "Answered, but refused the request");
                }
                // Anything else is a reason those rows do not cover, so it is reported here
                // rather than disappearing. The wording is bounded and sanitized.
                return Row.warning("Backend", "Answered, but refused the request",
                    BingoErrors.describeBoardError(status.getBackendError()));
            default:
                return Row.notChecked("Backend", "Not checked yet");
        }
    }

    private static Row tokenRow(SystemStatus status) {
        if (!status.isTokenSet()) {
            return Row.failed("Event token", "Not set",
                "Copy the token from the organizer's Config tab into Event Token.");
        }
        if (status.getFetch() == SystemStatus.Fetch.REJECTED
            && BingoErrors.isBadToken(status.getBackendError())) {
            return Row.failed("Event token", "Rejected by the backend",
                "The token no longer matches the organizer's Config tab. Ask for the "
                    + "current one.");
        }
        if (status.getFetch() == SystemStatus.Fetch.LOADED) {
            return Row.ready("Event token", "Accepted");
        }
        return Row.notChecked("Event token", "Set, not checked yet");
    }

    /**
     * There is no version number on the wire yet, so this reports the one incompatibility the
     * current protocol can actually prove: a deployment that does not understand the request
     * at all, which is what an Apps Script left on an old copy of {@code Code.gs} looks like.
     * Everything else reads "not reported" rather than inventing a pass.
     */
    private static Row versionRow(SystemStatus status) {
        if (status.getFetch() == SystemStatus.Fetch.REJECTED
            && BingoErrors.isUnsupportedRequest(status.getBackendError())) {
            return Row.failed("Backend version", "The deployment did not understand the request",
                "Ask the organizer to re-deploy the Apps Script from the current "
                    + "backend/Code.gs.");
        }
        if (status.getFetch() == SystemStatus.Fetch.LOADED) {
            return Row.ready("Backend version", "Answers this client's requests");
        }
        return Row.notChecked("Backend version", "Not reported");
    }

    private static Row playerRow(SystemStatus status) {
        if (!status.isLoggedIn()) {
            return Row.notChecked("RuneScape name", "Not logged in",
                "Log in to check your team and your board.");
        }
        String rsn = status.getRsn();
        if (rsn == null || rsn.trim().isEmpty()) {
            return Row.notChecked("RuneScape name", "Not available yet",
                "Wait for the world to finish loading, then run the checks again.");
        }
        return Row.ready("RuneScape name", rsn);
    }

    private static Row teamRow(SystemStatus status) {
        BingoBoard board = status.getBoard();
        if (board == null) {
            return Row.notChecked("Team", "Not checked yet");
        }
        if (board.isConfigured()) {
            return Row.ready("Team", String.valueOf(board.getTeam()));
        }
        String rsn = status.getRsn();
        String who = rsn == null || rsn.trim().isEmpty() ? "your RuneScape name" : rsn;
        return Row.failed("Team", "Your name is not on the Teams tab",
            "Ask the organizer to add " + who + " to the Teams tab, spelled exactly as it "
                + "appears in game.");
    }

    private static Row eventRow(SystemStatus status) {
        BingoBoard board = status.getBoard();
        if (board == null) {
            return Row.notChecked("Event", "Not checked yet");
        }
        if (board.isEventOpen()) {
            return Row.ready("Event", "Open");
        }
        return Row.warning("Event", "Closed — drops will not be claimed",
            "If it should be running, ask the organizer to open the event on the Config tab.");
    }

    /**
     * How much of the board is on screen and whether it is still live. When it last loaded is
     * on the line above the report, which is visible from here and from the board itself, so
     * it is deliberately not repeated as a second timestamp that could disagree.
     */
    private static Row boardRow(SystemStatus status) {
        BingoBoard board = status.getBoard();
        if (board == null) {
            return Row.notChecked("Board", "Not loaded yet");
        }
        String tiles = board.getRemainingCount() + " of " + board.getTiles().size()
            + " tiles left";
        switch (status.getFetch()) {
            case LOADED:
                return Row.ready("Board", tiles);
            case CHECKING:
                return Row.checking("Board", "Refreshing…");
            case REJECTED:
                return Row.warning("Board", "On screen, but no longer live",
                    "These rows are the last board that loaded. Fix the problem above, then "
                        + "run the checks again.");
            case UNREACHABLE:
                return Row.warning("Board", "On screen, but the last refresh failed",
                    "The line above the report says when it last updated.");
            default:
                return Row.notChecked("Board", tiles);
        }
    }

    private static Row detectionRow(SystemStatus status) {
        if (!status.isDetectionEnabled()) {
            return Row.failed("Claim detection", "Suspended — the backend refused a refresh",
                "Drops are not being submitted. Run the checks again once the problem above "
                    + "is fixed.");
        }
        BingoBoard board = status.getBoard();
        if (board == null) {
            return Row.notChecked("Claim detection", "Not checked yet");
        }
        if (board.isConfigured() && board.isEventOpen()) {
            return Row.ready("Claim detection", "Watching for drops");
        }
        return Row.warning("Claim detection", "Idle",
            "Nothing is submitted while the rows above are unresolved.");
    }

    /**
     * A missing Dink is a warning and never a failure: the sheet is what decides a claim, so
     * tiles still count without it. What is lost is the announcement, which is why the row
     * says so rather than reading as a broken plugin.
     */
    private static Row dinkRow(SystemStatus status) {
        switch (status.getDink()) {
            case RUNNING:
                return Row.ready("Dink", "Installed and running",
                    "Dink only accepts claims when its External Plugin Requests → Enable "
                        + "External Plugin Notifications setting is on. Use Test Dink below to "
                        + "confirm a message reaches Discord.");
            case DISABLED:
                return Row.warning("Dink", "Installed, but switched off",
                    "Turn Dink on in the RuneLite plugin list. Tiles are still claimed "
                        + "without it, but nothing is announced.");
            case MISSING:
                return Row.warning("Dink", "Not installed",
                    "Install Dink from the Plugin Hub. Tiles are still claimed without it, "
                        + "but nothing is announced.");
            default:
                return Row.notChecked("Dink", "Not checked");
        }
    }

    /**
     * Loot Tracker posts the {@code LootReceived} event this plugin reads for everything that
     * is not an ordinary NPC drop -- chests, caskets, and the rest. NPC drops arrive on the
     * client's own events and are unaffected, which is exactly the distinction a player needs
     * before deciding whether to care.
     */
    private static Row lootTrackerRow(SystemStatus status) {
        switch (status.getLootTracker()) {
            case RUNNING:
                return Row.ready("Loot Tracker", "Installed and running");
            case DISABLED:
            case MISSING:
                return Row.warning("Loot Tracker",
                    "Off — chest and casket drops will not be seen",
                    "Turn Loot Tracker on in the RuneLite plugin list. Ordinary NPC drops "
                        + "are detected either way.");
            default:
                return Row.notChecked("Loot Tracker", "Not checked");
        }
    }
}
