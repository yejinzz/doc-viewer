# 로컬 테스트 환경 구성 가이드

## 사전 준비

### 1. JDK 11 확인

```bash
java -version
# 출력 예시: openjdk version "11.0.x"
```

JDK 11이 없다면:
- macOS: `brew install openjdk@11`
- Ubuntu/Debian: `sudo apt install openjdk-11-jdk`

---

### 2. Maven 확인

```bash
mvn -version
# 출력 예시: Apache Maven 3.x.x
```

없다면:
- macOS: `brew install maven`
- Ubuntu/Debian: `sudo apt install maven`

---

### 3. LibreOffice 설치

#### macOS

```bash
brew install --cask libreoffice
```

설치 후 경로: `/Applications/LibreOffice.app/Contents`

```bash
# 설치 확인
ls /Applications/LibreOffice.app/Contents/MacOS/soffice
```

#### Ubuntu/Debian

```bash
sudo apt install libreoffice
```

설치 후 경로: `/usr/lib/libreoffice`

```bash
# 설치 확인
ls /usr/lib/libreoffice/program/soffice
```

#### HWP 파일 지원 확인

| 형식 | 최소 LibreOffice 버전 |
|------|----------------------|
| .hwp | 7.0 이상 |
| .hwpx | 7.6 이상 |

```bash
soffice --version
# 출력 예시: LibreOffice 7.6.x.x
```

---

## fat-jar 빌드

프로젝트 루트에서 실행:

```bash
cd /path/to/doc-viewer
mvn clean package -DskipTests
```

빌드 성공 시 `target/doc-viewer-1.0.0.jar` 생성 (약 16MB, SQLite 드라이버 포함).

---

## 서버 기동

### macOS

```bash
java -jar target/doc-viewer-1.0.0.jar \
  --libreoffice=/Applications/LibreOffice.app/Contents \
  --port=8090 \
  --result-dir=/tmp/docviewer \
  --allowed-paths=/tmp/test-files
```

### Linux

```bash
java -jar target/doc-viewer-1.0.0.jar \
  --libreoffice=/usr/lib/libreoffice \
  --port=8090 \
  --result-dir=/tmp/docviewer \
  --allowed-paths=/tmp/test-files
```

기동 성공 시 콘솔 출력:

```
HH:mm:ss [main] INFO  com.docviewer.DocViewerServer - doc-viewer v2 started on http://localhost:8090/docviewer
HH:mm:ss [main] INFO  com.docviewer.DocViewerServer - Result dir: /tmp/docviewer
```

> `--allowed-paths`를 생략하면 전체 파일시스템에 접근 가능하며 `SECURITY WARNING`이 출력됩니다.
> 로컬 테스트 외 운영 환경에서는 반드시 지정하세요.

---

## 테스트 파일 준비

```bash
mkdir -p /tmp/test-files
# 테스트할 파일을 /tmp/test-files/ 에 복사
cp ~/Downloads/sample.hwp   /tmp/test-files/
cp ~/Downloads/report.docx  /tmp/test-files/
cp ~/Downloads/data.xlsx    /tmp/test-files/
cp ~/Downloads/slide.pptx   /tmp/test-files/
```

---

## Step 1: 파일 변환 등록 (API 호출)

운영 환경에서는 CMS 서버가 자동으로 호출하는 단계입니다.
로컬 테스트에서는 curl로 직접 시뮬레이션합니다.

```bash
# HWP 파일 등록 + 변환
curl -s -X POST http://localhost:8090/docviewer/api/convert \
  -H "Content-Type: application/json" \
  -d '{
    "key": "TEST_HWP_0",
    "path": "/tmp/test-files/sample.hwp",
    "originalName": "sample.hwp",
    "fileHash": ""
  }'
# 응답: {"status":"ok","key":"TEST_HWP_0"}
```

LibreOffice가 필요한 파일(HWP, DOCX, XLSX 등)은 첫 변환에 5~15초 걸립니다.
curl이 응답을 반환하면 변환 완료입니다. PDF, TXT, 이미지는 즉시 처리됩니다.

다른 파일도 같은 방식으로 등록합니다:

```bash
curl -s -X POST http://localhost:8090/docviewer/api/convert \
  -H "Content-Type: application/json" \
  -d '{"key":"TEST_DOCX_0","path":"/tmp/test-files/report.docx","originalName":"report.docx","fileHash":""}'

curl -s -X POST http://localhost:8090/docviewer/api/convert \
  -H "Content-Type: application/json" \
  -d '{"key":"TEST_PDF_0","path":"/tmp/test-files/document.pdf","originalName":"document.pdf","fileHash":""}'

curl -s -X POST http://localhost:8090/docviewer/api/convert \
  -H "Content-Type: application/json" \
  -d '{"key":"TEST_IMG_0","path":"/tmp/test-files/image.png","originalName":"image.png","fileHash":""}'
```

---

## Step 2: 변환 상태 확인

```bash
curl -s http://localhost:8090/docviewer/api/status/TEST_HWP_0
# 변환 완료: {"status":"ok","convertStatus":"converted"}
# 변환 중:   {"status":"ok","convertStatus":"registered"}
# 변환 실패: {"status":"ok","convertStatus":"error"}
```

---

## Step 3: 브라우저에서 뷰어 열기

변환 완료 후 브라우저 주소창에 입력:

```
# HWP 파일
http://localhost:8090/docviewer/view?key=TEST_HWP_0

# Word 파일
http://localhost:8090/docviewer/view?key=TEST_DOCX_0

# PDF 파일
http://localhost:8090/docviewer/view?key=TEST_PDF_0

# 이미지
http://localhost:8090/docviewer/view?key=TEST_IMG_0
```

---

## 기타 API 테스트

```bash
# 헬스체크
curl http://localhost:8090/docviewer/health
# 정상: {"status":"ok","libreoffice":true,"port":8090}
# LibreOffice 초기화 전: {"status":"ok","libreoffice":false,"port":8090}

# 강제 재변환 (파일 교체 후 캐시 갱신)
curl -s -X POST "http://localhost:8090/docviewer/api/refresh?key=TEST_HWP_0"
# 응답: {"status":"ok","key":"TEST_HWP_0"}

# 키 삭제
curl -s -X DELETE http://localhost:8090/docviewer/api/key/TEST_HWP_0
# 응답: {"status":"ok"}
```

---

## 간이 HTML 테스트 페이지 (JSP 통합 시뮬레이션)

Step 1~3으로 개별 파일 테스트가 충분하다면 이 섹션은 건너뛰어도 됩니다.
JSP에서 `DocViewer.open()` 버튼 클릭 동작 자체를 확인하고 싶을 때 사용합니다.

> Step 1의 curl 등록이 완료된 키만 뷰어가 열립니다. 미등록 키는 404 에러 페이지로 연결됩니다.

```html
<!DOCTYPE html>
<html lang="ko">
<head><meta charset="UTF-8"><title>doc-viewer v2 테스트</title></head>
<body>
  <h2>첨부파일 목록</h2>
  <ul>
    <li><a href="#" onclick="DocViewer.open('TEST_HWP_0')">sample.hwp</a></li>
    <li><a href="#" onclick="DocViewer.open('TEST_DOCX_0')">report.docx</a></li>
    <li><a href="#" onclick="DocViewer.open('TEST_PDF_0')">document.pdf</a></li>
    <li><a href="#" onclick="DocViewer.open('TEST_IMG_0')">image.png</a></li>
  </ul>

  <script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
</body>
</html>
```

> 로컬 HTML 파일(`file://`)에서 `localhost`로의 fetch는 브라우저 CORS 정책에 따라 차단될 수 있습니다.
> 이 경우 간단한 정적 서버로 서빙하세요:
>
> ```bash
> # Python 간이 서버 (test.html이 있는 디렉터리에서)
> python3 -m http.server 3000
> # 브라우저: http://localhost:3000/test.html
> ```

---

## 서버 중지

`Ctrl+C`로 종료하면 shutdown hook이 LibreOffice 데몬과 SQLite 연결을 자동으로 정리합니다.

---

## 문제 해결

| 증상 | 원인 | 해결 |
|------|------|------|
| `--libreoffice is required` 에러 | 필수 파라미터 누락 | `--libreoffice=<경로>` 추가 |
| `/api/convert` 403 응답 | API 호출 IP가 화이트리스트 밖 | `--api-allowed-ips`에 호출 IP 추가 |
| `/api/convert` 415 응답 | 허용되지 않은 확장자 | `--allowed-extensions`에 해당 확장자 추가 |
| `/api/convert` 413 응답 | 파일 크기 초과 | `--max-file-size` 값 늘리기 |
| `/api/convert` 404 응답 | path 경로 파일 없음 | 파일 경로 확인 |
| 뷰어 접근 시 404 응답 | 변환 미완료 키 | `/api/status/{key}` 확인 후 `converted` 상태일 때 열기 |
| HWP 변환 실패 | LibreOffice 버전 낮음 | 7.0 이상으로 업그레이드 |
| 변환 후 깨진 PDF | LibreOffice 한글 폰트 없음 | `apt install fonts-nanum` 또는 폰트 설치 |
| 403 Forbidden (파일 접근) | `--allowed-paths`에 경로 미포함 | 경로를 `--allowed-paths`에 추가 |
| `Address already in use` | 포트 충돌 | `--port=8091` 등 다른 포트 사용 |
| 페이지 로딩만 되고 안 열림 | LibreOffice 데몬 초기화 대기 | `/api/status`로 변환 완료 확인 후 재시도 |
