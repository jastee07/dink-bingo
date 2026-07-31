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
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;

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

        add(header, BorderLayout.NORTH);
        add(itemsPanel, BorderLayout.CENTER);
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
        statusLabel.setText(board.getRemainingCount() + " of " + board.getTiles().size() + " tiles left"
            + (board.isEventOpen() ? "" : " (event closed)"));

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;

        for (BingoTile tile : board.getTiles()) {
            itemsPanel.add(buildRow(tile), c);
            c.gridy++;
        }

        itemsPanel.revalidate();
        itemsPanel.repaint();
    }

    private JPanel buildRow(BingoTile tile) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(36, 32));
        Set<Integer> creditedIds = new HashSet<>();
        for (BingoContribution contribution : tile.getClaimedItems()) {
            creditedIds.add(contribution.getId());
        }
        BingoItem iconItem = tile.getClaimedItem();
        if (iconItem == null) {
            for (BingoItem option : tile.getOptions()) {
                if (!creditedIds.contains(option.getId())) {
                    iconItem = option;
                    break;
                }
            }
        }
        if (iconItem != null) {
            AsyncBufferedImage image = itemManager.getImage(iconItem.getId());
            image.addTo(icon);
        }
        row.add(icon, BorderLayout.WEST);

        JLabel name = new JLabel();
        name.setFont(FontManager.getRunescapeSmallFont());
        if (tile.isClaimed()) {
            String winner = tile.getClaimedItem() != null ? tile.getClaimedItem().getName() : null;
            // Strikethrough via HTML is the only way to get it on a plain JLabel.
            name.setText("<html><s>" + escape(tile.getName()) + "</s></html>");
            name.setForeground(CLAIMED_COLOR);
            name.setToolTipText("Claimed by " + tile.getClaimedBy() +
                (winner == null ? "" : " with " + winner));
        } else {
            name.setText(tile.getName());
            name.setForeground(OPEN_COLOR);
            StringJoiner credited = new StringJoiner(", ");
            for (BingoContribution contribution : tile.getClaimedItems()) {
                credited.add(contribution.getName());
            }
            StringJoiner missing = new StringJoiner(", ");
            for (BingoItem option : tile.getOptions()) {
                if (!creditedIds.contains(option.getId())) missing.add(option.getName());
            }
            name.setToolTipText((tile.getClaimedItems().isEmpty() ? "" :
                "Credited: " + credited + ". ") + "Still eligible: " + missing);
        }
        row.add(name, BorderLayout.CENTER);

        if (tile.isClaimed() && tile.getClaimedBy() != null) {
            JLabel by = new JLabel(tile.getClaimedBy());
            by.setFont(FontManager.getRunescapeSmallFont());
            by.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
            row.add(by, BorderLayout.EAST);
        } else if (tile.getRequired() > 1 || tile.getProgress() > 0) {
            JLabel progress = new JLabel(tile.getProgress() + "/" + tile.getRequired());
            progress.setFont(FontManager.getRunescapeSmallFont());
            progress.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
            row.add(progress, BorderLayout.EAST);
        }

        return row;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
