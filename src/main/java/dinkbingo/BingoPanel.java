package dinkbingo;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.SwingUtil;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicHTML;
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

    /**
     * The readiness report, and the button that swaps it in for the board.
     * <p>
     * A second view rather than a section of the first one: it answers a different question
     * ("is my setup going to work?" rather than "what is left to hunt?"), it is read once
     * before an event rather than glanced at during one, and its rows are long enough that
     * folding them into the board would push the tiles off a narrow sidebar.
     */
    private final JPanel checkPanel = new JPanel();
    private final JScrollPane checkScrollPane = new JScrollPane(
        checkPanel,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    );
    private final JButton systemCheckButton = new JButton();
    private final JButton runChecksButton = new JButton("Run checks again");

    /** Whether the report has replaced the board in the centre of the panel. EDT-owned. */
    private boolean showingCheck;

    /** The last report evaluated, so opening the view is instant. EDT-owned. */
    @Nullable
    private SystemCheck lastCheck;

    private Runnable systemCheckHandler = () -> {
    };

    /** The button's untinted colour, captured before severity is allowed to change it. */
    private final Color defaultButtonColor;

    /**
     * Whether the board itself wants the filter strip. The report has no rows to filter, so
     * the strip is hidden while it is up without forgetting what the board asked for.
     */
    private boolean filterBarWanted;

    /**
     * Local view controls. They narrow and reorder what is on screen and nothing else: a tile
     * hidden here is still claimed normally when it drops, and no control reaches the backend.
     */
    private final JTextField searchField = new JTextField();
    private final JComboBox<BoardProgressFilter> progressCombo =
        new JComboBox<>(BoardProgressFilter.values());
    private final JComboBox<BoardSort> sortCombo = new JComboBox<>(BoardSort.values());
    private final JCheckBox eligibleOnlyBox = new JCheckBox("Only what can still be claimed");
    private final JButton clearFiltersButton = new JButton("Clear filters");
    private final JButton filtersToggle = new JButton();
    private final JPanel filterControls = new JPanel();
    private final JPanel filterBar = new JPanel(new BorderLayout());

    /**
     * Set while the filters are being reset programmatically, so clearing four controls
     * redraws the board once at the end rather than four times on the way there.
     */
    private boolean adjustingFilters;

    /**
     * The last board handed to {@link #render}, so changing a filter can redraw the rows
     * without a fetch. EDT-owned, like the controls that read it.
     */
    private BingoBoard lastBoard = BingoBoard.EMPTY;
    private boolean lastConfigured;
    private BoardView lastBoardView = BoardView.NAMED_TILES;
    private boolean lastHideCompletedTiles;

    /** Why the list is empty, or "" when it is not. EDT-owned. */
    private String emptyState = "";

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

    /**
     * Completed render counts, read by tests to wait for the rows they asked for. See
     * {@link #renderCount()}.
     */
    private volatile int renderCount;
    private volatile int checkRenderCount;

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
        header.add(buildFilterBar(), BorderLayout.SOUTH);

        systemCheckButton.setFocusPainted(false);
        systemCheckButton.setFont(FontManager.getRunescapeSmallFont());
        // Captured before anything tints it, so going back to ready restores the real default
        // rather than a guess at what the look and feel uses.
        defaultButtonColor = systemCheckButton.getForeground();
        systemCheckButton.setToolTipText("Check the whole setup \u2014 backend, token, team, "
            + "event, Dink \u2014 without claiming anything.");
        systemCheckButton.addActionListener(e -> setSystemCheckVisible(!showingCheck));
        updateSystemCheckButton();

        runChecksButton.setFocusPainted(false);
        runChecksButton.setFont(FontManager.getRunescapeSmallFont());
        runChecksButton.addActionListener(e -> systemCheckHandler.run());

        checkPanel.setLayout(new GridBagLayout());
        checkPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        checkScrollPane.setBorder(BorderFactory.createEmptyBorder());
        checkScrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        checkScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        // The view can be opened before the plugin has published anything, and an empty panel
        // is indistinguishable from a report that found nothing to say. Populated directly
        // rather than through drawCheckRows: there is nothing to tear down yet, and the
        // teardown is the part that belongs to the EDT.
        populateCheckRows(null);

        JPanel footer = new JPanel(new BorderLayout(0, 4));
        footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        footer.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        JPanel buttons = new JPanel(new BorderLayout(0, 4));
        buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
        buttons.add(systemCheckButton, BorderLayout.NORTH);
        buttons.add(testButton, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.NORTH);
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

    public void setSystemCheckHandler(Runnable handler) {
        this.systemCheckHandler = handler;
    }

    /**
     * The search, filter, and sort strip that sits under the team summary.
     * <p>
     * Collapsed by default and one line tall when it is. The summary and Refresh button are
     * the two things a player needs during a drop, so the controls are not allowed to push
     * them off the top of a narrow sidebar just by existing.
     */
    private JPanel buildFilterBar() {
        filterBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
        filterBar.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        filtersToggle.setFocusPainted(false);
        filtersToggle.setFont(FontManager.getRunescapeSmallFont());
        filtersToggle.setToolTipText("Search, filter, and sort the rows below. "
            + "Nothing here changes what can be claimed.");
        filtersToggle.addActionListener(e -> setFiltersExpanded(!filterControls.isVisible()));

        searchField.setFont(FontManager.getRunescapeSmallFont());
        searchField.setToolTipText("Matches tile names and item names");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onFilterChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onFilterChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onFilterChanged();
            }
        });

        progressCombo.setFont(FontManager.getRunescapeSmallFont());
        progressCombo.setToolTipText("Show every tile, or only the ones at this stage");
        progressCombo.addActionListener(e -> onFilterChanged());

        sortCombo.setFont(FontManager.getRunescapeSmallFont());
        sortCombo.setToolTipText("Reorders the rows on screen. The board itself is unchanged");
        sortCombo.addActionListener(e -> onFilterChanged());

        eligibleOnlyBox.setFont(FontManager.getRunescapeSmallFont());
        eligibleOnlyBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
        eligibleOnlyBox.setForeground(OPEN_COLOR);
        eligibleOnlyBox.setToolTipText("Hide completed tiles and items already credited");
        // An item listener rather than an action listener, so clearing the box in code
        // redraws the board the same way a click does.
        eligibleOnlyBox.addItemListener(e -> onFilterChanged());

        clearFiltersButton.setFocusPainted(false);
        clearFiltersButton.setFont(FontManager.getRunescapeSmallFont());
        clearFiltersButton.setEnabled(false);
        clearFiltersButton.addActionListener(e -> clearFiltersOnEdt());

        filterControls.setLayout(new BoxLayout(filterControls, BoxLayout.Y_AXIS));
        filterControls.setBackground(ColorScheme.DARK_GRAY_COLOR);
        filterControls.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        filterControls.add(labelled("Search", searchField));
        filterControls.add(labelled("Show", progressCombo));
        filterControls.add(labelled("Sort", sortCombo));
        filterControls.add(eligibleOnlyBox);
        filterControls.add(clearFiltersButton);
        filterControls.setVisible(false);

        filterBar.add(filtersToggle, BorderLayout.NORTH);
        filterBar.add(filterControls, BorderLayout.CENTER);
        updateFiltersToggle();
        return filterBar;
    }

    private static JPanel labelled(String text, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        row.add(label, BorderLayout.WEST);
        row.add(control, BorderLayout.CENTER);
        return row;
    }

    private void setFiltersExpanded(boolean expanded) {
        filterControls.setVisible(expanded);
        updateFiltersToggle();
        filterBar.revalidate();
        filterBar.repaint();
    }

    /**
     * Says whether anything is filtering the list even while the controls are folded away, so
     * a short board is never mistaken for a board with nothing left on it.
     */
    private void updateFiltersToggle() {
        filtersToggle.setText((filterControls.isVisible() ? "\u25BE Filters" : "\u25B8 Filters")
            + (currentFilter().isActive() ? " \u2014 on" : ""));
    }

    /** The filter strip exactly as a player reads it. */
    String filtersToggleText() {
        return filtersToggle.getText();
    }

    /** The controls as they currently stand. */
    BoardFilter currentFilter() {
        return new BoardFilter(
            searchField.getText(),
            (BoardProgressFilter) progressCombo.getSelectedItem(),
            (BoardSort) sortCombo.getSelectedItem(),
            eligibleOnlyBox.isSelected());
    }

    /**
     * Redraw the rows for a control change.
     * <p>
     * Deliberately reuses the last board rather than asking for a fetch: filtering is a view
     * over the snapshot already on screen, and it must not restamp how fresh that snapshot is.
     * A message screen is left alone, because there are no rows to filter.
     */
    private void onFilterChanged() {
        if (adjustingFilters) {
            return;
        }
        updateFiltersToggle();
        clearFiltersButton.setEnabled(currentFilter().isActive());
        if (showingBoard) {
            renderOnEdt(lastBoard, lastConfigured, lastBoardView, lastHideCompletedTiles);
        }
    }

    /**
     * Drop every filter back to its default. Safe to call from any thread.
     * <p>
     * Used when the event itself changes. A search for a tile that only existed on the old
     * board would otherwise present the new one as empty.
     */
    public void clearFilters() {
        SwingUtilities.invokeLater(this::clearFiltersOnEdt);
    }

    private void clearFiltersOnEdt() {
        adjustingFilters = true;
        try {
            searchField.setText("");
            progressCombo.setSelectedItem(BoardProgressFilter.ALL);
            sortCombo.setSelectedItem(BoardSort.BOARD_ORDER);
            eligibleOnlyBox.setSelected(false);
        } finally {
            adjustingFilters = false;
        }
        onFilterChanged();
    }

    /** Test seams. Swing controls belong to the EDT, so call these from it. */
    void setSearch(String text) {
        searchField.setText(text);
    }

    void setProgressFilter(BoardProgressFilter progress) {
        progressCombo.setSelectedItem(progress);
    }

    void setSort(BoardSort sort) {
        sortCombo.setSelectedItem(sort);
    }

    void setEligibleOnly(boolean eligibleOnly) {
        eligibleOnlyBox.setSelected(eligibleOnly);
    }

    /** Why the list is empty as a player reads it; "" when rows are on screen. */
    String emptyStateText() {
        return emptyState;
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

    /**
     * Forget the last Dink test, for shutdown. Safe to call from any thread.
     * <p>
     * The panel outlives a plugin restart, so a line still reading "Sent to Dink at 3:42"
     * would be describing a run that has ended -- the same reason the freshness line and the
     * readiness report are cleared. The cooldown goes with it: it exists so a mistaken double
     * press cannot put two messages in an organizer's channel, and restarting the plugin is
     * not a mistaken double press. Leaving it would start the new run with a dead button and
     * nothing on screen to explain it.
     */
    public void resetTestStatus() {
        SwingUtilities.invokeLater(() -> {
            lastTestAt = null;
            testStatusLabel.setText("");
            testStatusLabel.setVisible(false);
            testButton.setEnabled(true);
        });
    }

    // ------------------------------------------------------------------
    // system check
    // ------------------------------------------------------------------

    /**
     * A freshly evaluated readiness report. Safe to call from any thread.
     * <p>
     * Published whenever the plugin learns something new, not only while the report is on
     * screen, so the button that opens it can carry the headline. A player who has not thought
     * to look is exactly the one who needs to be told that nothing is being claimed.
     */
    public void renderSystemCheck(SystemStatus status) {
        SystemCheck check = SystemCheck.evaluate(status);
        SwingUtilities.invokeLater(() -> applySystemCheck(check));
    }

    private void applySystemCheck(SystemCheck check) {
        lastCheck = check;
        updateSystemCheckButton();
        drawCheckRows(check);
    }

    /**
     * Swap the report in for the board, or back.
     * <p>
     * Opening it asks for a fresh evaluation, which reuses the ordinary board refresh: it is
     * coalesced with any fetch already in flight, and it is a read, so pressing this can never
     * change a tile.
     */
    void setSystemCheckVisible(boolean visible) {
        boolean swapping = visible != showingCheck;
        if (swapping) {
            showingCheck = visible;
            remove(visible ? itemsScrollPane : checkScrollPane);
            add(visible ? checkScrollPane : itemsScrollPane, BorderLayout.CENTER);
            updateFilterBarVisibility();
            updateSystemCheckButton();
        }
        // Asked for on the way in and on a second press while it is already up, so the rows
        // are never a stale report the player has to know to refresh. Closing asks for nothing.
        if (visible) {
            systemCheckHandler.run();
        }
        if (swapping) {
            revalidate();
            repaint();
        }
    }

    private void setFilterBarWanted(boolean wanted) {
        filterBarWanted = wanted;
        updateFilterBarVisibility();
    }

    /** The strip filters tile rows, and there are none on screen while the report is up. */
    private void updateFilterBarVisibility() {
        filterBar.setVisible(filterBarWanted && !showingCheck);
    }

    /**
     * Carries the headline on the button itself, so a broken setup is visible from the board
     * without opening anything.
     */
    private void updateSystemCheckButton() {
        if (showingCheck) {
            systemCheckButton.setText("Back to board");
            systemCheckButton.setForeground(defaultButtonColor);
            return;
        }
        if (lastCheck == null) {
            systemCheckButton.setText("System check");
            systemCheckButton.setForeground(defaultButtonColor);
            return;
        }
        systemCheckButton.setText("System check \u2014 " + lastCheck.summary());
        // A problem has to be visible from the board, not only to whoever thought to look.
        SystemCheck.State state = lastCheck.getState();
        systemCheckButton.setForeground(state == SystemCheck.State.FAILED
            || state == SystemCheck.State.WARNING
            ? stateColor(state) : defaultButtonColor);
    }

    /** {@code null} before anything has been checked, which is not the same as nothing wrong. */
    private void drawCheckRows(@Nullable SystemCheck check) {
        SwingUtil.fastRemoveAll(checkPanel);
        populateCheckRows(check);
    }

    private void populateCheckRows(@Nullable SystemCheck check) {
        RowSink rows = new RowSink(checkPanel);
        rows.add(runChecksButton);
        rows.add(buildCheckNote());
        if (check == null) {
            rows.add(buildEmptyRow("Nothing has been checked yet. Press Run checks again."));
        } else {
            for (SystemCheck.Row row : check.getRows()) {
                rows.add(buildCheckRow(row));
            }
        }

        checkPanel.revalidate();
        checkPanel.repaint();
        checkRenderCount++;
    }

    /**
     * Says up front that the report changes nothing, because the alternative way to find out
     * whether a setup works is to claim a tile with a drop the team cannot get back.
     */
    private static JPanel buildCheckNote() {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        row.add(wrappingLabel("Reads your setup only. Nothing here claims a tile, changes the "
            + "board, or writes to the sheet.", ColorScheme.MEDIUM_GRAY_COLOR),
            BorderLayout.CENTER);
        return row;
    }

    private static JPanel buildCheckRow(SystemCheck.Row check) {
        JPanel row = new JPanel(new BorderLayout(0, 2));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        row.add(smallLabel(glyph(check.getState()) + " " + check.getName(),
            stateColor(check.getState())), BorderLayout.NORTH);
        // The detail and the remedy are wrapped, which escapes them. A team name comes from
        // the organizer's sheet and a backend reason from a deployment the player does not
        // control, and Swing would otherwise render either as markup.
        row.add(wrappingLabel(check.getDetail(), OPEN_COLOR), BorderLayout.CENTER);

        String action = check.getAction();
        if (action != null) {
            row.add(wrappingLabel(action, ColorScheme.LIGHT_GRAY_COLOR), BorderLayout.SOUTH);
        }

        return row;
    }

    private static String glyph(SystemCheck.State state) {
        switch (state) {
            case READY:
                return "\u2713";
            case WARNING:
                return "\u26A0";
            case FAILED:
                return "\u2717";
            case CHECKING:
                return "\u2026";
            default:
                return "\u2013";
        }
    }

    private static Color stateColor(SystemCheck.State state) {
        switch (state) {
            case READY:
                return ColorScheme.PROGRESS_COMPLETE_COLOR;
            case WARNING:
                return ColorScheme.PROGRESS_INPROGRESS_COLOR;
            case FAILED:
                return STALE_COLOR;
            default:
                return ColorScheme.LIGHT_GRAY_COLOR;
        }
    }

    /**
     * Forget the last report, for shutdown. The panel outlives a plugin restart, so a button
     * still reading "System check -- ready" would be describing a run that has ended.
     */
    public void resetSystemCheck() {
        SwingUtilities.invokeLater(() -> {
            lastCheck = null;
            setSystemCheckVisible(false);
            updateSystemCheckButton();
            drawCheckRows(null);
        });
    }

    /** The button exactly as a player reads it. */
    String systemCheckButtonText() {
        return systemCheckButton.getText();
    }

    /** Whether the report has replaced the board. */
    boolean isShowingSystemCheck() {
        return showingCheck;
    }

    /** How many report renders have finished; the same test seam as {@link #renderCount()}. */
    int checkRenderCount() {
        return checkRenderCount;
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
        // The wording lives in BingoErrors alongside the claim-failure phrasing, so the
        // sidebar and the chat line cannot drift apart.
        String reason = BingoErrors.describeBoardError(backendError);
        showingBoard = false;
        SwingUtilities.invokeLater(() -> renderMessage("Couldn't load board", reason));
    }

    private void renderOnEdt(
        BingoBoard board,
        boolean configured,
        BoardView boardView,
        boolean hideCompletedTiles
    ) {
        // Remembered so a filter change can redraw these same rows without a fetch.
        lastBoard = board;
        lastConfigured = configured;
        lastBoardView = boardView;
        lastHideCompletedTiles = hideCompletedTiles;
        emptyState = "";

        if (!configured) {
            renderMessage("Not configured", "Set a Backend URL in the config");
            return;
        }

        if (!board.isConfigured()) {
            renderMessage("No team", "Your RSN is not on the Teams tab");
            return;
        }

        SwingUtil.fastRemoveAll(itemsPanel);

        headerLabel.setText(boardText(staleReason == null
            ? board.getTeam() : board.getTeam() + " \u2014 not live"));
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

        setFilterBarWanted(true);
        updateFiltersToggle();
        BoardFilter filter = currentFilter();

        clearFiltersButton.setEnabled(filter.isActive());

        // Filtering and sorting happen over a copy. The snapshot the detector matches drops
        // against is the same object either way, so nothing here can change what is claimable.
        List<BingoTile> tiles = filter.apply(board);

        RowSink rows = new RowSink(itemsPanel);
        if (boardView == BoardView.POSSIBLE_ITEMS) {
            addOptionRows(rows, tiles, filter, hideCompletedTiles);
        } else {
            addTileRows(rows, tiles, hideCompletedTiles);
        }

        if (rows.isEmpty() && !board.getTiles().isEmpty()) {
            // An empty list is otherwise indistinguishable from a board with nothing left,
            // which is the one reading that would make a player stop hunting.
            emptyState = emptyStateFor(filter, hideCompletedTiles);
            if (!emptyState.isEmpty()) {
                rows.add(buildEmptyRow(emptyState));
            }
        }

        refreshItemsPanel();
        renderCount++;
    }

    /** One row per tile, which is the view a player watches during an event. */
    private void addTileRows(RowSink rows, List<BingoTile> tiles, boolean hideCompletedTiles) {
        for (BingoTile tile : tiles) {
            if (hideCompletedTiles && tile.isClaimed()) {
                continue;
            }
            rows.add(buildTileRow(tile));
        }
    }

    /**
     * One row per item that could satisfy a tile, for deciding what to hunt.
     * <p>
     * A credited item keeps its row, struck through, rather than disappearing: that is what
     * tells a player their drop was counted. Dropping the row instead just reshuffles the list
     * under them, which reads like the item was never eligible.
     */
    private void addOptionRows(
        RowSink rows,
        List<BingoTile> tiles,
        BoardFilter filter,
        boolean hideCompletedTiles
    ) {
        for (BingoTile tile : tiles) {
            if (tile.isClaimed()) {
                // A completed tile has no options left to hunt, so it contributes only the
                // items that finished it, and only while completed rows are being shown.
                if (!hideCompletedTiles) {
                    for (BingoContribution credited : creditedContributions(tile)) {
                        addCreditedRow(rows, filter, tile, credited);
                    }
                }
                continue;
            }
            Map<Integer, BingoContribution> credited = creditedById(tile);
            for (BingoItem option : tile.getOptions()) {
                BingoContribution counted = credited.get(option.getId());
                if (counted == null) {
                    if (filter.showsOption(tile, option.getName(), false)) {
                        rows.add(buildItemRow(tile, option));
                    }
                } else if (!hideCompletedTiles) {
                    addCreditedRow(rows, filter, tile, counted);
                }
            }
        }
    }

    private void addCreditedRow(
        RowSink rows,
        BoardFilter filter,
        BingoTile tile,
        BingoContribution credited
    ) {
        if (filter.showsOption(tile, credited.getName(), true)) {
            rows.add(buildCreditedItemRow(tile, credited));
        }
    }

    /**
     * Appends rows down a single {@link GridBagLayout} column.
     * <p>
     * Owns the row index and the constraints, so a caller adds a row instead of adding a
     * component and remembering to advance a counter -- which the item view had to do at four
     * separate call sites. Counting them is also what decides whether an empty-state line is
     * needed, and that count has to include rows the filters dropped.
     */
    private static final class RowSink {

        private final JPanel panel;
        private final GridBagConstraints constraints = new GridBagConstraints();
        private int count;

        RowSink(JPanel panel) {
            this.panel = panel;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.gridx = 0;
            constraints.gridy = 0;
            constraints.weightx = 1;
        }

        void add(JComponent row) {
            panel.add(row, constraints);
            constraints.gridy++;
            count++;
        }

        boolean isEmpty() {
            return count == 0;
        }
    }

    private static String emptyStateFor(BoardFilter filter, boolean hideCompletedTiles) {
        if (filter.isActive()) {
            return "Nothing matches the filters you have set \u2014 clear them to see the "
                + "whole board.";
        }
        if (hideCompletedTiles) {
            return "Every tile is completed. Turn off Hide Completed Tiles to see them.";
        }
        return "";
    }

    private static JPanel buildEmptyRow(String message) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));
        row.add(wrappingLabel(message, ColorScheme.LIGHT_GRAY_COLOR), BorderLayout.CENTER);
        return row;
    }

    /**
     * Replace the rows with a header and one explanatory line.
     * <p>
     * Every screen that has no tiles on it goes through here -- loading, a failed or refused
     * fetch, no backend configured, no team -- so they cannot drift apart on whether they
     * clear the freshness line or hide the filter strip. Both must go: there is nothing for
     * either to describe.
     */
    private void renderMessage(String header, String status) {
        SwingUtil.fastRemoveAll(itemsPanel);
        headerLabel.setText(header);
        statusLabel.setForeground(LIVE_STATUS_COLOR);
        statusLabel.setText(status);
        freshnessLabel.setVisible(false);
        setFilterBarWanted(false);
        refreshItemsPanel();
        renderCount++;
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

    /**
     * How many renders have finished. A test seam, and one the panel cannot do without.
     * <p>
     * {@code SwingUtil.fastRemoveAll} pumps pending events while it tears down the old rows,
     * so a task queued behind a render can be run from inside it, with the previous board
     * still half on screen. Counting completed renders is what lets a test wait for the rows
     * it asked for instead of reading the ones being taken apart.
     */
    int renderCount() {
        return renderCount;
    }

    private void refreshItemsPanel() {
        itemsPanel.revalidate();
        itemsPanel.repaint();
    }

    private JPanel buildTileRow(BingoTile tile) {
        JPanel row = itemRowPanel();
        Map<Integer, BingoContribution> creditedItems = creditedById(tile);
        row.add(itemIcon(tileIconItem(tile, creditedItems)), BorderLayout.WEST);

        JLabel name;
        if (tile.isClaimed()) {
            String winner = tile.getClaimedItem() != null ? tile.getClaimedItem().getName() : null;
            name = smallLabel(struckThrough(tile.getName()), CLAIMED_COLOR);
            name.setToolTipText(boardText("Claimed by " + tile.getClaimedBy() +
                (winner == null ? "" : " with " + winner)));
        } else {
            name = smallLabel(boardText(tile.getName()), OPEN_COLOR);
            StringJoiner credited = new StringJoiner(", ");
            for (BingoContribution contribution : tile.getClaimedItems()) {
                credited.add(contribution.getName());
            }
            StringJoiner missing = new StringJoiner(", ");
            for (BingoItem option : tile.getOptions()) {
                if (!creditedItems.containsKey(option.getId())) missing.add(option.getName());
            }
            name.setToolTipText(boardText((tile.getClaimedItems().isEmpty() ? "" :
                "Credited: " + credited + ". ") + "Still eligible: " + missing));
        }
        row.add(name, BorderLayout.CENTER);

        if (tile.isClaimed() && tile.getClaimedBy() != null) {
            row.add(smallLabel(boardText(tile.getClaimedBy()),
                ColorScheme.PROGRESS_COMPLETE_COLOR), BorderLayout.EAST);
        } else {
            addProgressLabel(row, tile, null);
        }

        return row;
    }

    private JPanel buildItemRow(BingoTile tile, BingoItem option) {
        JPanel row = itemRowPanel();
        row.add(itemIcon(option), BorderLayout.WEST);

        JLabel name = smallLabel(boardText(option.getName()), OPEN_COLOR);
        name.setToolTipText(boardText("Eligible for " + tile.getName()));
        row.add(name, BorderLayout.CENTER);

        addProgressLabel(row, tile, tile.getName());

        return row;
    }

    /**
     * The item whose icon stands for the tile: the winning drop once it is complete, and
     * otherwise the first option still worth hunting. Null only for a tile with no options at
     * all, which leaves the icon slot blank rather than guessing.
     */
    @Nullable
    private static BingoItem tileIconItem(
        BingoTile tile,
        Map<Integer, BingoContribution> creditedItems
    ) {
        if (tile.getClaimedItem() != null) {
            return tile.getClaimedItem();
        }
        for (BingoItem option : tile.getOptions()) {
            if (!creditedItems.containsKey(option.getId())) {
                return option;
            }
        }
        return null;
    }

    /**
     * The {@code n/m} counter, shown only once there is something to count. A one-of-one tile
     * with no progress would otherwise read "0/1", which is just the open state spelled out.
     */
    private static void addProgressLabel(JPanel row, BingoTile tile, @Nullable String tooltip) {
        if (tile.getRequired() <= 1 && tile.getProgress() <= 0) {
            return;
        }
        JLabel progress = smallLabel(tile.getProgress() + "/" + tile.getRequired(),
            ColorScheme.PROGRESS_INPROGRESS_COLOR);
        progress.setToolTipText(tooltip == null ? null : boardText(tooltip));
        row.add(progress, BorderLayout.EAST);
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
        JPanel row = itemRowPanel();
        row.add(itemIcon(credited.getId()), BorderLayout.WEST);

        String claimedBy = credited.getClaimedBy() != null
            ? credited.getClaimedBy() : tile.getClaimedBy();

        JLabel name = smallLabel(struckThrough(credited.getName()), CLAIMED_COLOR);
        name.setToolTipText(boardText((tile.isClaimed()
            ? "Completed " + tile.getName()
            : "Already counted toward " + tile.getName())
            + (claimedBy == null ? "" : " by " + claimedBy)));
        row.add(name, BorderLayout.CENTER);

        if (claimedBy != null) {
            row.add(smallLabel(boardText(claimedBy), ColorScheme.PROGRESS_COMPLETE_COLOR),
                BorderLayout.EAST);
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

    // ------------------------------------------------------------------
    // widget helpers
    //
    // Every row in both views is the same shape -- a dark panel of small-font labels, some of
    // them wrapped, some carrying an item icon -- so the shape lives here once. Building it
    // inline per row is how three row builders drifted into three slightly different paddings
    // and two different ways of escaping a name.
    // ------------------------------------------------------------------

    /** The standard tile/item row: dark, padded, and laid out icon / name / trailing label. */
    private static JPanel itemRowPanel() {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return row;
    }

    private static JLabel smallLabel(String text, Color foreground) {
        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(foreground);
        return label;
    }

    /**
     * A label that wraps rather than clipping, for anything longer than a few words.
     * <p>
     * The width hint is what makes an HTML label wrap in the fixed-width sidebar. Wrapping
     * means rendering as HTML, so the text is escaped here rather than at each call site --
     * most of these strings come from the organizer's sheet or the backend.
     */
    private static JLabel wrappingLabel(String text, Color foreground) {
        return smallLabel("<html><body style='width:100%'>" + escape(text) + "</body></html>",
            foreground);
    }

    /**
     * An item icon slot of fixed size, so rows line up whether or not an image has loaded.
     * <p>
     * A null item leaves the slot blank rather than dropping it, which keeps the names in one
     * column on a tile that has no options to picture.
     */
    private JLabel itemIcon(@Nullable BingoItem item) {
        JLabel icon = emptyIcon();
        if (item != null) {
            itemManager.getImage(item.getId()).addTo(icon);
        }
        return icon;
    }

    private JLabel itemIcon(int itemId) {
        JLabel icon = emptyIcon();
        itemManager.getImage(itemId).addTo(icon);
        return icon;
    }

    private static JLabel emptyIcon() {
        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(36, 32));
        return icon;
    }

    /** Strikethrough via HTML is the only way to get it on a plain JLabel. */
    private static String struckThrough(String text) {
        return "<html><s>" + escape(text) + "</s></html>";
    }

    /**
     * Text from the board, made safe to hand straight to a label or a tooltip.
     * <p>
     * Swing renders a string as HTML when it starts with {@code <html>}, and a tile name, item
     * name, team, or player name is whatever the organizer's sheet says -- or whatever a
     * backend the player does not control chose to return. Left alone, such a value can render
     * as markup, and Swing's HTML supports {@code <img src>}, so the board could make the
     * client fetch a URL of the backend's choosing. {@link BingoErrors} already keeps backend
     * error text from leading a label for this reason; these are the same strings from the
     * same source.
     * <p>
     * Only a leading {@code <html>} switches the mode, so ordinary text is returned untouched
     * and keeps a plain label's ellipsis truncation. A value that would switch it is escaped
     * into an HTML label instead, which renders it literally.
     */
    private static String boardText(@Nullable String text) {
        if (text == null) {
            return "";
        }
        // The predicate Swing itself uses, rather than a second copy of it that could drift.
        return BasicHTML.isHTMLString(text) ? "<html>" + escape(text) + "</html>" : text;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
