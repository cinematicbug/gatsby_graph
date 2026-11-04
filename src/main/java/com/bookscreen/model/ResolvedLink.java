package com.bookscreen.model;

import com.bookscreen.model.Enums.LinkStatus;
import java.math.BigDecimal;

public record ResolvedLink(
        String openLibraryWorkId,
        long tmdbId,
        BigDecimal matchConfidence,
        String matcherVersion,
        LinkStatus linkStatus,
        MatchEvidence evidence
) {
    public record MatchEvidence(
            double titleSimilarityT,
            double authorEvidenceA,
            double keywordSignalK,
            double plotOverlapP,
            boolean chronologicalGatePassed,
            boolean titleCompatible,
            boolean seriesTitleMatch,
            Integer effectiveBookYear,
            int filmReleaseYear
    ) {}
}