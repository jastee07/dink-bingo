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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BingoPanelTest {

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
    void possibleItemsShowsOnlyEligibleOptionsForUnfinishedTiles() throws Exception {
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
        assertEquals(2, itemList.getComponentCount());
        assertEquals("Arcane prayer scroll", rowName(itemList.getComponent(0)));
        assertEquals("Twisted buckler", rowName(itemList.getComponent(1)));
        assertEquals("1 of 2 tiles left", statusText(panel));
    }

    @Test
    void aRejectedTokenNamesTheSetupMistakeInsteadOfTheConnection() throws Exception {
        BingoPanel panel = new BingoPanel(mock(ItemManager.class));

        panel.renderLoadError("bad_token");
        SwingUtilities.invokeAndWait(() -> {
            // Flush the render queued by BingoPanel.renderLoadError.
        });

        assertEquals("Event token rejected. Check the token on the Config tab.",
            statusText(panel));
    }

    @Test
    void aTransportFailureStillAsksAboutTheConnection() throws Exception {
        BingoPanel panel = new BingoPanel(mock(ItemManager.class));

        panel.renderLoadError();
        SwingUtilities.invokeAndWait(() -> {
            // Flush the render queued by BingoPanel.renderLoadError.
        });

        assertEquals("Check your connection, then press Refresh", statusText(panel));
    }

    @Test
    void knownBackendErrorsBecomeActionableText() {
        assertEquals("Event token rejected. Check the token on the Config tab.",
            BingoPanel.describeBackendError("bad_token"));
        assertEquals("The backend is busy. Press Refresh to try again.",
            BingoPanel.describeBackendError("lock_timeout"));
        assertEquals("The backend rejected the request. Check the Apps Script deployment.",
            BingoPanel.describeBackendError("bad_request"));
        assertEquals("Check your connection, then press Refresh",
            BingoPanel.describeBackendError(null));
        assertEquals("Check your connection, then press Refresh",
            BingoPanel.describeBackendError("   "));
    }

    /** Sheet and Config mistakes surface as free text thrown by the Apps Script. */
    @Test
    void anUnrecognizedBackendErrorIsShownButBounded() {
        assertEquals("Backend error: Error: event_start must be before event_end",
            BingoPanel.describeBackendError("Error: event_start must be before event_end"));

        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            long_.append("abcdefghij");
        }
        String bounded = BingoPanel.describeBackendError(long_.toString());
        assertEquals("Backend error: " + long_.substring(0, 80) + "\u2026", bounded);
    }

    /**
     * A custom backend controls the error field, and Swing renders a label as markup as soon
     * as its text starts with an HTML tag.
     */
    @Test
    void anUnrecognizedBackendErrorIsNeverRenderedAsMarkup() throws Exception {
        String hostile = "<html><img src='http://tracker.example/x.png'>";
        String described = BingoPanel.describeBackendError(hostile);
        assertTrue(described.startsWith("Backend error: "), described);

        BingoPanel panel = new BingoPanel(mock(ItemManager.class));
        panel.renderLoadError(hostile);
        SwingUtilities.invokeAndWait(() -> {
            // Flush the render queued by BingoPanel.renderLoadError.
        });
        assertFalse(statusText(panel).toLowerCase(java.util.Locale.ROOT).startsWith("<html"));
    }

    /** A multi-line reason would otherwise leave the sidebar showing only its first word. */
    @Test
    void aMultiLineBackendErrorCollapsesToOneLine() {
        assertEquals("Backend error: Error: invalid Items row 7",
            BingoPanel.describeBackendError("Error:\n  invalid Items\trow 7\n"));
    }

    private static String rowName(Component component) {
        JPanel row = (JPanel) component;
        return ((javax.swing.JLabel) ((BorderLayout) row.getLayout())
            .getLayoutComponent(BorderLayout.CENTER)).getText();
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
