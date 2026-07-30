package dinkbingo;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dinkbingo.BingoResponses.BoardItem;
import dinkbingo.BingoResponses.BoardResponse;
import dinkbingo.BingoResponses.ClaimRequest;
import dinkbingo.BingoResponses.ClaimResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the Apps Script backend.
 * <p>
 * Every call runs on the injected executor and never on the client thread. Retries reuse
 * the caller's {@code claimId}, which the backend treats as an idempotency key, so a retry
 * after a timeout can never produce a second claim.
 */
@Slf4j
@Singleton
public class BingoClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_ATTEMPTS = 4;
    private static final long BASE_BACKOFF_MS = 1500L;

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ScheduledExecutorService executor;
    private final BingoConfig config;

    @Inject
    public BingoClient(OkHttpClient httpClient, Gson gson, ScheduledExecutorService executor, BingoConfig config) {
        this.httpClient = httpClient;
        this.gson = gson;
        this.executor = executor;
        this.config = config;
    }

    /** Whether the user has pointed us at a backend. Until then we make no requests at all. */
    public boolean isConfigured() {
        return !config.backendUrl().trim().isEmpty() && parseUrl() != null;
    }

    public CompletableFuture<BingoBoard> fetchBoard(String rsn) {
        HttpUrl base = parseUrl();
        if (base == null || rsn == null) {
            return CompletableFuture.completedFuture(BingoBoard.EMPTY);
        }

        HttpUrl url = base.newBuilder()
            .addQueryParameter("action", "board")
            .addQueryParameter("token", config.eventToken())
            .addQueryParameter("rsn", rsn)
            .build();

        Request request = new Request.Builder().url(url).get().build();

        return executeWithRetry(request, BoardResponse.class).thenApply(res -> {
            if (res == null || res.getItems() == null) {
                log.debug("Board fetch returned nothing usable");
                return BingoBoard.EMPTY;
            }
            if (res.getError() != null) {
                log.warn("Bingo backend rejected board fetch: {}", res.getError());
                return BingoBoard.EMPTY;
            }
            List<BingoItem> items = new ArrayList<>(res.getItems().size());
            for (BoardItem item : res.getItems()) {
                items.add(new BingoItem(item.getId(), item.getName(), item.getPoints(),
                    item.isClaimed(), item.getClaimedBy(), item.getClaimedAt()));
            }
            return new BingoBoard(res.getTeam(), items, res.isEventOpen());
        });
    }

    public CompletableFuture<ClaimResponse> submitClaim(ClaimRequest claim) {
        HttpUrl url = parseUrl();
        if (url == null) {
            return CompletableFuture.completedFuture(null);
        }

        claim.setToken(config.eventToken());
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON, gson.toJson(claim)))
            .build();

        return executeWithRetry(request, ClaimResponse.class);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private HttpUrl parseUrl() {
        return HttpUrl.parse(config.backendUrl().trim());
    }

    private <T> CompletableFuture<T> executeWithRetry(Request request, Class<T> type) {
        CompletableFuture<T> future = new CompletableFuture<>();
        attempt(request, type, 1, future);
        return future;
    }

    private <T> void attempt(Request request, Class<T> type, int attemptNumber, CompletableFuture<T> future) {
        executor.execute(() -> {
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    ResponseBody body = response.body();
                    String raw = body != null ? body.string() : "";
                    try {
                        future.complete(gson.fromJson(raw, type));
                    } catch (JsonSyntaxException e) {
                        // Apps Script serves an HTML error page when the deployment is
                        // misconfigured; surfacing that is far more useful than a parse trace.
                        log.warn("Bingo backend returned non-JSON (check the deployment is " +
                            "'Execute as: Me' and 'Who has access: Anyone'): {}",
                            raw.length() > 200 ? raw.substring(0, 200) + "…" : raw);
                        future.complete(null);
                    }
                    return;
                }

                if (response.code() >= 500 && attemptNumber < MAX_ATTEMPTS) {
                    retry(request, type, attemptNumber, future, "HTTP " + response.code());
                } else {
                    log.warn("Bingo backend returned HTTP {}", response.code());
                    future.complete(null);
                }
            } catch (IOException e) {
                if (attemptNumber < MAX_ATTEMPTS) {
                    retry(request, type, attemptNumber, future, e.toString());
                } else {
                    log.warn("Bingo backend unreachable after {} attempts", MAX_ATTEMPTS, e);
                    future.complete(null);
                }
            } catch (Exception e) {
                log.warn("Unexpected failure talking to the bingo backend", e);
                future.complete(null);
            }
        });
    }

    private <T> void retry(Request request, Class<T> type, int attemptNumber, CompletableFuture<T> future, String cause) {
        long delay = BASE_BACKOFF_MS * (1L << (attemptNumber - 1));
        log.debug("Retrying bingo request in {}ms (attempt {} failed: {})", delay, attemptNumber, cause);
        executor.schedule(
            () -> attempt(request, type, attemptNumber + 1, future),
            delay,
            TimeUnit.MILLISECONDS
        );
    }
}
