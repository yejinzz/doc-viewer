# doc-viewer v2 통합 가이드

기존 프로젝트에서 doc-viewer v2를 사용하는 방법을 설명합니다.

> **v1 → v2 핵심 변경사항**
> v1은 파일 경로(`?path=`)를 직접 브라우저에 전달했습니다.
> v2는 CMS 서버가 먼저 `/api/convert`를 호출해 **키를 등록**하고, 브라우저는 해당 키(`?key=`)로 뷰어를 엽니다.
> 이 구조 덕분에 파일 경로가 브라우저에 노출되지 않고, 접근 권한 제어가 서버 측에서 이루어집니다.

---

## 통합 전제 조건

1. doc-viewer 서버가 기동 중이어야 합니다 (`java -jar doc-viewer-1.0.0.jar ...`)
2. CMS WAS에서 doc-viewer 서버(기본 포트 8090)로 HTTP 요청이 가능해야 합니다
3. doc-viewer의 `--api-allowed-ips`에 CMS 서버 IP가 포함되어야 합니다 (기본: `127.0.0.1`)
4. CMS가 접근하는 파일 경로가 doc-viewer의 `--allowed-paths`에 포함되어야 합니다

---

## 통합 플로우

```
사용자 클릭 "미리보기"
  └─ CMS Controller (Java)
      ├─ DB에서 변환 여부 확인 (DOCVIEWER_CONVERT_YN)
      ├─ 미변환이면 → POST /docviewer/api/convert (동기 호출)
      │     body: { key, path, originalName, fileHash }
      │     응답: { "status":"ok", "key":"FILE_001_0" }
      ├─ DB 업데이트 (DOCVIEWER_CONVERT_YN = 'Y')
      └─ redirect → http://doc-viewer:8090/docviewer/view?key=FILE_001_0
```

---

## DB 마이그레이션 (CMS 측)

v2 통합을 위해 첨부파일 테이블에 변환 상태 컬럼을 추가합니다.

```sql
ALTER TABLE comtnfiledetail
  ADD COLUMN DOCVIEWER_CONVERT_YN   CHAR(1)     DEFAULT 'N',
  ADD COLUMN DOCVIEWER_CONVERT_DT   DATETIME    NULL,
  ADD COLUMN DOCVIEWER_FILE_HASH    VARCHAR(64) NULL;
```

---

## Java / Spring 서버사이드 통합

### "미리보기" 버튼 컨트롤러 예시 (Spring MVC)

```java
@GetMapping("/file/preview")
public String preview(
        @RequestParam String atchFileId,
        @RequestParam int fileSn,
        RedirectAttributes ra) throws Exception {

    FileDetailVo file = fileService.getFileDetail(atchFileId, fileSn);
    String key = atchFileId + "_" + fileSn;

    // 미변환이면 doc-viewer API 호출
    if (!"Y".equals(file.getDocviewerConvertYn())) {
        String json = String.format(
            "{\"key\":\"%s\",\"path\":\"%s\",\"originalName\":\"%s\",\"fileHash\":\"\"}",
            key,
            file.getFileStreCours() + file.getStreFileNm(),
            file.getOrignlFileNm().replace("\"", "\\\""));

        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:8090/docviewer/api/convert"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(120))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        fileService.updateDocviewerConvertYn(atchFileId, fileSn, "Y");
    }

    return "redirect:http://localhost:8090/docviewer/view?key=" + key;
}
```

> **key 형식**: `ATCH_FILE_ID + "_" + FILE_SN`
> 예: `FILE_000000000080Gi9_0`
> 허용 문자: `A-Z a-z 0-9 _ -` (1~100자)

---

## JSP 프론트엔드 통합

### Step 1 — 클라이언트 스크립트 include

JSP 페이지의 `<body>` 끝에 추가:

```html
<script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
```

> `localhost:8090`은 doc-viewer 서버 실제 주소/포트로 변경하세요.
> `doc-viewer-client.js`는 자신의 `src` 속성에서 서버 URL을 자동 추출하므로
> 이 URL 하나만 맞으면 나머지 API 호출도 같은 서버를 자동으로 바라봅니다.

### Step 2 — 미리보기 버튼

```javascript
// key = ATCH_FILE_ID + "_" + FILE_SN
DocViewer.open('FILE_000000000080Gi9_0');
```

> v2에서 `DocViewer.open()`은 **파일 경로가 아닌 키**를 받습니다.
> 파일 경로 노출 없이 브라우저에서 뷰어를 엽니다.

### JSP 파일 완성 예시

서버사이드에서 변환을 처리하고 JSP에서 키만 렌더링하는 패턴:

```jsp
<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>첨부파일 목록</title>
</head>
<body>

<table>
  <tr>
    <th>파일명</th>
    <th>미리보기</th>
  </tr>
  <c:forEach var="file" items="${attachList}">
    <tr>
      <td>${file.orignlFileNm}</td>
      <td>
        <%-- key = ATCH_FILE_ID + "_" + FILE_SN --%>
        <a href="#" onclick="DocViewer.open('${file.atchFileId}_${file.fileSn}'); return false;">
          미리보기
        </a>
      </td>
    </tr>
  </c:forEach>
</table>

<script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
</body>
</html>
```

> 이 패턴에서 미리보기 클릭 시 브라우저가 직접 `DocViewer.open(key)`를 호출하므로,
> 사전 변환이 완료된 키여야 합니다. 변환은 파일 업로드 시점 또는 최초 미리보기 클릭 시 서버사이드에서 처리하세요.

---

## Spring Boot (Thymeleaf) 통합

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
  <div th:each="file : ${files}">
    <%-- controller에서 key = atchFileId + "_" + fileSn 으로 계산해 모델에 전달 --%>
    <a href="#"
       th:onclick="|DocViewer.open('[[${file.key}]]'); return false;|"
       th:text="${file.orignlFileNm}"></a>
  </div>

  <script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
</body>
</html>
```

---

## 비 Java 프로젝트 통합

doc-viewer는 언어/프레임워크 무관하게 사용할 수 있습니다.
단, `/api/convert` 호출은 서버사이드에서 이루어져야 합니다 (API IP 화이트리스트 제한).

### 전체 흐름 (언어 무관)

1. **업로드/조회 시점** — 서버사이드에서 `POST http://doc-viewer:8090/docviewer/api/convert` 호출
2. **프론트엔드** — `DocViewer.open(key)` 또는 직접 URL 호출

### PHP (Laravel)

```php
// 서버사이드: 변환 등록
$key = $atchFileId . '_' . $fileSn;
if ($file->docviewer_convert_yn !== 'Y') {
    Http::timeout(120)->post('http://localhost:8090/docviewer/api/convert', [
        'key'          => $key,
        'path'         => $file->file_path,
        'originalName' => $file->original_name,
        'fileHash'     => '',
    ]);
    $file->update(['docviewer_convert_yn' => 'Y']);
}
```

```blade
{{-- Blade 템플릿: 프론트엔드 --}}
@foreach($files as $file)
  <a href="#" onclick="DocViewer.open('{{ $file->key }}'); return false;">
    {{ $file->original_name }}
  </a>
@endforeach

<script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
```

### Python (Django)

```python
# views.py: 변환 등록
import requests

def preview(request, atch_file_id, file_sn):
    key = f"{atch_file_id}_{file_sn}"
    file = FileDetail.objects.get(atch_file_id=atch_file_id, file_sn=file_sn)
    if file.docviewer_convert_yn != 'Y':
        requests.post('http://localhost:8090/docviewer/api/convert', json={
            'key': key,
            'path': file.file_path,
            'originalName': file.original_name,
            'fileHash': '',
        }, timeout=120)
        file.docviewer_convert_yn = 'Y'
        file.save()
    return redirect(f'http://localhost:8090/docviewer/view?key={key}')
```

---

## doc-viewer-client.js API

`doc-viewer-client.js`를 include하면 전역 `DocViewer` 객체가 등록됩니다.

### `DocViewer.open(key, options?)`

| 파라미터 | 타입 | 기본값 | 설명 |
|----------|------|--------|------|
| `key` | `string` | (필수) | 문서 키 (`ATCH_FILE_ID + "_" + FILE_SN`) |
| `options.target` | `string` | `'_blank'` | window.open 타겟 |

```javascript
// 새 탭에서 열기 (기본)
DocViewer.open('FILE_000000000080Gi9_0');

// 동일 탭에서 열기
DocViewer.open('FILE_000000000080Gi9_0', { target: '_self' });
```

---

## 직접 URL 호출 (스크립트 없이)

```javascript
// 순수 JavaScript
window.open(
  'http://localhost:8090/docviewer/view?key=' + encodeURIComponent('FILE_000000000080Gi9_0'),
  '_blank'
);
```

```jsp
<%-- JSP에서 직접 링크 --%>
<a href="http://localhost:8090/docviewer/view?key=${atchFileId}_${fileSn}"
   target="_blank">미리보기</a>
```

---

## 운영 환경 배포 체크리스트

- [ ] LibreOffice 설치 및 버전 확인 (`soffice --version`)
- [ ] HWP/HWPX 지원 시 `apt install libreoffice-h2orestart` 설치
- [ ] 설치 확인: `soffice --headless --convert-to pdf sample.hwp --outdir /tmp && ls /tmp/sample.pdf`
- [ ] 오픈소스 한글 폰트 설치: `apt-cache search fonts-nanum` 으로 패키지명 확인 후 설치 (Ubuntu 버전마다 다름)
- [ ] (권장) Windows 핵심 폰트 추가로 레이아웃 정확도 향상: `gulim.ttc`, `batang.ttc`, `dotum.ttc`, `malgun.ttf` → `/usr/share/fonts/truetype/` 복사 후 `fc-cache -fv`
- [ ] `--result-dir` 디렉터리 생성 및 쓰기 권한 확인
- [ ] `java -jar doc-viewer-1.0.0.jar --libreoffice=<경로> --allowed-paths=<업로드경로> --api-allowed-ips=<CMS서버IP>` 기동
- [ ] 헬스체크 확인: `curl http://<서버>:8090/docviewer/health`
- [ ] DB 마이그레이션: `comtnfiledetail`에 `DOCVIEWER_CONVERT_YN`, `DOCVIEWER_CONVERT_DT`, `DOCVIEWER_FILE_HASH` 컬럼 추가
- [ ] CMS 컨트롤러에 `/api/convert` 호출 로직 추가
- [ ] 방화벽: 8090 포트는 내부 WAS에서만 접근 가능하도록 제한
- [ ] systemd/supervisor 등 프로세스 관리 도구로 자동 재기동 설정
- [ ] JSP/HTML 템플릿에 `doc-viewer-client.js` include 추가 및 `DocViewer.open(key)` 수정

---

## 자주 묻는 질문

**Q. key는 어떻게 구성하나요?**

`ATCH_FILE_ID + "_" + FILE_SN` 형식입니다.
예: `FILE_000000000080Gi9` + `_` + `0` = `FILE_000000000080Gi9_0`
허용 문자는 `A-Z a-z 0-9 _ -` (1~100자)입니다.

**Q. 변환 전에 미리보기를 열면 어떻게 되나요?**

`/docviewer/view?key=`는 레지스트리에서 `convert_status = 'converted'`인 키만 서빙합니다.
미변환 키로 접근하면 404 에러 페이지가 표시됩니다.
반드시 `/api/convert` 성공 후 뷰어를 열어야 합니다.

**Q. 파일이 수정되면 자동으로 반영되나요?**

ViewHandler가 파일 접근 시 lastModified/size를 빠르게 체크하고 변경 감지 시 SHA-256을 재계산해 메타데이터를 업데이트합니다.
캐시 무효화는 자동으로 되지 않으므로, 파일 교체 후 강제 재변환이 필요하면 `POST /api/refresh?key={key}`를 호출하세요.

**Q. doc-viewer 서버와 CMS WAS가 다른 서버에 있어도 되나요?**

됩니다. `--api-allowed-ips`에 CMS 서버 IP를 추가하고, `doc-viewer-client.js`의 `src` URL을 실제 doc-viewer 서버 주소로 변경하면 됩니다.
단, 파일 경로는 doc-viewer 서버가 접근 가능한 경로여야 합니다 (NFS/공유 스토리지 등).

**Q. 같은 파일을 여러 번 등록하면 어떻게 되나요?**

`/api/convert`는 `INSERT OR REPLACE` 방식이라 같은 키로 재등록 시 상태가 `registered`로 초기화되고 재변환됩니다.
이미 변환된 파일을 불필요하게 재등록하지 않도록 CMS에서 `DOCVIEWER_CONVERT_YN` 플래그로 관리하세요.

**Q. 변환 속도가 느린데 개선할 수 있나요?**

`--lo-pool-size=2` 또는 `--lo-pool-size=3`으로 LibreOffice 인스턴스를 늘리면 동시 처리량이 올라갑니다.
단, 인스턴스 1개당 메모리 약 200~400MB 추가 사용합니다.
