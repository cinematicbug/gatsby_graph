package com.bookscreen.client;

import com.bookscreen.config.AppConfig;
import com.bookscreen.model.FilmCandidate;
import com.bookscreen.model.FilmPayload;
import com.bookscreen.normalize.TitleNormalizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class TmdbApiClient {

    private static final String BASE_URL = "https://api.themoviedb.org/3";

    private final RateLimitedHttpGateway gateway;
    private final AppConfig config;
    private final Map<String, String> headers;

    public TmdbApiClient(RateLimitedHttpGateway gateway, AppConfig config) {
        this.gateway = gateway;
        this.config = config;
        this.headers = Map.of(
                "Authorization", "Bearer " + config.tmdbBearerToken(),
                "Accept", "application/json"
        );
    }

    private record SearchStub(long tmdbId, String title, int releaseYear, double titleSim) {}

    /**
     * Phase B1 + B2: Searches TMDB by normalized book title, applies chronological gate,
     * caps at CANDIDATE_CAP_PER_BOOK ranked by title similarity, and enriches details with credits & keywords.
     */
    public List<FilmCandidate> discoverAndEnrichCandidates(String bookTitle, Integer effectiveBookYear)
            throws IOException, InterruptedException {
        if (effectiveBookYear == null) {
            return List.of();
        }

        String queryTitle = TitleNormalizer.normalizeTitle(bookTitle);
        if (queryTitle.isEmpty()) {
            queryTitle = bookTitle;
        }

        String encoded = URLEncoder.encode(queryTitle, StandardCharsets.UTF_8);
        URI searchUri = URI.create(BASE_URL + "/search/movie?query=" + encoded + "&include_adult=false&page=1");
        JsonNode root = gateway.getJson(RateLimitedHttpGateway.Provider.TMDB, searchUri, headers);
        if (root == null || !root.has("results")) {
            return List.of();
        }

        List<SearchStub> stubs = new ArrayList<>();
        for (JsonNode item : root.get("results")) {
            if (item.path("adult").asBoolean(false)) {
                continue;
            }
            Integer releaseYear = parseReleaseYear(item.path("release_date").asText(""));
            if (releaseYear == null || releaseYear < effectiveBookYear) {
                continue; // Hard gate Y_film >= Y_book
            }
            long tmdbId = item.path("id").asLong(0);
            String title = item.path("title").asText("").trim();
            if (tmdbId <= 0 || title.isEmpty()) {
                continue;
            }
            double sim = TitleNormalizer.computeTitleSimilarity(bookTitle, title);
            stubs.add(new SearchStub(tmdbId, title, releaseYear, sim));
        }

        stubs.sort(Comparator.comparingDouble(SearchStub::titleSim).reversed());
        int limit = Math.min(stubs.size(), config.candidateCapPerBook());

        List<FilmCandidate> enriched = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            FilmCandidate detail = fetchFilmDetails(stubs.get(i).tmdbId());
            if (detail != null && detail.releaseYear() >= effectiveBookYear) {
                enriched.add(detail);
            }
        }
        return enriched;
    }

    /**
     * Phase B2 single detail call with append_to_response=credits,keywords (Section 3, Step 4).
     */
    public FilmCandidate fetchFilmDetails(long tmdbId) throws IOException, InterruptedException {
        URI detailUri = URI.create(BASE_URL + "/movie/" + tmdbId + "?append_to_response=credits,keywords");
        JsonNode node = gateway.getJson(RateLimitedHttpGateway.Provider.TMDB, detailUri, headers);
        if (node == null || node.path("adult").asBoolean(false)) {
            return null;
        }

        String status = node.path("status").asText("Released");
        if (!"Released".equalsIgnoreCase(status)) {
            return null; // Drop unreleased entries (Section 3, Step 5)
        }

        Integer releaseYear = parseReleaseYear(node.path("release_date").asText(""));
        if (releaseYear == null) {
            return null;
        }

        String title = node.path("title").asText("").trim();
        String imdbId = node.path("imdb_id").asText(null);
        if (imdbId != null && imdbId.isBlank()) {
            imdbId = null;
        }

        // Section 3, Step 5: A budget, revenue or runtime of 0 becomes NULL (unknown)
        int rawRuntime = node.path("runtime").asInt(0);
        Integer runtimeMinutes = rawRuntime > 0 ? rawRuntime : null;

        long rawBudget = node.path("budget").asLong(0L);
        Long productionBudget = rawBudget > 0L ? rawBudget : null;

        long rawRevenue = node.path("revenue").asLong(0L);
        Long boxOfficeGross = rawRevenue > 0L ? rawRevenue : null;

        int voteCount = node.path("vote_count").asInt(0);
        BigDecimal score10 = null;
        if (voteCount > 0 && node.hasNonNull("vote_average")) {
            score10 = BigDecimal.valueOf(node.get("vote_average").asDouble(0.0))
                    .setScale(1, RoundingMode.HALF_UP);
        }

        String overview = node.path("overview").asText("");
        List<FilmPayload.CrewCredit> writingCredits = extractWritingCredits(node.path("credits").path("crew"));
        List<String> keywords = extractKeywords(node.path("keywords").path("keywords"));

        FilmPayload payload = new FilmPayload(
                tmdbId,
                title,
                releaseYear,
                overview,
                writingCredits,
                keywords
        );

        return new FilmCandidate(
                tmdbId,
                imdbId,
                title,
                releaseYear,
                runtimeMinutes,
                productionBudget,
                boxOfficeGross,
                score10,
                voteCount,
                payload
        );
    }

    /**
     * Stratified decade discover sweep across multiple sort orders for building the Film Baseline (Section 4.3).
     */
    public List<FilmCandidate> fetchDecadeBaselineFilms(int decadeStartYear, int minVotes, int pagesPerSortOrder)
            throws IOException, InterruptedException {
        int decadeEndYear = decadeStartYear + 9;
        String[] sortOrders = {"vote_count.desc", "primary_release_date.asc", "vote_average.desc"};
        Map<Long, FilmCandidate> deduped = new LinkedHashMap<>();

        for (String sortBy : sortOrders) {
            for (int page = 1; page <= pagesPerSortOrder; page++) {
                URI discoverUri = URI.create(
                        BASE_URL + "/discover/movie?include_adult=false"
                                + "&primary_release_date.gte=" + decadeStartYear + "-01-01"
                                + "&primary_release_date.lte=" + decadeEndYear + "-12-31"
                                + "&vote_count.gte=" + minVotes
                                + "&sort_by=" + sortBy
                                + "&page=" + page
                );
                JsonNode root = gateway.getJson(RateLimitedHttpGateway.Provider.TMDB, discoverUri, headers);
                if (root == null || !root.has("results")) {
                    break;
                }
                for (JsonNode item : root.get("results")) {
                    long id = item.path("id").asLong(0);
                    String title = item.path("title").asText("").trim();
                    Integer year = parseReleaseYear(item.path("release_date").asText(""));
                    int votes = item.path("vote_count").asInt(0);
                    if (id <= 0 || title.isEmpty() || year == null || votes < minVotes) {
                        continue;
                    }
                    BigDecimal score = BigDecimal.valueOf(item.path("vote_average").asDouble(0.0))
                            .setScale(1, RoundingMode.HALF_UP);
                    FilmPayload stubPayload = new FilmPayload(id, title, year, item.path("overview").asText(""), List.of(), List.of());
                    deduped.putIfAbsent(id, new FilmCandidate(id, null, title, year, null, null, null, score, votes, stubPayload));
                }
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private static List<FilmPayload.CrewCredit> extractWritingCredits(JsonNode crewArray) {
        if (crewArray == null || !crewArray.isArray()) {
            return List.of();
        }
        List<FilmPayload.CrewCredit> credits = new ArrayList<>();
        for (JsonNode c : crewArray) {
            String dept = c.path("department").asText("");
            String job = c.path("job").asText("");
            String name = c.path("name").asText("");
            if (!name.isBlank() && (dept.equalsIgnoreCase("Writing")
                    || job.toLowerCase(Locale.ROOT).contains("novel")
                    || job.toLowerCase(Locale.ROOT).contains("book")
                    || job.toLowerCase(Locale.ROOT).contains("author")
                    || job.toLowerCase(Locale.ROOT).contains("story")
                    || job.toLowerCase(Locale.ROOT).contains("writer"))) {
                credits.add(new FilmPayload.CrewCredit(name, job, dept));
            }
        }
        return credits;
    }

    private static List<String> extractKeywords(JsonNode kwArray) {
        if (kwArray == null || !kwArray.isArray()) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        for (JsonNode k : kwArray) {
            String name = k.path("name").asText("").trim();
            if (!name.isEmpty()) {
                keywords.add(name);
            }
        }
        return keywords;
    }

    private static Integer parseReleaseYear(String dateStr) {
        if (dateStr == null || dateStr.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(dateStr.substring(0, 4));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}