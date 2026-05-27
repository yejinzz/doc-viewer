package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.detector.FileTypeDetector;
import com.sun.net.httpserver.*;
import org.slf4j.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class ViewHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(ViewHandler.class);

    private final DocViewerConfig config;
    private final DocumentConverter converter;
    private final ConversionCache cache;
    private final FileTypeDetector detector;

    public ViewHandler(DocViewerConfig config, DocumentConverter converter,
                       ConversionCache cache, FileTypeDetector detector) {
        this.config = config;
        this.converter = converter;
        this.cache = cache;
        this.detector = detector;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
        String rawPath = params.get("path");
        if (rawPath == null) { sendError(exchange, 400, "path parameter required"); return; }

        String filePath;
        try { filePath = URLDecoder.decode(rawPath, "UTF-8"); }
        catch (UnsupportedEncodingException e) { sendError(exchange, 400, "Invalid path"); return; }

        File file = new File(filePath);
        try { validatePath(filePath); }
        catch (SecurityException e) { sendError(exchange, 403, "Access denied"); return; }

        if (!file.exists() || !file.isFile()) { sendError(exchange, 404, "File not found"); return; }

        if (!detector.isSupported(file.getName())) {
            sendError(exchange, 415, "Unsupported file type: " + file.getName());
            return;
        }
        FileTypeDetector.RenderType serverType = detector.detect(file.getName());
        String clientRenderType;
        String fileUrl;

        if (serverType == FileTypeDetector.RenderType.LIBREOFFICE) {
            try {
                String cacheId = cache.getOrConvert(file, converter::convert);
                fileUrl = "/docviewer/file?id=" + cacheId;
                clientRenderType = "pdf";
            } catch (Exception e) {
                log.error("Conversion failed for {}", filePath, e);
                sendError(exchange, 500, "Conversion failed: " + e.getMessage());
                return;
            }
        } else {
            fileUrl = "/docviewer/file?path=" + URLEncoder.encode(filePath, "UTF-8");
            clientRenderType = serverType.name().toLowerCase();
        }

        String configJson = String.format(
            "{\"filename\":\"%s\",\"renderType\":\"%s\",\"fileUrl\":\"%s\"}",
            file.getName().replace("\"", "\\\""),
            clientRenderType,
            fileUrl
        );
        String html = loadTemplate(configJson);
        byte[] bytes = html.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }

    private String loadTemplate(String configJson) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/static/viewer.html")) {
            if (in == null) throw new IOException("viewer.html not found");
            return new String(in.readAllBytes(), "UTF-8")
                .replace("__DOCVIEWER_CONFIG__", configJson);
        }
    }

    private void validatePath(String filePath) {
        Path resolved = Paths.get(filePath).normalize().toAbsolutePath();
        if (config.allowedPaths.isEmpty()) return;
        for (String allowed : config.allowedPaths) {
            if (resolved.startsWith(Paths.get(allowed).toAbsolutePath())) return;
        }
        throw new SecurityException("Not in allowed paths: " + resolved);
    }

    private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        byte[] bytes = ("<html><body><p>" + msg + "</p></body></html>").getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
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
