package com.docviewer.security;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LicenseCheckerTest {

    private static class TestHttpExchange extends HttpExchange {
        private final String remoteIp;
        private final String hostHeader;
        private final Headers headers;

        TestHttpExchange(String remoteIp, String hostHeader) {
            this.remoteIp = remoteIp;
            this.hostHeader = hostHeader;
            this.headers = new Headers();
            if (hostHeader != null) headers.add("Host", hostHeader);
        }

        @Override
        public Headers getRequestHeaders() {
            return headers;
        }

        @Override
        public Headers getResponseHeaders() {
            return new Headers();
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress(remoteIp, 12345);
        }

        @Override
        public int getResponseCode() {
            return 0;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public String getProtocol() {
            return null;
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            return null;
        }

        @Override
        public OutputStream getResponseBody() {
            return null;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
        }

        @Override
        public String getRequestMethod() {
            return null;
        }

        @Override
        public java.net.URI getRequestURI() {
            return null;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
        }
    }

    private HttpExchange mockExchange(String remoteIp, String hostHeader) {
        return new TestHttpExchange(remoteIp, hostHeader);
    }

    @Test
    void unconfiguredAllowsAllAndDoesNotThrow() {
        LicenseChecker lc = new LicenseChecker(List.of(), List.of());
        assertTrue(lc.isAllowed(mockExchange("1.2.3.4", "example.com")));
    }

    @Test
    void allowedByIp() {
        LicenseChecker lc = new LicenseChecker(List.of("192.168.1.0/24"), List.of());
        assertTrue(lc.isAllowed(mockExchange("192.168.1.50", null)));
        assertFalse(lc.isAllowed(mockExchange("10.0.0.1", null)));
    }

    @Test
    void allowedByDomain() {
        LicenseChecker lc = new LicenseChecker(List.of(), List.of("mycompany.com"));
        assertTrue(lc.isAllowed(mockExchange("1.2.3.4", "mycompany.com")));
        assertTrue(lc.isAllowed(mockExchange("1.2.3.4", "admin.mycompany.com")));
        assertFalse(lc.isAllowed(mockExchange("1.2.3.4", "evil.com")));
    }

    @Test
    void domainMatchStripPort() {
        LicenseChecker lc = new LicenseChecker(List.of(), List.of("mycompany.com"));
        assertTrue(lc.isAllowed(mockExchange("1.2.3.4", "mycompany.com:8080")));
    }

    @Test
    void ipTakesPriorityOverDomain() {
        LicenseChecker lc = new LicenseChecker(List.of("10.0.0.1"), List.of("mycompany.com"));
        assertTrue(lc.isAllowed(mockExchange("10.0.0.1", "other.com")));
        assertTrue(lc.isAllowed(mockExchange("9.9.9.9", "mycompany.com")));
        assertFalse(lc.isAllowed(mockExchange("9.9.9.9", "evil.com")));
    }
}
