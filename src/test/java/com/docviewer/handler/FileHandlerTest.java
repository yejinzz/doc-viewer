package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.detector.FileTypeDetector;
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

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("fh-test-");
        cache = new ConversionCache(tempDir, 86400L);

        DocViewerConfig config = DocViewerConfig.fromArgs(new String[0]);
        FileTypeDetector detector = new FileTypeDetector();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docviewer/file", new FileHandler(cache, config, detector));
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder())
            .map(Path::toFile).forEach(File::delete);
    }

    @Test
    void servesCachedPdfById() throws Exception {
        String cacheId = "testid00testid00";
        Files.write(cache.cachedPath(cacheId), "%PDF-1.4 content".getBytes());

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<byte[]> resp = client.send(
            HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/docviewer/file?id=" + cacheId)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
        assertEquals(200, resp.statusCode());
        assertEquals("application/pdf", resp.headers().firstValue("content-type").orElse(""));
    }

    @Test
    void returns404ForMissingCacheId() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/docviewer/file?id=nonexistent")).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(404, resp.statusCode());
    }

    @Test
    void servesOriginalFileByPath() throws Exception {
        File txtFile = File.createTempFile("test", ".txt", tempDir.toFile());
        Files.write(txtFile.toPath(), "hello world".getBytes());

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/docviewer/file?path="
                + URLEncoder.encode(txtFile.getAbsolutePath(), "UTF-8"))).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
        assertEquals("hello world", resp.body());
    }

    @Test
    void returns400WithNoParams() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/docviewer/file")).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(400, resp.statusCode());
    }
}
