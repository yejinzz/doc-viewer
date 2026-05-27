package com.docviewer.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class DocViewerConfig {
    public final int port;
    public final String libreOfficePath;
    public final int loPort;
    public final int loPoolSize;
    public final long cacheTtlSeconds;
    public final int convertTimeoutSeconds;
    public final List<String> allowedPaths;

    private DocViewerConfig(Builder b) {
        this.port = b.port;
        this.libreOfficePath = b.libreOfficePath;
        this.loPort = b.loPort;
        this.loPoolSize = b.loPoolSize;
        this.cacheTtlSeconds = b.cacheTtlSeconds;
        this.convertTimeoutSeconds = b.convertTimeoutSeconds;
        this.allowedPaths = b.allowedPaths;
    }

    public static DocViewerConfig fromArgs(String[] args) {
        Builder b = new Builder();
        for (String arg : args) {
            if (!arg.startsWith("--")) continue;
            String[] parts = arg.substring(2).split("=", 2);
            if (parts.length < 2) continue;
            switch (parts[0]) {
                case "port":             b.port = Integer.parseInt(parts[1]); break;
                case "libreoffice":      b.libreOfficePath = parts[1]; break;
                case "lo-port":          b.loPort = Integer.parseInt(parts[1]); break;
                case "lo-pool-size":     b.loPoolSize = Integer.parseInt(parts[1]); break;
                case "cache-ttl":        b.cacheTtlSeconds = Long.parseLong(parts[1]); break;
                case "convert-timeout":  b.convertTimeoutSeconds = Integer.parseInt(parts[1]); break;
                case "allowed-paths":
                    b.allowedPaths = Arrays.stream(parts[1].split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
                    break;
            }
        }
        return b.build();
    }

    private static class Builder {
        int port = 8090;
        String libreOfficePath = null;
        int loPort = 2002;
        int loPoolSize = 1;
        long cacheTtlSeconds = 86400L;
        int convertTimeoutSeconds = 30;
        List<String> allowedPaths = List.of();

        DocViewerConfig build() { return new DocViewerConfig(this); }
    }
}
