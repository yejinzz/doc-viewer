package com.docviewer.config;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
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
        assertEquals(104857600L, cfg.maxFileSizeBytes);
        assertFalse(cfg.allowedExtensions.isEmpty());
        assertTrue(cfg.allowedExtensions.contains("pdf"));
        assertTrue(cfg.allowedExtensions.contains("hwp"));
        assertTrue(cfg.licenseAllowedIps.isEmpty());
        assertTrue(cfg.licenseAllowedDomains.isEmpty());
        assertEquals(List.of("127.0.0.1"), cfg.apiAllowedIps);
        assertNotNull(cfg.resultDir);
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
            "--allowed-paths=/upload,/files",
            "--result-dir=/var/docviewer",
            "--max-file-size=52428800",
            "--allowed-extensions=pdf,hwp,docx",
            "--license-allowed-ips=192.168.1.0/24",
            "--license-allowed-domains=example.com",
            "--api-allowed-ips=127.0.0.1,10.0.0.5"
        });
        assertEquals(9000, cfg.port);
        assertEquals("/usr/lib/libreoffice", cfg.libreOfficePath);
        assertEquals(2003, cfg.loPort);
        assertEquals(2, cfg.loPoolSize);
        assertEquals(3600L, cfg.cacheTtlSeconds);
        assertEquals(60, cfg.convertTimeoutSeconds);
        assertEquals(List.of("/upload", "/files"), cfg.allowedPaths);
        assertEquals("/var/docviewer", cfg.resultDir);
        assertEquals(52428800L, cfg.maxFileSizeBytes);
        assertTrue(cfg.allowedExtensions.containsAll(Set.of("pdf", "hwp", "docx")));
        assertEquals(3, cfg.allowedExtensions.size());
        assertEquals(List.of("192.168.1.0/24"), cfg.licenseAllowedIps);
        assertEquals(List.of("example.com"), cfg.licenseAllowedDomains);
        assertEquals(List.of("127.0.0.1", "10.0.0.5"), cfg.apiAllowedIps);
    }

    @Test
    void loadsFromPropertiesFile() throws Exception {
        Path propsFile = Files.createTempFile("docviewer-test-", ".properties");
        Files.writeString(propsFile,
            "port=9999\n" +
            "libreoffice.path=/opt/libreoffice\n" +
            "result.dir=/tmp/dv\n" +
            "max.file.size=20971520\n" +
            "allowed.extensions=pdf,hwp\n" +
            "license.allowed-ips=10.0.0.0/8\n" +
            "api.allowed-ips=127.0.0.1\n"
        );
        try {
            DocViewerConfig cfg = DocViewerConfig.fromArgs(
                new String[]{"--config=" + propsFile.toAbsolutePath()});
            assertEquals(9999, cfg.port);
            assertEquals("/opt/libreoffice", cfg.libreOfficePath);
            assertEquals("/tmp/dv", cfg.resultDir);
            assertEquals(20971520L, cfg.maxFileSizeBytes);
            assertEquals(Set.of("pdf", "hwp"), cfg.allowedExtensions);
            assertEquals(List.of("10.0.0.0/8"), cfg.licenseAllowedIps);
        } finally {
            Files.deleteIfExists(propsFile);
        }
    }

    @Test
    void cliArgOverridesPropertiesFile() throws Exception {
        Path propsFile = Files.createTempFile("docviewer-test-", ".properties");
        Files.writeString(propsFile, "port=9999\n");
        try {
            DocViewerConfig cfg = DocViewerConfig.fromArgs(
                new String[]{"--config=" + propsFile.toAbsolutePath(), "--port=8888"});
            assertEquals(8888, cfg.port);
        } finally {
            Files.deleteIfExists(propsFile);
        }
    }

    @Test
    void ignoresUnknownArgs() {
        assertDoesNotThrow(() -> DocViewerConfig.fromArgs(new String[]{"--unknown=value"}));
    }
}
