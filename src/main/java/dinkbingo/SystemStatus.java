package dinkbingo;

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
 */
@Getter
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

    private final BackendUrlState backendUrl;
    private final boolean tokenSet;
    private final boolean loggedIn;

    /** The local player's display name, or null when not logged in. */
    @Nullable
    private final String rsn;

    private final Fetch fetch;

    /** The backend's raw {@code error} value for a {@link Fetch#REJECTED} fetch. */
    @Nullable
    private final String backendError;

    /** The last board that loaded, or null if none ever has. */
    @Nullable
    private final BingoBoard board;

    private final boolean detectionEnabled;
    private final Presence dink;
    private final Presence lootTracker;

    private SystemStatus(Builder builder) {
        this.backendUrl = builder.backendUrl;
        this.tokenSet = builder.tokenSet;
        this.loggedIn = builder.loggedIn;
        this.rsn = builder.rsn;
        this.fetch = builder.fetch;
        this.backendError = builder.backendError;
        this.board = builder.board;
        this.detectionEnabled = builder.detectionEnabled;
        this.dink = builder.dink;
        this.lootTracker = builder.lootTracker;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Defaults describe a plugin that has done nothing yet, so a partially filled status
     * reports "not checked" rather than inventing a passing row.
     */
    public static final class Builder {

        private BackendUrlState backendUrl = BackendUrlState.UNSET;
        private boolean tokenSet;
        private boolean loggedIn;
        @Nullable
        private String rsn;
        private Fetch fetch = Fetch.NOT_CHECKED;
        @Nullable
        private String backendError;
        @Nullable
        private BingoBoard board;
        private boolean detectionEnabled = true;
        private Presence dink = Presence.UNKNOWN;
        private Presence lootTracker = Presence.UNKNOWN;

        private Builder() {
        }

        public Builder backendUrl(BackendUrlState backendUrl) {
            this.backendUrl = backendUrl;
            return this;
        }

        public Builder tokenSet(boolean tokenSet) {
            this.tokenSet = tokenSet;
            return this;
        }

        public Builder loggedIn(boolean loggedIn) {
            this.loggedIn = loggedIn;
            return this;
        }

        public Builder rsn(@Nullable String rsn) {
            this.rsn = rsn;
            return this;
        }

        public Builder fetch(Fetch fetch) {
            this.fetch = fetch;
            return this;
        }

        public Builder backendError(@Nullable String backendError) {
            this.backendError = backendError;
            return this;
        }

        public Builder board(@Nullable BingoBoard board) {
            this.board = board;
            return this;
        }

        public Builder detectionEnabled(boolean detectionEnabled) {
            this.detectionEnabled = detectionEnabled;
            return this;
        }

        public Builder dink(Presence dink) {
            this.dink = dink;
            return this;
        }

        public Builder lootTracker(Presence lootTracker) {
            this.lootTracker = lootTracker;
            return this;
        }

        public SystemStatus build() {
            return new SystemStatus(this);
        }
    }
}
