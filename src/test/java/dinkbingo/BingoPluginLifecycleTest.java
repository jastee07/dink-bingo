package dinkbingo;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BingoPluginLifecycleTest {

    @Mock
    private Client client;
    @Mock
    private Player player;
    @Mock
    private ClientThread clientThread;
    @Mock
    private ClientToolbar clientToolbar;
    @Mock
    private ChatMessageManager chatMessageManager;
    @Mock
    private ScheduledExecutorService executor;
    @Mock
    private ScheduledFuture<?> refreshTask;
    @Mock
    private BingoConfig config;
    @Mock
    private BingoClient bingoClient;
    @Mock
    private BingoDetector detector;
    @Mock
    private BingoAnnouncer announcer;
    @Mock
    private BingoPanel panel;
    @Mock
    private OverlayManager overlayManager;
    @Mock
    private BingoVerificationOverlay verificationOverlay;

    private BingoPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        plugin = new BingoPlugin();
        inject("client", client);
        inject("clientThread", clientThread);
        inject("clientToolbar", clientToolbar);
        inject("chatMessageManager", chatMessageManager);
        inject("executor", executor);
        inject("config", config);
        inject("bingoClient", bingoClient);
        inject("detector", detector);
        inject("announcer", announcer);
        inject("panel", panel);
        inject("overlayManager", overlayManager);
        inject("verificationOverlay", verificationOverlay);

        when(config.refreshMinutes()).thenReturn(5);
        when(config.boardView()).thenReturn(BoardView.NAMED_TILES);
        when(bingoClient.isConfigured()).thenReturn(true);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(player);
        when(player.getName()).thenReturn("Jake");
        doReturn(refreshTask).when(executor)
            .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(clientThread).invokeLater(any(Runnable.class));
    }

    @Test
    void boardCompletionAfterShutdownIsIgnored() throws Exception {
        CompletableFuture<BoardResult> pending = new CompletableFuture<>();
        when(bingoClient.fetchBoard("Jake")).thenReturn(pending);
        BingoBoard board = board("Current Team");

        plugin.startUp();
        plugin.shutDown();
        pending.complete(BoardResult.of(board));

        verify(detector, never()).setBoard(any());
        verify(panel, never()).render(board, true, BoardView.NAMED_TILES, false);
    }

    @Test
    void overlappingRefreshesAreCoalescedAndOnlyTheNewestBoardApplies() throws Exception {
        CompletableFuture<BoardResult> first = new CompletableFuture<>();
        CompletableFuture<BoardResult> second = new CompletableFuture<>();
        when(bingoClient.fetchBoard("Jake")).thenReturn(first, second);
        BingoBoard stale = board("Stale Team");
        BingoBoard current = board("Current Team");

        plugin.startUp();
        plugin.refreshBoard();
        verify(bingoClient, times(1)).fetchBoard("Jake");

        first.complete(BoardResult.of(stale));
        verify(bingoClient, times(2)).fetchBoard("Jake");
        second.complete(BoardResult.of(current));

        verify(detector, never()).setBoard(stale);
        verify(detector).setBoard(current);
        verify(panel, never()).render(stale, true, BoardView.NAMED_TILES, false);
        verify(panel).render(current, true, BoardView.NAMED_TILES, false);
    }

    @Test
    void configuredStartupShowsLoadingUntilTheInitialBoardArrives() throws Exception {
        CompletableFuture<BoardResult> pending = new CompletableFuture<>();
        when(bingoClient.fetchBoard("Jake")).thenReturn(pending);
        BingoBoard board = board("Current Team");

        plugin.startUp();

        verify(panel).renderLoading();
        verify(panel, never()).render(BingoBoard.EMPTY, true, BoardView.NAMED_TILES, false);

        pending.complete(BoardResult.of(board));

        verify(panel).render(board, true, BoardView.NAMED_TILES, false);
    }

    @Test
    void initialBoardFailureReplacesLoadingWithAnError() throws Exception {
        when(bingoClient.fetchBoard("Jake")).thenReturn(
            CompletableFuture.completedFuture(null));

        plugin.startUp();

        verify(panel).renderLoading();
        verify(panel).renderLoadError();
    }

    /** An unreachable backend is the only failure the connection message actually explains. */
    @Test
    void anUnreachableBackendKeepsTheConnectionMessage() throws Exception {
        when(bingoClient.fetchBoard("Jake")).thenReturn(
            CompletableFuture.completedFuture(BoardResult.unreachable()));

        plugin.startUp();

        verify(panel).renderLoadError();
        verify(panel, never()).renderLoadError(anyString());
    }

    @Test
    void aBackendRejectionReachesThePanelWithItsReason() throws Exception {
        when(bingoClient.fetchBoard("Jake")).thenReturn(
            CompletableFuture.completedFuture(BoardResult.rejected("bad_token")));

        plugin.startUp();

        verify(panel).renderLoading();
        verify(panel).renderLoadError("bad_token");
        verify(panel, never()).renderLoadError();
    }

    /**
     * A later failure must not replace a board the player can still read, but an explicit
     * refusal must not let that board keep looking current either. An organizer rotating the
     * event token mid-event is the common way to reach this: the old board stays on screen,
     * every drop is submitted against it, and every claim is rejected with the reason hidden
     * because an earlier refresh had succeeded.
     */
    @Test
    void aBackendRejectionAfterASuccessfulLoadStopsClaimingAndLabelsTheBoard() throws Exception {
        BingoBoard board = board("Current Team");
        when(bingoClient.fetchBoard("Jake")).thenReturn(
            CompletableFuture.completedFuture(BoardResult.of(board)),
            CompletableFuture.completedFuture(BoardResult.rejected("bad_token")));

        plugin.startUp();
        plugin.refreshBoard();

        verify(panel).render(board, true, BoardView.NAMED_TILES, false);
        verify(detector).setDetectionEnabled(false);
        verify(panel).renderStale(board, true, BoardView.NAMED_TILES, false, "bad_token");
        // The board is not replaced by an error screen; the rows stay as reference.
        verify(panel, never()).renderLoadError(anyString());
        verify(panel, never()).renderLoadError();
    }

    /**
     * A failed round trip is not evidence the backend has refused this client, so the board
     * stays usable and drops keep being submitted. This is the case a brief outage produces,
     * and suspending detection for it would lose real claims.
     */
    @Test
    void aTransportFailureAfterASuccessfulLoadKeepsClaiming() throws Exception {
        BingoBoard board = board("Current Team");
        when(bingoClient.fetchBoard("Jake")).thenReturn(
            CompletableFuture.completedFuture(BoardResult.of(board)),
            CompletableFuture.completedFuture(BoardResult.unreachable()));

        plugin.startUp();
        plugin.refreshBoard();

        verify(detector, never()).setDetectionEnabled(false);
        verify(panel, never()).renderStale(any(), anyBoolean(), any(), anyBoolean(), anyString());
        verify(panel, never()).renderLoadError();
    }

    /** Recovery is ordinary: the next good board replaces the warning and resumes claiming. */
    @Test
    void aSuccessfulRefreshAfterARejectionRestoresTheBoard() throws Exception {
        BingoBoard first = board("Current Team");
        BingoBoard recovered = board("Recovered Team");
        when(bingoClient.fetchBoard("Jake")).thenReturn(
            CompletableFuture.completedFuture(BoardResult.of(first)),
            CompletableFuture.completedFuture(BoardResult.rejected("bad_token")),
            CompletableFuture.completedFuture(BoardResult.of(recovered)));

        plugin.startUp();
        plugin.refreshBoard();
        plugin.refreshBoard();

        // setBoard is what lifts the suspension, so the detector is handed the new board.
        verify(detector).setBoard(recovered);
        verify(panel).render(recovered, true, BoardView.NAMED_TILES, false);
    }

    /**
     * A rejection on the very first load has no board to keep, so it stays an error screen
     * rather than becoming a stale one.
     */
    @Test
    void aRejectionBeforeAnyBoardLoadsStillShowsTheErrorScreen() throws Exception {
        when(bingoClient.fetchBoard("Jake")).thenReturn(
            CompletableFuture.completedFuture(BoardResult.rejected("bad_token")));

        plugin.startUp();

        verify(detector).setDetectionEnabled(false);
        verify(panel).renderLoadError("bad_token");
        verify(panel, never()).renderStale(any(), anyBoolean(), any(), anyBoolean(), anyString());
    }

    private void inject(String name, Object value) throws Exception {
        Field field = BingoPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private static BingoBoard board(String team) {
        return new BingoBoard(team, Collections.singletonList(
            new BingoTile("4151", "Abyssal whip", 1,
                1, 0,
                Collections.singletonList(new BingoItem(4151, "Abyssal whip")),
                Collections.emptyList(),
                false, null, null, null)), true);
    }
}
