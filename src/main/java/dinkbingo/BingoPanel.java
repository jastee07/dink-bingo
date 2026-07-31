package dinkbingo;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.SwingUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

/**
 * Side panel showing the team's board: which tiles are still open, and who took the rest.
 */
@Singleton
public class BingoPanel extends PluginPanel {

    private static final Color CLAIMED_COLOR = new Color(0x7A, 0x7A, 0x7A);
    private static final Color OPEN_COLOR = Color.WHITE;

    private final ItemManager itemManager;

    private final JLabel headerLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JPanel itemsPanel = new JPanel();
    private final JScrollPane itemsScrollPane = new JScrollPane(
        itemsPanel,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    );
    private final JButton refreshButton = new JButton("Refresh");

    private Runnable refreshHandler = () -> {
    };

    @Inject
    BingoPanel(ItemManager itemManager) {
        super(false);
        this.itemManager = itemManager;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        headerLabel.setFont(FontManager.getRunescapeBoldFont());
        headerLabel.setForeground(Color.WHITE);
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setBackground(ColorScheme.DARK_GRAY_COLOR);
        titles.add(headerLabel);
        titles.add(statusLabel);

        refreshButton.setFocusPainted(false);
        refreshButton.addActionListener(e -> refreshHandler.run());

        header.add(titles, BorderLayout.CENTER);
        header.add(refreshButton, BorderLayout.EAST);

        itemsPanel.setLayout(new GridBagLayout());
        itemsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        itemsScrollPane.setBorder(BorderFactory.createEmptyBorder());
        itemsScrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        itemsScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        add(header, BorderLayout.NORTH);
        add(itemsScrollPane, BorderLayout.CENTER);
    }

    public void setRefreshHandler(Runnable handler) {
        this.refreshHandler = handler;
    }

    /** Safe to call from any thread. */
    public void render(BingoBoard board, boolean configured) {
        SwingUtilities.invokeLater(() -> renderOnEdt(board, configured));
    }

    private void renderOnEdt(BingoBoard board, boolean configured) {
        SwingUtil.fastRemoveAll(itemsPanel);

        if (!configured) {
            headerLabel.setText("Not configured");
            statusLabel.setText("Set a Backend URL in the config");
            itemsPanel.revalidate();
            itemsPanel.repaint();
            return;
        }

        if (!board.isConfigured()) {
            headerLabel.setText("No team");
            statusLabel.setText("Your RSN is not on the Teams tab");
            itemsPanel.revalidate();
            itemsPanel.repaint();
            return;
        }

        headerLabel.setText(board.getTeam());
        statusLabel.setText(board.getRemainingCount() + " of " + board.getItems().size() + " tiles left"
            + (board.isEventOpen() ? "" : " (event closed)"));

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;

        for (BingoItem item : board.getItems()) {
            itemsPanel.add(buildRow(item), c);
            c.gridy++;
        }

        itemsPanel.revalidate();
        itemsPanel.repaint();
    }

    private JPanel buildRow(BingoItem item) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(36, 32));
        AsyncBufferedImage image = itemManager.getImage(item.getId());
        image.addTo(icon);
        row.add(icon, BorderLayout.WEST);

        JLabel name = new JLabel();
        name.setFont(FontManager.getRunescapeSmallFont());
        if (item.isClaimed()) {
            // Strikethrough via HTML is the only way to get it on a plain JLabel.
            name.setText("<html><s>" + escape(item.getName()) + "</s></html>");
            name.setForeground(CLAIMED_COLOR);
            name.setToolTipText("Claimed by " + item.getClaimedBy());
        } else {
            name.setText(escape(item.getName()));
            name.setForeground(OPEN_COLOR);
        }
        row.add(name, BorderLayout.CENTER);

        if (item.isClaimed() && item.getClaimedBy() != null) {
            JLabel by = new JLabel(item.getClaimedBy());
            by.setFont(FontManager.getRunescapeSmallFont());
            by.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
            row.add(by, BorderLayout.EAST);
        }

        return row;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
