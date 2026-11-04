CREATE TABLE authors (
                         author_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                         openlibrary_author_key VARCHAR(32) NOT NULL,
                         full_name VARCHAR(255) NOT NULL,
                         last_synced_at TIMESTAMP NULL,
                         UNIQUE KEY uq_author_ol (openlibrary_author_key)
) ENGINE=InnoDB;

CREATE TABLE source_books (
                              book_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                              openlibrary_work_id VARCHAR(32) NOT NULL,
                              title VARCHAR(500) NOT NULL,
                              first_publish_year SMALLINT NULL,
                              page_count_median SMALLINT UNSIGNED NULL,
                              edition_count INT UNSIGNED NOT NULL DEFAULT 0,
                              avg_rating_5 DECIMAL(3,2) NULL,
                              ratings_count INT UNSIGNED NOT NULL DEFAULT 0,
                              rating_source ENUM('OPENLIBRARY','GOOGLE_BOOKS') NULL,
                              ol_payload JSON NULL,
                              payload_fetched_at TIMESTAMP NULL,
                              rating_checked_at TIMESTAMP NULL,
                              film_search_checked_at TIMESTAMP NULL,
                              last_synced_at TIMESTAMP NULL,
                              UNIQUE KEY uq_book_work (openlibrary_work_id),
                              CONSTRAINT chk_pages CHECK (page_count_median IS NULL OR page_count_median > 0),
                              CONSTRAINT chk_rating CHECK (avg_rating_5 IS NULL OR avg_rating_5 BETWEEN 0 AND 5),
                              CONSTRAINT chk_rating_src CHECK (avg_rating_5 IS NULL OR rating_source IS NOT NULL)
) ENGINE=InnoDB;

CREATE TABLE book_authors (
                              book_id BIGINT UNSIGNED NOT NULL,
                              author_id BIGINT UNSIGNED NOT NULL,
                              author_order TINYINT UNSIGNED NOT NULL DEFAULT 1,
                              PRIMARY KEY (book_id, author_id),
                              UNIQUE KEY uq_author_order (book_id, author_order),
                              FOREIGN KEY (book_id) REFERENCES source_books(book_id) ON DELETE CASCADE,
                              FOREIGN KEY (author_id) REFERENCES authors(author_id)
) ENGINE=InnoDB;

CREATE TABLE book_editions (
                               edition_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                               openlibrary_edition_id VARCHAR(32) NOT NULL,
                               book_id BIGINT UNSIGNED NOT NULL,
                               isbn_13 CHAR(13) NULL,
                               page_count SMALLINT UNSIGNED NULL,
                               publish_year SMALLINT NULL,
                               UNIQUE KEY uq_edition_ol (openlibrary_edition_id),
                               KEY ix_edition_isbn (isbn_13),
                               FOREIGN KEY (book_id) REFERENCES source_books(book_id) ON DELETE CASCADE,
                               CONSTRAINT chk_ed_pages CHECK (page_count IS NULL OR page_count > 0)
) ENGINE=InnoDB;

CREATE TABLE film_adaptations (
                                  movie_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                                  tmdb_id INT UNSIGNED NOT NULL,
                                  imdb_id VARCHAR(16) NULL,
                                  title VARCHAR(500) NOT NULL,
                                  release_year SMALLINT NOT NULL,
                                  runtime_minutes SMALLINT UNSIGNED NULL,
                                  production_budget BIGINT UNSIGNED NULL,
                                  box_office_gross BIGINT UNSIGNED NULL,
                                  tmdb_score_10 DECIMAL(3,1) NULL,
                                  tmdb_vote_count INT UNSIGNED NOT NULL DEFAULT 0,
                                  tmdb_payload JSON NULL,
                                  payload_fetched_at TIMESTAMP NULL,
                                  last_synced_at TIMESTAMP NULL,
                                  gross_multiple DECIMAL(12,2) GENERATED ALWAYS AS (
                                      CASE WHEN production_budget >= 10000 AND box_office_gross >= 10000
                                               THEN box_office_gross / production_budget END
                                      ) VIRTUAL,
                                  UNIQUE KEY uq_film_tmdb (tmdb_id),
                                  KEY ix_film_imdb (imdb_id),
                                  CONSTRAINT chk_runtime CHECK (runtime_minutes IS NULL OR runtime_minutes > 0)
) ENGINE=InnoDB;

CREATE TABLE adaptation_links (
                                  link_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                                  book_id BIGINT UNSIGNED NOT NULL,
                                  movie_id BIGINT UNSIGNED NOT NULL,
                                  match_confidence DECIMAL(3,2) NOT NULL,
                                  matcher_version VARCHAR(16) NOT NULL,
                                  link_status ENUM('ACCEPTED','REVIEW','REJECTED') NOT NULL,
                                  status_source ENUM('MATCHER','HUMAN') NOT NULL DEFAULT 'MATCHER',
                                  evidence JSON NULL,
                                  reviewed_by VARCHAR(64) NULL,
                                  reviewed_at TIMESTAMP NULL,
                                  last_synced_at TIMESTAMP NULL,
                                  UNIQUE KEY uq_link (book_id, movie_id),
                                  KEY ix_link_status (link_status),
                                  FOREIGN KEY (book_id) REFERENCES source_books(book_id) ON DELETE CASCADE,
                                  FOREIGN KEY (movie_id) REFERENCES film_adaptations(movie_id) ON DELETE CASCADE,
                                  CONSTRAINT chk_conf CHECK (match_confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB;

CREATE TABLE baseline_versions (
                                   version_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                                   status ENUM('BUILDING','FROZEN','RETIRED') NOT NULL DEFAULT 'BUILDING',
                                   created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   frozen_at TIMESTAMP NULL,
                                   params JSON NULL
) ENGINE=InnoDB;

CREATE TABLE baseline_scores (
                                 baseline_version INT UNSIGNED NOT NULL,
                                 domain ENUM('BOOK','FILM') NOT NULL,
                                 entity_ref VARCHAR(40) NOT NULL,
                                 partition_key VARCHAR(32) NOT NULL,
                                 raw_score DECIMAL(4,2) NOT NULL,
                                 score_count INT UNSIGNED NOT NULL,
                                 shrunk_score DECIMAL(6,4) NULL,
                                 PRIMARY KEY (baseline_version, domain, entity_ref),
                                 KEY ix_rank (baseline_version, domain, partition_key, shrunk_score),
                                 FOREIGN KEY (baseline_version) REFERENCES baseline_versions(version_id)
) ENGINE=InnoDB;

CREATE TABLE baseline_partitions (
                                     baseline_version INT UNSIGNED NOT NULL,
                                     domain ENUM('BOOK','FILM') NOT NULL,
                                     partition_key VARCHAR(32) NOT NULL,
                                     n INT UNSIGNED NOT NULL,
                                     mean_raw DECIMAL(6,4) NOT NULL,
                                     PRIMARY KEY (baseline_version, domain, partition_key),
                                     FOREIGN KEY (baseline_version) REFERENCES baseline_versions(version_id)
) ENGINE=InnoDB;

CREATE TABLE economic_index (
                                year SMALLINT PRIMARY KEY,
                                cpi_index DECIMAL(8,3) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE sync_runs (
                           run_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                           trigger_key VARCHAR(100) NOT NULL,
                           status ENUM('RUNNING','OK','FAILED') NOT NULL DEFAULT 'RUNNING',
                           started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           finished_at TIMESTAMP NULL,
                           api_calls INT UNSIGNED NOT NULL DEFAULT 0,
                           cache_hits INT UNSIGNED NOT NULL DEFAULT 0,
                           books_synced INT UNSIGNED NOT NULL DEFAULT 0,
                           links_accepted INT UNSIGNED NOT NULL DEFAULT 0,
                           links_review INT UNSIGNED NOT NULL DEFAULT 0,
                           retries INT UNSIGNED NOT NULL DEFAULT 0,
                           errors INT UNSIGNED NOT NULL DEFAULT 0,
                           film_financial_coverage DECIMAL(5,4) NULL
) ENGINE=InnoDB;

CREATE TABLE sync_errors (
                             error_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                             run_id BIGINT UNSIGNED NULL,
                             entity_type VARCHAR(32) NOT NULL,
                             external_key VARCHAR(64) NOT NULL,
                             stage VARCHAR(32) NOT NULL,
                             attempt TINYINT UNSIGNED NOT NULL DEFAULT 1,
                             message TEXT NOT NULL,
                             created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE sync_leases (
                             lease_key VARCHAR(100) PRIMARY KEY,
                             owner VARCHAR(64) NOT NULL,
                             expires_at TIMESTAMP NOT NULL
) ENGINE=InnoDB;

CREATE TABLE snapshot_runs (
                               snapshot_run_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                               baseline_version INT UNSIGNED NULL,
                               status ENUM('BUILDING','BUILT','SWAPPED','FAILED') NOT NULL DEFAULT 'BUILDING',
                               started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               built_at TIMESTAMP NULL,
                               swapped_at TIMESTAMP NULL,
                               row_count INT UNSIGNED NULL,
                               divergence_threshold DECIMAL(5,4) NULL,
                               flagged_share DECIMAL(5,4) NULL
) ENGINE=InnoDB;

CREATE TABLE adaptation_snapshot (
                                     link_id BIGINT UNSIGNED PRIMARY KEY,
                                     book_id BIGINT UNSIGNED NOT NULL,
                                     movie_id BIGINT UNSIGNED NOT NULL,
                                     book_title VARCHAR(500) NOT NULL,
                                     film_title VARCHAR(500) NOT NULL,
                                     adaptation_delay_years SMALLINT NOT NULL,
                                     pages_per_minute DECIMAL(8,4) NULL,
                                     book_partition VARCHAR(32) NULL,
                                     film_partition VARCHAR(32) NULL,
                                     book_baseline_n INT UNSIGNED NULL,
                                     film_baseline_n INT UNSIGNED NULL,
                                     book_shrunk_score DECIMAL(6,4) NULL,
                                     film_shrunk_score DECIMAL(6,4) NULL,
                                     book_rank DECIMAL(5,4) NULL,
                                     film_rank DECIMAL(5,4) NULL,
                                     rank_divergence DECIMAL(5,4) NULL,
                                     legacy_scaled_delta DECIMAL(4,2) NULL,
                                     gross_multiple DECIMAL(12,2) NULL,
                                     gross_adj_usd BIGINT NULL,
                                     book_reliability ENUM('LOW','OK') NULL,
                                     divergence_category VARCHAR(24) NULL,
                                     insufficient_reason VARCHAR(24) NULL,
                                     baseline_version INT UNSIGNED NULL,
                                     snapshot_run_id BIGINT UNSIGNED NOT NULL,
                                     refreshed_at TIMESTAMP NOT NULL,
                                     KEY ix_snap_cat (divergence_category),
                                     KEY ix_snap_book (book_id),
                                     KEY ix_snap_movie (movie_id),
                                     KEY ix_snap_div (rank_divergence)
) ENGINE=InnoDB;

CREATE VIEW v_adaptation_divergence AS
SELECT link_id, book_id, movie_id, book_title, film_title, adaptation_delay_years,
       pages_per_minute, book_rank, film_rank, rank_divergence, gross_multiple,
       gross_adj_usd, book_reliability, divergence_category, insufficient_reason,
       baseline_version, refreshed_at
FROM adaptation_snapshot;

CREATE VIEW v_review_queue AS
SELECT l.link_id, sb.title AS book_title, fa.title AS film_title,
       l.match_confidence, l.evidence
FROM adaptation_links l
         JOIN source_books sb     ON sb.book_id  = l.book_id
         JOIN film_adaptations fa ON fa.movie_id = l.movie_id
WHERE l.link_status = 'REVIEW';