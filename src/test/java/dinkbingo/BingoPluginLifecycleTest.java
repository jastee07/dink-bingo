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
import static org.mockito.ArgumentMatchers.anyLong;
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
        CompletableFuture<BingoBoard> pending = new CompletableFuture<>();
        when(bingoClient.fetchBoard("Jake")).thenReturn(pending);
        BingoBoard board = board("Current Team");

        plugin.startUp();
        plugin.shutDown();
        pending.complete(board);

        verify(detector, never()).setBoard(any());
        verify(panel, never()).render(board, true);
    }

    @Test
    void overlappingRefreshesAreCoalescedAndOnlyTheNewestBoardApplies() throws Exception {
        CompletableFuture<BingoBoard> first = new CompletableFuture<>();
        CompletableFuture<BingoBoard> second = new CompletableFuture<>();
        when(bingoClient.fetchBoard("Jake")).thenReturn(first, second);
        BingoBoard stale = board("Stale Team");
        BingoBoard current = board("Current Team");

        plugin.startUp();
        plugin.refreshBoard();
        verify(bingoClient, times(1)).fetchBoard("Jake");

        first.complete(stale);
        verify(bingoClient, times(2)).fetchBoard("Jake");
        second.complete(current);

        verify(detector, never()).setBoard(stale);
        verify(detector).setBoard(current);
        verify(panel, never()).render(stale, true);
        verify(panel).render(current, true);
    }

    private void inject(String name, Object value) throws Exception {
        Field field = BingoPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private static BingoBoard board(String team) {
        return new BingoBoard(team, Collections.singletonList(
            new BingoTile("4151", "Abyssal whip", 1,
                Collections.singletonList(new BingoItem(4151, "Abyssal whip")),
                false, null, null, null)), true);
    }
}
