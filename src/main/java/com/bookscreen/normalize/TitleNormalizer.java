package com.bookscreen.normalize;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class TitleNormalizer {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern EDITION_NOISE = Pattern.compile(
            "\\b(illustrated|unabridged|anniversary\\s+edition|collector'?s\\s+edition|special\\s+edition|deluxe\\s+edition|a\\s+novel|a\\s+memoir)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PART_INDICATOR = Pattern.compile(
            "\\b(part|vol|volume|book)\\s+(one|two|three|four|five|i|ii|iii|iv|v|\\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PARENTHETICAL_NOISE = Pattern.compile("\\([^)]*\\)|\\[[^]]*\\]");
    private static final Pattern LEADING_ARTICLE = Pattern.compile("^(the|a|an)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "of", "and", "in", "on", "to", "for", "with", "at", "by", "from", "is", "that", "his", "her"
    );

    /** Words a TMDB collection name contributes that never identify one specific book. */
    private static final Set<String> FRANCHISE_NOISE = Set.of(
            "collection", "saga", "series", "trilogy", "universe", "franchise", "presents"
    );

    private TitleNormalizer() {}

    /**
     * Normalizes a book or film title according to Section 5.1 rules.
     */
    public static String normalizeTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String working = Normalizer.normalize(raw, Normalizer.Form.NFD);
        working = DIACRITICS.matcher(working).replaceAll("");
        working = working.toLowerCase(Locale.ROOT);

        // Remove parenthetical edition/series tags before colon cutting
        working = PARENTHETICAL_NOISE.matcher(working).replaceAll(" ");

        // Strip part indicators ("Dune: Part One" -> "Dune")
        working = PART_INDICATOR.matcher(working).replaceAll(" ");

        // Cut subtitles after a colon (only if prefix before colon is non-empty)
        int colonIdx = working.indexOf(':');
        if (colonIdx > 0) {
            String prefix = working.substring(0, colonIdx).trim();
            if (!prefix.isEmpty()) {
                working = prefix;
            }
        }

        // Remove edition noise phrases
        working = EDITION_NOISE.matcher(working).replaceAll(" ");

        // Strip punctuation
        working = NON_ALPHANUMERIC.matcher(working).replaceAll(" ");
        working = MULTI_SPACE.matcher(working).replaceAll(" ").trim();

        // Drop leading article ("The Shining" -> "shining")
        working = LEADING_ARTICLE.matcher(working).replaceFirst("").trim();

        return working;
    }

    /**
     * Normalizes a person's name (author / crew member) for credit matching.
     */
    public static String normalizePersonName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }
        String working = Normalizer.normalize(rawName, Normalizer.Form.NFD);
        working = DIACRITICS.matcher(working).replaceAll("");
        working = NON_ALPHANUMERIC.matcher(working.toLowerCase(Locale.ROOT)).replaceAll(" ");
        return MULTI_SPACE.matcher(working).replaceAll(" ").trim();
    }

    /**
     * Tokenizes text into normalized non-stopword tokens (minimum length 3 for plot overlap).
     */
    public static Set<String> tokenizeContent(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }
        String cleaned = Normalizer.normalize(text, Normalizer.Form.NFD);
        cleaned = DIACRITICS.matcher(cleaned).replaceAll("");
        cleaned = NON_ALPHANUMERIC.matcher(cleaned.toLowerCase(Locale.ROOT)).replaceAll(" ");

        return Arrays.stream(MULTI_SPACE.split(cleaned.trim()))
                .filter(tok -> tok.length() >= 3 && !STOP_WORDS.contains(tok))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Computes T = max(Jaro-Winkler, token-set Jaccard) on normalized titles (Section 5.1).
     */
    public static double computeTitleSimilarity(String rawBookTitle, String rawFilmTitle) {
        String normBook = normalizeTitle(rawBookTitle);
        String normFilm = normalizeTitle(rawFilmTitle);

        if (normBook.isEmpty() || normFilm.isEmpty()) {
            return 0.0;
        }
        if (normBook.equals(normFilm)) {
            return 1.0;
        }

        double jw = jaroWinkler(normBook, normFilm);
        double jaccard = tokenSetJaccard(normBook, normFilm);
        return Math.min(1.0, Math.max(jw, jaccard));
    }

    /**
     * Meaningful (non-empty) normalized tokens of a title.
     */
    public static Set<String> titleTokens(String rawTitle) {
        String norm = normalizeTitle(rawTitle);
        if (norm.isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(MULTI_SPACE.split(norm))
                .filter(tok -> !tok.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Fraction of the book title's tokens that the film title also contains.
     * A sequel ("Fifty Shades Freed" for the book "Fifty Shades of Grey") scores ~0.5 here,
     * while the actual adaptation scores 1.0.
     */
    public static double bookTitleCoverage(String rawBookTitle, String rawFilmTitle) {
        Set<String> bookTokens = titleTokens(rawBookTitle);
        Set<String> filmTokens = titleTokens(rawFilmTitle);
        if (bookTokens.isEmpty() || filmTokens.isEmpty()) {
            return 0.0;
        }
        long shared = bookTokens.stream().filter(filmTokens::contains).count();
        return (double) shared / bookTokens.size();
    }

    /**
     * True when two titles can plausibly name the same underlying work: identical titles,
     * one title being a subtitle/shortened form of the other ("Dune" / "Dune: Part Two"),
     * or a spelling-level variant of a comparable title.
     *
     * Sequels and companion volumes deliberately fail this check so they can never be
     * auto-accepted as the adaptation of a different book in the same series.
     */
    public static boolean isCompatibleTitle(String rawBookTitle, String rawFilmTitle) {
        String book = normalizeTitle(rawBookTitle);
        String film = normalizeTitle(rawFilmTitle);
        if (book.isEmpty() || film.isEmpty()) {
            return false;
        }
        if (book.equals(film)) {
            return true;
        }

        Set<String> bookTokens = titleTokens(book);
        Set<String> filmTokens = titleTokens(film);
        if (bookTokens.isEmpty() || filmTokens.isEmpty()) {
            return false;
        }

        // One title is a subtitle / extended form of the other ("Dune" vs "Dune: Part Two").
        // The shorter title must still carry most of the words, so a one-word companion volume
        // such as "Grey" is not treated as the same work as "Fifty Shades of Grey".
        int smaller = Math.min(bookTokens.size(), filmTokens.size());
        int larger = Math.max(bookTokens.size(), filmTokens.size());
        if (smaller >= 2 && ((double) smaller / larger) >= 0.60
                && (filmTokens.containsAll(bookTokens) || bookTokens.containsAll(filmTokens))) {
            return true;
        }

        // Spelling and regional title variants ("Philosopher's" / "Sorcerer's", "Colour" / "Color"):
        // the titles must use the same number of words and share most of them. Sharing only a
        // prefix is not enough, which is what keeps sequels apart.
        if (bookTokens.size() == filmTokens.size()) {
            Set<String> intersection = new HashSet<>(bookTokens);
            intersection.retainAll(filmTokens);
            Set<String> union = new HashSet<>(bookTokens);
            union.addAll(filmTokens);
            if (!union.isEmpty() && ((double) intersection.size() / union.size()) >= 0.50) {
                return true;
            }
        }

        // Single-word spelling variants ("Grey" / "Gray") only need a tight edit distance.
        return bookTokens.size() == 1 && filmTokens.size() == 1 && jaroWinkler(book, film) >= 0.90;
    }

    /**
     * Removes the franchise prefix a TMDB collection name contributes to a film title, so the film
     * "The Twilight Saga: Eclipse" resolves to "eclipse" and can match the book "Eclipse".
     *
     * Returns null when the film has no collection or nothing franchise-specific to strip.
     */
    public static String stripCollectionPrefix(String rawFilmTitle, String rawCollectionName) {
        if (rawCollectionName == null || rawCollectionName.isBlank()) {
            return null;
        }
        // Stripping has to happen on the loose form: normalizeTitle() would cut
        // "The Twilight Saga: Eclipse" down to "the twilight saga" before the prefix is removed.
        String looseFilm = normalizeLoose(rawFilmTitle);
        String looseCollection = normalizeLoose(rawCollectionName);
        if (looseFilm.isEmpty() || looseCollection.isEmpty()) {
            return null;
        }

        Set<String> collectionWords = new HashSet<>(Arrays.asList(MULTI_SPACE.split(looseCollection)));
        collectionWords.addAll(FRANCHISE_NOISE);

        List<String> words = new ArrayList<>(Arrays.asList(MULTI_SPACE.split(looseFilm)));
        while (!words.isEmpty() && collectionWords.contains(words.get(0))) {
            words.remove(0);
        }
        while (!words.isEmpty() && FRANCHISE_NOISE.contains(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }

        // The film is named after its franchise ("The Hunger Games"): compare on the plain title.
        if (words.isEmpty()) {
            return normalizeTitle(rawFilmTitle);
        }

        String fragment = cleanTitleFragment(String.join(" ", words));
        return fragment.isEmpty() ? normalizeTitle(rawFilmTitle) : fragment;
    }

    /** Lowercase/diacritic/punctuation cleanup that keeps word order and never cuts subtitles. */
    private static String normalizeLoose(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String working = Normalizer.normalize(raw, Normalizer.Form.NFD);
        working = DIACRITICS.matcher(working).replaceAll("");
        working = working.toLowerCase(Locale.ROOT);
        working = PARENTHETICAL_NOISE.matcher(working).replaceAll(" ");
        working = NON_ALPHANUMERIC.matcher(working).replaceAll(" ");
        return MULTI_SPACE.matcher(working).replaceAll(" ").trim();
    }

    /** Final cleanup for a title fragment: drops part markers, edition noise and a leading article. */
    private static String cleanTitleFragment(String loose) {
        String working = PART_INDICATOR.matcher(loose).replaceAll(" ");
        working = EDITION_NOISE.matcher(working).replaceAll(" ");
        working = NON_ALPHANUMERIC.matcher(working).replaceAll(" ");
        working = MULTI_SPACE.matcher(working).replaceAll(" ").trim();
        return LEADING_ARTICLE.matcher(working).replaceFirst("").trim();
    }

    public static double tokenSetJaccard(String normA, String normB) {
        Set<String> setA = Arrays.stream(MULTI_SPACE.split(normA.trim()))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        Set<String> setB = Arrays.stream(MULTI_SPACE.split(normB.trim()))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        if (setA.isEmpty() || setB.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);

        return (double) intersection.size() / union.size();
    }

    public static double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 || len2 == 0) {
            return 0.0;
        }

        int matchDistance = Math.max(0, (Math.max(len1, len2) / 2) - 1);
        boolean[] s1Matches = new boolean[len1];
        boolean[] s2Matches = new boolean[len2];

        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, len2);
            for (int j = start; j < end; j++) {
                if (s2Matches[j] || s1.charAt(i) != s2.charAt(j)) {
                    continue;
                }
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) {
            return 0.0;
        }

        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!s1Matches[i]) {
                continue;
            }
            while (!s2Matches[k]) {
                k++;
            }
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }

        double m = matches;
        double jaro = ((m / len1) + (m / len2) + ((m - (transpositions / 2.0)) / m)) / 3.0;

        int prefix = 0;
        int maxPrefix = Math.min(4, Math.min(len1, len2));
        for (int i = 0; i < maxPrefix; i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }

        return jaro + (prefix * 0.1 * (1.0 - jaro));
    }
}