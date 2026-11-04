package com.bookscreen.dao;

import com.bookscreen.config.AppConfig;
import com.bookscreen.config.DatabaseConfig;
import com.bookscreen.model.*;
import com.bookscreen.model.Enums.LinkStatus;
import com.bookscreen.normalize.RatingNormalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class AdaptationUpsertDao {

    public record CandidatePair(FilmCandidate film, ResolvedLink link) {}

    public record StoredPairForReplay(
            long linkId,
            long bookId,
            long movieId,
            BookPayload bookPayload,
            FilmPayload filmPayload
    ) {}

    public record StalenessState(
            boolean exists,
            Instant lastSyncedAt,
            Instant ratingCheckedAt,
            Instant filmSearchCheckedAt
    ) {}

    private final DatabaseConfig dbConfig;
    private final AppConfig appConfig;
    private final ObjectMapper mapper;

    public AdaptationUpsertDao(DatabaseConfig dbConfig, AppConfig appConfig, ObjectMapper mapper) {
        this.dbConfig = dbConfig;
        this.appConfig = appConfig;
        this.mapper = mapper;
    }

    /**
     * Acquires or renews a 15-minute sync lease in sync_leases (Section 7.5).
     */
    public boolean acquireLease(String leaseKey, String owner) throws SQLException {
        String sql = """
            INSERT INTO sync_leases (lease_key, owner, expires_at)
            VALUES (?, ?, TIMESTAMPADD(MINUTE, 15, NOW())) AS new
            ON DUPLICATE KEY UPDATE
                owner = IF(sync_leases.expires_at < NOW() OR sync_leases.owner = new.owner, new.owner, sync_leases.owner),
                expires_at = IF(sync_leases.expires_at < NOW() OR sync_leases.owner = new.owner, new.expires_at, sync_leases.expires_at)
            """;
        String verifySql = "SELECT owner FROM sync_leases WHERE lease_key = ? AND expires_at > NOW()";

        try (Connection conn = dbConfig.openAutoCommitConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, leaseKey);
                ps.setString(2, owner);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(verifySql)) {
                ps.setString(1, leaseKey);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && owner.equals(rs.getString("owner"));
                }
            }
        }
    }

    public void releaseLease(String leaseKey, String owner) {
        String sql = "DELETE FROM sync_leases WHERE lease_key = ? AND owner = ?";
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, leaseKey);
            ps.setString(2, owner);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    /**
     * Checks TTL and negative-caching timestamps before network calls (Section 7.5).
     */
    public StalenessState checkWorkStaleness(String openLibraryWorkId) throws SQLException {
        String sql = """
            SELECT last_synced_at, rating_checked_at, film_search_checked_at
            FROM source_books
            WHERE openlibrary_work_id = ?
            """;
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, openLibraryWorkId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new StalenessState(false, null, null, null);
                }
                return new StalenessState(
                        true,
                        toInstant(rs.getTimestamp("last_synced_at")),
                        toInstant(rs.getTimestamp("rating_checked_at")),
                        toInstant(rs.getTimestamp("film_search_checked_at"))
                );
            }
        }
    }

    public long startSyncRun(String triggerKey) throws SQLException {
        String sql = "INSERT INTO sync_runs (trigger_key, status, started_at) VALUES (?, 'RUNNING', NOW())";
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, triggerKey);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void updateSyncRunProgress(
            long runId,
            int apiCalls,
            int cacheHits,
            int booksSynced,
            int linksAccepted,
            int linksReview,
            int retries,
            int errors
    ) {
        String sql = """
            UPDATE sync_runs
               SET api_calls = ?, cache_hits = ?, books_synced = ?,
                   links_accepted = ?, links_review = ?, retries = ?, errors = ?
             WHERE run_id = ?
            """;
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, apiCalls);
            ps.setInt(2, cacheHits);
            ps.setInt(3, booksSynced);
            ps.setInt(4, linksAccepted);
            ps.setInt(5, linksReview);
            ps.setInt(6, retries);
            ps.setInt(7, errors);
            ps.setLong(8, runId);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public void finishSyncRun(long runId, String status) {
        String coverageSql = """
            SELECT CASE WHEN COUNT(*) = 0 THEN NULL
                        ELSE ROUND(SUM(CASE WHEN production_budget >= 10000 AND box_office_gross >= 10000 THEN 1 ELSE 0 END) / COUNT(*), 4)
                   END AS cov
            FROM film_adaptations
            """;
        String updateSql = "UPDATE sync_runs SET status = ?, finished_at = NOW(), film_financial_coverage = ? WHERE run_id = ?";

        try (Connection conn = dbConfig.openAutoCommitConnection()) {
            BigDecimal coverage = null;
            try (PreparedStatement ps = conn.prepareStatement(coverageSql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    coverage = rs.getBigDecimal("cov");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, status);
                if (coverage != null) {
                    ps.setBigDecimal(2, coverage);
                } else {
                    ps.setNull(2, Types.DECIMAL);
                }
                ps.setLong(3, runId);
                ps.executeUpdate();
            }
        } catch (SQLException ignored) {}
    }

    /**
     * Logs an error on a separate auto-commit connection so rolled-back units of work are still recorded (Section 7.1).
     */
    public void logSyncError(Long runId, String entityType, String externalKey, String stage, int attempt, String message) {
        String sql = """
            INSERT INTO sync_errors (run_id, entity_type, external_key, stage, attempt, message, created_at)
            VALUES (?, ?, ?, ?, ?, ?, NOW())
            """;
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (runId != null) {
                ps.setLong(1, runId);
            } else {
                ps.setNull(1, Types.BIGINT);
            }
            ps.setString(2, entityType);
            ps.setString(3, externalKey);
            ps.setString(4, stage);
            ps.setInt(5, attempt);
            ps.setString(6, message != null ? message : "Unknown error");
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    /**
     * Persists a BookBundle and its candidate film links inside a single transaction with deadlock retries
     * and per-film savepoints (Section 7.1, 7.2, 7.3).
     */
    public int persistBookBundleWithRetries(
            Long runId,
            BookBundle bundle,
            List<CandidatePair> filmPairs,
            boolean filmSearchPerformed
    ) throws SQLException {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeBookBundleTransaction(runId, bundle, filmPairs, filmSearchPerformed, attempt);
            } catch (SQLException ex) {
                logSyncError(runId, "BOOK_BUNDLE", bundle.openLibraryWorkId(), "DB_TRANSACTION", attempt, ex.getMessage());
                if (attempt < maxAttempts && isDeadlockOrLockTimeout(ex)) {
                    sleepBackoff(attempt);
                    continue;
                }
                throw ex;
            }
        }
        return 0;
    }

    private int executeBookBundleTransaction(
            Long runId,
            BookBundle bundle,
            List<CandidatePair> filmPairs,
            boolean filmSearchPerformed,
            int attempt
    ) throws SQLException {
        try (Connection conn = dbConfig.openTransactionalConnection()) {
            try {
                // 1. Upsert authors in fixed order
                List<Long> authorIds = new ArrayList<>();
                for (AuthorRecord author : bundle.authors()) {
                    long authorId = upsertAuthor(conn, author);
                    authorIds.add(authorId);
                }

                // 2. Upsert source_books
                long bookId = upsertSourceBook(conn, bundle, filmSearchPerformed);

                // 3. Upsert book_authors
                for (int i = 0; i < bundle.authors().size(); i++) {
                    upsertBookAuthor(conn, bookId, authorIds.get(i), bundle.authors().get(i).authorOrder());
                }

                // 4. Upsert book_editions and recompute median page count from stored rows (Section 4.1 & 6)
                for (EditionRecord edition : bundle.editions()) {
                    upsertBookEdition(conn, bookId, edition);
                }
                recomputeAndStoreBookMedianPages(conn, bookId);

                // 5 & 6. Upsert film_adaptations and adaptation_links behind a SAVEPOINT per film (Section 7.1)
                int savedLinks = 0;
                for (CandidatePair pair : filmPairs) {
                    if (pair.link().linkStatus() == LinkStatus.REJECTED) {
                        continue; // Section 5.1: Discarded links are logged/skipped, not stored unless REVIEW/ACCEPTED
                    }
                    Savepoint sp = conn.setSavepoint("sp_film_" + pair.film().tmdbId());
                    try {
                        long movieId = upsertFilmAdaptation(conn, pair.film());
                        upsertAdaptationLink(conn, bookId, movieId, pair.link());
                        savedLinks++;
                    } catch (SQLException linkEx) {
                        conn.rollback(sp);
                        logSyncError(runId, "FILM_LINK",
                                bundle.openLibraryWorkId() + "->" + pair.film().tmdbId(),
                                "SAVEPOINT_ROLLBACK", attempt, linkEx.getMessage());
                    }
                }

                conn.commit();
                return savedLinks;
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    private long upsertAuthor(Connection conn, AuthorRecord author) throws SQLException {
        String sql = """
            INSERT INTO authors (openlibrary_author_key, full_name, last_synced_at)
            VALUES (?, ?, NOW()) AS new
            ON DUPLICATE KEY UPDATE
                author_id = LAST_INSERT_ID(author_id),
                full_name = new.full_name,
                last_synced_at = NOW()
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, author.openLibraryAuthorKey());
            ps.setString(2, author.fullName());
            ps.executeUpdate();
            return extractGeneratedId(ps);
        }
    }

    private long upsertSourceBook(Connection conn, BookBundle bundle, boolean filmSearchPerformed) throws SQLException {
        String sql = """
            INSERT INTO source_books
              (openlibrary_work_id, title, first_publish_year, page_count_median, edition_count,
               avg_rating_5, ratings_count, rating_source, ol_payload, payload_fetched_at,
               rating_checked_at, film_search_checked_at, last_synced_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), ?, NOW()) AS new
            ON DUPLICATE KEY UPDATE
              book_id = LAST_INSERT_ID(book_id),
              title = new.title,
              first_publish_year = COALESCE(new.first_publish_year, source_books.first_publish_year),
              page_count_median = COALESCE(new.page_count_median, source_books.page_count_median),
              edition_count = GREATEST(new.edition_count, source_books.edition_count),
              avg_rating_5 = COALESCE(new.avg_rating_5, source_books.avg_rating_5),
              ratings_count = IF(new.avg_rating_5 IS NOT NULL, new.ratings_count, source_books.ratings_count),
              rating_source = COALESCE(new.rating_source, source_books.rating_source),
              ol_payload = COALESCE(new.ol_payload, source_books.ol_payload),
              payload_fetched_at = COALESCE(new.payload_fetched_at, source_books.payload_fetched_at),
              rating_checked_at = NOW(),
              film_search_checked_at = COALESCE(new.film_search_checked_at, source_books.film_search_checked_at),
              last_synced_at = NOW()
            """;

        Integer initialMedian = RatingNormalizer.computeMedianPageCount(bundle.editions());
        String payloadJson = toJson(bundle.payload());

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, bundle.openLibraryWorkId());
            ps.setString(2, bundle.title());
            setNullableShort(ps, 3, bundle.firstPublishYear());
            setNullableInt(ps, 4, initialMedian);
            ps.setInt(5, bundle.editions().size());
            if (bundle.avgRating5() != null && bundle.ratingSource() != null) {
                ps.setBigDecimal(6, bundle.avgRating5());
                ps.setInt(7, bundle.ratingsCount());
                ps.setString(8, bundle.ratingSource().name());
            } else {
                ps.setNull(6, Types.DECIMAL);
                ps.setInt(7, 0);
                ps.setNull(8, Types.VARCHAR);
            }
            ps.setString(9, payloadJson);
            if (filmSearchPerformed) {
                ps.setTimestamp(10, Timestamp.from(Instant.now()));
            } else {
                ps.setNull(10, Types.TIMESTAMP);
            }
            ps.executeUpdate();
            return extractGeneratedId(ps);
        }
    }

    private void upsertBookAuthor(Connection conn, long bookId, long authorId, int order) throws SQLException {
        String sql = """
            INSERT INTO book_authors (book_id, author_id, author_order)
            VALUES (?, ?, ?) AS new
            ON DUPLICATE KEY UPDATE author_order = new.author_order
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bookId);
            ps.setLong(2, authorId);
            ps.setInt(3, order);
            ps.executeUpdate();
        }
    }

    private void upsertBookEdition(Connection conn, long bookId, EditionRecord ed) throws SQLException {
        String sql = """
            INSERT INTO book_editions (openlibrary_edition_id, book_id, isbn_13, page_count, publish_year)
            VALUES (?, ?, ?, ?, ?) AS new
            ON DUPLICATE KEY UPDATE
                edition_id = LAST_INSERT_ID(edition_id),
                isbn_13 = COALESCE(new.isbn_13, book_editions.isbn_13),
                page_count = COALESCE(new.page_count, book_editions.page_count),
                publish_year = COALESCE(new.publish_year, book_editions.publish_year)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ed.openLibraryEditionId());
            ps.setLong(2, bookId);
            if (ed.isbn13() != null) {
                ps.setString(3, ed.isbn13());
            } else {
                ps.setNull(3, Types.CHAR);
            }
            setNullableInt(ps, 4, ed.pageCount());
            setNullableShort(ps, 5, ed.publishYear());
            ps.executeUpdate();
        }
    }

    private void recomputeAndStoreBookMedianPages(Connection conn, long bookId) throws SQLException {
        String selectSql = "SELECT openlibrary_edition_id, isbn_13, page_count, publish_year FROM book_editions WHERE book_id = ?";
        List<EditionRecord> stored = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setLong(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int pages = rs.getInt("page_count");
                    Integer pObj = rs.wasNull() ? null : pages;
                    short yr = rs.getShort("publish_year");
                    Integer yObj = rs.wasNull() ? null : (int) yr;
                    stored.add(new EditionRecord(
                            rs.getString("openlibrary_edition_id"),
                            rs.getString("isbn_13"),
                            pObj,
                            yObj
                    ));
                }
            }
        }
        Integer median = RatingNormalizer.computeMedianPageCount(stored);
        String updateSql = "UPDATE source_books SET page_count_median = ?, edition_count = ? WHERE book_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            setNullableInt(ps, 1, median);
            ps.setInt(2, stored.size());
            ps.setLong(3, bookId);
            ps.executeUpdate();
        }
    }

    private long upsertFilmAdaptation(Connection conn, FilmCandidate film) throws SQLException {
        // Section 7.2: Never list generated column (gross_multiple) in insert or update clauses.
        String sql = """
            INSERT INTO film_adaptations
              (tmdb_id, imdb_id, title, release_year, runtime_minutes,
               production_budget, box_office_gross, tmdb_score_10, tmdb_vote_count,
               tmdb_payload, payload_fetched_at, last_synced_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW()) AS new
            ON DUPLICATE KEY UPDATE
              movie_id = LAST_INSERT_ID(movie_id),
              imdb_id = COALESCE(new.imdb_id, film_adaptations.imdb_id),
              title = new.title,
              release_year = new.release_year,
              runtime_minutes = COALESCE(new.runtime_minutes, film_adaptations.runtime_minutes),
              production_budget = COALESCE(new.production_budget, film_adaptations.production_budget),
              box_office_gross = COALESCE(new.box_office_gross, film_adaptations.box_office_gross),
              tmdb_score_10 = COALESCE(new.tmdb_score_10, film_adaptations.tmdb_score_10),
              tmdb_vote_count = GREATEST(new.tmdb_vote_count, film_adaptations.tmdb_vote_count),
              tmdb_payload = COALESCE(new.tmdb_payload, film_adaptations.tmdb_payload),
              payload_fetched_at = COALESCE(new.payload_fetched_at, film_adaptations.payload_fetched_at),
              last_synced_at = NOW()
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, film.tmdbId());
            if (film.imdbId() != null) {
                ps.setString(2, film.imdbId());
            } else {
                ps.setNull(2, Types.VARCHAR);
            }
            ps.setString(3, film.title());
            ps.setShort(4, (short) film.releaseYear());
            setNullableInt(ps, 5, film.runtimeMinutes());
            setNullableLong(ps, 6, film.productionBudget());
            setNullableLong(ps, 7, film.boxOfficeGross());
            if (film.tmdbScore10() != null) {
                ps.setBigDecimal(8, film.tmdbScore10());
            } else {
                ps.setNull(8, Types.DECIMAL);
            }
            ps.setInt(9, film.tmdbVoteCount());
            ps.setString(10, toJson(film.payload()));
            ps.executeUpdate();
            return extractGeneratedId(ps);
        }
    }

    /**
     * Exact upsert from Section 7.2 preserving HUMAN review decisions.
     */
    public void upsertAdaptationLink(Connection conn, long bookId, long movieId, ResolvedLink link) throws SQLException {
        String sql = """
            INSERT INTO adaptation_links
              (book_id, movie_id, match_confidence, matcher_version, link_status,
               status_source, evidence, last_synced_at)
            VALUES (?, ?, ?, ?, ?, 'MATCHER', ?, NOW()) AS new
            ON DUPLICATE KEY UPDATE
              link_id          = LAST_INSERT_ID(link_id),
              match_confidence = new.match_confidence,
              matcher_version  = new.matcher_version,
              evidence         = new.evidence,
              link_status      = IF(adaptation_links.status_source = 'HUMAN',
                                    adaptation_links.link_status, new.link_status),
              last_synced_at   = NOW()
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bookId);
            ps.setLong(2, movieId);
            ps.setBigDecimal(3, link.matchConfidence().setScale(2, RoundingMode.HALF_UP));
            ps.setString(4, link.matcherVersion());
            ps.setString(5, link.linkStatus().name());
            ps.setString(6, toJson(link.evidence()));
            ps.executeUpdate();
        }
    }

    /**
     * Loads all stored pairs with non-null JSON payloads for the offline replay-match command (Section 3 & 5.1).
     */
    public List<StoredPairForReplay> fetchAllStoredPairsForReplay() throws SQLException, JsonProcessingException {
        String sql = """
            SELECT l.link_id, l.book_id, l.movie_id, sb.ol_payload, fa.tmdb_payload
            FROM adaptation_links l
            JOIN source_books sb     ON sb.book_id  = l.book_id
            JOIN film_adaptations fa ON fa.movie_id = l.movie_id
            WHERE sb.ol_payload IS NOT NULL AND fa.tmdb_payload IS NOT NULL
            """;
        List<StoredPairForReplay> list = new ArrayList<>();
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                BookPayload bp = mapper.readValue(rs.getString("ol_payload"), BookPayload.class);
                FilmPayload fp = mapper.readValue(rs.getString("tmdb_payload"), FilmPayload.class);
                list.add(new StoredPairForReplay(
                        rs.getLong("link_id"),
                        rs.getLong("book_id"),
                        rs.getLong("movie_id"),
                        bp,
                        fp
                ));
            }
        }
        return list;
    }

    /**
     * Updates a link from the human review CLI command with status_source = 'HUMAN' (Section 5.1 & 7.2).
     */
    public boolean recordHumanReviewDecision(long linkId, LinkStatus decision, String reviewer) throws SQLException {
        if (decision != LinkStatus.ACCEPTED && decision != LinkStatus.REJECTED) {
            throw new IllegalArgumentException("Human decision must be ACCEPTED or REJECTED");
        }
        String sql = """
            UPDATE adaptation_links
               SET link_status = ?,
                   status_source = 'HUMAN',
                   reviewed_by = ?,
                   reviewed_at = NOW(),
                   last_synced_at = NOW()
             WHERE link_id = ?
            """;
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, decision.name());
            ps.setString(2, reviewer);
            ps.setLong(3, linkId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Loads or refreshes US CPI-U series into economic_index (Section 5.3 & 6).
     */
    public void upsertCpiIndex(int year, BigDecimal cpiValue) throws SQLException {
        String sql = """
            INSERT INTO economic_index (year, cpi_index)
            VALUES (?, ?) AS new
            ON DUPLICATE KEY UPDATE cpi_index = new.cpi_index
            """;
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setShort(1, (short) year);
            ps.setBigDecimal(2, cpiValue);
            ps.executeUpdate();
        }
    }

    private static boolean isDeadlockOrLockTimeout(SQLException ex) {
        String state = ex.getSQLState();
        int code = ex.getErrorCode();
        return "40001".equals(state) || code == 1213 || code == 1205;
    }

    private static void sleepBackoff(int attempt) {
        long ms = (long) (Math.pow(2, attempt) * 200L) + ThreadLocalRandom.current().nextLong(50, 250);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String toJson(Object obj) throws SQLException {
        if (obj == null) return null;
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize payload JSON", e);
        }
    }

    private static long extractGeneratedId(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.getGeneratedKeys()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            throw new SQLException("No generated key returned from upsert.");
        }
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer val) throws SQLException {
        if (val != null) ps.setInt(idx, val);
        else ps.setNull(idx, Types.INTEGER);
    }

    private static void setNullableShort(PreparedStatement ps, int idx, Integer val) throws SQLException {
        if (val != null) ps.setShort(idx, val.shortValue());
        else ps.setNull(idx, Types.SMALLINT);
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long val) throws SQLException {
        if (val != null) ps.setLong(idx, val);
        else ps.setNull(idx, Types.BIGINT);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}