package dinkbingo;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dinkbingo.BingoResponses.BoardItem;
import dinkbingo.BingoResponses.BoardRequest;
import dinkbingo.BingoResponses.BoardResponse;
import dinkbingo.BingoResponses.BoardTile;
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

        BoardRequest boardRequest = new BoardRequest();
        boardRequest.setAction("board");
        boardRequest.setToken(config.eventToken());
        boardRequest.setRsn(rsn);
        Request request = new Request.Builder()
            .url(base)
            .post(RequestBody.create(JSON, gson.toJson(boardRequest)))
            .build();

        return executeWithRetry(request, BoardResponse.class).thenApply(res -> {
            if (res == null || res.getTiles() == null) {
                log.debug("Board fetch returned nothing usable");
                return null;
            }
            if (res.getError() != null) {
                log.warn("Bingo backend rejected board fetch: {}", res.getError());
                return null;
            }
            List<BingoTile> tiles = new ArrayList<>(res.getTiles().size());
            for (BoardTile tile : res.getTiles()) {
                List<BingoItem> options = new ArrayList<>();
                if (tile.getOptions() != null) {
                    for (BoardItem option : tile.getOptions()) {
                        options.add(new BingoItem(option.getId(), option.getName()));
                    }
                }
                BoardItem won = tile.getClaimedItem();
                BingoItem claimedItem = won == null ? null : new BingoItem(won.getId(), won.getName());
                tiles.add(new BingoTile(tile.getId(), tile.getName(), tile.getPoints(), options,
                    tile.isClaimed(), tile.getClaimedBy(), tile.getClaimedAt(), claimedItem));
            }
            return new BingoBoard(res.getTeam(), tiles, res.isEventOpen());
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
        HttpUrl url = HttpUrl.parse(config.backendUrl().trim());
        if (url == null) {
            return null;
        }
        if (url.isHttps() || isLoopback(url.host())) {
            return url;
        }
        log.warn("Bingo backend URL must use HTTPS");
        return null;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host)
            || "::1".equals(host);
    }

    private <T> CompletableFuture<T> executeWithRetry(Request request, Class<T> type) {
        CompletableFuture<T> future = new CompletableFuture<>();
        attempt(request, type, 1, future);
        return future;
    }

    private <T> void attempt(Request request, Class<T> type, int attemptNumber, CompletableFuture<T> future) {
        executor.execute(() -> {
            if (future.isDone()) {
                return;
            }
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    ResponseBody body = response.body();
                    String raw = body != null ? body.string() : "";
                    try {
                        T parsed = gson.fromJson(raw, type);
                        if (isRetryable(parsed) && attemptNumber < MAX_ATTEMPTS) {
                            retry(request, type, attemptNumber, future, "retryable backend response");
                        } else {
                            future.complete(parsed);
                        }
                    } catch (JsonSyntaxException e) {
                        // Apps Script serves an HTML error page when the deployment is
                        // misconfigured. Never log the response body because a custom backend
                        // could reflect request credentials into it.
                        log.warn("Bingo backend returned non-JSON (check the deployment is " +
                            "'Execute as: Me' and 'Who has access: Anyone')");
                        future.complete(null);
                    }
                    return;
                }

                if (isRetryableHttp(response.code()) && attemptNumber < MAX_ATTEMPTS) {
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

    private static boolean isRetryable(Object response) {
        return response instanceof ClaimResponse && ((ClaimResponse) response).isRetryable();
    }

    private static boolean isRetryableHttp(int code) {
        return code == 408 || code == 429 || code >= 500;
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
