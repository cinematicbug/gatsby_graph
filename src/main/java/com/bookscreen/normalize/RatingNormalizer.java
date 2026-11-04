package com.bookscreen.normalize;

import com.bookscreen.model.EditionRecord;
import com.bookscreen.model.Enums.BookReliability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class RatingNormalizer {

    private RatingNormalizer() {}

    /**
     * Bayesian shrinkage formula (Section 5.2):
     * s = (m * mu + n * x) / (m + n), scaled to DECIMAL(6,4).
     */
    public static BigDecimal computeShrunkScore(
            BigDecimal rawMeanX,
            int sampleCountN,
            int minSampleFloor,
            BigDecimal partitionMeanMu,
            double priorWeightM
    ) {
        if (rawMeanX == null || partitionMeanMu == null || sampleCountN < minSampleFloor) {
            return null;
        }
        double x = rawMeanX.doubleValue();
        double mu = partitionMeanMu.doubleValue();
        double shrunk = (priorWeightM * mu + sampleCountN * x) / (priorWeightM + sampleCountN);
        return BigDecimal.valueOf(shrunk).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Reliability tier (Section 4.1): LOW below 100 ratings, OK at 100 or more.
     */
    public static BookReliability classifyReliability(int ratingsCount) {
        return ratingsCount >= 100 ? BookReliability.OK : BookReliability.LOW;
    }

    /**
     * Recomputes the median page count across all editions that report page_count > 0 (Section 4.1).
     */
    public static Integer computeMedianPageCount(List<EditionRecord> editions) {
        if (editions == null || editions.isEmpty()) {
            return null;
        }
        List<Integer> validPages = editions.stream()
                .map(EditionRecord::pageCount)
                .filter(p -> p != null && p > 0)
                .sorted()
                .toList();

        if (validPages.isEmpty()) {
            return null;
        }
        int size = validPages.size();
        int mid = size / 2;
        if (size % 2 == 1) {
            return validPages.get(mid);
        } else {
            return Math.round((validPages.get(mid - 1) + validPages.get(mid)) / 2.0f);
        }
    }

    /**
     * Computes earliest publish year across editions when work-level first_publish_year is missing (Section 5.1).
     */
    public static Integer findEarliestEditionYear(List<EditionRecord> editions) {
        if (editions == null || editions.isEmpty()) {
            return null;
        }
        return editions.stream()
                .map(EditionRecord::publishYear)
                .filter(y -> y != null && y > 1000)
                .min(Integer::compareTo)
                .orElse(null);
    }

    /**
     * Reference mid-rank percentile calculation (Section 5.2 & Section 10 differential tests):
     * R = (countBelow + 0.5 * countEqual) / totalN
     */
    public static BigDecimal computeMidRank(BigDecimal targetShrunkScore, List<BigDecimal> baselineShrunkScores) {
        if (targetShrunkScore == null || baselineShrunkScores == null || baselineShrunkScores.isEmpty()) {
            return null;
        }
        long below = 0;
        long equal = 0;
        for (BigDecimal base : baselineShrunkScores) {
            if (base == null) continue;
            int cmp = base.compareTo(targetShrunkScore);
            if (cmp < 0) {
                below++;
            } else if (cmp == 0) {
                equal++;
            }
        }
        double rank = (below + 0.5 * equal) / baselineShrunkScores.size();
        return BigDecimal.valueOf(rank).setScale(4, RoundingMode.HALF_UP);
    }
}