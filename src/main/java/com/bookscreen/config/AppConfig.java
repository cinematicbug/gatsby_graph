package com.bookscreen.config;

import java.time.Duration;

public record AppConfig(
        String jdbcUrl,
        String dbUser,
        String dbPassword,
        String tmdbBearerToken,
        String googleBooksApiKey,
        String openLibraryUserAgent,
        int minBookRatings,
        int minFilmVotes,
        int minPartitionSize,
        int minCalibrationLinks,
        double bookPriorWeight,
        double filmPriorWeight,
        double tailTarget,
        double studioShare,
        int candidateCapPerBook,
        int maxApiCallsPerSync,
        int baseCpiYear,
        String matcherVersion,
        double acceptedThreshold,
        double reviewThreshold,
        Duration bookRatingsTtl,
        Duration bookMetadataTtl,
        Duration recentFilmDetailsTtl,
        Duration olderFilmDetailsTtl,
        Duration negativeLookupTtl,
        Duration syncLeaseDuration
) {
    public static AppConfig fromEnv() {
        return new AppConfig(
                env("DB_URL", "jdbc:mysql://localhost:3306/adaptation_divergence?rewriteBatchedStatements=true&useServerPrepStmts=true"),
                env("DB_USER", "root"),
                env("DB_PASS", "password"),
                env("TMDB_BEARER_TOKEN", ""),
                env("GOOGLE_BOOKS_API_KEY", ""),
                env("OPENLIBRARY_USER_AGENT", "BookScreenDivergenceEngine/2.1 (contact@example.com)"),
                envInt("MIN_BOOK_RATINGS", 25),
                envInt("MIN_FILM_VOTES", 100),
                envInt("MIN_PARTITION", 200),
                envInt("MIN_CALIBRATION_LINKS", 300),
                envDouble("BOOK_PRIOR_WEIGHT", 50.0),
                envDouble("FILM_PRIOR_WEIGHT", 100.0),
                envDouble("TAIL_TARGET", 0.20),
                envDouble("STUDIO_SHARE", 0.50),
                envInt("CANDIDATE_CAP_PER_BOOK", 8),
                envInt("MAX_API_CALLS_PER_SYNC", 400),
                envInt("BASE_CPI_YEAR", 2026),
                env("MATCHER_VERSION", "v2.1.0"),
                envDouble("MATCHER_ACCEPTED_THRESHOLD", 0.70),
                envDouble("MATCHER_REVIEW_THRESHOLD", 0.40),
                Duration.ofDays(envInt("TTL_BOOK_RATINGS_DAYS", 7)),
                Duration.ofDays(envInt("TTL_BOOK_META_DAYS", 30)),
                Duration.ofDays(envInt("TTL_RECENT_FILM_DAYS", 7)),
                Duration.ofDays(envInt("TTL_OLDER_FILM_DAYS", 30)),
                Duration.ofDays(envInt("TTL_NEGATIVE_DAYS", 90)),
                Duration.ofMinutes(15)
        );
    }

    private static String env(String key, String defaultVal) {
        String val = System.getenv(key);
        return (val == null || val.isBlank()) ? defaultVal : val.trim();
    }

    private static int envInt(String key, int defaultVal) {
        String val = System.getenv(key);
        return (val == null || val.isBlank()) ? defaultVal : Integer.parseInt(val.trim());
    }

    private static double envDouble(String key, double defaultVal) {
        String val = System.getenv(key);
        return (val == null || val.isBlank()) ? defaultVal : Double.parseDouble(val.trim());
    }
}