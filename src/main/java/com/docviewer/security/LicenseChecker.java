package com.docviewer.security;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.*;
import java.util.List;

public class LicenseChecker {
    private static final Logger log = LoggerFactory.getLogger(LicenseChecker.class);

    private final IpWhitelistFilter ipFilter;
    private final List<String> allowedDomains;
    private final boolean unconfigured;

    public LicenseChecker(List<String> allowedIps, List<String> allowedDomains) {
        this.ipFilter = new IpWhitelistFilter(allowedIps);
        this.allowedDomains = List.copyOf(allowedDomains);
        this.unconfigured = allowedIps.isEmpty() && allowedDomains.isEmpty();
        if (unconfigured) {
            log.warn("SECURITY WARNING: license.allowed-ips and license.allowed-domains are not configured. All requests are allowed.");
        }
    }

    public boolean isAllowed(HttpExchange exchange) {
        if (unconfigured) return true;
        String remoteIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!ipFilter.isEmpty() && ipFilter.isAllowed(remoteIp)) return true;
        if (!allowedDomains.isEmpty()) {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (host != null) {
                String domain = host.contains(":") ? host.substring(0, host.lastIndexOf(':')) : host;
                for (String allowed : allowedDomains) {
                    if (domain.equals(allowed) || domain.endsWith("." + allowed)) return true;
                }
            }
        }
        return false;
    }
}
