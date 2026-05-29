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
            1
        );
        File dest = tempDir.resolve("out.pdf").toFile();

        Exception ex = assertThrows(Exception.class,
            () -> converter.convert(dummyHwp(), dest));
        assertTrue(ex.getMessage().contains("timed out"), ex.getMessage());
    }

    @Test
    void successfulConversion_movesOutputToDest() throws Exception {
        Path soffice = fakeSoffice(
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
