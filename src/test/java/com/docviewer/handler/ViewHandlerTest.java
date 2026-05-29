package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.registry.FileKeyRegistry;
import com.docviewer.security.LicenseChecker;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ViewHandlerTest {
    private HttpServer server;
    private int port;
    private Path tempDir;
    private ConversionCache cache;
    private FileKeyRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("vh-test-");
        cache = new ConversionCache(tempDir, 86400L);
        registry = FileKeyRegistry.forTesting();

        DocViewerConfig config = DocViewerConfig.fromArgs(new String[]{
            "--allowed-paths=" + tempDir.toAbsolutePath()
        });
        FileTypeDetector detector = new FileTypeDetector(config.allowedExtensions);
        LicenseChecker license = new LicenseChecker(List.of(), List.of());

        DocumentConverter converter = new DocumentConverter() {
            public void convert(File src, File dest) throws Exception {
                Files.write(dest.toPath(), "%PDF-1.4 dummy".getBytes());
            }
            public boolean isAlive() { return true; }
            public void shutdown() {}
        };

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docviewer/view",
            new ViewHandler(config, converter, cache, detector, registry, license));
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
    void returns400WhenKeyMissing() throws Exception {
        assertEquals(400, get("/docviewer/view").statusCode());
    }

    @Test
    void returns400ForInvalidKeyFormat() throws Exception {
        assertEquals(400, get("/docviewer/view?key=../evil").statusCode());
    }

    @Test
    void returns404ForUnregisteredKey() throws Exception {
        assertEquals(404, get("/docviewer/view?key=UNKNOWN_KEY_001").statusCode());
    }

    @Test
    void returns404WhenStatusNotConverted() throws Exception {
        registry.register("FILE_NOTYET_0", "/data/f.hwp", "f.hwp");
        assertEquals(404, get("/docviewer/view?key=FILE_NOTYET_0").statusCode());
    }

    @Test
    void servesViewerHtmlForConvertedPdfKey() throws Exception {
        File pdf = File.createTempFile("test", ".pdf", tempDir.toFile());
        Files.write(pdf.toPath(), "%PDF-1.4".getBytes());
        String key = "FILE_PDF_0";
        registry.register(key, pdf.getAbsolutePath(), "test.pdf");
        registry.markConverted(key, "hash1", pdf.length(), pdf.lastModified());

        HttpResponse<String> resp = get("/docviewer/view?key=" + key);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("DOCVIEWER_CONFIG"));
        assertTrue(resp.body().contains("\"renderType\":\"pdf\""));
    }

    @Test
    void hwpKeyReturnsHwpRenderTypeWithFileKey() throws Exception {
        File hwp = File.createTempFile("doc", ".hwp", tempDir.toFile());
        Files.write(hwp.toPath(), "fake hwp".getBytes());
        String key = "FILE_HWP_0";
        registry.register(key, hwp.getAbsolutePath(), "doc.hwp");
        registry.markConverted(key, "hash1", hwp.length(), hwp.lastModified());

        HttpResponse<String> resp = get("/docviewer/view?key=" + key);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"renderType\":\"hwp\""));
        assertTrue(resp.body().contains("/docviewer/file?key=" + key));
        assertFalse(resp.body().contains("/docviewer/file?id="));
    }

    @Test
    void hwpxKeyReturnsHwpRenderType() throws Exception {
        File hwpx = File.createTempFile("doc", ".hwpx", tempDir.toFile());
        Files.write(hwpx.toPath(), "fake hwpx".getBytes());
        String key = "FILE_HWPX_0";
        registry.register(key, hwpx.getAbsolutePath(), "doc.hwpx");
        registry.markConverted(key, "hash1", hwpx.length(), hwpx.lastModified());

        HttpResponse<String> resp = get("/docviewer/view?key=" + key);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"renderType\":\"hwp\""));
        assertTrue(resp.body().contains("/docviewer/file?key=" + key));
    }

    @Test
    void servesImageViewerForImageKey() throws Exception {
        File img = File.createTempFile("photo", ".png", tempDir.toFile());
        Files.write(img.toPath(), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        String key = "FILE_IMG_0";
        registry.register(key, img.getAbsolutePath(), "photo.png");
        registry.markConverted(key, "hash1", img.length(), img.lastModified());

        HttpResponse<String> resp = get("/docviewer/view?key=" + key);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"renderType\":\"image\""));
        assertTrue(resp.body().contains("/docviewer/file?key=" + key));
    }

    @Test
    void returns404WhenFileIsMissing() throws Exception {
        String key = "FILE_MISSING_0";
        registry.register(key, "/nonexistent/file.pdf", "file.pdf");
        registry.markConverted(key, "hash1", 100L, 999L);
        assertEquals(404, get("/docviewer/view?key=" + key).statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
