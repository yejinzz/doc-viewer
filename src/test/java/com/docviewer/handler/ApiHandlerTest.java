package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.registry.FileKeyRegistry;
import com.docviewer.security.IpWhitelistFilter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ApiHandlerTest {
    private HttpServer server;
    private int port;
    private Path tempDir;
    private FileKeyRegistry registry;
    private ConversionCache cache;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("api-test-");
        registry = FileKeyRegistry.forTesting();
        cache = new ConversionCache(tempDir, 86400L);

        DocViewerConfig config = DocViewerConfig.fromArgs(new String[]{
            "--result-dir=" + tempDir.toAbsolutePath(),
            "--allowed-paths=" + tempDir.toAbsolutePath(),
            "--api-allowed-ips=127.0.0.1"
        });
        FileTypeDetector detector = new FileTypeDetector(config.allowedExtensions);

        DocumentConverter converter = new DocumentConverter() {
            public void convert(File src, File dest) throws Exception {
                Files.write(dest.toPath(), "%PDF-1.4 dummy".getBytes());
            }
            public boolean isAlive() { return true; }
            public void shutdown() {}
        };

        IpWhitelistFilter apiFilter = new IpWhitelistFilter(config.apiAllowedIps);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docviewer/api",
            new ApiHandler(config, converter, cache, detector, registry, apiFilter));
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        registry.close();
        Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder())
            .map(Path::toFile).forEach(File::delete);
    }

    @Test
    void convertRegistersAndConvertsFile() throws Exception {
        File hwp = File.createTempFile("test", ".hwp", tempDir.toFile());
        Files.write(hwp.toPath(), "fake hwp".getBytes());

        String body = String.format(
            "{\"key\":\"FILE_001_0\",\"path\":\"%s\",\"originalName\":\"문서.hwp\",\"fileHash\":\"\"}",
            hwp.getAbsolutePath().replace("\\", "\\\\"));

        HttpResponse<String> resp = post("/docviewer/api/convert", body);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"status\":\"ok\""));
        assertEquals("converted", registry.getStatus("FILE_001_0"));
    }

    @Test
    void convertRejects415ForUnsupportedExtension() throws Exception {
        File exe = File.createTempFile("test", ".exe", tempDir.toFile());
        Files.write(exe.toPath(), new byte[]{0});

        String body = String.format(
            "{\"key\":\"FILE_002_0\",\"path\":\"%s\",\"originalName\":\"bad.exe\",\"fileHash\":\"\"}",
            exe.getAbsolutePath().replace("\\", "\\\\"));

        HttpResponse<String> resp = post("/docviewer/api/convert", body);
        assertEquals(415, resp.statusCode());
    }

    @Test
    void convertRejects413ForOversizedFile() throws Exception {
        DocViewerConfig smallConfig = DocViewerConfig.fromArgs(new String[]{
            "--result-dir=" + tempDir.toAbsolutePath(),
            "--allowed-paths=" + tempDir.toAbsolutePath(),
            "--max-file-size=10",
            "--api-allowed-ips=127.0.0.1"
        });
        File big = File.createTempFile("big", ".pdf", tempDir.toFile());
        Files.write(big.toPath(), new byte[11]);

        FileTypeDetector det = new FileTypeDetector(smallConfig.allowedExtensions);
        DocumentConverter conv = new DocumentConverter() {
            public void convert(File src, File dest) throws Exception {}
            public boolean isAlive() { return true; }
            public void shutdown() {}
        };
        IpWhitelistFilter f = new IpWhitelistFilter(smallConfig.apiAllowedIps);
        ApiHandler handler = new ApiHandler(smallConfig, conv, cache, det, registry, f);

        HttpServer s2 = HttpServer.create(new InetSocketAddress(0), 0);
        s2.createContext("/docviewer/api", handler);
        s2.setExecutor(null);
        s2.start();
        int p2 = s2.getAddress().getPort();
        try {
            String body = String.format(
                "{\"key\":\"FILE_003_0\",\"path\":\"%s\",\"originalName\":\"big.pdf\",\"fileHash\":\"\"}",
                big.getAbsolutePath().replace("\\", "\\\\"));
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + p2 + "/docviewer/api/convert"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(413, resp.statusCode());
        } finally {
            s2.stop(0);
        }
    }

    @Test
    void convertRejects400ForInvalidKeyFormat() throws Exception {
        String body = "{\"key\":\"../evil\",\"path\":\"/tmp/f.hwp\",\"originalName\":\"f.hwp\",\"fileHash\":\"\"}";
        HttpResponse<String> resp = post("/docviewer/api/convert", body);
        assertEquals(400, resp.statusCode());
    }

    @Test
    void statusReturnsConvertStatus() throws Exception {
        registry.register("FILE_STATUS_0", "/data/f.pdf", "f.pdf");
        HttpResponse<String> resp = get("/docviewer/api/status/FILE_STATUS_0");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("registered"));
    }

    @Test
    void statusReturns404ForUnknownKey() throws Exception {
        HttpResponse<String> resp = get("/docviewer/api/status/UNKNOWN_KEY");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void deleteKeyRemovesEntry() throws Exception {
        registry.register("FILE_DEL_0", "/data/f.pdf", "f.pdf");
        HttpResponse<String> resp = delete("/docviewer/api/key/FILE_DEL_0");
        assertEquals(200, resp.statusCode());
        assertNull(registry.getStatus("FILE_DEL_0"));
    }

    @Test
    void hwpConvertSkipsConverter() throws Exception {
        // converter가 호출되면 무조건 실패하는 서버를 별도 기동
        DocumentConverter failConverter = new DocumentConverter() {
            public void convert(File src, File dest) throws Exception {
                throw new RuntimeException("converter must not be called for HWP");
            }
            public boolean isAlive() { return true; }
            public void shutdown() {}
        };
        DocViewerConfig cfg = DocViewerConfig.fromArgs(new String[]{
            "--result-dir=" + tempDir.toAbsolutePath(),
            "--allowed-paths=" + tempDir.toAbsolutePath(),
            "--api-allowed-ips=127.0.0.1"
        });
        FileTypeDetector det = new FileTypeDetector(cfg.allowedExtensions);
        IpWhitelistFilter f = new IpWhitelistFilter(cfg.apiAllowedIps);
        ConversionCache c = new ConversionCache(tempDir, 86400L);

        HttpServer s = HttpServer.create(new InetSocketAddress(0), 0);
        s.createContext("/docviewer/api",
            new ApiHandler(cfg, failConverter, c, det, registry, f));
        s.setExecutor(null);
        s.start();
        int p = s.getAddress().getPort();

        try {
            File hwp = File.createTempFile("skip", ".hwp", tempDir.toFile());
            Files.write(hwp.toPath(), "fake hwp".getBytes());
            String body = String.format(
                "{\"key\":\"FILE_HWP_SKIP_0\",\"path\":\"%s\",\"originalName\":\"문서.hwp\",\"fileHash\":\"\"}",
                hwp.getAbsolutePath().replace("\\", "\\\\"));

            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + p + "/docviewer/api/convert"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("\"status\":\"ok\""));
            assertEquals("converted", registry.getStatus("FILE_HWP_SKIP_0"));
        } finally {
            s.stop(0);
        }
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method("DELETE", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
