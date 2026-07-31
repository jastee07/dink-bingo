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
        panel.render(new BingoBoard("Team One", tiles, true), true);
        SwingUtilities.invokeAndWait(() -> {
            // Flush the render queued by BingoPanel.render.
        });

        JPanel itemList = (JPanel) scrollPane.getViewport().getView();
        assertEquals(50, itemList.getComponentCount());
        assertTrue(itemList.getPreferredSize().height > 400);
        assertNotSame(header, itemList);
    }
}
