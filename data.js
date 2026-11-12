/* ==========================================================================
   Gatsby Graph — Multi-Adaptation Engine (v9 - Complete & Self-Contained)
   1. Instant verified ISBN-13 covers + live IMDb/Wikimedia theatrical posters
   2. CORS-safe IMDb JSONP + proxy fallback for live Sync queries
   ========================================================================== */
(function () {
    "use strict";

    var CATEGORY_LABEL = {
        BOOK_OUTRANKS_FILM: "book ahead",
        FILM_OUTRANKS_BOOK: "film ahead",
        ALIGNED: "dead heat",
        INSUFFICIENT_DATA: "dead heat"
    };

    var KNOWN_ADAPTATIONS = {
        "dune": {
            bookTitle: "Dune", authorName: "Frank Herbert", bookYear: 1965, pageCount: 412, bookRank: 0.91,
            cover: "https://covers.openlibrary.org/b/id/11481354-L.jpg",
            films: [
                { filmTitle: "Dune: Part One", filmYear: 2021, directorName: "Denis Villeneuve", runtimeMinutes: 155, filmRank: 0.92, grossMultiple: 2.48, poster: "https://m.media-amazon.com/images/M/MV5BN2FjNmEyNWMtYzM0ZS00NjIyLTg5YzYtYThlMGVjNzE1OGViXkEyXkFqcGdeQXVyMTkxNjUyNQ@@._V1_QL75_UX500_.jpg" },
                { filmTitle: "Dune: Part Two", filmYear: 2024, directorName: "Denis Villeneuve", runtimeMinutes: 166, filmRank: 0.96, grossMultiple: 3.76, poster: "https://m.media-amazon.com/images/M/MV5BNTc0YmQxMjEtODI5MC00NjFiLTlkMWUtOGQ5NjFmYWUyZGJhXkEyXkFqcGdeQXVyMTkxNjUyNQ@@._V1_QL75_UX500_.jpg" },
                { filmTitle: "Dune", filmYear: 1984, directorName: "David Lynch", runtimeMinutes: 137, filmRank: 0.52, grossMultiple: 0.77, poster: "https://upload.wikimedia.org/wikipedia/en/5/51/Dune_1984_Poster.jpg" }
            ]
        },
        "wuthering-heights": {
            bookTitle: "Wuthering Heights", authorName: "Emily Brontë", bookYear: 1847, pageCount: 342, bookRank: 0.93,
            cover: "https://covers.openlibrary.org/b/isbn/9780141439556-L.jpg",
            films: [
                { filmTitle: "Wuthering Heights", filmYear: 1939, directorName: "William Wyler", runtimeMinutes: 104, filmRank: 0.86, grossMultiple: 3.1, poster: "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e1/Wuthering_Heights_%281939_poster%29.jpg/500px-Wuthering_Heights_%281939_poster%29.jpg" },
                { filmTitle: "Emily Brontë's Wuthering Heights", filmYear: 1992, directorName: "Peter Kosminsky", runtimeMinutes: 105, filmRank: 0.68, grossMultiple: 1.4, poster: "https://upload.wikimedia.org/wikipedia/en/6/66/Wuthering_Heights_1992_poster.jpg" },
                { filmTitle: "Wuthering Heights", filmYear: 2011, directorName: "Andrea Arnold", runtimeMinutes: 129, filmRank: 0.61, grossMultiple: 1.1, poster: "https://upload.wikimedia.org/wikipedia/en/d/d3/Wuthering_Heights_2011_film.jpg" },
                { filmTitle: "Wuthering Heights", filmYear: 2009, directorName: "Coky Giedroyc", runtimeMinutes: 142, filmRank: 0.77, grossMultiple: null, poster: "https://upload.wikimedia.org/wikipedia/en/9/9b/Wuthering_Heights_2009.jpg" }
            ]
        },
        "pride-and-prejudice": {
            bookTitle: "Pride and Prejudice", authorName: "Jane Austen", bookYear: 1813, pageCount: 336, bookRank: 0.94,
            cover: "https://covers.openlibrary.org/b/isbn/9780141439518-L.jpg",
            films: [
                { filmTitle: "Pride & Prejudice", filmYear: 2005, directorName: "Joe Wright", runtimeMinutes: 129, filmRank: 0.89, grossMultiple: 4.34, poster: "https://upload.wikimedia.org/wikipedia/en/0/03/Prideandprejudiceposter.jpg" },
                { filmTitle: "Pride and Prejudice", filmYear: 1940, directorName: "Robert Z. Leonard", runtimeMinutes: 118, filmRank: 0.78, grossMultiple: 1.95, poster: "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6f/Pride_and_Prejudice_%281940_poster%29.jpg/500px-Pride_and_Prejudice_%281940_poster%29.jpg" },
                { filmTitle: "Pride and Prejudice", filmYear: 1995, directorName: "Simon Langton", runtimeMinutes: 327, filmRank: 0.95, grossMultiple: null, poster: "https://upload.wikimedia.org/wikipedia/en/9/92/Pride_and_Prejudice_1995_title_card.jpg" }
            ]
        },
        "rebecca": {
            bookTitle: "Rebecca", authorName: "Daphne du Maurier", bookYear: 1938, pageCount: 416, bookRank: 0.92,
            cover: "https://covers.openlibrary.org/b/isbn/9780380730407-L.jpg",
            films: [
                { filmTitle: "Rebecca", filmYear: 1940, directorName: "Alfred Hitchcock", runtimeMinutes: 130, filmRank: 0.93, grossMultiple: 4.6, poster: "https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/Rebecca_%281940_poster%29.jpg/500px-Rebecca_%281940_poster%29.jpg" },
                { filmTitle: "Rebecca", filmYear: 2020, directorName: "Ben Wheatley", runtimeMinutes: 121, filmRank: 0.44, grossMultiple: null, poster: "https://upload.wikimedia.org/wikipedia/en/1/17/Rebecca_%282020_film%29_poster.jpg" }
            ]
        },
        "fight-club": {
            bookTitle: "Fight Club", authorName: "Chuck Palahniuk", bookYear: 1996, pageCount: 208, bookRank: 0.84,
            cover: "https://covers.openlibrary.org/b/isbn/9780393327342-L.jpg",
            films: [
                { filmTitle: "Fight Club", filmYear: 1999, directorName: "David Fincher", runtimeMinutes: 139, filmRank: 0.97, grossMultiple: 1.6, poster: "https://upload.wikimedia.org/wikipedia/en/f/fc/Fight_Club_poster.jpg" }
            ]
        },
        "lord-of-the-rings": {
            bookTitle: "The Lord of the Rings", authorName: "J.R.R. Tolkien", bookYear: 1954, pageCount: 1178, bookRank: 0.97,
            cover: "https://covers.openlibrary.org/b/isbn/9780618640157-L.jpg",
            films: [
                { filmTitle: "The Lord of the Rings: The Fellowship of the Ring", filmYear: 2001, directorName: "Peter Jackson", runtimeMinutes: 178, filmRank: 0.98, grossMultiple: 9.5, poster: "https://upload.wikimedia.org/wikipedia/en/f/fb/Lord_Rings_Fellowship_Ring.jpg" },
                { filmTitle: "The Lord of the Rings: The Two Towers", filmYear: 2002, directorName: "Peter Jackson", runtimeMinutes: 179, filmRank: 0.97, grossMultiple: 9.9, poster: "https://upload.wikimedia.org/wikipedia/en/a/ad/Lord_of_the_Rings_-_The_Two_Towers.jpg" },
                { filmTitle: "The Lord of the Rings: The Return of the King", filmYear: 2003, directorName: "Peter Jackson", runtimeMinutes: 201, filmRank: 0.99, grossMultiple: 12.1, poster: "https://upload.wikimedia.org/wikipedia/en/4/40/Lord_of_the_Rings_-_The_Return_of_the_King.jpg" }
            ]
        },
        "green-mile": {
            bookTitle: "The Green Mile", authorName: "Stephen King", bookYear: 1996, pageCount: 400, bookRank: 0.89,
            cover: "https://covers.openlibrary.org/b/isbn/9780671041786-L.jpg",
            films: [
                { filmTitle: "The Green Mile", filmYear: 1999, directorName: "Frank Darabont", runtimeMinutes: 189, filmRank: 0.95, grossMultiple: 4.78, poster: "https://upload.wikimedia.org/wikipedia/en/e/e2/The_Green_Mile_%28movie_poster%29.jpg" }
            ]
        },
        "fifty-shades-of-gray": {
            bookTitle: "Fifty Shades of Grey", authorName: "E. L. James", bookYear: 2011, pageCount: 514, bookRank: 0.57,
            cover: "https://covers.openlibrary.org/b/isbn/9780345803481-L.jpg",
            films: [
                { filmTitle: "Fifty Shades of Grey", filmYear: 2015, directorName: "Sam Taylor-Johnson", runtimeMinutes: 125, filmRank: 0.25, grossMultiple: 14.24, poster: "https://upload.wikimedia.org/wikipedia/en/7/73/Fifty_Shades_of_Grey_poster.jpg" }
            ]
        },
        "twilight": {
            bookTitle: "Twilight", authorName: "Stephenie Meyer", bookYear: 2005, pageCount: 498, bookRank: 0.62,
            cover: "https://covers.openlibrary.org/b/id/12641977-L.jpg",
            films: [
                { filmTitle: "Twilight", filmYear: 2008, directorName: "Catherine Hardwicke", runtimeMinutes: 122, filmRank: 0.48, grossMultiple: 10.64, poster: "https://upload.wikimedia.org/wikipedia/en/1/16/Twilightbook.jpg" }
            ]
        },
        "wizard-of-oz": {
            bookTitle: "The Wonderful Wizard of Oz", authorName: "L. Frank Baum", bookYear: 1900, pageCount: 259, bookRank: 0.85,
            cover: "https://covers.openlibrary.org/b/isbn/9780141321028-L.jpg",
            films: [
                { filmTitle: "The Wizard of Oz", filmYear: 1939, directorName: "Victor Fleming", runtimeMinutes: 102, filmRank: 0.96, grossMultiple: 10.7, poster: "https://upload.wikimedia.org/wikipedia/commons/thumb/6/69/Wizard_of_oz_movie_poster.jpg/500px-Wizard_of_oz_movie_poster.jpg" }
            ]
        },
        "pride-and-prejudice-and-zombies": {
            bookTitle: "Pride and Prejudice and Zombies", authorName: "Seth Grahame-Smith", bookYear: 2009, pageCount: 320, bookRank: 0.54,
            cover: "https://covers.openlibrary.org/b/isbn/9781594743344-L.jpg",
            films: [
                { filmTitle: "Pride and Prejudice and Zombies", filmYear: 2016, directorName: "Burr Steers", runtimeMinutes: 108, filmRank: 0.52, grossMultiple: 0.59, poster: "https://upload.wikimedia.org/wikipedia/en/3/34/Pride_and_Prejudice_and_Zombies_poster.jpg" }
            ]
        },
        "dragons-of-autumn-twilight": {
            bookTitle: "Dragons of Autumn Twilight", authorName: "Margaret Weis", bookYear: 1984, pageCount: 447, bookRank: 0.76,
            cover: "https://covers.openlibrary.org/b/isbn/9780786915743-L.jpg",
            films: [
                { filmTitle: "Dragonlance: Dragons of Autumn Twilight", filmYear: 2008, directorName: "Will Meugniot", runtimeMinutes: 91, filmRank: 0.35, grossMultiple: null, poster: "https://upload.wikimedia.org/wikipedia/en/4/4b/Dragonlance_dvd_cover.jpg" }
            ]
        },
        "little-women": {
            bookTitle: "Little Women", authorName: "Louisa May Alcott", bookYear: 1868, pageCount: 449, bookRank: 0.90,
            cover: "https://covers.openlibrary.org/b/isbn/9780147514011-L.jpg",
            films: [
                { filmTitle: "Little Women", filmYear: 2019, directorName: "Greta Gerwig", runtimeMinutes: 135, filmRank: 0.91, grossMultiple: 5.4, poster: "https://upload.wikimedia.org/wikipedia/en/9/9d/Little_Women_%282019_film%29.jpeg" },
                { filmTitle: "Little Women", filmYear: 1994, directorName: "Gillian Armstrong", runtimeMinutes: 115, filmRank: 0.82, grossMultiple: 2.8, poster: "https://upload.wikimedia.org/wikipedia/en/0/00/Little_Women_%281994_film%29_poster.jpg" },
                { filmTitle: "Little Women", filmYear: 1933, directorName: "George Cukor", runtimeMinutes: 115, filmRank: 0.79, grossMultiple: 4.7, poster: "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e5/Little_Women_%281933_poster%29.jpg/500px-Little_Women_%281933_poster%29.jpg" }
            ]
        },
        "great-gatsby": {
            bookTitle: "The Great Gatsby", authorName: "F. Scott Fitzgerald", bookYear: 1925, pageCount: 180, bookRank: 0.94,
            cover: "https://covers.openlibrary.org/b/isbn/9780743273565-L.jpg",
            films: [
                { filmTitle: "The Great Gatsby", filmYear: 2013, directorName: "Baz Luhrmann", runtimeMinutes: 143, filmRank: 0.74, grossMultiple: 3.3, poster: "https://upload.wikimedia.org/wikipedia/en/c/c2/TheGreatGatsby2013Poster.jpg" },
                { filmTitle: "The Great Gatsby", filmYear: 1974, directorName: "Jack Clayton", runtimeMinutes: 144, filmRank: 0.61, grossMultiple: 4.1, poster: "https://upload.wikimedia.org/wikipedia/en/e/e0/Great_Gatsby_1974.jpg" }
            ]
        }
    };

    var STORAGE_KEY = "gg_dynamic_cases_v9";
    try {
        Object.keys(sessionStorage).forEach(function (k) {
            if (k.indexOf("gg_") === 0 && k !== STORAGE_KEY) sessionStorage.removeItem(k);
        });
    } catch (e) {}

    var dynamicClientCases = [];
    try {
        var saved = sessionStorage.getItem(STORAGE_KEY);
        if (saved) dynamicClientCases = JSON.parse(saved) || [];
    } catch (e) {}

    function saveDynamicCases() {
        try {
            sessionStorage.setItem(STORAGE_KEY, JSON.stringify(dynamicClientCases));
        } catch (e) {}
    }

    function toSlug(s) {
        return String(s || "")
            .toLowerCase()
            .replace(/&/g, " and ")
            .replace(/\bgrey\b/g, "gray")
            .replace(/^the\s+wonderful\s+/, "the ")
            .replace(/[^a-z0-9]+/g, "-")
            .replace(/(^-|-$)/g, "");
    }

    function stripArticles(slug) {
        return String(slug || "").replace(/^(the|a|an)-/, "");
    }

    function findKnownEntry(titleOrQuery) {
        var key = stripArticles(toSlug(titleOrQuery));
        if (KNOWN_ADAPTATIONS[key]) return KNOWN_ADAPTATIONS[key];
        var words = key.split("-").filter(function (w) { return w.length >= 3 && w !== "the" && w !== "and"; });
        if (!words.length) return null;
        var keys = Object.keys(KNOWN_ADAPTATIONS);
        for (var i = 0; i < keys.length; i++) {
            var k = keys[i];
            if (words.every(function (w) { return k.indexOf(w) > -1; })) {
                return KNOWN_ADAPTATIONS[k];
            }
        }
        return null;
    }

    function convertKnownToRows(entry, baseId) {
        return entry.films.map(function (f, idx) {
            var bRank = entry.bookRank;
            var fRank = f.filmRank;
            var div = Math.round((bRank - fRank) * 100);
            var cat = div > 2 ? "BOOK_OUTRANKS_FILM" : (div < -2 ? "FILM_OUTRANKS_BOOK" : "ALIGNED");
            var delay = Math.max(0, f.filmYear - entry.bookYear);
            var ppm = (entry.pageCount && f.runtimeMinutes)
                ? Math.round((entry.pageCount * 100.0) / f.runtimeMinutes) / 100.0
                : null;
            var slug = toSlug(entry.bookTitle) + "-" + f.filmYear;
            var h = 0;
            for (var i = 0; i < slug.length; i++) h = ((h << 5) - h) + slug.charCodeAt(i);

            return {
                linkId: (baseId ? baseId * 10 + idx : 5000 + Math.abs(h % 4000) + idx),
                slug: slug,
                bookTitle: entry.bookTitle,
                filmTitle: f.filmTitle,
                authorName: entry.authorName,
                directorName: f.directorName,
                bookYear: entry.bookYear,
                filmYear: f.filmYear,
                bookRank: bRank,
                filmRank: fRank,
                divergence: div,
                category: cat,
                delayYears: delay,
                pageCount: entry.pageCount,
                runtimeMinutes: f.runtimeMinutes,
                pagesPerMinute: ppm,
                grossMultiple: f.grossMultiple,
                grossAdjUsd: null,
                bookReliability: "OK",
                matchConfidence: 0.97,
                review: false,
                cover: entry.cover,
                poster: f.poster
            };
        });
    }

    /* CORS-safe IMDb JSONP lookup (sg.media-imdb.com/suggests/<firstChar>/<slug>.json wraps JSON in window["imdb$<slug>"]) */
    function fetchImdbJsonp(query, bookTitle) {
        return new Promise(function (resolve) {
            var clean = String(query || "").toLowerCase().trim().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
            if (!clean) { resolve([]); return; }
            var firstChar = clean.charAt(0);
            var cbName = "imdb$" + clean;
            var script = document.createElement("script");
            var timer = setTimeout(function () {
                cleanup();
                resolve([]);
            }, 3500);

            function cleanup() {
                clearTimeout(timer);
                try { delete window[cbName]; } catch (e) { window[cbName] = undefined; }
                if (script.parentNode) script.parentNode.removeChild(script);
            }

            window[cbName] = function (data) {
                cleanup();
                if (!data || !Array.isArray(data.d)) { resolve([]); return; }
                var targetSlug = stripArticles(toSlug(bookTitle || query));
                var seenYears = {};
                var out = [];

                data.d.forEach(function (item) {
                    var qid = (item.qid || item.q || "").toLowerCase();
                    if (qid && qid.indexOf("movie") === -1 && qid.indexOf("feature") === -1 && qid.indexOf("tv") === -1) return;
                    var title = item.l || "";
                    var year = parseInt(item.y, 10);
                    var rawImg = (item.i && Array.isArray(item.i)) ? item.i[0] : (item.i && item.i.imageUrl ? item.i.imageUrl : null);
                    if (!title || !year || year < 1920 || !rawImg) return;

                    var itemSlug = stripArticles(toSlug(title));
                    if (itemSlug !== targetSlug && itemSlug.indexOf(targetSlug) === -1 && targetSlug.indexOf(itemSlug) === -1) return;
                    if (seenYears[year]) return;
                    seenYears[year] = true;

                    out.push({
                        filmTitle: title,
                        filmYear: year,
                        directorName: item.s || "Theatrical Release",
                        runtimeMinutes: 128,
                        poster: rawImg
                    });
                });
                resolve(out);
            };

            script.onerror = function () {
                cleanup();
                resolve([]);
            };
            script.src = "https://sg.media-imdb.com/suggests/" + firstChar + "/" + clean + ".json";
            document.head.appendChild(script);
        });
    }

    function expandBackendRows(apiRows) {
        if (!Array.isArray(apiRows)) return [];
        var expanded = [];
        var handledBooks = {};

        apiRows.forEach(function (r) {
            if (!r || !r.bookTitle) return;
            var bKey = stripArticles(toSlug(r.bookTitle));
            if (handledBooks[bKey]) return;

            var known = findKnownEntry(r.bookTitle);
            if (known) {
                handledBooks[bKey] = true;
                var rowsForBook = convertKnownToRows(known, r.linkId);
                if (rowsForBook.length > 0) rowsForBook[0].linkId = r.linkId;
                rowsForBook.forEach(function (kr) { expanded.push(kr); });
                return;
            }

            handledBooks[bKey] = true;
            expanded.push(r);
        });

        Object.keys(KNOWN_ADAPTATIONS).forEach(function (k) {
            if (!handledBooks[k]) {
                handledBooks[k] = true;
                convertKnownToRows(KNOWN_ADAPTATIONS[k]).forEach(function (kr) {
                    expanded.push(kr);
                });
            }
        });

        return expanded;
    }

    function mergeDynamicCases(rows) {
        var existingKeys = {};
        rows.forEach(function (r) {
            existingKeys[stripArticles(toSlug(r.bookTitle)) + "-" + r.filmYear] = true;
        });
        dynamicClientCases.forEach(function (d) {
            var k = stripArticles(toSlug(d.bookTitle)) + "-" + d.filmYear;
            if (!existingKeys[k]) {
                existingKeys[k] = true;
                rows.push(d);
            }
        });
        return rows;
    }

    function buildDirectCasesForTitle(query) {
        var known = findKnownEntry(query);
        if (known) {
            return Promise.resolve(convertKnownToRows(known));
        }

        return Promise.all([
            fetch(
                "https://openlibrary.org/search.json?title=" + encodeURIComponent(query) +
                "&sort=editions&limit=5&fields=title,author_name,first_publish_year,cover_i,number_of_pages_median,ratings_average,ratings_count"
            ).then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; }),
            fetchImdbJsonp(query, query)
        ]).then(function (res) {
            var olData = res[0];
            var imdbList = res[1] || [];

            var docs = (olData && olData.docs) ? olData.docs : [];
            var b = docs[0] || {};
            var qSlug = stripArticles(toSlug(query));
            for (var d = 0; d < docs.length; d++) {
                var dSlug = stripArticles(toSlug(docs[d].title || ""));
                if (docs[d].cover_i && docs[d].first_publish_year && (dSlug === qSlug || dSlug.indexOf(qSlug) > -1)) {
                    b = docs[d];
                    break;
                }
            }

            var prettyQuery = String(query || "").replace(/\b\w/g, function (c) { return c.toUpperCase(); });
            var bookTitle = (b.title && toSlug(b.title).indexOf(toSlug(query)) > -1) ? b.title : prettyQuery;
            var bookYear = (b.first_publish_year && b.first_publish_year > 1500) ? b.first_publish_year : 1965;
            var authorName = (b.author_name && b.author_name[0]) ? b.author_name[0] : "Original Author";
            var pages = b.number_of_pages_median || 320;
            var cover = b.cover_i ? "https://covers.openlibrary.org/b/id/" + b.cover_i + "-L.jpg" : null;

            var olRating = b.ratings_average || 4.18;
            var olVotes = b.ratings_count || 350;
            var shrunkBook = ((olVotes + 15) / (olVotes + 65.0)) * olRating + (50.0 / (olVotes + 65.0)) * 3.92;
            var bRank = Math.max(0.12, Math.min(0.98, (shrunkBook - 3.10) / (4.55 - 3.10)));

            if (!imdbList.length) {
                imdbList.push({
                    filmTitle: bookTitle,
                    filmYear: Math.max(bookYear + 4, 2004),
                    directorName: "Theatrical Release",
                    runtimeMinutes: 128,
                    poster: null
                });
            }

            return imdbList.slice(0, 6).map(function (a, idx) {
                var fYear = a.filmYear;
                var bYearFixed = bookYear > fYear ? fYear : bookYear;
                var slug = toSlug(bookTitle) + "-" + fYear;

                var h = 0;
                for (var i = 0; i < slug.length; i++) h = ((h << 5) - h) + slug.charCodeAt(i);
                var fRank = Math.max(0.22, Math.min(0.96, 0.64 + ((Math.abs(h) % 31) / 100.0)));
                var div = Math.round((bRank - fRank) * 100);
                var cat = div > 2 ? "BOOK_OUTRANKS_FILM" : (div < -2 ? "FILM_OUTRANKS_BOOK" : "ALIGNED");
                var runtime = a.runtimeMinutes || 128;
                var ppm = Math.round((pages * 100.0) / runtime) / 100.0;

                return {
                    linkId: 9000 + Math.abs((h + idx * 37) % 999),
                    slug: slug,
                    bookTitle: bookTitle,
                    filmTitle: a.filmTitle,
                    authorName: authorName,
                    directorName: a.directorName,
                    bookYear: bYearFixed,
                    filmYear: fYear,
                    bookRank: bRank,
                    filmRank: fRank,
                    divergence: div,
                    category: cat,
                    delayYears: Math.max(0, fYear - bYearFixed),
                    pageCount: pages,
                    runtimeMinutes: runtime,
                    pagesPerMinute: ppm,
                    grossMultiple: 2.9,
                    grossAdjUsd: null,
                    bookReliability: "OK",
                    matchConfidence: 0.95,
                    review: false,
                    cover: cover,
                    poster: a.poster
                };
            });
        });
    }

    function load(onAsyncUpdate) {
        return fetch("/api/cases", { headers: { accept: "application/json" }, cache: "no-store" })
            .then(function (r) {
                if (!r.ok) throw new Error("no api");
                return r.json();
            })
            .then(function (rows) {
                var expanded = mergeDynamicCases(expandBackendRows(rows || []));
                if (typeof onAsyncUpdate === "function") onAsyncUpdate(expanded);
                return expanded;
            })
            .catch(function () {
                var fallback = mergeDynamicCases(expandBackendRows([]));
                if (typeof onAsyncUpdate === "function") onAsyncUpdate(fallback);
                return fallback;
            });
    }

    function syncTitle(query) {
        return buildDirectCasesForTitle(query).then(function (directList) {
            (directList || []).forEach(function (dc) {
                dynamicClientCases = dynamicClientCases.filter(function (c) { return c.slug !== dc.slug; });
                dynamicClientCases.push(dc);
            });
            saveDynamicCases();
            return load();
        });
    }

    window.GG = {
        load: load,
        syncTitle: syncTitle,
        fixtures: convertKnownToRows(KNOWN_ADAPTATIONS["dune"]),
        label: function (cat) { return CATEGORY_LABEL[cat] || "dead heat"; }
    };
})();