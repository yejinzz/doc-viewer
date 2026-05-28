package com.docviewer.registry;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class FileKeyRegistryTest {
    private FileKeyRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = FileKeyRegistry.forTesting();
    }

    @AfterEach
    void tearDown() throws Exception {
        registry.close();
    }

    @Test
    void registerAndFindByKey() throws Exception {
        registry.register("FILE_001_0", "/data/file.hwp", "문서.hwp");
        FileKeyRegistry.FileKeyEntry e = registry.findByKey("FILE_001_0");
        assertNotNull(e);
        assertEquals("FILE_001_0", e.key);
        assertEquals("/data/file.hwp", e.filePath);
        assertEquals("문서.hwp", e.originalName);
        assertEquals("registered", e.convertStatus);
    }

    @Test
    void findByKeyReturnsNullForUnknownKey() throws Exception {
        assertNull(registry.findByKey("UNKNOWN_KEY"));
    }

    @Test
    void markConverted() throws Exception {
        registry.register("FILE_002_0", "/data/doc.xlsx", "report.xlsx");
        registry.markConverted("FILE_002_0", "abc123hash", 20480L, 1000L);
        FileKeyRegistry.FileKeyEntry e = registry.findByKey("FILE_002_0");
        assertEquals("converted", e.convertStatus);
        assertEquals("abc123hash", e.fileHash);
        assertEquals(20480L, e.fileSize);
        assertEquals(1000L, e.lastModified);
    }

    @Test
    void markError() throws Exception {
        registry.register("FILE_003_0", "/data/bad.hwp", "bad.hwp");
        registry.markError("FILE_003_0", "LibreOffice crashed");
        FileKeyRegistry.FileKeyEntry e = registry.findByKey("FILE_003_0");
        assertEquals("error", e.convertStatus);
    }

    @Test
    void getStatus() throws Exception {
        assertNull(registry.getStatus("NONE"));
        registry.register("FILE_004_0", "/data/f.pdf", "f.pdf");
        assertEquals("registered", registry.getStatus("FILE_004_0"));
    }

    @Test
    void deleteKey() throws Exception {
        registry.register("FILE_005_0", "/data/d.pdf", "d.pdf");
        registry.delete("FILE_005_0");
        assertNull(registry.findByKey("FILE_005_0"));
    }

    @Test
    void updateMetadata() throws Exception {
        registry.register("FILE_006_0", "/data/m.pdf", "m.pdf");
        registry.markConverted("FILE_006_0", "hash1", 100L, 999L);
        registry.updateMetadata("FILE_006_0", "hash2", 200L, 1999L);
        FileKeyRegistry.FileKeyEntry e = registry.findByKey("FILE_006_0");
        assertEquals("hash2", e.fileHash);
        assertEquals(200L, e.fileSize);
        assertEquals(1999L, e.lastModified);
        assertEquals("converted", e.convertStatus);
    }

    @Test
    void registerIsIdempotent() throws Exception {
        registry.register("FILE_007_0", "/data/a.hwp", "a.hwp");
        registry.markConverted("FILE_007_0", "h1", 100L, 1L);
        registry.register("FILE_007_0", "/data/a.hwp", "a.hwp");
        assertEquals("registered", registry.getStatus("FILE_007_0"));
    }
}
