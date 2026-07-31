package dinkbingo;

import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable snapshot of the board for one team.
 * <p>
 * Replaced wholesale on each fetch rather than mutated, so a detection thread reading
 * {@link #getRemaining()} always sees a coherent view.
 */
@Getter
public final class BingoBoard {

    public static final BingoBoard EMPTY = new BingoBoard(null, Collections.emptyList(), false);

    /** The team the local player belongs to, or null if the backend does not recognise the RSN. */
    @Nullable
    private final String team;

    private final List<BingoTile> tiles;

    private final boolean eventOpen;

    private final Map<Integer, BingoTile> byItemId;

    private final Map<String, BingoTile> byTileId;

    /** Logical tile ids this team has not claimed yet. */
    private final Set<String> remaining;

    /** Accepted item ids not yet credited for their logical tile. */
    private final Set<Integer> openItemIds;

    public BingoBoard(@Nullable String team, List<BingoTile> tiles, boolean eventOpen) {
        this.team = team;
        this.tiles = Collections.unmodifiableList(new ArrayList<>(tiles));
        this.eventOpen = eventOpen;

        Map<Integer, BingoTile> byItemId = new LinkedHashMap<>();
        Map<String, BingoTile> byTileId = new LinkedHashMap<>();
        Set<String> remaining = new LinkedHashSet<>();
        Set<Integer> openItemIds = new LinkedHashSet<>();
        for (BingoTile tile : tiles) {
            if (byTileId.put(tile.getId(), tile) != null) {
                throw new IllegalArgumentException("Duplicate tile id: " + tile.getId());
            }
            Set<Integer> credited = new LinkedHashSet<>();
            for (BingoContribution contribution : tile.getClaimedItems()) {
                credited.add(contribution.getId());
            }
            for (BingoItem option : tile.getOptions()) {
                if (byItemId.put(option.getId(), tile) != null) {
                    throw new IllegalArgumentException("Item belongs to multiple tiles: " + option.getId());
                }
                if (!tile.isClaimed() && !credited.contains(option.getId())) {
                    openItemIds.add(option.getId());
                }
            }
            if (!tile.isClaimed()) {
                remaining.add(tile.getId());
            }
        }
        this.byItemId = Collections.unmodifiableMap(byItemId);
        this.byTileId = Collections.unmodifiableMap(byTileId);
        this.remaining = Collections.unmodifiableSet(remaining);
        this.openItemIds = Collections.unmodifiableSet(openItemIds);
    }

    /**
     * Whether a drop of this item should be submitted as a claim.
     * <p>
     * A tile we already know is claimed is deliberately not resubmitted: the backend would
     * reject it anyway, and resubmitting is how the audit log fills with noise.
     */
    public boolean isClaimable(int itemId) {
        BingoTile tile = byItemId.get(itemId);
        return eventOpen && team != null && tile != null && openItemIds.contains(itemId);
    }

    public int getRemainingCount() {
        return remaining.size();
    }

    @Nullable
    public BingoItem findOption(int itemId) {
        BingoTile tile = byItemId.get(itemId);
        if (tile == null) {
            return null;
        }
        for (BingoItem option : tile.getOptions()) {
            if (option.getId() == itemId) {
                return option;
            }
        }
        return null;
    }

    /** Whether the backend recognised our RSN and gave us a team. */
    public boolean isConfigured() {
        return team != null;
    }
}
