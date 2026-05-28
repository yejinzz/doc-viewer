# doc-viewer v2 설계 스펙

**작성일**: 2026-05-28
**상태**: 승인됨
**이전 버전**: `2026-05-27-doc-viewer-design.md` (v1 — path 기반, 기본 보안)

---

## 개요

doc-viewer v1은 독립 실행형 HTTP 서버로 구현되어 있으며, `?path=` 파라미터로 파일시스템 경로를 직접 노출한다. v2는 다음을 목표로 한다:

- 파일시스템 경로 노출 제거 → 불투명한 key 기반 식별
- CMS(Spring/JSP 기반)와 server-to-server API 통합
- 상용화 수준 보안: 확장자 화이트리스트, 파일 크기 제한, 입력값 검증, IP 화이트리스트
- 설정 파일 기반 라이선스 제어 (별도 키 발급 없음)
- 10만 건 이상 장기 운영을 위한 SQLite 기반 키 레지스트리
- 파일 수정 감지(해시 체크) 및 자동 재변환
- 개선된 로딩/에러/예외 UI

---

## 기술 스택 변경사항

| 영역 | v1 | v2 |
|---|---|---|
| 키 레지스트리 | 없음 (path 직접 사용) | SQLite (`sqlite-jdbc`) |
| 보안 | allowed-paths 화이트리스트 | IP 화이트리스트 + 확장자 + 크기 + 입력값 검증 |
| 라이선스 | 없음 | 설정 파일 기반 IP/도메인 제한 |
| 설정 | CLI args / properties | properties 파일 우선, CLI로 override |
| 파일 식별 | `?path=` | `?key=` (CMS composite PK 기반) |

나머지 스택(JDK 11, JodConverter, SLF4J, Maven fat-jar, pdf.js)은 동일하게 유지.

---

## 아키텍처

### 전체 흐름

```
[브라우저]                        [CMS WAS (Spring/JSP)]
  "문서보기" 버튼 클릭
    → CMS 서버 호출
                                  POST /docviewer/api/convert
                                  {key, path, originalName, fileHash}
                                        ↓ IP 화이트리스트 검증
                                  [doc-viewer 서버 :8090]
                                    1. 확장자 / 크기 검증
                                    2. SQLite에 key→path 저장
                                    3. LibreOffice 변환 (동기)
                                    4. fileHash 저장
                                        ↓ {"status":"ok"}
                                  CMS: DOCVIEWER_CONVERT_YN='Y' UPDATE
                                  CMS: 뷰어 URL 반환

  새 탭 열림
  GET /docviewer/view?key=FILE_xxx_0
                                        ↓
                                  SQLite: key → path 조회
                                  해시 빠른 체크 (lastModified + fileSize)
                                  → 변경 없음: 캐시된 PDF 즉시 제공
                                  → 변경 감지: SHA-256 재계산 → 불일치 시 재변환
                                  viewer.html 반환

  pdf.js 렌더링 ←── GET /docviewer/file?id={cacheId}
```

### 파일 키 체계

- **키 형식**: `{ATCH_FILE_ID}_{FILE_SN}` (예: `FILE_000000000080Gi9_0`)
- CMS `comtnfiledetail` 테이블의 복합 PK를 그대로 활용 → 별도 UUID 컬럼 불필요
- doc-viewer 내부에 SQLite DB(`docviewer.db`)로 key → {path, originalName, fileHash, convertedAt} 영속 저장
- 10만 건 이상 + 10년 이상 운영 대응 (인덱스 기반 O(log n) 조회)

---

## CMS 테이블 변경

`comtnfiledetail`에 3개 컬럼 추가:

```sql
ALTER TABLE comtnfiledetail
  ADD COLUMN DOCVIEWER_CONVERT_YN   CHAR(1)      DEFAULT 'N' COMMENT '문서뷰어변환여부(Y/N)',
  ADD COLUMN DOCVIEWER_CONVERT_DT   DATETIME     NULL        COMMENT '문서뷰어변환일시',
  ADD COLUMN DOCVIEWER_FILE_HASH    VARCHAR(64)  NULL        COMMENT '원본파일해시(SHA-256)';
```

---

## REST API

### 공개 API (브라우저 → doc-viewer)

| Method | Path | 설명 |
|---|---|---|
| GET | `/docviewer/view?key={key}` | viewer.html 반환. 새 탭 진입 메인 엔드포인트 |
| GET | `/docviewer/file?id={cacheId}` | 변환된 PDF 또는 원본 파일 스트리밍 |
| GET | `/docviewer/static/**` | viewer.js, pdf.js 등 정적 리소스 |
| GET | `/docviewer/health` | 서버 상태 + LibreOffice 데몬 상태 |

### 내부 API (CMS 서버 → doc-viewer, IP 화이트리스트 적용)

| Method | Path | 설명 |
|---|---|---|
| POST | `/docviewer/api/convert` | 파일 등록 + LibreOffice 변환 (동기) |
| POST | `/docviewer/api/refresh?key={key}` | 파일 수정 후 캐시 무효화 + 강제 재변환 |
| DELETE | `/docviewer/api/key/{key}` | 파일 삭제 시 키 + 캐시 정리 |
| GET | `/docviewer/api/status/{key}` | 변환 상태 조회 (`registered / converted / error`) |

#### POST /docviewer/api/convert

Request body (JSON):
```json
{
  "key": "FILE_000000000080Gi9_0",
  "path": "/data/webapp/uploads/LV_202503121047128860.XLSX",
  "originalName": "보고서.xlsx",
  "fileHash": "a3f1c2d4e5..."
}
```

Response:
```json
{"status": "ok", "key": "FILE_000000000080Gi9_0"}
```

오류 시:
```json
{"status": "error", "code": 413, "message": "파일 크기가 너무 큽니다 (최대 100MB)"}
```

---

## 보안

### 계층 구조

```
요청 진입
  ↓
1. 라이선스 검증 (IP/도메인 허용 목록)
  ↓
2. 내부 API 전용 IP 화이트리스트 (/docviewer/api/*)
  ↓
3. 입력값 검증 (key 패턴, cacheId 패턴)
  ↓
4. 파일 검증 (확장자, 크기, 심볼릭 링크, path traversal)
  ↓
5. 실제 처리
```

### 상세 규칙

| 검증 항목 | 규칙 |
|---|---|
| 라이선스 IP 허용 | `license.allowed-ips` CIDR 지원. 미설정 시 전체 허용 + 경고 |
| 라이선스 도메인 허용 | `license.allowed-domains` Host 헤더 검증. 미설정 시 생략 |
| 내부 API IP | `api.allowed-ips` 기본 `127.0.0.1`. 미설정 시 localhost만 허용 |
| 확장자 | `allowed.extensions` 화이트리스트. 미포함 시 415 |
| 파일 크기 | `max.file.size` 기본 100MB. 초과 시 413 |
| key 파라미터 | `^[A-Za-z0-9_\-]{1,100}$` 패턴만 허용 |
| cacheId 파라미터 | hex 문자 16자만 허용 |
| Path Traversal | 경로 정규화 + `allowed.paths` 검증 |
| 심볼릭 링크 | `Files.isSymbolicLink()` 체크 → 403 |
| 응답 헤더 | `X-Content-Type-Options: nosniff` 추가 |

---

## 파일 수정 감지 (해시 체크)

파일 수정 시나리오: 관리자가 동일 파일명으로 원본을 교체한 경우.

### 2단계 감지 전략

```
GET /docviewer/view?key=xxx 요청 시:
  Step 1 (빠른 체크):
    SQLite 저장값: {lastModified, fileSize}
    현재 파일 값 비교
    → 동일: 캐시 즉시 사용

  Step 2 (변경 감지 시):
    SHA-256(file content) 전체 재계산
    SQLite 저장 fileHash와 비교
    → 일치: lastModified만 바뀐 경우 (무시)
    → 불일치: 캐시 무효화 + 재변환 + DB 업데이트
```

### CMS 명시적 갱신 (권장)

파일 교체 후 CMS 서버에서 호출:
```
POST /docviewer/api/refresh?key=FILE_xxx_0
```
→ 즉시 캐시 삭제 + 재변환 + SQLite 해시 업데이트

자동 감지(2단계)는 fallback으로 동작하므로 CMS에서 명시적 refresh를 호출하지 않아도 다음 조회 시 자동으로 처리됨.

---

## 설정 파일

`doc-viewer.properties` (JAR과 동일 디렉토리 기본, `--config=<경로>`로 override):

```properties
# 서버
port=8090

# LibreOffice
libreoffice.path=/usr/lib/libreoffice
libreoffice.port=2002
libreoffice.pool-size=1

# 파일 저장 (변환 결과 및 캐시, SQLite DB 위치)
result.dir=/var/docviewer/converted
cache.ttl=86400
convert.timeout=60

# 보안
allowed.paths=/data/webapp/uploads
allowed.extensions=pdf,hwp,hwpx,doc,docx,xls,xlsx,ppt,pptx,txt,jpg,jpeg,png,gif,bmp,webp
max.file.size=104857600

# 라이선스 (미설정 시 전체 허용 + 경고)
license.allowed-ips=127.0.0.1
license.allowed-domains=

# 내부 API 화이트리스트 (미설정 시 localhost만 허용)
api.allowed-ips=127.0.0.1
```

CLI 인자가 properties보다 우선 적용됨. 기동 예:
```bash
java -jar doc-viewer.jar --config=/etc/docviewer/doc-viewer.properties
```

---

## SQLite 키 레지스트리

저장 위치: `{result.dir}/docviewer.db`

스키마:
```sql
CREATE TABLE IF NOT EXISTS file_keys (
    key             TEXT PRIMARY KEY,
    file_path       TEXT NOT NULL,
    original_name   TEXT,
    file_hash       TEXT,
    file_size       INTEGER,
    last_modified   INTEGER,
    converted_at    DATETIME,
    convert_status  TEXT DEFAULT 'registered',  -- registered / converted / error
    error_message   TEXT
);

CREATE INDEX IF NOT EXISTS idx_convert_status ON file_keys(convert_status);
```

의존성 추가 (`pom.xml`):
```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.45.3.0</version>
</dependency>
```

---

## 프로젝트 구조 변경

```
doc-viewer/
├── src/main/java/com/docviewer/
│   ├── DocViewerServer.java              # 수정: v2 컴포넌트 조립
│   ├── config/
│   │   └── DocViewerConfig.java          # 수정: 새 설정 항목 추가
│   ├── security/
│   │   ├── IpWhitelistFilter.java        # 신규: IP/CIDR 검증
│   │   └── LicenseChecker.java           # 신규: 라이선스 허용 목록 검증
│   ├── registry/
│   │   └── FileKeyRegistry.java          # 신규: SQLite key-path 매핑 CRUD
│   ├── handler/
│   │   ├── ViewHandler.java              # 수정: ?path → ?key, 해시 체크
│   │   ├── FileHandler.java              # 수정: 입력값 검증 강화
│   │   ├── ApiHandler.java               # 신규: /api/convert, /api/refresh, /api/key DELETE, /api/status
│   │   └── StaticHandler.java            # 유지
│   ├── converter/
│   │   ├── DocumentConverter.java        # 유지
│   │   └── LibreOfficeConverter.java     # 유지
│   ├── cache/
│   │   └── ConversionCache.java          # 수정: result.dir 경로 사용
│   └── detector/
│       └── FileTypeDetector.java         # 수정: 확장자 설정 기반으로 동적 로드
├── src/main/resources/
│   ├── static/
│   │   ├── viewer.html                   # 수정: 에러/로딩 UI 개선
│   │   ├── viewer.js                     # 수정: 에러 케이스 처리
│   │   ├── viewer.css                    # 수정: 에러/로딩 스타일
│   │   ├── doc-viewer-client.js          # 수정: key 기반으로 변경
│   │   └── lib/                          # 유지
│   └── logback.xml                       # 유지
└── docs/
    └── superpowers/specs/
        └── 2026-05-28-doc-viewer-v2-design.md
```

---

## UI 개선

### 상태별 화면

**로딩** (변환 중 / 파일 로딩 중)
```
┌────────────────────────────────────────────┐
│  report.hwp                    [다운로드]  │
├────────────────────────────────────────────┤
│                                            │
│         ⟳  (CSS 스피너 애니메이션)          │
│     문서를 변환하는 중입니다...             │
│     잠시만 기다려주세요.                   │
│                                            │
└────────────────────────────────────────────┘
```

**에러**
```
┌────────────────────────────────────────────┐
│  report.hwp                    [다운로드]  │
├────────────────────────────────────────────┤
│                                            │
│         ✕  (에러 아이콘)                   │
│     [에러 메시지]                          │
│                                            │
│         [원본 파일 다운로드]               │
│                                            │
└────────────────────────────────────────────┘
```

### 에러 코드 및 메시지

| 상황 | HTTP | 메시지 |
|---|---|---|
| key 없음 / 잘못된 형식 | 400 | "잘못된 요청입니다" |
| key 미등록 | 404 | "등록되지 않은 문서입니다. CMS에서 변환을 먼저 진행해주세요." |
| 파일 미존재 | 404 | "파일을 찾을 수 없습니다" + 원본 다운로드 링크 |
| 파일 크기 초과 | 413 | "파일 크기가 너무 큽니다 (최대 100MB)" |
| 미지원 확장자 | 415 | "지원하지 않는 파일 형식입니다. (지원: PDF, HWP, Word, Excel, PPT, 이미지)" |
| 변환 실패 | 500 | "문서를 변환할 수 없습니다" + 원본 다운로드 링크 |
| 라이선스 거부 | 403 | "접근이 허용되지 않습니다" |
| 변환 타임아웃 | 504 | "변환 시간이 초과되었습니다 (최대 60초)" + 원본 다운로드 링크 |

---

## CMS 서버사이드 통합 흐름

```java
// CMS 서버사이드 (Java/Spring) — "문서보기" 버튼 핸들러
String key = atchFileId + "_" + fileSn;
String fullPath = fileStreCours + streFileNm;
String convertYn = fileDetail.getDocviewerConvertYn();

if (!"Y".equals(convertYn)) {
    // 원본 파일 SHA-256 계산
    String fileHash = Sha256Util.hash(new File(fullPath));

    // doc-viewer API 동기 호출
    DocViewerApiClient.convert(key, fullPath, orignlFileNm, fileHash);

    // DB 업데이트
    fileDao.updateDocviewerConvert(atchFileId, fileSn, "Y", fileHash);
}

// 새 창 URL 반환
return "/docviewer/view?key=" + key;
```

**타임아웃 처리**: `convert.timeout`(기본 60초)은 LibreOffice 변환 제한 시간. CMS HTTP 클라이언트는 이보다 여유있게 (예: 120초) 설정 권장. 타임아웃 시 doc-viewer는 504, CMS는 사용자에게 "변환 실패" 안내.

doc-viewer-client.js (기존 프로젝트 통합 스니펫)도 key 기반으로 업데이트:
```javascript
// CMS에서 제공하는 key를 사용
DocViewer.openByKey('FILE_000000000080Gi9_0');
```

또는 기존 path 기반 API는 내부 API(서버사이드)로만 사용하고, 브라우저 노출은 key만 사용.

---

## 캐싱 전략 (변경사항)

- 캐시 저장 위치: `{result.dir}/cache/` (v1의 `java.io.tmpdir` → 설정 파일로 변경)
- 캐시 키: `SHA-256(filePath + lastModified)` (기존 유지)
- 해시 체크 자동 재변환 추가 (신규)
- SQLite DB 파일 위치: `{result.dir}/docviewer.db`

---

## 배포 체크리스트

1. LibreOffice 설치: `apt install libreoffice` (hwpx는 7.6+)
2. `doc-viewer.properties` 작성 (`result.dir`, `libreoffice.path`, `allowed.paths`, `license.allowed-ips` 설정)
3. result.dir 디렉토리 생성 및 쓰기 권한 부여
4. 기동: `java -jar doc-viewer.jar --config=/etc/docviewer/doc-viewer.properties`
5. 헬스체크: `curl http://localhost:8090/docviewer/health`
6. CMS DB 마이그레이션: `DOCVIEWER_CONVERT_YN`, `DOCVIEWER_CONVERT_DT`, `DOCVIEWER_FILE_HASH` 컬럼 추가
7. 방화벽: 8090 포트는 내부 WAS에서만 접근 가능하도록 제한
8. systemd/supervisor: 프로세스 자동 재기동 설정
