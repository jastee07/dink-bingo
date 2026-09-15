package dinkbingo;

import org.jetbrains.annotations.Nullable;

/**
 * The outcome of one board fetch: either a board, or why we do not have one.
 * <p>
 * A board fetch can fail two very different ways, and the panel says something different for
 * each. The backend can answer and reject the request, in which case its {@code error} names
 * a setup mistake the organizer can actually fix, or the round trip can fail outright, which
 * is the only case that is genuinely about the player's connection. Returning {@code null}
 * for both is what made every failure render as "check your connection".
 */
public final class BoardResult {

    /** The round trip never produced a usable response, so no backend reason exists. */
    private static final BoardResult UNREACHABLE = new BoardResult(null, null);

    @Nullable
    private final BingoBoard board;

    @Nullable
    private final String backendError;

    private BoardResult(@Nullable BingoBoard board, @Nullable String backendError) {
        this.board = board;
        this.backendError = backendError;
    }

    public static BoardResult of(BingoBoard board) {
        return new BoardResult(board, null);
    }

    /** The backend answered and refused the fetch. {@code backendError} is its raw reason. */
    public static BoardResult rejected(String backendError) {
        return new BoardResult(null, backendError);
    }

    /** No usable response arrived: transport failure, a bad HTTP status, or unparseable body. */
    public static BoardResult unreachable() {
        return UNREACHABLE;
    }

    public boolean isSuccess() {
        return board != null;
    }

    @Nullable
    public BingoBoard getBoard() {
        return board;
    }

    /** The backend's raw {@code error} value, or null when the response never arrived. */
    @Nullable
    public String getBackendError() {
        return backendError;
    }
}
