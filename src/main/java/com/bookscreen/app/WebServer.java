package com.bookscreen.app;

import com.bookscreen.dao.AnalyticsDao;
import com.bookscreen.dao.AnalyticsDao.WebCaseDto;
import com.bookscreen.dao.SnapshotDao;
import com.bookscreen.service.SyncOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WebServer {

    private final int port;
    private final Path webRoot;
    private final AnalyticsDao analyticsDao;
    private final SyncOrchestrator orchestrator;
    private final SnapshotDao snapshotDao;
    private final ObjectMapper mapper;

    public WebServer(
            int port,
            Path webRoot,
            AnalyticsDao analyticsDao,
            SyncOrchestrator orchestrator,
            SnapshotDao snapshotDao,
            ObjectMapper mapper
    ) {
        this.port = port;
        this.webRoot = webRoot;
        this.analyticsDao = analyticsDao;
        this.orchestrator = orchestrator;
        this.snapshotDao = snapshotDao;
        this.mapper = mapper;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // 1. GET /api/cases
        server.createContext("/api/cases", ex -> {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                List<WebCaseDto> cases = analyticsDao.fetchWebCases(mapper);
                sendJson(ex, 200, mapper.writeValueAsString(cases));
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // 2. GET /api/case?id=108 or ?slug=dune
        server.createContext("/api/case", ex -> {
            try {
                Map<String, String> params = parseQuery(ex.getRequestURI().getRawQuery());
                String idParam = params.get("id");
                String slugParam = params.get("slug");
                List<WebCaseDto> cases = analyticsDao.fetchWebCases(mapper);

                WebCaseDto found = cases.stream()
                        .filter(c -> (idParam != null && String.valueOf(c.linkId()).equals(idParam))
                                || (slugParam != null && c.slug().equalsIgnoreCase(slugParam)))
                        .findFirst()
                        .orElse(null);

                if (found == null) {
                    sendJson(ex, 404, "{\"error\":\"Not found\"}");
                } else {
                    sendJson(ex, 200, mapper.writeValueAsString(found));
                }
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // 3. POST /api/sync?q=The+War+of+the+Worlds (Live on-demand ingestion from browser search!)
        server.createContext("/api/sync", ex -> {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"error\":\"Use POST\"}");
                return;
            }
            try {
                Map<String, String> params = parseQuery(ex.getRequestURI().getRawQuery());
                String query = params.getOrDefault("q", "").trim();
                String mode = params.getOrDefault("mode", "title").trim();
                if (query.isEmpty()) {
                    sendJson(ex, 400, "{\"error\":\"Missing query parameter q\"}");
                    return;
                }
                orchestrator.syncByTrigger(mode, query, 5);
                snapshotDao.rebuildAndSwapSnapshot(null);
                List<WebCaseDto> updated = analyticsDao.fetchWebCases(mapper);
                sendJson(ex, 200, mapper.writeValueAsString(updated));
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // 4. Static files & clean routes (/ -> index.html, /cases -> cases.html, /case -> case.html)
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String file = switch (path) {
                case "/", "/index.html" -> "index.html";
                case "/cases", "/cases.html", "/copies.html" -> "cases.html";
                case "/rankings", "/rankings.html" -> "rankings.html";
                case "/method", "/method.html" -> "method.html";
                case "/case", "/case.html" -> "case.html";
                default -> path.startsWith("/") ? path.substring(1) : path;
            };

            Path target = webRoot.resolve(file).normalize();
            if (!target.startsWith(webRoot) || !Files.exists(target) || Files.isDirectory(target)) {
                byte[] notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(notFound); }
                return;
            }

            String mime = Files.probeContentType(target);
            if (file.endsWith(".css")) mime = "text/css; charset=utf-8";
            else if (file.endsWith(".js")) mime = "application/javascript; charset=utf-8";
            else if (file.endsWith(".html")) mime = "text/html; charset=utf-8";

            byte[] bytes = Files.readAllBytes(target);
            ex.getResponseHeaders().set("Content-Type", mime != null ? mime : "application/octet-stream");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.start();
        System.out.println("Gatsby Graph live at http://localhost:" + port);
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return map;
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }
}