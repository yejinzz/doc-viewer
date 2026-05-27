# 통합 문서 뷰어 (doc-viewer) 설계 스펙

**작성일**: 2026-05-27
**상태**: 승인됨

---

## 개요

브라우저에서 첨부파일 클릭 한 번으로 문서를 새 탭에서 미리보는 통합 뷰어.
기존 JSP 기반 프로젝트 어디에나 최소한의 코드로 붙일 수 있는 독립 실행형 솔루션.

### 핵심 요구사항

- 첨부파일 클릭 시 새 탭에서 즉시 미리보기
- Word, HWP, 스프레드시트, PPT, PDF, TXT, 이미지 등 모든 주요 문서 형식 지원
- 서버사이드 LibreOffice 변환으로 A4 고정 문제 없는 반응형 렌더링
- JDK 11 기반 순수 Java 구현, `java -jar` 한 줄로 기동
- 기존 JSP 프로젝트에 스크립트 1줄 + 함수 호출 1줄로 통합

---

## 기술 스택

| 영역 | 선택 | 이유 |
|---|---|---|
| 런타임 | JDK 11 | 요구사항 |
| HTTP 서버 | `com.sun.net.httpserver` (JDK 내장) | 외부 의존성 없음 |
| LibreOffice 연동 | JodConverter (Apache 2.0) | 데몬 재사용으로 빠른 변환 |
| 로깅 | SLF4J + Logback | 경량 표준 |
| 빌드 | Maven + maven-shade-plugin | fat-jar 패키징 |
| 프론트엔드 | Vanilla JS + HTML + CSS | JSP 환경 호환, 프레임워크 의존성 없음 |
| PDF 렌더링 | Mozilla pdf.js | 검증된 오픈소스, JAR 내 번들 |

---

## 아키텍처

### 전체 흐름

```
[기존 JSP 페이지]
  └─ 첨부파일 클릭
       └─ window.open('/docviewer/view?path=/upload/files/foo.hwp')
            └─ [doc-viewer 서버 (fat-jar, 포트 8090)]
                  ├─ 파일 타입 판별
                  ├─ 프론트 처리 대상 (PDF/TXT/이미지) → viewer.html + 파일 직접 서빙
                  └─ LibreOffice 변환 필요 → JodConverter → PDF 변환 → 캐시 저장
                        └─ viewer.html (pdf.js) → /docviewer/file?id=xxx → PDF 렌더링
```

### 파일 타입별 처리 전략

| 파일 유형 | 처리 위치 | 방식 |
|---|---|---|
| PDF | 프론트 | pdf.js 직접 렌더링 |
| TXT | 프론트 | `<pre>` 태그 + EUC-KR/UTF-8 인코딩 감지 |
| 이미지 (jpg/png/gif/webp/bmp/svg) | 프론트 | `<img>` 태그 |
| Word (.doc/.docx) | 서버 → LibreOffice | → PDF → pdf.js |
| 한글 (.hwp/.hwpx) | 서버 → LibreOffice | → PDF → pdf.js |
| 스프레드시트 (.xls/.xlsx/.ods/.csv) | 서버 → LibreOffice | → PDF → pdf.js |
| 프레젠테이션 (.ppt/.pptx) | 서버 → LibreOffice | → PDF → pdf.js |
| 기타 문서 (.rtf/.odt/.odp 등) | 서버 → LibreOffice | → PDF → pdf.js |

---

## 프로젝트 구조

```
doc-viewer/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/docviewer/
│   │   │   ├── DocViewerServer.java            # 진입점 (main + HTTP 서버 기동)
│   │   │   ├── handler/
│   │   │   │   ├── ViewHandler.java            # GET /docviewer/view?path=...
│   │   │   │   ├── FileHandler.java            # GET /docviewer/file?id=...
│   │   │   │   └── StaticHandler.java          # GET /docviewer/static/**
│   │   │   ├── converter/
│   │   │   │   ├── DocumentConverter.java      # 변환 인터페이스
│   │   │   │   └── LibreOfficeConverter.java   # JodConverter 래핑 구현체
│   │   │   ├── cache/
│   │   │   │   └── ConversionCache.java        # 파일 변경 감지 + 임시 PDF 캐싱
│   │   │   ├── detector/
│   │   │   │   └── FileTypeDetector.java       # 확장자 + MIME 판별, 처리 방식 결정
│   │   │   └── config/
│   │   │       └── DocViewerConfig.java        # CLI args / properties 파싱
│   │   └── resources/
│   │       └── static/
│   │           ├── viewer.html                 # 뷰어 진입 페이지
│   │           ├── viewer.js                   # 파일 타입 분기 + pdf.js 제어
│   │           ├── viewer.css
│   │           ├── doc-viewer-client.js        # 기존 프로젝트 통합용 스니펫
│   │           └── lib/
│   │               ├── pdf.mjs                 # Mozilla pdf.js
│   │               └── pdf.worker.mjs
└── docs/
    └── superpowers/specs/
        └── 2026-05-27-doc-viewer-design.md
```

---

## REST API

| Method | Path | 설명 |
|---|---|---|
| GET | `/docviewer/view?path={filePath}` | viewer.html 반환. 새 탭 진입 메인 엔드포인트 |
| GET | `/docviewer/file?id={cacheId}` | 변환된 PDF 또는 원본 파일 바이트 스트리밍 |
| GET | `/docviewer/static/**` | viewer.js, pdf.js 등 정적 리소스 서빙 |
| GET | `/docviewer/health` | 서버 상태 + LibreOffice 데몬 상태 |

변환은 `/view` 요청 처리 중 서버 내부에서 투명하게 수행. 클라이언트는 변환 여부를 알 필요 없음.

---

## 캐싱 전략

```
캐시 키 = SHA-256(filePath + lastModified)
캐시 HIT  → 변환된 PDF 즉시 반환
캐시 MISS → LibreOffice 변환 → {tmpDir}/docviewer-cache/{cacheId}.pdf 저장 → 반환
```

- **저장 위치**: `java.io.tmpdir/docviewer-cache/`
- **무효화**: 원본 파일 `lastModified` 변경 시 자동 재변환
- **TTL 정리**: 서버 기동 시 24시간 이상 된 캐시 파일 삭제 (설정 가능)
- **중복 변환 방지**: `ConcurrentHashMap`으로 in-flight 변환 추적, 같은 파일 동시 요청 시 첫 번째 변환 완료까지 대기

---

## LibreOffice 데몬 관리

JodConverter가 LibreOffice를 소켓 포트(기본 2002)로 데몬 실행 후 커넥션 풀로 관리.

- **풀 사이즈 기본값**: 1 (LibreOffice 동시 변환 불안정 방지, 변환 요청은 큐에서 순차 처리)
- **자동 재기동**: 데몬 크래시 감지 시 자동 재기동 1회 시도
- **고트래픽 대응**: `--lo-pool-size` 설정으로 LibreOffice 인스턴스 수 증가 가능

---

## 에러 처리

| 상황 | 처리 방식 |
|---|---|
| 파일 경로 없음 / 접근 불가 | viewer.html에 에러 메시지 표시 |
| 지원하지 않는 파일 형식 | "지원하지 않는 파일 형식입니다" 안내 |
| LibreOffice 변환 실패 | "문서를 변환할 수 없습니다" + 원본 파일 다운로드 링크 |
| LibreOffice 데몬 다운 | 자동 재기동 1회, 실패 시 에러 페이지 |
| 변환 타임아웃 (기본 30초) | 타임아웃 안내 + 원본 다운로드 링크 |
| Directory traversal 시도 | 400 Bad Request, 허용 경로 외 접근 차단 |

---

## 보안

- `path` 파라미터: 절대경로 정규화 + `--allowed-paths` 화이트리스트 검증으로 directory traversal 방지
- LibreOffice 데몬 포트(2002)는 localhost only 바인딩
- HTTP 서버 포트(8090)는 내부 WAS에서만 접근하도록 방화벽 제한 권장

---

## 프론트엔드 뷰어

### viewer.html 동작

```
새 탭 열림 (/docviewer/view?path=...)
  └─ viewer.js: path 파싱 → 파일 타입 판별
       ├─ PDF / 변환 대상 → pdf.js 렌더러
       │     └─ PDFPage.getViewport({ scale }) : 컨테이너 너비 기준 동적 스케일 계산
       ├─ TXT → fetch → EUC-KR/UTF-8 디코딩 → <pre>
       └─ 이미지 → <img src="/docviewer/file?...">
```

### 뷰어 UI

```
┌─────────────────────────────────────────────┐
│  report.hwp                  [다운로드] [인쇄] │
├─────────────────────────────────────────────┤
│  페이지 < 1 / 24 >   [확대 −]  100%  [확대 +] │  ← PDF 전용
├─────────────────────────────────────────────┤
│                                             │
│              렌더링 영역                     │
│         (canvas / pre / img)                │
│                                             │
└─────────────────────────────────────────────┘
```

- 변환 중: 스피너 + "문서를 변환하는 중입니다..." 표시
- 반응형: 뷰어 창 너비에 맞게 PDF 자동 스케일 (A4 고정 문제 없음)

### TXT 인코딩 처리

```javascript
// EUC-KR 우선 시도, 실패 시 UTF-8 폴백
try {
  text = new TextDecoder('euc-kr').decode(bytes);
} catch {
  text = new TextDecoder('utf-8').decode(bytes);
}
```

---

## 기존 JSP 프로젝트 통합 방법

**① 스크립트 1줄 include** (head 또는 body 끝)
```html
<script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
```

**② 첨부파일 클릭 이벤트에 1줄**
```javascript
DocViewer.open('/upload/files/report.hwp');
```

`doc-viewer-client.js`가 서버 URL을 자체 포함하므로 JSP 코드에 URL 하드코딩 불필요.

---

## 설정 파라미터

```
--port=8090                          # HTTP 서버 포트 (기본 8090)
--libreoffice=/usr/lib/libreoffice   # LibreOffice 설치 경로 (필수)
--lo-port=2002                       # LibreOffice 소켓 포트 (기본 2002)
--lo-pool-size=1                     # LibreOffice 인스턴스 수 (기본 1)
--cache-ttl=86400                    # 캐시 TTL 초 (기본 86400 = 24시간)
--convert-timeout=30                 # 변환 타임아웃 초 (기본 30)
--allowed-paths=/upload,/home/files  # 파일 접근 허용 경로 (보안, 미설정 시 전체 허용)
```

properties 파일 (`doc-viewer.properties`)로도 동일하게 설정 가능.

---

## 배포 체크리스트

1. LibreOffice 설치: `apt install libreoffice` 또는 RPM 패키지
2. HWP 지원: LibreOffice 7.0+ (hwp), 7.6+ (hwpx)
3. 서버 기동: `java -jar doc-viewer.jar --libreoffice=/usr/lib/libreoffice --allowed-paths=/upload`
4. 방화벽: 8090 포트는 내부 WAS에서만 접근 가능하도록 제한
5. 프로세스 관리: systemd 또는 supervisor로 서버 재기동 설정 권장
