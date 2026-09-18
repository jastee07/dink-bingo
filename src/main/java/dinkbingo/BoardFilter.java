package dinkbingo;

import lombok.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The sidebar's local search, filter, and sort selection, applied to a board snapshot.
 * <p>
 * Purely a view over an immutable {@link BingoBoard}: it decides what is on screen and in what
 * order, and never touches claim eligibility or backend state. A tile hidden by a filter is
 * still claimed normally when it drops, which is why this lives beside the panel rather than
 * inside {@link BingoDetector}.
 */
@Value
public class BoardFilter {

    public static final BoardFilter NONE =
        new BoardFilter("", BoardProgressFilter.ALL, BoardSort.BOARD_ORDER, false);

    /** Matched case-insensitively against tile names and option item names. */
    String search;

    BoardProgressFilter progress;

    BoardSort sort;

    /** Hide anything that can no longer be contributed to: claimed tiles, credited items. */
    boolean eligibleOnly;

    public BoardFilter(
        String search,
        BoardProgressFilter progress,
        BoardSort sort,
        boolean eligibleOnly
    ) {
        this.search = search == null ? "" : search;
        this.progress = progress == null ? BoardProgressFilter.ALL : progress;
        this.sort = sort == null ? BoardSort.BOARD_ORDER : sort;
        this.eligibleOnly = eligibleOnly;
    }

    /** Whether anything here narrows or reorders the board. Drives the empty-result wording. */
    public boolean isActive() {
        return !search.trim().isEmpty()
            || progress != BoardProgressFilter.ALL
            || sort != BoardSort.BOARD_ORDER
            || eligibleOnly;
    }

    /**
     * The tiles to render, filtered and ordered.
     * <p>
     * The returned list is a copy; the board's own tile list is never reordered. Sorting is
     * stable over board order, so equal tiles -- two tiles worth the same points, say -- stay
     * in the order the organizer arranged them rather than shuffling between renders.
     */
    public List<BingoTile> apply(BingoBoard board) {
        List<BingoTile> kept = new ArrayList<>();
        for (BingoTile tile : board.getTiles()) {
            if (!progress.matches(tile)) {
                continue;
            }
            if (eligibleOnly && tile.isClaimed()) {
                continue;
            }
            if (!matchesSearch(tile)) {
                continue;
            }
            kept.add(tile);
        }
        Comparator<BingoTile> comparator = sort.comparator();
        if (comparator != null) {
            kept.sort(comparator);
        }
        return kept;
    }

    /**
     * Whether one option row of an already-kept tile belongs on screen.
     * <p>
     * Only the item view calls this. Searching for a tile name keeps all of its items, so a
     * player who knows the tile but not its contents still sees the whole list.
     *
     * @param optionName the item named on the row
     * @param credited whether this option has already been counted toward the tile
     */
    public boolean showsOption(BingoTile tile, String optionName, boolean credited) {
        if (eligibleOnly && (credited || tile.isClaimed())) {
            return false;
        }
        String needle = needle();
        return needle.isEmpty()
            || contains(tile.getName(), needle)
            || contains(optionName, needle);
    }

    /** A tile matches on its own name or on any item that could satisfy it. */
    private boolean matchesSearch(BingoTile tile) {
        String needle = needle();
        if (needle.isEmpty() || contains(tile.getName(), needle)) {
            return true;
        }
        for (BingoItem option : tile.getOptions()) {
            if (contains(option.getName(), needle)) {
                return true;
            }
        }
        // A completed tile's winning item need not still be among the options.
        for (BingoContribution contribution : tile.getClaimedItems()) {
            if (contains(contribution.getName(), needle)) {
                return true;
            }
        }
        BingoItem won = tile.getClaimedItem();
        return won != null && contains(won.getName(), needle);
    }

    private String needle() {
        return search.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Case-insensitive in the one locale, not the player's. A Turkish client lower-casing
     * "Twisted buckler" its own way would stop matching what the sheet says.
     */
    private static boolean contains(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle);
    }
}
