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
