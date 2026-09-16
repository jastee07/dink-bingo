package dinkbingo;

import java.util.Comparator;

import org.jetbrains.annotations.Nullable;

/**
 * How the sidebar orders tiles.
 * <p>
 * Every ordering other than {@link #BOARD_ORDER} is applied as a stable sort over the board's
 * own order, so tiles that compare equal stay in the order the organizer wrote them. Nothing
 * here touches the {@link BingoBoard} snapshot itself; sorting is a view concern only.
 */
public enum BoardSort {

    /** The order the backend returned, which is the order the organizer arranged the sheet. */
    BOARD_ORDER("Board order", null),

    NAME("Name", (a, b) -> a.getName().compareToIgnoreCase(b.getName())),

    /** Highest value first, so the tiles worth chasing are at the top. */
    POINTS("Points", (a, b) -> Integer.compare(b.getPoints(), a.getPoints())),

    /**
     * Closest to done first. A completed tile counts as fully complete, so it leads; use
     * {@link BoardProgressFilter} to take those out of the list.
     */
    PROGRESS("Progress", (a, b) -> Double.compare(fraction(b), fraction(a))),

    /** Unfinished work first, completed tiles last. */
    COMPLETION("Completion", Comparator.comparing(BingoTile::isClaimed));

    private final String label;

    @Nullable
    private final Comparator<BingoTile> comparator;

    BoardSort(String label, @Nullable Comparator<BingoTile> comparator) {
        this.label = label;
        this.comparator = comparator;
    }

    /** Null for {@link #BOARD_ORDER}, which is the absence of a sort rather than a rule. */
    @Nullable
    Comparator<BingoTile> comparator() {
        return comparator;
    }

    private static double fraction(BingoTile tile) {
        if (tile.isClaimed()) {
            return 1;
        }
        int required = Math.max(1, tile.getRequired());
        return Math.min(1, (double) tile.getProgress() / required);
    }

    @Override
    public String toString() {
        return label;
    }
}
