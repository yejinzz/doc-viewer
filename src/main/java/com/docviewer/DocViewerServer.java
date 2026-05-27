package com.docviewer;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.LibreOfficeConverter;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.handler.*;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.*;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class DocViewerServer {
    private static final Logger log = LoggerFactory.getLogger(DocViewerServer.class);

    public static void main(String[] args) throws Exception {
        DocViewerConfig config = DocViewerConfig.fromArgs(args);

        if (config.libreOfficePath == null) {
            log.error("--libreoffice=<path> is required. e.g. --libreoffice=/usr/lib/libreoffice");
            System.exit(1);
        }

        ConversionCache cache = new ConversionCache(config.cacheTtlSeconds);
        cache.cleanup();

        FileTypeDetector detector = new FileTypeDetector();
        LibreOfficeConverter converter = new LibreOfficeConverter(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down doc-viewer...");
            converter.shutdown();
        }));

        HttpServer server = HttpServer.create(new InetSocketAddress(config.port), 100);
        server.createContext("/docviewer/view",   new ViewHandler(config, converter, cache, detector));
        server.createContext("/docviewer/file",   new FileHandler(cache, config, detector));
        server.createContext("/docviewer/static", new StaticHandler());
        server.createContext("/docviewer/health", exchange -> {
            String body = String.format(
                "{\"status\":\"ok\",\"libreoffice\":%b,\"port\":%d}",
                converter.isAlive(), config.port
            );
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        log.info("doc-viewer started on http://localhost:{}/docviewer", config.port);
    }
}
