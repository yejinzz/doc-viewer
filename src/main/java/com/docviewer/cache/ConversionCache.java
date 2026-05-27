package com.docviewer.cache;

import org.slf4j.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.concurrent.*;

public class ConversionCache {
    private static final Logger log = LoggerFactory.getLogger(ConversionCache.class);

    private final Path cacheDir;
    private final long ttlMillis;
    private final ConcurrentMap<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();

    public ConversionCache(long ttlSeconds) throws IOException {
        this(Paths.get(System.getProperty("java.io.tmpdir"), "docviewer-cache"), ttlSeconds);
    }

    public ConversionCache(Path cacheDir, long ttlSeconds) throws IOException {
        this.cacheDir = cacheDir;
        this.ttlMillis = ttlSeconds * 1000;
        Files.createDirectories(cacheDir);
    }

    public interface Converter {
        void convert(File source, File dest) throws Exception;
    }

    public String getOrConvert(File source, Converter converter) throws Exception {
        String cacheId = cacheId(source);
        if (isCached(cacheId)) return cacheId;

        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture<Void> existing = inFlight.putIfAbsent(cacheId, future);
        if (existing != null) {
            existing.get();
            return cacheId;
        }
        try {
            converter.convert(source, cachedPath(cacheId).toFile());
            future.complete(null);
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(cacheId);
        }
        return cacheId;
    }

    public Path cachedPath(String cacheId) {
        return cacheDir.resolve(cacheId + ".pdf");
    }

    public boolean isCached(String cacheId) {
        return Files.exists(cachedPath(cacheId));
    }

    public void cleanup() {
        long cutoff = System.currentTimeMillis() - ttlMillis;
        try {
            Files.list(cacheDir)
                .filter(p -> p.toString().endsWith(".pdf"))
                .filter(p -> {
                    try { return Files.getLastModifiedTime(p).toMillis() < cutoff; }
                    catch (IOException e) { return false; }
                })
                .forEach(p -> {
                    try { Files.delete(p); log.info("Evicted cache {}", p.getFileName()); }
                    catch (IOException e) { log.warn("Failed to evict {}", p, e); }
                });
        } catch (IOException e) {
            log.warn("Cache cleanup failed", e);
        }
    }

    private String cacheId(File source) {
        return sha256(source.getAbsolutePath() + ":" + source.lastModified());
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
