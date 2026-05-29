package com.docviewer;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.converter.DispatchingConverter;
import com.docviewer.converter.HwpCliConverter;
import com.docviewer.converter.LibreOfficeConverter;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.handler.*;
import com.docviewer.registry.FileKeyRegistry;
import com.docviewer.security.*;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.*;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.Executors;

public class DocViewerServer {
    private static final Logger log = LoggerFactory.getLogger(DocViewerServer.class);

    public static void main(String[] args) throws Exception {
        DocViewerConfig config = DocViewerConfig.fromArgs(args);

        if (config.libreOfficePath == null) {
            log.error("--libreoffice=<path> is required. e.g. --libreoffice=/usr/lib/libreoffice");
            System.exit(1);
        }

        Path resultDir = Paths.get(config.resultDir);
        Files.createDirectories(resultDir);

        ConversionCache cache = new ConversionCache(resultDir, config.cacheTtlSeconds);
        cache.cleanup();

        FileKeyRegistry registry = new FileKeyRegistry(resultDir.resolve("docviewer.db"));
        FileTypeDetector detector = new FileTypeDetector(config.allowedExtensions);
        DocumentConverter libreOffice = new LibreOfficeConverter(config);
        DocumentConverter hwp = new HwpCliConverter(config);
        DocumentConverter converter = new DispatchingConverter(libreOffice, hwp);
        LicenseChecker license = new LicenseChecker(config.licenseAllowedIps, config.licenseAllowedDomains);
        IpWhitelistFilter apiFilter = new IpWhitelistFilter(config.apiAllowedIps);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down doc-viewer...");
            converter.shutdown();
            registry.close();
        }));

        HttpServer server = HttpServer.create(new InetSocketAddress(config.port), 100);
        server.createContext("/docviewer/view",
            new ViewHandler(config, converter, cache, detector, registry, license));
        server.createContext("/docviewer/file",
            new FileHandler(cache, config, detector, registry));
        server.createContext("/docviewer/static",
            new StaticHandler());
        server.createContext("/docviewer/api",
            new ApiHandler(config, converter, cache, detector, registry, apiFilter));
        server.createContext("/docviewer/health", exchange -> {
            String body = String.format(
                "{\"status\":\"ok\",\"libreoffice\":%b,\"port\":%d}",
                converter.isAlive(), config.port);
            byte[] bytes = body.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        if (config.allowedPaths.isEmpty()) {
            log.warn("SECURITY WARNING: --allowed-paths not set. ALL filesystem paths are accessible!");
        }
        log.info("doc-viewer v2 started on http://localhost:{}/docviewer", config.port);
        log.info("Result dir: {}", resultDir.toAbsolutePath());
    }
}
