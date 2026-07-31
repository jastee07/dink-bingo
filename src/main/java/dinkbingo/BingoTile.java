package dinkbingo;

import lombok.Value;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A logical board slot completed by {@link #required} distinct items from {@link #options}.
 */
@Value
public class BingoTile {

    String id;

    String name;

    int points;

    int required;

    int progress;

    List<BingoItem> options;

    List<BingoContribution> claimedItems;

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
        int required,
        int progress,
        List<BingoItem> options,
        List<BingoContribution> claimedItems,
        boolean claimed,
        @Nullable String claimedBy,
        @Nullable String claimedAt,
        @Nullable BingoItem claimedItem
    ) {
        this.id = id;
        this.name = name;
        this.points = points;
        this.required = required;
        this.progress = progress;
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
        this.claimedItems = Collections.unmodifiableList(new ArrayList<>(claimedItems));
        this.claimed = claimed;
        this.claimedBy = claimedBy;
        this.claimedAt = claimedAt;
        this.claimedItem = claimedItem;
    }
}
