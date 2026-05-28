package com.docviewer.security;

import java.util.List;

public class IpWhitelistFilter {
    private final List<String> entries;

    public IpWhitelistFilter(List<String> entries) {
        this.entries = List.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean isAllowed(String remoteAddress) {
        if (entries.isEmpty()) return true;
        if (remoteAddress == null) return false;
        String ip = normalize(remoteAddress);
        for (String entry : entries) {
            if (entry.contains("/")) {
                if (matchesCidr(ip, entry)) return true;
            } else {
                if (entry.equals(ip)) return true;
            }
        }
        return false;
    }

    private String normalize(String ip) {
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return "127.0.0.1";
        return ip;
    }

    private boolean matchesCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            int prefix = Integer.parseInt(parts[1].trim());
            int ipInt = toInt(ip);
            int baseInt = toInt(parts[0].trim());
            int mask = prefix == 0 ? 0 : (0xFFFFFFFF << (32 - prefix));
            return (ipInt & mask) == (baseInt & mask);
        } catch (Exception e) {
            return false;
        }
    }

    private int toInt(String ip) {
        String[] parts = ip.split("\\.");
        int r = 0;
        for (String p : parts) r = (r << 8) | Integer.parseInt(p.trim());
        return r;
    }
}
