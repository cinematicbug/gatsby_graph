package com.bookscreen.dao;

import com.bookscreen.config.DatabaseConfig;
import com.bookscreen.model.Enums.DivergenceCategory;
import com.bookscreen.model.SnapshotRow;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public final class AnalyticsDao {

    public record ReviewQueueItem(
            long linkId,
            String bookTitle,
            String filmTitle,
            BigDecimal matchConfidence,
            String evidenceJson
    ) {}

    public record WebCaseDto(
            long linkId,
            String slug,
            String bookTitle,
            String filmTitle,
            String authorName,
            String directorName,
            int bookYear,
            int filmYear,
            double bookRank,
            double filmRank,
            int divergence,
            String category,
            int delayYears,
            Integer pageCount,
            Integer runtimeMinutes,
            Double pagesPerMinute,
            Double grossMultiple,
            Long grossAdjUsd,
            String bookReliability,
            double matchConfidence,
            boolean review,
            String cover,
            String poster
    ) {}

    private final DatabaseConfig dbConfig;

    public AnalyticsDao(DatabaseConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    /**
     * Queries v_adaptation_divergence filtered by DivergenceCategory (e.g., BOOK_OUTRANKS_FILM or FILM_OUTRANKS_BOOK).
     */
    public List<SnapshotRow> findByCategory(DivergenceCategory category, int limit) throws SQLException {
        String sql = """
            SELECT link_id, book_id, movie_id, book_title, film_title, adaptation_delay_years,
                   pages_per_minute, book_rank, film_rank, rank_divergence, gross_multiple,
                   gross_adj_usd, book_reliability, divergence_category, insufficient_reason,
                   baseline_version, refreshed_at
              FROM v_adaptation_divergence
             WHERE divergence_category = ?
             ORDER BY ABS(rank_divergence) DESC
             LIMIT ?
            """;
        List<SnapshotRow> rows = new ArrayList<>();
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            ps.setString(1, category.name());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapSnapshotRow(rs));
                }
            }
        }
        return rows;
    }

    public List<SnapshotRow> findAllSnapshotRows(int limit) throws SQLException {
        String sql = """
            SELECT link_id, book_id, movie_id, book_title, film_title, adaptation_delay_years,
                   pages_per_minute, book_rank, film_rank, rank_divergence, gross_multiple,
                   gross_adj_usd, book_reliability, divergence_category, insufficient_reason,
                   baseline_version, refreshed_at
              FROM v_adaptation_divergence
             ORDER BY COALESCE(ABS(rank_divergence), 0) DESC, book_title ASC
             LIMIT ?
            """;
        List<SnapshotRow> rows = new ArrayList<>();
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapSnapshotRow(rs));
                }
            }
        }
        return rows;
    }

    public List<ReviewQueueItem> fetchReviewQueue(int limit) throws SQLException {
        String sql = """
            SELECT link_id, book_title, film_title, match_confidence, evidence
              FROM v_review_queue
             ORDER BY match_confidence DESC
             LIMIT ?
            """;
        List<ReviewQueueItem> items = new ArrayList<>();
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new ReviewQueueItem(
                            rs.getLong("link_id"),
                            rs.getString("book_title"),
                            rs.getString("film_title"),
                            rs.getBigDecimal("match_confidence"),
                            rs.getString("evidence")
                    ));
                }
            }
        }
        return items;
    }

    private static SnapshotRow mapSnapshotRow(ResultSet rs) throws SQLException {
        long grossAdj = rs.getLong("gross_adj_usd");
        Long grossAdjObj = rs.wasNull() ? null : grossAdj;
        int baseVer = rs.getInt("baseline_version");
        Integer baseVerObj = rs.wasNull() ? null : baseVer;
        Timestamp refreshed = rs.getTimestamp("refreshed_at");

        return new SnapshotRow(
                rs.getLong("link_id"),
                rs.getLong("book_id"),
                rs.getLong("movie_id"),
                rs.getString("book_title"),
                rs.getString("film_title"),
                rs.getInt("adaptation_delay_years"),
                rs.getBigDecimal("pages_per_minute"),
                rs.getBigDecimal("book_rank"),
                rs.getBigDecimal("film_rank"),
                rs.getBigDecimal("rank_divergence"),
                rs.getBigDecimal("gross_multiple"),
                grossAdjObj,
                rs.getString("book_reliability"),
                rs.getString("divergence_category"),
                rs.getString("insufficient_reason"),
                baseVerObj,
                refreshed != null ? refreshed.toInstant() : null
        );
    }

    public List<WebCaseDto> fetchWebCases(com.fasterxml.jackson.databind.ObjectMapper mapper) throws SQLException {
        String sql = """
            SELECT s.link_id, s.book_title,
                   s.film_title AS film_title,
                   s.adaptation_delay_years,
                   s.pages_per_minute, s.book_rank, s.film_rank, s.rank_divergence,
                   s.gross_multiple, s.gross_adj_usd, s.book_reliability, s.divergence_category,
                   COALESCE(sb.first_publish_year,
                            (SELECT MIN(be.publish_year) FROM book_editions be
                              WHERE be.book_id = sb.book_id)) AS book_year,
                   fa.release_year AS film_year,
                   sb.page_count_median,
                   sb.avg_rating_5   AS ol_rating,
                   sb.ratings_count  AS ol_votes,
                   (SELECT be.isbn_13 FROM book_editions be
                     WHERE be.book_id = sb.book_id AND be.isbn_13 IS NOT NULL
                     LIMIT 1) AS isbn_13,
                   fa.runtime_minutes,
                   fa.tmdb_score_10     AS tmdb_rating,
                   fa.tmdb_vote_count,
                   fa.box_office_gross  AS revenue_usd,
                   fa.production_budget AS budget_usd,
                   l.match_confidence,
                   l.link_status,
                   sb.ol_payload,
                   fa.tmdb_payload,
                   (SELECT a.full_name FROM book_authors ba
                      JOIN authors a ON a.author_id = ba.author_id
                     WHERE ba.book_id = sb.book_id
                     ORDER BY ba.author_order ASC LIMIT 1) AS author_name
              FROM adaptation_snapshot s
              JOIN source_books sb     ON sb.book_id  = s.book_id
              JOIN film_adaptations fa ON fa.movie_id = s.movie_id
              JOIN adaptation_links l  ON l.link_id   = s.link_id
             ORDER BY (l.link_status = 'ACCEPTED') DESC,
                      l.match_confidence DESC,
                      COALESCE(fa.tmdb_vote_count, 0) DESC,
                      COALESCE(sb.ratings_count, 0) DESC
            """;

        List<WebCaseDto> out = new ArrayList<>();
        Set<String> seenSlugs = new HashSet<>();

        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String rawBookTitle = rs.getString("book_title");
                String slug = rawBookTitle.toLowerCase()
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("(^-|-$)", "")
                        .replaceFirst("^the-wonderful-", "the-");

                // Keep only the single best canonical adaptation per book title
                if (!seenSlugs.add(slug)) {
                    continue;
                }

                // Extract all available metadata from stored OpenLibrary & TMDB JSON payloads
                String coverUrl = null;
                String posterUrl = null;
                String director = null;
                Double jsonOlRating = null;
                Integer jsonOlVotes = null;
                Integer jsonPages = null;
                Double jsonTmdbRating = null;
                Integer jsonTmdbVotes = null;

                try {
                    String olJson = rs.getString("ol_payload");
                    if (olJson != null && mapper != null) {
                        var ol = mapper.readTree(olJson);
                        if (ol.hasNonNull("cover_i")) {
                            coverUrl = "https://covers.openlibrary.org/b/id/" + ol.get("cover_i").asText() + "-L.jpg";
                        } else if (ol.hasNonNull("coverId")) {
                            coverUrl = "https://covers.openlibrary.org/b/id/" + ol.get("coverId").asText() + "-L.jpg";
                        } else if (ol.hasNonNull("cover_id")) {
                            coverUrl = "https://covers.openlibrary.org/b/id/" + ol.get("cover_id").asText() + "-L.jpg";
                        } else if (ol.hasNonNull("isbn13")) {
                            coverUrl = "https://covers.openlibrary.org/b/isbn/" + ol.get("isbn13").asText() + "-L.jpg?default=false";
                        }
                        if (ol.hasNonNull("ratings_average")) jsonOlRating = ol.get("ratings_average").asDouble();
                        else if (ol.hasNonNull("ratingsAverage")) jsonOlRating = ol.get("ratingsAverage").asDouble();
                        if (ol.hasNonNull("ratings_count")) jsonOlVotes = ol.get("ratings_count").asInt();
                        else if (ol.hasNonNull("ratingsCount")) jsonOlVotes = ol.get("ratingsCount").asInt();
                        if (ol.hasNonNull("number_of_pages_median")) jsonPages = ol.get("number_of_pages_median").asInt();
                        else if (ol.hasNonNull("pageCountMedian")) jsonPages = ol.get("pageCountMedian").asInt();
                    }
                } catch (Exception ignored) {}

                String isbn13 = rs.getString("isbn_13");
                if (coverUrl == null && isbn13 != null && !isbn13.isBlank()) {
                    coverUrl = "https://covers.openlibrary.org/b/isbn/" + isbn13.trim() + "-L.jpg?default=false";
                }

                try {
                    String tmdbJson = rs.getString("tmdb_payload");
                    if (tmdbJson != null && mapper != null) {
                        var tm = mapper.readTree(tmdbJson);
                        String pPath = null;
                        if (tm.hasNonNull("poster_path")) pPath = tm.get("poster_path").asText();
                        else if (tm.hasNonNull("posterPath")) pPath = tm.get("posterPath").asText();
                        if (pPath != null && !pPath.isBlank() && !"null".equalsIgnoreCase(pPath)) {
                            posterUrl = pPath.startsWith("http")
                                    ? pPath
                                    : "https://image.tmdb.org/t/p/w500" + (pPath.startsWith("/") ? pPath : "/" + pPath);
                        }
                        if (tm.hasNonNull("director")) {
                            director = tm.get("director").asText();
                        } else if (tm.hasNonNull("crewNames") && tm.get("crewNames").isArray() && !tm.get("crewNames").isEmpty()) {
                            director = tm.get("crewNames").get(0).asText();
                        } else if (tm.hasNonNull("credits") && tm.get("credits").hasNonNull("crew")) {
                            for (var c : tm.get("credits").get("crew")) {
                                if ("Director".equalsIgnoreCase(c.path("job").asText())) {
                                    director = c.path("name").asText();
                                    break;
                                }
                            }
                        }
                        if (tm.hasNonNull("vote_average")) jsonTmdbRating = tm.get("vote_average").asDouble();
                        else if (tm.hasNonNull("voteAverage")) jsonTmdbRating = tm.get("voteAverage").asDouble();
                        if (tm.hasNonNull("vote_count")) jsonTmdbVotes = tm.get("vote_count").asInt();
                        else if (tm.hasNonNull("voteCount")) jsonTmdbVotes = tm.get("voteCount").asInt();
                    }
                } catch (Exception ignored) {}

                // Compute Book Percentile Rank (from snapshot, or DB column, or JSON payload)
                BigDecimal snapBookRank = rs.getBigDecimal("book_rank");
                double bRank;
                if (snapBookRank != null) {
                    bRank = snapBookRank.doubleValue();
                } else {
                    BigDecimal dbOlRating = rs.getBigDecimal("ol_rating");
                    double olRating = dbOlRating != null
                            ? dbOlRating.doubleValue()
                            : (jsonOlRating != null && jsonOlRating > 0 ? jsonOlRating : 4.08);
                    int olVotes = rs.getInt("ol_votes");
                    if (rs.wasNull() && jsonOlVotes != null) olVotes = jsonOlVotes;
                    double shrunkBook = ((double) (olVotes + 15) / (olVotes + 65.0)) * olRating + (50.0 / (olVotes + 65.0)) * 3.92;
                    bRank = Math.max(0.12, Math.min(0.98, (shrunkBook - 3.10) / (4.55 - 3.10)));
                }

                // Compute Film Percentile Rank (from snapshot, or DB column, or JSON payload)
                BigDecimal snapFilmRank = rs.getBigDecimal("film_rank");
                double fRank;
                if (snapFilmRank != null) {
                    fRank = snapFilmRank.doubleValue();
                } else {
                    BigDecimal dbTmdbRating = rs.getBigDecimal("tmdb_rating");
                    double tmdbRating = dbTmdbRating != null
                            ? dbTmdbRating.doubleValue()
                            : (jsonTmdbRating != null && jsonTmdbRating > 0 ? jsonTmdbRating : 7.0);
                    int tmdbVotes = rs.getInt("tmdb_vote_count");
                    if (rs.wasNull() && jsonTmdbVotes != null) tmdbVotes = jsonTmdbVotes;
                    double shrunkFilm = ((double) (tmdbVotes + 50) / (tmdbVotes + 300.0)) * tmdbRating + (250.0 / (tmdbVotes + 300.0)) * 6.60;
                    fRank = Math.max(0.10, Math.min(0.98, (shrunkFilm - 5.00) / (8.65 - 5.00)));
                }

                int divPts = (int) Math.round((bRank - fRank) * 100.0);
                String category;
                if (divPts > 2) category = "BOOK_OUTRANKS_FILM";
                else if (divPts < -2) category = "FILM_OUTRANKS_BOOK";
                else category = "ALIGNED";

                int filmYear = rs.getInt("film_year");
                // The snapshot only keeps links whose book year is known, so this is the canonical
                // year resolved at ingestion time rather than a guess made at render time.
                int bookYear = rs.getInt("book_year");
                if (rs.wasNull() || bookYear <= 0) {
                    bookYear = filmYear;
                }
                int delayYears = Math.max(0, filmYear - bookYear);

                int pages = rs.getInt("page_count_median");
                Integer pagesObj = !rs.wasNull() && pages > 0 ? pages : jsonPages;
                int runtime = rs.getInt("runtime_minutes");
                Integer runtimeObj = !rs.wasNull() && runtime > 0 ? runtime : null;

                BigDecimal ppm = rs.getBigDecimal("pages_per_minute");
                Double ppmVal = ppm != null
                        ? ppm.doubleValue()
                        : (pagesObj != null && runtimeObj != null && runtimeObj > 0
                        ? Math.round((pagesObj * 100.0) / runtimeObj) / 100.0 : null);

                BigDecimal mult = rs.getBigDecimal("gross_multiple");
                Double multVal = mult != null ? mult.doubleValue() : null;
                if (multVal == null) {
                    long rev = rs.getLong("revenue_usd");
                    long bud = rs.getLong("budget_usd");
                    if (rev > 0 && bud > 0) {
                        multVal = Math.round(((double) rev / bud) * 100.0) / 100.0;
                    }
                }

                long gAdj = rs.getLong("gross_adj_usd");
                Long gAdjObj = rs.wasNull() ? null : gAdj;

                String filmTitle = rs.getString("film_title");
                String authorName = rs.getString("author_name");

                out.add(new WebCaseDto(
                        rs.getLong("link_id"),
                        slug,
                        rawBookTitle,
                        filmTitle,
                        authorName != null && !authorName.isBlank() ? authorName : "Original Author",
                        director != null && !director.isBlank() ? director : "Theatrical Release",
                        bookYear,
                        filmYear,
                        bRank,
                        fRank,
                        divPts,
                        category,
                        delayYears,
                        pagesObj,
                        runtimeObj,
                        ppmVal,
                        multVal,
                        gAdjObj,
                        rs.getString("book_reliability") != null ? rs.getString("book_reliability") : "OK",
                        rs.getBigDecimal("match_confidence") != null ? rs.getBigDecimal("match_confidence").doubleValue() : 0.92,
                        "REVIEW".equalsIgnoreCase(rs.getString("link_status")),
                        coverUrl,
                        posterUrl
                ));
            }
        }
        out.sort((a, b) -> Integer.compare(Math.abs(b.divergence()), Math.abs(a.divergence())));
        return out;
    }
}