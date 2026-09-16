package dinkbingo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardFilterTest {

    @Test
    void noFilterKeepsTheBoardExactlyAsTheOrganizerWroteIt() {
        BingoBoard board = board(open("a", "Zulrah unique", 3), open("b", "Abyssal whip", 1));

        List<BingoTile> tiles = BoardFilter.NONE.apply(board);

        assertFalse(BoardFilter.NONE.isActive());
        assertEquals(Arrays.asList("Zulrah unique", "Abyssal whip"), names(tiles));
    }

    /** A player searching for a boss drop rarely knows which logical tile it belongs to. */
    @Test
    void searchMatchesTileNamesAndOptionNamesCaseInsensitively() {
        BingoTile grouped = grouped("raids", "Any two raids uniques", 2,
            Arrays.asList(new BingoItem(1, "Dexterous prayer scroll"),
                new BingoItem(2, "Twisted buckler")),
            Collections.emptyList());
        BingoBoard board = board(grouped, open("whip", "Abyssal whip", 1));

        assertEquals(Collections.singletonList("Any two raids uniques"),
            names(search("BUCKLER").apply(board)), "an option name must find its tile");
        assertEquals(Collections.singletonList("Any two raids uniques"),
            names(search("raids").apply(board)), "a tile name must find itself");
        assertEquals(Collections.singletonList("Abyssal whip"),
            names(search("  whip  ").apply(board)), "surrounding space is not part of a search");
        assertTrue(names(search("nothing here").apply(board)).isEmpty());
    }

    /** The winning item of a completed tile need not still be one of its options. */
    @Test
    void searchMatchesCreditedAndWinningItems() {
        BingoItem won = new BingoItem(9, "Tumeken's shadow");
        BingoTile claimed = new BingoTile("toa", "Tombs purple", 5, 1, 1,
            Collections.singletonList(new BingoItem(8, "Elidinis' ward")),
            Collections.emptyList(), true, "Jake", null, won);
        BingoTile partial = grouped("raids", "Any two raids uniques", 2,
            Collections.singletonList(new BingoItem(1, "Arcane prayer scroll")),
            Collections.singletonList(new BingoContribution(
                2, "Dexterous prayer scroll", "Jake", null)));
        BingoBoard board = board(claimed, partial);

        assertEquals(Collections.singletonList("Tombs purple"),
            names(search("shadow").apply(board)));
        assertEquals(Collections.singletonList("Any two raids uniques"),
            names(search("dexterous").apply(board)));
    }

    @Test
    void progressFilterSeparatesOpenPartialAndCompletedTiles() {
        BingoTile open = open("a", "Open tile", 1);
        BingoTile partial = grouped("b", "Partial tile", 2,
            Collections.singletonList(new BingoItem(2, "Arcane prayer scroll")),
            Collections.singletonList(new BingoContribution(1, "Dexterous prayer scroll", "Jake", null)));
        BingoTile completed = completed("c", "Completed tile");
        BingoBoard board = board(open, partial, completed);

        assertEquals(Collections.singletonList("Open tile"),
            names(filter("", BoardProgressFilter.OPEN, BoardSort.BOARD_ORDER, false).apply(board)));
        assertEquals(Collections.singletonList("Partial tile"),
            names(filter("", BoardProgressFilter.PARTIAL, BoardSort.BOARD_ORDER, false).apply(board)));
        assertEquals(Collections.singletonList("Completed tile"),
            names(filter("", BoardProgressFilter.COMPLETED, BoardSort.BOARD_ORDER, false).apply(board)));
    }

    /**
     * A grouped tile the backend reports with progress but no itemised contributions is still
     * partly done, and a player filtering for in-progress work has to see it.
     */
    @Test
    void progressAloneMarksATileAsInProgress() {
        BingoTile counted = new BingoTile("b", "Counted tile", 2, 3, 1,
            Collections.singletonList(new BingoItem(2, "Arcane prayer scroll")),
            Collections.emptyList(), false, null, null, null);

        assertTrue(BoardProgressFilter.PARTIAL.matches(counted));
        assertFalse(BoardProgressFilter.OPEN.matches(counted));
    }

    @Test
    void eligibleOnlyDropsClaimedTilesAndCreditedItems() {
        BingoTile partial = grouped("raids", "Any two raids uniques", 2,
            Arrays.asList(new BingoItem(1, "Dexterous prayer scroll"),
                new BingoItem(2, "Arcane prayer scroll")),
            Collections.singletonList(new BingoContribution(
                1, "Dexterous prayer scroll", "Jake", null)));
        BingoTile completed = completed("c", "Completed tile");
        BoardFilter filter = filter("", BoardProgressFilter.ALL, BoardSort.BOARD_ORDER, true);

        assertEquals(Collections.singletonList("Any two raids uniques"),
            names(filter.apply(board(partial, completed))));
        assertFalse(filter.showsOption(partial, "Dexterous prayer scroll", true),
            "a credited item can no longer be contributed");
        assertTrue(filter.showsOption(partial, "Arcane prayer scroll", false));
    }

    /** Searching a tile by name is how a player asks for everything that satisfies it. */
    @Test
    void aTileNameMatchKeepsEveryOneOfItsItems() {
        BingoTile grouped = grouped("raids", "Any two raids uniques", 2,
            Arrays.asList(new BingoItem(1, "Dexterous prayer scroll"),
                new BingoItem(2, "Twisted buckler")),
            Collections.emptyList());
        BoardFilter filter = search("raids");

        assertTrue(filter.showsOption(grouped, "Dexterous prayer scroll", false));
        assertTrue(filter.showsOption(grouped, "Twisted buckler", false));
    }

    @Test
    void sortingOrdersByNamePointsProgressAndCompletion() {
        BingoTile cheap = open("a", "Zamorak godsword", 1);
        BingoTile dear = open("b", "Twisted bow", 10);
        BingoTile nearlyDone = new BingoTile("c", "Any three barrows", 5, 3, 2,
            Collections.singletonList(new BingoItem(3, "Dharok's helm")),
            Collections.emptyList(), false, null, null, null);
        BingoTile done = completed("d", "Abyssal whip");
        BingoBoard board = board(cheap, dear, nearlyDone, done);

        assertEquals(Arrays.asList("Abyssal whip", "Any three barrows", "Twisted bow",
                "Zamorak godsword"),
            names(sortedBy(BoardSort.NAME).apply(board)));
        assertEquals(Arrays.asList("Twisted bow", "Any three barrows", "Zamorak godsword",
                "Abyssal whip"),
            names(sortedBy(BoardSort.POINTS).apply(board)),
            "tiles worth the same stay in board order");
        assertEquals(Arrays.asList("Abyssal whip", "Any three barrows", "Zamorak godsword",
                "Twisted bow"),
            names(sortedBy(BoardSort.PROGRESS).apply(board)),
            "a completed tile counts as fully complete");
        assertEquals(Arrays.asList("Zamorak godsword", "Twisted bow", "Any three barrows",
                "Abyssal whip"),
            names(sortedBy(BoardSort.COMPLETION).apply(board)),
            "unfinished work first, in board order");
    }

    /**
     * Ties are the common case on a board where most tiles are worth the same. Shuffling them
     * between renders would make the sidebar unreadable during an event.
     */
    @Test
    void sortingIsStableAndLeavesTheBoardUntouched() {
        BingoTile first = open("a", "First", 1);
        BingoTile second = open("b", "Second", 1);
        BingoTile third = open("c", "Third", 1);
        BingoBoard board = board(first, second, third);

        List<BingoTile> once = sortedBy(BoardSort.POINTS).apply(board);
        List<BingoTile> twice = sortedBy(BoardSort.POINTS).apply(board);

        assertEquals(Arrays.asList("First", "Second", "Third"), names(once));
        assertEquals(names(once), names(twice));
        assertEquals(Arrays.asList("First", "Second", "Third"), names(board.getTiles()),
            "sorting a view must not reorder the snapshot");
    }

    @Test
    void combinedFiltersNarrowTogether() {
        BingoTile cheapPartial = new BingoTile("a", "Cheap barrows", 1, 3, 1,
            Collections.singletonList(new BingoItem(1, "Dharok's helm")),
            Collections.emptyList(), false, null, null, null);
        BingoTile dearPartial = new BingoTile("b", "Dear barrows", 9, 3, 1,
            Collections.singletonList(new BingoItem(2, "Ahrim's hood")),
            Collections.emptyList(), false, null, null, null);
        BingoTile openTile = open("c", "Barrows starter", 4);
        BingoBoard board = board(cheapPartial, dearPartial, openTile);

        List<BingoTile> tiles = filter("barrows", BoardProgressFilter.PARTIAL,
            BoardSort.POINTS, true).apply(board);

        assertEquals(Arrays.asList("Dear barrows", "Cheap barrows"), names(tiles));
    }

    @Test
    void everyNarrowingOrReorderingControlCountsAsActive() {
        assertFalse(BoardFilter.NONE.isActive());
        assertFalse(search("   ").isActive(), "whitespace is not a search");
        assertTrue(search("whip").isActive());
        assertTrue(filter("", BoardProgressFilter.OPEN, BoardSort.BOARD_ORDER, false).isActive());
        assertTrue(sortedBy(BoardSort.NAME).isActive());
        assertTrue(filter("", BoardProgressFilter.ALL, BoardSort.BOARD_ORDER, true).isActive());
    }

    private static BoardFilter search(String text) {
        return filter(text, BoardProgressFilter.ALL, BoardSort.BOARD_ORDER, false);
    }

    private static BoardFilter sortedBy(BoardSort sort) {
        return filter("", BoardProgressFilter.ALL, sort, false);
    }

    private static BoardFilter filter(
        String search,
        BoardProgressFilter progress,
        BoardSort sort,
        boolean eligibleOnly
    ) {
        return new BoardFilter(search, progress, sort, eligibleOnly);
    }

    private static BingoBoard board(BingoTile... tiles) {
        return new BingoBoard("Team One", Arrays.asList(tiles), true);
    }

    private static List<String> names(List<BingoTile> tiles) {
        List<String> names = new ArrayList<>();
        for (BingoTile tile : tiles) {
            names.add(tile.getName());
        }
        return names;
    }

    private static BingoTile open(String id, String name, int points) {
        return new BingoTile(id, name, points, 1, 0,
            Collections.singletonList(new BingoItem(id.hashCode(), name)),
            Collections.emptyList(), false, null, null, null);
    }

    private static BingoTile grouped(
        String id,
        String name,
        int required,
        List<BingoItem> options,
        List<BingoContribution> credited
    ) {
        return new BingoTile(id, name, 1, required, credited.size(), options, credited,
            false, null, null, null);
    }

    private static BingoTile completed(String id, String name) {
        BingoItem item = new BingoItem(id.hashCode(), name);
        return new BingoTile(id, name, 1, 1, 1, Collections.singletonList(item),
            Collections.emptyList(), true, "Jake", null, item);
    }
}
