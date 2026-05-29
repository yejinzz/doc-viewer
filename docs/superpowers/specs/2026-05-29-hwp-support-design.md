# HWP/HWPX 지원 설계 스펙

**작성일**: 2026-05-29  
**상태**: 승인됨  
**관련 스펙**: `2026-05-28-doc-viewer-v2-design.md`

---

## 개요

LibreOffice는 HWP/HWPX(한컴오피스 파일)를 기본 지원하지 않는다. `libreoffice-h2orestart`(apt 패키지)를 설치하면 시스템 전체 익스텐션으로 등록되어 LibreOffice CLI에서 HWP 변환이 가능해진다.

그러나 현재 사용 중인 JODConverter는 격리된 user profile로 LibreOffice 데몬을 실행하므로, 시스템 익스텐션 인식 여부가 불확실하다. 따라서 HWP/HWPX에 한해 `soffice --headless` CLI 서브프로세스 방식으로 변환하고, 나머지 포맷은 기존 JODConverter 데몬을 유지하는 **Dispatching 방식**으로 구현한다.

---

## 배포 전제조건

온프레미스 리눅스 서버에 다음이 설치되어 있어야 한다:

```bash
apt install libreoffice libreoffice-h2orestart
```

`libreoffice-h2orestart`는 시스템 전체 LibreOffice 익스텐션으로 등록되어 `soffice --headless` 실행 시 자동으로 HWP 포맷을 인식한다.

---

## 아키텍처

### 컴포넌트 구조

```
DocumentConverter (interface — 변경 없음)
  ├── LibreOfficeConverter   (변경 없음 — JODConverter 데몬)
  ├── HwpCliConverter        ← 신규
  └── DispatchingConverter   ← 신규 (외부 진입점)
        ├── HWP/HWPX → HwpCliConverter
        └── 그 외    → LibreOfficeConverter

DocViewerServer              (수정 — DispatchingConverter로 조립)
```

`ApiHandler`, `ConversionCache`, `FileTypeDetector` 등 나머지 컴포넌트는 변경 없음. `DispatchingConverter`가 `DocumentConverter` 인터페이스를 구현하므로 외부에서 투명하게 교체된다.

### 변환 흐름

```
POST /docviewer/api/convert (hwp 파일)
  → ApiHandler → cache.getOrConvert(file, converter::convert)
  → DispatchingConverter.convert(source, dest)
      → 확장자 = hwp/hwpx
      → HwpCliConverter.convert(source, dest)
          1. 임시 출력 디렉토리 생성
          2. soffice --headless CLI 실행
          3. {tmpdir}/{filename}.pdf → dest 이동
          4. 임시 디렉토리 정리
  → 캐시 저장 → 응답
```

---

## 신규 클래스 상세

### HwpCliConverter

**역할**: `soffice --headless` 서브프로세스로 HWP/HWPX → PDF 변환

**soffice 경로 유도**: `DocViewerConfig.libreOfficePath + "/program/soffice"`. 별도 설정 항목 추가 없음.

**UserInstallation 분리**: JODConverter 데몬과 충돌 방지를 위해 별도 profile 디렉토리 사용.
- 경로: `{resultDir}/soffice-profile`
- CLI 인자: `-env:UserInstallation=file://{resultDir}/soffice-profile`

**실행 커맨드**:
```
{libreOfficePath}/program/soffice
  --headless
  -env:UserInstallation=file://{resultDir}/soffice-profile
  --convert-to pdf {source}
  --outdir {tmpdir}
```

**타임아웃**: `config.convertTimeoutSeconds` 재사용. 초과 시 `process.destroyForcibly()` 후 예외.

**에러 처리**:

| 상황 | 처리 |
|---|---|
| exit code != 0 | "soffice exited with code N" 예외 |
| 출력 파일 미생성 | "Conversion produced no output" 예외 |
| 타임아웃 초과 | process 강제 종료 후 "Conversion timed out" 예외 |

모든 예외는 기존 `ApiHandler`가 500으로 처리한다.

### DispatchingConverter

**역할**: 파일 확장자 기준으로 `HwpCliConverter` / `LibreOfficeConverter` 라우팅

```java
private static final Set<String> HWP_EXTS = Set.of("hwp", "hwpx");

convert(source, dest):
  ext = source.getName().toLowerCase().후미확장자
  if HWP_EXTS.contains(ext) → hwpCliConverter.convert(source, dest)
  else                       → libreOfficeConverter.convert(source, dest)

isAlive()  → libreOfficeConverter.isAlive()
shutdown() → libreOfficeConverter.shutdown()
```

---

## 동시성 처리

`soffice --headless`는 동일 UserInstallation을 두 프로세스가 동시에 사용할 수 없다. `HwpCliConverter`에 `Semaphore(1)`을 두어 HWP 변환을 직렬화한다.

```
HWP 요청 A → semaphore 획득 → 변환 중
HWP 요청 B → semaphore 대기 (convert.timeout 이내)
DOCX 요청 C → LibreOfficeConverter (데몬, 영향 없음)
```

Semaphore 대기도 `convertTimeoutSeconds` 제한을 적용한다. 대기 중 timeout 시 "Conversion queue timeout" 예외.

---

## 변경 파일 목록

```
신규:
  src/main/java/com/docviewer/converter/HwpCliConverter.java
  src/main/java/com/docviewer/converter/DispatchingConverter.java
  src/test/java/com/docviewer/converter/HwpCliConverterTest.java
  src/test/java/com/docviewer/converter/DispatchingConverterTest.java

수정:
  src/main/java/com/docviewer/DocViewerServer.java
    — LibreOfficeConverter 대신 DispatchingConverter 조립
```

---

## 테스트 전략

### DispatchingConverterTest (단위 테스트)

- Mock `LibreOfficeConverter`, Mock `HwpCliConverter` 주입
- hwp 확장자 → `hwpCliConverter.convert()` 호출 검증
- hwpx 확장자 → `hwpCliConverter.convert()` 호출 검증
- docx 확장자 → `libreOfficeConverter.convert()` 호출 검증
- `isAlive()` / `shutdown()` → `libreOfficeConverter`로 위임 검증

### HwpCliConverterTest (단위 테스트)

실제 LibreOffice 없이 검증 가능한 범위만:
- exit code != 0 시 예외 발생 검증 (ProcessBuilder mock)
- 출력 파일 미생성 시 예외 발생 검증
- 타임아웃 시 예외 발생 검증

실제 변환은 로컬 통합 테스트(LibreOffice + h2orestart 설치된 환경)에서 수동 확인.

---

## 배포 가이드 추가사항

기존 v2 배포 체크리스트에 다음 항목 추가:

```
1. LibreOffice 설치: apt install libreoffice
2. HWP 지원 익스텐션 설치: apt install libreoffice-h2orestart   ← 추가
3. 검증: soffice --headless --convert-to pdf sample.hwp --outdir /tmp
         → /tmp/sample.pdf 생성 확인
```
