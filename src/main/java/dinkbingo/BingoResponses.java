package dinkbingo;

import lombok.Data;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Wire types for the Apps Script backend. Deserialized by Gson, so these are mutable
 * beans with field names matching the JSON exactly.
 */
public class BingoResponses {

    private BingoResponses() {
    }

    /** Outcome of a claim attempt. Only {@link #CLAIMED} triggers an announcement. */
    public static final String CLAIMED = "claimed";
    public static final String DUPLICATE = "duplicate";
    public static final String NOT_ON_BOARD = "not_on_board";
    public static final String NOT_ON_TEAM = "not_on_team";
    public static final String EVENT_CLOSED = "event_closed";
    public static final String ERROR = "error";

    @Data
    public static class BoardRequest {
        String action;
        String token;
        String rsn;
    }

    @Data
    public static class BoardResponse {
        String status;
        @Nullable
        String team;
        int remaining;
        int total;
        boolean eventOpen;
        @Nullable
        List<BoardItem> items;
        @Nullable
        String error;
    }

    @Data
    public static class BoardItem {
        int id;
        String name;
        int points;
        boolean claimed;
        @Nullable
        String claimedBy;
        @Nullable
        String claimedAt;
    }

    @Data
    public static class ClaimRequest {
        String token;
        String rsn;
        int itemId;
        String itemName;
        int quantity;
        String source;
        String claimId;
    }

    @Data
    public static class ClaimResponse {
        String status;
        boolean replay;
        @Nullable
        String team;
        int itemId;
        @Nullable
        String itemName;
        int points;
        int remaining;
        int total;
        @Nullable
        String claimedBy;
        @Nullable
        String claimedAt;
        @Nullable
        String error;
        boolean retryable;

        public boolean isClaimed() {
            return CLAIMED.equals(status);
        }

        public boolean isResolvedOutcome() {
            return CLAIMED.equals(status)
                || DUPLICATE.equals(status)
                || NOT_ON_BOARD.equals(status)
                || NOT_ON_TEAM.equals(status)
                || EVENT_CLOSED.equals(status);
        }
    }
}
