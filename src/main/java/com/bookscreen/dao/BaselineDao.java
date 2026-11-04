package com.bookscreen.dao;

import com.bookscreen.config.AppConfig;
import com.bookscreen.config.DatabaseConfig;
import com.bookscreen.model.Enums.Domain;

import java.math.BigDecimal;
import java.sql.*;

public final class BaselineDao {

    private final DatabaseConfig dbConfig;
    private final AppConfig appConfig;

    public BaselineDao(DatabaseConfig dbConfig, AppConfig appConfig) {
        this.dbConfig = dbConfig;
        this.appConfig = appConfig;
    }

    public int createBuildingVersion(String paramsJson) throws SQLException {
        String sql = "INSERT INTO baseline_versions (status, params) VALUES ('BUILDING', ?)";
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, paramsJson);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Inserts a candidate baseline score only if it clears the minimum floor and has no ACCEPTED adaptation link (Section 4.3).
     */
    public void upsertBaselineEntryIfUnlinked(
            int versionId,
            Domain domain,
            String entityRef,
            String partitionKey,
            BigDecimal rawScore,
            int scoreCount
    ) throws SQLException {
        if (rawScore == null) return;
        if (domain == Domain.BOOK && scoreCount < appConfig.minBookRatings()) return;
        if (domain == Domain.FILM && scoreCount < appConfig.minFilmVotes()) return;

        String checkLinkedSql = (domain == Domain.BOOK)
                ? """
                  SELECT 1 FROM adaptation_links l
                  JOIN source_books sb ON sb.book_id = l.book_id
                  WHERE sb.openlibrary_work_id = ? AND l.link_status = 'ACCEPTED' LIMIT 1
                  """
                : """
                  SELECT 1 FROM adaptation_links l
                  JOIN film_adaptations fa ON fa.movie_id = l.movie_id
                  WHERE fa.tmdb_id = ? AND l.link_status = 'ACCEPTED' LIMIT 1
                  """;

        String insertSql = """
            INSERT INTO baseline_scores
              (baseline_version, domain, entity_ref, partition_key, raw_score, score_count)
            VALUES (?, ?, ?, ?, ?, ?) AS new
            ON DUPLICATE KEY UPDATE
              raw_score = new.raw_score,
              score_count = new.score_count
            """;

        try (Connection conn = dbConfig.openAutoCommitConnection()) {
            try (PreparedStatement checkPs = conn.prepareStatement(checkLinkedSql)) {
                if (domain == Domain.BOOK) {
                    checkPs.setString(1, entityRef);
                } else {
                    checkPs.setLong(1, Long.parseLong(entityRef));
                }
                try (ResultSet rs = checkPs.executeQuery()) {
                    if (rs.next()) {
                        return; // Exclude known ACCEPTED adaptations from baseline (Section 4.3)
                    }
                }
            }

            try (PreparedStatement insPs = conn.prepareStatement(insertSql)) {
                insPs.setInt(1, versionId);
                insPs.setString(2, domain.name());
                insPs.setString(3, entityRef);
                insPs.setString(4, partitionKey);
                insPs.setBigDecimal(5, rawScore);
                insPs.setInt(6, scoreCount);
                insPs.executeUpdate();
            }
        }
    }

    /**
     * Populates baseline_partitions, computes shrunk_score for all baseline rows, and freezes the version (Section 4.3 & 5.2).
     */
    public void computePartitionsAndFreeze(int versionId) throws SQLException {
        String populatePartitionsSql = """
            INSERT INTO baseline_partitions (baseline_version, domain, partition_key, n, mean_raw)
            SELECT baseline_version, domain, partition_key, COUNT(*), ROUND(AVG(raw_score), 4)
              FROM baseline_scores
             WHERE baseline_version = ?
             GROUP BY baseline_version, domain, partition_key
            ON DUPLICATE KEY UPDATE n = VALUES(n), mean_raw = VALUES(mean_raw)
            """;

        String shrinkBooksSql = """
            UPDATE baseline_scores bs
            JOIN baseline_partitions bp
              ON bp.baseline_version = bs.baseline_version
             AND bp.domain = bs.domain
             AND bp.partition_key = bs.partition_key
               SET bs.shrunk_score = ROUND((? * bp.mean_raw + bs.score_count * bs.raw_score) / (? + bs.score_count), 4)
             WHERE bs.baseline_version = ? AND bs.domain = 'BOOK'
            """;

        String shrinkFilmsSql = """
            UPDATE baseline_scores bs
            JOIN baseline_partitions bp
              ON bp.baseline_version = bs.baseline_version
             AND bp.domain = bs.domain
             AND bp.partition_key = bs.partition_key
               SET bs.shrunk_score = ROUND((? * bp.mean_raw + bs.score_count * bs.raw_score) / (? + bs.score_count), 4)
             WHERE bs.baseline_version = ? AND bs.domain = 'FILM'
            """;

        String freezeSql = "UPDATE baseline_versions SET status = 'FROZEN', frozen_at = NOW() WHERE version_id = ?";

        try (Connection conn = dbConfig.openTransactionalConnection()) {
            try {
                try (PreparedStatement ps = conn.prepareStatement(populatePartitionsSql)) {
                    ps.setInt(1, versionId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(shrinkBooksSql)) {
                    ps.setDouble(1, appConfig.bookPriorWeight());
                    ps.setDouble(2, appConfig.bookPriorWeight());
                    ps.setInt(3, versionId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(shrinkFilmsSql)) {
                    ps.setDouble(1, appConfig.filmPriorWeight());
                    ps.setDouble(2, appConfig.filmPriorWeight());
                    ps.setInt(3, versionId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(freezeSql)) {
                    ps.setInt(1, versionId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    public Integer findLatestFrozenVersion() throws SQLException {
        String sql = "SELECT MAX(version_id) AS v FROM baseline_versions WHERE status = 'FROZEN'";
        try (Connection conn = dbConfig.openAutoCommitConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                int v = rs.getInt("v");
                return rs.wasNull() ? null : v;
            }
            return null;
        }
    }
}