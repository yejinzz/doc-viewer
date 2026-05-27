package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.detector.FileTypeDetector;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class FileHandler implements HttpHandler {
    private final ConversionCache cache;
    private final DocViewerConfig config;
    private final FileTypeDetector detector;

    public FileHandler(ConversionCache cache, DocViewerConfig config, FileTypeDetector detector) {
        this.cache = cache;
        this.config = config;
        this.detector = detector;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
        try {
            if (params.containsKey("id")) {
                serveById(exchange, params.get("id"));
            } else if (params.containsKey("path")) {
                serveByPath(exchange, URLDecoder.decode(params.get("path"), "UTF-8"));
            } else {
                exchange.sendResponseHeaders(400, -1);
            }
        } finally {
            exchange.close();
        }
    }

    private void serveById(HttpExchange exchange, String cacheId) throws IOException {
        Path path = cache.cachedPath(cacheId);
        if (!Files.exists(path)) { exchange.sendResponseHeaders(404, -1); return; }
        serveFile(exchange, path, "application/pdf", cacheId + ".pdf");
    }

    private void serveByPath(HttpExchange exchange, String rawPath) throws IOException {
        Path resolved;
        try {
            resolved = validatePath(rawPath);
        } catch (SecurityException e) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }
        if (!Files.exists(resolved)) { exchange.sendResponseHeaders(404, -1); return; }
        String filename = resolved.getFileName().toString();
        serveFile(exchange, resolved, detector.mimeType(filename), filename);
    }

    private void serveFile(HttpExchange exchange, Path path, String contentType, String filename) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + filename + "\"");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Path validatePath(String rawPath) {
        Path resolved = Paths.get(rawPath).normalize().toAbsolutePath();
        if (config.allowedPaths.isEmpty()) return resolved;
        for (String allowed : config.allowedPaths) {
            if (resolved.startsWith(Paths.get(allowed).toAbsolutePath())) return resolved;
        }
        throw new SecurityException("Path not in allowed list: " + resolved);
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
