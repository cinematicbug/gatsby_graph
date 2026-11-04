package com.bookscreen.model;

import java.math.BigDecimal;
import java.time.Instant;

public record SnapshotRow(
        long linkId,
        long bookId,
        long movieId,
        String bookTitle,
        String filmTitle,
        int adaptationDelayYears,
        BigDecimal pagesPerMinute,
        BigDecimal bookRank,
        BigDecimal filmRank,
        BigDecimal rankDivergence,
        BigDecimal grossMultiple,
        Long grossAdjUsd,
        String bookReliability,
        String divergenceCategory,
        String insufficientReason,
        Integer baselineVersion,
        Instant refreshedAt
) {}