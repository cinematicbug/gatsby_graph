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
import java.util.Collections;
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

    /**
     * Only credits that point at the underlying book count as author evidence. Screenwriters,
     * "story by" and "characters" credits belong to the film, not to the novelist.
     */
    private static final Set<String> SOURCE_CREDIT_JOBS = Set.of(
            "novel", "author", "original story", "book", "short story", "graphic novel"
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

        // Every book in a series shares the novelist and the "based on novel" keyword, so titles
        // are the only per-work discriminator available. TMDB names franchise entries with the
        // collection as a prefix ("The Twilight Saga: Eclipse"), so the comparison is retried with
        // that prefix stripped and the stronger reading wins. A sequel therefore matches its own
        // book, while "Fifty Shades Freed" still never matches "Fifty Shades of Grey".
        double tScore = TitleNormalizer.computeTitleSimilarity(book.title(), film.title());
        boolean titleCompatible = TitleNormalizer.isCompatibleTitle(book.title(), film.title());
        boolean seriesTitleMatch = false;

        String strippedFilmTitle = TitleNormalizer.stripCollectionPrefix(film.title(), film.collectionName());
        if (strippedFilmTitle != null
                && !strippedFilmTitle.equals(TitleNormalizer.normalizeTitle(film.title()))) {
            double seriesTScore = TitleNormalizer.computeTitleSimilarity(book.title(), strippedFilmTitle);
            if (seriesTScore > tScore) {
                tScore = seriesTScore;
            }
            seriesTitleMatch = TitleNormalizer.isCompatibleTitle(book.title(), strippedFilmTitle);
            titleCompatible = titleCompatible || seriesTitleMatch;
        }

        double aScore = computeAuthorSignal(book.authorNames(), film.writingCredits());
        double kScore = computeKeywordSignal(film.keywords());
        double pScore = computePlotOverlapSignal(book.description(), film.overview());

        double rawConfidence = (0.40 * tScore) + (0.35 * aScore) + (0.15 * kScore) + (0.10 * pScore);
        BigDecimal confidence = BigDecimal.valueOf(Math.min(1.0, Math.max(0.0, rawConfidence)))
                .setScale(2, RoundingMode.HALF_UP);

        LinkStatus status;
        if (!gatePassed || confidence.doubleValue() < config.reviewThreshold()) {
            status = LinkStatus.REJECTED; // Discarded / below REVIEW band
        } else if (confidence.doubleValue() >= config.acceptedThreshold() && titleCompatible) {
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
                titleCompatible,
                seriesTitleMatch,
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

                if (namesContain(normCrew, normAuthor) || namesContain(normAuthor, normCrew)) {
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

    /**
     * Token-boundary name containment ("e l james" inside "e l james"), so a surname fragment
     * shared with an unrelated credit cannot produce full author evidence.
     */
    private static boolean namesContain(String haystack, String needle) {
        List<String> haystackTokens = List.of(haystack.split(" "));
        List<String> needleTokens = List.of(needle.split(" "));
        return Collections.indexOfSubList(haystackTokens, needleTokens) >= 0;
    }

    private boolean isRelevantCredit(FilmPayload.CrewCredit credit) {
        // The whole "Writing" department also contains screenwriters, so job titles are the only
        // trustworthy indicator that a credit belongs to the source novel.
        String job = credit.job() == null ? "" : credit.job().toLowerCase(Locale.ROOT);
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