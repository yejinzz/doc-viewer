# doc-viewer v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** doc-viewer를 path 기반에서 key 기반으로 전환하고, SQLite 레지스트리, IP 화이트리스트, 라이선스, 보안 강화, 개선된 UI를 추가한다.

**Architecture:** CMS 서버가 `POST /docviewer/api/convert`로 파일을 등록·변환하고 키를 받는다. 브라우저는 `GET /docviewer/view?key=xxx`로 뷰어를 연다. doc-viewer는 SQLite(`docviewer.db`)에 key→path 매핑을 저장하며, 파일 수정 감지는 lastModified 빠른 체크 + SHA-256 풀 체크 2단계로 처리한다.

**Tech Stack:** JDK 11, JodConverter 4.4.2, `org.xerial:sqlite-jdbc:3.45.3.0` (신규), SLF4J + Logback, Maven shade (fat-jar), Vanilla JS, Mozilla pdf.js 4.2.67

---

## 파일 구조

```
신규:
  src/main/java/com/docviewer/security/IpWhitelistFilter.java
  src/main/java/com/docviewer/security/LicenseChecker.java
  src/main/java/com/docviewer/registry/FileKeyRegistry.java
  src/main/java/com/docviewer/util/HashUtil.java
  src/main/java/com/docviewer/handler/ApiHandler.java
  src/test/java/com/docviewer/security/IpWhitelistFilterTest.java
  src/test/java/com/docviewer/security/LicenseCheckerTest.java
  src/test/java/com/docviewer/registry/FileKeyRegistryTest.java
  src/test/java/com/docviewer/handler/ApiHandlerTest.java

수정:
  pom.xml                          — sqlite-jdbc 의존성 추가
  src/main/java/com/docviewer/config/DocViewerConfig.java     — v2 설정 항목
  src/main/java/com/docviewer/detector/FileTypeDetector.java  — 설정 기반 확장자
  src/main/java/com/docviewer/cache/ConversionCache.java      — result.dir 사용
  src/main/java/com/docviewer/handler/ViewHandler.java        — ?path → ?key, 해시체크
  src/main/java/com/docviewer/handler/FileHandler.java        — ?key 기반, 보안강화
  src/main/java/com/docviewer/DocViewerServer.java            — 신규 컴포넌트 조립
  src/main/resources/static/viewer.html  — 에러/로딩 UI
  src/main/resources/static/viewer.js    — 에러 케이스 처리
  src/main/resources/static/viewer.css   — 에러/로딩 스타일
  src/main/resources/static/doc-viewer-client.js — key 기반
  src/test/java/com/docviewer/config/DocViewerConfigTest.java
  src/test/java/com/docviewer/detector/FileTypeDetectorTest.java
  src/test/java/com/docviewer/handler/ViewHandlerTest.java
  src/test/java/com/docviewer/handler/FileHandlerTest.java
```

---

## Task 1: pom.xml + DocViewerConfig v2

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/docviewer/config/DocViewerConfig.java`
- Modify: `src/test/java/com/docviewer/config/DocViewerConfigTest.java`

- [ ] **Step 1: DocViewerConfigTest 업데이트 (새 필드 + properties 파일 테스트 추가)**

`src/test/java/com/docviewer/config/DocViewerConfigTest.java` 전체 교체:
```java
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
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
mvn test -Dtest=DocViewerConfigTest -q
```
Expected: FAIL (새 필드 없음)

- [ ] **Step 3: pom.xml에 sqlite-jdbc 추가**

`pom.xml`의 `<dependencies>` 블록에서 junit 의존성 바로 위에 추가:
```xml
        <dependency>
            <groupId>org.xerial</groupId>
            <artifactId>sqlite-jdbc</artifactId>
            <version>3.45.3.0</version>
        </dependency>
```

- [ ] **Step 4: DocViewerConfig v2 구현**

`src/main/java/com/docviewer/config/DocViewerConfig.java` 전체 교체:
```java
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
        this.allowedPaths = Collections.unmodifiableList(b.allowedPaths);
        this.resultDir = b.resultDir;
        this.maxFileSizeBytes = b.maxFileSizeBytes;
        this.allowedExtensions = Collections.unmodifiableSet(b.allowedExtensions);
        this.licenseAllowedIps = Collections.unmodifiableList(b.licenseAllowedIps);
        this.licenseAllowedDomains = Collections.unmodifiableList(b.licenseAllowedDomains);
        this.apiAllowedIps = Collections.unmodifiableList(b.apiAllowedIps);
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
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
mvn test -Dtest=DocViewerConfigTest -q
```
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/docviewer/config/DocViewerConfig.java \
        src/test/java/com/docviewer/config/DocViewerConfigTest.java
git commit -m "feat: DocViewerConfig v2 - add resultDir, extensions whitelist, IP whitelist, properties file support"
```

---

## Task 2: IpWhitelistFilter

**Files:**
- Create: `src/main/java/com/docviewer/security/IpWhitelistFilter.java`
- Create: `src/test/java/com/docviewer/security/IpWhitelistFilterTest.java`

- [ ] **Step 1: 테스트 작성**

`src/test/java/com/docviewer/security/IpWhitelistFilterTest.java`:
```java
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
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
mvn test -Dtest=IpWhitelistFilterTest -q
```
Expected: FAIL (클래스 없음)

- [ ] **Step 3: IpWhitelistFilter 구현**

`src/main/java/com/docviewer/security/IpWhitelistFilter.java`:
```java
package com.docviewer.security;

import java.util.List;

public class IpWhitelistFilter {
    private final List<String> entries;

    public IpWhitelistFilter(List<String> entries) {
        this.entries = entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean isAllowed(String remoteAddress) {
        if (entries.isEmpty()) return true;
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
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
mvn test -Dtest=IpWhitelistFilterTest -q
```
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docviewer/security/IpWhitelistFilter.java \
        src/test/java/com/docviewer/security/IpWhitelistFilterTest.java
git commit -m "feat: add IpWhitelistFilter with CIDR support"
```

---

## Task 3: LicenseChecker

**Files:**
- Create: `src/main/java/com/docviewer/security/LicenseChecker.java`
- Create: `src/test/java/com/docviewer/security/LicenseCheckerTest.java`

- [ ] **Step 1: 테스트 작성**

`src/test/java/com/docviewer/security/LicenseCheckerTest.java`:
```java
package com.docviewer.security;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class LicenseCheckerTest {

    private HttpExchange mockExchange(String remoteIp, String hostHeader) {
        HttpExchange ex = mock(HttpExchange.class);
        when(ex.getRemoteAddress()).thenReturn(new InetSocketAddress(remoteIp, 12345));
        Headers headers = new Headers();
        if (hostHeader != null) headers.add("Host", hostHeader);
        when(ex.getRequestHeaders()).thenReturn(headers);
        return ex;
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
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
mvn test -Dtest=LicenseCheckerTest -q
```
Expected: FAIL

- [ ] **Step 3: LicenseChecker 구현**

`src/main/java/com/docviewer/security/LicenseChecker.java`:
```java
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
        this.allowedDomains = allowedDomains;
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
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
mvn test -Dtest=LicenseCheckerTest -q
```
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docviewer/security/ \
        src/test/java/com/docviewer/security/LicenseCheckerTest.java
git commit -m "feat: add LicenseChecker for IP/domain based access control"
```

---

## Task 4: FileKeyRegistry + HashUtil

**Files:**
- Create: `src/main/java/com/docviewer/registry/FileKeyRegistry.java`
- Create: `src/main/java/com/docviewer/util/HashUtil.java`
- Create: `src/test/java/com/docviewer/registry/FileKeyRegistryTest.java`

- [ ] **Step 1: FileKeyRegistryTest 작성**

`src/test/java/com/docviewer/registry/FileKeyRegistryTest.java`:
```java
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
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
mvn test -Dtest=FileKeyRegistryTest -q
```
Expected: FAIL (클래스 없음)

- [ ] **Step 3: FileKeyRegistry 구현**

`src/main/java/com/docviewer/registry/FileKeyRegistry.java`:
```java
package com.docviewer.registry;

import org.slf4j.*;
import java.io.Closeable;
import java.nio.file.Path;
import java.sql.*;

public class FileKeyRegistry implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(FileKeyRegistry.class);

    private final Connection conn;

    public static class FileKeyEntry {
        public final String key;
        public final String filePath;
        public final String originalName;
        public final String fileHash;
        public final long fileSize;
        public final long lastModified;
        public final String convertStatus;

        FileKeyEntry(String key, String filePath, String originalName,
                     String fileHash, long fileSize, long lastModified, String convertStatus) {
            this.key = key;
            this.filePath = filePath;
            this.originalName = originalName;
            this.fileHash = fileHash;
            this.fileSize = fileSize;
            this.lastModified = lastModified;
            this.convertStatus = convertStatus;
        }
    }

    public FileKeyRegistry(Path dbPath) throws SQLException {
        this("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    public static FileKeyRegistry forTesting() throws SQLException {
        return new FileKeyRegistry("jdbc:sqlite::memory:");
    }

    FileKeyRegistry(String jdbcUrl) throws SQLException {
        this.conn = DriverManager.getConnection(jdbcUrl);
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute(
                "CREATE TABLE IF NOT EXISTS file_keys (" +
                "  key            TEXT PRIMARY KEY," +
                "  file_path      TEXT NOT NULL," +
                "  original_name  TEXT," +
                "  file_hash      TEXT," +
                "  file_size      INTEGER," +
                "  last_modified  INTEGER," +
                "  converted_at   TEXT," +
                "  convert_status TEXT DEFAULT 'registered'," +
                "  error_message  TEXT" +
                ")"
            );
            s.execute("CREATE INDEX IF NOT EXISTS idx_status ON file_keys(convert_status)");
        }
    }

    public void register(String key, String filePath, String originalName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO file_keys (key, file_path, original_name, convert_status) VALUES (?,?,?,'registered')")) {
            ps.setString(1, key);
            ps.setString(2, filePath);
            ps.setString(3, originalName);
            ps.executeUpdate();
        }
    }

    public void markConverted(String key, String fileHash, long fileSize, long lastModified) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE file_keys SET file_hash=?, file_size=?, last_modified=?, " +
                "convert_status='converted', converted_at=datetime('now'), error_message=NULL WHERE key=?")) {
            ps.setString(1, fileHash);
            ps.setLong(2, fileSize);
            ps.setLong(3, lastModified);
            ps.setString(4, key);
            ps.executeUpdate();
        }
    }

    public void markError(String key, String errorMessage) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE file_keys SET convert_status='error', error_message=? WHERE key=?")) {
            ps.setString(1, errorMessage);
            ps.setString(2, key);
            ps.executeUpdate();
        }
    }

    public void updateMetadata(String key, String fileHash, long fileSize, long lastModified) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE file_keys SET file_hash=?, file_size=?, last_modified=? WHERE key=?")) {
            ps.setString(1, fileHash);
            ps.setLong(2, fileSize);
            ps.setLong(3, lastModified);
            ps.setString(4, key);
            ps.executeUpdate();
        }
    }

    public FileKeyEntry findByKey(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT key, file_path, original_name, file_hash, file_size, last_modified, convert_status " +
                "FROM file_keys WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new FileKeyEntry(
                    rs.getString("key"), rs.getString("file_path"), rs.getString("original_name"),
                    rs.getString("file_hash"), rs.getLong("file_size"),
                    rs.getLong("last_modified"), rs.getString("convert_status")
                );
            }
        }
    }

    public String getStatus(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT convert_status FROM file_keys WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void delete(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM file_keys WHERE key=?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            log.warn("Error closing registry connection", e);
        }
    }
}
```

- [ ] **Step 4: HashUtil 구현** (테스트 없음 — 다른 태스크에서 간접 검증)

`src/main/java/com/docviewer/util/HashUtil.java`:
```java
package com.docviewer.util;

import java.io.*;
import java.nio.file.Files;
import java.security.*;

public class HashUtil {

    public static String sha256File(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file.toPath())) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String sha256String(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(input.getBytes("UTF-8")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
mvn test -Dtest=FileKeyRegistryTest -q
```
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/docviewer/registry/ \
        src/main/java/com/docviewer/util/ \
        src/test/java/com/docviewer/registry/
git commit -m "feat: add FileKeyRegistry (SQLite) and HashUtil"
```

---

## Task 5: FileTypeDetector v2

**Files:**
- Modify: `src/main/java/com/docviewer/detector/FileTypeDetector.java`
- Modify: `src/test/java/com/docviewer/detector/FileTypeDetectorTest.java`

- [ ] **Step 1: FileTypeDetectorTest에 새 테스트 케이스 추가**

`src/test/java/com/docviewer/detector/FileTypeDetectorTest.java` 끝에 추가:
```java
    @Test
    void customAllowedExtensionsRestrictsIsSupported() {
        FileTypeDetector restricted = new FileTypeDetector(java.util.Set.of("pdf", "hwp"));
        assertTrue(restricted.isSupported("report.pdf"));
        assertTrue(restricted.isSupported("doc.hwp"));
        assertFalse(restricted.isSupported("sheet.xlsx")); // not in custom list
        assertFalse(restricted.isSupported("image.png"));  // not in custom list
    }

    @Test
    void detectStillWorksWithCustomExtensions() {
        FileTypeDetector restricted = new FileTypeDetector(java.util.Set.of("pdf", "hwp", "jpg"));
        assertEquals(FileTypeDetector.RenderType.PDF, restricted.detect("doc.pdf"));
        assertEquals(FileTypeDetector.RenderType.IMAGE, restricted.detect("photo.jpg"));
        assertEquals(FileTypeDetector.RenderType.LIBREOFFICE, restricted.detect("file.hwp"));
    }
```

파일 끝 `}` 닫는 괄호 앞에 위의 두 테스트를 추가하면 됨.

- [ ] **Step 2: 테스트 실패 확인**

```bash
mvn test -Dtest=FileTypeDetectorTest -q
```
Expected: FAIL (생성자 없음)

- [ ] **Step 3: FileTypeDetector v2 구현**

`src/main/java/com/docviewer/detector/FileTypeDetector.java` 전체 교체:
```java
package com.docviewer.detector;

import java.util.Map;
import java.util.Set;

public class FileTypeDetector {

    public enum RenderType { PDF, TEXT, IMAGE, LIBREOFFICE }

    private static final Set<String> IMAGE_EXTS = Set.of(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico"
    );
    private static final Set<String> TEXT_EXTS = Set.of("txt", "log", "csv");
    private static final Set<String> PDF_EXTS = Set.of("pdf");
    private static final Map<String, String> MIME_MAP = Map.ofEntries(
        Map.entry("pdf",  "application/pdf"),
        Map.entry("txt",  "text/plain"),
        Map.entry("csv",  "text/csv"),
        Map.entry("jpg",  "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("png",  "image/png"),
        Map.entry("gif",  "image/gif"),
        Map.entry("webp", "image/webp"),
        Map.entry("bmp",  "image/bmp"),
        Map.entry("svg",  "image/svg+xml")
    );

    private final Set<String> allowedExtensions;

    public FileTypeDetector() {
        this.allowedExtensions = null;
    }

    public FileTypeDetector(Set<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public RenderType detect(String filename) {
        String ext = ext(filename);
        if (PDF_EXTS.contains(ext))   return RenderType.PDF;
        if (TEXT_EXTS.contains(ext))  return RenderType.TEXT;
        if (IMAGE_EXTS.contains(ext)) return RenderType.IMAGE;
        return RenderType.LIBREOFFICE;
    }

    public String mimeType(String filename) {
        return MIME_MAP.getOrDefault(ext(filename), "application/octet-stream");
    }

    public boolean isSupported(String filename) {
        String ext = ext(filename);
        if (allowedExtensions != null) return allowedExtensions.contains(ext);
        return PDF_EXTS.contains(ext) || TEXT_EXTS.contains(ext)
            || IMAGE_EXTS.contains(ext) || isLibreOfficeExt(ext);
    }

    private boolean isLibreOfficeExt(String ext) {
        return Set.of("doc","docx","hwp","hwpx","xls","xlsx","ods",
                      "ppt","pptx","odp","odt","rtf").contains(ext);
    }

    private String ext(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
mvn test -Dtest=FileTypeDetectorTest -q
```
Expected: `Tests run: 11, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docviewer/detector/FileTypeDetector.java \
        src/test/java/com/docviewer/detector/FileTypeDetectorTest.java
git commit -m "feat: FileTypeDetector v2 - config-driven extension whitelist"
```

---

## Task 6: ConversionCache v2

**Files:**
- Modify: `src/main/java/com/docviewer/cache/ConversionCache.java`

ConversionCache의 `(Path, long)` 생성자는 이제 `resultDir`(부모)를 받고 내부에 `cache/` 서브디렉토리를 생성한다. 기존 테스트는 이 생성자로 `tempDir`을 넘기므로, 캐시가 `tempDir/cache/`로 이동하지만 `cachedPath()` 반환값이 일치하므로 로직은 그대로 동작한다.

- [ ] **Step 1: 기존 테스트가 아직 통과하는지 확인**

```bash
mvn test -Dtest=ConversionCacheTest -q
```
Expected: PASS (변경 전 기준)

- [ ] **Step 2: ConversionCache 수정**

`src/main/java/com/docviewer/cache/ConversionCache.java`에서:

```java
    public ConversionCache(long ttlSeconds) throws IOException {
        this(Paths.get(System.getProperty("java.io.tmpdir"), "docviewer-cache"), ttlSeconds);
    }

    public ConversionCache(Path cacheDir, long ttlSeconds) throws IOException {
        this.cacheDir = cacheDir;
```

를 아래로 교체:

```java
    public ConversionCache(Path resultDir, long ttlSeconds) throws IOException {
        this.cacheDir = resultDir.resolve("cache");
```

즉, `public ConversionCache(long ttlSeconds)` 생성자를 삭제하고 `(Path, long)` 생성자의 바디를 `resultDir.resolve("cache")`로 변경한다.

전체 교체 후 파일:
```java
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

    public ConversionCache(Path resultDir, long ttlSeconds) throws IOException {
        this.cacheDir = resultDir.resolve("cache");
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
            try { Files.deleteIfExists(cachedPath(cacheId)); } catch (IOException ignored) {}
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
```

- [ ] **Step 3: 테스트 통과 확인**

```bash
mvn test -Dtest=ConversionCacheTest -q
```
Expected: `Tests run: 4, Failures: 0, Errors: 0` (캐시 경로가 `tempDir/cache/`로 이동해도 `cachedPath()` 참조가 일치하므로 통과)

- [ ] **Step 3b: ConversionCacheTest에 invalidateCache 테스트 추가**

`src/test/java/com/docviewer/cache/ConversionCacheTest.java`의 마지막 테스트 끝(닫는 `}` 전)에 추가:
```java
    @Test
    void invalidateCacheDeletesCachedFile() throws Exception {
        File source = File.createTempFile("src", ".hwp", tempDir.toFile());
        String id = cache.getOrConvert(source, (src, dest) ->
            java.nio.file.Files.write(dest.toPath(), "pdf".getBytes()));
        assertTrue(cache.isCached(id));
        cache.invalidateCache(source);
        assertFalse(cache.isCached(id));
    }
```

- [ ] **Step 3c: ConversionCache에 invalidateCache 추가**

`src/main/java/com/docviewer/cache/ConversionCache.java`의 `cleanup()` 메서드 바로 위에 추가:
```java
    public void invalidateCache(File source) {
        String id = cacheId(source);
        try { Files.deleteIfExists(cachedPath(id)); }
        catch (IOException e) { log.warn("Failed to invalidate cache for {}", source.getName(), e); }
    }
```

- [ ] **Step 3d: ConversionCacheTest 통과 확인**

```bash
mvn test -Dtest=ConversionCacheTest -q
```
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/docviewer/cache/ConversionCache.java \
        src/test/java/com/docviewer/cache/ConversionCacheTest.java
git commit -m "feat: ConversionCache v2 - result.dir/cache/, add invalidateCache()"
```

---

## Task 7: ApiHandler

**Files:**
- Create: `src/main/java/com/docviewer/handler/ApiHandler.java`
- Create: `src/test/java/com/docviewer/handler/ApiHandlerTest.java`

ApiHandler는 CMS 서버 전용 내부 API (`/docviewer/api/*`)를 처리한다. 모든 요청에 `apiAllowedIps` 체크를 적용한다.

- [ ] **Step 1: ApiHandlerTest 작성**

`src/test/java/com/docviewer/handler/ApiHandlerTest.java`:
```java
package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.registry.FileKeyRegistry;
import com.docviewer.security.IpWhitelistFilter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ApiHandlerTest {
    private HttpServer server;
    private int port;
    private Path tempDir;
    private FileKeyRegistry registry;
    private ConversionCache cache;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("api-test-");
        registry = FileKeyRegistry.forTesting();
        cache = new ConversionCache(tempDir, 86400L);

        DocViewerConfig config = DocViewerConfig.fromArgs(new String[]{
            "--result-dir=" + tempDir.toAbsolutePath(),
            "--allowed-paths=" + tempDir.toAbsolutePath(),
            "--api-allowed-ips=127.0.0.1"
        });
        FileTypeDetector detector = new FileTypeDetector(config.allowedExtensions);

        DocumentConverter converter = new DocumentConverter() {
            public void convert(File src, File dest) throws Exception {
                Files.write(dest.toPath(), "%PDF-1.4 dummy".getBytes());
            }
            public boolean isAlive() { return true; }
            public void shutdown() {}
        };

        IpWhitelistFilter apiFilter = new IpWhitelistFilter(config.apiAllowedIps);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docviewer/api",
            new ApiHandler(config, converter, cache, detector, registry, apiFilter));
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        registry.close();
        Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder())
            .map(Path::toFile).forEach(File::delete);
    }

    @Test
    void convertRegistersAndConvertsFile() throws Exception {
        File hwp = File.createTempFile("test", ".hwp", tempDir.toFile());
        Files.write(hwp.toPath(), "fake hwp".getBytes());

        String body = String.format(
            "{\"key\":\"FILE_001_0\",\"path\":\"%s\",\"originalName\":\"문서.hwp\",\"fileHash\":\"\"}",
            hwp.getAbsolutePath().replace("\\", "\\\\"));

        HttpResponse<String> resp = post("/docviewer/api/convert", body);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"status\":\"ok\""));
        assertEquals("converted", registry.getStatus("FILE_001_0"));
    }

    @Test
    void convertRejects415ForUnsupportedExtension() throws Exception {
        File exe = File.createTempFile("test", ".exe", tempDir.toFile());
        Files.write(exe.toPath(), new byte[]{0});

        String body = String.format(
            "{\"key\":\"FILE_002_0\",\"path\":\"%s\",\"originalName\":\"bad.exe\",\"fileHash\":\"\"}",
            exe.getAbsolutePath().replace("\\", "\\\\"));

        HttpResponse<String> resp = post("/docviewer/api/convert", body);
        assertEquals(415, resp.statusCode());
    }

    @Test
    void convertRejects413ForOversizedFile() throws Exception {
        DocViewerConfig smallConfig = DocViewerConfig.fromArgs(new String[]{
            "--result-dir=" + tempDir.toAbsolutePath(),
            "--allowed-paths=" + tempDir.toAbsolutePath(),
            "--max-file-size=10",
            "--api-allowed-ips=127.0.0.1"
        });
        File big = File.createTempFile("big", ".pdf", tempDir.toFile());
        Files.write(big.toPath(), new byte[11]);

        FileTypeDetector det = new FileTypeDetector(smallConfig.allowedExtensions);
        DocumentConverter conv = (src, dest) -> {};
        IpWhitelistFilter f = new IpWhitelistFilter(smallConfig.apiAllowedIps);
        ApiHandler handler = new ApiHandler(smallConfig, conv, cache, det, registry, f);

        HttpServer s2 = HttpServer.create(new InetSocketAddress(0), 0);
        s2.createContext("/docviewer/api", handler);
        s2.setExecutor(null);
        s2.start();
        int p2 = s2.getAddress().getPort();
        try {
            String body = String.format(
                "{\"key\":\"FILE_003_0\",\"path\":\"%s\",\"originalName\":\"big.pdf\",\"fileHash\":\"\"}",
                big.getAbsolutePath().replace("\\", "\\\\"));
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + p2 + "/docviewer/api/convert"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(413, resp.statusCode());
        } finally {
            s2.stop(0);
        }
    }

    @Test
    void convertRejects400ForInvalidKeyFormat() throws Exception {
        String body = "{\"key\":\"../evil\",\"path\":\"/tmp/f.hwp\",\"originalName\":\"f.hwp\",\"fileHash\":\"\"}";
        HttpResponse<String> resp = post("/docviewer/api/convert", body);
        assertEquals(400, resp.statusCode());
    }

    @Test
    void statusReturnsConvertStatus() throws Exception {
        registry.register("FILE_STATUS_0", "/data/f.pdf", "f.pdf");
        HttpResponse<String> resp = get("/docviewer/api/status/FILE_STATUS_0");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("registered"));
    }

    @Test
    void statusReturns404ForUnknownKey() throws Exception {
        HttpResponse<String> resp = get("/docviewer/api/status/UNKNOWN_KEY");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void deleteKeyRemovesEntry() throws Exception {
        registry.register("FILE_DEL_0", "/data/f.pdf", "f.pdf");
        HttpResponse<String> resp = delete("/docviewer/api/key/FILE_DEL_0");
        assertEquals(200, resp.statusCode());
        assertNull(registry.getStatus("FILE_DEL_0"));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method("DELETE", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
mvn test -Dtest=ApiHandlerTest -q
```
Expected: FAIL (클래스 없음)

- [ ] **Step 3: ApiHandler 구현**

`src/main/java/com/docviewer/handler/ApiHandler.java`:
```java
package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.registry.FileKeyRegistry;
import com.docviewer.security.IpWhitelistFilter;
import com.docviewer.util.HashUtil;
import com.sun.net.httpserver.*;
import org.slf4j.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ApiHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{1,100}$");

    private final DocViewerConfig config;
    private final DocumentConverter converter;
    private final ConversionCache cache;
    private final FileTypeDetector detector;
    private final FileKeyRegistry registry;
    private final IpWhitelistFilter apiFilter;

    public ApiHandler(DocViewerConfig config, DocumentConverter converter, ConversionCache cache,
                      FileTypeDetector detector, FileKeyRegistry registry, IpWhitelistFilter apiFilter) {
        this.config = config;
        this.converter = converter;
        this.cache = cache;
        this.detector = detector;
        this.registry = registry;
        this.apiFilter = apiFilter;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String remoteIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!apiFilter.isAllowed(remoteIp)) {
            sendJson(exchange, 403, "{\"status\":\"error\",\"message\":\"IP not allowed\"}");
            return;
        }
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        try {
            if ("POST".equals(method) && path.equals("/docviewer/api/convert")) {
                handleConvert(exchange);
            } else if ("POST".equals(method) && path.equals("/docviewer/api/refresh")) {
                handleRefresh(exchange);
            } else if ("DELETE".equals(method) && path.startsWith("/docviewer/api/key/")) {
                handleDelete(exchange, path.substring("/docviewer/api/key/".length()));
            } else if ("GET".equals(method) && path.startsWith("/docviewer/api/status/")) {
                handleStatus(exchange, path.substring("/docviewer/api/status/".length()));
            } else {
                sendJson(exchange, 404, "{\"status\":\"error\",\"message\":\"Not found\"}");
            }
        } catch (Exception e) {
            log.error("API error", e);
            sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"Internal error\"}");
        }
    }

    private void handleConvert(HttpExchange exchange) throws Exception {
        Map<String, String> req = parseJsonBody(exchange);
        String key = req.get("key");
        String filePath = req.get("path");
        String originalName = req.getOrDefault("originalName", "");

        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid key format\"}");
            return;
        }
        if (filePath == null || filePath.isEmpty()) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"path required\"}");
            return;
        }

        Path resolved;
        try { resolved = Paths.get(filePath).normalize().toAbsolutePath(); }
        catch (Exception e) { sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid path\"}"); return; }

        if (!config.allowedPaths.isEmpty()) {
            boolean allowed = config.allowedPaths.stream()
                .anyMatch(ap -> resolved.startsWith(Paths.get(ap).toAbsolutePath()));
            if (!allowed) { sendJson(exchange, 403, "{\"status\":\"error\",\"message\":\"Path not allowed\"}"); return; }
        }

        File file = resolved.toFile();
        if (!file.exists() || !file.isFile()) {
            sendJson(exchange, 404, "{\"status\":\"error\",\"message\":\"File not found\"}"); return;
        }
        if (Files.isSymbolicLink(resolved)) {
            sendJson(exchange, 403, "{\"status\":\"error\",\"message\":\"Symbolic links not allowed\"}"); return;
        }
        if (file.length() > config.maxFileSizeBytes) {
            sendJson(exchange, 413, "{\"status\":\"error\",\"message\":\"File too large (max " + (config.maxFileSizeBytes / 1024 / 1024) + "MB)\"}"); return;
        }
        String displayName = originalName.isEmpty() ? file.getName() : originalName;
        if (!detector.isSupported(displayName)) {
            sendJson(exchange, 415, "{\"status\":\"error\",\"message\":\"Unsupported file type\"}"); return;
        }

        try {
            registry.register(key, resolved.toString(), displayName);
            cache.getOrConvert(file, converter::convert);
            String hash = HashUtil.sha256File(file);
            registry.markConverted(key, hash, file.length(), file.lastModified());
            log.info("Converted and registered key={} path={}", key, resolved);
            sendJson(exchange, 200, "{\"status\":\"ok\",\"key\":\"" + key + "\"}");
        } catch (Exception e) {
            try { registry.markError(key, e.getMessage()); } catch (Exception ignored) {}
            log.error("Conversion failed for key={}", key, e);
            sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"Conversion failed: " + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleRefresh(HttpExchange exchange) throws Exception {
        String query = exchange.getRequestURI().getRawQuery();
        String key = queryParam(query, "key");
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid key\"}"); return;
        }
        FileKeyRegistry.FileKeyEntry entry = registry.findByKey(key);
        if (entry == null) {
            sendJson(exchange, 404, "{\"status\":\"error\",\"message\":\"Key not found\"}"); return;
        }
        File file = new File(entry.filePath);
        if (!file.exists()) {
            sendJson(exchange, 404, "{\"status\":\"error\",\"message\":\"File not found\"}"); return;
        }
        try {
            cache.invalidateCache(file); // 강제 재변환을 위해 캐시 먼저 삭제
            cache.getOrConvert(file, converter::convert);
            String hash = HashUtil.sha256File(file);
            registry.markConverted(key, hash, file.length(), file.lastModified());
            sendJson(exchange, 200, "{\"status\":\"ok\",\"key\":\"" + key + "\"}");
        } catch (Exception e) {
            log.error("Refresh failed for key={}", key, e);
            sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"Refresh failed\"}");
        }
    }

    private void handleDelete(HttpExchange exchange, String key) throws Exception {
        if (key.isEmpty() || !KEY_PATTERN.matcher(key).matches()) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid key\"}"); return;
        }
        registry.delete(key);
        sendJson(exchange, 200, "{\"status\":\"ok\"}");
    }

    private void handleStatus(HttpExchange exchange, String key) throws Exception {
        if (key.isEmpty() || !KEY_PATTERN.matcher(key).matches()) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid key\"}"); return;
        }
        String status = registry.getStatus(key);
        if (status == null) {
            sendJson(exchange, 404, "{\"status\":\"error\",\"message\":\"Key not found\"}"); return;
        }
        sendJson(exchange, 200, "{\"status\":\"ok\",\"convertStatus\":\"" + status + "\"}");
    }

    private Map<String, String> parseJsonBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), "UTF-8");
        Map<String, String> map = new LinkedHashMap<>();
        Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(body);
        while (m.find()) map.put(m.group(1), m.group(2));
        return map;
    }

    private String queryParam(String query, String name) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    private void sendJson(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
mvn test -Dtest=ApiHandlerTest -q
```
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docviewer/handler/ApiHandler.java \
        src/test/java/com/docviewer/handler/ApiHandlerTest.java
git commit -m "feat: add ApiHandler for internal CMS-to-viewer API (/api/convert, /api/refresh, /api/key, /api/status)"
```

---

## Task 8: ViewHandler v2

**Files:**
- Modify: `src/main/java/com/docviewer/handler/ViewHandler.java`
- Modify: `src/test/java/com/docviewer/handler/ViewHandlerTest.java`

`?path=` → `?key=`로 전환. 레지스트리에서 경로를 조회하고, 파일 수정 감지(lastModified 빠른 체크 + SHA-256 풀 체크)를 수행한다.

- [ ] **Step 1: ViewHandlerTest v2로 전체 교체**

`src/test/java/com/docviewer/handler/ViewHandlerTest.java`:
```java
package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.registry.FileKeyRegistry;
import com.docviewer.security.LicenseChecker;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ViewHandlerTest {
    private HttpServer server;
    private int port;
    private Path tempDir;
    private ConversionCache cache;
    private FileKeyRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("vh-test-");
        cache = new ConversionCache(tempDir, 86400L);
        registry = FileKeyRegistry.forTesting();

        DocViewerConfig config = DocViewerConfig.fromArgs(new String[]{
            "--allowed-paths=" + tempDir.toAbsolutePath()
        });
        FileTypeDetector detector = new FileTypeDetector(config.allowedExtensions);
        LicenseChecker license = new LicenseChecker(List.of(), List.of());

        DocumentConverter converter = new DocumentConverter() {
            public void convert(File src, File dest) throws Exception {
                Files.write(dest.toPath(), "%PDF-1.4 dummy".getBytes());
            }
            public boolean isAlive() { return true; }
            public void shutdown() {}
        };

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docviewer/view",
            new ViewHandler(config, converter, cache, detector, registry, license));
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        registry.close();
        Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder())
            .map(Path::toFile).forEach(File::delete);
    }

    @Test
    void returns400WhenKeyMissing() throws Exception {
        assertEquals(400, get("/docviewer/view").statusCode());
    }

    @Test
    void returns400ForInvalidKeyFormat() throws Exception {
        assertEquals(400, get("/docviewer/view?key=../evil").statusCode());
    }

    @Test
    void returns404ForUnregisteredKey() throws Exception {
        assertEquals(404, get("/docviewer/view?key=UNKNOWN_KEY_001").statusCode());
    }

    @Test
    void returns404WhenStatusNotConverted() throws Exception {
        registry.register("FILE_NOTYET_0", "/data/f.hwp", "f.hwp");
        assertEquals(404, get("/docviewer/view?key=FILE_NOTYET_0").statusCode());
    }

    @Test
    void servesViewerHtmlForConvertedPdfKey() throws Exception {
        File pdf = File.createTempFile("test", ".pdf", tempDir.toFile());
        Files.write(pdf.toPath(), "%PDF-1.4".getBytes());
        String key = "FILE_PDF_0";
        registry.register(key, pdf.getAbsolutePath(), "test.pdf");
        registry.markConverted(key, "hash1", pdf.length(), pdf.lastModified());

        HttpResponse<String> resp = get("/docviewer/view?key=" + key);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("DOCVIEWER_CONFIG"));
        assertTrue(resp.body().contains("\"renderType\":\"pdf\""));
    }

    @Test
    void triggersConversionForHwpAndServesViewerHtml() throws Exception {
        File hwp = File.createTempFile("doc", ".hwp", tempDir.toFile());
        Files.write(hwp.toPath(), "fake hwp".getBytes());
        String key = "FILE_HWP_0";
        registry.register(key, hwp.getAbsolutePath(), "doc.hwp");
        registry.markConverted(key, "hash1", hwp.length(), hwp.lastModified());

        HttpResponse<String> resp = get("/docviewer/view?key=" + key);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"renderType\":\"pdf\""));
        assertTrue(resp.body().contains("/docviewer/file?id="));
    }

    @Test
    void servesImageViewerForImageKey() throws Exception {
        File img = File.createTempFile("photo", ".png", tempDir.toFile());
        Files.write(img.toPath(), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        String key = "FILE_IMG_0";
        registry.register(key, img.getAbsolutePath(), "photo.png");
        registry.markConverted(key, "hash1", img.length(), img.lastModified());

        HttpResponse<String> resp = get("/docviewer/view?key=" + key);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"renderType\":\"image\""));
        assertTrue(resp.body().contains("/docviewer/file?key=" + key));
    }

    @Test
    void returns404WhenFileIsMissing() throws Exception {
        String key = "FILE_MISSING_0";
        registry.register(key, "/nonexistent/file.pdf", "file.pdf");
        registry.markConverted(key, "hash1", 100L, 999L);
        assertEquals(404, get("/docviewer/view?key=" + key).statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
mvn test -Dtest=ViewHandlerTest -q
```
Expected: FAIL (시그니처 변경)

- [ ] **Step 3: ViewHandler v2 구현**

`src/main/java/com/docviewer/handler/ViewHandler.java` 전체 교체:
```java
package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.registry.FileKeyRegistry;
import com.docviewer.security.LicenseChecker;
import com.docviewer.util.HashUtil;
import com.sun.net.httpserver.*;
import org.slf4j.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ViewHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(ViewHandler.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{1,100}$");

    private final DocViewerConfig config;
    private final DocumentConverter converter;
    private final ConversionCache cache;
    private final FileTypeDetector detector;
    private final FileKeyRegistry registry;
    private final LicenseChecker license;

    public ViewHandler(DocViewerConfig config, DocumentConverter converter, ConversionCache cache,
                       FileTypeDetector detector, FileKeyRegistry registry, LicenseChecker license) {
        this.config = config;
        this.converter = converter;
        this.cache = cache;
        this.detector = detector;
        this.registry = registry;
        this.license = license;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!license.isAllowed(exchange)) {
            sendError(exchange, 403, null, "접근이 허용되지 않습니다");
            return;
        }

        Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
        String key = params.get("key");

        if (key == null) { sendError(exchange, 400, null, "key 파라미터가 필요합니다"); return; }
        if (!KEY_PATTERN.matcher(key).matches()) { sendError(exchange, 400, null, "잘못된 요청입니다"); return; }

        FileKeyRegistry.FileKeyEntry entry;
        try { entry = registry.findByKey(key); }
        catch (Exception e) { sendError(exchange, 500, null, "서버 오류가 발생했습니다"); return; }

        if (entry == null) {
            sendError(exchange, 404, null, "등록되지 않은 문서입니다. CMS에서 변환을 먼저 진행해주세요.");
            return;
        }
        if (!"converted".equals(entry.convertStatus)) {
            sendError(exchange, 404, null, "아직 변환되지 않은 문서입니다. CMS에서 변환을 먼저 진행해주세요.");
            return;
        }

        File file = new File(entry.filePath);
        if (!file.exists() || !file.isFile()) {
            sendError(exchange, 404, key, "파일을 찾을 수 없습니다");
            return;
        }

        checkAndUpdateHashIfChanged(key, entry, file);

        String displayName = entry.originalName != null ? entry.originalName : file.getName();
        FileTypeDetector.RenderType renderType = detector.detect(displayName);
        String clientRenderType;
        String fileUrl;

        if (renderType == FileTypeDetector.RenderType.LIBREOFFICE) {
            try {
                String cacheId = cache.getOrConvert(file, converter::convert);
                fileUrl = "/docviewer/file?id=" + cacheId;
                clientRenderType = "pdf";
            } catch (Exception e) {
                log.error("Conversion failed for key={}", key, e);
                sendError(exchange, 500, key, "문서를 변환할 수 없습니다: " + e.getMessage());
                return;
            }
        } else {
            fileUrl = "/docviewer/file?key=" + key;
            clientRenderType = renderType.name().toLowerCase();
        }

        String configJson = String.format(
            "{\"filename\":\"%s\",\"renderType\":\"%s\",\"fileUrl\":\"%s\",\"downloadKey\":\"%s\"}",
            escapeJson(displayName), clientRenderType, fileUrl, key
        );
        String html;
        try { html = loadTemplate(configJson); }
        catch (IOException e) { sendError(exchange, 500, null, "뷰어를 로드할 수 없습니다"); return; }

        byte[] bytes = html.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }

    private void checkAndUpdateHashIfChanged(String key, FileKeyRegistry.FileKeyEntry entry, File file) {
        boolean quickChanged = file.length() != entry.fileSize || file.lastModified() != entry.lastModified;
        if (!quickChanged) return;
        try {
            String currentHash = HashUtil.sha256File(file);
            registry.updateMetadata(key, currentHash, file.length(), file.lastModified());
            if (!currentHash.equals(entry.fileHash)) {
                log.info("File content changed for key={}, cache will auto-invalidate on next getOrConvert", key);
            }
        } catch (Exception e) {
            log.warn("Hash check failed for key={}", key, e);
        }
    }

    private String loadTemplate(String configJson) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/static/viewer.html")) {
            if (in == null) throw new IOException("viewer.html not found");
            return new String(in.readAllBytes(), "UTF-8")
                .replace("__DOCVIEWER_CONFIG__", configJson);
        }
    }

    private void sendError(HttpExchange exchange, int code, String downloadKey, String msg) throws IOException {
        String downloadLink = downloadKey != null
            ? "<p><a href=\"/docviewer/file?key=" + downloadKey + "\">원본 파일 다운로드</a></p>" : "";
        byte[] bytes = ("<html><head><meta charset=\"UTF-8\"><title>오류</title>" +
            "<link rel=\"stylesheet\" href=\"/docviewer/static/viewer.css\"></head>" +
            "<body><div id=\"toolbar\"><span id=\"filename\">오류</span></div>" +
            "<div id=\"viewer-container\"><div id=\"error\" style=\"display:block\">" +
            "<p>" + escapeHtml(msg) + "</p>" + downloadLink + "</div></div></body></html>")
            .getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }

    private Map<String, String> queryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
mvn test -Dtest=ViewHandlerTest -q
```
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docviewer/handler/ViewHandler.java \
        src/test/java/com/docviewer/handler/ViewHandlerTest.java
git commit -m "feat: ViewHandler v2 - key-based lookup, hash change detection, LicenseChecker integration"
```

---

## Task 9: FileHandler v2

**Files:**
- Modify: `src/main/java/com/docviewer/handler/FileHandler.java`
- Modify: `src/test/java/com/docviewer/handler/FileHandlerTest.java`

`?path=` 파라미터 제거. `?key=`로 레지스트리에서 원본 파일 경로 조회 후 서빙.

- [ ] **Step 1: FileHandlerTest v2로 전체 교체**

`src/test/java/com/docviewer/handler/FileHandlerTest.java`:
```java
package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.registry.FileKeyRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FileHandlerTest {
    private HttpServer server;
    private int port;
    private Path tempDir;
    private ConversionCache cache;
    private FileKeyRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("fh-test-");
        cache = new ConversionCache(tempDir, 86400L);
        registry = FileKeyRegistry.forTesting();

        DocViewerConfig config = DocViewerConfig.fromArgs(new String[]{
            "--allowed-paths=" + tempDir.toAbsolutePath()
        });
        FileTypeDetector detector = new FileTypeDetector();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docviewer/file",
            new FileHandler(cache, config, detector, registry));
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        registry.close();
        Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder())
            .map(Path::toFile).forEach(File::delete);
    }

    @Test
    void servesCachedPdfById() throws Exception {
        String cacheId = "testid00testid00";
        Files.createDirectories(cache.cachedPath(cacheId).getParent());
        Files.write(cache.cachedPath(cacheId), "%PDF-1.4 content".getBytes());

        HttpResponse<byte[]> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/file?id=" + cacheId)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode());
        assertEquals("application/pdf", resp.headers().firstValue("content-type").orElse(""));
    }

    @Test
    void returns404ForMissingCacheId() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/file?id=nonexistent")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }

    @Test
    void servesOriginalFileByKey() throws Exception {
        File txtFile = File.createTempFile("test", ".txt", tempDir.toFile());
        Files.write(txtFile.toPath(), "hello world".getBytes());
        registry.register("FILE_TXT_0", txtFile.getAbsolutePath(), "test.txt");
        registry.markConverted("FILE_TXT_0", "h1", txtFile.length(), txtFile.lastModified());

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/file?key=FILE_TXT_0")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("hello world", resp.body());
    }

    @Test
    void returns404ForUnknownKey() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/file?key=UNKNOWN_99")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }

    @Test
    void returns400WithNoParams() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/file")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode());
    }

    @Test
    void returns400ForInvalidCacheId() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/file?id=../evil")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
mvn test -Dtest=FileHandlerTest -q
```
Expected: FAIL (시그니처 변경)

- [ ] **Step 3: FileHandler v2 구현**

`src/main/java/com/docviewer/handler/FileHandler.java` 전체 교체:
```java
package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.registry.FileKeyRegistry;
import com.sun.net.httpserver.*;
import org.slf4j.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class FileHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(FileHandler.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{1,100}$");
    private static final Pattern CACHE_ID_PATTERN = Pattern.compile("^[a-f0-9]{1,64}$");

    private final ConversionCache cache;
    private final DocViewerConfig config;
    private final FileTypeDetector detector;
    private final FileKeyRegistry registry;

    public FileHandler(ConversionCache cache, DocViewerConfig config,
                       FileTypeDetector detector, FileKeyRegistry registry) {
        this.cache = cache;
        this.config = config;
        this.detector = detector;
        this.registry = registry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
        try {
            if (params.containsKey("id")) {
                serveById(exchange, params.get("id"));
            } else if (params.containsKey("key")) {
                serveByKey(exchange, params.get("key"));
            } else {
                exchange.sendResponseHeaders(400, -1);
            }
        } catch (Exception e) {
            log.error("FileHandler error", e);
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    private void serveById(HttpExchange exchange, String cacheId) throws IOException {
        if (!CACHE_ID_PATTERN.matcher(cacheId).matches()) {
            exchange.sendResponseHeaders(400, -1); return;
        }
        Path path = cache.cachedPath(cacheId);
        if (!Files.exists(path)) { exchange.sendResponseHeaders(404, -1); return; }
        serveFile(exchange, path, "application/pdf", cacheId + ".pdf");
    }

    private void serveByKey(HttpExchange exchange, String key) throws Exception {
        if (!KEY_PATTERN.matcher(key).matches()) {
            exchange.sendResponseHeaders(400, -1); return;
        }
        FileKeyRegistry.FileKeyEntry entry = registry.findByKey(key);
        if (entry == null) { exchange.sendResponseHeaders(404, -1); return; }

        Path resolved = Paths.get(entry.filePath).normalize().toAbsolutePath();
        if (!config.allowedPaths.isEmpty()) {
            boolean allowed = config.allowedPaths.stream()
                .anyMatch(ap -> resolved.startsWith(Paths.get(ap).toAbsolutePath()));
            if (!allowed) { exchange.sendResponseHeaders(403, -1); return; }
        }
        if (!Files.exists(resolved)) { exchange.sendResponseHeaders(404, -1); return; }

        String filename = entry.originalName != null ? entry.originalName
            : resolved.getFileName().toString();
        serveFile(exchange, resolved, detector.mimeType(filename), filename);
    }

    private void serveFile(HttpExchange exchange, Path path, String contentType, String filename) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Disposition",
            "inline; filename=\"" + filename.replace("\"", "") + "\"");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private Map<String, String> queryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
mvn test -Dtest=FileHandlerTest -q
```
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docviewer/handler/FileHandler.java \
        src/test/java/com/docviewer/handler/FileHandlerTest.java
git commit -m "feat: FileHandler v2 - key-based original file serving, remove ?path= parameter"
```

---

## Task 10: DocViewerServer v2

**Files:**
- Modify: `src/main/java/com/docviewer/DocViewerServer.java`

- [ ] **Step 1: DocViewerServer v2 구현**

`src/main/java/com/docviewer/DocViewerServer.java` 전체 교체:
```java
package com.docviewer;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.LibreOfficeConverter;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.handler.*;
import com.docviewer.registry.FileKeyRegistry;
import com.docviewer.security.*;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.*;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.Executors;

public class DocViewerServer {
    private static final Logger log = LoggerFactory.getLogger(DocViewerServer.class);

    public static void main(String[] args) throws Exception {
        DocViewerConfig config = DocViewerConfig.fromArgs(args);

        if (config.libreOfficePath == null) {
            log.error("--libreoffice=<path> is required. e.g. --libreoffice=/usr/lib/libreoffice");
            System.exit(1);
        }

        Path resultDir = Paths.get(config.resultDir);
        Files.createDirectories(resultDir);

        ConversionCache cache = new ConversionCache(resultDir, config.cacheTtlSeconds);
        cache.cleanup();

        FileKeyRegistry registry = new FileKeyRegistry(resultDir.resolve("docviewer.db"));
        FileTypeDetector detector = new FileTypeDetector(config.allowedExtensions);
        LibreOfficeConverter converter = new LibreOfficeConverter(config);
        LicenseChecker license = new LicenseChecker(config.licenseAllowedIps, config.licenseAllowedDomains);
        IpWhitelistFilter apiFilter = new IpWhitelistFilter(config.apiAllowedIps);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down doc-viewer...");
            converter.shutdown();
            registry.close();
        }));

        HttpServer server = HttpServer.create(new InetSocketAddress(config.port), 100);
        server.createContext("/docviewer/view",
            new ViewHandler(config, converter, cache, detector, registry, license));
        server.createContext("/docviewer/file",
            new FileHandler(cache, config, detector, registry));
        server.createContext("/docviewer/static",
            new StaticHandler());
        server.createContext("/docviewer/api",
            new ApiHandler(config, converter, cache, detector, registry, apiFilter));
        server.createContext("/docviewer/health", exchange -> {
            String body = String.format(
                "{\"status\":\"ok\",\"libreoffice\":%b,\"port\":%d}",
                converter.isAlive(), config.port);
            byte[] bytes = body.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        if (config.allowedPaths.isEmpty()) {
            log.warn("SECURITY WARNING: --allowed-paths not set. ALL filesystem paths are accessible!");
        }
        log.info("doc-viewer v2 started on http://localhost:{}/docviewer", config.port);
        log.info("Result dir: {}", resultDir.toAbsolutePath());
    }
}
```

- [ ] **Step 2: 전체 테스트 통과 확인**

```bash
mvn test -q
```
Expected: `BUILD SUCCESS`, 모든 테스트 통과

- [ ] **Step 3: fat-jar 빌드 확인**

```bash
mvn package -DskipTests -q
ls -lh target/doc-viewer-1.0.0.jar
```
Expected: JAR 파일 존재 (15MB 이상, sqlite-jdbc 포함)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/docviewer/DocViewerServer.java
git commit -m "feat: DocViewerServer v2 - assemble all v2 components (registry, security, license)"
```

---

## Task 11: Frontend v2

**Files:**
- Modify: `src/main/resources/static/viewer.html`
- Modify: `src/main/resources/static/viewer.css`
- Modify: `src/main/resources/static/viewer.js`
- Modify: `src/main/resources/static/doc-viewer-client.js`

- [ ] **Step 1: viewer.css v2 교체** (에러/로딩 스타일 추가)

`src/main/resources/static/viewer.css`:
```css
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: sans-serif; background: #f0f0f0; height: 100vh; display: flex; flex-direction: column; overflow: hidden; }

#toolbar {
    display: flex; align-items: center; padding: 8px 16px;
    background: #333; color: #fff; gap: 12px; flex-shrink: 0; min-height: 44px;
}
#filename { font-size: 14px; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
#pdf-controls { display: flex; align-items: center; gap: 6px; font-size: 13px; }
#pdf-controls button, #toolbar-actions button {
    background: #555; color: #fff; border: none;
    padding: 4px 10px; cursor: pointer; border-radius: 3px; font-size: 13px;
}
#pdf-controls button:hover, #toolbar-actions button:hover { background: #777; }
#toolbar-actions { display: flex; gap: 8px; }
#download-link {
    background: #555; color: #fff; padding: 4px 10px;
    text-decoration: none; border-radius: 3px; font-size: 13px;
}
#download-link:hover { background: #777; }

#viewer-container {
    flex: 1; overflow: auto; display: flex;
    justify-content: center; align-items: flex-start; padding: 20px;
}

/* Loading */
#loading {
    margin: auto; text-align: center; color: #666;
}
.spinner {
    display: inline-block; width: 40px; height: 40px;
    border: 4px solid #ddd; border-top-color: #555;
    border-radius: 50%; animation: spin 0.8s linear infinite;
    margin-bottom: 12px;
}
@keyframes spin { to { transform: rotate(360deg); } }
#loading-text { font-size: 15px; margin-top: 8px; }

/* Error */
#error {
    display: none; margin: auto; text-align: center;
    color: #c00; font-size: 15px; padding: 32px; max-width: 480px;
}
#error .error-icon { font-size: 40px; margin-bottom: 12px; }
#error .error-message { margin-bottom: 16px; line-height: 1.6; }
#error .error-download a {
    display: inline-block; background: #555; color: #fff;
    padding: 8px 16px; text-decoration: none; border-radius: 3px; font-size: 13px;
}

#pdf-canvas { display: block; box-shadow: 0 2px 8px rgba(0,0,0,0.3); background: #fff; }
#text-content {
    white-space: pre-wrap; word-break: break-all; background: #fff;
    padding: 32px; max-width: 900px; width: 100%;
    box-shadow: 0 2px 8px rgba(0,0,0,0.15);
    font-family: 'Courier New', monospace; font-size: 13px; line-height: 1.6;
}
#image-content { max-width: 100%; max-height: 100%; object-fit: contain; }
.hidden { display: none !important; }
```

- [ ] **Step 2: viewer.html v2 교체**

`src/main/resources/static/viewer.html`:
```html
<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>문서 뷰어</title>
  <link rel="stylesheet" href="/docviewer/static/viewer.css">
  <script>window.DOCVIEWER_CONFIG = __DOCVIEWER_CONFIG__;</script>
  <script src="/docviewer/static/lib/pdf.min.js"></script>
</head>
<body>
  <div id="toolbar">
    <span id="filename"></span>
    <div id="pdf-controls" class="hidden">
      <button id="prev-page">&#8249;</button>
      <span id="page-info">1 / 1</span>
      <button id="next-page">&#8250;</button>
      <button id="zoom-out">&#8722;</button>
      <span id="zoom-level">100%</span>
      <button id="zoom-in">+</button>
    </div>
    <div id="toolbar-actions">
      <a id="download-link" download>다운로드</a>
      <button id="print-btn">인쇄</button>
    </div>
  </div>
  <div id="viewer-container">
    <div id="loading">
      <div class="spinner"></div>
      <div id="loading-text">문서를 불러오는 중입니다...</div>
    </div>
    <div id="error">
      <div class="error-icon">✕</div>
      <div class="error-message" id="error-message"></div>
      <div class="error-download" id="error-download"></div>
    </div>
    <canvas id="pdf-canvas" class="hidden"></canvas>
    <pre id="text-content" class="hidden"></pre>
    <img id="image-content" class="hidden" alt="문서 이미지">
  </div>
  <script src="/docviewer/static/viewer.js"></script>
</body>
</html>
```

- [ ] **Step 3: viewer.js v2 교체** (에러 처리 강화, downloadKey 지원)

`src/main/resources/static/viewer.js`:
```javascript
(function () {
  'use strict';

  var cfg = window.DOCVIEWER_CONFIG;
  var $ = function (id) { return document.getElementById(id); };

  $('filename').textContent = cfg.filename || '문서 뷰어';
  $('download-link').href = cfg.fileUrl;
  $('download-link').setAttribute('download', cfg.filename || 'document');
  $('print-btn').addEventListener('click', function () { window.print(); });

  function showError(msg, showDownload) {
    $('loading').classList.add('hidden');
    $('error-message').textContent = msg;
    if (showDownload && cfg.downloadKey) {
      $('error-download').innerHTML =
        '<a href="/docviewer/file?key=' + encodeURIComponent(cfg.downloadKey) + '">원본 파일 다운로드</a>';
    }
    $('error').style.display = 'block';
  }

  function showLoading(text) {
    $('loading-text').textContent = text || '문서를 불러오는 중입니다...';
    $('loading').classList.remove('hidden');
  }

  // IMAGE
  if (cfg.renderType === 'image') {
    $('loading').classList.add('hidden');
    var img = $('image-content');
    img.onerror = function () { showError('이미지를 불러올 수 없습니다.', true); };
    img.src = cfg.fileUrl;
    img.classList.remove('hidden');
    return;
  }

  // TEXT
  if (cfg.renderType === 'text') {
    showLoading('텍스트 파일을 불러오는 중...');
    fetch(cfg.fileUrl)
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.arrayBuffer();
      })
      .then(function (buf) {
        var text;
        try { text = new TextDecoder('euc-kr').decode(buf); }
        catch (e) { text = new TextDecoder('utf-8').decode(buf); }
        $('loading').classList.add('hidden');
        $('text-content').textContent = text;
        $('text-content').classList.remove('hidden');
      })
      .catch(function (e) { showError('파일을 불러올 수 없습니다: ' + e.message, true); });
    return;
  }

  // PDF
  var pdfjsLib = window['pdfjs-dist/build/pdf'];
  if (!pdfjsLib) { showError('PDF 뷰어를 초기화할 수 없습니다. 페이지를 새로고침하세요.', false); return; }
  pdfjsLib.GlobalWorkerOptions.workerSrc = '/docviewer/static/lib/pdf.worker.min.js';

  var pdfDoc = null;
  var currentPage = 1;
  var scale = 1.0;
  var canvas = $('pdf-canvas');
  var ctx = canvas.getContext('2d');
  var rendering = false;

  showLoading('문서를 변환하는 중입니다...');

  function containerWidth() { return $('viewer-container').clientWidth - 40; }

  function renderPage(num) {
    if (rendering) return Promise.resolve();
    rendering = true;
    return pdfDoc.getPage(num).then(function (page) {
      var base = page.getViewport({ scale: 1 });
      var autoScale = Math.min(containerWidth() / base.width, 2.0);
      var vp = page.getViewport({ scale: autoScale * scale });
      canvas.width = vp.width;
      canvas.height = vp.height;
      return page.render({ canvasContext: ctx, viewport: vp }).promise;
    }).then(function () {
      $('page-info').textContent = currentPage + ' / ' + pdfDoc.numPages;
      rendering = false;
    }).catch(function (e) {
      rendering = false;
      showError('페이지를 렌더링할 수 없습니다: ' + e.message, true);
    });
  }

  pdfjsLib.getDocument(cfg.fileUrl).promise.then(function (doc) {
    pdfDoc = doc;
    $('loading').classList.add('hidden');
    canvas.classList.remove('hidden');
    $('pdf-controls').classList.remove('hidden');
    return renderPage(1);
  }).catch(function (e) {
    showError('PDF를 열 수 없습니다: ' + e.message, true);
  });

  $('prev-page').addEventListener('click', function () {
    if (currentPage <= 1) return;
    currentPage--; renderPage(currentPage);
  });
  $('next-page').addEventListener('click', function () {
    if (!pdfDoc || currentPage >= pdfDoc.numPages) return;
    currentPage++; renderPage(currentPage);
  });
  $('zoom-in').addEventListener('click', function () {
    scale = Math.min(scale + 0.25, 3.0);
    $('zoom-level').textContent = Math.round(scale * 100) + '%';
    renderPage(currentPage);
  });
  $('zoom-out').addEventListener('click', function () {
    scale = Math.max(scale - 0.25, 0.5);
    $('zoom-level').textContent = Math.round(scale * 100) + '%';
    renderPage(currentPage);
  });
  window.addEventListener('resize', function () { if (pdfDoc) renderPage(currentPage); });
})();
```

- [ ] **Step 4: doc-viewer-client.js v2 교체**

`src/main/resources/static/doc-viewer-client.js`:
```javascript
(function () {
  var base = (function () {
    var scripts = document.getElementsByTagName('script');
    for (var i = 0; i < scripts.length; i++) {
      var m = (scripts[i].src || '').match(
        /^(https?:\/\/[^\/]+)\/docviewer\/static\/doc-viewer-client\.js/);
      if (m) return m[1];
    }
    return '';
  })();

  window.DocViewer = {
    // key: CMS의 ATCH_FILE_ID + "_" + FILE_SN (예: "FILE_000000000080Gi9_0")
    open: function (key, options) {
      var url = base + '/docviewer/view?key=' + encodeURIComponent(key);
      window.open(url, (options && options.target) || '_blank');
    }
  };
})();
```

- [ ] **Step 5: 전체 빌드 + JAR 리소스 확인**

```bash
mvn package -DskipTests -q
jar tf target/doc-viewer-1.0.0.jar | grep "static/"
```
Expected: `static/viewer.html`, `static/viewer.js`, `static/viewer.css`, `static/doc-viewer-client.js`, `static/lib/pdf.min.js` 등 목록 출력

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/
git commit -m "feat: frontend v2 - loading spinner, error UI, key-based doc-viewer-client.js"
```

---

## Task 12: 전체 테스트 통과 + 최종 빌드 검증

- [ ] **Step 1: 전체 테스트 실행**

```bash
mvn test
```
Expected: `BUILD SUCCESS`, 모든 테스트 통과, 0 failures

실패 시: 오류 메시지를 확인하여 해당 태스크로 돌아가 수정 후 재실행.

- [ ] **Step 2: fat-jar 패키징**

```bash
mvn package -DskipTests
ls -lh target/doc-viewer-1.0.0.jar
```
Expected: JAR 존재, 크기 15MB 이상

- [ ] **Step 3: JAR 내 SQLite 드라이버 포함 확인**

```bash
jar tf target/doc-viewer-1.0.0.jar | grep -i sqlite | head -5
```
Expected: `org/sqlite/` 또는 `native/` 하위 파일 목록 출력

- [ ] **Step 4: 헬스 엔드포인트 연기 기동 테스트** (LibreOffice 없는 환경 — 기동 실패가 예상됨)

```bash
java -jar target/doc-viewer-1.0.0.jar \
  --libreoffice=/nonexistent 2>&1 | head -5
```
Expected: LibreOffice 경로 오류 로그 출력 (정상 — LibreOffice 없는 환경)

- [ ] **Step 5: 최종 커밋**

```bash
git add -u
git commit -m "feat: doc-viewer v2 complete - key registry, IP whitelist, license, security hardening"
```

---

## CMS 연동 참고 (구현 범위 외)

doc-viewer v2와 연동하는 CMS 서버사이드 코드 예시 (Spring MVC 기준):

**1. comtnfiledetail 마이그레이션 SQL:**
```sql
ALTER TABLE comtnfiledetail
  ADD COLUMN DOCVIEWER_CONVERT_YN   CHAR(1)      DEFAULT 'N',
  ADD COLUMN DOCVIEWER_CONVERT_DT   DATETIME     NULL,
  ADD COLUMN DOCVIEWER_FILE_HASH    VARCHAR(64)  NULL;
```

**2. "문서보기" 버튼 핸들러 (Java):**
```java
String key = atchFileId + "_" + fileSn;
String fullPath = fileStreCours + streFileNm;

if (!"Y".equals(docviewerConvertYn)) {
    // doc-viewer 동기 변환 요청
    String json = String.format(
        "{\"key\":\"%s\",\"path\":\"%s\",\"originalName\":\"%s\",\"fileHash\":\"\"}",
        key, fullPath.replace("\\", "\\\\"), orignlFileNm);
    HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:8090/docviewer/api/convert"))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .timeout(Duration.ofSeconds(120)).build(),
        HttpResponse.BodyHandlers.ofString());
    // DB 업데이트
    fileDao.updateDocviewerConvertYn(atchFileId, fileSn, "Y");
}
return "redirect:http://localhost:8090/docviewer/view?key=" + key;
```

**3. JSP 통합 (doc-viewer-client.js):**
```html
<script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
```
```javascript
// key = ATCH_FILE_ID + "_" + FILE_SN
DocViewer.open('FILE_000000000080Gi9_0');
```
