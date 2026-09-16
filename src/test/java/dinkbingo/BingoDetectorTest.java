package dinkbingo;

import dinkbingo.BingoResponses.ClaimRequest;
import dinkbingo.BingoResponses.ClaimResponse;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BingoDetectorTest {

    private static final int WHIP = 4151;
    private static final int SCROLL = 21034;
    private static final int NOTED_WHIP = 4152;
    private static final int OFF_BOARD = 999;

    @Mock
    private Client client;

    @Mock
    private ItemManager itemManager;

    @Mock
    private BingoConfig config;

    @Mock
    private BingoClient bingoClient;

    @Mock
    private Player localPlayer;

    private BingoDetector detector;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(client.getLocalPlayer()).thenReturn(localPlayer);
        when(localPlayer.getName()).thenReturn("Jake");
        when(config.includeCollectionLog()).thenReturn(true);
        when(bingoClient.isConfigured()).thenReturn(true);
        // Identity canonicalization by default; overridden in the noted-item test.
        when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(bingoClient.submitClaim(any())).thenReturn(new CompletableFuture<>());

        detector = new BingoDetector(client, itemManager, config, bingoClient);
        detector.setBoard(board(open(WHIP, "Abyssal whip"), open(SCROLL, "Dexterous prayer scroll")));
    }

    @Test
    void submitsClaimForItemOnBoard() {
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");

        ClaimRequest claim = captureClaim();
        assertEquals(WHIP, claim.getItemId());
        assertEquals("Abyssal whip", claim.getItemName());
        assertEquals("Jake", claim.getRsn());
        assertEquals("Abyssal demon", claim.getSource());
        assertNotNull(claim.getClaimId(), "a claimId is required for backend idempotency");
    }

    @Test
    void submitsOneClaimForAStackedDropRegardlessOfSize() {
        detector.onLoot(loot(WHIP, 20), "Abyssal demon");

        ClaimRequest claim = captureClaim();
        assertEquals(WHIP, claim.getItemId());
        verify(bingoClient, times(1)).submitClaim(any());
    }

    @Test
    void ignoresItemNotOnBoard() {
        detector.onLoot(loot(OFF_BOARD, 1), "Goblin");
        verify(bingoClient, never()).submitClaim(any());
    }

    @Test
    void ignoresTileAlreadyClaimed() {
        detector.setBoard(board(claimed(WHIP, "Abyssal whip", "Teammate")));
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        verify(bingoClient, never()).submitClaim(any());
    }

    @Test
    void ignoresLootWhenPlayerHasNoTeam() {
        detector.setBoard(new BingoBoard(null, Collections.singletonList(open(WHIP, "Abyssal whip")), true));
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        verify(bingoClient, never()).submitClaim(any());
    }

    @Test
    void ignoresLootWhenEventClosed() {
        detector.setBoard(new BingoBoard("Team One", Collections.singletonList(open(WHIP, "Abyssal whip")), false));
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        verify(bingoClient, never()).submitClaim(any());
    }

    @Test
    void ignoresEverythingUntilBackendIsConfigured() {
        when(bingoClient.isConfigured()).thenReturn(false);
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        verify(bingoClient, never()).submitClaim(any());
    }

    /**
     * RuneLite fires both ServerNpcLoot and NpcLootReceived for some NPCs. Without the
     * in-flight guard that would be two claims and two audit rows for one kill.
     */
    @Test
    void submitsOnceWhenTheSameDropArrivesTwice() {
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");

        verify(bingoClient, times(1)).submitClaim(any());
    }

    @Test
    void alternativesShareInFlightState() {
        detector.setBoard(board(group("rare-drop", "Any rare drop",
            new BingoItem(WHIP, "Abyssal whip"),
            new BingoItem(SCROLL, "Dexterous prayer scroll"))));

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.onLoot(loot(SCROLL, 1), "Chambers of Xeric");

        verify(bingoClient, times(2)).submitClaim(any());
    }

    @Test
    void resolvedAlternativeSuppressesTheRestOfTheTile() {
        detector.setBoard(board(group("rare-drop", "Any rare drop",
            new BingoItem(WHIP, "Abyssal whip"),
            new BingoItem(SCROLL, "Dexterous prayer scroll"))));
        when(bingoClient.submitClaim(any()))
            .thenReturn(CompletableFuture.completedFuture(response(BingoResponses.CLAIMED)));

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.onLoot(loot(SCROLL, 1), "Chambers of Xeric");

        verify(bingoClient, times(1)).submitClaim(any());
    }

    @Test
    void differentThresholdOptionsCanAdvanceTogether() {
        detector.setBoard(board(thresholdGroup("rare-drop", "Two rare drops", 2,
            new BingoItem(WHIP, "Abyssal whip"),
            new BingoItem(SCROLL, "Dexterous prayer scroll"))));

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.onLoot(loot(SCROLL, 1), "Chambers of Xeric");

        verify(bingoClient, times(2)).submitClaim(any());
    }

    @Test
    void acceptedProgressSuppressesOnlyTheCreditedItem() {
        detector.setBoard(board(thresholdGroup("rare-drop", "Two rare drops", 2,
            new BingoItem(WHIP, "Abyssal whip"),
            new BingoItem(SCROLL, "Dexterous prayer scroll"))));
        ClaimResponse progress = response(BingoResponses.PROGRESS);
        progress.setTileId("rare-drop");
        progress.setProgress(1);
        progress.setRequired(2);
        progress.setComplete(false);
        when(bingoClient.submitClaim(any()))
            .thenReturn(CompletableFuture.completedFuture(progress), new CompletableFuture<>());

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.onLoot(loot(SCROLL, 1), "Chambers of Xeric");

        verify(bingoClient, times(2)).submitClaim(any());
    }

    /**
     * BingoPlugin deliberately forwards ServerNpcLoot, NpcLootReceived and LootReceived without
     * trying to work out which one "owns" a given kill, because upstream keeps moving bosses
     * between them. That is only safe if three events for one drop still yield one claim.
     */
    @Test
    void submitsOnceWhenTheSameDropArrivesFromEveryLootEvent() {
        detector.onLoot(loot(WHIP, 1), "Abyssal demon"); // ServerNpcLoot
        detector.onLoot(loot(WHIP, 1), "Abyssal demon"); // NpcLootReceived
        detector.onLoot(loot(WHIP, 1), "Abyssal demon"); // LootReceived (NPC)

        verify(bingoClient, times(1)).submitClaim(any());
    }

    @Test
    void allowsRetryAfterAnUnresolvedClaim() {
        CompletableFuture<ClaimResponse> failed = new CompletableFuture<>();
        when(bingoClient.submitClaim(any())).thenReturn(failed);

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        failed.complete(null); // backend unreachable

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        verify(bingoClient, times(2)).submitClaim(any());
    }

    /**
     * A claim that never produced a usable response used to be entirely silent. The drop was
     * not recorded anywhere, so a rare one could be lost without the player noticing in time
     * to ask the organizer.
     */
    @Test
    void reportsAClaimThatNeverResolved() {
        List<String> unresolved = new ArrayList<>();
        List<ClaimResponse> announced = new ArrayList<>();
        detector.setClaimUnresolvedListener(unresolved::add);
        detector.setClaimListener((response, source) -> announced.add(response));

        CompletableFuture<ClaimResponse> failed = new CompletableFuture<>();
        when(bingoClient.submitClaim(any())).thenReturn(failed);

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        failed.complete(null); // retries exhausted, no usable response

        assertEquals(Collections.singletonList("Abyssal whip"), unresolved,
            "the player is told which drop was not claimed");
        assertTrue(announced.isEmpty(),
            "nothing reached the sheet, so there is nothing to announce");
    }

    @Test
    void reportsAClaimThatFailedWithAnException() {
        List<String> unresolved = new ArrayList<>();
        detector.setClaimUnresolvedListener(unresolved::add);

        CompletableFuture<ClaimResponse> failed = new CompletableFuture<>();
        when(bingoClient.submitClaim(any())).thenReturn(failed);

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        failed.completeExceptionally(new IllegalStateException("boom"));

        assertEquals(Collections.singletonList("Abyssal whip"), unresolved);
    }

    /**
     * A resolved outcome, including a rejection, already reaches the player through the claim
     * listener. Reporting it as unresolved as well would double up the chat line.
     */
    @Test
    void doesNotReportAResolvedRejectionAsUnresolved() {
        List<String> unresolved = new ArrayList<>();
        detector.setClaimUnresolvedListener(unresolved::add);
        when(bingoClient.submitClaim(any())).thenReturn(
            CompletableFuture.completedFuture(response(BingoResponses.NOT_ON_TEAM)));

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");

        assertTrue(unresolved.isEmpty());
    }

    /**
     * After the backend explicitly refuses a request, the board on screen is no longer one it
     * will honour. Submitting against it only produces rejections the player cannot act on.
     */
    @Test
    void suspendedDetectionSubmitsNothing() {
        detector.setDetectionEnabled(false);

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");

        verify(bingoClient, never()).submitClaim(any());
    }

    /** A board that loaded is proof the backend is answering this client again. */
    @Test
    void aFreshBoardResumesDetection() {
        detector.setDetectionEnabled(false);
        detector.setBoard(board(open(WHIP, "Abyssal whip")));

        assertTrue(detector.isDetectionEnabled());
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        verify(bingoClient, times(1)).submitClaim(any());
    }

    @Test
    void resetResumesDetection() {
        detector.setDetectionEnabled(false);
        detector.reset();

        assertTrue(detector.isDetectionEnabled());
    }

    @Test
    void doesNotResubmitAfterBackendResolvesTheTile() {
        when(bingoClient.submitClaim(any()))
            .thenReturn(CompletableFuture.completedFuture(response(BingoResponses.DUPLICATE)));

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");

        verify(bingoClient, times(1)).submitClaim(any());
    }

    @Test
    void allowsAnotherDropAfterARetryableBackendErrorExhaustsRetries() {
        ClaimResponse retryable = response(BingoResponses.ERROR);
        retryable.setRetryable(true);
        when(bingoClient.submitClaim(any()))
            .thenReturn(CompletableFuture.completedFuture(retryable), new CompletableFuture<>());

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");

        verify(bingoClient, times(2)).submitClaim(any());
    }

    @Test
    void authoritativeBoardRefreshReopensAnAdministrativelyUnclaimedTile() {
        when(bingoClient.submitClaim(any()))
            .thenReturn(CompletableFuture.completedFuture(response(BingoResponses.CLAIMED)),
                new CompletableFuture<>());

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.setBoard(board(open(WHIP, "Abyssal whip")));
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");

        verify(bingoClient, times(2)).submitClaim(any());
    }

    @Test
    void canonicalizesNotedItemsBeforeMatching() {
        when(itemManager.canonicalize(NOTED_WHIP)).thenReturn(WHIP);

        detector.onLoot(loot(NOTED_WHIP, 1), "Abyssal demon");

        assertEquals(WHIP, captureClaim().getItemId());
    }

    @Test
    void claimsFromCollectionLogByName() {
        detector.onGameMessage("New item added to your collection log: Dexterous prayer scroll");

        ClaimRequest claim = captureClaim();
        assertEquals(SCROLL, claim.getItemId());
        assertEquals("Collection log", claim.getSource());
    }

    @Test
    void claimsAGroupedAlternativeFromCollectionLogByName() {
        detector.setBoard(board(group("rare-drop", "Any rare drop",
            new BingoItem(WHIP, "Abyssal whip"),
            new BingoItem(SCROLL, "Dexterous prayer scroll"))));

        detector.onGameMessage("New item added to your collection log: Dexterous prayer scroll");

        ClaimRequest claim = captureClaim();
        assertEquals(SCROLL, claim.getItemId());
        assertEquals("Dexterous prayer scroll", claim.getItemName());
    }

    @Test
    void ignoresCollectionLogWhenDisabled() {
        when(config.includeCollectionLog()).thenReturn(false);
        detector.onGameMessage("New item added to your collection log: Dexterous prayer scroll");
        verify(bingoClient, never()).submitClaim(any());
    }

    @Test
    void ignoresUnrelatedGameMessages() {
        detector.onGameMessage("You feel something weird sneaking into your backpack.");
        verify(bingoClient, never()).submitClaim(any());
    }

    @Test
    void notifiesListenerWithTheBackendResponse() {
        ClaimResponse expected = response(BingoResponses.CLAIMED);
        when(bingoClient.submitClaim(any())).thenReturn(CompletableFuture.completedFuture(expected));

        List<ClaimResponse> seen = new ArrayList<>();
        detector.setClaimListener((res, source) -> seen.add(res));

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");

        assertEquals(1, seen.size());
        assertTrue(seen.get(0).isClaimed());
    }

    /**
     * The source must travel with the claim rather than be read from shared state: a second
     * drop can land while the first claim is still waiting on the backend, and the
     * announcement would otherwise name the wrong monster.
     */
    @Test
    void reportsTheSourceOfEachClaimEvenWhenAnotherDropInterleaves() {
        CompletableFuture<ClaimResponse> whipCall = new CompletableFuture<>();
        CompletableFuture<ClaimResponse> scrollCall = new CompletableFuture<>();
        when(bingoClient.submitClaim(any())).thenReturn(whipCall, scrollCall);

        List<String> sources = new ArrayList<>();
        detector.setClaimListener((res, source) -> sources.add(source));

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.onLoot(loot(SCROLL, 1), "Chambers of Xeric");

        // The scroll's backend call resolves first, then the whip's.
        scrollCall.complete(response(BingoResponses.CLAIMED));
        whipCall.complete(response(BingoResponses.CLAIMED));

        assertEquals(Arrays.asList("Chambers of Xeric", "Abyssal demon"), sources);
    }

    @Test
    void resetClearsBoardAndDedupeState() {
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.reset();

        assertEquals(BingoBoard.EMPTY, detector.getBoard());
        assertTrue(detector.getBoard().getRemaining().isEmpty());
    }

    @Test
    void completionFromBeforeResetDoesNotNotifyTheNewSessionListener() {
        CompletableFuture<ClaimResponse> oldClaim = new CompletableFuture<>();
        when(bingoClient.submitClaim(any())).thenReturn(oldClaim);
        List<ClaimResponse> seen = new ArrayList<>();
        detector.setClaimListener((response, source) -> seen.add(response));

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.reset();
        detector.setBoard(board(open(WHIP, "Abyssal whip")));
        oldClaim.complete(response(BingoResponses.CLAIMED));

        assertTrue(seen.isEmpty());
    }

    @Test
    void aClaimThatCannotBeSubmittedDoesNotPinTheItemInFlight() {
        // The executor rejects new work once RuneLite is shutting down; the rejection must not
        // leave the item marked in flight, because nothing would ever clear it.
        when(bingoClient.submitClaim(any()))
            .thenThrow(new RejectedExecutionException("executor shutting down"))
            .thenReturn(CompletableFuture.completedFuture(response(BingoResponses.CLAIMED)));

        detector.onLoot(loot(WHIP, 1), "Abyssal demon");
        detector.onLoot(loot(WHIP, 1), "Abyssal demon");

        verify(bingoClient, times(2)).submitClaim(any());
    }

    // ------------------------------------------------------------------

    private ClaimRequest captureClaim() {
        ArgumentCaptor<ClaimRequest> captor = ArgumentCaptor.forClass(ClaimRequest.class);
        verify(bingoClient).submitClaim(captor.capture());
        return captor.getValue();
    }

    private static List<ItemStack> loot(int id, int quantity) {
        return Collections.singletonList(new ItemStack(id, quantity));
    }

    private static BingoBoard board(BingoTile... tiles) {
        return new BingoBoard("Team One", Arrays.asList(tiles), true);
    }

    private static BingoTile open(int id, String name) {
        return group(String.valueOf(id), name, new BingoItem(id, name));
    }

    private static BingoTile claimed(int id, String name, String by) {
        BingoItem item = new BingoItem(id, name);
        BingoContribution contribution =
            new BingoContribution(id, name, by, "2026-07-30T00:00:00Z");
        return new BingoTile(String.valueOf(id), name, 1, 1, 1,
            Collections.singletonList(item), Collections.singletonList(contribution),
            true, by, "2026-07-30T00:00:00Z", item);
    }

    private static BingoTile group(String id, String name, BingoItem... options) {
        return thresholdGroup(id, name, 1, options);
    }

    private static BingoTile thresholdGroup(String id, String name, int required, BingoItem... options) {
        return new BingoTile(id, name, 1, required, 0, Arrays.asList(options),
            Collections.emptyList(), false, null, null, null);
    }

    private static ClaimResponse response(String status) {
        ClaimResponse response = new ClaimResponse();
        response.setStatus(status);
        response.setTeam("Team One");
        response.setTileId(String.valueOf(WHIP));
        response.setTileName("Abyssal whip");
        response.setItemId(WHIP);
        response.setItemName("Abyssal whip");
        response.setProgress(BingoResponses.CLAIMED.equals(status) ? 1 : 0);
        response.setRequired(1);
        response.setComplete(BingoResponses.CLAIMED.equals(status)
            || BingoResponses.DUPLICATE.equals(status));
        response.setRemaining(1);
        response.setTotal(2);
        return response;
    }
}
