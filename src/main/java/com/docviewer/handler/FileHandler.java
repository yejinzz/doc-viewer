package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.registry.FileKeyRegistry;
import com.sun.net.httpserver.*;
import org.slf4j.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class FileHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(FileHandler.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{1,100}$");
    private static final Pattern CACHE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9]{1,64}$");

    private final ConversionCache cache;
    private final DocViewerConfig config;
    private final FileTypeDetector detector;
    private final FileKeyRegistry registry;

    public FileHandler(ConversionCache cache, DocViewerConfig config,
                       FileTypeDetector detector, FileKeyRegistry registry) {
        this.cache = cache;
        this.config = config;
        this.detector = detector;
        this.registry = registry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
        try {
            if (params.containsKey("id")) {
                serveById(exchange, params.get("id"));
            } else if (params.containsKey("key")) {
                serveByKey(exchange, params.get("key"));
            } else {
                exchange.sendResponseHeaders(400, -1);
            }
        } catch (Exception e) {
            log.error("FileHandler error", e);
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    private void serveById(HttpExchange exchange, String cacheId) throws IOException {
        if (!CACHE_ID_PATTERN.matcher(cacheId).matches()) {
            exchange.sendResponseHeaders(400, -1); return;
        }
        Path path = cache.cachedPath(cacheId);
        if (!Files.exists(path)) { exchange.sendResponseHeaders(404, -1); return; }
        serveFile(exchange, path, "application/pdf", cacheId + ".pdf");
    }

    private void serveByKey(HttpExchange exchange, String key) throws Exception {
        if (!KEY_PATTERN.matcher(key).matches()) {
            exchange.sendResponseHeaders(400, -1); return;
        }
        FileKeyRegistry.FileKeyEntry entry = registry.findByKey(key);
        if (entry == null) { exchange.sendResponseHeaders(404, -1); return; }

        Path resolved = Paths.get(entry.filePath).normalize().toAbsolutePath();
        if (!config.allowedPaths.isEmpty()) {
            boolean allowed = config.allowedPaths.stream()
                .anyMatch(ap -> resolved.startsWith(Paths.get(ap).toAbsolutePath()));
            if (!allowed) { exchange.sendResponseHeaders(403, -1); return; }
        }
        if (!Files.exists(resolved)) { exchange.sendResponseHeaders(404, -1); return; }

        String filename = entry.originalName != null ? entry.originalName
            : resolved.getFileName().toString();
        serveFile(exchange, resolved, detector.mimeType(filename), filename);
    }

    private void serveFile(HttpExchange exchange, Path path, String contentType, String filename) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Disposition",
            "inline; filename=\"" + filename.replace("\"", "") + "\"");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private Map<String, String> queryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }
}
