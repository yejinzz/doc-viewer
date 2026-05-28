package com.docviewer.config;

import java.io.*;
import java.util.*;
import java.util.stream.*;

public class DocViewerConfig {
    public final int port;
    public final String libreOfficePath;
    public final int loPort;
    public final int loPoolSize;
    public final long cacheTtlSeconds;
    public final int convertTimeoutSeconds;
    public final List<String> allowedPaths;
    public final String resultDir;
    public final long maxFileSizeBytes;
    public final Set<String> allowedExtensions;
    public final List<String> licenseAllowedIps;
    public final List<String> licenseAllowedDomains;
    public final List<String> apiAllowedIps;

    private static final Set<String> DEFAULT_EXTENSIONS = Set.of(
        "pdf", "hwp", "hwpx", "doc", "docx", "xls", "xlsx",
        "ppt", "pptx", "txt", "jpg", "jpeg", "png", "gif", "bmp", "webp"
    );

    private DocViewerConfig(Builder b) {
        this.port = b.port;
        this.libreOfficePath = b.libreOfficePath;
        this.loPort = b.loPort;
        this.loPoolSize = b.loPoolSize;
        this.cacheTtlSeconds = b.cacheTtlSeconds;
        this.convertTimeoutSeconds = b.convertTimeoutSeconds;
        this.allowedPaths = Collections.unmodifiableList(new ArrayList<>(b.allowedPaths));
        this.resultDir = b.resultDir;
        this.maxFileSizeBytes = b.maxFileSizeBytes;
        this.allowedExtensions = Collections.unmodifiableSet(b.allowedExtensions);
        this.licenseAllowedIps = Collections.unmodifiableList(new ArrayList<>(b.licenseAllowedIps));
        this.licenseAllowedDomains = Collections.unmodifiableList(new ArrayList<>(b.licenseAllowedDomains));
        this.apiAllowedIps = Collections.unmodifiableList(new ArrayList<>(b.apiAllowedIps));
    }

    public static DocViewerConfig fromArgs(String[] args) {
        Builder b = new Builder();
        String configPath = null;
        for (String arg : args) {
            if (arg.startsWith("--config=")) configPath = arg.substring("--config=".length());
        }
        if (configPath == null) {
            File def = new File("doc-viewer.properties");
            if (def.exists()) configPath = def.getPath();
        }
        if (configPath != null) loadProperties(b, configPath);
        for (String arg : args) {
            if (!arg.startsWith("--") || arg.startsWith("--config=")) continue;
            String[] parts = arg.substring(2).split("=", 2);
            if (parts.length < 2) continue;
            applyEntry(b, parts[0], parts[1]);
        }
        return new DocViewerConfig(b);
    }

    private static void loadProperties(Builder b, String path) {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config file: " + path, e);
        }
        props.forEach((k, v) -> applyEntry(b, propKeyToArgKey((String) k), (String) v));
    }

    private static String propKeyToArgKey(String propKey) {
        switch (propKey) {
            case "port":                   return "port";
            case "libreoffice.path":       return "libreoffice";
            case "libreoffice.port":       return "lo-port";
            case "libreoffice.pool-size":  return "lo-pool-size";
            case "cache.ttl":              return "cache-ttl";
            case "convert.timeout":        return "convert-timeout";
            case "allowed.paths":          return "allowed-paths";
            case "result.dir":             return "result-dir";
            case "max.file.size":          return "max-file-size";
            case "allowed.extensions":     return "allowed-extensions";
            case "license.allowed-ips":    return "license-allowed-ips";
            case "license.allowed-domains":return "license-allowed-domains";
            case "api.allowed-ips":        return "api-allowed-ips";
            default:                       return propKey;
        }
    }

    private static void applyEntry(Builder b, String key, String value) {
        switch (key) {
            case "port":                  b.port = Integer.parseInt(value.trim()); break;
            case "libreoffice":           b.libreOfficePath = value.trim(); break;
            case "lo-port":               b.loPort = Integer.parseInt(value.trim()); break;
            case "lo-pool-size":          b.loPoolSize = Integer.parseInt(value.trim()); break;
            case "cache-ttl":             b.cacheTtlSeconds = Long.parseLong(value.trim()); break;
            case "convert-timeout":       b.convertTimeoutSeconds = Integer.parseInt(value.trim()); break;
            case "allowed-paths":         b.allowedPaths = splitList(value); break;
            case "result-dir":            b.resultDir = value.trim(); break;
            case "max-file-size":         b.maxFileSizeBytes = Long.parseLong(value.trim()); break;
            case "allowed-extensions":    b.allowedExtensions = new HashSet<>(splitList(value)); break;
            case "license-allowed-ips":   b.licenseAllowedIps = splitList(value); break;
            case "license-allowed-domains": b.licenseAllowedDomains = splitList(value); break;
            case "api-allowed-ips":       b.apiAllowedIps = splitList(value); break;
        }
    }

    private static List<String> splitList(String value) {
        return Arrays.stream(value.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    private static class Builder {
        int port = 8090;
        String libreOfficePath = null;
        int loPort = 2002;
        int loPoolSize = 1;
        long cacheTtlSeconds = 86400L;
        int convertTimeoutSeconds = 30;
        List<String> allowedPaths = new ArrayList<>();
        String resultDir = System.getProperty("java.io.tmpdir") + File.separator + "docviewer-output";
        long maxFileSizeBytes = 100L * 1024 * 1024;
        Set<String> allowedExtensions = new HashSet<>(DEFAULT_EXTENSIONS);
        List<String> licenseAllowedIps = new ArrayList<>();
        List<String> licenseAllowedDomains = new ArrayList<>();
        List<String> apiAllowedIps = new ArrayList<>(List.of("127.0.0.1"));
    }
}
