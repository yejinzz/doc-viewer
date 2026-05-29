package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.registry.FileKeyRegistry;
import com.docviewer.security.LicenseChecker;
import com.docviewer.util.HashUtil;
import com.sun.net.httpserver.*;
import org.slf4j.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ViewHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(ViewHandler.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{1,100}$");

    private final DocViewerConfig config;
    private final DocumentConverter converter;
    private final ConversionCache cache;
    private final FileTypeDetector detector;
    private final FileKeyRegistry registry;
    private final LicenseChecker license;

    public ViewHandler(DocViewerConfig config, DocumentConverter converter, ConversionCache cache,
                       FileTypeDetector detector, FileKeyRegistry registry, LicenseChecker license) {
        this.config = config;
        this.converter = converter;
        this.cache = cache;
        this.detector = detector;
        this.registry = registry;
        this.license = license;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!license.isAllowed(exchange)) {
            sendError(exchange, 403, null, "접근이 허용되지 않습니다");
            return;
        }

        Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
        String key = params.get("key");

        if (key == null) { sendError(exchange, 400, null, "key 파라미터가 필요합니다"); return; }
        if (!KEY_PATTERN.matcher(key).matches()) { sendError(exchange, 400, null, "잘못된 요청입니다"); return; }

        FileKeyRegistry.FileKeyEntry entry;
        try { entry = registry.findByKey(key); }
        catch (Exception e) { sendError(exchange, 500, null, "서버 오류가 발생했습니다"); return; }

        if (entry == null) {
            sendError(exchange, 404, null, "등록되지 않은 문서입니다. CMS에서 변환을 먼저 진행해주세요.");
            return;
        }
        if (!"converted".equals(entry.convertStatus)) {
            sendError(exchange, 404, null, "아직 변환되지 않은 문서입니다. CMS에서 변환을 먼저 진행해주세요.");
            return;
        }

        File file = new File(entry.filePath);
        if (!file.exists() || !file.isFile()) {
            sendError(exchange, 404, key, "파일을 찾을 수 없습니다");
            return;
        }

        checkAndUpdateHashIfChanged(key, entry, file);

        String displayName = entry.originalName != null ? entry.originalName : file.getName();
        FileTypeDetector.RenderType renderType = detector.detect(displayName);
        String clientRenderType;
        String fileUrl;

        if (renderType == FileTypeDetector.RenderType.LIBREOFFICE) {
            try {
                String cacheId = cache.getOrConvert(file, converter::convert);
                fileUrl = "/docviewer/file?id=" + cacheId;
                clientRenderType = "pdf";
            } catch (Exception e) {
                log.error("Conversion failed for key={}", key, e);
                sendError(exchange, 500, key, "문서를 변환할 수 없습니다: " + e.getMessage());
                return;
            }
        } else if (renderType == FileTypeDetector.RenderType.HWP) {
            fileUrl = "/docviewer/file?key=" + key;
            clientRenderType = "hwp";
        } else {
            fileUrl = "/docviewer/file?key=" + key;
            clientRenderType = renderType.name().toLowerCase();
        }

        String configJson = String.format(
            "{\"filename\":\"%s\",\"renderType\":\"%s\",\"fileUrl\":\"%s\",\"downloadKey\":\"%s\"}",
            escapeJson(displayName), clientRenderType, fileUrl, key
        );
        String html;
        try { html = loadTemplate(configJson); }
        catch (IOException e) { sendError(exchange, 500, null, "뷰어를 로드할 수 없습니다"); return; }

        byte[] bytes = html.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }

    private void checkAndUpdateHashIfChanged(String key, FileKeyRegistry.FileKeyEntry entry, File file) {
        boolean quickChanged = file.length() != entry.fileSize || file.lastModified() != entry.lastModified;
        if (!quickChanged) return;
        try {
            String currentHash = HashUtil.sha256File(file);
            registry.updateMetadata(key, currentHash, file.length(), file.lastModified());
            if (!currentHash.equals(entry.fileHash)) {
                log.info("File content changed for key={}, cache will auto-invalidate on next getOrConvert", key);
            }
        } catch (Exception e) {
            log.warn("Hash check failed for key={}", key, e);
        }
    }

    private String loadTemplate(String configJson) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/static/viewer.html")) {
            if (in == null) throw new IOException("viewer.html not found");
            return new String(in.readAllBytes(), "UTF-8")
                .replace("__DOCVIEWER_CONFIG__", configJson);
        }
    }

    private void sendError(HttpExchange exchange, int code, String downloadKey, String msg) throws IOException {
        String downloadLink = downloadKey != null
            ? "<p><a href=\"/docviewer/file?key=" + downloadKey + "\">원본 파일 다운로드</a></p>" : "";
        byte[] bytes = ("<html><head><meta charset=\"UTF-8\"><title>오류</title>" +
            "<link rel=\"stylesheet\" href=\"/docviewer/static/viewer.css\"></head>" +
            "<body><div id=\"toolbar\"><span id=\"filename\">오류</span></div>" +
            "<div id=\"viewer-container\"><div id=\"error\" style=\"display:block\">" +
            "<p>" + escapeHtml(msg) + "</p>" + downloadLink + "</div></div></body></html>")
            .getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
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

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
