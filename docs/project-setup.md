# doc-viewer 프로젝트 구조 및 설정 레퍼런스

## 프로젝트 개요

브라우저에서 첨부파일 클릭 한 번으로 새 탭에서 문서를 미리보는 독립 실행형 통합 뷰어.
LibreOffice를 서버사이드 변환 엔진으로 사용하고, `java -jar` 한 줄로 기동합니다.

---

## 기술 스택

| 영역 | 선택 | 버전 |
|------|------|------|
| 런타임 | JDK | 11 |
| HTTP 서버 | `com.sun.net.httpserver` (JDK 내장) | - |
| LibreOffice 연동 | JodConverter | 4.4.2 |
| 로깅 | SLF4J + Logback | 1.7.36 / 1.2.12 |
| 빌드 | Maven + maven-shade-plugin | 3.x / 3.4.1 |
| PDF 렌더링 | Mozilla pdf.js (UMD 빌드) | 3.11.174 |
| 프론트엔드 | Vanilla JS + HTML + CSS | - |

---

## 프로젝트 디렉터리 구조

```
doc-viewer/
├── pom.xml
├── docs/
│   ├── local-test-guide.md          # 로컬 테스트 환경 구성 및 기동 방법
│   ├── project-setup.md             # 이 파일 — 프로젝트 구조 및 설정 레퍼런스
│   └── integration-guide.md         # 기존 프로젝트 통합 방법
└── src/
    ├── main/
    │   ├── java/com/docviewer/
    │   │   ├── DocViewerServer.java            # 진입점 (main + HTTP 서버 기동)
    │   │   ├── handler/
    │   │   │   ├── ViewHandler.java            # GET /docviewer/view?path=...
    │   │   │   ├── FileHandler.java            # GET /docviewer/file?id=... 또는 ?path=...
    │   │   │   └── StaticHandler.java          # GET /docviewer/static/**
    │   │   ├── converter/
    │   │   │   ├── DocumentConverter.java      # 변환 인터페이스
    │   │   │   └── LibreOfficeConverter.java   # JodConverter 래핑 구현체
    │   │   ├── cache/
    │   │   │   └── ConversionCache.java        # 파일 변경 감지 + 임시 PDF 캐싱
    │   │   ├── detector/
    │   │   │   └── FileTypeDetector.java       # 확장자 기반 처리 방식 결정
    │   │   └── config/
    │   │       └── DocViewerConfig.java        # CLI args 파싱
    │   └── resources/
    │       ├── logback.xml
    │       └── static/
    │           ├── viewer.html                 # 뷰어 진입 페이지
    │           ├── viewer.js                   # 파일 타입 분기 + pdf.js 제어
    │           ├── viewer.css
    │           ├── doc-viewer-client.js        # 기존 프로젝트 통합용 스니펫
    │           └── lib/
    │               ├── pdf.min.js              # Mozilla pdf.js 3.11.174 UMD 빌드
    │               └── pdf.worker.min.js
    └── test/
        └── java/com/docviewer/
            ├── cache/ConversionCacheTest.java
            ├── config/DocViewerConfigTest.java
            ├── detector/FileTypeDetectorTest.java
            └── handler/
                ├── FileHandlerTest.java
                ├── StaticHandlerTest.java
                └── ViewHandlerTest.java
```

---

## REST API

| Method | Path | 설명 |
|--------|------|------|
| GET | `/docviewer/view?path={filePath}` | viewer.html 반환. 새 탭 진입 메인 엔드포인트 |
| GET | `/docviewer/file?id={cacheId}` | 변환된 PDF 스트리밍 |
| GET | `/docviewer/file?path={filePath}` | 원본 파일 스트리밍 (PDF/TXT/이미지용) |
| GET | `/docviewer/static/**` | viewer.js, pdf.js 등 정적 리소스 서빙 |
| GET | `/docviewer/health` | 서버 상태 + LibreOffice 데몬 상태 |

---

## 파일 타입별 처리 전략

| 파일 유형 | 처리 위치 | 방식 |
|-----------|-----------|------|
| PDF | 프론트 | pdf.js 직접 렌더링 |
| TXT | 프론트 | `<pre>` 태그, EUC-KR/UTF-8 자동 감지 |
| 이미지 (jpg/jpeg/png/gif/webp/bmp/svg) | 프론트 | `<img>` 태그 |
| Word (.doc/.docx) | 서버 → LibreOffice | → PDF → pdf.js |
| 한글 (.hwp/.hwpx) | 서버 → LibreOffice | → PDF → pdf.js |
| 스프레드시트 (.xls/.xlsx/.ods/.csv) | 서버 → LibreOffice | → PDF → pdf.js |
| 프레젠테이션 (.ppt/.pptx/.odp) | 서버 → LibreOffice | → PDF → pdf.js |
| 기타 (.rtf/.odt 등) | 서버 → LibreOffice | → PDF → pdf.js |

---

## 설정 파라미터 전체 레퍼런스

CLI 인수 (`--key=value`) 또는 `doc-viewer.properties` 파일로 설정 가능.

| 파라미터 | 기본값 | 필수 | 설명 |
|----------|--------|------|------|
| `--port` | `8090` | 아니오 | HTTP 서버 포트 |
| `--libreoffice` | 없음 | **예** | LibreOffice 설치 경로 |
| `--lo-port` | `2002` | 아니오 | LibreOffice JodConverter 소켓 포트 |
| `--lo-pool-size` | `1` | 아니오 | LibreOffice 인스턴스 수 |
| `--cache-ttl` | `86400` | 아니오 | 캐시 TTL (초, 기본 24시간) |
| `--convert-timeout` | `30` | 아니오 | LibreOffice 변환 타임아웃 (초) |
| `--allowed-paths` | 없음 (전체 허용) | 권장 | 접근 허용 경로 목록 (콤마 구분) |

### LibreOffice 경로

| OS | 경로 |
|----|------|
| macOS (brew cask) | `/Applications/LibreOffice.app/Contents` |
| Ubuntu/Debian | `/usr/lib/libreoffice` |
| RHEL/CentOS (RPM) | `/usr/lib64/libreoffice` 또는 `/opt/libreoffice7.x` |

### 기동 예시

```bash
# 개발 / 테스트 (allowed-paths 없이)
java -jar doc-viewer-1.0.0.jar \
  --libreoffice=/Applications/LibreOffice.app/Contents

# 운영 권장
java -jar doc-viewer-1.0.0.jar \
  --libreoffice=/usr/lib/libreoffice \
  --port=8090 \
  --lo-pool-size=2 \
  --cache-ttl=86400 \
  --convert-timeout=60 \
  --allowed-paths=/app/upload,/data/files
```

---

## 캐싱

- **저장 위치**: `java.io.tmpdir/docviewer-cache/` (기본 `/tmp/docviewer-cache/`)
- **캐시 키**: `SHA-256(filePath + ":" + lastModified)` 앞 16자리 hex
- **무효화**: 원본 파일 `lastModified` 변경 시 자동 재변환
- **TTL 정리**: 서버 기동 시 `--cache-ttl` 이상 된 파일 자동 삭제
- **중복 변환 방지**: 동일 파일 동시 요청 시 첫 번째 변환 완료까지 대기 (`CompletableFuture` 기반)
- **실패 시**: 변환 실패로 생성된 불완전한 캐시 파일 자동 삭제

---

## 빌드

```bash
# 전체 빌드 (테스트 포함)
mvn clean package

# 테스트 스킵
mvn clean package -DskipTests

# 테스트만 실행
mvn test

# 빌드 산출물
target/doc-viewer-1.0.0.jar          # 배포용 fat-jar (약 3.5MB)
target/original-doc-viewer-1.0.0.jar  # shade 이전 원본 jar
```

---

## 보안 고려사항

1. **`--allowed-paths` 미설정 시 전체 파일시스템 접근 가능** — 운영 환경에서는 반드시 설정
2. **Directory traversal 방지**: `path` 파라미터는 `normalize().toAbsolutePath()` + `startsWith()` 화이트리스트 검증
3. **LibreOffice 소켓 포트 (기본 2002)**: localhost only 바인딩
4. **HTTP 서버 포트 (기본 8090)**: 내부 네트워크 또는 방화벽으로 외부 접근 차단 권장
5. **보안 경고**: `allowed-paths` 미설정 시 시작 로그에 `SECURITY WARNING` 출력

---

## 운영 환경 권장 구성

### systemd 서비스 등록 (Linux)

`/etc/systemd/system/doc-viewer.service`:

```ini
[Unit]
Description=doc-viewer Document Preview Server
After=network.target

[Service]
Type=simple
User=docviewer
ExecStart=/usr/bin/java -jar /opt/doc-viewer/doc-viewer-1.0.0.jar \
  --libreoffice=/usr/lib/libreoffice \
  --port=8090 \
  --allowed-paths=/app/upload \
  --cache-ttl=86400
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable doc-viewer
sudo systemctl start doc-viewer
sudo systemctl status doc-viewer
```

### 폰트 설치 (Linux, 한글 깨짐 방지)

```bash
# Ubuntu/Debian
sudo apt install fonts-nanum fonts-nanum-coding

# RHEL/CentOS
sudo yum install google-noto-cjk-fonts
```
