package com.bookscreen.client;

import com.bookscreen.config.AppConfig;
import com.bookscreen.model.*;
import com.bookscreen.model.Enums.RatingSource;
import com.bookscreen.normalize.RatingNormalizer;
import com.bookscreen.normalize.TitleNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OpenLibraryClient {

    private static final String BASE_URL = "https://openlibrary.org";
    private static final Pattern FOUR_DIGIT_YEAR = Pattern.compile("\\b(1[4-9]\\d{2}|20\\d{2})\\b");

    /** Years outside this window are catalogue noise, not publication dates. */
    private static final int MIN_PLAUSIBLE_YEAR = 1400;

    /**
     * A year only becomes canonical once at least two editions agree on it, so a single
     * mis-catalogued reprint cannot outrank the real first publication year.
     */
    private static final int YEAR_CORROBORATION = 2;

    private final RateLimitedHttpGateway gateway;
    private final Map<String, String> headers;

    private record EditionFetch(List<EditionRecord> editions, Integer coverId) {}

    public OpenLibraryClient(RateLimitedHttpGateway gateway, AppConfig config) {
        this.gateway = gateway;
        this.headers = Map.of(
                "User-Agent", config.openLibraryUserAgent(),
                "Accept", "application/json"
        );
    }

    /**
     * Resolves an author search string and fetches up to maxWorks BookBundles for that author.
     */
    public List<BookBundle> fetchWorksByAuthorQuery(String authorQuery, int maxWorks)
            throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(authorQuery, StandardCharsets.UTF_8);
        URI searchUri = URI.create(BASE_URL + "/search/authors.json?q=" + encoded + "&limit=1");
        JsonNode searchRoot = gateway.getJson(RateLimitedHttpGateway.Provider.OPENLIBRARY, searchUri, headers);
        if (searchRoot == null || !searchRoot.has("docs") || searchRoot.get("docs").isEmpty()) {
            return List.of();
        }

        JsonNode topAuthor = searchRoot.get("docs").get(0);
        String authorKey = cleanKey(topAuthor.path("key").asText(""));
        String authorName = topAuthor.path("name").asText(authorQuery);
        if (authorKey.isEmpty()) {
            return List.of();
        }

        AuthorRecord primaryAuthor = new AuthorRecord(authorKey, authorName, 1);

        URI worksUri = URI.create(BASE_URL + "/authors/" + authorKey + "/works.json?limit=" + maxWorks);
        JsonNode worksRoot = gateway.getJson(RateLimitedHttpGateway.Provider.OPENLIBRARY, worksUri, headers);
        if (worksRoot == null || !worksRoot.has("entries")) {
            return List.of();
        }

        List<BookBundle> bundles = new ArrayList<>();
        for (JsonNode entry : worksRoot.get("entries")) {
            String workId = cleanKey(entry.path("key").asText(""));
            if (workId.isEmpty()) continue;

            // The author works listing already carries the title and first_publish_date,
            // so it doubles as the authoritative "search doc" for year resolution.
            BookBundle bundle = buildBookBundleFromWorkNode(workId, entry, entry, List.of(primaryAuthor));
            if (bundle != null) {
                bundles.add(bundle);
            }
        }
        return bundles;
    }

    /**
     * Resolves works directly by title search query.
     */
    public List<BookBundle> fetchWorksByTitleQuery(String titleQuery, int maxWorks)
            throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(titleQuery, StandardCharsets.UTF_8);
        URI searchUri = URI.create(BASE_URL + "/search.json?title=" + encoded
                + "&limit=" + Math.max(maxWorks * 4, 20)
                + "&fields=key,title,author_key,author_name,first_publish_year,cover_i,edition_count");
        JsonNode searchRoot = gateway.getJson(RateLimitedHttpGateway.Provider.OPENLIBRARY, searchUri, headers);
        if (searchRoot == null || !searchRoot.has("docs")) {
            return List.of();
        }

        // OpenLibrary title search is a loose full-text match: "Fifty Shades of Grey" also returns
        // "Fifty Writers On Fifty Shades Of Grey". Keep only works whose title names the same book.
        List<JsonNode> docs = new ArrayList<>();
        for (JsonNode doc : searchRoot.get("docs")) {
            String candidateTitle = doc.path("title").asText("").trim();
            String workId = cleanKey(doc.path("key").asText(""));
            if (workId.isEmpty() || candidateTitle.isEmpty()) continue;
            if (!TitleNormalizer.isCompatibleTitle(titleQuery, candidateTitle)) continue;
            docs.add(doc);
        }

        // The canonical work is the one the catalogue has the most editions of; ties go to the
        // earliest year so a later omnibus does not win over the original novel.
        docs.sort((a, b) -> {
            int byEditions = Integer.compare(
                    b.path("edition_count").asInt(0), a.path("edition_count").asInt(0));
            if (byEditions != 0) return byEditions;
            return Integer.compare(
                    a.path("first_publish_year").asInt(Integer.MAX_VALUE),
                    b.path("first_publish_year").asInt(Integer.MAX_VALUE));
        });

        List<BookBundle> bundles = new ArrayList<>();
        for (JsonNode doc : docs) {
            if (bundles.size() >= maxWorks) break;

            String workId = cleanKey(doc.path("key").asText(""));
            URI workUri = URI.create(BASE_URL + "/works/" + workId + ".json");
            JsonNode workNode = gateway.getJson(RateLimitedHttpGateway.Provider.OPENLIBRARY, workUri, headers);
            if (workNode == null) {
                workNode = doc;
            }

            BookBundle bundle = buildBookBundleFromWorkNode(workId, workNode, doc, extractAuthors(doc));
            if (bundle != null) {
                bundles.add(bundle);
            }
        }
        return bundles;
    }

    /**
     * Sweeps works by subject for building frozen book baseline cohorts (Section 4.3).
     */
    public List<BookBundle> fetchSubjectBaselineWorks(String subject, int limit)
            throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(subject.toLowerCase(Locale.ROOT).replace(" ", "_"), StandardCharsets.UTF_8);
        URI subjectUri = URI.create(BASE_URL + "/subjects/" + encoded + ".json?limit=" + limit);
        JsonNode root = gateway.getJson(RateLimitedHttpGateway.Provider.OPENLIBRARY, subjectUri, headers);
        if (root == null || !root.has("works")) {
            return List.of();
        }

        List<BookBundle> results = new ArrayList<>();
        for (JsonNode workSummary : root.get("works")) {
            String workId = cleanKey(workSummary.path("key").asText(""));
            if (workId.isEmpty()) continue;

            List<AuthorRecord> authors = new ArrayList<>();
            if (workSummary.has("authors") && workSummary.get("authors").isArray()) {
                int order = 1;
                for (JsonNode aNode : workSummary.get("authors")) {
                    String aKey = cleanKey(aNode.path("key").asText(""));
                    String aName = aNode.path("name").asText("Unknown");
                    if (!aKey.isEmpty()) {
                        authors.add(new AuthorRecord(aKey, aName, order++));
                    }
                }
            }
            if (authors.isEmpty()) {
                authors.add(new AuthorRecord("OL_UNKNOWN", "Unknown Author", 1));
            }

            BookBundle bundle = buildBookBundleFromWorkNode(workId, workSummary, null, authors);
            if (bundle != null) {
                results.add(bundle);
            }
        }
        return results;
    }

    private static List<AuthorRecord> extractAuthors(JsonNode doc) {
        List<AuthorRecord> authors = new ArrayList<>();
        JsonNode aKeys = doc.path("author_key");
        JsonNode aNames = doc.path("author_name");
        if (aKeys.isArray() && aNames.isArray()) {
            int count = Math.min(aKeys.size(), aNames.size());
            for (int i = 0; i < count; i++) {
                authors.add(new AuthorRecord(
                        cleanKey(aKeys.get(i).asText("")),
                        aNames.get(i).asText(),
                        i + 1
                ));
            }
        }
        if (authors.isEmpty()) {
            authors.add(new AuthorRecord("OL_UNKNOWN", "Unknown Author", 1));
        }
        return authors;
    }

    private BookBundle buildBookBundleFromWorkNode(
            String workId,
            JsonNode workNode,
            JsonNode searchDoc,
            List<AuthorRecord> authors
    ) throws IOException, InterruptedException {
        String title = workNode.path("title").asText("").trim();
        if (title.isEmpty() && searchDoc != null) {
            title = searchDoc.path("title").asText("").trim();
        }
        if (title.isEmpty()) {
            return null;
        }

        EditionFetch editionFetch = fetchEditionsForWork(workId);
        Integer canonicalYear = resolveCanonicalYear(plausibleYear(workYear(workNode, searchDoc)),
                editionFetch.editions());
        String description = extractDescription(workNode.get("description"));

        Integer coverId = coverIdFrom(searchDoc);
        if (coverId == null) {
            coverId = editionFetch.coverId();
        }

        RatingSummary rating = fetchRatingsForWork(workId);

        List<String> authorNames = authors.stream().map(AuthorRecord::fullName).toList();
        BookPayload payload = new BookPayload(
                workId,
                title,
                canonicalYear,
                RatingNormalizer.findEarliestEditionYear(editionFetch.editions()),
                authorNames,
                description,
                coverId
        );

        return new BookBundle(
                workId,
                title,
                canonicalYear,
                authors,
                editionFetch.editions(),
                rating.avgRating5(),
                rating.ratingsCount(),
                rating.avgRating5() != null ? RatingSource.OPENLIBRARY : null,
                payload
        );
    }

    /**
     * Resolves the publication year a book should be judged against.
     *
     * Work-level first_publish_date values are frequently reprint years (OpenLibrary tags the
     * canonical "Fifty Shades of Grey" work as 2000, "A Game of Thrones" as 1998 and
     * "The Green Mile" as 1998), so edition dates are preferred whenever they are corroborated.
     */
    static Integer resolveCanonicalYear(Integer workYear, List<EditionRecord> editions) {
        List<Integer> years = new ArrayList<>();
        if (editions != null) {
            for (EditionRecord edition : editions) {
                if (isPlausibleYear(edition.publishYear())) {
                    years.add(edition.publishYear());
                }
            }
        }
        if (years.isEmpty()) {
            return isPlausibleYear(workYear) ? workYear : null;
        }
        Collections.sort(years);

        Map<Integer, Integer> counts = new TreeMap<>();
        for (int year : years) {
            counts.merge(year, 1, Integer::sum);
        }
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() >= YEAR_CORROBORATION) {
                return entry.getKey();
            }
        }

        int earliest = years.get(0);
        if (isPlausibleYear(workYear) && Math.abs(workYear - earliest) <= 1) {
            return workYear;
        }
        return earliest;
    }

    private static Integer workYear(JsonNode workNode, JsonNode searchDoc) {
        Integer year = extractYear(workNode.path("first_publish_date").asText(""));
        if (year == null) {
            year = extractYear(workNode.path("first_publish_year").asText(""));
        }
        if (year == null && searchDoc != null) {
            year = extractYear(searchDoc.path("first_publish_year").asText(""));
        }
        return year;
    }

    private static Integer coverIdFrom(JsonNode doc) {
        if (doc == null) return null;
        JsonNode cover = doc.get("cover_i");
        if (cover == null) cover = doc.get("cover_id");
        if (cover != null && cover.isNumber() && cover.asInt(0) > 0) {
            return cover.asInt();
        }
        return null;
    }

    private EditionFetch fetchEditionsForWork(String workId) throws IOException, InterruptedException {
        URI editionsUri = URI.create(BASE_URL + "/works/" + workId + "/editions.json?limit=50");
        JsonNode root = gateway.getJson(RateLimitedHttpGateway.Provider.OPENLIBRARY, editionsUri, headers);
        if (root == null || !root.has("entries")) {
            return new EditionFetch(List.of(), null);
        }

        List<EditionRecord> editions = new ArrayList<>();
        Integer coverId = null;
        for (JsonNode ed : root.get("entries")) {
            String editionId = cleanKey(ed.path("key").asText(""));
            if (editionId.isEmpty()) continue;

            String isbn13 = null;
            JsonNode isbnArr = ed.get("isbn_13");
            if (isbnArr != null && isbnArr.isArray() && !isbnArr.isEmpty()) {
                String rawIsbn = isbnArr.get(0).asText("").replaceAll("[^0-9]", "");
                if (rawIsbn.length() == 13) {
                    isbn13 = rawIsbn;
                }
            }

            Integer pages = null;
            if (ed.hasNonNull("number_of_pages")) {
                int p = ed.get("number_of_pages").asInt(0);
                if (p > 0) {
                    pages = p;
                }
            }

            Integer pubYear = plausibleYear(extractYear(ed.path("publish_date").asText("")));
            editions.add(new EditionRecord(editionId, isbn13, pages, pubYear));

            if (coverId == null) {
                JsonNode covers = ed.get("covers");
                if (covers != null && covers.isArray() && !covers.isEmpty() && covers.get(0).asInt(0) > 0) {
                    coverId = covers.get(0).asInt();
                }
            }
        }
        return new EditionFetch(editions, coverId);
    }

    private record RatingSummary(BigDecimal avgRating5, int ratingsCount) {}

    private RatingSummary fetchRatingsForWork(String workId) throws IOException, InterruptedException {
        URI ratingsUri = URI.create(BASE_URL + "/works/" + workId + "/ratings.json");
        JsonNode root = gateway.getJson(RateLimitedHttpGateway.Provider.OPENLIBRARY, ratingsUri, headers);
        if (root == null || !root.has("summary")) {
            return new RatingSummary(null, 0);
        }

        JsonNode summary = root.get("summary");
        int count = summary.path("count").asInt(0);
        if (count <= 0 || !summary.hasNonNull("average")) {
            return new RatingSummary(null, 0);
        }

        double avg = summary.get("average").asDouble(0.0);
        if (avg <= 0.0 || avg > 5.0) {
            return new RatingSummary(null, 0);
        }

        return new RatingSummary(
                BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP),
                count
        );
    }

    private static String extractDescription(JsonNode descNode) {
        if (descNode == null || descNode.isNull()) {
            return "";
        }
        if (descNode.isTextual()) {
            return descNode.asText();
        }
        if (descNode instanceof ObjectNode obj && obj.has("value")) {
            return obj.path("value").asText("");
        }
        return "";
    }

    private static Integer extractYear(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }
        Matcher m = FOUR_DIGIT_YEAR.matcher(rawDate);
        if (m.find()) {
            return plausibleYear(Integer.parseInt(m.group(1)));
        }
        return null;
    }

    private static Integer plausibleYear(Integer year) {
        return isPlausibleYear(year) ? year : null;
    }

    private static boolean isPlausibleYear(Integer year) {
        return year != null
                && year >= MIN_PLAUSIBLE_YEAR
                && year <= Year.now().getValue() + 1;
    }

    private static String cleanKey(String rawKey) {
        if (rawKey == null) return "";
        int lastSlash = rawKey.lastIndexOf('/');
        return (lastSlash >= 0 ? rawKey.substring(lastSlash + 1) : rawKey).trim();
    }
}
