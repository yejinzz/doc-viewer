package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.registry.FileKeyRegistry;
import com.docviewer.security.IpWhitelistFilter;
import com.docviewer.util.HashUtil;
import com.sun.net.httpserver.*;
import org.slf4j.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ApiHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{1,100}$");

    private final DocViewerConfig config;
    private final DocumentConverter converter;
    private final ConversionCache cache;
    private final FileTypeDetector detector;
    private final FileKeyRegistry registry;
    private final IpWhitelistFilter apiFilter;

    public ApiHandler(DocViewerConfig config, DocumentConverter converter, ConversionCache cache,
                      FileTypeDetector detector, FileKeyRegistry registry, IpWhitelistFilter apiFilter) {
        this.config = config;
        this.converter = converter;
        this.cache = cache;
        this.detector = detector;
        this.registry = registry;
        this.apiFilter = apiFilter;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String remoteIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!apiFilter.isAllowed(remoteIp)) {
            sendJson(exchange, 403, "{\"status\":\"error\",\"message\":\"IP not allowed\"}");
            return;
        }
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        try {
            if ("POST".equals(method) && path.equals("/docviewer/api/convert")) {
                handleConvert(exchange);
            } else if ("POST".equals(method) && path.equals("/docviewer/api/refresh")) {
                handleRefresh(exchange);
            } else if ("DELETE".equals(method) && path.startsWith("/docviewer/api/key/")) {
                handleDelete(exchange, path.substring("/docviewer/api/key/".length()));
            } else if ("GET".equals(method) && path.startsWith("/docviewer/api/status/")) {
                handleStatus(exchange, path.substring("/docviewer/api/status/".length()));
            } else {
                sendJson(exchange, 404, "{\"status\":\"error\",\"message\":\"Not found\"}");
            }
        } catch (Exception e) {
            log.error("API error", e);
            sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"Internal error\"}");
        }
    }

    private void handleConvert(HttpExchange exchange) throws Exception {
        Map<String, String> req = parseJsonBody(exchange);
        String key = req.get("key");
        String filePath = req.get("path");
        String originalName = req.getOrDefault("originalName", "");

        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid key format\"}");
            return;
        }
        if (filePath == null || filePath.isEmpty()) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"path required\"}");
            return;
        }

        Path resolved;
        try { resolved = Paths.get(filePath).normalize().toAbsolutePath(); }
        catch (Exception e) { sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid path\"}"); return; }

        if (!config.allowedPaths.isEmpty()) {
            boolean allowed = config.allowedPaths.stream()
                .anyMatch(ap -> resolved.startsWith(Paths.get(ap).toAbsolutePath()));
            if (!allowed) { sendJson(exchange, 403, "{\"status\":\"error\",\"message\":\"Path not allowed\"}"); return; }
        }

        File file = resolved.toFile();
        if (!file.exists() || !file.isFile()) {
            sendJson(exchange, 404, "{\"status\":\"error\",\"message\":\"File not found\"}"); return;
        }
        if (Files.isSymbolicLink(resolved)) {
            sendJson(exchange, 403, "{\"status\":\"error\",\"message\":\"Symbolic links not allowed\"}"); return;
        }
        if (file.length() > config.maxFileSizeBytes) {
            sendJson(exchange, 413, "{\"status\":\"error\",\"message\":\"File too large (max " + (config.maxFileSizeBytes / 1024 / 1024) + "MB)\"}"); return;
        }
        String displayName = originalName.isEmpty() ? file.getName() : originalName;
        if (!detector.isSupported(displayName)) {
            sendJson(exchange, 415, "{\"status\":\"error\",\"message\":\"Unsupported file type\"}"); return;
        }

        try {
            registry.register(key, resolved.toString(), displayName);
            cache.getOrConvert(file, converter::convert);
            String hash = HashUtil.sha256File(file);
            registry.markConverted(key, hash, file.length(), file.lastModified());
            log.info("Converted and registered key={} path={}", key, resolved);
            sendJson(exchange, 200, "{\"status\":\"ok\",\"key\":\"" + key + "\"}");
        } catch (Exception e) {
            try { registry.markError(key, e.getMessage()); } catch (Exception ignored) {}
            log.error("Conversion failed for key={}", key, e);
            sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"Conversion failed: " + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleRefresh(HttpExchange exchange) throws Exception {
        String query = exchange.getRequestURI().getRawQuery();
        String key = queryParam(query, "key");
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid key\"}"); return;
        }
        FileKeyRegistry.FileKeyEntry entry = registry.findByKey(key);
        if (entry == null) {
            sendJson(exchange, 404, "{\"status\":\"error\",\"message\":\"Key not found\"}"); return;
        }
        File file = new File(entry.filePath);
        if (!file.exists()) {
            sendJson(exchange, 404, "{\"status\":\"error\",\"message\":\"File not found\"}"); return;
        }
        try {
            cache.invalidateCache(file);
            cache.getOrConvert(file, converter::convert);
            String hash = HashUtil.sha256File(file);
            registry.markConverted(key, hash, file.length(), file.lastModified());
            sendJson(exchange, 200, "{\"status\":\"ok\",\"key\":\"" + key + "\"}");
        } catch (Exception e) {
            log.error("Refresh failed for key={}", key, e);
            sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"Refresh failed\"}");
        }
    }

    private void handleDelete(HttpExchange exchange, String key) throws Exception {
        if (key.isEmpty() || !KEY_PATTERN.matcher(key).matches()) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid key\"}"); return;
        }
        registry.delete(key);
        sendJson(exchange, 200, "{\"status\":\"ok\"}");
    }

    private void handleStatus(HttpExchange exchange, String key) throws Exception {
        if (key.isEmpty() || !KEY_PATTERN.matcher(key).matches()) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid key\"}"); return;
        }
        String status = registry.getStatus(key);
        if (status == null) {
            sendJson(exchange, 404, "{\"status\":\"error\",\"message\":\"Key not found\"}"); return;
        }
        sendJson(exchange, 200, "{\"status\":\"ok\",\"convertStatus\":\"" + status + "\"}");
    }

    private Map<String, String> parseJsonBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), "UTF-8");
        Map<String, String> map = new LinkedHashMap<>();
        Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(body);
        while (m.find()) map.put(m.group(1), m.group(2));
        return map;
    }

    private String queryParam(String query, String name) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    private void sendJson(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
