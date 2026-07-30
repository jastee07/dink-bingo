package dinkbingo;

import lombok.Value;
import org.jetbrains.annotations.Nullable;

/**
 * A single tile on the board, as reported by the backend.
 */
@Value
public class BingoItem {

    int id;

    String name;

    int points;

    boolean claimed;

    /** RSN of the teammate who claimed this tile, when {@link #claimed}. */
    @Nullable
    String claimedBy;

    @Nullable
    String claimedAt;
}
