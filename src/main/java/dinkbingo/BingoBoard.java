package dinkbingo;

import lombok.Getter;
import org.jetbrains.annotations.Nullable;

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

    private final List<BingoItem> items;

    private final boolean eventOpen;

    private final Map<Integer, BingoItem> byId;

    /** Ids of tiles this team has not claimed yet — the set detection matches against. */
    private final Set<Integer> remaining;

    public BingoBoard(@Nullable String team, List<BingoItem> items, boolean eventOpen) {
        this.team = team;
        this.items = Collections.unmodifiableList(items);
        this.eventOpen = eventOpen;

        Map<Integer, BingoItem> byId = new LinkedHashMap<>(items.size());
        Set<Integer> remaining = new LinkedHashSet<>();
        for (BingoItem item : items) {
            byId.put(item.getId(), item);
            if (!item.isClaimed()) {
                remaining.add(item.getId());
            }
        }
        this.byId = Collections.unmodifiableMap(byId);
        this.remaining = Collections.unmodifiableSet(remaining);
    }

    /**
     * Whether a drop of this item should be submitted as a claim.
     * <p>
     * A tile we already know is claimed is deliberately not resubmitted: the backend would
     * reject it anyway, and resubmitting is how the audit log fills with noise.
     */
    public boolean isClaimable(int itemId) {
        return eventOpen && team != null && remaining.contains(itemId);
    }

    public int getRemainingCount() {
        return remaining.size();
    }

    /** Whether the backend recognised our RSN and gave us a team. */
    public boolean isConfigured() {
        return team != null;
    }
}
