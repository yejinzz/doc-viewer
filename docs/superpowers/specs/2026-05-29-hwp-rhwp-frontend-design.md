# HWP/HWPX 프론트엔드 렌더링 설계 (rhwp)

**날짜:** 2026-05-29  
**브랜치:** feat/rhwp  
**배경:** LibreOffice + libreoffice-h2orestart 확장으로 HWP 변환 시 폰트 품질 문제가 해결되지 않아, 서버사이드 변환 대신 Rust/WASM 기반 rhwp 라이브러리로 브라우저에서 직접 렌더링하는 방식으로 전환.

---

## 1. 아키텍처 개요

```
CMS Server
  │
  │ POST /docviewer/api/convert
  │  { key, path: "/data/files/문서.hwp", originalName }
  ▼
ApiHandler
  ├─ 파일 유효성 검사 (존재, 크기, 확장자)
  ├─ FileTypeDetector → RenderType.HWP 감지
  ├─ SQLite register(key, path)
  ├─ [LibreOffice 호출 없음]
  ├─ SHA-256 해시 계산
  └─ markConverted(key, hash, size, lastModified)
       → {"status":"ok","key":"..."}

Browser
  │
  │ GET /docviewer/view?key=FILE_xxx
  ▼
ViewHandler
  ├─ SQLite lookup → file_path, convert_status
  ├─ FileTypeDetector → RenderType.HWP 감지
  ├─ [cache.getOrConvert() 호출 없음]
  └─ config JSON inject
       { renderType: "hwp", fileUrl: "/docviewer/file?key=FILE_xxx", filename: "문서.hwp" }
       → viewer.html 반환

Browser (viewer.js)
  │
  │ fetch fileUrl → ArrayBuffer → Uint8Array
  ▼
rhwp WASM (dynamic import)
  ├─ await init({ module_or_path: '/docviewer/static/lib/rhwp/rhwp_bg.wasm' })
  ├─ new HwpDocument(new Uint8Array(buffer))
  ├─ doc.pageCount() → 전체 페이지 수
  └─ doc.renderPageSvg(pageIndex) → SVG 문자열
       → <div id="hwp-container"> 에 innerHTML 삽입
```

**핵심 원칙:**
- CMS 연동 코드 변경 없음 — `/api/convert` 호출 방식 동일
- HWP/HWPX에 대해 LibreOffice가 단 한 번도 호출되지 않음
- `FileHandler?key=`는 기존 구현 그대로 재사용 (원본 HWP 파일 서빙)
- `.hwp`와 `.hwpx` 모두 서버에서 `renderType: "hwp"`로 통일하여 내려줌

---

## 2. 서버사이드 변경

### 2-1. `FileTypeDetector` — HWP enum 추가

```java
public enum RenderType {
    PDF, TEXT, IMAGE, LIBREOFFICE, HWP  // HWP 신규 추가
}
```

`.hwp`, `.hwpx`를 LIBREOFFICE에서 HWP로 재매핑.

### 2-2. `ApiHandler` — HWP 타입 변환 skip

`handleConvert()` 내부:
```java
registry.register(key, resolved.toString(), displayName);

FileTypeDetector.RenderType type = detector.detect(displayName);
if (type != FileTypeDetector.RenderType.HWP) {
    cache.getOrConvert(file, converter::convert);
}

String hash = HashUtil.sha256File(file);
registry.markConverted(key, hash, file.length(), file.lastModified());
```

`handleRefresh()` 내부: HWP 타입이면 `cache.invalidateCache()` 및 `cache.getOrConvert()` 호출 없이 hash만 갱신.

### 2-3. `ViewHandler` — HWP 분기 추가

```java
if (renderType == FileTypeDetector.RenderType.LIBREOFFICE) {
    String cacheId = cache.getOrConvert(file, converter::convert);
    fileUrl = "/docviewer/file?id=" + cacheId;
    clientRenderType = "pdf";
} else if (renderType == FileTypeDetector.RenderType.HWP) {
    fileUrl = "/docviewer/file?key=" + key;
    clientRenderType = "hwp";
} else {
    fileUrl = "/docviewer/file?key=" + key;
    clientRenderType = renderType.name().toLowerCase();
}
```

`FileHandler`는 변경 없음 — `?key=` 서빙 이미 구현됨.

---

## 3. 프론트엔드 변경

### 3-1. `viewer.html` — HWP 컨테이너 추가

기존 `<canvas>`, `<pre>`, `<img>` 옆에 추가:
```html
<div id="hwp-container" style="display:none"></div>
```

### 3-2. `viewer.js` — HWP 렌더링 블록 추가

기존 `if/return` 분기 구조에 HWP 블록 추가 (파일 분리 없이 viewer.js 내부에 위치):

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
        container.style.display = 'block';
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

**모듈 로딩:** `dynamic import()`는 일반 스크립트(non-module)에서도 동작. viewer.html 구조 변경 불필요. WASM 경로는 절대경로 고정으로 어느 URL에서 뷰어가 열려도 동일하게 로드.

**줌 컨트롤:** SVG는 CSS `width: 100%`로 컨테이너에 맞춤. 줌 버튼은 HWP 모드에서 비활성화(hide) 처리.

---

## 4. 에러 처리

| 상황 | 동작 |
|------|------|
| fetch 실패 (네트워크, 권한) | `showError()` + 다운로드 링크 |
| WASM 로드 실패 | `showError()` + 다운로드 링크 |
| HwpDocument 파싱 실패 | `showError()` + 다운로드 링크 |
| renderPageSvg 실패 | `showError()` + 다운로드 링크 |

기존 `showError()` UI 재사용. 별도 에러 UI 추가 없음.

---

## 5. 변경 파일 요약

| 파일 | 변경 내용 |
|------|----------|
| `FileTypeDetector.java` | HWP enum 추가, hwp/hwpx 매핑 변경 |
| `ApiHandler.java` | handleConvert/handleRefresh에 HWP 타입 skip 분기 |
| `ViewHandler.java` | HWP 타입 분기 추가 (fileUrl, clientRenderType) |
| `viewer.html` | `<div id="hwp-container">` 추가 |
| `viewer.js` | HWP 렌더링 블록 추가 |

기존 파일 추가 없음. `FileHandler`, `ConversionCache`, `LibreOfficeConverter` 변경 없음.

---

## 6. 기존 설계와의 관계

`docs/superpowers/specs/2026-05-29-hwp-support-design.md` (HwpCliConverter + DispatchingConverter 서버사이드 방식)는 별도 브랜치에서 사용 중이므로 보존. 본 문서는 해당 설계를 대체하지 않으며 feat/rhwp 브랜치에서의 독립적인 접근 방식.
