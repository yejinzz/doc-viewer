# doc-viewer 통합 가이드

기존 프로젝트에서 doc-viewer를 사용하는 방법을 설명합니다.

---

## 통합 전제 조건

1. doc-viewer 서버가 기동 중이어야 합니다 (`java -jar doc-viewer-1.0.0.jar ...`)
2. 기존 프로젝트의 WAS에서 doc-viewer 서버(기본 포트 8090)로 네트워크 접근이 가능해야 합니다
3. 기존 프로젝트가 접근하는 파일 경로가 doc-viewer의 `--allowed-paths` 에 포함되어야 합니다

---

## JSP 프로젝트 통합 (2줄 통합)

### Step 1 — 클라이언트 스크립트 include

JSP 페이지의 `<head>` 또는 `<body>` 끝에 추가:

```html
<script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
```

> `localhost:8090`은 doc-viewer 서버 주소/포트로 변경하세요.
> `doc-viewer-client.js`는 자신의 `src` 속성에서 서버 URL을 자동으로 추출하므로,
> 이 스크립트 URL만 맞게 바꾸면 나머지 API 호출도 자동으로 같은 서버를 바라봅니다.

### Step 2 — 첨부파일 클릭 이벤트에 호출

```javascript
DocViewer.open('/upload/files/report.hwp');
```

기본으로 새 탭(`_blank`)에서 열립니다. 타겟 변경이 필요하면:

```javascript
DocViewer.open('/upload/files/report.hwp', { target: '_blank' });
```

### JSP 파일 예시

```jsp
<%@ page contentType="text/html; charset=UTF-8" %>
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
      <td>${file.originalName}</td>
      <td>
        <a href="#" onclick="DocViewer.open('${file.savePath}'); return false;">
          미리보기
        </a>
      </td>
    </tr>
  </c:forEach>
</table>

<!-- doc-viewer 클라이언트 스크립트 (body 끝에 include) -->
<script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>

</body>
</html>
```

---

## Java 프로젝트 통합 (Spring, 전통 Servlet 등)

doc-viewer는 독립 실행형 서버이므로 Java 프로젝트의 빌드/런타임 의존성이 **없습니다**.
기존 Java WAS 옆에 doc-viewer 서버를 별도 프로세스로 띄우고, 프론트에서 `doc-viewer-client.js`를 로드하는 것이 전부입니다.

### Spring Boot 프로젝트 예시

**Thymeleaf 템플릿:**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
  <div th:each="file : ${files}">
    <a href="#" th:onclick="|DocViewer.open('${file.path}'); return false;|"
       th:text="${file.name}"></a>
  </div>

  <script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
</body>
</html>
```

**Spring MVC Controller (경로 파라미터 예시):**

```java
@GetMapping("/files")
public String listFiles(Model model) {
    List<FileInfo> files = fileService.getFiles();
    model.addAttribute("files", files);
    return "file-list";
}
```

> 별도 의존성 추가 없음. 서버 쪽은 기존 코드 변경 없이 프론트 템플릿에 스크립트 1줄만 추가합니다.

---

## 전통 Servlet / JSP (Maven WAR) 프로젝트 통합

`pom.xml`이나 `web.xml` 변경 없이 JSP 파일에만 스크립트 include 추가.

**공통 레이아웃 JSP (`layout/common.jsp`)에 추가하면 전체 적용:**

```jsp
<%-- body 닫는 태그 바로 위 --%>
<script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
</body>
```

---

## 비 Java 프로젝트 통합 (PHP, Python, Node.js 등)

doc-viewer는 언어/프레임워크 무관하게 사용 가능합니다.
HTTP 서버이므로 어떤 환경이든 클라이언트 스크립트 1줄 include로 통합됩니다.

### PHP (Laravel Blade)

```blade
{{-- resources/views/files/index.blade.php --}}
@foreach($files as $file)
  <a href="#" onclick="DocViewer.open('{{ $file->path }}'); return false;">
    {{ $file->name }}
  </a>
@endforeach

<script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
```

### Python (Django Template)

```html
{% for file in files %}
  <a href="#" onclick="DocViewer.open('{{ file.path }}'); return false;">
    {{ file.name }}
  </a>
{% endfor %}

<script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
```

### Node.js (Express + EJS)

```html
<% files.forEach(function(file) { %>
  <a href="#" onclick="DocViewer.open('<%= file.path %>'); return false;">
    <%= file.name %>
  </a>
<% }) %>

<script src="http://localhost:8090/docviewer/static/doc-viewer-client.js"></script>
```

---

## doc-viewer-client.js API

`doc-viewer-client.js`를 include하면 전역 `DocViewer` 객체가 등록됩니다.

### `DocViewer.open(filePath, options?)`

| 파라미터 | 타입 | 기본값 | 설명 |
|----------|------|--------|------|
| `filePath` | `string` | (필수) | 서버 파일시스템의 절대 경로 |
| `options.target` | `string` | `'_blank'` | window.open 타겟 |

```javascript
// 새 탭에서 열기 (기본)
DocViewer.open('/upload/files/report.hwp');

// 동일 탭에서 열기
DocViewer.open('/upload/files/report.hwp', { target: '_self' });

// 지정 이름의 탭/창에서 열기
DocViewer.open('/upload/files/report.hwp', { target: 'previewWindow' });
```

---

## 직접 URL 호출 (스크립트 없이)

`doc-viewer-client.js` 없이 직접 URL을 구성해 새 탭을 열 수도 있습니다:

```javascript
// 순수 JavaScript
window.open(
  'http://localhost:8090/docviewer/view?path=' + encodeURIComponent('/upload/files/report.hwp'),
  '_blank'
);
```

```jsp
<%-- JSP에서 직접 링크 --%>
<a href="http://localhost:8090/docviewer/view?path=${fn:escapeXml(file.path)}"
   target="_blank">미리보기</a>
```

---

## 운영 환경 배포 체크리스트

- [ ] LibreOffice 설치 및 버전 확인 (`soffice --version`)
- [ ] HWP 지원 필요 시 LibreOffice 7.0+, HWPX는 7.6+ 확인
- [ ] Linux 서버 한글 폰트 설치 (`fonts-nanum` 등)
- [ ] `java -jar doc-viewer-1.0.0.jar --libreoffice=<경로> --allowed-paths=<업로드경로>` 기동
- [ ] 헬스체크 확인: `curl http://<서버>:8090/docviewer/health`
- [ ] 방화벽: 8090 포트는 내부 WAS에서만 접근 가능하도록 제한
- [ ] systemd/supervisor 등 프로세스 관리 도구로 자동 재기동 설정
- [ ] JSP/HTML 템플릿에 `doc-viewer-client.js` include 추가
- [ ] 첨부파일 경로가 `--allowed-paths`에 포함되어 있는지 확인

---

## 자주 묻는 질문

**Q. 파일 경로를 어떻게 전달해야 하나요?**

서버 파일시스템의 절대 경로를 전달합니다. 웹 URL이 아닙니다.
예: `/upload/files/report.hwp` (서버의 실제 파일 경로)

**Q. doc-viewer 서버와 기존 WAS가 다른 서버(IP)에 있어도 되나요?**

됩니다. `doc-viewer-client.js`의 `src` URL만 실제 doc-viewer 서버 주소로 변경하면 됩니다.
단, 파일 경로는 doc-viewer 서버가 접근 가능한 경로여야 합니다 (NFS/공유 스토리지 등).

**Q. 같은 포트에 여러 doc-viewer를 띄울 수 있나요?**

포트가 겹치면 안 됩니다. `--port`와 `--lo-port`를 다르게 설정하면 여러 인스턴스 실행 가능합니다.

**Q. 변환 속도가 느린데 개선할 수 있나요?**

`--lo-pool-size=2` 또는 `--lo-pool-size=3`으로 LibreOffice 인스턴스를 늘리면 동시 처리량이 올라갑니다.
단, 인스턴스 1개당 메모리 약 200~400MB 추가 사용합니다.
