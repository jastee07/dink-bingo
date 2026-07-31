package dinkbingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dinkbingo.BingoResponses.ClaimRequest;
import dinkbingo.BingoResponses.ClaimResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class BingoClientTest {

    private MockWebServer server;
    private ScheduledExecutorService executor;

    @Mock
    private BingoConfig config;

    private BingoClient client;

    @BeforeEach
    void setUp() throws IOException {
        MockitoAnnotations.openMocks(this);

        server = new MockWebServer();
        server.start();
        executor = Executors.newScheduledThreadPool(2);

        when(config.backendUrl()).thenReturn(server.url("/exec").toString());
        when(config.eventToken()).thenReturn("secret-token");

        client = new BingoClient(new OkHttpClient(), new Gson(), executor, config);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
        executor.shutdownNow();
    }

    @Test
    void isNotConfiguredWithoutABackendUrl() {
        when(config.backendUrl()).thenReturn("  ");
        assertFalse(client.isConfigured());
    }

    @Test
    void isNotConfiguredWithAnUnparseableUrl() {
        when(config.backendUrl()).thenReturn("not a url");
        assertFalse(client.isConfigured());
    }

    @Test
    void rejectsPlainHttpExceptForLoopbackDevelopmentServers() {
        when(config.backendUrl()).thenReturn("http://example.com/exec");
        assertFalse(client.isConfigured());
    }

    @Test
    void fetchesAndParsesTheBoard() throws Exception {
        server.enqueue(json("{\"status\":\"ok\",\"team\":\"Team One\",\"remaining\":1,\"total\":2," +
            "\"eventOpen\":true,\"tiles\":[" +
            "{\"id\":\"rare-drop\",\"name\":\"Any rare drop\",\"points\":1,\"required\":2,\"progress\":1,\"claimed\":false," +
            "\"claimedItems\":[{\"id\":4151,\"name\":\"Abyssal whip\",\"claimedBy\":\"Jake\"}]," +
            "\"options\":[{\"id\":4151,\"name\":\"Abyssal whip\"},{\"id\":11832,\"name\":\"Bandos chestplate\"}]}," +
            "{\"id\":\"21034\",\"name\":\"Dexterous prayer scroll\",\"points\":2,\"required\":1,\"progress\":1,\"claimed\":true," +
            "\"claimedBy\":\"Someone\",\"claimedItem\":{\"id\":21034,\"name\":\"Dexterous prayer scroll\"}," +
            "\"claimedItems\":[{\"id\":21034,\"name\":\"Dexterous prayer scroll\",\"claimedBy\":\"Someone\"}]," +
            "\"options\":[{\"id\":21034,\"name\":\"Dexterous prayer scroll\"}]}]}"));

        BingoBoard board = client.fetchBoard("Jake").get(5, TimeUnit.SECONDS);

        assertEquals("Team One", board.getTeam());
        assertTrue(board.isEventOpen());
        assertEquals(2, board.getTiles().size());
        assertEquals(1, board.getRemainingCount());
        assertFalse(board.isClaimable(4151), "an already-credited option is not claimable");
        assertTrue(board.isClaimable(11832), "an uncredited option can advance a partial tile");
        assertFalse(board.isClaimable(21034), "an already-claimed tile is not claimable");
        assertEquals(2, board.getByItemId().get(4151).getRequired());
        assertEquals(1, board.getByItemId().get(4151).getProgress());
        assertEquals("Jake", board.getByItemId().get(4151).getClaimedItems().get(0).getClaimedBy());
        assertEquals("Someone", board.getByItemId().get(21034).getClaimedBy());
        assertEquals("rare-drop", board.getByItemId().get(4151).getId());

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        JsonObject body = new JsonParser().parse(request.getBody().readUtf8()).getAsJsonObject();
        assertEquals("board", body.get("action").getAsString());
        assertEquals("secret-token", body.get("token").getAsString());
        assertEquals("Jake", body.get("rsn").getAsString());
    }

    @Test
    void preservesTheCurrentBoardWhenTheBackendRejectsTheToken() throws Exception {
        server.enqueue(json("{\"status\":\"error\",\"error\":\"bad_token\",\"tiles\":[]}"));

        BingoBoard board = client.fetchBoard("Jake").get(5, TimeUnit.SECONDS);

        assertNull(board);
    }

    @Test
    void postsTheClaimWithTokenAndIdempotencyKey() throws Exception {
        server.enqueue(json("{\"status\":\"claimed\",\"team\":\"Team One\",\"tileId\":\"rare-drop\"," +
            "\"tileName\":\"Any rare drop\",\"itemId\":4151," +
            "\"itemName\":\"Abyssal whip\",\"progress\":2,\"required\":2,\"complete\":true," +
            "\"claimedItems\":[{\"id\":11832,\"name\":\"Bandos chestplate\"},{\"id\":4151,\"name\":\"Abyssal whip\"}]," +
            "\"remaining\":3,\"total\":4}"));

        ClaimResponse response = client.submitClaim(claim()).get(5, TimeUnit.SECONDS);

        assertTrue(response.isClaimed());
        assertEquals("Team One", response.getTeam());
        assertEquals("rare-drop", response.getTileId());
        assertEquals(2, response.getProgress());
        assertEquals(2, response.getRequired());
        assertTrue(response.isComplete());
        assertEquals(2, response.getClaimedItems().size());
        assertEquals(3, response.getRemaining());

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        JsonObject body = new JsonParser().parse(request.getBody().readUtf8()).getAsJsonObject();
        assertEquals("secret-token", body.get("token").getAsString(), "the client injects the configured token");
        assertEquals(4151, body.get("itemId").getAsInt());
        assertEquals("claim-abc", body.get("claimId").getAsString());
        assertEquals("Jake", body.get("rsn").getAsString());
        assertFalse(body.has("accountHash"), "account hashes must not leave the client");
    }

    @Test
    void parsesAProgressResponseAsAnAnnounceableResolvedOutcome() throws Exception {
        server.enqueue(json("{\"status\":\"progress\",\"team\":\"Team One\",\"tileId\":\"rare-drop\"," +
            "\"tileName\":\"Any rare drop\",\"itemId\":4151,\"itemName\":\"Abyssal whip\"," +
            "\"progress\":1,\"required\":2,\"complete\":false,\"remaining\":4,\"total\":4}"));

        ClaimResponse response = client.submitClaim(claim()).get(5, TimeUnit.SECONDS);

        assertTrue(response.isProgress());
        assertTrue(response.isAnnounceable());
        assertTrue(response.isResolvedOutcome());
        assertFalse(response.isComplete());
        assertEquals(1, response.getProgress());
        assertEquals(2, response.getRequired());
    }

    @Test
    void parsesADuplicateResponseWithoutTreatingItAsClaimed() throws Exception {
        server.enqueue(json("{\"status\":\"duplicate\",\"team\":\"Team One\",\"itemId\":4151," +
            "\"itemName\":\"Abyssal whip\",\"claimedBy\":\"Teammate\",\"remaining\":4}"));

        ClaimResponse response = client.submitClaim(claim()).get(5, TimeUnit.SECONDS);

        assertFalse(response.isClaimed());
        assertEquals(BingoResponses.DUPLICATE, response.getStatus());
        assertEquals("Teammate", response.getClaimedBy());
    }

    /** A retried claim reuses the caller's claimId, so the backend can answer with a replay. */
    @Test
    void retriesServerErrorsAndSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(json("{\"status\":\"claimed\",\"itemId\":4151,\"remaining\":1}"));

        ClaimResponse response = client.submitClaim(claim()).get(15, TimeUnit.SECONDS);

        assertNotNull(response);
        assertTrue(response.isClaimed());
        assertEquals(2, server.getRequestCount());

        String first = server.takeRequest().getBody().readUtf8();
        String second = server.takeRequest().getBody().readUtf8();
        assertEquals(
            new JsonParser().parse(first).getAsJsonObject().get("claimId").getAsString(),
            new JsonParser().parse(second).getAsJsonObject().get("claimId").getAsString(),
            "the retry must reuse the claimId or the backend cannot deduplicate it"
        );
    }

    @Test
    void retriesApplicationLevelRetryableErrorsWithTheSameClaimId() throws Exception {
        server.enqueue(json("{\"status\":\"error\",\"error\":\"lock_timeout\",\"retryable\":true}"));
        server.enqueue(json("{\"status\":\"claimed\",\"itemId\":4151,\"remaining\":1,\"total\":2}"));

        ClaimResponse response = client.submitClaim(claim()).get(15, TimeUnit.SECONDS);

        assertNotNull(response);
        assertTrue(response.isClaimed());
        assertEquals(2, server.getRequestCount());

        String first = server.takeRequest().getBody().readUtf8();
        String second = server.takeRequest().getBody().readUtf8();
        assertEquals(
            new JsonParser().parse(first).getAsJsonObject().get("claimId").getAsString(),
            new JsonParser().parse(second).getAsJsonObject().get("claimId").getAsString()
        );
    }

    @Test
    void doesNotRetryClientErrors() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));

        ClaimResponse response = client.submitClaim(claim()).get(5, TimeUnit.SECONDS);

        assertNull(response);
        assertEquals(1, server.getRequestCount());
    }

    /** Apps Script serves an HTML error page when the deployment access level is wrong. */
    @Test
    void survivesANonJsonResponse() throws Exception {
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "text/html")
            .setBody("<!DOCTYPE html><html><body>Sorry, unable to open the file</body></html>"));

        assertNull(client.submitClaim(claim()).get(5, TimeUnit.SECONDS));
    }

    @Test
    void returnsEmptyBoardWhenNotConfigured() throws Exception {
        when(config.backendUrl()).thenReturn("");
        assertEquals(BingoBoard.EMPTY.getTiles(), client.fetchBoard("Jake").get(5, TimeUnit.SECONDS).getTiles());
        assertEquals(0, server.getRequestCount());
    }

    // ------------------------------------------------------------------

    private static ClaimRequest claim() {
        ClaimRequest claim = new ClaimRequest();
        claim.setRsn("Jake");
        claim.setItemId(4151);
        claim.setItemName("Abyssal whip");
        claim.setQuantity(1);
        claim.setSource("Abyssal demon");
        claim.setClaimId("claim-abc");
        return claim;
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
