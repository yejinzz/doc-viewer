# HWP/HWPX 프론트엔드 렌더링 (rhwp) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** HWP/HWPX 파일을 LibreOffice 변환 없이 rhwp WASM 라이브러리로 브라우저에서 직접 SVG 렌더링한다.

**Architecture:** `FileTypeDetector`에 HWP enum을 추가해 hwp/hwpx를 LIBREOFFICE와 분리한다. `ApiHandler`는 HWP 타입에 대해 LibreOffice 변환을 건너뛰고 바로 `markConverted`한다. `ViewHandler`는 HWP 타입에 `renderType:"hwp"`와 `fileUrl:?key=...`를 반환하고, `viewer.js`가 rhwp WASM을 dynamic import로 로드해 SVG 페이지를 렌더링한다.

**Tech Stack:** Java 11, JUnit 5, Mockito, Maven, rhwp v0.7.13 (Rust/WASM), plain ES5 JavaScript (dynamic import)

---

## File Map

| 파일 | 역할 |
|------|------|
| `src/main/java/com/docviewer/detector/FileTypeDetector.java` | HWP enum 추가, hwp/hwpx 매핑 변경 |
| `src/test/java/com/docviewer/detector/FileTypeDetectorTest.java` | 기존 테스트 수정 + HWP 테스트 추가 |
| `src/main/java/com/docviewer/handler/ApiHandler.java` | HWP 타입 시 변환 skip 분기 추가 |
| `src/test/java/com/docviewer/handler/ApiHandlerTest.java` | HWP 변환 skip 검증 테스트 추가 |
| `src/main/java/com/docviewer/handler/ViewHandler.java` | HWP 분기 추가 |
| `src/test/java/com/docviewer/handler/ViewHandlerTest.java` | 기존 HWP 테스트 수정 + 신규 추가 |
| `src/main/resources/static/viewer.html` | `<div id="hwp-container">` 추가 |
| `src/main/resources/static/viewer.js` | HWP 렌더링 블록 추가 |

---

## Task 1: FileTypeDetector — HWP 타입 분리

**Files:**
- Modify: `src/main/java/com/docviewer/detector/FileTypeDetector.java`
- Modify: `src/test/java/com/docviewer/detector/FileTypeDetectorTest.java`

- [ ] **Step 1: 기존 테스트에서 hwp/hwpx 관련 단언 수정**

`FileTypeDetectorTest.java`의 두 테스트를 수정한다. HWP → LIBREOFFICE 였던 단언들을 HWP로 바꾸고, 신규 `detectsHwpFormats` 테스트를 추가한다.

```java
// detectsLibreOfficeFormats() — hwp/hwpx 라인 제거
@Test
void detectsLibreOfficeFormats() {
    assertEquals(LIBREOFFICE, detector.detect("doc.docx"));
    assertEquals(LIBREOFFICE, detector.detect("sheet.xlsx"));
    assertEquals(LIBREOFFICE, detector.detect("pres.pptx"));
    assertEquals(LIBREOFFICE, detector.detect("old.doc"));
    // 아래 두 줄 제거됨:
    // assertEquals(LIBREOFFICE, detector.detect("문서.hwp"));
    // assertEquals(LIBREOFFICE, detector.detect("data.hwpx"));
}

// detectsHwpFormats() — 신규 추가
@Test
void detectsHwpFormats() {
    assertEquals(HWP, detector.detect("문서.hwp"));
    assertEquals(HWP, detector.detect("data.hwpx"));
    assertEquals(HWP, detector.detect("DOC.HWP"));
    assertEquals(HWP, detector.detect("DOC.HWPX"));
}

// detectStillWorksWithCustomExtensions() — hwp 단언 수정
@Test
void detectStillWorksWithCustomExtensions() {
    FileTypeDetector restricted = new FileTypeDetector(java.util.Set.of("pdf", "hwp", "jpg"));
    assertEquals(FileTypeDetector.RenderType.PDF, restricted.detect("doc.pdf"));
    assertEquals(FileTypeDetector.RenderType.IMAGE, restricted.detect("photo.jpg"));
    assertEquals(FileTypeDetector.RenderType.HWP, restricted.detect("file.hwp")); // LIBREOFFICE → HWP
}
```

import 문에 `HWP`를 추가한다:
```java
import static com.docviewer.detector.FileTypeDetector.RenderType.*;
// HWP가 enum에 없으므로 아직 컴파일 안 됨 — 의도적
```

- [ ] **Step 2: 테스트 실패 확인**

```
mvn test -Dtest=FileTypeDetectorTest
```

예상 출력: 컴파일 에러 또는 `FAILURES` — `HWP` 심볼 없음, `detectsHwpFormats` 실패

- [ ] **Step 3: FileTypeDetector 구현**

`FileTypeDetector.java` 전체를 아래로 교체:

```java
package com.docviewer.detector;

import java.util.Map;
import java.util.Set;

public class FileTypeDetector {

    public enum RenderType { PDF, TEXT, IMAGE, LIBREOFFICE, HWP }

    private static final Set<String> IMAGE_EXTS = Set.of(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico"
    );
    private static final Set<String> TEXT_EXTS = Set.of("txt", "log", "csv");
    private static final Set<String> PDF_EXTS  = Set.of("pdf");
    private static final Set<String> HWP_EXTS  = Set.of("hwp", "hwpx");
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
        if (HWP_EXTS.contains(ext))   return RenderType.HWP;
        return RenderType.LIBREOFFICE;
    }

    public String mimeType(String filename) {
        return MIME_MAP.getOrDefault(ext(filename), "application/octet-stream");
    }

    public boolean isSupported(String filename) {
        String ext = ext(filename);
        if (allowedExtensions != null) return allowedExtensions.contains(ext);
        return PDF_EXTS.contains(ext) || TEXT_EXTS.contains(ext)
            || IMAGE_EXTS.contains(ext) || HWP_EXTS.contains(ext)
            || isLibreOfficeExt(ext);
    }

    private boolean isLibreOfficeExt(String ext) {
        return Set.of("doc","docx","xls","xlsx","ods",
                      "ppt","pptx","odp","odt","rtf").contains(ext);
    }

    private String ext(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```
mvn test -Dtest=FileTypeDetectorTest
```

예상 출력: `Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 5: 커밋**

```
git add src/main/java/com/docviewer/detector/FileTypeDetector.java
git add src/test/java/com/docviewer/detector/FileTypeDetectorTest.java
git commit -m "feat: add HWP RenderType to FileTypeDetector, split hwp/hwpx from LIBREOFFICE"
```

---

## Task 2: ApiHandler — HWP 변환 skip

**Files:**
- Modify: `src/main/java/com/docviewer/handler/ApiHandler.java`
- Modify: `src/test/java/com/docviewer/handler/ApiHandlerTest.java`

- [ ] **Step 1: 테스트 추가 — HWP는 converter를 호출하지 않음**

`ApiHandlerTest.java`에 아래 테스트를 추가한다. converter가 호출되면 예외를 던지도록 설정해 호출 여부를 검증한다.

```java
@Test
void hwpConvertSkipsConverter() throws Exception {
    // converter가 호출되면 무조건 실패하는 서버를 별도 기동
    DocumentConverter failConverter = new DocumentConverter() {
        public void convert(File src, File dest) throws Exception {
            throw new RuntimeException("converter must not be called for HWP");
        }
        public boolean isAlive() { return true; }
        public void shutdown() {}
    };
    DocViewerConfig cfg = DocViewerConfig.fromArgs(new String[]{
        "--result-dir=" + tempDir.toAbsolutePath(),
        "--allowed-paths=" + tempDir.toAbsolutePath(),
        "--api-allowed-ips=127.0.0.1"
    });
    FileTypeDetector det = new FileTypeDetector(cfg.allowedExtensions);
    IpWhitelistFilter f = new IpWhitelistFilter(cfg.apiAllowedIps);
    ConversionCache c = new ConversionCache(tempDir, 86400L);

    HttpServer s = HttpServer.create(new InetSocketAddress(0), 0);
    s.createContext("/docviewer/api",
        new ApiHandler(cfg, failConverter, c, det, registry, f));
    s.setExecutor(null);
    s.start();
    int p = s.getAddress().getPort();

    try {
        File hwp = File.createTempFile("skip", ".hwp", tempDir.toFile());
        Files.write(hwp.toPath(), "fake hwp".getBytes());
        String body = String.format(
            "{\"key\":\"FILE_HWP_SKIP_0\",\"path\":\"%s\",\"originalName\":\"문서.hwp\",\"fileHash\":\"\"}",
            hwp.getAbsolutePath().replace("\\", "\\\\"));

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + p + "/docviewer/api/convert"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"status\":\"ok\""));
        assertEquals("converted", registry.getStatus("FILE_HWP_SKIP_0"));
    } finally {
        s.stop(0);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
mvn test -Dtest=ApiHandlerTest#hwpConvertSkipsConverter
```

예상 출력: `FAIL` — 현재 구현이 converter를 호출해 500 에러 반환

- [ ] **Step 3: ApiHandler 구현 — handleConvert에 HWP skip 분기**

`ApiHandler.java`의 `handleConvert()` 안에서 `cache.getOrConvert` 호출 부분을 아래로 교체:

```java
// 변경 전 (line 107):
// cache.getOrConvert(file, converter::convert);

// 변경 후:
FileTypeDetector.RenderType fileType = detector.detect(displayName);
if (fileType != FileTypeDetector.RenderType.HWP) {
    cache.getOrConvert(file, converter::convert);
}
```

- [ ] **Step 4: ApiHandler 구현 — handleRefresh에 HWP skip 분기**

`ApiHandler.java`의 `handleRefresh()` 안에서 cache 관련 호출 부분을 교체:

```java
// 변경 전 (lines 134-135):
// cache.invalidateCache(file);
// cache.getOrConvert(file, converter::convert);

// 변경 후:
FileTypeDetector.RenderType fileType = detector.detect(entry.originalName != null ? entry.originalName : file.getName());
if (fileType != FileTypeDetector.RenderType.HWP) {
    cache.invalidateCache(file);
    cache.getOrConvert(file, converter::convert);
}
```

- [ ] **Step 5: 전체 ApiHandler 테스트 통과 확인**

```
mvn test -Dtest=ApiHandlerTest
```

예상 출력: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 6: 커밋**

```
git add src/main/java/com/docviewer/handler/ApiHandler.java
git add src/test/java/com/docviewer/handler/ApiHandlerTest.java
git commit -m "feat: skip LibreOffice conversion for HWP/HWPX in ApiHandler"
```

---

## Task 3: ViewHandler — HWP renderType 반환

**Files:**
- Modify: `src/main/java/com/docviewer/handler/ViewHandler.java`
- Modify: `src/test/java/com/docviewer/handler/ViewHandlerTest.java`

- [ ] **Step 1: 기존 HWP 테스트 수정 + 신규 테스트 추가**

`ViewHandlerTest.java`의 `triggersConversionForHwpAndServesViewerHtml` 를 아래로 교체:

```java
@Test
void hwpKeyReturnsHwpRenderTypeWithFileKey() throws Exception {
    File hwp = File.createTempFile("doc", ".hwp", tempDir.toFile());
    Files.write(hwp.toPath(), "fake hwp".getBytes());
    String key = "FILE_HWP_0";
    registry.register(key, hwp.getAbsolutePath(), "doc.hwp");
    registry.markConverted(key, "hash1", hwp.length(), hwp.lastModified());

    HttpResponse<String> resp = get("/docviewer/view?key=" + key);
    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains("\"renderType\":\"hwp\""));
    assertTrue(resp.body().contains("/docviewer/file?key=" + key));
    // PDF 캐시 경로가 노출되지 않음을 검증
    assertFalse(resp.body().contains("/docviewer/file?id="));
}

@Test
void hwpxKeyReturnsHwpRenderType() throws Exception {
    File hwpx = File.createTempFile("doc", ".hwpx", tempDir.toFile());
    Files.write(hwpx.toPath(), "fake hwpx".getBytes());
    String key = "FILE_HWPX_0";
    registry.register(key, hwpx.getAbsolutePath(), "doc.hwpx");
    registry.markConverted(key, "hash1", hwpx.length(), hwpx.lastModified());

    HttpResponse<String> resp = get("/docviewer/view?key=" + key);
    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains("\"renderType\":\"hwp\""));
    assertTrue(resp.body().contains("/docviewer/file?key=" + key));
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
mvn test -Dtest=ViewHandlerTest#hwpKeyReturnsHwpRenderTypeWithFileKey+hwpxKeyReturnsHwpRenderType
```

예상 출력: `FAIL` — 현재 `renderType:pdf`와 `?id=` 반환

- [ ] **Step 3: ViewHandler 구현 — HWP 분기 추가**

`ViewHandler.java`의 `handle()` 메서드 안에서 renderType 분기 블록을 아래로 교체:

```java
// 변경 전:
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

// 변경 후:
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
} else if (renderType == FileTypeDetector.RenderType.HWP) {
    fileUrl = "/docviewer/file?key=" + key;
    clientRenderType = "hwp";
} else {
    fileUrl = "/docviewer/file?key=" + key;
    clientRenderType = renderType.name().toLowerCase();
}
```

- [ ] **Step 4: 전체 ViewHandler 테스트 통과 확인**

```
mvn test -Dtest=ViewHandlerTest
```

예상 출력: `Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 5: 전체 테스트 통과 확인**

```
mvn test
```

예상 출력: `BUILD SUCCESS`, 모든 테스트 통과

- [ ] **Step 6: 커밋**

```
git add src/main/java/com/docviewer/handler/ViewHandler.java
git add src/test/java/com/docviewer/handler/ViewHandlerTest.java
git commit -m "feat: return renderType=hwp and file?key= for HWP/HWPX in ViewHandler"
```

---

## Task 4: viewer.html — HWP 컨테이너 추가

**Files:**
- Modify: `src/main/resources/static/viewer.html`

- [ ] **Step 1: hwp-container div 추가**

`viewer.html`의 `<img id="image-content" ...>` 바로 다음 줄에 추가:

```html
    <canvas id="pdf-canvas" class="hidden"></canvas>
    <pre id="text-content" class="hidden"></pre>
    <img id="image-content" class="hidden" alt="문서 이미지">
    <div id="hwp-container" class="hidden"></div>   <!-- 추가 -->
  </div>
```

- [ ] **Step 2: 커밋**

```
git add src/main/resources/static/viewer.html
git commit -m "feat: add hwp-container div to viewer.html"
```

---

## Task 5: viewer.js — HWP 렌더링 블록 추가

**Files:**
- Modify: `src/main/resources/static/viewer.js`

- [ ] **Step 1: HWP 렌더링 블록 추가**

`viewer.js`에서 TEXT 블록(`// TEXT ... return;`) 바로 다음, PDF 섹션(`// PDF`) 바로 앞에 아래 블록을 삽입:

```javascript
  // HWP / HWPX
  if (cfg.renderType === 'hwp') {
    showLoading('HWP 문서를 불러오는 중...');
    fetch(cfg.fileUrl)
      .then(function(r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.arrayBuffer();
      })
      .then(function(buf) {
        return import('/docviewer/static/lib/rhwp/rhwp.js').then(function(mod) {
          return mod.default({ module_or_path: '/docviewer/static/lib/rhwp/rhwp_bg.wasm' })
            .then(function() { return mod; });
        }).then(function(mod) {
          var doc = new mod.HwpDocument(new Uint8Array(buf));
          var total = doc.pageCount();
          var current = 0;
          var container = $('hwp-container');

          $('loading').classList.add('hidden');
          container.classList.remove('hidden');
          $('pdf-controls').classList.remove('hidden');
          $('zoom-in').style.display = 'none';
          $('zoom-out').style.display = 'none';
          $('zoom-level').style.display = 'none';
          $('page-info').textContent = '1 / ' + total;

          function renderPage(idx) {
            container.innerHTML = doc.renderPageSvg(idx);
            $('page-info').textContent = (idx + 1) + ' / ' + total;
          }
          renderPage(0);

          $('prev-page').addEventListener('click', function() {
            if (current <= 0) return;
            current--; renderPage(current);
          });
          $('next-page').addEventListener('click', function() {
            if (current >= total - 1) return;
            current++; renderPage(current);
          });
        });
      })
      .catch(function(e) { showError('HWP 문서를 열 수 없습니다: ' + e.message, true); });
    return;
  }
```

삽입 위치 기준 — 최종 viewer.js 구조:

```
(function () {
  'use strict';
  // ... 공통 설정 ($, showError, showLoading) ...

  // IMAGE
  if (cfg.renderType === 'image') { ... return; }

  // TEXT
  if (cfg.renderType === 'text') { ... return; }

  // HWP / HWPX  ← 여기 삽입
  if (cfg.renderType === 'hwp') { ... return; }

  // PDF
  var pdfjsLib = ...
  ...
})();
```

- [ ] **Step 2: 브라우저에서 HWP 파일 동작 확인**

서버를 빌드하고 기동한다:

```
mvn package -DskipTests
java -jar target/doc-viewer-1.0.0.jar --libreoffice=/usr/lib/libreoffice --allowed-paths=/tmp --result-dir=/tmp/dv-out
```

테스트 HWP 파일을 등록한다:

```
curl -X POST http://localhost:8090/docviewer/api/convert \
  -H "Content-Type: application/json" \
  -d '{"key":"TEST_HWP_001","path":"/tmp/sample.hwp","originalName":"sample.hwp","fileHash":""}'
```

브라우저에서 `http://localhost:8090/docviewer/view?key=TEST_HWP_001` 열어 확인:
- 로딩 스피너 표시 후 SVG 페이지 렌더링
- 이전/다음 버튼으로 페이지 이동
- 줌 버튼 숨김 처리 확인
- 다운로드 버튼 동작 확인 (원본 HWP 파일 다운로드)

- [ ] **Step 3: 커밋**

```
git add src/main/resources/static/viewer.js
git commit -m "feat: add HWP/HWPX client-side rendering via rhwp WASM in viewer.js"
```
