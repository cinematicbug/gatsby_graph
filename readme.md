# Gatsby Graph

**Same story. Two versions. *One* verdict.**

[**Live Demo → cinematicbug.github.io/gatsby_graph**](https://cinematicbug.github.io/gatsby_graph/)

Gatsby Graph is a full-stack literary and cinema analytics platform that scores books directly against their theatrical film adaptations. Rather than comparing raw star ratings across incompatible scales, it normalizes reader reception and box-office/critical film reception into unified percentile ranks (`0–100th`) to compute a single **Adaptation Divergence Score**,answering whether the page or the screen deserves your evening.

---

## Don't See Your Book? Use the Live **Sync** Button!

> **Important Usage Note:** If you search for a book or movie in the **Case Files** directory and it does not appear in the initial index, **do not worry!**
>
> Simply type the title into the search bar on the **Case Files** page and click the **"Sync"** button that appears when no local match is found.
>
> **What happens when you click Sync:**
> 1. Gatsby Graph queries **OpenLibrary's Canonical Edition API** in real time to pull the original first-edition publication year, author, median page count, and verified book cover.
> 2. Simultaneously, it queries **IMDb's CloudFront Suggestion API** (`v3.sg.media-imdb.com`) to discover **all theatrical screen adaptations** of that book, complete with release years, principal credits, and official theatrical posters.
> 3. It computes Bayesian-shrunk percentile ranks, adaptation delay (`filmYear − bookYear`), and narrative compression (`pagesPerMinute`), then injects the newly synthesized case file(s) directly into your active session!

---

## Key Features

* **Multi-Adaptation Engine:** Books adapted across multiple eras are never collapsed into a single generic entry. Classics like *Dune* (1984, 2021, 2024), *Wuthering Heights* (1939, 1992, 2009, 2011, 2026), *Pride and Prejudice* (1940, 1995, 2005), *Little Women* (1933, 1994, 2019), and *Rebecca* (1940, 2020) resolve into distinct, year-keyed case files so you can compare how different directors interpreted the same source material.
* **Adaptation Divergence Scoring:** Calculates a signed point spread (`Book Percentile − Film Percentile`) categorizing every pairing into **Book Ahead** (`+pts`), **Film Ahead** (`−pts`), or **Dead Heat**.
* **Narrative Compression & Delay Metrics:** Tracks how many years it took a novel to reach theaters (`delayYears`) alongside its **Pages-Per-Minute (`PPM`)** density ratio (`pageCount / runtimeMinutes`).
* **Zero-Lag Preloaded Showcase & 3D Motion UI:** Features an editorial dark-mode interface with a 3D floating cover/poster plate, cursor-tracked radial spotlight, and an auto-rotating hero showcase that preloads and decodes image pairs in memory (`Image.decode()`) for synchronized crossfades.
* **Hybrid Deployment (Java Backend + Static Offline-First Adapter):** Runs seamlessly against the local Java + MySQL backend (`/api/cases`, `/api/sync`) OR as a standalone client-side web app on GitHub Pages with `sessionStorage` asset caching (`gg_asset_cache_v1`).

---

## Architecture & Tech Stack

* **Frontend:** Vanilla HTML5, CSS3 (custom editorial design system, fluid typography, 3D keyframe physics), and ES6+ JavaScript (`web/app.js`, `web/data.js`).
* **Backend:** Java HTTP Server + MySQL relational schema storing canonical book-to-film link records, percentile rankings, and match confidence scores.
* **External Data Sources & APIs:**
    * **OpenLibrary Search & Covers API:** Canonical first-publish years, author attribution, page counts, ratings, and ISBN-13 / Cover ID artwork.
    * **IMDb CloudFront Suggestion API (`v3.sg.media-imdb.com`):** CORS-enabled live theatrical film discovery, release years, and high-resolution poster hydration.
    * **TMDB / Backend Sync Pipeline:** Server-side ingestion for box-office gross multiples and adaptation metadata.

---

## Running Locally

### 1. Clone the Repository
```bash
git clone [https://github.com/cinematicbug/gatsby_graph.git](https://github.com/cinematicbug/gatsby_graph.git)
cd gatsby_graph
```

### 2. Run With the Java Backend (Full API + Database Mode)
1. Configure your MySQL instance and database credentials in the Java project configuration.
2. Build and run the Java server from your IDE (IntelliJ IDEA) or via Maven/Gradle.
3. Open **`http://localhost:8080`** in your browser.

### 3. Run as a Static Frontend (Zero-Setup Mode)
Because `data.js` includes an autonomous multi-adaptation fallback and live API hydration layer, you can also run the frontend without starting MySQL or the Java server:
```bash
npx serve .
```
Or simply visit the live GitHub Pages deployment at **[https://cinematicbug.github.io/gatsby_graph/](https://cinematicbug.github.io/gatsby_graph/)**.

---

## Project Structure

```text
gatsby_graph/
├── src/                  # Java backend source (controllers, sync service, DAO, scoring)
├── web/                  # Source frontend assets served by the Java server
│   ├── index.html        # Home page with 3D hero showcase & quick search
│   ├── cases.html        # Searchable, filterable directory + Live Sync button
│   ├── case.html         # Individual adaptation breakdown & percentile bars
│   ├── rankings.html     # Leaderboards (Book Ahead, Film Ahead, Most Compressed)
│   ├── method.html       # Statistical methodology & Bayesian shrinkage documentation
│   ├── styles.css        # Editorial dark-theme stylesheet & motion animations
│   ├── data.js           # Multi-adaptation engine, live Sync & API hydration
│   └── app.js            # UI rendering, fuzzy search, sorting & hero rotator
├── index.html            # Root mirror for GitHub Pages static deployment
└── README.md             # Project documentation
```