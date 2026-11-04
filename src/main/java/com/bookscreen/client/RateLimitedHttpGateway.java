package com.bookscreen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class RateLimitedHttpGateway implements AutoCloseable {

    public enum Provider {
        OPENLIBRARY, TMDB, GOOGLE_BOOKS
    }

    public static final class CallBudgetExceededException extends RuntimeException {
        public CallBudgetExceededException(String message) {
            super(message);
        }
    }

    private record CacheEntry(String body, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService refillScheduler;
    private final Map<Provider, Semaphore> rateBuckets = new ConcurrentHashMap<>();
    private final Map<Provider, Semaphore> concurrencySemaphores = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry> responseCache = new ConcurrentHashMap<>();

    private final int maxApiCallsPerSync;
    private final Duration cacheTtl;
    private final AtomicInteger apiCallsCount = new AtomicInteger(0);
    private final AtomicInteger cacheHitsCount = new AtomicInteger(0);
    private final AtomicInteger retriesCount = new AtomicInteger(0);

    public RateLimitedHttpGateway(int maxApiCallsPerSync) {
        this.maxApiCallsPerSync = maxApiCallsPerSync;
        this.cacheTtl = Duration.ofMinutes(15);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.refillScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "token-bucket-refill");
            t.setDaemon(true);
            return t;
        });

        initProviderLimiter(Provider.OPENLIBRARY, 5, 4);
        initProviderLimiter(Provider.TMDB, 15, 4);
        initProviderLimiter(Provider.GOOGLE_BOOKS, 5, 4);
    }

    private void initProviderLimiter(Provider provider, int requestsPerSecond, int maxConcurrency) {
        Semaphore bucket = new Semaphore(requestsPerSecond);
        rateBuckets.put(provider, bucket);
        concurrencySemaphores.put(provider, new Semaphore(maxConcurrency));

        refillScheduler.scheduleAtFixedRate(() -> {
            int deficit = requestsPerSecond - bucket.availablePermits();
            if (deficit > 0) {
                bucket.release(deficit);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    public void resetRunCounters() {
        apiCallsCount.set(0);
        cacheHitsCount.set(0);
        retriesCount.set(0);
    }

    public int getApiCallsCount() {
        return apiCallsCount.get();
    }

    public int getCacheHitsCount() {
        return cacheHitsCount.get();
    }

    public int getRetriesCount() {
        return retriesCount.get();
    }

    /**
     * Executes a GET request with caching, token-bucket limiting, call budget checking, and 429/5xx retry logic.
     */
    public JsonNode getJson(Provider provider, URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        String cacheKey = provider.name() + ":" + uri.toString();
        CacheEntry cached = responseCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            cacheHitsCount.incrementAndGet();
            return objectMapper.readTree(cached.body());
        }

        int currentCalls = apiCallsCount.incrementAndGet();
        if (currentCalls > maxApiCallsPerSync) {
            apiCallsCount.decrementAndGet();
            throw new CallBudgetExceededException(
                    "Exceeded MAX_API_CALLS_PER_SYNC (" + maxApiCallsPerSync + ") at URI: " + uri
            );
        }

        Semaphore concurrency = concurrencySemaphores.get(provider);
        Semaphore bucket = rateBuckets.get(provider);

        concurrency.acquire();
        try {
            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                bucket.acquire();

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(15))
                        .GET();
                if (headers != null) {
                    headers.forEach(reqBuilder::header);
                }

                HttpResponse<String> response;
                try {
                    response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                } catch (IOException ex) {
                    if (attempt == maxAttempts) {
                        throw ex;
                    }
                    retriesCount.incrementAndGet();
                    sleepBackoff(attempt, null);
                    continue;
                }

                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    String body = response.body();
                    responseCache.put(cacheKey, new CacheEntry(body, Instant.now().plus(cacheTtl)));
                    return objectMapper.readTree(body);
                }

                if (status == 404) {
                    return null;
                }

                if (status == 429 || status >= 500) {
                    if (attempt == maxAttempts) {
                        throw new IOException("HTTP " + status + " after " + maxAttempts + " attempts for " + uri);
                    }
                    retriesCount.incrementAndGet();
                    String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
                    sleepBackoff(attempt, retryAfter);
                    continue;
                }

                throw new IOException("Unexpected HTTP status " + status + " for " + uri);
            }
            throw new IOException("Exhausted retry attempts for " + uri);
        } finally {
            concurrency.release();
        }
    }

    private void sleepBackoff(int attempt, String retryAfterHeader) throws InterruptedException {
        long sleepMillis = (long) (Math.pow(2, attempt) * 500L)
                + ThreadLocalRandom.current().nextLong(100, 450);
        if (retryAfterHeader != null) {
            try {
                long seconds = Long.parseLong(retryAfterHeader.trim());
                sleepMillis = Math.max(sleepMillis, seconds * 1000L);
            } catch (NumberFormatException ignored) {
                // Fall back to exponential backoff with jitter
            }
        }
        Thread.sleep(Math.min(sleepMillis, 15_000L));
    }

    @Override
    public void close() {
        refillScheduler.shutdownNow();
    }
}