# HWP/HWPX Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** HWP/HWPX 파일을 `soffice --headless` CLI 서브프로세스로 변환하는 `HwpCliConverter`와, 확장자 기준으로 converter를 라우팅하는 `DispatchingConverter`를 추가한다.

**Architecture:** `DispatchingConverter`가 `DocumentConverter` 인터페이스를 구현하고, 내부적으로 HWP/HWPX는 `HwpCliConverter`(CLI 서브프로세스), 나머지는 기존 `LibreOfficeConverter`(JODConverter 데몬)로 라우팅한다. `DocViewerServer`에서 조립만 바꾸면 되므로 핸들러·캐시·레지스트리 등 나머지 코드는 전혀 건드리지 않는다.

**Tech Stack:** Java 11, JUnit 5, Maven, bash 임시 스크립트(테스트용 fake soffice)

---

## 파일 맵

```
신규:
  src/main/java/com/docviewer/converter/HwpCliConverter.java
  src/main/java/com/docviewer/converter/DispatchingConverter.java
  src/test/java/com/docviewer/converter/HwpCliConverterTest.java
  src/test/java/com/docviewer/converter/DispatchingConverterTest.java

수정:
  src/main/java/com/docviewer/DocViewerServer.java  (36번째 줄 근처 조립부)
```

---

## Task 1: DispatchingConverter

**Files:**
- Create: `src/main/java/com/docviewer/converter/DispatchingConverter.java`
- Create: `src/test/java/com/docviewer/converter/DispatchingConverterTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/docviewer/converter/DispatchingConverterTest.java` 파일을 생성한다:

```java
package com.docviewer.converter;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DispatchingConverterTest {

    static class RecordingConverter implements DocumentConverter {
        final List<String> converted = new ArrayList<>();
        boolean alive = true;
        boolean shutdownCalled = false;

        @Override public void convert(File source, File dest) {
            converted.add(source.getName());
        }
        @Override public boolean isAlive() { return alive; }
        @Override public void shutdown() { shutdownCalled = true; }
    }

    @Test
    void hwp_routesToHwpConverter() throws Exception {
        RecordingConverter lo = new RecordingConverter();
        RecordingConverter hwp = new RecordingConverter();
        DispatchingConverter dispatcher = new DispatchingConverter(lo, hwp);

        dispatcher.convert(new File("report.hwp"), new File("out.pdf"));

        assertEquals(1, hwp.converted.size());
        assertTrue(lo.converted.isEmpty());
    }

    @Test
    void hwpx_routesToHwpConverter() throws Exception {
        RecordingConverter lo = new RecordingConverter();
        RecordingConverter hwp = new RecordingConverter();
        DispatchingConverter dispatcher = new DispatchingConverter(lo, hwp);

        dispatcher.convert(new File("report.hwpx"), new File("out.pdf"));

        assertEquals(1, hwp.converted.size());
        assertTrue(lo.converted.isEmpty());
    }

    @Test
    void docx_routesToLibreOfficeConverter() throws Exception {
        RecordingConverter lo = new RecordingConverter();
        RecordingConverter hwp = new RecordingConverter();
        DispatchingConverter dispatcher = new DispatchingConverter(lo, hwp);

        dispatcher.convert(new File("report.docx"), new File("out.pdf"));

        assertEquals(1, lo.converted.size());
        assertTrue(hwp.converted.isEmpty());
    }

    @Test
    void extensionIsCaseInsensitive() throws Exception {
        RecordingConverter lo = new RecordingConverter();
        RecordingConverter hwp = new RecordingConverter();
        DispatchingConverter dispatcher = new DispatchingConverter(lo, hwp);

        dispatcher.convert(new File("report.HWP"), new File("out.pdf"));

        assertEquals(1, hwp.converted.size());
    }

    @Test
    void isAlive_delegatesToLibreOfficeConverter() {
        RecordingConverter lo = new RecordingConverter();
        RecordingConverter hwp = new RecordingConverter();
        DispatchingConverter dispatcher = new DispatchingConverter(lo, hwp);

        lo.alive = false;
        assertFalse(dispatcher.isAlive());

        lo.alive = true;
        assertTrue(dispatcher.isAlive());
    }

    @Test
    void shutdown_delegatesToLibreOfficeConverter() {
        RecordingConverter lo = new RecordingConverter();
        RecordingConverter hwp = new RecordingConverter();
        DispatchingConverter dispatcher = new DispatchingConverter(lo, hwp);

        dispatcher.shutdown();

        assertTrue(lo.shutdownCalled);
        assertFalse(hwp.shutdownCalled);
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 오류 확인**

```bash
cd /mnt/c/Users/gonet1/Desktop/project/doc-viewer
mvn test -Dtest=DispatchingConverterTest -q 2>&1 | tail -20
```

Expected: `ERROR` — `DispatchingConverter` 클래스가 없어서 컴파일 실패

- [ ] **Step 3: DispatchingConverter 구현**

`src/main/java/com/docviewer/converter/DispatchingConverter.java` 파일을 생성한다:

```java
package com.docviewer.converter;

import java.io.File;
import java.util.Set;

public class DispatchingConverter implements DocumentConverter {

    private static final Set<String> HWP_EXTS = Set.of("hwp", "hwpx");

    private final DocumentConverter libreOfficeConverter;
    private final DocumentConverter hwpConverter;

    public DispatchingConverter(DocumentConverter libreOfficeConverter, DocumentConverter hwpConverter) {
        this.libreOfficeConverter = libreOfficeConverter;
        this.hwpConverter = hwpConverter;
    }

    @Override
    public void convert(File source, File dest) throws Exception {
        if (HWP_EXTS.contains(ext(source.getName()))) {
            hwpConverter.convert(source, dest);
        } else {
            libreOfficeConverter.convert(source, dest);
        }
    }

    @Override
    public boolean isAlive() {
        return libreOfficeConverter.isAlive();
    }

    @Override
    public void shutdown() {
        libreOfficeConverter.shutdown();
    }

    private String ext(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
```

- [ ] **Step 4: 테스트 실행 — 전부 통과 확인**

```bash
cd /mnt/c/Users/gonet1/Desktop/project/doc-viewer
mvn test -Dtest=DispatchingConverterTest -q
```

Expected: `BUILD SUCCESS` (6개 테스트 통과)

- [ ] **Step 5: 커밋**

```bash
cd /mnt/c/Users/gonet1/Desktop/project/doc-viewer
git add src/main/java/com/docviewer/converter/DispatchingConverter.java \
        src/test/java/com/docviewer/converter/DispatchingConverterTest.java
git commit -m "feat: add DispatchingConverter — routes HWP/HWPX to hwpConverter, others to LibreOffice"
```

---

## Task 2: HwpCliConverter

**Files:**
- Create: `src/main/java/com/docviewer/converter/HwpCliConverter.java`
- Create: `src/test/java/com/docviewer/converter/HwpCliConverterTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/docviewer/converter/HwpCliConverterTest.java` 파일을 생성한다:

```java
package com.docviewer.converter;

import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class HwpCliConverterTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("hwp-test-");
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteDir(tempDir);
    }

    private Path fakeSoffice(String script) throws Exception {
        Path p = Files.createTempFile(tempDir, "fake-soffice-", ".sh");
        Files.writeString(p, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(p, Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
        ));
        return p;
    }

    private File dummyHwp() throws Exception {
        Path p = Files.createTempFile(tempDir, "test", ".hwp");
        Files.writeString(p, "dummy");
        return p.toFile();
    }

    @Test
    void nonZeroExitCode_throwsException() throws Exception {
        Path soffice = fakeSoffice("exit 1");
        HwpCliConverter converter = new HwpCliConverter(
            soffice.toString(),
            tempDir.resolve("soffice-profile"),
            10
        );
        File dest = tempDir.resolve("out.pdf").toFile();

        Exception ex = assertThrows(Exception.class,
            () -> converter.convert(dummyHwp(), dest));
        assertTrue(ex.getMessage().contains("soffice exited with code 1"), ex.getMessage());
    }

    @Test
    void noOutputFile_throwsException() throws Exception {
        // 종료코드 0이지만 출력 파일 미생성
        Path soffice = fakeSoffice("exit 0");
        HwpCliConverter converter = new HwpCliConverter(
            soffice.toString(),
            tempDir.resolve("soffice-profile"),
            10
        );
        File dest = tempDir.resolve("out.pdf").toFile();

        Exception ex = assertThrows(Exception.class,
            () -> converter.convert(dummyHwp(), dest));
        assertTrue(ex.getMessage().contains("Conversion produced no output"), ex.getMessage());
    }

    @Test
    void timeout_throwsException() throws Exception {
        Path soffice = fakeSoffice("sleep 60");
        HwpCliConverter converter = new HwpCliConverter(
            soffice.toString(),
            tempDir.resolve("soffice-profile"),
            1   // 1초 타임아웃
        );
        File dest = tempDir.resolve("out.pdf").toFile();

        Exception ex = assertThrows(Exception.class,
            () -> converter.convert(dummyHwp(), dest));
        assertTrue(ex.getMessage().contains("timed out"), ex.getMessage());
    }

    @Test
    void successfulConversion_movesOutputToDest() throws Exception {
        // 출력 파일을 직접 생성하는 fake soffice
        // soffice는 {source의 stem}.pdf를 --outdir에 만든다
        // 스크립트에서 $@ 를 파싱하는 대신, 마지막 인자(--outdir 다음)의 디렉토리에 파일을 생성
        Path soffice = fakeSoffice(
            // --outdir 다음 인자가 출력 디렉토리. 소스 파일명(stem)으로 PDF 생성
            "OUTDIR=\"\"; SRCFILE=\"\"; NEXT_IS_OUTDIR=0\n" +
            "for arg in \"$@\"; do\n" +
            "  if [ \"$NEXT_IS_OUTDIR\" = \"1\" ]; then OUTDIR=\"$arg\"; NEXT_IS_OUTDIR=0\n" +
            "  elif [ \"$arg\" = \"--outdir\" ]; then NEXT_IS_OUTDIR=1\n" +
            "  elif [ \"$arg\" != \"--headless\" ] && [ \"$arg\" != \"--convert-to\" ] && [ \"$arg\" != \"pdf\" ] && echo \"$arg\" | grep -qv '^-'; then SRCFILE=\"$arg\"\n" +
            "  fi\n" +
            "done\n" +
            "STEM=$(basename \"$SRCFILE\" | sed 's/\\.[^.]*$//')\n" +
            "touch \"$OUTDIR/$STEM.pdf\"\n" +
            "exit 0"
        );
        File source = dummyHwp();
        File dest = tempDir.resolve("converted.pdf").toFile();
        HwpCliConverter converter = new HwpCliConverter(
            soffice.toString(),
            tempDir.resolve("soffice-profile"),
            10
        );

        converter.convert(source, dest);

        assertTrue(dest.exists(), "dest 파일이 생성되어야 한다");
    }

    private void deleteDir(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 오류 확인**

```bash
cd /mnt/c/Users/gonet1/Desktop/project/doc-viewer
mvn test -Dtest=HwpCliConverterTest -q 2>&1 | tail -20
```

Expected: `ERROR` — `HwpCliConverter` 클래스가 없어서 컴파일 실패

- [ ] **Step 3: HwpCliConverter 구현**

`src/main/java/com/docviewer/converter/HwpCliConverter.java` 파일을 생성한다:

```java
package com.docviewer.converter;

import com.docviewer.config.DocViewerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;

public class HwpCliConverter implements DocumentConverter {
    private static final Logger log = LoggerFactory.getLogger(HwpCliConverter.class);

    private final String sofficeExe;
    private final Path userInstallDir;
    private final int timeoutSeconds;
    private final Semaphore semaphore = new Semaphore(1);

    public HwpCliConverter(DocViewerConfig config) {
        this.sofficeExe = config.libreOfficePath + "/program/soffice";
        this.userInstallDir = Paths.get(config.resultDir, "soffice-profile");
        this.timeoutSeconds = config.convertTimeoutSeconds;
    }

    HwpCliConverter(String sofficeExe, Path userInstallDir, int timeoutSeconds) {
        this.sofficeExe = sofficeExe;
        this.userInstallDir = userInstallDir;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void convert(File source, File dest) throws Exception {
        if (!semaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new Exception("Conversion queue timeout after " + timeoutSeconds + "s");
        }
        try {
            Path tmpDir = Files.createTempDirectory("hwp-out-");
            try {
                runSoffice(source, tmpDir);
                Path outFile = findOutputFile(source, tmpDir);
                Files.move(outFile, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("HWP converted {} -> {}", source.getName(), dest.getName());
            } finally {
                deleteDir(tmpDir);
            }
        } finally {
            semaphore.release();
        }
    }

    @Override
    public boolean isAlive() {
        return true;
    }

    @Override
    public void shutdown() {
    }

    private void runSoffice(File source, Path outDir) throws Exception {
        Files.createDirectories(userInstallDir);
        String userInstallUri = userInstallDir.toUri().toString();

        List<String> cmd = List.of(
            sofficeExe,
            "--headless",
            "-env:UserInstallation=" + userInstallUri,
            "--convert-to", "pdf",
            source.getAbsolutePath(),
            "--outdir", outDir.toAbsolutePath().toString()
        );

        Process process = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("Conversion timed out after " + timeoutSeconds + "s");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new Exception("soffice exited with code " + exitCode);
        }
    }

    private Path findOutputFile(File source, Path outDir) throws Exception {
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        Path expected = outDir.resolve(stem + ".pdf");
        if (!Files.exists(expected)) {
            throw new Exception("Conversion produced no output: " + expected.getFileName());
        }
        return expected;
    }

    private void deleteDir(Path dir) {
        try {
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }
}
```

- [ ] **Step 4: 테스트 실행 — 전부 통과 확인**

```bash
cd /mnt/c/Users/gonet1/Desktop/project/doc-viewer
mvn test -Dtest=HwpCliConverterTest -q
```

Expected: `BUILD SUCCESS` (4개 테스트 통과)

- [ ] **Step 5: 커밋**

```bash
cd /mnt/c/Users/gonet1/Desktop/project/doc-viewer
git add src/main/java/com/docviewer/converter/HwpCliConverter.java \
        src/test/java/com/docviewer/converter/HwpCliConverterTest.java
git commit -m "feat: add HwpCliConverter — soffice --headless subprocess with semaphore and timeout"
```

---

## Task 3: DocViewerServer 조립 교체

**Files:**
- Modify: `src/main/java/com/docviewer/DocViewerServer.java`

- [ ] **Step 1: DocViewerServer.java 수정**

`DocViewerServer.java`의 36~37번째 줄을 다음과 같이 수정한다.

변경 전:
```java
        LibreOfficeConverter converter = new LibreOfficeConverter(config);
```

변경 후:
```java
        DocumentConverter libreOffice = new LibreOfficeConverter(config);
        DocumentConverter hwp = new HwpCliConverter(config);
        DocumentConverter converter = new DispatchingConverter(libreOffice, hwp);
```

import 목록에 아래 두 줄을 추가한다 (기존 `LibreOfficeConverter` import 아래에):
```java
import com.docviewer.converter.DocumentConverter;
import com.docviewer.converter.DispatchingConverter;
import com.docviewer.converter.HwpCliConverter;
```

- [ ] **Step 2: 빌드 및 기존 테스트 전체 통과 확인**

```bash
cd /mnt/c/Users/gonet1/Desktop/project/doc-viewer
mvn test -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: 커밋**

```bash
cd /mnt/c/Users/gonet1/Desktop/project/doc-viewer
git add src/main/java/com/docviewer/DocViewerServer.java
git commit -m "feat: wire DispatchingConverter in DocViewerServer — HWP via CLI, others via JODConverter daemon"
```

---

## 배포 체크리스트 (운영자용)

1. 패키지 설치:
   ```bash
   apt install libreoffice libreoffice-h2orestart
   ```
2. 설치 검증:
   ```bash
   soffice --headless --convert-to pdf sample.hwp --outdir /tmp
   ls /tmp/sample.pdf   # 존재해야 함
   ```
3. `doc-viewer.properties`에서 `libreoffice.path` 확인 (예: `/usr/lib/libreoffice`)
4. 서버 기동 후 헬스체크: `curl http://localhost:8090/docviewer/health`
