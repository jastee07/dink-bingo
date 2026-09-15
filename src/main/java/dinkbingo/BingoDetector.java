package dinkbingo;

import dinkbingo.BingoResponses.ClaimRequest;
import dinkbingo.BingoResponses.ClaimResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the board snapshot and decides which drops are worth submitting.
 * <p>
 * Deliberately free of RuneLite event subscriptions so it can be unit tested directly;
 * {@link BingoPlugin} owns the {@code @Subscribe} methods and forwards to here.
 */
@Slf4j
@Singleton
public class BingoDetector {

    private static final Pattern COLLECTION_LOG_PATTERN =
        Pattern.compile("New item added to your collection log: (?<itemName>.*)");

    private final Client client;
    private final ItemManager itemManager;
    private final BingoConfig config;
    private final BingoClient bingoClient;

    @Inject
    public BingoDetector(Client client, ItemManager itemManager, BingoConfig config, BingoClient bingoClient) {
        this.client = client;
        this.itemManager = itemManager;
        this.config = config;
        this.bingoClient = bingoClient;
    }

    /** The latest board snapshot; replaced wholesale, never mutated. */
    @Getter
    private volatile BingoBoard board = BingoBoard.EMPTY;

    /**
     * Item ids with a contribution POST in flight. Duplicate events for one item must not both
     * submit, while two different accepted items from one loot event must both be allowed.
     * {@code ServerNpcLoot} and {@code NpcLootReceived} for some NPCs) must not both submit.
     */
    private final Set<Integer> inFlight = new CopyOnWriteArraySet<>();

    /**
     * Item ids the backend has already credited for us this session.
     */
    private final Set<Integer> resolvedItems = new CopyOnWriteArraySet<>();

    /** Logical tiles completed before the refreshed board snapshot arrives. */
    private final Set<String> resolvedTiles = new CopyOnWriteArraySet<>();
    private final AtomicLong generation = new AtomicLong();

    /**
     * Invoked with the response and the loot source for every submitted claim, off the client
     * thread and on whichever thread completed the request. The source is carried through
     * rather than read from shared state, because another drop can land during the backend
     * round trip.
     */
    private volatile BiConsumer<ClaimResponse, String> claimListener = (res, source) -> {
    };

    public void setClaimListener(BiConsumer<ClaimResponse, String> listener) {
        this.claimListener = listener;
    }

    /**
     * Invoked with the item name when a submitted claim never produced a usable response, so
     * the player learns the drop was not recorded. The claim stays unresolved, so a later drop
     * of the same item is submitted again.
     */
    private volatile Consumer<String> claimUnresolvedListener = itemName -> {
    };

    public void setClaimUnresolvedListener(Consumer<String> listener) {
        this.claimUnresolvedListener = listener;
    }

    /**
     * When false, no drop is submitted even though the last known board is still held.
     * <p>
     * Set after the backend explicitly refuses a request. The board stays on screen as
     * reference, but the backend has already said it will not honour this client, so
     * submitting against that snapshot only produces rejections the player cannot act on.
     * A transport failure is different and deliberately leaves detection alone: the board is
     * probably still correct and the next drop may well get through.
     */
    private volatile boolean detectionEnabled = true;

    public void setDetectionEnabled(boolean enabled) {
        this.detectionEnabled = enabled;
    }

    public boolean isDetectionEnabled() {
        return detectionEnabled;
    }

    public void setBoard(BingoBoard board) {
        // A board that loaded is proof the backend is answering this client again, so any
        // suspension from an earlier refusal is lifted here rather than at a separate call
        // site that could be forgotten.
        this.detectionEnabled = true;
        this.board = board;
        // The sheet is authoritative. If an organizer unclaimed a tile, allow this client
        // to submit it again instead of retaining the session-local resolved marker.
        this.resolvedItems.removeAll(board.getOpenItemIds());
        this.resolvedTiles.removeAll(board.getRemaining());
    }

    public void reset() {
        this.detectionEnabled = true;
        this.generation.incrementAndGet();
        this.board = BingoBoard.EMPTY;
        this.inFlight.clear();
        this.resolvedItems.clear();
        this.resolvedTiles.clear();
    }

    // ------------------------------------------------------------------
    // detection entry points
    // ------------------------------------------------------------------

    public void onLoot(Collection<ItemStack> items, String source) {
        if (items == null || !bingoClient.isConfigured()) {
            return;
        }
        for (ItemStack item : items) {
            // Un-notes, un-placeholders and un-wears so board ids match what actually dropped.
            int canonical = itemManager.canonicalize(item.getId());
            // Stack size is deliberately ignored: tiles count distinct item ids, never quantity.
            submitIfClaimable(canonical, source);
        }
    }

    public void onGameMessage(String message) {
        if (!config.includeCollectionLog() || !bingoClient.isConfigured()) {
            return;
        }
        Matcher matcher = COLLECTION_LOG_PATTERN.matcher(message);
        if (!matcher.find()) {
            return;
        }

        // The board already carries item names, so match on those rather than maintaining a
        // separate name -> id index just for this path.
        String itemName = matcher.group("itemName").trim();
        for (BingoTile tile : board.getTiles()) {
            for (BingoItem option : tile.getOptions()) {
                if (option.getName().equalsIgnoreCase(itemName)) {
                    submitIfClaimable(option.getId(), "Collection log");
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // claim submission
    // ------------------------------------------------------------------

    boolean shouldSubmit(int itemId) {
        BingoTile tile = board.getByItemId().get(itemId);
        return detectionEnabled
            && board.isClaimable(itemId)
            && tile != null
            && !inFlight.contains(itemId)
            && !resolvedItems.contains(itemId)
            && !resolvedTiles.contains(tile.getId());
    }

    private void submitIfClaimable(int itemId, String source) {
        if (!shouldSubmit(itemId)) {
            return;
        }
        BingoTile tile = board.getByItemId().get(itemId);
        if (tile == null || !inFlight.add(itemId)) {
            return; // lost the race against another event for this exact item
        }

        BingoItem option = board.findOption(itemId);
        ClaimRequest claim = new ClaimRequest();
        claim.setRsn(getPlayerName());
        claim.setItemId(itemId);
        claim.setItemName(option != null ? option.getName() : String.valueOf(itemId));
        claim.setSource(source != null ? source : "");
        claim.setClaimId(UUID.randomUUID().toString());
        long claimGeneration = generation.get();

        log.debug("Submitting bingo claim for {} ({}) from {}", claim.getItemName(), itemId, source);

        CompletableFuture<ClaimResponse> pending;
        try {
            pending = bingoClient.submitClaim(claim);
        } catch (RuntimeException e) {
            // The client is expected to surface every failure as a completed future, but do not
            // depend on that for the in-flight marker: a throw here would otherwise pin the item
            // for the rest of the session and block every later drop of it.
            inFlight.remove(itemId);
            log.warn("Bingo claim for {} was not submitted", itemId, e);
            return;
        }

        pending.whenComplete((response, error) -> {
            try {
                if (claimGeneration != generation.get()) {
                    return; // plugin reset, logout, or configuration change while request was in flight
                }
                if (error != null || response == null) {
                    // Unresolved: allow a later drop of the same item to try again. Tell the
                    // player, because the drop was not recorded and a rare one may need the
                    // organizer. Never announced -- nothing reached the sheet.
                    log.debug("Bingo claim for {} did not resolve", itemId, error);
                    claimUnresolvedListener.accept(claim.getItemName());
                    return;
                }
                if (response.isResolvedOutcome()) {
                    if (response.isComplete()) {
                        resolvedTiles.add(tile.getId());
                    } else {
                        resolvedItems.add(itemId);
                    }
                }
                claimListener.accept(response, claim.getSource());
            } finally {
                inFlight.remove(itemId);
            }
        });
    }

    private String getPlayerName() {
        return client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
    }
}
