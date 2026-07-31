package dinkbingo;

import lombok.Value;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A logical board slot. Any one of {@link #options} can claim it.
 */
@Value
public class BingoTile {

    String id;

    String name;

    int points;

    List<BingoItem> options;

    boolean claimed;

    @Nullable
    String claimedBy;

    @Nullable
    String claimedAt;

    @Nullable
    BingoItem claimedItem;

    public BingoTile(
        String id,
        String name,
        int points,
        List<BingoItem> options,
        boolean claimed,
        @Nullable String claimedBy,
        @Nullable String claimedAt,
        @Nullable BingoItem claimedItem
    ) {
        this.id = id;
        this.name = name;
        this.points = points;
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
        this.claimed = claimed;
        this.claimedBy = claimedBy;
        this.claimedAt = claimedAt;
        this.claimedItem = claimedItem;
    }
}
