package com.bookscreen.app;

import com.bookscreen.client.OpenLibraryClient;
import com.bookscreen.client.RateLimitedHttpGateway;
import com.bookscreen.client.TmdbApiClient;
import com.bookscreen.config.AppConfig;
import com.bookscreen.config.DatabaseConfig;
import com.bookscreen.dao.AdaptationUpsertDao;
import com.bookscreen.dao.AnalyticsDao;
import com.bookscreen.dao.AnalyticsDao.ReviewQueueItem;
import com.bookscreen.dao.BaselineDao;
import com.bookscreen.dao.SnapshotDao;
import com.bookscreen.dao.SnapshotDao.SnapshotBuildSummary;
import com.bookscreen.model.Enums.DivergenceCategory;
import com.bookscreen.model.Enums.LinkStatus;
import com.bookscreen.model.SnapshotRow;
import com.bookscreen.service.EntityMatcherService;
import com.bookscreen.service.SyncOrchestrator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class BookScreenCli {

    public static void main(String[] args) throws Exception {
        AppConfig appConfig = AppConfig.fromEnv();
        try (DatabaseConfig dbConfig = new DatabaseConfig(appConfig, true);
             RateLimitedHttpGateway gateway = new RateLimitedHttpGateway(appConfig.maxApiCallsPerSync())) {

            OpenLibraryClient olClient = new OpenLibraryClient(gateway, appConfig);
            TmdbApiClient tmdbClient = new TmdbApiClient(gateway, appConfig);
            EntityMatcherService matcher = new EntityMatcherService();
            AdaptationUpsertDao upsertDao = new AdaptationUpsertDao(dbConfig, appConfig, gateway.mapper());
            BaselineDao baselineDao = new BaselineDao(dbConfig, appConfig);
            SnapshotDao snapshotDao = new SnapshotDao(dbConfig, appConfig);
            AnalyticsDao analyticsDao = new AnalyticsDao(dbConfig);

            SyncOrchestrator orchestrator = new SyncOrchestrator(
                    appConfig, dbConfig, gateway, olClient, tmdbClient, matcher, upsertDao, baselineDao
            );

            if (args.length == 0) {
                printUsage();
                return;
            }

            String cmd = args[0].toLowerCase();
            switch (cmd) {
                case "load-cpi" -> {
                    seedDefaultUsCpiSeries(upsertDao);
                    System.out.println("Loaded US CPI-U reference series (1970-2026) into economic_index.");
                }
                case "baseline" -> {
                    List<String> subjects = List.of("fiction", "thriller", "science_fiction", "mystery", "horror", "fantasy");
                    List<Integer> decades = List.of(1970, 1980, 1990, 2000, 2010, 2020);
                    int versionId = orchestrator.buildAndFreezeBaseline(subjects, decades);
                    System.out.println("Built and froze baseline_version = " + versionId);
                }
                case "sync" -> {
                    String mode = args.length >= 3 ? args[1] : "author";
                    String query = args.length >= 3 ? args[2] : (args.length == 2 ? args[1] : "Stephen King");
                    int maxWorks = args.length >= 4 ? Integer.parseInt(args[3]) : 15;
                    orchestrator.syncByTrigger(mode, query, maxWorks);
                }
                case "snapshot" -> {
                    Integer baseVer = args.length >= 2 ? Integer.parseInt(args[1]) : null;
                    SnapshotBuildSummary summary = snapshotDao.rebuildAndSwapSnapshot(baseVer);
                    System.out.printf("Snapshot swapped [runId=%d, baseline=%s, rows=%d, d=%s, flaggedShare=%s]%n",
                            summary.snapshotRunId(), summary.baselineVersion(), summary.rowCount(),
                            summary.divergenceThreshold(), summary.flaggedShare());
                }
                case "replay-match" -> {
                    int rescored = orchestrator.replayAllStoredMatches();
                    System.out.println("Re-scored " + rescored + " stored adaptation links offline.");
                }
                case "review" -> {
                    if (args.length == 1 || "list".equalsIgnoreCase(args[1])) {
                        List<ReviewQueueItem> queue = analyticsDao.fetchReviewQueue(50);
                        System.out.println("=== v_review_queue (" + queue.size() + " pending) ===");
                        for (ReviewQueueItem item : queue) {
                            System.out.printf("[linkId=%d] %s -> %s | C=%s | %s%n",
                                    item.linkId(), item.bookTitle(), item.filmTitle(),
                                    item.matchConfidence(), item.evidenceJson());
                        }
                    } else if (args.length >= 4) {
                        long linkId = Long.parseLong(args[1]);
                        LinkStatus decision = LinkStatus.valueOf(args[2].toUpperCase());
                        String reviewer = args[3];
                        boolean ok = upsertDao.recordHumanReviewDecision(linkId, decision, reviewer);
                        System.out.println(ok ? "Saved HUMAN decision for link " + linkId : "Link not found: " + linkId);
                    } else {
                        printUsage();
                    }
                }
                case "report" -> {
                    List<SnapshotRow> rows = args.length >= 2
                            ? analyticsDao.findByCategory(DivergenceCategory.valueOf(args[1].toUpperCase()), 50)
                            : analyticsDao.findAllSnapshotRows(50);
                    System.out.println("=== v_adaptation_divergence (" + rows.size() + " rows) ===");
                    for (SnapshotRow r : rows) {
                        System.out.printf("%-28s | %-28s | delay=%2dy | ppm=%6s | R_b=%6s | R_f=%6s | dR=%7s | mult=%6s | cat=%s (%s)%n",
                                truncate(r.bookTitle(), 28),
                                truncate(r.filmTitle(), 28),
                                r.adaptationDelayYears(),
                                r.pagesPerMinute(),
                                r.bookRank(),
                                r.filmRank(),
                                r.rankDivergence(),
                                r.grossMultiple(),
                                r.divergenceCategory(),
                                r.insufficientReason() != null ? r.insufficientReason() : r.bookReliability());
                    }
                }

                case "serve" -> {
                    int port = args.length >= 2 ? Integer.parseInt(args[1]) : 8080;
                    java.nio.file.Path webDir = java.nio.file.Paths.get("web").toAbsolutePath();
                    new WebServer(port, webDir, analyticsDao, orchestrator, snapshotDao, gateway.mapper()).start();
                }
                default -> printUsage();
            }
        }
    }

    private static void seedDefaultUsCpiSeries(AdaptationUpsertDao dao) throws Exception {
        Map<Integer, String> cpiByYear = new TreeMap<>();
        cpiByYear.put(1970, "38.800");  cpiByYear.put(1975, "53.800");
        cpiByYear.put(1980, "82.400");  cpiByYear.put(1985, "107.600");
        cpiByYear.put(1990, "130.700"); cpiByYear.put(1995, "152.400");
        cpiByYear.put(2000, "172.200"); cpiByYear.put(2005, "195.300");
        cpiByYear.put(2010, "218.056"); cpiByYear.put(2015, "237.017");
        cpiByYear.put(2018, "251.107"); cpiByYear.put(2019, "255.657");
        cpiByYear.put(2020, "258.811"); cpiByYear.put(2021, "270.970");
        cpiByYear.put(2022, "292.655"); cpiByYear.put(2023, "304.702");
        cpiByYear.put(2024, "313.689"); cpiByYear.put(2025, "320.500");
        cpiByYear.put(2026, "327.000");

        // Interpolate intermediate years so every release year has an exact row
        int prevYear = 1970;
        double prevVal = 38.800;
        for (var entry : cpiByYear.entrySet()) {
            int curYear = entry.getKey();
            double curVal = Double.parseDouble(entry.getValue());
            for (int y = prevYear; y <= curYear; y++) {
                double ratio = (curYear == prevYear) ? 1.0 : (double) (y - prevYear) / (curYear - prevYear);
                double interp = prevVal + ratio * (curVal - prevVal);
                dao.upsertCpiIndex(y, BigDecimal.valueOf(Math.round(interp * 1000.0) / 1000.0));
            }
            prevYear = curYear;
            prevVal = curVal;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static void printUsage() {
        System.out.println("""
            Gatsby Graph / Book-to-Screen Adaptation Divergence Engine v2.1
            Usage:
              load-cpi                                     Load US CPI-U table (1970-2026)
              baseline                                     Build & freeze adaptation-blind baseline cohort
              sync author "<Author Name>" [maxWorks]       Sync works & film candidates by author
              sync title  "<Book Title>"  [maxWorks]       Sync works & film candidates by book title
              snapshot [baselineVersion]                   Rebuild & atomically swap adaptation_snapshot
              replay-match                                 Offline re-score all stored pairs from JSON payloads
              review list                                  List pending links in v_review_queue
              review <linkId> <ACCEPTED|REJECTED> <user>   Record persistent HUMAN review decision
              report [BOOK_OUTRANKS_FILM|FILM_OUTRANKS_BOOK|ALIGNED|INSUFFICIENT_DATA]
            """);
    }
}