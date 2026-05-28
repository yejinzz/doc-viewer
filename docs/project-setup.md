# doc-viewer v2 프로젝트 구조 및 설정 레퍼런스

## 프로젝트 개요

브라우저에서 첨부파일 클릭 한 번으로 새 탭에서 문서를 미리보는 독립 실행형 통합 뷰어.
CMS 서버가 `/docviewer/api/convert`로 파일을 등록·변환하고 키를 발급하면, 브라우저는 해당 키로 뷰어를 엽니다.
LibreOffice를 서버사이드 변환 엔진으로 사용하고, `java -jar` 한 줄로 기동합니다.

---

## 기술 스택

| 영역 | 선택 | 버전 |
|------|------|------|
| 런타임 | JDK | 11 |
| HTTP 서버 | `com.sun.net.httpserver` (JDK 내장) | - |
| LibreOffice 연동 | JodConverter | 4.4.2 |
| 키-경로 저장소 | SQLite (org.xerial:sqlite-jdbc) | 3.45.3.0 |
| 로깅 | SLF4J + Logback | 1.7.36 / 1.2.12 |
| 빌드 | Maven + maven-shade-plugin | 3.x / 3.4.1 |
| PDF 렌더링 | Mozilla pdf.js (UMD 빌드) | 4.2.67 |
| 프론트엔드 | Vanilla JS + HTML + CSS | - |

---

## 아키텍처 개요

```
CMS 서버
  └─ POST /docviewer/api/convert  ──→  doc-viewer
       { key, path, originalName }       └─ 파일 등록 + LibreOffice 변환
                                          └─ SQLite(docviewer.db)에 key→path 저장
                                          └─ resultDir/cache/ 에 PDF 캐싱
브라우저
  └─ GET /docviewer/view?key=xxx  ──→  doc-viewer
                                          └─ 레지스트리에서 경로 조회
                                          └─ viewer.html + DOCVIEWER_CONFIG JSON 반환
```

---

## 프로젝트 디렉터리 구조

```
doc-viewer/
├── pom.xml
├── docs/
│   ├── local-test-guide.md
│   ├── project-setup.md
│   └── integration-guide.md
└── src/
    ├── main/
    │   ├── java/com/docviewer/
    │   │   ├── DocViewerServer.java              # 진입점 (main + HTTP 서버 기동)
    │   │   ├── handler/
    │   │   │   ├── ApiHandler.java               # POST /docviewer/api/* (CMS 전용)
    │   │   │   ├── ViewHandler.java              # GET /docviewer/view?key=...
    │   │   │   ├── FileHandler.java              # GET /docviewer/file?id=... 또는 ?key=...
    │   │   │   └── StaticHandler.java            # GET /docviewer/static/**
    │   │   ├── registry/
    │   │   │   └── FileKeyRegistry.java          # SQLite key→path 레지스트리
    │   │   ├── security/
    │   │   │   ├── IpWhitelistFilter.java        # CIDR 기반 IP 화이트리스트
    │   │   │   └── LicenseChecker.java           # IP/도메인 기반 뷰어 접근 제어
    │   │   ├── converter/
    │   │   │   ├── DocumentConverter.java        # 변환 인터페이스
    │   │   │   └── LibreOfficeConverter.java     # JodConverter 래핑 구현체
    │   │   ├── cache/
    │   │   │   └── ConversionCache.java          # 변환 결과 PDF 캐싱 (resultDir/cache/)
    │   │   ├── detector/
    │   │   │   └── FileTypeDetector.java         # 확장자 기반 처리 방식 결정
    │   │   ├── util/
    │   │   │   └── HashUtil.java                 # SHA-256 파일/문자열 해시
    │   │   └── config/
    │   │       └── DocViewerConfig.java          # CLI args + properties 파일 파싱
    │   └── resources/
    │       ├── logback.xml
    │       └── static/
    │           ├── viewer.html                   # 뷰어 진입 페이지
    │           ├── viewer.js                     # 파일 타입 분기 + pdf.js 제어
    │           ├── viewer.css                    # 로딩 스피너 + 에러 UI 포함
    │           ├── doc-viewer-client.js          # 기존 프로젝트 통합용 스니펫
    │           └── lib/
    │               ├── pdf.min.js                # Mozilla pdf.js 4.2.67 UMD 빌드
    │               └── pdf.worker.min.js
    └── test/
        └── java/com/docviewer/
            ├── cache/ConversionCacheTest.java
            ├── config/DocViewerConfigTest.java
            ├── detector/FileTypeDetectorTest.java
            ├── handler/
            │   ├── ApiHandlerTest.java
            │   ├── FileHandlerTest.java
            │   ├── StaticHandlerTest.java
            │   └── ViewHandlerTest.java
            ├── registry/FileKeyRegistryTest.java
            └── security/
                ├── IpWhitelistFilterTest.java
                └── LicenseCheckerTest.java
```

---

## REST API

### CMS 전용 내부 API (`/docviewer/api/*`)

`--api-allowed-ips`에 등록된 IP에서만 호출 가능 (기본: `127.0.0.1`).

| Method | Path | 설명 |
|--------|------|------|
| POST | `/docviewer/api/convert` | 파일 등록 및 변환. key, path, originalName, fileHash 수신 |
| POST | `/docviewer/api/refresh?key={key}` | 기존 키 강제 재변환 |
| DELETE | `/docviewer/api/key/{key}` | 레지스트리에서 키 삭제 |
| GET | `/docviewer/api/status/{key}` | 변환 상태 조회 (`registered` / `converted` / `error`) |

**POST `/docviewer/api/convert` 요청 본문:**

```json
{
  "key": "FILE_000000000080Gi9_0",
  "path": "/app/upload/2024/file.hwp",
  "originalName": "보고서.hwp",
  "fileHash": ""
}
```

**응답:**

```json
{ "status": "ok", "key": "FILE_000000000080Gi9_0" }
```

### 브라우저 접근 API

| Method | Path | 설명 |
|--------|------|------|
| GET | `/docviewer/view?key={key}` | viewer.html 반환. 뷰어 진입 메인 엔드포인트 |
| GET | `/docviewer/file?id={cacheId}` | 변환된 PDF 스트리밍 |
| GET | `/docviewer/file?key={key}` | 원본 파일 스트리밍 (PDF/TXT/이미지용) |
| GET | `/docviewer/static/**` | viewer.js, pdf.js 등 정적 리소스 서빙 |
| GET | `/docviewer/health` | 서버 상태 + LibreOffice 데몬 상태 |

---

## 파일 타입별 처리 전략

| 파일 유형 | 처리 위치 | 방식 |
|-----------|-----------|------|
| PDF | 프론트 | pdf.js 직접 렌더링 |
| TXT | 프론트 | `<pre>` 태그, EUC-KR/UTF-8 자동 감지 |
| 이미지 (jpg/jpeg/png/gif/webp/bmp) | 프론트 | `<img>` 태그 |
| Word (.doc/.docx) | 서버 → LibreOffice | → PDF → pdf.js |
| 한글 (.hwp/.hwpx) | 서버 → LibreOffice | → PDF → pdf.js |
| 스프레드시트 (.xls/.xlsx/.ods) | 서버 → LibreOffice | → PDF → pdf.js |
| 프레젠테이션 (.ppt/.pptx/.odp) | 서버 → LibreOffice | → PDF → pdf.js |
| 기타 (.rtf/.odt 등) | 서버 → LibreOffice | → PDF → pdf.js |

---

## 설정 파라미터 전체 레퍼런스

CLI 인수 (`--key=value`) 또는 `doc-viewer.properties` 파일로 설정 가능합니다.
CLI 인수가 파일 설정보다 우선합니다.

### 기본 서버 설정

| 파라미터 | 기본값 | 필수 | 설명 |
|----------|--------|------|------|
| `--port` | `8090` | 아니오 | HTTP 서버 포트 |
| `--libreoffice` | 없음 | **예** | LibreOffice 설치 경로 |
| `--lo-port` | `2002` | 아니오 | LibreOffice JodConverter 소켓 포트 |
| `--lo-pool-size` | `1` | 아니오 | LibreOffice 인스턴스 수 |
| `--cache-ttl` | `86400` | 아니오 | 캐시 TTL (초, 기본 24시간) |
| `--convert-timeout` | `30` | 아니오 | LibreOffice 변환 타임아웃 (초) |
| `--result-dir` | `{tmpdir}/docviewer-output` | 아니오 | 변환 결과 및 DB 저장 디렉터리 |

### 보안 설정

| 파라미터 | 기본값 | 필수 | 설명 |
|----------|--------|------|------|
| `--allowed-paths` | 없음 (전체 허용) | **권장** | 파일 접근 허용 경로 목록 (콤마 구분) |
| `--max-file-size` | `104857600` (100MB) | 아니오 | 업로드 허용 최대 파일 크기 (바이트) |
| `--allowed-extensions` | 16개 기본 확장자 | 아니오 | 허용 확장자 목록 (콤마 구분) |
| `--api-allowed-ips` | `127.0.0.1` | 아니오 | `/api/*` 호출 허용 IP (CIDR 표기 가능) |
| `--license-allowed-ips` | 없음 (전체 허용) | 아니오 | 뷰어 접근 허용 IP (CIDR 표기 가능) |
| `--license-allowed-domains` | 없음 (전체 허용) | 아니오 | 뷰어 접근 허용 도메인 (Host 헤더 기준) |

> `--license-allowed-ips`와 `--license-allowed-domains` 둘 다 설정하지 않으면 모든 요청을 허용하며 시작 시 `SECURITY WARNING`이 출력됩니다.

### properties 파일 키 매핑

`doc-viewer.properties` 파일 또는 `--config=<경로>`로 로드 가능합니다.

| properties 키 | CLI 인수 |
|---------------|---------|
| `port` | `--port` |
| `libreoffice.path` | `--libreoffice` |
| `result.dir` | `--result-dir` |
| `max.file.size` | `--max-file-size` |
| `allowed.extensions` | `--allowed-extensions` |
| `allowed.paths` | `--allowed-paths` |
| `cache.ttl` | `--cache-ttl` |
| `convert.timeout` | `--convert-timeout` |
| `api.allowed-ips` | `--api-allowed-ips` |
| `license.allowed-ips` | `--license-allowed-ips` |
| `license.allowed-domains` | `--license-allowed-domains` |

### LibreOffice 경로

| OS | 경로 |
|----|------|
| macOS (brew cask) | `/Applications/LibreOffice.app/Contents` |
| Ubuntu/Debian | `/usr/lib/libreoffice` |
| RHEL/CentOS (RPM) | `/usr/lib64/libreoffice` 또는 `/opt/libreoffice7.x` |

### 기동 예시

```bash
# 개발 / 테스트
java -jar doc-viewer-1.0.0.jar \
  --libreoffice=/Applications/LibreOffice.app/Contents

# 운영 권장
java -jar doc-viewer-1.0.0.jar \
  --libreoffice=/usr/lib/libreoffice \
  --port=8090 \
  --result-dir=/var/docviewer \
  --lo-pool-size=2 \
  --cache-ttl=86400 \
  --convert-timeout=60 \
  --allowed-paths=/app/upload,/data/files \
  --api-allowed-ips=127.0.0.1,10.0.0.0/8 \
  --license-allowed-ips=192.168.0.0/16
```

```properties
# doc-viewer.properties 예시
libreoffice.path=/usr/lib/libreoffice
port=8090
result.dir=/var/docviewer
lo.pool-size=2
allowed.paths=/app/upload,/data/files
api.allowed-ips=127.0.0.1
license.allowed-domains=mycompany.com
```

---

## 데이터 저장소

### SQLite 레지스트리 (`docviewer.db`)

`--result-dir` 하위에 `docviewer.db`로 생성됩니다. 키 → 파일 경로 매핑을 영속 저장합니다.

| 컬럼 | 설명 |
|------|------|
| `key` (PK) | 문서 고유 키 (`ATCH_FILE_ID + "_" + FILE_SN`) |
| `file_path` | 서버 파일시스템 절대 경로 |
| `original_name` | 원본 파일명 (브라우저 표시용) |
| `file_hash` | SHA-256 (파일 변경 감지) |
| `file_size` | 파일 크기 (바이트) |
| `last_modified` | 파일 최종 수정 시각 (epoch ms) |
| `convert_status` | `registered` / `converted` / `error` |

### 캐시 디렉터리 (`resultDir/cache/`)

- **저장 위치**: `--result-dir` 하위 `cache/` 서브디렉터리
- **캐시 키**: `SHA-256(filePath + ":" + lastModified)` 앞 16자리 hex
- **파일 변경 감지**: lastModified/size 빠른 체크 → SHA-256 풀 체크 2단계
- **TTL 정리**: 서버 기동 시 `--cache-ttl` 이상 된 파일 자동 삭제
- **중복 변환 방지**: 동일 파일 동시 요청 시 첫 번째 변환 완료까지 대기 (`CompletableFuture` 기반)
- **강제 재변환**: `POST /api/refresh?key={key}` 호출 시 캐시 삭제 후 재변환

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
target/doc-viewer-1.0.0.jar          # 배포용 fat-jar (약 16MB, sqlite-jdbc 포함)
target/original-doc-viewer-1.0.0.jar  # shade 이전 원본 jar
```

---

## 보안 고려사항

1. **`--allowed-paths` 미설정 시 전체 파일시스템 접근 가능** — 운영 환경에서는 반드시 설정
2. **Path traversal 방지**: 파일 경로는 `normalize().toAbsolutePath()` + `startsWith()` 화이트리스트 검증
3. **심볼릭 링크 차단**: `Files.isSymbolicLink()` 체크로 링크 파일 접근 거부 (403)
4. **파일 크기 제한**: `--max-file-size` 초과 시 413 응답
5. **확장자 화이트리스트**: `--allowed-extensions` 외 확장자 415 응답
6. **API 엔드포인트**: `--api-allowed-ips` 화이트리스트 검증, 기본 `127.0.0.1`만 허용
7. **뷰어 접근 제어**: `--license-allowed-ips` / `--license-allowed-domains` 미설정 시 전체 허용 + SECURITY WARNING 출력
8. **키 형식 검증**: `KEY_PATTERN = ^[A-Za-z0-9_\-]{1,100}$` 위반 시 400 응답
9. **LibreOffice 소켓 포트**: localhost only 바인딩
10. **HTTP 응답 헤더**: 모든 응답에 `X-Content-Type-Options: nosniff` 포함

---

## 운영 환경 권장 구성

### systemd 서비스 등록 (Linux)

`/etc/systemd/system/doc-viewer.service`:

```ini
[Unit]
Description=doc-viewer v2 Document Preview Server
After=network.target

[Service]
Type=simple
User=docviewer
ExecStart=/usr/bin/java -jar /opt/doc-viewer/doc-viewer-1.0.0.jar \
  --libreoffice=/usr/lib/libreoffice \
  --port=8090 \
  --result-dir=/var/docviewer \
  --allowed-paths=/app/upload \
  --api-allowed-ips=127.0.0.1 \
  --license-allowed-ips=192.168.0.0/16 \
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
