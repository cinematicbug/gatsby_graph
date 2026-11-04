package com.bookscreen.service;

import com.bookscreen.client.OpenLibraryClient;
import com.bookscreen.client.RateLimitedHttpGateway;
import com.bookscreen.client.TmdbApiClient;
import com.bookscreen.config.AppConfig;
import com.bookscreen.dao.AdaptationUpsertDao;
import com.bookscreen.dao.AdaptationUpsertDao.CandidatePair;
import com.bookscreen.dao.AdaptationUpsertDao.StalenessState;
import com.bookscreen.dao.AdaptationUpsertDao.StoredPairForReplay;
import com.bookscreen.dao.BaselineDao;
import com.bookscreen.config.DatabaseConfig;
import com.bookscreen.model.*;
import com.bookscreen.model.Enums.Domain;
import com.bookscreen.model.Enums.LinkStatus;
import com.bookscreen.service.EntityMatcherService.MatcherConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SyncOrchestrator {

    private final AppConfig appConfig;
    private final DatabaseConfig dbConfig;
    private final RateLimitedHttpGateway gateway;
    private final OpenLibraryClient olClient;
    private final TmdbApiClient tmdbClient;
    private final EntityMatcherService matcher;
    private final AdaptationUpsertDao upsertDao;
    private final BaselineDao baselineDao;

    public SyncOrchestrator(
            AppConfig appConfig,
            DatabaseConfig dbConfig,
            RateLimitedHttpGateway gateway,
            OpenLibraryClient olClient,
            TmdbApiClient tmdbClient,
            EntityMatcherService matcher,
            AdaptationUpsertDao upsertDao,
            BaselineDao baselineDao
    ) {
        this.appConfig = appConfig;
        this.dbConfig = dbConfig;
        this.gateway = gateway;
        this.olClient = olClient;
        this.tmdbClient = tmdbClient;
        this.matcher = matcher;
        this.upsertDao = upsertDao;
        this.baselineDao = baselineDao;
    }

    /**
     * Executes end-to-end two-phase ingestion and persistence for an author or title trigger (Section 3).
     */
    public void syncByTrigger(String mode, String query, int maxWorks) throws Exception {
        String leaseKey = mode.toUpperCase() + ":" + query.trim().toLowerCase();
        String owner = UUID.randomUUID().toString();
        if (!upsertDao.acquireLease(leaseKey, owner)) {
            System.out.println("Sync skipped: active lease already held for " + leaseKey);
            return;
        }

        gateway.resetRunCounters();
        long runId = upsertDao.startSyncRun(leaseKey);
        int booksSynced = 0;
        int linksAccepted = 0;
        int linksReview = 0;
        int errors = 0;

        try {
            // Phase A: Literary retrieval (no DB connection held)
            List<BookBundle> bundles = "title".equalsIgnoreCase(mode)
                    ? olClient.fetchWorksByTitleQuery(query, maxWorks)
                    : olClient.fetchWorksByAuthorQuery(query, maxWorks);

            MatcherConfig matcherConfig = MatcherConfig.fromAppConfig(appConfig);

            for (BookBundle bundle : bundles) {
                try {
                    StalenessState state = upsertDao.checkWorkStaleness(bundle.openLibraryWorkId());
                    boolean skipFilmSearch = state.exists()
                            && state.filmSearchCheckedAt() != null
                            && Instant.now().isBefore(state.filmSearchCheckedAt().plus(appConfig.negativeLookupTtl()));

                    List<CandidatePair> candidatePairs = new ArrayList<>();
                    boolean filmSearchPerformed = false;

                    // Phase B1 & B2: Cinema discovery and detail enrichment (no DB connection held)
                    if (!skipFilmSearch && bundle.payload().effectivePublishYear() != null) {
                        List<FilmCandidate> candidates = tmdbClient.discoverAndEnrichCandidates(
                                bundle.title(),
                                bundle.payload().effectivePublishYear()
                        );
                        filmSearchPerformed = true;

                        for (FilmCandidate film : candidates) {
                            ResolvedLink resolved = matcher.score(bundle.payload(), film.payload(), matcherConfig);
                            if (resolved.linkStatus() == LinkStatus.ACCEPTED) {
                                linksAccepted++;
                            } else if (resolved.linkStatus() == LinkStatus.REVIEW) {
                                linksReview++;
                            }
                            candidatePairs.add(new CandidatePair(film, resolved));
                        }
                    }

                    // Tier 3 Persistence: Single-writer transaction per BookBundle
                    upsertDao.persistBookBundleWithRetries(runId, bundle, candidatePairs, filmSearchPerformed);
                    booksSynced++;

                    upsertDao.updateSyncRunProgress(
                            runId,
                            gateway.getApiCallsCount(),
                            gateway.getCacheHitsCount(),
                            booksSynced,
                            linksAccepted,
                            linksReview,
                            gateway.getRetriesCount(),
                            errors
                    );
                } catch (Exception workEx) {
                    errors++;
                    upsertDao.logSyncError(runId, "WORK", bundle.openLibraryWorkId(), "SYNC_LOOP", 1, workEx.getMessage());
                }
            }

            upsertDao.finishSyncRun(runId, "OK");
            System.out.printf("Sync complete [runId=%d]: books=%d, accepted=%d, review=%d, apiCalls=%d, cacheHits=%d%n",
                    runId, booksSynced, linksAccepted, linksReview, gateway.getApiCallsCount(), gateway.getCacheHitsCount());
        } catch (Exception ex) {
            upsertDao.finishSyncRun(runId, "FAILED");
            throw ex;
        } finally {
            upsertDao.releaseLease(leaseKey, owner);
        }
    }

    /**
     * Offline replay-match command: re-scores every stored pair from persisted JSON payloads with zero API calls (Section 3 & 5.1).
     */
    public int replayAllStoredMatches() throws Exception {
        List<StoredPairForReplay> pairs = upsertDao.fetchAllStoredPairsForReplay();
        MatcherConfig matcherConfig = MatcherConfig.fromAppConfig(appConfig);
        int updated = 0;

        try (Connection conn = dbConfig.openTransactionalConnection()) {
            try {
                for (StoredPairForReplay pair : pairs) {
                    ResolvedLink rescored = matcher.score(pair.bookPayload(), pair.filmPayload(), matcherConfig);
                    upsertDao.upsertAdaptationLink(conn, pair.bookId(), pair.movieId(), rescored);
                    updated++;
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
        return updated;
    }

    /**
     * Builds and freezes a stratified baseline version across OpenLibrary subjects and TMDB decades (Section 4.3).
     */
    public int buildAndFreezeBaseline(List<String> subjects, List<Integer> decades) throws Exception {
        gateway.resetRunCounters();
        String paramsJson = String.format("{\"subjects\":%s,\"decades\":%s}",
                gateway.mapper().writeValueAsString(subjects),
                gateway.mapper().writeValueAsString(decades));
        int versionId = baselineDao.createBuildingVersion(paramsJson);

        for (String subject : subjects) {
            List<BookBundle> books = olClient.fetchSubjectBaselineWorks(subject, 50);
            for (BookBundle b : books) {
                if (b.avgRating5() != null && b.ratingSource() != null) {
                    baselineDao.upsertBaselineEntryIfUnlinked(
                            versionId,
                            Domain.BOOK,
                            b.openLibraryWorkId(),
                            b.ratingSource().name(),
                            b.avgRating5(),
                            b.ratingsCount()
                    );
                }
            }
        }

        for (int decade : decades) {
            String partitionKey = decade + "s";
            List<FilmCandidate> films = tmdbClient.fetchDecadeBaselineFilms(decade, appConfig.minFilmVotes(), 2);
            for (FilmCandidate f : films) {
                if (f.tmdbScore10() != null) {
                    baselineDao.upsertBaselineEntryIfUnlinked(
                            versionId,
                            Domain.FILM,
                            String.valueOf(f.tmdbId()),
                            partitionKey,
                            f.tmdbScore10(),
                            f.tmdbVoteCount()
                    );
                }
            }
        }

        baselineDao.computePartitionsAndFreeze(versionId);
        return versionId;
    }
}