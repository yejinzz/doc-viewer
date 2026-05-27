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
which soffice
# 또는
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

빌드 성공 시 `target/doc-viewer-1.0.0.jar` 생성 (약 3.5MB).

---

## 서버 기동

### macOS

```bash
java -jar target/doc-viewer-1.0.0.jar \
  --libreoffice=/Applications/LibreOffice.app/Contents \
  --port=8090 \
  --allowed-paths=/tmp/test-files
```

### Linux

```bash
java -jar target/doc-viewer-1.0.0.jar \
  --libreoffice=/usr/lib/libreoffice \
  --port=8090 \
  --allowed-paths=/tmp/test-files
```

기동 성공 시 콘솔 출력:

```
HH:mm:ss [main] INFO  com.docviewer.DocViewerServer - doc-viewer started on http://localhost:8090/docviewer
```

---

## 브라우저 테스트

### 테스트 파일 준비

```bash
mkdir -p /tmp/test-files
# 테스트할 파일을 /tmp/test-files/ 에 복사
cp ~/Downloads/sample.hwp /tmp/test-files/
cp ~/Downloads/report.docx /tmp/test-files/
cp ~/Downloads/data.xlsx /tmp/test-files/
```

### 뷰어 직접 열기

브라우저 주소창에 입력:

```
# HWP 파일
http://localhost:8090/docviewer/view?path=/tmp/test-files/sample.hwp

# Word 파일
http://localhost:8090/docviewer/view?path=/tmp/test-files/report.docx

# PDF 파일
http://localhost:8090/docviewer/view?path=/tmp/test-files/document.pdf

# 이미지
http://localhost:8090/docviewer/view?path=/tmp/test-files/image.png

# TXT
http://localhost:8090/docviewer/view?path=/tmp/test-files/readme.txt
```

### 상태 확인 (헬스체크)

```bash
curl http://localhost:8090/docviewer/health
# 정상: {"status":"ok","libreoffice":true,"port":8090}
# LibreOffice 아직 초기화 중: {"status":"ok","libreoffice":false,"port":8090}
```

> LibreOffice 데몬은 첫 번째 변환 요청 시 초기화되어 약 5~10초 소요됩니다. 이후 요청부터는 빠르게 처리됩니다.

---

## 간이 HTML 테스트 페이지 (기존 JSP 통합 시뮬레이션)

아래 HTML 파일을 로컬에 저장(`test.html`)하고 브라우저에서 열면 `DocViewer.open()` 동작을 테스트할 수 있습니다:

```html
<!DOCTYPE html>
<html lang="ko">
<head><meta charset="UTF-8"><title>doc-viewer 테스트</title></head>
<body>
  <h2>첨부파일 목록</h2>
  <ul>
    <li><a href="#" onclick="DocViewer.open('/tmp/test-files/sample.hwp')">sample.hwp</a></li>
    <li><a href="#" onclick="DocViewer.open('/tmp/test-files/report.docx')">report.docx</a></li>
    <li><a href="#" onclick="DocViewer.open('/tmp/test-files/data.xlsx')">data.xlsx</a></li>
    <li><a href="#" onclick="DocViewer.open('/tmp/test-files/document.pdf')">document.pdf</a></li>
    <li><a href="#" onclick="DocViewer.open('/tmp/test-files/image.png')">image.png</a></li>
  </ul>

  <script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
</body>
</html>
```

> 로컬 HTML 파일(`file://`)에서 `localhost`로의 fetch는 브라우저 CORS 정책에 따라 차단될 수 있습니다.
> 이 경우 간단한 정적 서버로 서빙하거나 실제 JSP 서버 환경에서 테스트하세요.
>
> ```bash
> # Python 간이 서버 (test.html이 있는 디렉터리에서)
> python3 -m http.server 3000
> # 브라우저: http://localhost:3000/test.html
> ```

---

## 서버 중지

`Ctrl+C` 로 종료하면 shutdown hook이 LibreOffice 데몬을 자동으로 정리합니다.

---

## 문제 해결

| 증상 | 원인 | 해결 |
|------|------|------|
| `--libreoffice is required` 에러 | 필수 파라미터 누락 | `--libreoffice=<경로>` 추가 |
| HWP 변환 실패 | LibreOffice 버전 낮음 | 7.0 이상으로 업그레이드 |
| 변환 후 깨진 PDF | LibreOffice 한글 폰트 없음 | `apt install fonts-nanum` 또는 폰트 설치 |
| 403 Forbidden | `--allowed-paths` 에 해당 경로 미포함 | 경로를 `--allowed-paths` 에 추가 |
| 페이지가 로딩만 되고 안 열림 | LibreOffice 데몬 초기화 대기 | 5~10초 후 다시 시도 |
| `Address already in use` | 포트 충돌 | `--port=8091` 등 다른 포트 사용 |
