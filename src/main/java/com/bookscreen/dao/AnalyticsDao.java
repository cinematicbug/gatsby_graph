package com.bookscreen.dao;

import com.bookscreen.config.DatabaseConfig;
import com.bookscreen.model.Enums.DivergenceCategory;
import com.bookscreen.model.SnapshotRow;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class AnalyticsDao {

    public record ReviewQueueItem(
            long linkId,
            String bookTitle,
            String filmTitle,
            BigDecimal matchConfidence,
            String evidenceJson
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
            ps.setQueryTimeout(60); // Section 7.4 reader cap
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
}