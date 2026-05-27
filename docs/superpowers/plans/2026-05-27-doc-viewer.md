# doc-viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브라우저 새 탭에서 첨부파일을 클릭 한 번에 미리보는 독립 실행형 문서 뷰어 서버 (fat-jar)를 구축한다.

**Architecture:** JDK 11 내장 HttpServer + JodConverter(LibreOffice daemon) 조합으로 Word/HWP/스프레드시트 등을 PDF로 변환한 뒤 pdf.js로 렌더링. PDF/TXT/이미지는 프론트에서 직접 처리. 변환 결과는 SHA-256 기반 캐시로 재사용. `java -jar doc-viewer.jar` 한 줄로 기동, JSP 프로젝트에는 script 1줄 + 함수 호출 1줄로 통합.

**Tech Stack:** JDK 11, JodConverter 4.4.2, SLF4J + Logback, Maven shade plugin (fat-jar), Vanilla JS, Mozilla pdf.js 4.2.67 (legacy build)

---

## 파일 구조 (전체)

```
doc-viewer/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/docviewer/
│   │   │   ├── DocViewerServer.java
│   │   │   ├── config/DocViewerConfig.java
│   │   │   ├── detector/FileTypeDetector.java
│   │   │   ├── cache/ConversionCache.java
│   │   │   ├── converter/
│   │   │   │   ├── DocumentConverter.java      (interface)
│   │   │   │   └── LibreOfficeConverter.java
│   │   │   └── handler/
│   │   │       ├── ViewHandler.java
│   │   │       ├── FileHandler.java
│   │   │       └── StaticHandler.java
│   │   └── resources/
│   │       ├── logback.xml
│   │       └── static/
│   │           ├── viewer.html                  (템플릿: __DOCVIEWER_CONFIG__ 치환)
│   │           ├── viewer.js
│   │           ├── viewer.css
│   │           ├── doc-viewer-client.js
│   │           └── lib/
│   │               ├── pdf.min.js               (pdf.js legacy build)
│   │               └── pdf.worker.min.js
│   └── test/
│       └── java/com/docviewer/
│           ├── config/DocViewerConfigTest.java
│           ├── detector/FileTypeDetectorTest.java
│           ├── cache/ConversionCacheTest.java
│           └── handler/
│               ├── StaticHandlerTest.java
│               ├── FileHandlerTest.java
│               └── ViewHandlerTest.java
└── docs/
```

---

## Task 1: Maven 프로젝트 세팅 (pom.xml + 디렉토리 구조)

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/docviewer/.gitkeep`
- Create: `src/main/resources/logback.xml`

- [ ] **Step 1: 디렉토리 구조 생성**

```bash
mkdir -p src/main/java/com/docviewer/{config,detector,cache,converter,handler}
mkdir -p src/main/resources/static/lib
mkdir -p src/test/java/com/docviewer/{config,detector,cache,handler}
```

- [ ] **Step 2: pom.xml 작성**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.docviewer</groupId>
    <artifactId>doc-viewer</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.jodconverter</groupId>
            <artifactId>jodconverter-local</artifactId>
            <version>4.4.2</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>1.7.36</version>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>1.2.12</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.9.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>5.3.1</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.0.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.4.1</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>com.docviewer.DocViewerServer</mainClass>
                                </transformer>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: logback.xml 작성**

`src/main/resources/logback.xml`:
```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

- [ ] **Step 4: 빌드 확인**

```bash
mvn compile
```
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git init
git add pom.xml src/main/resources/logback.xml
git commit -m "feat: initialize maven project with JodConverter and fat-jar setup"
```

---

## Task 2: pdf.js 정적 에셋 다운로드

**Files:**
- Create: `src/main/resources/static/lib/pdf.min.js`
- Create: `src/main/resources/static/lib/pdf.worker.min.js`

pdf.js 4.2.67 legacy build을 다운로드하여 JAR에 번들한다.

- [ ] **Step 1: 다운로드 및 압축 해제**

```bash
cd /tmp
curl -L https://github.com/mozilla/pdf.js/releases/download/v4.2.67/pdfjs-4.2.67-legacy-dist.zip -o pdfjs.zip
unzip -o pdfjs.zip -d pdfjs
```

- [ ] **Step 2: 필요한 파일만 복사**

```bash
cp /tmp/pdfjs/build/pdf.min.js     src/main/resources/static/lib/pdf.min.js
cp /tmp/pdfjs/build/pdf.worker.min.js src/main/resources/static/lib/pdf.worker.min.js
rm -rf /tmp/pdfjs /tmp/pdfjs.zip
```

- [ ] **Step 3: 파일 존재 확인**

```bash
ls -lh src/main/resources/static/lib/
```
Expected: `pdf.min.js`와 `pdf.worker.min.js` 모두 0바이트 이상

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/lib/
git commit -m "feat: bundle pdf.js 4.2.67 legacy build as static resource"
```

---

## Task 3: DocViewerConfig

**Files:**
- Create: `src/main/java/com/docviewer/config/DocViewerConfig.java`
- Create: `src/test/java/com/docviewer/config/DocViewerConfigTest.java`

CLI args(`--key=value`)를 파싱해 설정 객체를 만든다.

- [ ] **Step 1: 테스트 작성**

`src/test/java/com/docviewer/config/DocViewerConfigTest.java`:
```java
package com.docviewer.config;

import org.junit.jupiter.api.Test;
import java.util.List;
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
            "--allowed-paths=/upload,/files"
        });
        assertEquals(9000, cfg.port);
        assertEquals("/usr/lib/libreoffice", cfg.libreOfficePath);
        assertEquals(2003, cfg.loPort);
        assertEquals(2, cfg.loPoolSize);
        assertEquals(3600L, cfg.cacheTtlSeconds);
        assertEquals(60, cfg.convertTimeoutSeconds);
        assertEquals(List.of("/upload", "/files"), cfg.allowedPaths);
    }

    @Test
    void ignoresUnknownArgs() {
        assertDoesNotThrow(() -> DocViewerConfig.fromArgs(new String[]{"--unknown=value"}));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
mvn test -pl . -Dtest=DocViewerConfigTest
```
Expected: `FAIL` (클래스 없음)

- [ ] **Step 3: DocViewerConfig 구현**

`src/main/java/com/docviewer/config/DocViewerConfig.java`:
```java
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
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
mvn test -Dtest=DocViewerConfigTest
```
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "feat: add DocViewerConfig with CLI args parsing"
```

---

## Task 4: FileTypeDetector

**Files:**
- Create: `src/main/java/com/docviewer/detector/FileTypeDetector.java`
- Create: `src/test/java/com/docviewer/detector/FileTypeDetectorTest.java`

파일 확장자로 렌더링 방식(`PDF / TEXT / IMAGE / LIBREOFFICE`)을 결정한다.

- [ ] **Step 1: 테스트 작성**

`src/test/java/com/docviewer/detector/FileTypeDetectorTest.java`:
```java
package com.docviewer.detector;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.docviewer.detector.FileTypeDetector.RenderType.*;

class FileTypeDetectorTest {
    private final FileTypeDetector detector = new FileTypeDetector();

    @Test
    void detectsPdf() {
        assertEquals(PDF, detector.detect("report.pdf"));
        assertEquals(PDF, detector.detect("REPORT.PDF"));
    }

    @Test
    void detectsImages() {
        assertEquals(IMAGE, detector.detect("photo.jpg"));
        assertEquals(IMAGE, detector.detect("image.PNG"));
        assertEquals(IMAGE, detector.detect("anim.gif"));
        assertEquals(IMAGE, detector.detect("modern.webp"));
    }

    @Test
    void detectsText() {
        assertEquals(TEXT, detector.detect("readme.txt"));
        assertEquals(TEXT, detector.detect("data.csv"));
    }

    @Test
    void detectsLibreOfficeFormats() {
        assertEquals(LIBREOFFICE, detector.detect("문서.hwp"));
        assertEquals(LIBREOFFICE, detector.detect("doc.docx"));
        assertEquals(LIBREOFFICE, detector.detect("sheet.xlsx"));
        assertEquals(LIBREOFFICE, detector.detect("pres.pptx"));
        assertEquals(LIBREOFFICE, detector.detect("old.doc"));
        assertEquals(LIBREOFFICE, detector.detect("data.hwpx"));
    }

    @Test
    void isSupportedReturnsFalseForUnknown() {
        assertFalse(detector.isSupported("archive.zip"));
        assertFalse(detector.isSupported("binary.exe"));
    }

    @Test
    void isSupportedReturnsTrueForKnown() {
        assertTrue(detector.isSupported("report.pdf"));
        assertTrue(detector.isSupported("doc.hwp"));
        assertTrue(detector.isSupported("image.png"));
    }

    @Test
    void mimeTypeForPdf() {
        assertEquals("application/pdf", detector.mimeType("report.pdf"));
    }

    @Test
    void mimeTypeForJpg() {
        assertEquals("image/jpeg", detector.mimeType("photo.jpg"));
    }

    @Test
    void mimeTypeForUnknownFallback() {
        assertEquals("application/octet-stream", detector.mimeType("file.xyz"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
mvn test -Dtest=FileTypeDetectorTest
```
Expected: `FAIL`

- [ ] **Step 3: FileTypeDetector 구현**

`src/main/java/com/docviewer/detector/FileTypeDetector.java`:
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
    private static final Set<String> LO_EXTS = Set.of(
        "doc", "docx", "hwp", "hwpx", "xls", "xlsx", "ods",
        "ppt", "pptx", "odp", "odt", "rtf"
    );
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

    public RenderType detect(String filename) {
        String ext = ext(filename);
        if (PDF_EXTS.contains(ext))  return RenderType.PDF;
        if (TEXT_EXTS.contains(ext)) return RenderType.TEXT;
        if (IMAGE_EXTS.contains(ext)) return RenderType.IMAGE;
        return RenderType.LIBREOFFICE;
    }

    public String mimeType(String filename) {
        return MIME_MAP.getOrDefault(ext(filename), "application/octet-stream");
    }

    public boolean isSupported(String filename) {
        String ext = ext(filename);
        return PDF_EXTS.contains(ext) || TEXT_EXTS.contains(ext)
            || IMAGE_EXTS.contains(ext) || LO_EXTS.contains(ext);
    }

    private String ext(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
mvn test -Dtest=FileTypeDetectorTest
```
Expected: `Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "feat: add FileTypeDetector for routing files to PDF/TEXT/IMAGE/LibreOffice renderer"
```

---

## Task 5: ConversionCache

**Files:**
- Create: `src/main/java/com/docviewer/cache/ConversionCache.java`
- Create: `src/test/java/com/docviewer/cache/ConversionCacheTest.java`

파일경로 + lastModified 기반 SHA-256 키로 변환된 PDF를 디스크에 캐싱. 중복 변환 방지.

- [ ] **Step 1: 테스트 작성**

`src/test/java/com/docviewer/cache/ConversionCacheTest.java`:
```java
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
        // simulate file change by touching lastModified
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
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
mvn test -Dtest=ConversionCacheTest
```
Expected: `FAIL`

- [ ] **Step 3: ConversionCache 구현**

`src/main/java/com/docviewer/cache/ConversionCache.java`:
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

    public ConversionCache(long ttlSeconds) throws IOException {
        this(Paths.get(System.getProperty("java.io.tmpdir"), "docviewer-cache"), ttlSeconds);
    }

    ConversionCache(Path cacheDir, long ttlSeconds) throws IOException {
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
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
mvn test -Dtest=ConversionCacheTest
```
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "feat: add ConversionCache with SHA-256 key, TTL cleanup, in-flight deduplication"
```

---

## Task 6: DocumentConverter 인터페이스 + LibreOfficeConverter

**Files:**
- Create: `src/main/java/com/docviewer/converter/DocumentConverter.java`
- Create: `src/main/java/com/docviewer/converter/LibreOfficeConverter.java`

LibreOfficeConverter는 JodConverter로 LibreOffice 데몬을 관리한다. **실제 LibreOffice 없이는 단위 테스트 불가 → 인터페이스 정의 후 통합 테스트에서 검증.**

- [ ] **Step 1: DocumentConverter 인터페이스 작성**

`src/main/java/com/docviewer/converter/DocumentConverter.java`:
```java
package com.docviewer.converter;

import java.io.File;

public interface DocumentConverter {
    void convert(File source, File dest) throws Exception;
    boolean isAlive();
    void shutdown();
}
```

- [ ] **Step 2: LibreOfficeConverter 구현**

`src/main/java/com/docviewer/converter/LibreOfficeConverter.java`:
```java
package com.docviewer.converter;

import com.docviewer.config.DocViewerConfig;
import org.jodconverter.core.office.OfficeException;
import org.jodconverter.core.office.OfficeManager;
import org.jodconverter.local.LocalConverter;
import org.jodconverter.local.office.LocalOfficeManager;
import org.slf4j.*;
import java.io.File;

public class LibreOfficeConverter implements DocumentConverter {
    private static final Logger log = LoggerFactory.getLogger(LibreOfficeConverter.class);

    private final OfficeManager officeManager;
    private final org.jodconverter.core.DocumentConverter converter;

    public LibreOfficeConverter(DocViewerConfig config) throws OfficeException {
        this.officeManager = LocalOfficeManager.builder()
            .officeHome(config.libreOfficePath)
            .portNumbers(config.loPort)
            .maxTasksPerProcess(50)
            .build();
        this.officeManager.start();
        this.converter = LocalConverter.builder()
            .officeManager(officeManager)
            .build();
        log.info("LibreOffice daemon started (port {})", config.loPort);
    }

    @Override
    public void convert(File source, File dest) throws Exception {
        converter.convert(source).to(dest).execute();
        log.info("Converted {} -> {}", source.getName(), dest.getName());
    }

    @Override
    public boolean isAlive() {
        return officeManager.isRunning();
    }

    @Override
    public void shutdown() {
        try {
            officeManager.stop();
            log.info("LibreOffice daemon stopped");
        } catch (OfficeException e) {
            log.warn("Error stopping LibreOffice daemon", e);
        }
    }
}
```

- [ ] **Step 3: 컴파일 확인**

```bash
mvn compile
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/
git commit -m "feat: add DocumentConverter interface and LibreOfficeConverter via JodConverter"
```

---

## Task 7: StaticHandler

**Files:**
- Create: `src/main/java/com/docviewer/handler/StaticHandler.java`
- Create: `src/test/java/com/docviewer/handler/StaticHandlerTest.java`

`/docviewer/static/**` 경로의 요청을 클래스패스 `/static/**` 리소스로 서빙한다.

- [ ] **Step 1: 더미 정적 파일 생성 (테스트용)**

```bash
echo "/* test */" > src/main/resources/static/viewer.css
echo "// test" > src/main/resources/static/viewer.js
```

- [ ] **Step 2: 테스트 작성**

`src/test/java/com/docviewer/handler/StaticHandlerTest.java`:
```java
package com.docviewer.handler;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class StaticHandlerTest {
    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docviewer/static", new StaticHandler());
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void servesViewerCss() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/static/viewer.css")).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
        assertEquals("text/css", resp.headers().firstValue("content-type").orElse(""));
    }

    @Test
    void returns404ForMissingResource() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/static/nonexistent.js")).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(404, resp.statusCode());
    }

    @Test
    void servesJsWithCorrectContentType() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/docviewer/static/viewer.js")).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("content-type").orElse("").contains("javascript"));
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
mvn test -Dtest=StaticHandlerTest
```
Expected: `FAIL`

- [ ] **Step 4: StaticHandler 구현**

`src/main/java/com/docviewer/handler/StaticHandler.java`:
```java
package com.docviewer.handler;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URI;

public class StaticHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String uriPath = exchange.getRequestURI().getPath();
        String resourcePath = "/static" + uriPath.replaceFirst("^/docviewer/static", "");

        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(resourcePath));
            exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private String contentType(String path) {
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
mvn test -Dtest=StaticHandlerTest
```
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "feat: add StaticHandler for serving classpath static resources"
```

---

## Task 8: FileHandler

**Files:**
- Create: `src/main/java/com/docviewer/handler/FileHandler.java`
- Create: `src/test/java/com/docviewer/handler/FileHandlerTest.java`

`/docviewer/file?id={cacheId}` 또는 `?path={encodedPath}` 요청에 파일 바이트를 스트리밍한다.

- [ ] **Step 1: 테스트 작성**

`src/test/java/com/docviewer/handler/FileHandlerTest.java`:
```java
package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.detector.FileTypeDetector;
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

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("fh-test-");
        cache = new ConversionCache(tempDir, 86400L);

        DocViewerConfig config = DocViewerConfig.fromArgs(new String[0]);
        FileTypeDetector detector = new FileTypeDetector();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docviewer/file", new FileHandler(cache, config, detector));
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder())
            .map(Path::toFile).forEach(File::delete);
    }

    @Test
    void servesCachedPdfById() throws Exception {
        Path pdfFile = tempDir.resolve("abc123.pdf");
        Files.write(pdfFile, "%PDF-1.4 test".getBytes());
        // manually write to cache dir location
        // use cache.cachedPath to find where to put it
        String cacheId = "testid00testid00";
        Files.write(cache.cachedPath(cacheId), "%PDF-1.4 content".getBytes());

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<byte[]> resp = client.send(
            HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/docviewer/file?id=" + cacheId)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
        assertEquals(200, resp.statusCode());
        assertEquals("application/pdf", resp.headers().firstValue("content-type").orElse(""));
    }

    @Test
    void returns404ForMissingCacheId() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/docviewer/file?id=nonexistent")).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(404, resp.statusCode());
    }

    @Test
    void servesOriginalFileByPath() throws Exception {
        File txtFile = File.createTempFile("test", ".txt", tempDir.toFile());
        Files.write(txtFile.toPath(), "hello world".getBytes());

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/docviewer/file?path="
                + URLEncoder.encode(txtFile.getAbsolutePath(), "UTF-8"))).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
        assertEquals("hello world", resp.body());
    }

    @Test
    void returns400WithNoParams() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/docviewer/file")).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(400, resp.statusCode());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
mvn test -Dtest=FileHandlerTest
```
Expected: `FAIL`

- [ ] **Step 3: FileHandler 구현**

`src/main/java/com/docviewer/handler/FileHandler.java`:
```java
package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.detector.FileTypeDetector;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class FileHandler implements HttpHandler {
    private final ConversionCache cache;
    private final DocViewerConfig config;
    private final FileTypeDetector detector;

    public FileHandler(ConversionCache cache, DocViewerConfig config, FileTypeDetector detector) {
        this.cache = cache;
        this.config = config;
        this.detector = detector;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
        try {
            if (params.containsKey("id")) {
                serveById(exchange, params.get("id"));
            } else if (params.containsKey("path")) {
                serveByPath(exchange, URLDecoder.decode(params.get("path"), "UTF-8"));
            } else {
                exchange.sendResponseHeaders(400, -1);
            }
        } finally {
            exchange.close();
        }
    }

    private void serveById(HttpExchange exchange, String cacheId) throws IOException {
        Path path = cache.cachedPath(cacheId);
        if (!Files.exists(path)) { exchange.sendResponseHeaders(404, -1); return; }
        serveFile(exchange, path, "application/pdf", cacheId + ".pdf");
    }

    private void serveByPath(HttpExchange exchange, String rawPath) throws IOException {
        Path resolved;
        try {
            resolved = validatePath(rawPath);
        } catch (SecurityException e) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }
        if (!Files.exists(resolved)) { exchange.sendResponseHeaders(404, -1); return; }
        String filename = resolved.getFileName().toString();
        serveFile(exchange, resolved, detector.mimeType(filename), filename);
    }

    private void serveFile(HttpExchange exchange, Path path, String contentType, String filename) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + filename + "\"");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Path validatePath(String rawPath) {
        Path resolved = Paths.get(rawPath).normalize().toAbsolutePath();
        if (config.allowedPaths.isEmpty()) return resolved;
        for (String allowed : config.allowedPaths) {
            if (resolved.startsWith(Paths.get(allowed).toAbsolutePath())) return resolved;
        }
        throw new SecurityException("Path not in allowed list: " + resolved);
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
mvn test -Dtest=FileHandlerTest
```
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "feat: add FileHandler for serving cached PDFs and original files"
```

---

## Task 9: ViewHandler

**Files:**
- Create: `src/main/java/com/docviewer/handler/ViewHandler.java`
- Create: `src/test/java/com/docviewer/handler/ViewHandlerTest.java`
- Create: `src/main/resources/static/viewer.html` (플레이스홀더 버전 — Task 11에서 완성)

`/docviewer/view?path=...` 요청을 받아 파일 타입 판별 → 필요시 변환 → `__DOCVIEWER_CONFIG__`가 치환된 viewer.html 반환.

- [ ] **Step 1: 플레이스홀더 viewer.html 작성**

`src/main/resources/static/viewer.html`:
```html
<!DOCTYPE html>
<html><head><meta charset="UTF-8"><title>뷰어</title></head>
<body>
<script>window.DOCVIEWER_CONFIG = __DOCVIEWER_CONFIG__;</script>
<div id="loading">로딩 중...</div>
</body></html>
```

- [ ] **Step 2: 테스트 작성**

`src/test/java/com/docviewer/handler/ViewHandlerTest.java`:
```java
package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.detector.FileTypeDetector;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ViewHandlerTest {
    private HttpServer server;
    private int port;
    private Path tempDir;
    private ConversionCache cache;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("vh-test-");
        cache = new ConversionCache(tempDir, 86400L);

        DocViewerConfig config = DocViewerConfig.fromArgs(new String[0]);
        FileTypeDetector detector = new FileTypeDetector();

        // Mock converter: writes dummy PDF bytes to dest
        DocumentConverter converter = new DocumentConverter() {
            public void convert(File src, File dest) throws Exception {
                Files.write(dest.toPath(), "%PDF-1.4 dummy".getBytes());
            }
            public boolean isAlive() { return true; }
            public void shutdown() {}
        };

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docviewer/view",
            new ViewHandler(config, converter, cache, detector));
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder())
            .map(Path::toFile).forEach(File::delete);
    }

    @Test
    void returns400WhenPathMissing() throws Exception {
        HttpResponse<String> resp = get("/docviewer/view");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void returns404ForNonExistentFile() throws Exception {
        HttpResponse<String> resp = get("/docviewer/view?path=/nonexistent/file.pdf");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void servesPdfViewerHtmlForPdfFile() throws Exception {
        File pdf = File.createTempFile("test", ".pdf", tempDir.toFile());
        Files.write(pdf.toPath(), "%PDF-1.4".getBytes());

        HttpResponse<String> resp = get("/docviewer/view?path="
            + URLEncoder.encode(pdf.getAbsolutePath(), "UTF-8"));

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("DOCVIEWER_CONFIG"));
        assertTrue(resp.body().contains("\"renderType\":\"pdf\""));
        assertTrue(resp.body().contains(pdf.getName()));
    }

    @Test
    void servesImageViewerHtmlForImageFile() throws Exception {
        File img = File.createTempFile("photo", ".png", tempDir.toFile());
        Files.write(img.toPath(), new byte[]{(byte)0x89, 'P', 'N', 'G'});

        HttpResponse<String> resp = get("/docviewer/view?path="
            + URLEncoder.encode(img.getAbsolutePath(), "UTF-8"));

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"renderType\":\"image\""));
    }

    @Test
    void triggersConversionForHwpFile() throws Exception {
        File hwp = File.createTempFile("doc", ".hwp", tempDir.toFile());
        Files.write(hwp.toPath(), "fake hwp content".getBytes());

        HttpResponse<String> resp = get("/docviewer/view?path="
            + URLEncoder.encode(hwp.getAbsolutePath(), "UTF-8"));

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"renderType\":\"pdf\""));
        assertTrue(resp.body().contains("/docviewer/file?id="));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        return client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
mvn test -Dtest=ViewHandlerTest
```
Expected: `FAIL`

- [ ] **Step 4: ViewHandler 구현**

`src/main/java/com/docviewer/handler/ViewHandler.java`:
```java
package com.docviewer.handler;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.DocumentConverter;
import com.docviewer.detector.FileTypeDetector;
import com.sun.net.httpserver.*;
import org.slf4j.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class ViewHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(ViewHandler.class);

    private final DocViewerConfig config;
    private final DocumentConverter converter;
    private final ConversionCache cache;
    private final FileTypeDetector detector;

    public ViewHandler(DocViewerConfig config, DocumentConverter converter,
                       ConversionCache cache, FileTypeDetector detector) {
        this.config = config;
        this.converter = converter;
        this.cache = cache;
        this.detector = detector;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
        String rawPath = params.get("path");
        if (rawPath == null) { sendError(exchange, 400, "path parameter required"); return; }

        String filePath;
        try { filePath = URLDecoder.decode(rawPath, "UTF-8"); }
        catch (UnsupportedEncodingException e) { sendError(exchange, 400, "Invalid path"); return; }

        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) { sendError(exchange, 404, "File not found"); return; }

        try { validatePath(filePath); }
        catch (SecurityException e) { sendError(exchange, 403, "Access denied"); return; }

        FileTypeDetector.RenderType serverType = detector.detect(file.getName());
        String clientRenderType;
        String fileUrl;

        if (serverType == FileTypeDetector.RenderType.LIBREOFFICE) {
            try {
                String cacheId = cache.getOrConvert(file, converter::convert);
                fileUrl = "/docviewer/file?id=" + cacheId;
                clientRenderType = "pdf";
            } catch (Exception e) {
                log.error("Conversion failed for {}", filePath, e);
                sendError(exchange, 500, "Conversion failed: " + e.getMessage());
                return;
            }
        } else {
            fileUrl = "/docviewer/file?path=" + URLEncoder.encode(filePath, "UTF-8");
            clientRenderType = serverType.name().toLowerCase();
        }

        String configJson = String.format(
            "{\"filename\":\"%s\",\"renderType\":\"%s\",\"fileUrl\":\"%s\"}",
            file.getName().replace("\"", "\\\""),
            clientRenderType,
            fileUrl
        );
        String html = loadTemplate(configJson);
        byte[] bytes = html.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }

    private String loadTemplate(String configJson) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/static/viewer.html")) {
            if (in == null) throw new IOException("viewer.html not found");
            return new String(in.readAllBytes(), "UTF-8")
                .replace("__DOCVIEWER_CONFIG__", configJson);
        }
    }

    private void validatePath(String filePath) {
        Path resolved = Paths.get(filePath).normalize().toAbsolutePath();
        if (config.allowedPaths.isEmpty()) return;
        for (String allowed : config.allowedPaths) {
            if (resolved.startsWith(Paths.get(allowed).toAbsolutePath())) return;
        }
        throw new SecurityException("Not in allowed paths: " + resolved);
    }

    private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        byte[] bytes = ("<html><body><p>" + msg + "</p></body></html>").getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
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
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
mvn test -Dtest=ViewHandlerTest
```
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "feat: add ViewHandler with file type routing, LibreOffice conversion, and security path validation"
```

---

## Task 10: DocViewerServer (진입점)

**Files:**
- Create: `src/main/java/com/docviewer/DocViewerServer.java`

`main()` 메서드. 모든 컴포넌트를 조립하고 HttpServer를 기동한다.

- [ ] **Step 1: DocViewerServer 작성**

`src/main/java/com/docviewer/DocViewerServer.java`:
```java
package com.docviewer;

import com.docviewer.cache.ConversionCache;
import com.docviewer.config.DocViewerConfig;
import com.docviewer.converter.LibreOfficeConverter;
import com.docviewer.detector.FileTypeDetector;
import com.docviewer.handler.*;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.*;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class DocViewerServer {
    private static final Logger log = LoggerFactory.getLogger(DocViewerServer.class);

    public static void main(String[] args) throws Exception {
        DocViewerConfig config = DocViewerConfig.fromArgs(args);

        if (config.libreOfficePath == null) {
            log.error("--libreoffice=<path> is required. e.g. --libreoffice=/usr/lib/libreoffice");
            System.exit(1);
        }

        ConversionCache cache = new ConversionCache(config.cacheTtlSeconds);
        cache.cleanup();

        FileTypeDetector detector = new FileTypeDetector();
        LibreOfficeConverter converter = new LibreOfficeConverter(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down doc-viewer...");
            converter.shutdown();
        }));

        HttpServer server = HttpServer.create(new InetSocketAddress(config.port), 100);
        server.createContext("/docviewer/view",   new ViewHandler(config, converter, cache, detector));
        server.createContext("/docviewer/file",   new FileHandler(cache, config, detector));
        server.createContext("/docviewer/static", new StaticHandler());
        server.createContext("/docviewer/health", exchange -> {
            String body = String.format(
                "{\"status\":\"ok\",\"libreoffice\":%b,\"port\":%d}",
                converter.isAlive(), config.port
            );
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        log.info("doc-viewer started on http://localhost:{}/docviewer", config.port);
    }
}
```

- [ ] **Step 2: 전체 테스트 통과 확인**

```bash
mvn test
```
Expected: 전체 테스트 `BUILD SUCCESS`, failures 0

- [ ] **Step 3: fat-jar 빌드 확인**

```bash
mvn package -DskipTests
ls -lh target/doc-viewer-1.0.0.jar
```
Expected: JAR 파일 존재, 크기 10MB 이상 (JodConverter 포함)

- [ ] **Step 4: Commit**

```bash
git add src/
git commit -m "feat: add DocViewerServer main entry point assembling all components"
```

---

## Task 11: viewer.html + viewer.css (완성본)

**Files:**
- Modify: `src/main/resources/static/viewer.html`
- Modify: `src/main/resources/static/viewer.css`

Task 9의 플레이스홀더 viewer.html을 pdf.js가 포함된 완성본으로 교체한다.

- [ ] **Step 1: viewer.css 완성본 작성**

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
#loading { margin: auto; color: #666; font-size: 16px; }
#error { margin: auto; color: #c00; font-size: 16px; text-align: center; padding: 20px; }
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

- [ ] **Step 2: viewer.html 완성본 작성**

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
    <div id="loading">문서를 불러오는 중입니다...</div>
    <div id="error" class="hidden"></div>
    <canvas id="pdf-canvas" class="hidden"></canvas>
    <pre id="text-content" class="hidden"></pre>
    <img id="image-content" class="hidden" alt="문서 이미지">
  </div>
  <script src="/docviewer/static/viewer.js"></script>
</body>
</html>
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/viewer.html src/main/resources/static/viewer.css
git commit -m "feat: complete viewer.html and viewer.css with pdf.js integration and toolbar"
```

---

## Task 12: viewer.js

**Files:**
- Modify: `src/main/resources/static/viewer.js`

파일 타입(`pdf` / `text` / `image`)에 따라 렌더러를 분기한다. PDF는 pdf.js로 컨테이너 너비 기반 자동 스케일 렌더링.

- [ ] **Step 1: viewer.js 작성**

`src/main/resources/static/viewer.js`:
```javascript
(function () {
  'use strict';

  var cfg = window.DOCVIEWER_CONFIG;
  var $ = function (id) { return document.getElementById(id); };

  $('filename').textContent = cfg.filename;
  $('download-link').href = cfg.fileUrl;
  $('download-link').setAttribute('download', cfg.filename);
  $('print-btn').addEventListener('click', function () { window.print(); });

  function showError(msg) {
    $('loading').classList.add('hidden');
    $('error').textContent = msg;
    $('error').classList.remove('hidden');
  }

  // IMAGE
  if (cfg.renderType === 'image') {
    $('loading').classList.add('hidden');
    $('image-content').src = cfg.fileUrl;
    $('image-content').classList.remove('hidden');
    return;
  }

  // TEXT
  if (cfg.renderType === 'text') {
    fetch(cfg.fileUrl)
      .then(function (r) { return r.arrayBuffer(); })
      .then(function (buf) {
        var text;
        try { text = new TextDecoder('euc-kr').decode(buf); }
        catch (e) { text = new TextDecoder('utf-8').decode(buf); }
        $('loading').classList.add('hidden');
        $('text-content').textContent = text;
        $('text-content').classList.remove('hidden');
      })
      .catch(function (e) { showError('파일을 불러올 수 없습니다: ' + e.message); });
    return;
  }

  // PDF (cfg.renderType === 'pdf')
  var pdfjsLib = window['pdfjs-dist/build/pdf'];
  if (!pdfjsLib) { showError('pdf.js 로드 실패'); return; }
  pdfjsLib.GlobalWorkerOptions.workerSrc = '/docviewer/static/lib/pdf.worker.min.js';

  var pdfDoc = null;
  var currentPage = 1;
  var scale = 1.0;
  var canvas = $('pdf-canvas');
  var ctx = canvas.getContext('2d');
  var rendering = false;

  function containerWidth() {
    return $('viewer-container').clientWidth - 40;
  }

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
      $('page-info').textContent = num + ' / ' + pdfDoc.numPages;
      rendering = false;
    });
  }

  pdfjsLib.getDocument(cfg.fileUrl).promise.then(function (doc) {
    pdfDoc = doc;
    $('loading').classList.add('hidden');
    canvas.classList.remove('hidden');
    $('pdf-controls').classList.remove('hidden');
    return renderPage(1);
  }).catch(function (e) {
    showError('PDF를 렌더링할 수 없습니다: ' + e.message);
  });

  $('prev-page').addEventListener('click', function () {
    if (currentPage <= 1) return;
    currentPage--;
    renderPage(currentPage);
  });
  $('next-page').addEventListener('click', function () {
    if (!pdfDoc || currentPage >= pdfDoc.numPages) return;
    currentPage++;
    renderPage(currentPage);
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

  window.addEventListener('resize', function () {
    if (pdfDoc) renderPage(currentPage);
  });
})();
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/viewer.js
git commit -m "feat: add viewer.js with pdf.js rendering, EUC-KR text decode, and image display"
```

---

## Task 13: doc-viewer-client.js (통합 스니펫)

**Files:**
- Create: `src/main/resources/static/doc-viewer-client.js`

기존 JSP 프로젝트에서 script 1줄 include 후 `DocViewer.open(path)` 한 줄로 뷰어를 열 수 있게 해주는 클라이언트 스니펫.

- [ ] **Step 1: doc-viewer-client.js 작성**

`src/main/resources/static/doc-viewer-client.js`:
```javascript
(function () {
  var base = (function () {
    var scripts = document.getElementsByTagName('script');
    for (var i = 0; i < scripts.length; i++) {
      var m = (scripts[i].src || '').match(/^(https?:\/\/[^\/]+)\/docviewer\/static\/doc-viewer-client\.js/);
      if (m) return m[1];
    }
    return '';
  })();

  window.DocViewer = {
    open: function (filePath, options) {
      var url = base + '/docviewer/view?path=' + encodeURIComponent(filePath);
      window.open(url, (options && options.target) || '_blank');
    }
  };
})();
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/doc-viewer-client.js
git commit -m "feat: add doc-viewer-client.js integration snippet for JSP projects"
```

---

## Task 14: 최종 빌드 및 동작 확인

- [ ] **Step 1: 전체 테스트 실행**

```bash
mvn test
```
Expected: 전체 `BUILD SUCCESS`, 0 failures

- [ ] **Step 2: fat-jar 패키징**

```bash
mvn package -DskipTests
```
Expected: `target/doc-viewer-1.0.0.jar` 생성

- [ ] **Step 3: JAR 내 정적 리소스 확인**

```bash
jar tf target/doc-viewer-1.0.0.jar | grep static/
```
Expected: `static/viewer.html`, `static/viewer.js`, `static/viewer.css`, `static/lib/pdf.min.js` 등 목록 출력

- [ ] **Step 4: 서버 기동 테스트 (LibreOffice 설치 환경에서)**

```bash
java -jar target/doc-viewer-1.0.0.jar \
  --libreoffice=/usr/lib/libreoffice \
  --allowed-paths=/tmp
```
Expected: `doc-viewer started on http://localhost:8090/docviewer` 로그 출력

- [ ] **Step 5: health 엔드포인트 확인**

```bash
curl http://localhost:8090/docviewer/health
```
Expected: `{"status":"ok","libreoffice":true,"port":8090}`

- [ ] **Step 6: TXT 파일 뷰어 확인 (브라우저)**

```bash
echo "Hello 문서 뷰어" > /tmp/test.txt
# 브라우저에서 열기:
# http://localhost:8090/docviewer/view?path=/tmp/test.txt
```
Expected: 새 탭에 "Hello 문서 뷰어" 텍스트 렌더링

- [ ] **Step 7: 최종 Commit**

```bash
git add .
git commit -m "feat: complete doc-viewer 1.0.0 - integrated document viewer with LibreOffice conversion"
```

---

## 기존 JSP 프로젝트 통합 요약

서버 기동 후 JSP 페이지에 아래 2줄만 추가:

```html
<!-- head 또는 body 끝에 -->
<script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
```

```javascript
// 첨부파일 클릭 이벤트
DocViewer.open('/upload/files/문서.hwp');
```
