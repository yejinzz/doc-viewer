package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.registry.FileKeyRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FileHandlerTest {
    private HttpServer server;
    private int port;
    private Path tempDir;
    private ConversionCache cache;
    private FileKeyRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("fh-test-");
        cache = new ConversionCache(tempDir, 86400L);
        registry = FileKeyRegistry.forTesting();

        DocViewerConfig config = DocViewerConfig.fromArgs(new String[]{
            "--allowed-paths=" + tempDir.toAbsolutePath()
        });
        FileTypeDetector detector = new FileTypeDetector();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docviewer/file",
            new FileHandler(cache, config, detector, registry));
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
    void servesCachedPdfById() throws Exception {
        String cacheId = "cafe0000cafe0000";
        Files.createDirectories(cache.cachedPath(cacheId).getParent());
        Files.write(cache.cachedPath(cacheId), "%PDF-1.4 content".getBytes());

        HttpResponse<byte[]> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/file?id=" + cacheId)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode());
        assertEquals("application/pdf", resp.headers().firstValue("content-type").orElse(""));
    }

    @Test
    void returns404ForMissingCacheId() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/file?id=deadbeef00000001")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }

    @Test
    void servesOriginalFileByKey() throws Exception {
        File txtFile = File.createTempFile("test", ".txt", tempDir.toFile());
        Files.write(txtFile.toPath(), "hello world".getBytes());
        registry.register("FILE_TXT_0", txtFile.getAbsolutePath(), "test.txt");
        registry.markConverted("FILE_TXT_0", "h1", txtFile.length(), txtFile.lastModified());

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/file?key=FILE_TXT_0")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("hello world", resp.body());
    }

    @Test
    void returns404ForUnknownKey() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/file?key=UNKNOWN_99")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }

    @Test
    void returns400WithNoParams() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/file")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode());
    }

    @Test
    void returns400ForInvalidCacheId() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/file?id=../evil")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode());
    }
}
