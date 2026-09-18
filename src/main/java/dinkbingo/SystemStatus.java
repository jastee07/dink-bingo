package dinkbingo;

import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the readiness checks are allowed to look at, gathered once.
 * <p>
 * Deliberately a plain immutable snapshot rather than a set of live references. The checks
 * read from several threads' worth of state -- a config setting, the game client, the last
 * board fetch, the RuneLite plugin list -- and evaluating them against the real objects would
 * mean a report whose rows disagree with each other because the world moved between two of
 * them. The plugin gathers this once and {@link SystemCheck} turns it into rows with no
 * further lookups.
 * <p>
 * Nothing secret belongs here. The event token is represented by {@link #tokenSet} and the
 * backend URL by a {@link BackendUrlState}, so no check can put either on screen even by
 * accident.
 * <p>
 * The builder defaults describe a plugin that has done nothing yet, so a partially filled
 * status reports "not checked" rather than inventing a passing row.
 */
@Getter
// Named Builder rather than Lombok's default SystemStatusBuilder: the nested name reads
// better at the one call site that fills it in, and in the tests that vary one field at a time.
@Builder(builderClassName = "Builder")
public final class SystemStatus {

    /** What the last board fetch did. */
    public enum Fetch {
        /** No fetch has been attempted, or the plugin is not configured to make one. */
        NOT_CHECKED,
        /** A fetch is in flight. */
        CHECKING,
        /** The backend answered with a board. */
        LOADED,
        /** The backend answered and refused; {@link #getBackendError()} is its reason. */
        REJECTED,
        /** No usable response arrived at all. */
        UNREACHABLE
    }

    /** Whether a RuneLite plugin this one leans on is installed and running. */
    public enum Presence {
        /** The plugin list could not be read, so nothing is claimed either way. */
        UNKNOWN,
        MISSING,
        /** Installed, but switched off, so it posts no events. */
        DISABLED,
        RUNNING
    }

    @Builder.Default
    private final BackendUrlState backendUrl = BackendUrlState.UNSET;

    private final boolean tokenSet;

    private final boolean loggedIn;

    /** The local player's display name, or null when not logged in. */
    @Nullable
    private final String rsn;

    @Builder.Default
    private final Fetch fetch = Fetch.NOT_CHECKED;

    /** The backend's raw {@code error} value for a {@link Fetch#REJECTED} fetch. */
    @Nullable
    private final String backendError;

    /** The last board that loaded, or null if none ever has. */
    @Nullable
    private final BingoBoard board;

    @Builder.Default
    private final boolean detectionEnabled = true;

    @Builder.Default
    private final Presence dink = Presence.UNKNOWN;

    @Builder.Default
    private final Presence lootTracker = Presence.UNKNOWN;
}
