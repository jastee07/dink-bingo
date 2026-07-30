package dinkbingo;

import com.google.inject.Provides;
import dinkbingo.BingoResponses.ClaimResponse;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
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
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.loottracker.LootRecordType;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@PluginDescriptor(
    name = "Dink Bingo",
    description = "Claims bingo tiles on a shared board when you get the drop, and announces via Dink",
    tags = {"bingo", "dink", "loot", "clan", "event", "collection", "discord"}
)
public class BingoPlugin extends Plugin {

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
    private OverlayManager overlayManager;

    @Inject
    private BingoVerificationOverlay verificationOverlay;

    private NavigationButton navButton;
    private ScheduledFuture<?> refreshTask;

    @Provides
    BingoConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BingoConfig.class);
    }

    @Override
    protected void startUp() {
        detector.setClaimListener(this::onClaimResolved);
        overlayManager.add(verificationOverlay);

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/bingo_icon.png");
        navButton = NavigationButton.builder()
            .tooltip("Dink Bingo")
            .icon(icon)
            .priority(7)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        panel.setRefreshHandler(this::refreshBoard);
        panel.setTestHandler(this::sendAnnouncementTest);
        panel.render(BingoBoard.EMPTY, bingoClient.isConfigured());

        scheduleRefresh();
        refreshBoard();
    }

    @Override
    protected void shutDown() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
        clientToolbar.removeNavigation(navButton);
        navButton = null;
        overlayManager.remove(verificationOverlay);
        detector.reset();
    }

    // ------------------------------------------------------------------
    // board lifecycle
    // ------------------------------------------------------------------

    private void scheduleRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
        }
        long minutes = Math.max(1, config.refreshMinutes());
        refreshTask = executor.scheduleWithFixedDelay(
            this::refreshBoard, minutes, minutes, TimeUnit.MINUTES);
    }

    void refreshBoard() {
        if (!bingoClient.isConfigured()) {
            panel.render(BingoBoard.EMPTY, false);
            return;
        }
        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) {
            return;
        }

        String rsn = client.getLocalPlayer().getName();
        bingoClient.fetchBoard(rsn).thenAccept(board -> {
            detector.setBoard(board);
            panel.render(board, true);
        });
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            // The local player is not populated the instant the state flips.
            clientThread.invokeLater(this::refreshBoard);
        } else if (event.getGameState() == GameState.LOGIN_SCREEN) {
            detector.reset();
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
            detector.reset();
            refreshBoard();
        }
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

        announcer.announce(response, source);

        if (config.chatMessageOnClaim()) {
            sendChatMessage(describe(response));
        }

        // Re-fetch so the panel and the remaining set reflect the new state, including any
        // tiles teammates claimed while we were busy.
        refreshBoard();
    }

    private void sendAnnouncementTest() {
        announcer.announceTest(detector.getBoard());
        sendChatMessage(
            "Bingo: sent a test request to Dink. Check Dink's External Plugin Requests settings "
                + "and the configured Discord webhook if it does not arrive."
        );
    }

    private String describe(ClaimResponse response) {
        String item = response.getItemName() != null ? response.getItemName() : "That item";
        switch (response.getStatus()) {
            case BingoResponses.CLAIMED:
                return "Bingo: claimed " + item + " for " + response.getTeam()
                    + " (" + response.getRemaining() + " tiles left).";
            case BingoResponses.DUPLICATE:
                return "Bingo: " + item + " was already claimed by " + response.getClaimedBy() + ".";
            case BingoResponses.NOT_ON_TEAM:
                return "Bingo: your RSN is not on the Teams tab, so nothing was claimed.";
            case BingoResponses.EVENT_CLOSED:
                return "Bingo: the event is not currently open, so nothing was claimed.";
            case BingoResponses.NOT_ON_BOARD:
                return "Bingo: " + item + " is not on the board.";
            default:
                return "Bingo: claim failed (" + response.getStatus() + ").";
        }
    }

    private void sendChatMessage(String message) {
        chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.CONSOLE)
            .runeLiteFormattedMessage(ColorUtil.wrapWithColorTag(message, new Color(0x00, 0xB0, 0x50)))
            .build());
    }
}
