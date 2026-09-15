package dinkbingo;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.SwingUtil;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Side panel showing the team's board: which tiles are still open, and who took the rest.
 */
@Singleton
public class BingoPanel extends PluginPanel {

    private static final Color CLAIMED_COLOR = new Color(0x7A, 0x7A, 0x7A);
    private static final Color OPEN_COLOR = Color.WHITE;
    private static final Color STALE_COLOR = new Color(0xDB, 0x6E, 0x6E);
    private static final Color LIVE_STATUS_COLOR = ColorScheme.LIGHT_GRAY_COLOR;


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
        statusLabel.setForeground(LIVE_STATUS_COLOR);

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
    public void render(
        BingoBoard board,
        boolean configured,
        BoardView boardView,
        boolean hideCompletedTiles
    ) {
        SwingUtilities.invokeLater(() ->
            renderOnEdt(board, configured, boardView, hideCompletedTiles, null));
    }

    /**
     * Keep the last known board on screen, clearly labelled as no longer live. Safe to call
     * from any thread.
     * <p>
     * Used when the backend answered and refused a refresh after an earlier one succeeded.
     * The rows are still useful reference, but presenting them as current would hide the fact
     * that the plugin has stopped claiming drops, which is exactly what a player needs to know
     * when an organizer rotates the event token mid-event.
     */
    public void renderStale(
        BingoBoard board,
        boolean configured,
        BoardView boardView,
        boolean hideCompletedTiles,
        @Nullable String backendError
    ) {
        String reason = BingoErrors.describeBoardError(backendError);
        SwingUtilities.invokeLater(() ->
            renderOnEdt(board, configured, boardView, hideCompletedTiles, reason));
    }

    /** Safe to call from any thread. */
    public void renderLoading() {
        SwingUtilities.invokeLater(() -> renderMessage(
            "Loading board",
            "Fetching your team's tiles\u2026"
        ));
    }

    /**
     * The board never arrived. Safe to call from any thread.
     * <p>
     * Only used when the round trip itself failed, which is the one case the player's
     * connection actually explains.
     */
    public void renderLoadError() {
        SwingUtilities.invokeLater(() -> renderMessage(
            "Couldn't load board",
            "Check your connection, then press Refresh"
        ));
    }

    /**
     * The backend answered and refused the fetch. Safe to call from any thread.
     * <p>
     * {@code backendError} is the raw {@code error} value from the response.
     */
    public void renderLoadError(String backendError) {
        String reason = describeBackendError(backendError);
        SwingUtilities.invokeLater(() -> renderMessage("Couldn't load board", reason));
    }

    /**
     * Turns a backend {@code error} value into something an organizer can act on.
     * <p>
     * The wording lives in {@link BingoErrors} alongside the claim-failure phrasing for the
     * same reason, so the two surfaces cannot drift apart.
     */
    static String describeBackendError(@Nullable String backendError) {
        return BingoErrors.describeBoardError(backendError);
    }

    private void renderOnEdt(
        BingoBoard board,
        boolean configured,
        BoardView boardView,
        boolean hideCompletedTiles,
        @Nullable String staleReason
    ) {
        SwingUtil.fastRemoveAll(itemsPanel);

        if (!configured) {
            headerLabel.setText("Not configured");
            statusLabel.setForeground(LIVE_STATUS_COLOR);
            statusLabel.setText("Set a Backend URL in the config");
            refreshItemsPanel();
            return;
        }

        if (!board.isConfigured()) {
            headerLabel.setText("No team");
            statusLabel.setForeground(LIVE_STATUS_COLOR);
            statusLabel.setText("Your RSN is not on the Teams tab");
            refreshItemsPanel();
            return;
        }

        headerLabel.setText(staleReason == null
            ? board.getTeam() : board.getTeam() + " \u2014 not live");
        if (staleReason == null) {
            statusLabel.setForeground(LIVE_STATUS_COLOR);
            statusLabel.setText(board.getRemainingCount() + " of " + board.getTiles().size()
                + " tiles left" + (board.isEventOpen() ? "" : " (event closed)"));
        } else {
            statusLabel.setForeground(STALE_COLOR);
            // The reason is bounded and single-line, and never leads, so a backend-controlled
            // string cannot be read as Swing markup or push the tile count off screen.
            statusLabel.setText("Not claiming drops \u2014 " + staleReason);
        }

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;

        if (boardView == BoardView.POSSIBLE_ITEMS) {
            for (BingoTile tile : board.getTiles()) {
                if (tile.isClaimed()) {
                    if (hideCompletedTiles) {
                        continue;
                    }
                    for (BingoContribution credited : creditedContributions(tile)) {
                        itemsPanel.add(buildCreditedItemRow(tile, credited), c);
                        c.gridy++;
                    }
                    continue;
                }
                Map<Integer, BingoContribution> credited = creditedById(tile);
                for (BingoItem option : tile.getOptions()) {
                    BingoContribution counted = credited.get(option.getId());
                    if (counted == null) {
                        itemsPanel.add(buildItemRow(tile, option), c);
                        c.gridy++;
                    } else if (!hideCompletedTiles) {
                        // Keeping the row in place, struck through, is what tells a player
                        // their drop was credited; dropping it just reshuffles the list.
                        itemsPanel.add(buildCreditedItemRow(tile, counted), c);
                        c.gridy++;
                    }
                }
            }
        } else {
            for (BingoTile tile : board.getTiles()) {
                if (hideCompletedTiles && tile.isClaimed()) {
                    continue;
                }
                itemsPanel.add(buildTileRow(tile), c);
                c.gridy++;
            }
        }

        refreshItemsPanel();
    }

    private void renderMessage(String header, String status) {
        SwingUtil.fastRemoveAll(itemsPanel);
        headerLabel.setText(header);
        statusLabel.setForeground(LIVE_STATUS_COLOR);
        statusLabel.setText(status);
        refreshItemsPanel();
    }

    private void refreshItemsPanel() {
        itemsPanel.revalidate();
        itemsPanel.repaint();
    }

    private JPanel buildTileRow(BingoTile tile) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(36, 32));
        Map<Integer, BingoContribution> creditedItems = creditedById(tile);
        BingoItem iconItem = tile.getClaimedItem();
        if (iconItem == null) {
            for (BingoItem option : tile.getOptions()) {
                if (!creditedItems.containsKey(option.getId())) {
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
                if (!creditedItems.containsKey(option.getId())) missing.add(option.getName());
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

    private JPanel buildItemRow(BingoTile tile, BingoItem option) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(36, 32));
        itemManager.getImage(option.getId()).addTo(icon);
        row.add(icon, BorderLayout.WEST);

        JLabel name = new JLabel(option.getName());
        name.setFont(FontManager.getRunescapeSmallFont());
        name.setForeground(OPEN_COLOR);
        name.setToolTipText("Eligible for " + tile.getName());
        row.add(name, BorderLayout.CENTER);

        if (tile.getRequired() > 1 || tile.getProgress() > 0) {
            JLabel progress = new JLabel(tile.getProgress() + "/" + tile.getRequired());
            progress.setFont(FontManager.getRunescapeSmallFont());
            progress.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
            progress.setToolTipText(tile.getName());
            row.add(progress, BorderLayout.EAST);
        }

        return row;
    }

    /**
     * The items credited to a completed tile. A backend that reports only the winning item
     * still names one row, so the tile never disappears from the item view silently.
     */
    private static List<BingoContribution> creditedContributions(BingoTile tile) {
        List<BingoContribution> credited = new ArrayList<>(tile.getClaimedItems());
        BingoItem won = tile.getClaimedItem();
        if (won != null && !creditedById(tile).containsKey(won.getId())) {
            credited.add(new BingoContribution(
                won.getId(), won.getName(), tile.getClaimedBy(), tile.getClaimedAt()));
        }
        return credited;
    }

    private JPanel buildCreditedItemRow(BingoTile tile, BingoContribution credited) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(36, 32));
        itemManager.getImage(credited.getId()).addTo(icon);
        row.add(icon, BorderLayout.WEST);

        String claimedBy = credited.getClaimedBy() != null
            ? credited.getClaimedBy() : tile.getClaimedBy();

        JLabel name = new JLabel();
        name.setFont(FontManager.getRunescapeSmallFont());
        // Strikethrough via HTML is the only way to get it on a plain JLabel.
        name.setText("<html><s>" + escape(credited.getName()) + "</s></html>");
        name.setForeground(CLAIMED_COLOR);
        name.setToolTipText((tile.isClaimed()
            ? "Completed " + tile.getName()
            : "Already counted toward " + tile.getName())
            + (claimedBy == null ? "" : " by " + claimedBy));
        row.add(name, BorderLayout.CENTER);

        if (claimedBy != null) {
            JLabel by = new JLabel(claimedBy);
            by.setFont(FontManager.getRunescapeSmallFont());
            by.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
            row.add(by, BorderLayout.EAST);
        }

        return row;
    }

    private static Map<Integer, BingoContribution> creditedById(BingoTile tile) {
        Map<Integer, BingoContribution> credited = new LinkedHashMap<>();
        for (BingoContribution contribution : tile.getClaimedItems()) {
            credited.put(contribution.getId(), contribution);
        }
        return credited;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
