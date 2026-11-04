package com.bookscreen.dao;

import com.bookscreen.config.AppConfig;
import com.bookscreen.config.DatabaseConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;

public final class SnapshotDao {

    public record SnapshotBuildSummary(
            long snapshotRunId,
            Integer baselineVersion,
            int rowCount,
            BigDecimal divergenceThreshold,
            BigDecimal flaggedShare
    ) {}

    private final DatabaseConfig dbConfig;
    private final AppConfig appConfig;

    public SnapshotDao(DatabaseConfig dbConfig, AppConfig appConfig) {
        this.dbConfig = dbConfig;
        this.appConfig = appConfig;
    }

    /**
     * Executes the complete crash-safe snapshot rebuild and atomic table swap (Section 7.4).
     */
    public SnapshotBuildSummary rebuildAndSwapSnapshot(Integer explicitBaselineVersion) throws SQLException {
        try (Connection conn = dbConfig.openAutoCommitConnection()) {
            // 0. Mark any crashed earlier runs (BUILDING or BUILT with no swapped_at) as FAILED
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("""
                    UPDATE snapshot_runs
                       SET status = 'FAILED'
                     WHERE status IN ('BUILDING', 'BUILT') AND swapped_at IS NULL
                    """);
            }

            Integer baselineVersion = explicitBaselineVersion != null
                    ? explicitBaselineVersion
                    : lookupLatestFrozenBaseline(conn);

            // 1. Insert snapshot_runs receipt row and drop leftover staging tables
            long snapshotRunId = insertSnapshotRunReceipt(conn, baselineVersion);
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS adaptation_snapshot_next, adaptation_snapshot_old");
                st.execute("CREATE TABLE adaptation_snapshot_next LIKE adaptation_snapshot");
            }

            try {
                // 2. Step A: Joins, shrinkage, CPI inflation adjustment, and defensive chronological gate
                int insertedRows = executeStepA(conn, snapshotRunId, baselineVersion);

                // 3. Step B: Baseline-relative mid-ranks and insufficiency reasons in precedence order
                executeStepB(conn, baselineVersion);

                // 4. Step C: Calibrate threshold d via PERCENT_RANK() and classify rows
                BigDecimal thresholdD = executeStepCAndCalibrate(conn);
                BigDecimal flaggedShare = computeFlaggedShare(conn);

                // 5. Mark run BUILT
                markSnapshotBuilt(conn, snapshotRunId, insertedRows, thresholdD, flaggedShare);

                // 6. Atomic Swap with scoped lock_wait_timeout = 10 and up to 5 retries
                swapTablesWithRetry(conn);
                markSnapshotSwapped(conn, snapshotRunId);

                // 7. Best-effort cleanup of old table (tolerated if it fails)
                try (Statement st = conn.createStatement()) {
                    st.execute("DROP TABLE IF EXISTS adaptation_snapshot_old");
                } catch (SQLException ignored) {}

                return new SnapshotBuildSummary(snapshotRunId, baselineVersion, insertedRows, thresholdD, flaggedShare);
            } catch (SQLException ex) {
                markSnapshotFailed(conn, snapshotRunId);
                throw ex;
            }
        }
    }

    private int executeStepA(Connection conn, long snapshotRunId, Integer baselineVersion) throws SQLException {
        // Exact Step A query from Section 7.4
        String stepASql = """
            INSERT INTO adaptation_snapshot_next
              (link_id, book_id, movie_id, book_title, film_title, adaptation_delay_years,
               pages_per_minute, book_partition, film_partition, book_baseline_n,
               film_baseline_n, book_shrunk_score, film_shrunk_score, legacy_scaled_delta,
               gross_multiple, gross_adj_usd, book_reliability, baseline_version,
               snapshot_run_id, refreshed_at)
            SELECT l.link_id, sb.book_id, fa.movie_id, sb.title, fa.title,
                   fa.release_year - y.book_year,
                   sb.page_count_median / fa.runtime_minutes,
                   sb.rating_source, CONCAT(FLOOR(fa.release_year / 10) * 10, 's'),
                   bp.n, fp.n,
                   CASE WHEN sb.ratings_count >= ? AND bp.mean_raw IS NOT NULL
                        THEN ROUND((? * bp.mean_raw + sb.ratings_count * sb.avg_rating_5)
                             / (? + sb.ratings_count), 4) END,
                   CASE WHEN fa.tmdb_vote_count >= ? AND fp.mean_raw IS NOT NULL
                        THEN ROUND((? * fp.mean_raw + fa.tmdb_vote_count * fa.tmdb_score_10)
                             / (? + fa.tmdb_vote_count), 4) END,
                   sb.avg_rating_5 * 2 - fa.tmdb_score_10,
                   fa.gross_multiple,
                   CASE WHEN cr.cpi_index > 0 AND cb.cpi_index IS NOT NULL
                        THEN ROUND(fa.box_office_gross * (cb.cpi_index / cr.cpi_index)) END,
                   IF(sb.ratings_count >= 100, 'OK', 'LOW'),
                   ?, ?, NOW()
            FROM adaptation_links l
            JOIN source_books sb     ON sb.book_id  = l.book_id
            JOIN film_adaptations fa ON fa.movie_id = l.movie_id
            JOIN (SELECT b.book_id, COALESCE(b.first_publish_year, MIN(e.publish_year)) AS book_year
                    FROM source_books b LEFT JOIN book_editions e ON e.book_id = b.book_id
                   GROUP BY b.book_id, b.first_publish_year) y ON y.book_id = sb.book_id
            LEFT JOIN baseline_partitions bp ON bp.baseline_version = ? AND bp.domain = 'BOOK'
                   AND bp.partition_key = sb.rating_source
            LEFT JOIN baseline_partitions fp ON fp.baseline_version = ? AND fp.domain = 'FILM'
                   AND fp.partition_key = CONCAT(FLOOR(fa.release_year / 10) * 10, 's')
            LEFT JOIN economic_index cr ON cr.year = LEAST(fa.release_year, (SELECT MAX(year) FROM economic_index))
            LEFT JOIN economic_index cb ON cb.year = LEAST(?, (SELECT MAX(year) FROM economic_index))
            WHERE l.link_status = 'ACCEPTED'
              AND y.book_year IS NOT NULL AND fa.release_year >= y.book_year
            """;

        try (PreparedStatement ps = conn.prepareStatement(stepASql)) {
            ps.setInt(1, appConfig.minBookRatings());
            ps.setDouble(2, appConfig.bookPriorWeight());
            ps.setDouble(3, appConfig.bookPriorWeight());
            ps.setInt(4, appConfig.minFilmVotes());
            ps.setDouble(5, appConfig.filmPriorWeight());
            ps.setDouble(6, appConfig.filmPriorWeight());
            if (baselineVersion != null) {
                ps.setInt(7, baselineVersion);
            } else {
                ps.setNull(7, Types.INTEGER);
            }
            ps.setLong(8, snapshotRunId);
            if (baselineVersion != null) {
                ps.setInt(9, baselineVersion);
                ps.setInt(10, baselineVersion);
            } else {
                ps.setNull(9, Types.INTEGER);
                ps.setNull(10, Types.INTEGER);
            }
            ps.setInt(11, appConfig.baseCpiYear());
            return ps.executeUpdate();
        }
    }

    private void executeStepB(Connection conn, Integer baselineVersion) throws SQLException {
        // 1. Compute book_rank via indexed range counts (Section 7.4 Step B)
        String updateBookRankSql = """
            UPDATE adaptation_snapshot_next s
            SET s.book_rank = (
                  (SELECT COUNT(*) FROM baseline_scores b
                    WHERE b.baseline_version = s.baseline_version AND b.domain = 'BOOK'
                      AND b.partition_key = s.book_partition AND b.shrunk_score < s.book_shrunk_score)
                + 0.5 * (SELECT COUNT(*) FROM baseline_scores b
                    WHERE b.baseline_version = s.baseline_version AND b.domain = 'BOOK'
                      AND b.partition_key = s.book_partition AND b.shrunk_score = s.book_shrunk_score)
                ) / s.book_baseline_n
            WHERE s.book_shrunk_score IS NOT NULL AND s.book_baseline_n > 0
            """;

        // 2. Compute film_rank via indexed range counts
        String updateFilmRankSql = """
            UPDATE adaptation_snapshot_next s
            SET s.film_rank = (
                  (SELECT COUNT(*) FROM baseline_scores b
                    WHERE b.baseline_version = s.baseline_version AND b.domain = 'FILM'
                      AND b.partition_key = s.film_partition AND b.shrunk_score < s.film_shrunk_score)
                + 0.5 * (SELECT COUNT(*) FROM baseline_scores b
                    WHERE b.baseline_version = s.baseline_version AND b.domain = 'FILM'
                      AND b.partition_key = s.film_partition AND b.shrunk_score = s.film_shrunk_score)
                ) / s.film_baseline_n
            WHERE s.film_shrunk_score IS NOT NULL AND s.film_baseline_n > 0
            """;

        // 3. Set rank_divergence where both ranks exist and partition sizes clear MIN_PARTITION
        String updateDivergenceSql = """
            UPDATE adaptation_snapshot_next
               SET rank_divergence = book_rank - film_rank
             WHERE book_rank IS NOT NULL AND film_rank IS NOT NULL
               AND book_baseline_n >= ? AND film_baseline_n >= ?
            """;

        // 4. Assign insufficient_reason in strict precedence order (Section 5.2):
        // NO_BASELINE -> BOOK_UNQUALIFIED -> FILM_UNQUALIFIED -> SMALL_PARTITION
        String updateReasonsSql = """
            UPDATE adaptation_snapshot_next s
            JOIN source_books sb ON sb.book_id = s.book_id
            JOIN film_adaptations fa ON fa.movie_id = s.movie_id
               SET s.insufficient_reason = CASE
                   WHEN s.baseline_version IS NULL OR s.book_baseline_n IS NULL OR s.film_baseline_n IS NULL
                        THEN 'NO_BASELINE'
                   WHEN sb.ratings_count < ? OR sb.avg_rating_5 IS NULL
                        THEN 'BOOK_UNQUALIFIED'
                   WHEN fa.tmdb_vote_count < ? OR fa.tmdb_score_10 IS NULL
                        THEN 'FILM_UNQUALIFIED'
                   WHEN s.book_baseline_n < ? OR s.film_baseline_n < ?
                        THEN 'SMALL_PARTITION'
                   ELSE NULL
               END
            """;

        try (Statement st = conn.createStatement()) {
            st.executeUpdate(updateBookRankSql);
            st.executeUpdate(updateFilmRankSql);
        }

        try (PreparedStatement ps = conn.prepareStatement(updateDivergenceSql)) {
            ps.setInt(1, appConfig.minPartitionSize());
            ps.setInt(2, appConfig.minPartitionSize());
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(updateReasonsSql)) {
            ps.setInt(1, appConfig.minBookRatings());
            ps.setInt(2, appConfig.minFilmVotes());
            ps.setInt(3, appConfig.minPartitionSize());
            ps.setInt(4, appConfig.minPartitionSize());
            ps.executeUpdate();
        }
    }

    private BigDecimal executeStepCAndCalibrate(Connection conn) throws SQLException {
        String countQualifyingSql = """
            SELECT COUNT(*) AS cnt
              FROM adaptation_snapshot_next
             WHERE rank_divergence IS NOT NULL AND insufficient_reason IS NULL
            """;
        int qualifyingCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(countQualifyingSql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                qualifyingCount = rs.getInt("cnt");
            }
        }

        if (qualifyingCount < appConfig.minCalibrationLinks()) {
            // Section 5.2 & 7.4: Fewer than MIN_CALIBRATION_LINKS qualifying rows -> NOT_CALIBRATED
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("""
                    UPDATE adaptation_snapshot_next
                       SET insufficient_reason = 'NOT_CALIBRATED'
                     WHERE insufficient_reason IS NULL
                    """);
                st.executeUpdate("""
                    UPDATE adaptation_snapshot_next
                       SET divergence_category = 'INSUFFICIENT_DATA'
                    """);
            }
            return null;
        }

        // Exact Step C PERCENT_RANK() calibration query from Section 7.4
        String calibrateSql = """
            SELECT MIN(a) AS d FROM (
              SELECT ABS(rank_divergence) AS a,
                     PERCENT_RANK() OVER (ORDER BY ABS(rank_divergence)) AS pr
                FROM adaptation_snapshot_next
               WHERE rank_divergence IS NOT NULL AND insufficient_reason IS NULL
            ) t
            WHERE pr >= ?
            """;

        BigDecimal d = null;
        double percentileTarget = 1.0 - appConfig.tailTarget();
        try (PreparedStatement ps = conn.prepareStatement(calibrateSql)) {
            ps.setDouble(1, percentileTarget);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    d = rs.getBigDecimal("d");
                }
            }
        }

        if (d == null) {
            d = BigDecimal.valueOf(0.3000).setScale(4, RoundingMode.HALF_UP);
        } else {
            d = d.setScale(4, RoundingMode.HALF_UP);
        }

        // Pre-negated -d in Java so SQL never contains a hand-negated bind parameter (Section 7.4)
        BigDecimal negD = d.negate();

        String classifySql = """
            UPDATE adaptation_snapshot_next
            SET divergence_category = CASE
                  WHEN insufficient_reason IS NOT NULL THEN 'INSUFFICIENT_DATA'
                  WHEN rank_divergence >= ? THEN 'BOOK_OUTRANKS_FILM'
                  WHEN rank_divergence <= ? THEN 'FILM_OUTRANKS_BOOK'
                  ELSE 'ALIGNED' END
            """;
        try (PreparedStatement ps = conn.prepareStatement(classifySql)) {
            ps.setBigDecimal(1, d);
            ps.setBigDecimal(2, negD);
            ps.executeUpdate();
        }
        return d;
    }

    private BigDecimal computeFlaggedShare(Connection conn) throws SQLException {
        String sql = """
            SELECT CASE WHEN COUNT(*) = 0 THEN NULL
                        ELSE ROUND(SUM(CASE WHEN divergence_category IN ('BOOK_OUTRANKS_FILM', 'FILM_OUTRANKS_BOOK') THEN 1 ELSE 0 END) / COUNT(*), 4)
                   END AS share
              FROM adaptation_snapshot_next
             WHERE insufficient_reason IS NULL
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getBigDecimal("share") : null;
        }
    }

    private void swapTablesWithRetry(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("SET SESSION lock_wait_timeout = 10");
        }
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (Statement st = conn.createStatement()) {
                st.execute("RENAME TABLE adaptation_snapshot TO adaptation_snapshot_old, adaptation_snapshot_next TO adaptation_snapshot");
                return;
            } catch (SQLException ex) {
                if (attempt == maxRetries) {
                    throw ex;
                }
                try {
                    Thread.sleep(attempt * 500L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    private Integer lookupLatestFrozenBaseline(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT MAX(version_id) FROM baseline_versions WHERE status = 'FROZEN'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
            return null;
        }
    }

    private long insertSnapshotRunReceipt(Connection conn, Integer baselineVersion) throws SQLException {
        String sql = "INSERT INTO snapshot_runs (baseline_version, status, started_at) VALUES (?, 'BUILDING', NOW())";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            if (baselineVersion != null) ps.setInt(1, baselineVersion);
            else ps.setNull(1, Types.INTEGER);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void markSnapshotBuilt(Connection conn, long runId, int rowCount, BigDecimal threshold, BigDecimal flaggedShare) throws SQLException {
        String sql = """
            UPDATE snapshot_runs
               SET status = 'BUILT', built_at = NOW(), row_count = ?, divergence_threshold = ?, flagged_share = ?
             WHERE snapshot_run_id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, rowCount);
            if (threshold != null) ps.setBigDecimal(2, threshold);
            else ps.setNull(2, Types.DECIMAL);
            if (flaggedShare != null) ps.setBigDecimal(3, flaggedShare);
            else ps.setNull(3, Types.DECIMAL);
            ps.setLong(4, runId);
            ps.executeUpdate();
        }
    }

    private void markSnapshotSwapped(Connection conn, long runId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE snapshot_runs SET status = 'SWAPPED', swapped_at = NOW() WHERE snapshot_run_id = ?")) {
            ps.setLong(1, runId);
            ps.executeUpdate();
        }
    }

    private void markSnapshotFailed(Connection conn, long runId) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE snapshot_runs SET status = 'FAILED' WHERE snapshot_run_id = ?")) {
            ps.setLong(1, runId);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }
}