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
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private final JLabel freshnessLabel = new JLabel();

    /**
     * How current the rows on screen are.
     * <p>
     * Owned by the panel rather than recomputed per render, so re-drawing for an unrelated
     * reason -- switching board view, toggling hidden tiles -- cannot make a board look
     * freshly fetched when nothing was fetched.
     */
    private volatile Freshness freshness = Freshness.UNKNOWN;

    /** When the last lifecycle-current board actually arrived; null until one has. */
    @Nullable
    private volatile Instant lastSuccess;

    /** Non-null only while the backend has explicitly refused a refresh. */
    @Nullable
    private volatile String staleReason;

    /**
     * Whether the tile rows are on screen at all. A loading or error screen has no rows, so
     * there is nothing for a freshness line to describe.
     */
    private volatile boolean showingBoard;

    /** Overridden in tests so the rendered time does not depend on the wall clock. */
    private Clock clock = Clock.systemDefaultZone();

    /**
     * Built once. The label is re-rendered on every refresh, so formatting must not allocate a
     * formatter each time, and the player's own locale and zone decide how the time reads.
     */
    private final DateTimeFormatter timeFormat = DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault());

    private enum Freshness { UNKNOWN, REFRESHING, UPDATED, FAILED, REJECTED }
    private final JPanel itemsPanel = new JPanel();
    private final JScrollPane itemsScrollPane = new JScrollPane(
        itemsPanel,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    );
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton testButton = new JButton("Test Dink");
    private final JLabel testStatusLabel = new JLabel();

    private Runnable refreshHandler = () -> {
    };

    private Runnable testHandler = () -> {
    };

    /**
     * Long enough that a mistaken double press cannot put two messages in an organizer's
     * channel, short enough to re-test straight after fixing a setting.
     */
    private static final Duration TEST_COOLDOWN = Duration.ofSeconds(30);

    /** When the last test was handed to Dink; null until one has been. EDT-owned. */
    @Nullable
    private Instant lastTestAt;

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
        freshnessLabel.setFont(FontManager.getRunescapeSmallFont());
        freshnessLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        freshnessLabel.setVisible(false);

        titles.add(headerLabel);
        titles.add(statusLabel);
        titles.add(freshnessLabel);

        refreshButton.setFocusPainted(false);
        refreshButton.addActionListener(e -> refreshHandler.run());

        testButton.setFocusPainted(false);
        testButton.setFont(FontManager.getRunescapeSmallFont());
        testButton.setToolTipText("Post a test notification to Dink. Claims nothing and "
            + "changes no tile.");
        testButton.addActionListener(e -> confirmAndSendTest());

        testStatusLabel.setFont(FontManager.getRunescapeSmallFont());
        testStatusLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        testStatusLabel.setVisible(false);

        header.add(titles, BorderLayout.CENTER);
        header.add(refreshButton, BorderLayout.EAST);

        JPanel footer = new JPanel(new BorderLayout(0, 4));
        footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        footer.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        footer.add(testButton, BorderLayout.NORTH);
        footer.add(testStatusLabel, BorderLayout.CENTER);

        itemsPanel.setLayout(new GridBagLayout());
        itemsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        itemsScrollPane.setBorder(BorderFactory.createEmptyBorder());
        itemsScrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        itemsScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        add(header, BorderLayout.NORTH);
        add(itemsScrollPane, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    public void setRefreshHandler(Runnable handler) {
        this.refreshHandler = handler;
    }

    public void setTestHandler(Runnable handler) {
        this.testHandler = handler;
    }

    /**
     * Asks before posting, because the message lands in whatever channel the event uses and
     * nobody else can tell a stray test from a mistake.
     */
    private void confirmAndSendTest() {
        int choice = JOptionPane.showConfirmDialog(
            this,
            "Post a test notification to Dink?\n\n"
                + "It claims nothing and changes no tile, but it does appear in the Discord "
                + "channel your webhook points at.",
            "Test Dink notification",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            sendTest();
        }
    }

    /**
     * Hands a test to the plugin unless one was just sent. Returns whether it was sent.
     * <p>
     * The wording afterwards deliberately stops at "handed to Dink". Dink acknowledges
     * nothing, so a panel that said "delivered" would be the same false confidence the test
     * exists to remove -- the message appearing in Discord is the only confirmation.
     */
    boolean sendTest() {
        Instant now = clock.instant();
        if (lastTestAt != null && Duration.between(lastTestAt, now).compareTo(TEST_COOLDOWN) < 0) {
            return false;
        }
        lastTestAt = now;
        testButton.setEnabled(false);
        // The width hint is what makes a long HTML label wrap instead of clipping in the
        // fixed-width sidebar.
        testStatusLabel.setText("<html><body style='width:100%'>Sent to Dink at "
            + escape(timeFormat.format(now))
            + ". Dink does not confirm delivery \u2014 check Discord.</body></html>");
        testStatusLabel.setVisible(true);

        Timer reEnable = new Timer((int) TEST_COOLDOWN.toMillis(),
            e -> testButton.setEnabled(true));
        reEnable.setRepeats(false);
        reEnable.start();

        testHandler.run();
        return true;
    }

    /** The test line exactly as a player reads it; empty when no test has been sent. */
    String testStatusText() {
        return testStatusLabel.isVisible() ? testStatusLabel.getText() : "";
    }

    /** Safe to call from any thread. */
    public void render(
        BingoBoard board,
        boolean configured,
        BoardView boardView,
        boolean hideCompletedTiles
    ) {
        showingBoard = configured && board.isConfigured();
        SwingUtilities.invokeLater(() ->
            renderOnEdt(board, configured, boardView, hideCompletedTiles));
    }

    /**
     * A lifecycle-current board arrived. Safe to call from any thread.
     * <p>
     * Separate from {@link #render} on purpose: the board is also re-rendered when the player
     * switches view or toggles hidden tiles, and stamping those as a refresh would tell them
     * the board is current when nothing was fetched.
     */
    public void markRefreshSucceeded() {
        lastSuccess = clock.instant();
        staleReason = null;
        freshness = Freshness.UPDATED;
        SwingUtilities.invokeLater(this::updateFreshnessLabel);
    }

    /**
     * A refresh is under way. Safe to call from any thread.
     * <p>
     * Deliberately touches only the freshness line. Blanking the rows here is what made a
     * periodic refresh flicker the whole board, and it throws away something readable in
     * exchange for nothing.
     */
    public void markRefreshing() {
        freshness = Freshness.REFRESHING;
        SwingUtilities.invokeLater(this::updateFreshnessLabel);
    }

    /**
     * The round trip failed and the rows on screen are the last good board. Safe to call from
     * any thread.
     * <p>
     * Distinct from a rejection: the board is probably still correct, so it keeps its last
     * success time and claims keep being submitted.
     */
    public void markRefreshFailed() {
        freshness = Freshness.FAILED;
        SwingUtilities.invokeLater(this::updateFreshnessLabel);
    }

    /** Forget how fresh anything was, for logout, a token change, or shutdown. */
    public void resetFreshness() {
        lastSuccess = null;
        staleReason = null;
        freshness = Freshness.UNKNOWN;
        SwingUtilities.invokeLater(this::updateFreshnessLabel);
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
        staleReason = BingoErrors.describeBoardError(backendError);
        freshness = Freshness.REJECTED;
        showingBoard = configured && board.isConfigured();
        SwingUtilities.invokeLater(() ->
            renderOnEdt(board, configured, boardView, hideCompletedTiles));
    }

    /** Safe to call from any thread. */
    public void renderLoading() {
        showingBoard = false;
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
        showingBoard = false;
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
        showingBoard = false;
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
        boolean hideCompletedTiles
    ) {
        SwingUtil.fastRemoveAll(itemsPanel);

        if (!configured) {
            headerLabel.setText("Not configured");
            statusLabel.setForeground(LIVE_STATUS_COLOR);
            statusLabel.setText("Set a Backend URL in the config");
            freshnessLabel.setVisible(false);
            refreshItemsPanel();
            return;
        }

        if (!board.isConfigured()) {
            headerLabel.setText("No team");
            statusLabel.setForeground(LIVE_STATUS_COLOR);
            statusLabel.setText("Your RSN is not on the Teams tab");
            freshnessLabel.setVisible(false);
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
        updateFreshnessLabel();

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
        // There are no rows on screen, so there is nothing for a freshness line to describe.
        freshnessLabel.setVisible(false);
        refreshItemsPanel();
    }

    /**
     * Describe how current the rows are, in the player's own locale and time zone.
     * <p>
     * Always an absolute time rather than "just now". Nothing ticks this label, so a relative
     * phrase would still read "just now" twenty minutes later, which is worse than no
     * indicator at all during an event.
     */
    private void updateFreshnessLabel() {
        String text = freshnessText();
        freshnessLabel.setText(text);
        freshnessLabel.setVisible(!text.isEmpty());
    }

    /**
     * The freshness line exactly as a player reads it, derived from the panel's own state.
     * <p>
     * Deliberately not read back out of the label. Swing fields are owned by the EDT, so
     * reading them from anywhere else sees whatever was published last, which made this
     * untestable and would make any future caller subtly wrong.
     */
    String freshnessText() {
        if (!showingBoard) {
            return "";
        }
        String text;
        switch (freshness) {
            case REFRESHING:
                text = "Refreshing\u2026";
                break;
            case UPDATED:
                text = "Updated " + formatLastSuccess();
                break;
            case FAILED:
                text = lastSuccess == null ? "Refresh failed"
                    : "Last updated " + formatLastSuccess() + " \u2014 refresh failed";
                break;
            case REJECTED:
                // The status line above already carries the reason and the warning, so this
                // only has to say how old the rows are.
                text = lastSuccess == null ? "" : "Last updated " + formatLastSuccess();
                break;
            default:
                text = "";
                break;
        }
        return text;
    }

    private String formatLastSuccess() {
        return lastSuccess == null ? "never" : timeFormat.format(lastSuccess);
    }

    /** Test seam so a rendered time does not depend on the wall clock. */
    void setClock(Clock clock) {
        this.clock = clock;
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
