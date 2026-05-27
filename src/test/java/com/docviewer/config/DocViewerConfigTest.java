package com.docviewer.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocViewerConfigTest {

    @Test
    void defaultValues() {
        DocViewerConfig cfg = DocViewerConfig.fromArgs(new String[0]);
        assertEquals(8090, cfg.port);
        assertNull(cfg.libreOfficePath);
        assertEquals(2002, cfg.loPort);
        assertEquals(1, cfg.loPoolSize);
        assertEquals(86400L, cfg.cacheTtlSeconds);
        assertEquals(30, cfg.convertTimeoutSeconds);
        assertTrue(cfg.allowedPaths.isEmpty());
    }

    @Test
    void parsesAllArgs() {
        DocViewerConfig cfg = DocViewerConfig.fromArgs(new String[]{
            "--port=9000",
            "--libreoffice=/usr/lib/libreoffice",
            "--lo-port=2003",
            "--lo-pool-size=2",
            "--cache-ttl=3600",
            "--convert-timeout=60",
            "--allowed-paths=/upload,/files"
        });
        assertEquals(9000, cfg.port);
        assertEquals("/usr/lib/libreoffice", cfg.libreOfficePath);
        assertEquals(2003, cfg.loPort);
        assertEquals(2, cfg.loPoolSize);
        assertEquals(3600L, cfg.cacheTtlSeconds);
        assertEquals(60, cfg.convertTimeoutSeconds);
        assertEquals(List.of("/upload", "/files"), cfg.allowedPaths);
    }

    @Test
    void ignoresUnknownArgs() {
        assertDoesNotThrow(() -> DocViewerConfig.fromArgs(new String[]{"--unknown=value"}));
    }
}
