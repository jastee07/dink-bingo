package dinkbingo;

import com.google.inject.Provides;
import dinkbingo.BingoResponses.ClaimResponse;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.loottracker.LootRecordType;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@PluginDescriptor(
    name = "Bingo with Dink Notifications",
    description = "Claims bingo tiles on a shared board when you get the drop, and announces via Dink",
    tags = {"bingo", "dink", "loot", "clan", "event", "collection", "discord"}
)
public class BingoPlugin extends Plugin {

    /** A detached panel handler, for shutdown. */
    private static final Runnable NOTHING = () -> {
    };

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ChatMessageManager chatMessageManager;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private BingoConfig config;

    @Inject
    private BingoClient bingoClient;

    @Inject
    private BingoDetector detector;

    @Inject
    private BingoAnnouncer announcer;

    @Inject
    private BingoPanel panel;

    @Inject
    private PluginManager pluginManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private BingoVerificationOverlay verificationOverlay;

    private NavigationButton navButton;
    private ScheduledFuture<?> refreshTask;
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final AtomicLong refreshGeneration = new AtomicLong();
    private final Object refreshStateLock = new Object();
    private volatile boolean active;

    /**
     * The fetch currently in flight, or null when none is. Guarded by
     * {@link #refreshStateLock}.
     * <p>
     * One field rather than a flag beside the two generations it is only valid for: a
     * completion is accepted only when it names the request that is actually outstanding, and
     * holding those together is what makes that impossible to get half right.
     */
    @Nullable
    private RefreshKey inFlight;

    /** Whether a refresh was asked for while one was already in flight. */
    private boolean refreshPending;

    private volatile BingoBoard currentBoard = BingoBoard.EMPTY;
    private volatile boolean boardLoaded;

    /**
     * What the last board fetch did, for the readiness report.
     * <p>
     * Distinct from {@link #boardLoaded}, which only says whether rows exist: a board that
     * loaded an hour ago and has been refused ever since is loaded and not current, and the
     * report has to be able to say which.
     */
    private volatile SystemStatus.Fetch fetchState = SystemStatus.Fetch.NOT_CHECKED;

    /** The backend's raw reason for the last refusal, or null. */
    @Nullable
    private volatile String lastBackendError;

    @Provides
    BingoConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BingoConfig.class);
    }

    @Override
    protected void startUp() {
        active = true;
        lifecycleGeneration.incrementAndGet();
        refreshGeneration.incrementAndGet();
        synchronized (refreshStateLock) {
            inFlight = null;
            refreshPending = false;
        }
        forgetBoard();
        detector.setClaimListener(this::onClaimResolved);
        detector.setClaimUnresolvedListener(this::onClaimUnresolved);
        overlayManager.add(verificationOverlay);

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/bingo_icon.png");
        navButton = NavigationButton.builder()
            .tooltip("Bingo with Dink Notifications")
            .icon(icon)
            .priority(7)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        panel.setRefreshHandler(this::refreshBoard);
        panel.setTestHandler(this::sendDinkTest);
        panel.setSystemCheckHandler(this::runSystemCheck);
        // The panel outlives a plugin restart, so filters from the previous run would
        // silently narrow the first board of this one.
        panel.clearFilters();
        if (bingoClient.isConfigured()) {
            panel.renderLoading();
        } else {
            renderBoard(BingoBoard.EMPTY, false);
        }

        scheduleRefresh();
        refreshBoard();
    }

    @Override
    protected void shutDown() {
        active = false;
        lifecycleGeneration.incrementAndGet();
        refreshGeneration.incrementAndGet();
        synchronized (refreshStateLock) {
            refreshPending = false;
        }
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
        }
        navButton = null;
        overlayManager.remove(verificationOverlay);
        // The panel and the detector are singletons that outlive this run, so every callback
        // into the plugin is detached before it can fire against a shut-down instance.
        panel.setRefreshHandler(NOTHING);
        panel.setTestHandler(NOTHING);
        panel.setSystemCheckHandler(NOTHING);
        detector.setClaimListener((response, source) -> {
        });
        detector.setClaimUnresolvedListener(itemName -> {
        });
        detector.reset();
        // The panel outlives a plugin restart, so a timestamp left behind would describe a
        // board from the previous run, and a readiness summary left behind would describe a
        // run that has ended.
        panel.resetFreshness();
        panel.resetSystemCheck();
    }

    // ------------------------------------------------------------------
    // board lifecycle
    // ------------------------------------------------------------------

    private void scheduleRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
        }
        if (!active) {
            return;
        }
        long minutes = Math.max(1, config.refreshMinutes());
        refreshTask = executor.scheduleWithFixedDelay(
            this::refreshBoard, minutes, minutes, TimeUnit.MINUTES);
    }

    void refreshBoard() {
        if (!active) {
            return;
        }
        long lifecycle = lifecycleGeneration.get();
        long refresh = refreshGeneration.incrementAndGet();
        clientThread.invokeLater(() -> beginRefresh(lifecycle, refresh));
    }

    private void beginRefresh(long lifecycle, long requestedRefresh) {
        if (!isCurrent(lifecycle) || requestedRefresh != refreshGeneration.get()) {
            return;
        }
        if (!bingoClient.isConfigured()) {
            resetBoardState();
            renderBoard(BingoBoard.EMPTY, false);
            publishSystemStatus();
            return;
        }
        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) {
            return;
        }

        String rsn = client.getLocalPlayer().getName();
        RefreshKey key;
        synchronized (refreshStateLock) {
            if (inFlight != null) {
                refreshPending = true;
                return;
            }
            key = new RefreshKey(lifecycle, refreshGeneration.get());
            inFlight = key;
        }

        fetchState = SystemStatus.Fetch.CHECKING;
        panel.markRefreshing();
        publishSystemStatus();
        bingoClient.fetchBoard(rsn).whenComplete((result, error) ->
            finishRefresh(key, result, error));
    }

    private void finishRefresh(RefreshKey key, BoardResult result, Throwable error) {
        boolean rerun;
        synchronized (refreshStateLock) {
            if (!key.equals(inFlight)) {
                return;
            }
            inFlight = null;
            rerun = refreshPending;
            refreshPending = false;
        }

        long lifecycle = key.getLifecycle();
        if (isCurrent(lifecycle) && key.getRefresh() == refreshGeneration.get()) {
            if (error == null && result != null && result.isSuccess()) {
                BingoBoard board = result.getBoard();
                currentBoard = board;
                boardLoaded = true;
                fetchState = SystemStatus.Fetch.LOADED;
                lastBackendError = null;
                // Re-enables detection if an earlier refusal suspended it.
                detector.setBoard(board);
                // Only reached for a lifecycle-current refresh, so a stale completion from an
                // older request can never move the timestamp forward.
                panel.markRefreshSucceeded();
                renderBoard(board, true);
            } else {
                // A reason only exists when the backend answered and refused. Everything
                // else really is a failed round trip, which is what the generic message says.
                String backendError = result == null ? null : result.getBackendError();
                fetchState = backendError != null
                    ? SystemStatus.Fetch.REJECTED : SystemStatus.Fetch.UNREACHABLE;
                lastBackendError = backendError;
                if (backendError != null) {
                    // An explicit refusal -- a rotated token, a redeployed script, a broken
                    // sheet -- means the backend will not honour this client as it stands.
                    // Keeping the old board on screen and still submitting drops against it
                    // hides the reason behind a board that looks current and produces claims
                    // that cannot succeed. Stop detecting and say so; the rows stay as
                    // reference, labelled.
                    detector.setDetectionEnabled(false);
                    if (boardLoaded) {
                        panel.renderStale(currentBoard, true, config.boardView(),
                            config.hideCompletedTiles(), backendError);
                    } else {
                        panel.renderLoadError(backendError);
                    }
                } else if (boardLoaded) {
                    // The rows stay exactly as they are -- the board is probably still correct
                    // and the next drop may well get through -- but they are no longer
                    // confirmed current, and saying so is the whole point.
                    panel.markRefreshFailed();
                } else {
                    panel.renderLoadError();
                }
            }
            // After the outcome has been applied, so the report and the board can never
            // disagree about what the last fetch did.
            publishSystemStatus();
        }
        if (rerun && active) {
            refreshBoard();
        }
    }

    private boolean isCurrent(long lifecycle) {
        return active && lifecycle == lifecycleGeneration.get();
    }

    /**
     * Run something on the client thread, but only if this plugin run is still the current one.
     * <p>
     * The game state and the local player can only be read on that thread, and everything
     * queued onto it here is the tail of an asynchronous operation -- a finished claim, a
     * readiness snapshot, a chat line -- that can land after a shutdown or a restart. The
     * lifecycle is captured now and checked then, so late work is dropped rather than talking
     * to a panel and a detector that belong to a different run.
     */
    private void onClientThread(Runnable work) {
        long lifecycle = lifecycleGeneration.get();
        clientThread.invokeLater(() -> {
            if (isCurrent(lifecycle)) {
                work.run();
            }
        });
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            // The local player is not populated the instant the state flips.
            refreshBoard();
        } else if (event.getGameState() == GameState.LOGIN_SCREEN) {
            refreshGeneration.incrementAndGet();
            resetBoardState();
            renderLoadingIfConfigured();
            publishSystemStatus();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!BingoConfig.GROUP.equals(event.getGroup())) {
            return;
        }
        if ("refreshMinutes".equals(event.getKey())) {
            scheduleRefresh();
        }
        if ("backendUrl".equals(event.getKey()) || "eventToken".equals(event.getKey())) {
            resetBoardState();
            // A different backend or token is a different event. A search left over from the
            // old board would present the new one as empty.
            panel.clearFilters();
            renderLoadingIfConfigured();
            refreshBoard();
        } else if (("boardView".equals(event.getKey())
            || "hideCompletedTiles".equals(event.getKey())) && boardLoaded) {
            renderBoard(currentBoard, true);
        }
    }

    private void renderBoard(BingoBoard board, boolean configured) {
        panel.render(board, configured, config.boardView(), config.hideCompletedTiles());
    }

    /**
     * Forget the board and everything concluded from it.
     * <p>
     * Used whenever the thing the board described has changed underneath it: a logout, a new
     * backend or token, a plugin that is no longer configured. Nothing has been proved about
     * the new situation yet, so carrying the old verdict forward would report the previous
     * event's setup as this one's, and leaving the snapshot in place would let a drop be
     * claimed against a board that no longer applies.
     */
    private void resetBoardState() {
        forgetBoard();
        detector.reset();
        panel.resetFreshness();
    }

    /** The plugin's own view of the board, without touching the detector or the panel. */
    private void forgetBoard() {
        currentBoard = BingoBoard.EMPTY;
        boardLoaded = false;
        fetchState = SystemStatus.Fetch.NOT_CHECKED;
        lastBackendError = null;
    }

    /**
     * Say a board is on its way, but only if one actually is. With no backend configured the
     * panel keeps saying so, which is the thing the player has to fix.
     */
    private void renderLoadingIfConfigured() {
        if (bingoClient.isConfigured()) {
            panel.renderLoading();
        }
    }

    /**
     * Names one board fetch: the plugin run it belongs to, and which refresh of that run it is.
     * <p>
     * A completion is applied only when this still matches the outstanding request, so a
     * response that arrives after a restart, a logout, or a newer refresh is dropped rather
     * than overwriting current state with an older board.
     */
    @Value
    private static class RefreshKey {

        long lifecycle;

        long refresh;
    }

    // ------------------------------------------------------------------
    // readiness report
    // ------------------------------------------------------------------

    /**
     * Dink's plugin name and package. Matched exactly rather than by substring, because this
     * plugin is itself called "Bingo with Dink Notifications" and would otherwise find itself.
     */
    private static final String DINK_NAME = "Dink";
    private static final String DINK_PACKAGE = "dinkplugin.";
    private static final String LOOT_TRACKER_NAME = "Loot Tracker";

    /**
     * Re-check everything and show the result.
     * <p>
     * The only network call this makes is the ordinary board fetch, which is a read, is
     * coalesced with any refresh already in flight, and runs off the client thread. Pressing
     * this can no more change a tile than pressing Refresh can.
     */
    private void runSystemCheck() {
        if (!active) {
            return;
        }
        refreshBoard();
        publishSystemStatus();
    }

    /**
     * Gather the current state and hand it to the panel.
     * <p>
     * On the client thread because the game state and the local player can only be read there.
     * Everything gathered is an in-memory field read, so the hop is cheap; the one genuinely
     * slow part of a check is the board fetch, which is somebody else's thread entirely.
     */
    private void publishSystemStatus() {
        onClientThread(() -> panel.renderSystemCheck(buildSystemStatus()));
    }

    private SystemStatus buildSystemStatus() {
        Player local = client.getLocalPlayer();
        boolean loggedIn = client.getGameState() == GameState.LOGGED_IN && local != null;
        String token = config.eventToken();
        return SystemStatus.builder()
            .backendUrl(bingoClient.backendUrlState())
            .tokenSet(token != null && !token.trim().isEmpty())
            .loggedIn(loggedIn)
            .rsn(loggedIn ? local.getName() : null)
            .fetch(fetchState)
            .backendError(lastBackendError)
            .board(boardLoaded ? currentBoard : null)
            .detectionEnabled(detector.isDetectionEnabled())
            .dink(presenceOf(DINK_NAME, DINK_PACKAGE))
            .lootTracker(presenceOf(LOOT_TRACKER_NAME, null))
            .build();
    }

    /**
     * Whether a RuneLite plugin this one depends on is installed and switched on.
     * <p>
     * Reports {@code UNKNOWN} rather than guessing if the plugin list cannot be read. A report
     * that invented "not installed" for a plugin that is in fact running would send a player
     * off to fix something that was never broken, which is worse than admitting it does not
     * know.
     */
    private SystemStatus.Presence presenceOf(String name, @Nullable String packagePrefix) {
        try {
            Plugin found = null;
            for (Plugin plugin : pluginManager.getPlugins()) {
                if (matches(plugin, name, packagePrefix)) {
                    found = plugin;
                    break;
                }
            }
            if (found == null) {
                return SystemStatus.Presence.MISSING;
            }
            return pluginManager.isPluginEnabled(found)
                ? SystemStatus.Presence.RUNNING : SystemStatus.Presence.DISABLED;
        } catch (RuntimeException e) {
            log.debug("Could not read the RuneLite plugin list", e);
            return SystemStatus.Presence.UNKNOWN;
        }
    }

    private static boolean matches(Plugin plugin, String name, @Nullable String packagePrefix) {
        if (name.equalsIgnoreCase(plugin.getName())) {
            return true;
        }
        // A plugin's display name is its author's to change; the package it ships in is not.
        return packagePrefix != null
            && plugin.getClass().getName().startsWith(packagePrefix);
    }

    // ------------------------------------------------------------------
    // Dink test
    // ------------------------------------------------------------------

    /**
     * Posts a test notification so a player can find a broken Dink setup before the event
     * instead of on their first real drop.
     * <p>
     * Deliberately independent of the board: it never calls the backend, so it works with no
     * team, a closed event, an unreachable backend, or no backend configured at all, and it
     * cannot create a Claims or Audit row.
     * <p>
     * The chat line stops at "handed to Dink" on purpose. Nothing acknowledges the message,
     * so claiming delivery here would rebuild the false confidence this feature exists to
     * remove.
     */
    private void sendDinkTest() {
        if (!active) {
            return;
        }
        announcer.announceTest();
        onClientThread(() -> sendChatMessage(
            "Bingo: test notification handed to Dink. Dink does not confirm delivery "
                + "\u2014 check Discord to be sure it arrived."));
    }

    // ------------------------------------------------------------------
    // detection
    // ------------------------------------------------------------------

    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event) {
        detector.onLoot(event.getItems(), event.getComposition().getName());
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        NPC npc = event.getNpc();
        detector.onLoot(event.getItems(), npc.getName());
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event) {
        if (!config.includePlayerLoot()) {
            return;
        }
        detector.onLoot(event.getItems(), event.getPlayer().getName());
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        // PK loot is gated behind its own config setting and arrives via PlayerLootReceived.
        if (event.getType() == LootRecordType.PLAYER) {
            return;
        }
        // Everything else is accepted, including LootRecordType.NPC. That overlaps with
        // ServerNpcLoot/NpcLootReceived above, but the detector dedupes per item id, and
        // upstream has historically moved individual bosses (Gauntlet, Whisperer, Araxxor)
        // between these events — a missed tile is far worse than a redundant event.
        detector.onLoot(event.getItems(), event.getName());
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE) {
            return;
        }
        detector.onGameMessage(event.getMessage());
    }

    // ------------------------------------------------------------------
    // claim results
    // ------------------------------------------------------------------

    private void onClaimResolved(ClaimResponse response, String source) {
        if (response == null) {
            return;
        }
        onClientThread(() -> handleClaimResolved(response, source));
    }

    private void handleClaimResolved(ClaimResponse response, String source) {
        announcer.announce(response, source);

        if (config.chatMessageOnClaim()) {
            sendChatMessage(describe(response));
        }

        // Re-fetch so the panel and the remaining set reflect the new state, including any
        // tiles teammates claimed while we were busy.
        refreshBoard();
    }

    private String describe(ClaimResponse response) {
        String item = response.getItemName() != null ? response.getItemName() : "That item";
        String tile = response.getTileName();
        String claimLabel = tile != null && !tile.equalsIgnoreCase(item) ?
            item + " for the " + tile + " tile" : item;
        switch (response.getStatus()) {
            case BingoResponses.CLAIMED:
                return "Bingo: completed " + claimLabel + " for " + response.getTeam()
                    + " (" + response.getRemaining() + " tiles left).";
            case BingoResponses.PROGRESS:
                return "Bingo: added " + item + " to the " + (tile != null ? tile : item)
                    + " tile for " + response.getTeam() + " (" + response.getProgress()
                    + "/" + response.getRequired() + ").";
            case BingoResponses.DUPLICATE:
                return response.isComplete()
                    ? "Bingo: the " + (tile != null ? tile : item) + " tile is already complete."
                    : "Bingo: " + item + " is already credited to the "
                        + (tile != null ? tile : item) + " tile.";
            case BingoResponses.NOT_ON_TEAM:
                return "Bingo: your RSN is not on the Teams tab, so nothing was claimed.";
            case BingoResponses.EVENT_CLOSED:
                return "Bingo: the event is not currently open, so nothing was claimed.";
            case BingoResponses.NOT_ON_BOARD:
                return "Bingo: " + item + " is not on the board.";
            default:
                // Every backend failure arrives with status "error", so switching on status
                // alone told the player nothing. The reason is in the error field, and its
                // wording is shared with the sidebar so the two cannot drift apart.
                return BingoErrors.describeClaimError(response.getError());
        }
    }

    /**
     * No usable response ever arrived for a submitted claim.
     * <p>
     * The drop stays unresolved, so a later drop of the same item will try again, but the
     * player was previously told nothing at all. Deliberately not announced: the sheet never
     * recorded anything, so there is nothing to put in Discord.
     */
    private void onClaimUnresolved(String itemName) {
        onClientThread(() -> {
            if (config.chatMessageOnClaim()) {
                sendChatMessage(BingoErrors.describeUnresolvedClaim(itemName));
            }
        });
    }

    private void sendChatMessage(String message) {
        chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.CONSOLE)
            .runeLiteFormattedMessage(ColorUtil.wrapWithColorTag(message, new Color(0x00, 0xB0, 0x50)))
            .build());
    }
}
