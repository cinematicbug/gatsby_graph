package com.bookscreen.model;

import java.math.BigDecimal;

public record FilmCandidate(
        long tmdbId,
        String imdbId,
        String title,
        int releaseYear,
        Integer runtimeMinutes,
        Long productionBudget,
        Long boxOfficeGross,
        BigDecimal tmdbScore10,
        int tmdbVoteCount,
        FilmPayload payload
) {}