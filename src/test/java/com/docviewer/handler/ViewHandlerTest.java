package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.detector.FileTypeDetector;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ViewHandlerTest {
    private HttpServer server;
    private int port;
    private Path tempDir;
    private ConversionCache cache;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("vh-test-");
        cache = new ConversionCache(tempDir, 86400L);

        DocViewerConfig config = DocViewerConfig.fromArgs(new String[0]);
        FileTypeDetector detector = new FileTypeDetector();

        DocumentConverter converter = new DocumentConverter() {
            public void convert(File src, File dest) throws Exception {
                Files.write(dest.toPath(), "%PDF-1.4 dummy".getBytes());
            }
            public boolean isAlive() { return true; }
            public void shutdown() {}
        };

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docviewer/view",
            new ViewHandler(config, converter, cache, detector));
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
    void returns400WhenPathMissing() throws Exception {
        HttpResponse<String> resp = get("/docviewer/view");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void returns404ForNonExistentFile() throws Exception {
        HttpResponse<String> resp = get("/docviewer/view?path=/nonexistent/file.pdf");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void servesPdfViewerHtmlForPdfFile() throws Exception {
        File pdf = File.createTempFile("test", ".pdf", tempDir.toFile());
        Files.write(pdf.toPath(), "%PDF-1.4".getBytes());

        HttpResponse<String> resp = get("/docviewer/view?path="
            + URLEncoder.encode(pdf.getAbsolutePath(), "UTF-8"));

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("DOCVIEWER_CONFIG"));
        assertTrue(resp.body().contains("\"renderType\":\"pdf\""));
        assertTrue(resp.body().contains(pdf.getName()));
    }

    @Test
    void servesImageViewerHtmlForImageFile() throws Exception {
        File img = File.createTempFile("photo", ".png", tempDir.toFile());
        Files.write(img.toPath(), new byte[]{(byte)0x89, 'P', 'N', 'G'});

        HttpResponse<String> resp = get("/docviewer/view?path="
            + URLEncoder.encode(img.getAbsolutePath(), "UTF-8"));

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"renderType\":\"image\""));
    }

    @Test
    void triggersConversionForHwpFile() throws Exception {
        File hwp = File.createTempFile("doc", ".hwp", tempDir.toFile());
        Files.write(hwp.toPath(), "fake hwp content".getBytes());

        HttpResponse<String> resp = get("/docviewer/view?path="
            + URLEncoder.encode(hwp.getAbsolutePath(), "UTF-8"));

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"renderType\":\"pdf\""));
        assertTrue(resp.body().contains("/docviewer/file?id="));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        return client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }
}
