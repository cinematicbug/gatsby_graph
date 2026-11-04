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