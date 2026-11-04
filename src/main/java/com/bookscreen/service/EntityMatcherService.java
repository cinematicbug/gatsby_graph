package com.bookscreen.service;

import com.bookscreen.config.AppConfig;
import com.bookscreen.model.BookPayload;
import com.bookscreen.model.Enums.LinkStatus;
import com.bookscreen.model.FilmPayload;
import com.bookscreen.model.ResolvedLink;
import com.bookscreen.model.ResolvedLink.MatchEvidence;
import com.bookscreen.normalize.TitleNormalizer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class EntityMatcherService {

    private static final Set<String> ADAPTATION_KEYWORDS = Set.of(
            "based on novel or book",
            "based on novel",
            "based on book",
            "based on short story",
            "based on novella",
            "based on young adult novel",
            "based on play",
            "based on work",
            "literary adaptation"
    );

    private static final Set<String> SOURCE_CREDIT_JOBS = Set.of(
            "novel", "author", "original story", "book", "short story",
            "characters", "graphic novel", "writer", "screenplay", "story", "based on"
    );

    public record MatcherConfig(
            String matcherVersion,
            double acceptedThreshold,
            double reviewThreshold
    ) {
        public static MatcherConfig fromAppConfig(AppConfig cfg) {
            return new MatcherConfig(
                    cfg.matcherVersion(),
                    cfg.acceptedThreshold(),
                    cfg.reviewThreshold()
            );
        }
    }

    /**
     * Pure function scoring BookPayload against FilmPayload with zero network or DB calls (Section 5.1).
     */
    public ResolvedLink score(BookPayload book, FilmPayload film, MatcherConfig config) {
        Integer effectiveBookYear = book.effectivePublishYear();
        int filmYear = film.releaseYear();

        // Hard Gate: Y_film >= Y_book (Section 5.1)
        boolean gatePassed = effectiveBookYear != null && filmYear >= effectiveBookYear;

        double tScore = TitleNormalizer.computeTitleSimilarity(book.title(), film.title());
        double aScore = computeAuthorSignal(book.authorNames(), film.writingCredits());
        double kScore = computeKeywordSignal(film.keywords());
        double pScore = computePlotOverlapSignal(book.description(), film.overview());

        double rawConfidence = (0.40 * tScore) + (0.35 * aScore) + (0.15 * kScore) + (0.10 * pScore);
        BigDecimal confidence = BigDecimal.valueOf(Math.min(1.0, Math.max(0.0, rawConfidence)))
                .setScale(2, RoundingMode.HALF_UP);

        LinkStatus status;
        if (!gatePassed || confidence.doubleValue() < config.reviewThreshold()) {
            status = LinkStatus.REJECTED; // Discarded / below REVIEW band
        } else if (confidence.doubleValue() >= config.acceptedThreshold()) {
            status = LinkStatus.ACCEPTED;
        } else {
            status = LinkStatus.REVIEW;
        }

        MatchEvidence evidence = new MatchEvidence(
                round4(tScore),
                round4(aScore),
                round4(kScore),
                round4(pScore),
                gatePassed,
                effectiveBookYear,
                filmYear
        );

        return new ResolvedLink(
                book.openLibraryWorkId(),
                film.tmdbId(),
                confidence,
                config.matcherVersion(),
                status,
                evidence
        );
    }

    /**
     * Signal A (Section 5.1):
     * 1.0 if full normalized author name appears in TMDB writing/source credits,
     * 0.5 if author surname (length >= 4) matches a writing credit surname,
     * 0.0 otherwise.
     */
    private double computeAuthorSignal(List<String> bookAuthors, List<FilmPayload.CrewCredit> credits) {
        if (bookAuthors == null || bookAuthors.isEmpty() || credits == null || credits.isEmpty()) {
            return 0.0;
        }

        double bestSignal = 0.0;
        for (String rawAuthor : bookAuthors) {
            String normAuthor = TitleNormalizer.normalizePersonName(rawAuthor);
            if (normAuthor.isEmpty()) continue;

            String[] authorParts = normAuthor.split(" ");
            String authorSurname = authorParts[authorParts.length - 1];

            for (FilmPayload.CrewCredit credit : credits) {
                if (!isRelevantCredit(credit)) {
                    continue;
                }
                String normCrew = TitleNormalizer.normalizePersonName(credit.name());
                if (normCrew.isEmpty()) continue;

                if (normCrew.equals(normAuthor) || normCrew.contains(normAuthor) || normAuthor.contains(normCrew)) {
                    return 1.0;
                }

                String[] crewParts = normCrew.split(" ");
                String crewSurname = crewParts[crewParts.length - 1];
                if (authorSurname.length() >= 4 && authorSurname.equals(crewSurname)) {
                    bestSignal = Math.max(bestSignal, 0.5);
                }
            }
        }
        return bestSignal;
    }

    private boolean isRelevantCredit(FilmPayload.CrewCredit credit) {
        String dept = credit.department() == null ? "" : credit.department().toLowerCase(Locale.ROOT);
        String job = credit.job() == null ? "" : credit.job().toLowerCase(Locale.ROOT);
        if (dept.contains("writing") || dept.contains("creator")) {
            return true;
        }
        for (String validJob : SOURCE_CREDIT_JOBS) {
            if (job.contains(validJob)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Signal K (Section 5.1): 1.0 if TMDB keywords contain a 'based on novel or book' family keyword, else 0.0.
     */
    private double computeKeywordSignal(List<String> filmKeywords) {
        if (filmKeywords == null || filmKeywords.isEmpty()) {
            return 0.0;
        }
        for (String kw : filmKeywords) {
            if (kw == null) continue;
            String lower = kw.toLowerCase(Locale.ROOT).trim();
            if (ADAPTATION_KEYWORDS.contains(lower)
                    || lower.contains("based on novel")
                    || lower.contains("based on book")
                    || lower.contains("based on a novel")) {
                return 1.0;
            }
        }
        return 0.0;
    }

    /**
     * Signal P (Section 5.1): Token overlap between film overview and book description (0.0 to 1.0).
     * Uses Szymkiewicz-Simpson / scaled Jaccard overlap so long book blurbs vs short TMDB overviews score fairly.
     */
    private double computePlotOverlapSignal(String bookDescription, String filmOverview) {
        Set<String> bookTokens = TitleNormalizer.tokenizeContent(bookDescription);
        Set<String> filmTokens = TitleNormalizer.tokenizeContent(filmOverview);

        if (bookTokens.isEmpty() || filmTokens.isEmpty()) {
            return 0.0;
        }

        long shared = bookTokens.stream().filter(filmTokens::contains).count();
        if (shared == 0) {
            return 0.0;
        }

        int minSize = Math.min(bookTokens.size(), filmTokens.size());
        double containment = (double) shared / minSize;
        // Scale so >= 30% content-token containment achieves full 1.0 plot signal
        return Math.min(1.0, containment / 0.30);
    }

    private static double round4(double val) {
        return Math.round(val * 10000.0) / 10000.0;
    }
}