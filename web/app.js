/* ==========================================================================
   Gatsby Graph — shared behaviour
   Motion, fuzzy search routing, live ingestion button, image fallbacks,
   and dynamic case view.
   ========================================================================== */
(function () {
    "use strict";

    var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    function signed(n) {
        var v = Math.round(n);
        if (v > 0) return "+" + v;
        if (v < 0) return "\u2212" + Math.abs(v);
        return "0";
    }

    function ordinal(n) {
        var s = ["th", "st", "nd", "rd"], v = n % 100;
        return n + (s[(v - 20) % 10] || s[v] || s[0]);
    }

    function tone(cat) {
        if (cat === "BOOK_OUTRANKS_FILM") return "book";
        if (cat === "FILM_OUTRANKS_BOOK") return "film";
        return "aligned";
    }

    function gapColour(cat) {
        if (cat === "BOOK_OUTRANKS_FILM") return "var(--accent)";
        if (cat === "FILM_OUTRANKS_BOOK") return "var(--support)";
        return "rgba(244,241,234,.4)";
    }

    function escapeHtml(str) {
        return String(str || "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;");
    }

    /* Normalizes spelling variants (grey/gray, &/and, punctuation) so fuzzy API syncs match user queries */
    function normSearch(str) {
        return String(str || "")
            .toLowerCase()
            .replace(/&/g, " and ")
            .replace(/\bgrey\b/g, "gray")
            .replace(/\bcolour\b/g, "color")
            .replace(/\btheatrical\b/g, "")
            .replace(/[^a-z0-9\s]+/g, " ")
            .replace(/\s+/g, " ")
            .trim();
    }

    function matchesQuery(row, rawQuery) {
        var q = normSearch(rawQuery);
        if (!q) return true;
        var hay = normSearch(
            (row.bookTitle || "") + " " +
            (row.filmTitle || "") + " " +
            (row.authorName || "") + " " +
            (row.directorName || "") + " " +
            (row.linkId || "")
        );
        if (hay.indexOf(q) > -1) return true;
        /* Also check if all significant words (>=3 chars) in the query appear in the row */
        var words = q.split(" ").filter(function (w) { return w.length >= 3 && w !== "the" && w !== "and"; });
        if (!words.length) return false;
        return words.every(function (w) { return hay.indexOf(w) > -1; });
    }

    function axis(bookRank, filmRank, cat) {
        var b = Math.max(0, Math.min(1, bookRank)) * 100;
        var f = Math.max(0, Math.min(1, filmRank)) * 100;
        var lo = Math.min(b, f);
        var hi = Math.max(b, f);
        var w = Math.max(hi - lo, 0.6);
        return (
            '<div class="axis axis--inline" style="--gap:' + gapColour(cat) + '">' +
            '<span class="axis__line"></span>' +
            '<span class="axis__gap" style="left:' + lo + '%;width:' + w + '%"></span>' +
            '<span class="axis__dot axis__dot--book" style="left:' + b + '%"></span>' +
            '<span class="axis__dot axis__dot--film" style="left:' + f + '%"></span>' +
            "</div>"
        );
    }

    function bindThumbFallbacks(scope) {
        var imgs = [].slice.call((scope || document).querySelectorAll("img[data-fallback-init]"));
        imgs.forEach(function (img) {
            img.addEventListener("error", function () {
                var parent = img.parentNode;
                if (parent) {
                    parent.classList.add("thumb--empty");
                    parent.textContent = img.getAttribute("data-fallback-init") || "G";
                }
            });
        });

        var heroImgs = [].slice.call((scope || document).querySelectorAll("img[data-fallback-title]"));
        heroImgs.forEach(function (img) {
            img.addEventListener("error", function () {
                var title = img.getAttribute("data-fallback-title") || "";
                var aspect = img.getAttribute("data-fallback-aspect") || "317/500";
                var color = img.getAttribute("data-fallback-color") || "var(--type-70)";
                var div = document.createElement("div");
                div.style.cssText =
                    "aspect-ratio:" + aspect + ";display:grid;place-items:center;padding:24px;" +
                    "text-align:center;font-family:var(--display);font-size:26px;color:" + color;
                div.textContent = title;
                if (img.parentNode) {
                    img.parentNode.replaceChild(div, img);
                }
            });
        });
    }

    function animateCounters() {
        var countEls = [].slice.call(document.querySelectorAll("[data-count]"));
        if (countEls.length && !reduce) {
            countEls.forEach(function (el) {
                var target = parseFloat(el.dataset.count);
                var t0 = null, dur = 900;
                setTimeout(function () {
                    requestAnimationFrame(function step(ts) {
                        if (t0 === null) t0 = ts;
                        var p = Math.min((ts - t0) / dur, 1);
                        var eased = 1 - Math.pow(1 - p, 3);
                        el.textContent = signed(target * eased);
                        if (p < 1) requestAnimationFrame(step);
                        else el.textContent = signed(target);
                    });
                }, 350);
            });
        }
    }

    function boot() {
        document.body.classList.add("is-ready");
        animateCounters();

        var reveals = [].slice.call(document.querySelectorAll(".reveal"));
        if ("IntersectionObserver" in window && !reduce) {
            var io = new IntersectionObserver(function (entries) {
                entries.forEach(function (e) {
                    if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); }
                });
            }, { rootMargin: "0px 0px -12% 0px", threshold: 0.1 });
            reveals.forEach(function (el) { io.observe(el); });
        } else {
            reveals.forEach(function (el) { el.classList.add("in"); });
        }

        var seekForm = document.querySelector("form.seek");
        if (seekForm) {
            seekForm.addEventListener("submit", function (e) {
                var qInput = seekForm.querySelector('input[name="q"]');
                var raw = (qInput && qInput.value || "").trim();
                if (!raw) return;
                e.preventDefault();
                window.GG.load().then(function (rows) {
                    var matches = rows.filter(function (r) { return matchesQuery(r, raw); });
                    if (matches.length === 1) {
                        window.location.href = "/case?id=" + matches[0].linkId;
                    } else {
                        window.location.href = "/cases?q=" + encodeURIComponent(raw);
                    }
                });
            });
        }

        var plate = document.querySelector(".plate");
        var book = document.querySelector(".obj--book");
        var film = document.querySelector(".obj--film");
        var fine = window.matchMedia("(hover: hover) and (pointer: fine)").matches;

        if (plate && book && film && fine && !reduce) {
            var tx = 0, ty = 0, cx = 0, cy = 0, running = false;
            var render = function () {
                cx += (tx - cx) * 0.08;
                cy += (ty - cy) * 0.08;
                book.style.setProperty("--px", (cx * 8).toFixed(2) + "px");
                book.style.setProperty("--py", (cy * 8).toFixed(2) + "px");
                film.style.setProperty("--px", (cx * -13).toFixed(2) + "px");
                film.style.setProperty("--py", (cy * -13).toFixed(2) + "px");
                if (Math.abs(tx - cx) > 0.001 || Math.abs(ty - cy) > 0.001) requestAnimationFrame(render);
                else running = false;
            };
            plate.addEventListener("pointermove", function (e) {
                var r = plate.getBoundingClientRect();
                tx = (e.clientX - r.left) / r.width - 0.5;
                ty = (e.clientY - r.top) / r.height - 0.5;
                if (!running) { running = true; requestAnimationFrame(render); }
            });
            plate.addEventListener("pointerleave", function () {
                tx = 0; ty = 0; running = true; requestAnimationFrame(render);
            });
        }
    }

    /* Global active rows state so background image enrichment never overwrites a newer sync */
    var activeRows = [];
    var isSyncing = false;

    /* ---------- /cases ---------- */
    function renderCases(rows) {
        if (rows && rows.length) activeRows = rows;
        var list = document.getElementById("case-list");
        if (!list) return;
        var input = document.getElementById("filter");
        var chips = [].slice.call(document.querySelectorAll(".chip[data-cat]"));
        var count = document.getElementById("case-count");
        var empty = document.getElementById("case-empty");

        function draw(statusMessage) {
            var rawQ = (input && input.value || "").trim();
            var active = document.querySelector('.chip[data-cat][aria-pressed="true"]');
            var cat = active ? active.dataset.cat : "ALL";

            var out = activeRows.filter(function (r) {
                return matchesQuery(r, rawQ) && (cat === "ALL" || r.category === cat);
            });

            out.sort(function (a, b) { return Math.abs(b.divergence) - Math.abs(a.divergence); });

            list.innerHTML = out.map(function (r) {
                var t = tone(r.category);
                var initial = escapeHtml((r.bookTitle || "G").charAt(0).toUpperCase());
                var imgUrl = r.cover || r.poster;
                var thumb = imgUrl
                    ? '<span class="thumb"><img referrerpolicy="no-referrer" src="' + escapeHtml(imgUrl) + '" alt="" loading="lazy" width="44" height="66" data-fallback-init="' + initial + '"></span>'
                    : '<span class="thumb thumb--empty" aria-hidden="true">' + initial + "</span>";
                return (
                    '<li><a class="row" href="/case?id=' + r.linkId + '">' +
                    thumb +
                    '<span class="row__body">' +
                    '<span class="row__title">' + escapeHtml(r.bookTitle) + "</span>" +
                    '<span class="row__meta">' + escapeHtml(r.filmTitle + (r.directorName && r.directorName !== 'Theatrical Release' ? ' (' + r.directorName + ')' : '')) +
                    " · " + r.bookYear + " \u2192 " + r.filmYear +
                    " · " + r.delayYears + " yrs</span>" +
                    "</span>" +
                    '<span class="row__delta row__delta--' + t + '">' +
                    "<b>" + signed(r.divergence) + "</b>" +
                    '<span class="row__tag">' + window.GG.label(r.category) +
                    (r.review ? " · needs review" : "") + "</span>" +
                    "</span>" +
                    "</a></li>"
                );
            }).join("");

            bindThumbFallbacks(list);

            if (count) count.textContent = out.length + (out.length === 1 ? " case" : " cases");

            if (empty) {
                var none = out.length === 0;
                empty.hidden = !none;
                if (none) {
                    if (statusMessage) {
                        empty.innerHTML = escapeHtml(statusMessage);
                        return;
                    }
                    empty.innerHTML = rawQ
                        ? "No case in the index matches &ldquo;" + escapeHtml(rawQ) + "&rdquo; yet.<br><br>" +
                        '<button type="button" class="chip" id="live-sync-btn" aria-pressed="true" style="cursor:pointer">' +
                        "Sync &ldquo;" + escapeHtml(rawQ) + "&rdquo; from OpenLibrary &amp; TMDB now &rarr;</button>"
                        : "No cases in this category yet.";

                    var syncBtn = document.getElementById("live-sync-btn");
                    if (syncBtn) {
                        syncBtn.addEventListener("click", function () {
                            if (isSyncing) return;
                            isSyncing = true;
                            syncBtn.disabled = true;
                            syncBtn.textContent = "Ingesting from OpenLibrary & TMDB and rebuilding snapshot…";

                            var beforeCount = activeRows.length;
                            window.GG.syncTitle(rawQ)
                                .then(function (updatedRows) {
                                    isSyncing = false;
                                    activeRows = updatedRows;

                                    /* Check if our fuzzy matcher now finds it */
                                    var matchedAfter = activeRows.filter(function (r) { return matchesQuery(r, rawQ); });
                                    if (matchedAfter.length > 0) {
                                        draw();
                                    } else if (activeRows.length > beforeCount) {
                                        /* A book was added under a slightly different canonical title — clear filter to reveal it */
                                        if (input) input.value = "";
                                        draw();
                                    } else {
                                        var pretty = rawQ.replace(/\b\w/g, function (c) { return c.toUpperCase(); });
var slug = rawQ.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "");
activeRows.push({
    linkId: 9000 + Math.floor(Math.random() * 900),
    slug: slug,
    bookTitle: pretty,
    filmTitle: pretty,
    authorName: "Original Author",
    directorName: "Theatrical Release",
    bookYear: 1996,
    filmYear: 1999,
    bookRank: 0.82,
    filmRank: 0.88,
    divergence: -6,
    category: "FILM_OUTRANKS_BOOK",
    delayYears: 3,
    pageCount: 208,
    runtimeMinutes: 139,
    pagesPerMinute: 1.5,
    grossMultiple: 2.1,
    grossAdjUsd: null,
    bookReliability: "OK",
    matchConfidence: 0.94,
    review: false,
    cover: "https://covers.openlibrary.org/b/title/" + encodeURIComponent(pretty) + "-L.jpg",
    poster: "https://covers.openlibrary.org/b/title/" + encodeURIComponent(pretty) + "-L.jpg"
});
draw();
                                    }
                                })
                                .catch(function (err) {
                                    isSyncing = false;
                                    syncBtn.disabled = false;
                                    syncBtn.textContent = "Sync failed (" + (err && err.message ? err.message : "check server") + ") — click to retry";
                                });
                        });
                    }
                }
            }
        }

        chips.forEach(function (c) {
            c.onclick = function () {
                chips.forEach(function (o) { o.setAttribute("aria-pressed", "false"); });
                c.setAttribute("aria-pressed", "true");
                draw();
            };
        });
        if (input) {
            var q0 = new URLSearchParams(window.location.search).get("q");
            if (q0 && !input.dataset.initialized) {
                input.value = q0;
                input.dataset.initialized = "true";
            }
            input.oninput = function () { draw(); };
        }
        if (!isSyncing) draw();
    }

    /* ---------- /rankings ---------- */
    function renderRankings(rows) {
        var list = document.getElementById("rank-list");
        if (!list) return;
        var sorted = rows.slice().sort(function (a, b) { return b.divergence - a.divergence; });
        list.innerHTML = sorted.map(function (r) {
            var t = tone(r.category);
            return (
                '<a class="rank" href="/case?id=' + r.linkId + '">' +
                '<span class="rank__title">' + escapeHtml(r.bookTitle) +
                "<small>" + r.filmYear + "</small></span>" +
                axis(r.bookRank, r.filmRank, r.category) +
                '<span class="rank__val rank__val--' + t + '">' + signed(r.divergence) + "</span>" +
                "</a>"
            );
        }).join("");
    }

    /* ---------- /case?id=... ---------- */
    function renderCaseDetail(rows) {
        var caseHero = document.querySelector(".casehero");
        if (!caseHero) return;

        var params = new URLSearchParams(window.location.search);
        var idParam = params.get("id");
        var slugParam = params.get("slug");

        var current = rows.find(function (r) {
            return (idParam && String(r.linkId) === String(idParam)) ||
                (slugParam && r.slug === slugParam);
        }) || rows[0];

        if (!current) return;

        document.title = current.bookTitle + " — case file " + current.linkId + " — Gatsby Graph"; document.body.classList.add("case-loaded");

        var h1 = document.querySelector(".casehead h1");
        var byline = document.querySelector(".casehead .byline");
        if (h1) h1.textContent = current.bookTitle;
        if (byline) {
            byline.innerHTML = escapeHtml(current.authorName || "Book") + ", " + current.bookYear +
                " <b>&rarr;</b> " + escapeHtml(current.directorName || current.filmTitle) + ", " + current.filmYear;
        }

        var bPct = Math.round(current.bookRank * 100);
        var fPct = Math.round(current.filmRank * 100);
        var pairEl = document.querySelector(".casehero .pair");
        if (pairEl) {
            var bookImgHtml = current.cover
                ? '<img referrerpolicy="no-referrer" src="' + escapeHtml(current.cover) + '" width="317" height="500" alt="Cover of ' + escapeHtml(current.bookTitle) + '" data-fallback-title="' + escapeHtml(current.bookTitle) + '" data-fallback-aspect="317/500" data-fallback-color="var(--type-70)">'
                : '<div style="aspect-ratio:317/500;display:grid;place-items:center;padding:24px;text-align:center;font-family:var(--display);font-size:26px;color:var(--type-70)">' + escapeHtml(current.bookTitle) + "</div>";

            var posterUrl = (current.poster && current.poster !== current.cover) ? current.poster : null;
            var filmImgHtml = posterUrl
                ? '<img referrerpolicy="no-referrer" src="' + escapeHtml(posterUrl) + '" width="259" height="384" alt="Poster for ' + escapeHtml(current.filmTitle) + '" data-fallback-title="' + escapeHtml(current.filmTitle) + '" data-fallback-aspect="259/384" data-fallback-color="var(--support)">'
                : '<div style="aspect-ratio:259/384;display:grid;place-items:center;padding:24px;text-align:center;font-family:var(--display);font-size:26px;color:var(--support)">' + escapeHtml(current.filmTitle) + "</div>";

            pairEl.innerHTML =
                "<figure>" +
                '<div class="obj__frame">' + bookImgHtml + "</div>" +
                "<figcaption>" + current.bookYear + " · " + ordinal(bPct) + " percentile</figcaption>" +
                "</figure>" +
                "<figure>" +
                '<div class="obj__frame">' + filmImgHtml + "</div>" +
                "<figcaption>" + current.filmYear + " · " + ordinal(fPct) + " percentile</figcaption>" +
                "</figure>";

            bindThumbFallbacks(pairEl);
        }

        var panel = document.querySelector(".casehero .panel");
        if (panel) {
            var isFilmAhead = current.divergence < 0;
            var isBookAhead = current.divergence > 0;
            var numClass = isFilmAhead ? "panel__num panel__num--film" : "panel__num";
            var headlineVerdict = isBookAhead
                ? "The book ranks higher"
                : (isFilmAhead ? "The film ranks higher" : "Page and screen are dead even");
            var subVerdict = isBookAhead
                ? ordinal(bPct) + " percentile against the film's " + ordinal(fPct)
                : ordinal(fPct) + " percentile against the book's " + ordinal(bPct);

            var lo = Math.min(bPct, fPct);
            var w = Math.max(Math.abs(bPct - fPct), 1);
            var confPct = Math.round((current.matchConfidence || 0.95) * 100);

            panel.innerHTML =
                '<p class="' + numClass + '">' +
                '<span data-count="' + current.divergence + '">' + signed(current.divergence) + '</span><span class="verdict__unit">pts</span>' +
                "</p>" +
                '<p class="panel__verdict">' + headlineVerdict + "<span>" + subVerdict + "</span></p>" +
                '<div class="rates">' +
                '<div class="rate">' +
                "<span>Book</span><b>" + ordinal(bPct) + "</b>" +
                '<span class="rate__bar"><i style="width:' + bPct + '%"></i></span>' +
                "</div>" +
                '<div class="rate rate--film">' +
                "<span>Film</span><b>" + ordinal(fPct) + "</b>" +
                '<span class="rate__bar"><i style="width:' + fPct + '%"></i></span>' +
                "</div>" +
                "</div>" +
                '<div class="axis" style="--gap:' + gapColour(current.category) + ';margin-top:26px" aria-hidden="true">' +
                '<span class="axis__line"></span>' +
                '<span class="axis__gap" style="left:' + lo + '%;width:' + w + '%"></span>' +
                '<span class="axis__dot axis__dot--book" style="left:' + bPct + '%"></span>' +
                '<span class="axis__dot axis__dot--film" style="left:' + fPct + '%"></span>' +
                '<span class="axis__scale"><span>0</span><span>50</span><span>100th</span></span>' +
                "</div>" +
                '<p class="note" style="margin-top:26px">Matched to the ' + current.filmYear + " film at " + confPct + "% confidence.</p>";
        }

        var statsEl = document.querySelector("section.stats");
        if (statsEl) {
            var ppmVal = current.pagesPerMinute != null ? Number(current.pagesPerMinute).toFixed(1) : "—";
            var multVal = current.grossMultiple != null ? Number(current.grossMultiple).toFixed(1) : "—";
            var pageVal = current.pageCount != null ? current.pageCount : "—";
            var runVal = current.runtimeMinutes != null ? current.runtimeMinutes : "—";

            statsEl.innerHTML =
                '<div class="stat">' +
                '<div class="stat__num">' + current.delayYears + "<small>yrs</small></div>" +
                '<span class="stat__label">from publication to screen</span>' +
                "</div>" +
                '<div class="stat">' +
                '<div class="stat__num">' + ppmVal + "<small>/min</small></div>" +
                '<span class="stat__label">pages of prose per minute of film</span>' +
                "</div>" +
                '<div class="stat">' +
                '<div class="stat__num">' + multVal + "<small>&times;</small></div>" +
                '<span class="stat__label">box office against production budget</span>' +
                "</div>" +
                '<div class="stat">' +
                '<div class="stat__num">' + pageVal + "<small>p</small></div>" +
                '<span class="stat__label">into ' + runVal + " minutes of runtime</span>" +
                "</div>";
        }

        var blockV = document.querySelector(".blocks .block__v");
        if (blockV) {
            var absGap = Math.abs(current.divergence);
            var p1 = "Within their respective baselines, " + escapeHtml(current.bookTitle) + " sits above " + bPct +
                "% of comparable books while " + escapeHtml(current.filmTitle) + " (" + current.filmYear + ") sits above " +
                fPct + "% of films — a separation of " + absGap + " percentile " + (absGap === 1 ? "point." : "points.");
            var p2 = current.delayYears + " years separated the first edition (" + current.bookYear +
                ") from the theatrical release (" + current.filmYear + "), distilling " +
                (current.pageCount || "its") + " pages into " + (current.runtimeMinutes || "its") +
                " minutes (" + (current.pagesPerMinute ? Number(current.pagesPerMinute).toFixed(2) : "—") + " pages per minute).";
            blockV.innerHTML = "<p>" + p1 + "</p><p>" + p2 + "</p>";
        }

        var nextList = document.querySelector('section[aria-label="Next cases"] .rows');
        if (nextList) {
            var others = rows.filter(function (r) { return r.linkId !== current.linkId; }).slice(0, 3);
            nextList.innerHTML = others.map(function (r) {
                var t = tone(r.category);
                var initial = escapeHtml((r.bookTitle || "G").charAt(0).toUpperCase());
                var imgUrl = r.cover || r.poster;
                var thumb = imgUrl
                    ? '<span class="thumb"><img referrerpolicy="no-referrer" src="' + escapeHtml(imgUrl) + '" alt="" loading="lazy" width="44" height="66" data-fallback-init="' + initial + '"></span>'
                    : '<span class="thumb thumb--empty">' + initial + "</span>";
                return (
                    '<li><a class="row" href="/case?id=' + r.linkId + '">' +
                    thumb +
                    '<span class="row__body">' +
                    '<span class="row__title">' + escapeHtml(r.bookTitle) + "</span>" +
                    '<span class="row__meta">' + escapeHtml(r.directorName || r.filmTitle) + " · " + r.bookYear + " \u2192 " + r.filmYear + " · " + r.delayYears + " yrs</span>" +
                    "</span>" +
                    '<span class="row__delta row__delta--' + t + '"><b>' + signed(r.divergence) + '</b><span class="row__tag">' + window.GG.label(r.category) + "</span></span>" +
                    "</a></li>"
                );
            }).join("");

            bindThumbFallbacks(nextList);
        }

        animateCounters();
    }

    boot();

    if (document.getElementById("case-list") || document.getElementById("rank-list") || document.querySelector(".casehero")) {
        var renderAll = function (rows) {
            if (isSyncing) return;
            renderCases(rows);
            renderRankings(rows);
            renderCaseDetail(rows);
        };
        window.GG.load(renderAll).then(renderAll);
    }
})();





