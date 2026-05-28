package com.docviewer.security;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IpWhitelistFilterTest {

    @Test
    void emptyListAllowsAll() {
        IpWhitelistFilter f = new IpWhitelistFilter(List.of());
        assertTrue(f.isAllowed("1.2.3.4"));
        assertTrue(f.isAllowed("127.0.0.1"));
    }

    @Test
    void exactIpMatch() {
        IpWhitelistFilter f = new IpWhitelistFilter(List.of("192.168.1.10"));
        assertTrue(f.isAllowed("192.168.1.10"));
        assertFalse(f.isAllowed("192.168.1.11"));
    }

    @Test
    void cidrMatch() {
        IpWhitelistFilter f = new IpWhitelistFilter(List.of("192.168.1.0/24"));
        assertTrue(f.isAllowed("192.168.1.1"));
        assertTrue(f.isAllowed("192.168.1.254"));
        assertFalse(f.isAllowed("192.168.2.1"));
    }

    @Test
    void cidrSlashZeroAllowsAll() {
        IpWhitelistFilter f = new IpWhitelistFilter(List.of("0.0.0.0/0"));
        assertTrue(f.isAllowed("1.2.3.4"));
    }

    @Test
    void ipv6LoopbackNormalizedToIpv4() {
        IpWhitelistFilter f = new IpWhitelistFilter(List.of("127.0.0.1"));
        assertTrue(f.isAllowed("::1"));
        assertTrue(f.isAllowed("0:0:0:0:0:0:0:1"));
    }

    @Test
    void multipleEntriesMatchesAny() {
        IpWhitelistFilter f = new IpWhitelistFilter(List.of("10.0.0.1", "192.168.0.0/16"));
        assertTrue(f.isAllowed("10.0.0.1"));
        assertTrue(f.isAllowed("192.168.5.100"));
        assertFalse(f.isAllowed("172.16.0.1"));
    }
}
