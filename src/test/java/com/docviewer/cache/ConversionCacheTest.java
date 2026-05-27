package com.docviewer.cache;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConversionCacheTest {
    private Path tempDir;
    private ConversionCache cache;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("docviewer-test-");
        cache = new ConversionCache(tempDir, 86400L);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.walk(tempDir)
            .sorted(java.util.Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
    }

    @Test
    void cachesMissOnFirstCall() throws Exception {
        File source = File.createTempFile("src", ".hwp", tempDir.toFile());
        AtomicInteger callCount = new AtomicInteger();

        String id1 = cache.getOrConvert(source, (src, dest) -> {
            callCount.incrementAndGet();
            Files.write(dest.toPath(), "pdf-content".getBytes());
        });

        assertEquals(1, callCount.get());
        assertTrue(Files.exists(cache.cachedPath(id1)));
    }

    @Test
    void cacheHitSkipsConverter() throws Exception {
        File source = File.createTempFile("src", ".hwp", tempDir.toFile());
        AtomicInteger callCount = new AtomicInteger();

        ConversionCache.Converter converter = (src, dest) -> {
            callCount.incrementAndGet();
            Files.write(dest.toPath(), "pdf-content".getBytes());
        };

        cache.getOrConvert(source, converter);
        cache.getOrConvert(source, converter);

        assertEquals(1, callCount.get());
    }

    @Test
    void invalidatesCacheWhenFileChanges() throws Exception {
        File source = File.createTempFile("src", ".hwp", tempDir.toFile());
        AtomicInteger callCount = new AtomicInteger();

        ConversionCache.Converter converter = (src, dest) -> {
            callCount.incrementAndGet();
            Files.write(dest.toPath(), "pdf-content".getBytes());
        };

        cache.getOrConvert(source, converter);
        source.setLastModified(System.currentTimeMillis() + 5000);
        cache.getOrConvert(source, converter);

        assertEquals(2, callCount.get());
    }

    @Test
    void cleanupDeletesOldFiles() throws Exception {
        ConversionCache shortTtl = new ConversionCache(tempDir, 0L);
        File source = File.createTempFile("src", ".hwp", tempDir.toFile());

        String id = shortTtl.getOrConvert(source, (src, dest) ->
            Files.write(dest.toPath(), "pdf".getBytes()));

        Thread.sleep(10);
        shortTtl.cleanup();

        assertFalse(Files.exists(shortTtl.cachedPath(id)));
    }
}
