package dinkbingo;

import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BingoPanelTest {

    /**
     * During an event a board that stopped refreshing looks exactly like one that is current,
     * which is the difference between trusting the tile list and reloading the client.
     */
    @Test
    void freshnessLineReportsEachRefreshOutcome() throws Exception {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        BingoPanel panel = new BingoPanel(itemManager);
        panel.setClock(Clock.fixed(Instant.parse("2026-09-15T14:32:00Z"), ZoneOffset.UTC));

        BingoBoard board = new BingoBoard("Team One", tiles(), true);

        // Nothing has loaded yet, so there is nothing to describe.
        panel.render(board, true, BoardView.NAMED_TILES, false);
        assertEquals("", panel.freshnessText());

        panel.markRefreshing();
        assertEquals("Refreshing\u2026", panel.freshnessText());

        panel.markRefreshSucceeded();
        panel.render(board, true, BoardView.NAMED_TILES, false);
        String updated = panel.freshnessText();
        assertTrue(updated.startsWith("Updated "), updated);

        // A failed round trip keeps the rows and the time they were last confirmed.
        panel.markRefreshFailed();
        String failed = panel.freshnessText();
        assertTrue(failed.startsWith("Last updated "), failed);
        assertTrue(failed.endsWith("refresh failed"), failed);
        assertEquals(updated.substring("Updated ".length()),
            failed.substring("Last updated ".length(), failed.indexOf(" \u2014")),
            "a failed refresh must not move the last-success time");

        // Recovery clears the warning.
        panel.markRefreshSucceeded();
        panel.render(board, true, BoardView.NAMED_TILES, false);
        assertEquals(updated, panel.freshnessText());
    }

    /**
     * The board is also re-rendered when the player switches view or hides completed tiles.
     * Those are not refreshes, and stamping them as one would report a board as current when
     * nothing was fetched.
     */
    @Test
    void reRenderingForAViewChangeDoesNotLookLikeARefresh() throws Exception {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        BingoPanel panel = new BingoPanel(itemManager);
        panel.setClock(Clock.fixed(Instant.parse("2026-09-15T14:32:00Z"), ZoneOffset.UTC));
        BingoBoard board = new BingoBoard("Team One", tiles(), true);

        panel.markRefreshSucceeded();
        panel.render(board, true, BoardView.NAMED_TILES, false);
        String afterRefresh = panel.freshnessText();

        panel.setClock(Clock.fixed(Instant.parse("2026-09-15T18:00:00Z"), ZoneOffset.UTC));
        panel.render(board, true, BoardView.POSSIBLE_ITEMS, true);
        flush();

        assertEquals(afterRefresh, panel.freshnessText(),
            "switching view must not restamp the board as freshly fetched");
    }

    /** A rejection keeps the rows but must not let them read as current. */
    @Test
    void aRejectedRefreshLabelsTheBoardAndKeepsItsAge() throws Exception {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        BingoPanel panel = new BingoPanel(itemManager);
        panel.setClock(Clock.fixed(Instant.parse("2026-09-15T14:32:00Z"), ZoneOffset.UTC));
        BingoBoard board = new BingoBoard("Team One", tiles(), true);

        panel.markRefreshSucceeded();
        panel.render(board, true, BoardView.NAMED_TILES, false);
        flush();

        panel.renderStale(board, true, BoardView.NAMED_TILES, false, "bad_token");
        assertTrue(panel.freshnessText().startsWith("Last updated "), panel.freshnessText());

        // Re-rendering while rejected keeps the warning rather than quietly going live again.
        panel.render(board, true, BoardView.POSSIBLE_ITEMS, false);
        assertTrue(panel.freshnessText().startsWith("Last updated "), panel.freshnessText());
    }

    /** Logout, a token change, or a plugin restart makes the previous board's age meaningless. */
    @Test
    void resetClearsTheFreshnessLine() throws Exception {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        BingoPanel panel = new BingoPanel(itemManager);
        BingoBoard board = new BingoBoard("Team One", tiles(), true);

        panel.markRefreshSucceeded();
        panel.render(board, true, BoardView.NAMED_TILES, false);
        assertFalse(panel.freshnessText().isEmpty());

        panel.resetFreshness();
        assertEquals("", panel.freshnessText());
    }

    /** A message screen has no rows, so there is nothing for a freshness line to describe. */
    @Test
    void messageScreensHideTheFreshnessLine() throws Exception {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        BingoPanel panel = new BingoPanel(itemManager);
        BingoBoard board = new BingoBoard("Team One", tiles(), true);

        panel.markRefreshSucceeded();
        panel.render(board, true, BoardView.NAMED_TILES, false);
        assertFalse(panel.freshnessText().isEmpty());

        panel.renderLoading();
        assertEquals("", panel.freshnessText());
    }

    // ------------------------------------------------------------------
    // Dink test action
    // ------------------------------------------------------------------

    /**
     * A misconfigured Dink is invisible until the first real drop, so the test has to work
     * with no eligible item and no live event -- it only hands a message to the plugin.
     */
    @Test
    void theDinkTestIsAvailableWithoutABoardAndReportsWhatItActuallyDid() throws Exception {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        BingoPanel panel = new BingoPanel(itemManager);
        panel.setClock(Clock.fixed(Instant.parse("2026-09-15T14:32:00Z"), ZoneOffset.UTC));
        AtomicInteger sent = new AtomicInteger();
        panel.setTestHandler(sent::incrementAndGet);

        // No board has ever loaded.
        panel.renderLoading();
        flush();

        assertEquals("", panel.testStatusText());
        assertTrue(invokeSendTest(panel));
        assertEquals(1, sent.get());

        String status = panel.testStatusText();
        assertTrue(status.contains("Sent to Dink"), status);
        // Dink acknowledges nothing, so the panel must not imply it arrived anywhere.
        assertTrue(status.contains("does not confirm delivery"), status);
        assertTrue(status.contains("check Discord"), status);
        assertFalse(status.toLowerCase().contains("delivered"), status);
    }

    /** One stray double-click should not put two tests in an organizer's channel. */
    @Test
    void repeatedDinkTestsAreRateLimitedUntilTheCooldownElapses() throws Exception {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        BingoPanel panel = new BingoPanel(itemManager);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-15T14:32:00Z"));
        panel.setClock(clock);
        AtomicInteger sent = new AtomicInteger();
        panel.setTestHandler(sent::incrementAndGet);

        assertTrue(invokeSendTest(panel));
        assertFalse(invokeSendTest(panel), "a second press inside the cooldown must not post");
        clock.advanceSeconds(29);
        assertFalse(invokeSendTest(panel));
        assertEquals(1, sent.get());

        clock.advanceSeconds(1);
        assertTrue(invokeSendTest(panel), "the cooldown must expire rather than lock the button");
        assertEquals(2, sent.get());
    }

    private static boolean invokeSendTest(BingoPanel panel) throws Exception {
        AtomicBoolean sent = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> sent.set(panel.sendTest()));
        return sent.get();
    }

    /** Lets a test step over the cooldown without sleeping through it. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * The controls are a view over the snapshot on screen. Narrowing the list must never look
     * like a fetch, or a board nobody has refreshed in an hour reads as current.
     */
    @Test
    void filteringRedrawsTheRowsWithoutRestampingTheBoard() throws Exception {
        BingoPanel panel = panel();
        panel.setClock(Clock.fixed(Instant.parse("2026-09-15T14:32:00Z"), ZoneOffset.UTC));
        BingoBoard board = new BingoBoard("Team One",
            java.util.Arrays.asList(tile(1, "Abyssal whip", false), tile(2, "Twisted bow", false)),
            true);

        panel.markRefreshSucceeded();
        render(panel, board, BoardView.NAMED_TILES, false);
        String afterRefresh = panel.freshnessText();

        panel.setClock(Clock.fixed(Instant.parse("2026-09-15T18:00:00Z"), ZoneOffset.UTC));
        onEdt(() -> panel.setSearch("bow"));
        flush();

        assertEquals(java.util.Collections.singletonList("Twisted bow"), rowNames(panel));
        assertEquals(afterRefresh, panel.freshnessText(),
            "filtering must not restamp the board as freshly fetched");
    }

    /** A search a player typed for one item has to find the tile it can complete. */
    @Test
    void searchMatchesTileNamesAndItemNamesInBothViews() throws Exception {
        BingoPanel panel = panel();
        BingoTile grouped = new BingoTile("raids", "Any two raids uniques", 2, 2, 0,
            java.util.Arrays.asList(
                new BingoItem(1, "Dexterous prayer scroll"),
                new BingoItem(2, "Twisted buckler")),
            Collections.emptyList(), false, null, null, null);
        BingoBoard board = new BingoBoard("Team One",
            java.util.Arrays.asList(grouped, tile(3, "Abyssal whip", false)), true);

        render(panel, board, BoardView.NAMED_TILES, false);
        onEdt(() -> panel.setSearch("BUCKLER"));
        flush();
        assertEquals(java.util.Collections.singletonList("Any two raids uniques"),
            rowNames(panel), "an item name must find its logical tile");

        render(panel, board, BoardView.POSSIBLE_ITEMS, false);
        assertEquals(java.util.Collections.singletonList("Twisted buckler"), rowNames(panel),
            "the item view shows the matching item, not its whole tile");

        // Searching the tile itself is how a player asks for everything that satisfies it.
        onEdt(() -> panel.setSearch("raids"));
        flush();
        assertEquals(java.util.Arrays.asList("Dexterous prayer scroll", "Twisted buckler"),
            rowNames(panel));
    }

    /** Credited and completed rows are exactly what the progress filters are asked about. */
    @Test
    void progressFiltersSplitOpenPartialAndCompletedTiles() throws Exception {
        BingoPanel panel = panel();
        BingoTile open = tile(1, "Open tile", false);
        BingoTile partial = new BingoTile("raids", "Partial tile", 2, 2, 1,
            java.util.Arrays.asList(
                new BingoItem(2, "Dexterous prayer scroll"),
                new BingoItem(3, "Twisted buckler")),
            java.util.Collections.singletonList(new BingoContribution(
                2, "Dexterous prayer scroll", "Jake", null)),
            false, null, null, null);
        BingoTile completed = tile(4, "Completed tile", true);
        BingoBoard board = new BingoBoard("Team One",
            java.util.Arrays.asList(open, partial, completed), true);

        render(panel, board, BoardView.NAMED_TILES, false);
        onEdt(() -> panel.setProgressFilter(BoardProgressFilter.PARTIAL));
        flush();
        assertEquals(java.util.Collections.singletonList("Partial tile"), rowNames(panel));

        onEdt(() -> panel.setProgressFilter(BoardProgressFilter.COMPLETED));
        flush();
        assertEquals(java.util.Collections.singletonList("<html><s>Completed tile</s></html>"),
            rowNames(panel));

        onEdt(() -> panel.setProgressFilter(BoardProgressFilter.OPEN));
        flush();
        assertEquals(java.util.Collections.singletonList("Open tile"), rowNames(panel));
    }

    /**
     * The eligibility toggle is the hunting list: everything on screen is something a drop can
     * still claim, so credited items and finished tiles go, in either view.
     */
    @Test
    void eligibleOnlyLeavesOnlyRowsADropCanStillClaim() throws Exception {
        BingoPanel panel = panel();
        BingoTile partial = new BingoTile("raids", "Any two raids uniques", 2, 2, 1,
            java.util.Arrays.asList(
                new BingoItem(1, "Dexterous prayer scroll"),
                new BingoItem(2, "Twisted buckler")),
            java.util.Collections.singletonList(new BingoContribution(
                1, "Dexterous prayer scroll", "Jake", null)),
            false, null, null, null);
        BingoTile completed = tile(3, "Completed tile", true);
        BingoBoard board = new BingoBoard("Team One",
            java.util.Arrays.asList(partial, completed), true);

        render(panel, board, BoardView.POSSIBLE_ITEMS, false);
        onEdt(() -> panel.setEligibleOnly(true));
        flush();
        assertEquals(java.util.Collections.singletonList("Twisted buckler"), rowNames(panel));

        render(panel, board, BoardView.NAMED_TILES, false);
        assertEquals(java.util.Collections.singletonList("Any two raids uniques"),
            rowNames(panel));
    }

    /** Sorting is a view. The board the detector matches against must come through untouched. */
    @Test
    void sortingReordersTheRowsAndNotTheBoard() throws Exception {
        BingoPanel panel = panel();
        List<BingoTile> boardTiles = java.util.Arrays.asList(
            tile(1, "Zamorak godsword", false),
            tile(2, "Abyssal whip", false),
            tile(3, "Twisted bow", false));
        BingoBoard board = new BingoBoard("Team One", boardTiles, true);

        render(panel, board, BoardView.NAMED_TILES, false);
        onEdt(() -> panel.setSort(BoardSort.NAME));
        flush();

        assertEquals(java.util.Arrays.asList("Abyssal whip", "Twisted bow", "Zamorak godsword"),
            rowNames(panel));
        assertEquals(java.util.Arrays.asList("Zamorak godsword", "Abyssal whip", "Twisted bow"),
            java.util.Arrays.asList(
                board.getTiles().get(0).getName(),
                board.getTiles().get(1).getName(),
                board.getTiles().get(2).getName()),
            "the snapshot itself is never reordered");
    }

    /**
     * Filters survive a refresh, because a player who narrowed the list to one boss does not
     * want it thrown away every few minutes; a different event clears them instead.
     */
    @Test
    void filtersSurviveARefreshAndAreClearedForANewEvent() throws Exception {
        BingoPanel panel = panel();
        BingoBoard board = new BingoBoard("Team One",
            java.util.Arrays.asList(tile(1, "Abyssal whip", false), tile(2, "Twisted bow", false)),
            true);

        render(panel, board, BoardView.NAMED_TILES, false);
        onEdt(() -> panel.setSearch("bow"));
        flush();
        assertEquals(java.util.Collections.singletonList("Twisted bow"), rowNames(panel));

        // A later refresh of the same event brings a new snapshot; the filter stays put.
        panel.markRefreshSucceeded();
        render(panel, board, BoardView.NAMED_TILES, false);
        assertEquals(java.util.Collections.singletonList("Twisted bow"), rowNames(panel));
        assertEquals("bow", panel.currentFilter().getSearch());

        clearFilters(panel);
        render(panel, board, BoardView.NAMED_TILES, false);
        assertEquals(BoardFilter.NONE, panel.currentFilter());
        assertEquals(java.util.Arrays.asList("Abyssal whip", "Twisted bow"), rowNames(panel));
    }

    /**
     * An empty list otherwise reads as a board with nothing left on it, which is the one
     * reading that would make a player stop hunting.
     */
    @Test
    void anEmptyResultSaysTheFiltersAreWhatIsHidingTheBoard() throws Exception {
        BingoPanel panel = panel();
        BingoBoard board = new BingoBoard("Team One",
            java.util.Collections.singletonList(tile(1, "Abyssal whip", false)), true);

        render(panel, board, BoardView.NAMED_TILES, false);
        assertEquals("", panel.emptyStateText());

        onEdt(() -> panel.setSearch("nothing on this board"));
        flush();
        assertEquals(1, rowNames(panel).size(), "the explanation is the only row");
        assertTrue(panel.emptyStateText().contains("filters"), panel.emptyStateText());

        // With nothing narrowing the list, an all-completed board is explained by the setting.
        onEdt(panel::clearFilters);
        flush();
        render(panel, new BingoBoard("Team One",
                java.util.Collections.singletonList(tile(1, "Abyssal whip", true)), true),
            BoardView.NAMED_TILES, true);
        assertTrue(panel.emptyStateText().contains("completed"), panel.emptyStateText());
    }

    /** Folded away, the strip is the only thing that can say the board is being narrowed. */
    @Test
    void theFilterStripSaysWhenAFilterIsOn() throws Exception {
        BingoPanel panel = panel();
        BingoBoard board = new BingoBoard("Team One",
            java.util.Collections.singletonList(tile(1, "Abyssal whip", false)), true);

        render(panel, board, BoardView.NAMED_TILES, false);
        assertFalse(panel.filtersToggleText().contains("on"), panel.filtersToggleText());

        onEdt(() -> panel.setSort(BoardSort.NAME));
        flush();
        assertTrue(panel.filtersToggleText().contains("on"), panel.filtersToggleText());

        onEdt(panel::clearFilters);
        flush();
        flush();
        assertFalse(panel.filtersToggleText().contains("on"), panel.filtersToggleText());
    }

    private static BingoPanel panel() {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        return new BingoPanel(itemManager);
    }

    /**
     * The rows a player sees, read on the EDT once the render that is building them has
     * finished.
     * <p>
     * Swing owns these components on the EDT, so the read happens there; the caller is
     * responsible for having waited out the render first.
     */
    private static List<String> rowNames(BingoPanel panel) throws Exception {
        List<String> names = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            BorderLayout layout = (BorderLayout) panel.getLayout();
            JScrollPane scrollPane =
                (JScrollPane) layout.getLayoutComponent(BorderLayout.CENTER);
            JPanel itemList = (JPanel) scrollPane.getViewport().getView();
            for (Component component : itemList.getComponents()) {
                names.add(rowName(component));
            }
        });
        return names;
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    /**
     * Hand the panel a board and wait for the rows to actually be on screen.
     * <p>
     * Waiting on the event queue is not enough. RuneLite's {@code SwingUtil.fastRemoveAll}
     * pumps pending events as it strips the old rows, so anything queued behind a render can
     * be run from inside it, while the previous board is half dismantled. The panel's own
     * count of finished renders is the only thing that says the new rows are there.
     */
    private static void render(
        BingoPanel panel,
        BingoBoard board,
        BoardView boardView,
        boolean hideCompletedTiles
    ) throws Exception {
        int before = panel.renderCount();
        panel.render(board, true, boardView, hideCompletedTiles);
        awaitRender(panel, before);
    }

    /**
     * Wait until the panel has finished at least one more render and has settled.
     * <p>
     * A control change renders too, so "one more than before" is not enough on its own: the
     * count has to stop moving across a full round trip of the event queue before the rows
     * can be read.
     */
    private static void awaitRender(BingoPanel panel, int before) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        int last = -1;
        while (System.currentTimeMillis() < deadline) {
            int now = panel.renderCount();
            if (now > before && now == last) {
                return;
            }
            last = now;
            flush();
            Thread.sleep(1);
        }
        throw new AssertionError("the panel never settled on a render");
    }

    /** Clearing from off the EDT is asynchronous, and it redraws the rows. */
    private static void clearFilters(BingoPanel panel) throws Exception {
        int before = panel.renderCount();
        panel.clearFilters();
        awaitRender(panel, before);
    }

    private static List<BingoTile> tiles() {
        return Collections.singletonList(new BingoTile("4151", "Abyssal whip", 1, 1, 0,
            Collections.singletonList(new BingoItem(4151, "Abyssal whip")),
            Collections.emptyList(), false, null, null, null));
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }


    @Test
    void longTileListScrollsWhileHeaderRemainsFixed() throws Exception {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        BingoPanel panel = new BingoPanel(itemManager);

        BorderLayout layout = (BorderLayout) panel.getLayout();
        Component center = layout.getLayoutComponent(BorderLayout.CENTER);
        Component header = layout.getLayoutComponent(BorderLayout.NORTH);

        assertTrue(center instanceof JScrollPane);
        JScrollPane scrollPane = (JScrollPane) center;
        assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            scrollPane.getVerticalScrollBarPolicy());
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            scrollPane.getHorizontalScrollBarPolicy());

        List<BingoTile> tiles = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int itemId = i + 1;
            String name = "Tile " + itemId;
            BingoItem option = new BingoItem(itemId, name);
            tiles.add(new BingoTile(
                String.valueOf(itemId),
                name,
                1,
                1,
                0,
                Collections.singletonList(option),
                Collections.emptyList(),
                false,
                null,
                null,
                null
            ));
        }
        panel.render(new BingoBoard("Team One", tiles, true), true,
            BoardView.NAMED_TILES, false);
        SwingUtilities.invokeAndWait(() -> {
            // Flush the render queued by BingoPanel.render.
        });

        JPanel itemList = (JPanel) scrollPane.getViewport().getView();
        assertEquals(50, itemList.getComponentCount());
        assertTrue(itemList.getPreferredSize().height > 400);
        assertNotSame(header, itemList);
    }

    @Test
    void completedTilesCanBeHiddenForTheCurrentTeam() throws Exception {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        BingoPanel panel = new BingoPanel(itemManager);

        BingoTile open = tile(1, "Open tile", false);
        BingoTile completed = tile(2, "Completed tile", true);
        BingoBoard board = new BingoBoard("Team One",
            java.util.Arrays.asList(open, completed), true);

        panel.render(board, true, BoardView.NAMED_TILES, true);
        SwingUtilities.invokeAndWait(() -> {
            // Flush the render queued by BingoPanel.render.
        });

        BorderLayout layout = (BorderLayout) panel.getLayout();
        JScrollPane scrollPane = (JScrollPane) layout.getLayoutComponent(BorderLayout.CENTER);
        JPanel itemList = (JPanel) scrollPane.getViewport().getView();
        assertEquals(1, itemList.getComponentCount());
    }

    @Test
    void possibleItemsStrikesOutEveryItemThatAlreadyCounted() throws Exception {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        BingoPanel panel = new BingoPanel(itemManager);

        BingoTile unfinished = new BingoTile(
            "raids",
            "Any two raids uniques",
            2,
            2,
            1,
            java.util.Arrays.asList(
                new BingoItem(1, "Dexterous prayer scroll"),
                new BingoItem(2, "Arcane prayer scroll"),
                new BingoItem(3, "Twisted buckler")
            ),
            Collections.singletonList(new BingoContribution(
                1, "Dexterous prayer scroll", "Jake", "2026-07-31T12:00:00Z")),
            false,
            null,
            null,
            null
        );
        BingoTile completed = tile(4, "Completed item", true);

        panel.render(new BingoBoard("Team One",
                java.util.Arrays.asList(unfinished, completed), true),
            true, BoardView.POSSIBLE_ITEMS, false);
        SwingUtilities.invokeAndWait(() -> {
            // Flush the render queued by BingoPanel.render.
        });

        BorderLayout layout = (BorderLayout) panel.getLayout();
        JScrollPane scrollPane = (JScrollPane) layout.getLayoutComponent(BorderLayout.CENTER);
        JPanel itemList = (JPanel) scrollPane.getViewport().getView();
        assertEquals(4, itemList.getComponentCount());
        assertEquals("<html><s>Dexterous prayer scroll</s></html>",
            rowName(itemList.getComponent(0)));
        assertEquals("Arcane prayer scroll", rowName(itemList.getComponent(1)));
        assertEquals("Twisted buckler", rowName(itemList.getComponent(2)));
        assertEquals("<html><s>Completed item</s></html>", rowName(itemList.getComponent(3)));
        assertEquals("1 of 2 tiles left", statusText(panel));
    }

    /**
     * The setting reads as a board-wide one, so the item view has to answer to it the way the
     * named-tile view does rather than hiding finished work unconditionally. With it on, the
     * view is the pure hunting list: only what can still drop.
     */
    @Test
    void possibleItemsDropsEveryFinishedItemWhenTheSettingIsOn() throws Exception {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        BingoPanel panel = new BingoPanel(itemManager);

        BingoTile unfinished = new BingoTile(
            "raids",
            "Any two raids uniques",
            2,
            2,
            1,
            java.util.Arrays.asList(
                new BingoItem(1, "Dexterous prayer scroll"),
                new BingoItem(2, "Arcane prayer scroll")
            ),
            Collections.singletonList(new BingoContribution(
                1, "Dexterous prayer scroll", "Jake", null)),
            false,
            null,
            null,
            null
        );
        BingoTile completed = tile(4, "Completed item", true);

        panel.render(new BingoBoard("Team One",
                java.util.Arrays.asList(unfinished, completed), true),
            true, BoardView.POSSIBLE_ITEMS, true);
        SwingUtilities.invokeAndWait(() -> {
            // Flush the render queued by BingoPanel.render.
        });

        BorderLayout layout = (BorderLayout) panel.getLayout();
        JScrollPane scrollPane = (JScrollPane) layout.getLayoutComponent(BorderLayout.CENTER);
        JPanel itemList = (JPanel) scrollPane.getViewport().getView();
        assertEquals(1, itemList.getComponentCount());
        assertEquals("Arcane prayer scroll", rowName(itemList.getComponent(0)));
    }

    /**
     * A credited item keeps its slot in the tile's option order rather than dropping out of
     * the list, so a player can see that their drop registered.
     */
    @Test
    void aCreditedItemKeepsItsPlaceStruckThroughWithItsContributor() throws Exception {
        ItemManager itemManager = mock(ItemManager.class);
        when(itemManager.getImage(anyInt())).thenReturn(mock(AsyncBufferedImage.class));
        BingoPanel panel = new BingoPanel(itemManager);

        BingoTile unfinished = new BingoTile(
            "raids",
            "Any two raids uniques",
            2,
            2,
            1,
            java.util.Arrays.asList(
                new BingoItem(1, "Dexterous prayer scroll"),
                new BingoItem(2, "Arcane prayer scroll"),
                new BingoItem(3, "Twisted buckler")
            ),
            Collections.singletonList(new BingoContribution(
                2, "Arcane prayer scroll", "Jake", null)),
            false,
            null,
            null,
            null
        );

        panel.render(new BingoBoard("Team One", Collections.singletonList(unfinished), true),
            true, BoardView.POSSIBLE_ITEMS, false);
        SwingUtilities.invokeAndWait(() -> {
            // Flush the render queued by BingoPanel.render.
        });

        BorderLayout layout = (BorderLayout) panel.getLayout();
        JScrollPane scrollPane = (JScrollPane) layout.getLayoutComponent(BorderLayout.CENTER);
        JPanel itemList = (JPanel) scrollPane.getViewport().getView();
        assertEquals(3, itemList.getComponentCount());
        assertEquals("Dexterous prayer scroll", rowName(itemList.getComponent(0)));
        assertEquals("<html><s>Arcane prayer scroll</s></html>",
            rowName(itemList.getComponent(1)));
        assertEquals("Twisted buckler", rowName(itemList.getComponent(2)));
        // The tile itself is unclaimed, so the name can only come from the contribution.
        assertEquals("Jake", rowEast(itemList.getComponent(1)));
    }

    // ------------------------------------------------------------------
    // system check
    // ------------------------------------------------------------------

    /**
     * The player who has not thought to open the report is exactly the one who needs to be
     * told that nothing is being claimed, so the headline rides on the button itself.
     */
    @Test
    void theSystemCheckButtonCarriesTheHeadlineWithoutBeingOpened() throws Exception {
        BingoPanel panel = panel();
        assertEquals("System check", panel.systemCheckButtonText());

        renderCheck(panel, readyStatus().build());
        assertEquals("System check \u2014 ready", panel.systemCheckButtonText());

        renderCheck(panel, readyStatus().dink(SystemStatus.Presence.MISSING).build());
        assertEquals("System check \u2014 1 warning", panel.systemCheckButtonText());

        renderCheck(panel, readyStatus().detectionEnabled(false).build());
        assertEquals("System check \u2014 1 problem", panel.systemCheckButtonText());
    }

    /**
     * The report replaces the board rather than sitting above it: the filter strip has no rows
     * to act on while it is up, and a sidebar this narrow cannot show both.
     */
    @Test
    void openingTheReportReplacesTheBoardAndPutsItBack() throws Exception {
        BingoPanel panel = panel();
        render(panel, new BingoBoard("Team One", tiles(), true), BoardView.NAMED_TILES, false);
        renderCheck(panel, readyStatus().build());

        Component board = center(panel);
        assertTrue(filterBarVisible(panel));

        onEdt(() -> panel.setSystemCheckVisible(true));
        assertTrue(panel.isShowingSystemCheck());
        assertNotSame(board, center(panel));
        assertEquals("Back to board", panel.systemCheckButtonText());
        assertFalse(filterBarVisible(panel), "the strip filters tile rows, and there are none");

        onEdt(() -> panel.setSystemCheckVisible(false));
        assertFalse(panel.isShowingSystemCheck());
        assertSame(board, center(panel), "the board comes back as it was");
        assertTrue(filterBarVisible(panel));
        assertEquals("System check \u2014 ready", panel.systemCheckButtonText());
    }

    /**
     * Opening the report re-runs the checks, because a report showing what was true ten
     * minutes ago is the problem it exists to solve. Going back to the board fetches nothing.
     */
    @Test
    void openingTheReportAsksForFreshChecksAndGoingBackDoesNot() throws Exception {
        BingoPanel panel = panel();
        AtomicInteger runs = new AtomicInteger();
        panel.setSystemCheckHandler(runs::incrementAndGet);

        onEdt(() -> panel.setSystemCheckVisible(true));
        assertEquals(1, runs.get());

        // Already open: the button in the report itself asks again.
        onEdt(() -> panel.setSystemCheckVisible(true));
        assertEquals(2, runs.get());

        onEdt(() -> panel.setSystemCheckVisible(false));
        assertEquals(2, runs.get(), "going back to the board must not ask for anything");
    }

    /** A failing row is only useful if it says what to do, and the panel has to draw that. */
    @Test
    void aFailingRowIsDrawnWithItsRemedy() throws Exception {
        BingoPanel panel = panel();
        renderCheck(panel, readyStatus().backendUrl(BackendUrlState.UNSET).build());
        onEdt(() -> panel.setSystemCheckVisible(true));

        List<String> text = checkRowText(panel);
        assertTrue(text.stream().anyMatch(line -> line.contains("Not set")), text.toString());
        assertTrue(text.stream().anyMatch(line -> line.contains("/exec")), text.toString());
    }

    /**
     * A team name comes from the organizer's sheet and a backend reason from a deployment the
     * player does not control. Swing renders a label that starts with {@code <html>} as markup.
     */
    @Test
    void reportRowsEscapeTextTheyDidNotWrite() throws Exception {
        BingoPanel panel = panel();
        renderCheck(panel, readyStatus()
            .board(new BingoBoard("<html><b>Team</b>", tiles(), true))
            .build());
        onEdt(() -> panel.setSystemCheckVisible(true));

        List<String> text = checkRowText(panel);
        assertTrue(text.stream().anyMatch(line -> line.contains("&lt;html&gt;&lt;b&gt;Team")),
            text.toString());
        assertTrue(text.stream().noneMatch(line -> line.contains("<b>Team")), text.toString());
    }

    /**
     * Opening the view before anything has been checked must not read as a report that found
     * nothing wrong, and an empty panel reads exactly like one.
     */
    @Test
    void theReportSaysSoBeforeAnythingHasBeenChecked() throws Exception {
        BingoPanel panel = panel();
        assertEquals("System check", panel.systemCheckButtonText());

        onEdt(() -> panel.setSystemCheckVisible(true));
        List<String> text = checkRowText(panel);
        assertTrue(text.stream().anyMatch(line -> line.contains("Nothing has been checked yet")),
            text.toString());
    }

    /**
     * The panel outlives a plugin restart. A button still reading "ready" would be describing
     * a run that has ended, which is the same lie the freshness line exists to prevent.
     */
    @Test
    void shuttingDownForgetsTheLastReportAndGoesBackToTheBoard() throws Exception {
        BingoPanel panel = panel();
        renderCheck(panel, readyStatus().build());
        onEdt(() -> panel.setSystemCheckVisible(true));
        assertEquals("Back to board", panel.systemCheckButtonText());

        resetCheck(panel);

        assertFalse(panel.isShowingSystemCheck());
        assertEquals("System check", panel.systemCheckButtonText());
        onEdt(() -> panel.setSystemCheckVisible(true));
        List<String> text = checkRowText(panel);
        assertTrue(text.stream().anyMatch(line -> line.contains("Nothing has been checked yet")),
            text.toString());
    }

    /** A status with everything working, for tests to break one thing at a time. */
    private static SystemStatus.Builder readyStatus() {
        return SystemStatus.builder()
            .backendUrl(BackendUrlState.OK)
            .tokenSet(true)
            .loggedIn(true)
            .rsn("Jake")
            .fetch(SystemStatus.Fetch.LOADED)
            .board(new BingoBoard("Team One", tiles(), true))
            .detectionEnabled(true)
            .dink(SystemStatus.Presence.RUNNING)
            .lootTracker(SystemStatus.Presence.RUNNING);
    }

    /**
     * Hand the panel a status and wait for the report to actually be on screen, for the same
     * reason {@link #render} waits: {@code fastRemoveAll} pumps the queue as it works.
     */
    private static void renderCheck(BingoPanel panel, SystemStatus status) throws Exception {
        int before = panel.checkRenderCount();
        panel.renderSystemCheck(status);
        awaitCheck(panel, before);
    }

    private static void resetCheck(BingoPanel panel) throws Exception {
        int before = panel.checkRenderCount();
        panel.resetSystemCheck();
        awaitCheck(panel, before);
    }

    private static void awaitCheck(BingoPanel panel, int before) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            flush();
            if (panel.checkRenderCount() > before) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("the report never rendered");
    }

    private static Component center(BingoPanel panel) throws Exception {
        Component[] found = new Component[1];
        onEdt(() -> found[0] = ((BorderLayout) panel.getLayout())
            .getLayoutComponent(BorderLayout.CENTER));
        return found[0];
    }

    private static boolean filterBarVisible(BingoPanel panel) throws Exception {
        boolean[] visible = new boolean[1];
        onEdt(() -> {
            JPanel header = (JPanel) ((BorderLayout) panel.getLayout())
                .getLayoutComponent(BorderLayout.NORTH);
            visible[0] = ((BorderLayout) header.getLayout())
                .getLayoutComponent(BorderLayout.SOUTH).isVisible();
        });
        return visible[0];
    }

    /** Every line of the report as a player reads it, remedies included. */
    private static List<String> checkRowText(BingoPanel panel) throws Exception {
        List<String> lines = new ArrayList<>();
        onEdt(() -> {
            JScrollPane pane = (JScrollPane) ((BorderLayout) panel.getLayout())
                .getLayoutComponent(BorderLayout.CENTER);
            JPanel list = (JPanel) pane.getViewport().getView();
            for (Component component : list.getComponents()) {
                if (!(component instanceof JPanel)) {
                    continue;
                }
                JPanel row = (JPanel) component;
                for (Component child : row.getComponents()) {
                    if (child instanceof javax.swing.JLabel) {
                        lines.add(((javax.swing.JLabel) child).getText());
                    }
                }
            }
        });
        return lines;
    }

    private static String rowName(Component component) {
        JPanel row = (JPanel) component;
        return ((javax.swing.JLabel) ((BorderLayout) row.getLayout())
            .getLayoutComponent(BorderLayout.CENTER)).getText();
    }

    private static String rowEast(Component component) {
        JPanel row = (JPanel) component;
        return ((javax.swing.JLabel) ((BorderLayout) row.getLayout())
            .getLayoutComponent(BorderLayout.EAST)).getText();
    }

    private static String statusText(BingoPanel panel) {
        BorderLayout panelLayout = (BorderLayout) panel.getLayout();
        JPanel header = (JPanel) panelLayout.getLayoutComponent(BorderLayout.NORTH);
        JPanel titles = (JPanel) ((BorderLayout) header.getLayout())
            .getLayoutComponent(BorderLayout.CENTER);
        return ((javax.swing.JLabel) titles.getComponent(1)).getText();
    }

    private static BingoTile tile(int id, String name, boolean claimed) {
        BingoItem item = new BingoItem(id, name);
        return new BingoTile(
            String.valueOf(id),
            name,
            1,
            1,
            claimed ? 1 : 0,
            Collections.singletonList(item),
            Collections.emptyList(),
            claimed,
            claimed ? "Jake" : null,
            null,
            claimed ? item : null
        );
    }
}
