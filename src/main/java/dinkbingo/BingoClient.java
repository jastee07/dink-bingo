package dinkbingo;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dinkbingo.BingoResponses.BoardItem;
import dinkbingo.BingoResponses.BoardClaimedItem;
import dinkbingo.BingoResponses.BoardRequest;
import dinkbingo.BingoResponses.BoardResponse;
import dinkbingo.BingoResponses.BoardTile;
import dinkbingo.BingoResponses.ClaimRequest;
import dinkbingo.BingoResponses.ClaimResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the Apps Script backend.
 * <p>
 * Every call is dispatched from the injected executor and never from the client thread, and
 * the request itself is enqueued on OkHttp's dispatcher so no bingo request ever blocks a
 * RuneLite thread while it waits. Retries reuse the caller's {@code claimId}, which the
 * backend treats as an idempotency key, so a retry after a timeout can never produce a
 * second claim.
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

    /**
     * Last parse of {@link BingoConfig#backendUrl()}, keyed by the raw value it was parsed
     * from so a changed setting invalidates it without anyone having to say so.
     */
    private volatile ParsedUrl parsed = ParsedUrl.UNPARSED;

    @Inject
    public BingoClient(OkHttpClient httpClient, Gson gson, ScheduledExecutorService executor, BingoConfig config) {
        this.httpClient = httpClient;
        this.gson = gson;
        this.executor = executor;
        this.config = config;
    }

    /** Whether the user has pointed us at a backend. Until then we make no requests at all. */
    public boolean isConfigured() {
        return parseUrl() != null;
    }

    /**
     * What the configured backend URL amounts to, for the readiness report.
     * <p>
     * Everything else here only needs to know whether a request can be made, but "unset",
     * "not a URL", and "not HTTPS" are three different mistakes with three different fixes,
     * and the parse is the only place that can tell them apart.
     */
    public BackendUrlState backendUrlState() {
        return resolve().state;
    }

    public CompletableFuture<BoardResult> fetchBoard(String rsn) {
        HttpUrl base = parseUrl();
        if (base == null || rsn == null) {
            return CompletableFuture.completedFuture(BoardResult.of(BingoBoard.EMPTY));
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
            if (res == null) {
                log.debug("Board fetch returned nothing usable");
                return BoardResult.unreachable();
            }
            // Checked before the tile list because a rejection carries no tiles, and its
            // reason is the whole point: it names the setup mistake behind the failure.
            if (res.getError() != null) {
                log.warn("Bingo backend rejected board fetch: {}", res.getError());
                return BoardResult.rejected(res.getError());
            }
            if (res.getTiles() == null) {
                log.debug("Board fetch returned no tiles and no error");
                return BoardResult.unreachable();
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
                List<BingoContribution> claimedItems = new ArrayList<>();
                if (tile.getClaimedItems() != null) {
                    for (BoardClaimedItem credited : tile.getClaimedItems()) {
                        claimedItems.add(new BingoContribution(
                            credited.getId(),
                            credited.getName(),
                            credited.getClaimedBy(),
                            credited.getClaimedAt()
                        ));
                    }
                }
                tiles.add(new BingoTile(
                    tile.getId(), tile.getName(), tile.getPoints(), tile.getRequired(),
                    tile.getProgress(), options, claimedItems, tile.isClaimed(),
                    tile.getClaimedBy(), tile.getClaimedAt(), claimedItem
                ));
            }
            return BoardResult.of(new BingoBoard(res.getTeam(), tiles, res.isEventOpen()));
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

    /**
     * The configured backend URL, or {@code null} when it is unset or unusable.
     * <p>
     * {@code BingoDetector} asks whether we are configured for every game message, on the
     * client thread, so the parse is cached against the raw setting it came from: an unchanged
     * setting costs a string compare, and a rejected one is logged once rather than once per
     * chat line.
     */
    private HttpUrl parseUrl() {
        return resolve().url;
    }

    private ParsedUrl resolve() {
        String raw = config.backendUrl();
        raw = raw == null ? "" : raw.trim();
        ParsedUrl cached = parsed;
        if (raw.equals(cached.raw)) {
            return cached;
        }
        ParsedUrl fresh = validate(raw);
        parsed = fresh;
        return fresh;
    }

    private static ParsedUrl validate(String raw) {
        if (raw.isEmpty()) {
            return new ParsedUrl(raw, null, BackendUrlState.UNSET);
        }
        HttpUrl url = HttpUrl.parse(raw);
        if (url == null) {
            return new ParsedUrl(raw, null, BackendUrlState.INVALID);
        }
        if (url.isHttps() || isLoopback(url.host())) {
            return new ParsedUrl(raw, url, BackendUrlState.OK);
        }
        log.warn("Bingo backend URL must use HTTPS");
        return new ParsedUrl(raw, null, BackendUrlState.INSECURE);
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host)
            || "::1".equals(host);
    }

    private <T> CompletableFuture<T> executeWithRetry(Request request, Class<T> type) {
        Exchange<T> exchange = new Exchange<>(request, type);
        send(exchange, 1);
        return exchange.future;
    }

    /**
     * One request and the future that answers it, so a retry does not have to carry the
     * request, the response type, and the future through every step by hand. Only the attempt
     * number changes between attempts, and it is passed separately for exactly that reason.
     */
    private static final class Exchange<T> {

        final Request request;
        final Class<T> type;
        final CompletableFuture<T> future = new CompletableFuture<>();

        Exchange(Request request, Class<T> type) {
            this.request = request;
            this.type = type;
        }

        /** Give up on this exchange. Callers must always reach here or complete the future. */
        void giveUp() {
            future.complete(null);
        }
    }

    /**
     * Enqueues one attempt. The executor hop keeps dispatch off the client thread and keeps a
     * shutting-down executor from firing new requests, but it only enqueues: the round trip
     * itself waits on OkHttp's dispatcher pool, so RuneLite's single shared scheduled thread is
     * never held for the length of a request.
     */
    private <T> void send(Exchange<T> exchange, int attemptNumber) {
        submit(exchange, () -> executor.execute(() -> {
            if (exchange.future.isDone()) {
                return;
            }
            try {
                httpClient.newCall(exchange.request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        failed(exchange, attemptNumber, e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        handle(exchange, attemptNumber, response);
                    }
                });
            } catch (Exception e) {
                log.warn("Unexpected failure talking to the bingo backend", e);
                exchange.giveUp();
            }
        }));
    }

    /**
     * Runs on OkHttp's dispatcher. Nothing here may throw: OkHttp only logs an exception raised
     * by a callback, so an escaping failure would leave the future uncompleted and pin
     * {@code BingoDetector}'s in-flight marker for the item.
     */
    private <T> void handle(Exchange<T> exchange, int attemptNumber, Response response) {
        try (Response closing = response) {
            if (closing.isSuccessful()) {
                complete(exchange, attemptNumber, closing.body());
                return;
            }

            if (isRetryableHttp(closing.code()) && attemptNumber < MAX_ATTEMPTS) {
                retry(exchange, attemptNumber, "HTTP " + closing.code());
            } else {
                log.warn("Bingo backend returned HTTP {}", closing.code());
                exchange.giveUp();
            }
        } catch (IOException e) {
            // The response arrived but reading it failed part way through, which is the same
            // kind of transport failure as never reaching the backend at all.
            failed(exchange, attemptNumber, e);
        } catch (Exception e) {
            log.warn("Unexpected failure talking to the bingo backend", e);
            exchange.giveUp();
        }
    }

    private <T> void complete(Exchange<T> exchange, int attemptNumber, ResponseBody body)
        throws IOException {
        String raw = body != null ? body.string() : "";
        try {
            T parsed = gson.fromJson(raw, exchange.type);
            if (isRetryable(parsed) && attemptNumber < MAX_ATTEMPTS) {
                retry(exchange, attemptNumber, "retryable backend response");
            } else {
                exchange.future.complete(parsed);
            }
        } catch (JsonSyntaxException e) {
            // Apps Script serves an HTML error page when the deployment is misconfigured.
            // Never log the response body because a custom backend could reflect request
            // credentials into it.
            log.warn("Bingo backend returned non-JSON (check the deployment is " +
                "'Execute as: Me' and 'Who has access: Anyone')");
            exchange.giveUp();
        }
    }

    private <T> void failed(Exchange<T> exchange, int attemptNumber, IOException e) {
        if (attemptNumber < MAX_ATTEMPTS) {
            retry(exchange, attemptNumber, e.toString());
        } else {
            log.warn("Bingo backend unreachable after {} attempts", MAX_ATTEMPTS, e);
            exchange.giveUp();
        }
    }

    private static boolean isRetryable(Object response) {
        return response instanceof ClaimResponse && ((ClaimResponse) response).isRetryable();
    }

    private static boolean isRetryableHttp(int code) {
        return code == 408 || code == 429 || code >= 500;
    }

    private <T> void retry(Exchange<T> exchange, int attemptNumber, String cause) {
        long delay = BASE_BACKOFF_MS * (1L << (attemptNumber - 1));
        log.debug("Retrying bingo request in {}ms (attempt {} failed: {})", delay, attemptNumber, cause);
        submit(exchange, () -> executor.schedule(
            () -> send(exchange, attemptNumber + 1),
            delay,
            TimeUnit.MILLISECONDS
        ));
    }

    /**
     * Hands work to the executor, completing the future with {@code null} if the executor is
     * shutting down. Without this the rejection escapes {@code submitClaim} synchronously on
     * the first attempt and is swallowed entirely on the retry path, and either way the future
     * never completes, so {@code BingoDetector} never clears its in-flight marker for the item.
     */
    private <T> void submit(Exchange<T> exchange, Runnable scheduling) {
        try {
            scheduling.run();
        } catch (RejectedExecutionException e) {
            log.debug("Bingo request dropped because the executor is shutting down");
            exchange.giveUp();
        }
    }

    /** An immutable parse result paired with the raw setting that produced it. */
    private static final class ParsedUrl {

        /** Matches no configured value, so the first lookup always parses. */
        static final ParsedUrl UNPARSED = new ParsedUrl(null, null, BackendUrlState.UNSET);

        final String raw;
        final HttpUrl url;
        final BackendUrlState state;

        ParsedUrl(String raw, HttpUrl url, BackendUrlState state) {
            this.raw = raw;
            this.url = url;
            this.state = state;
        }
    }
}
