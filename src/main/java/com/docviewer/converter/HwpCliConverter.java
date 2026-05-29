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
        long queueTimeoutSeconds = timeoutSeconds * 2L;
        if (!semaphore.tryAcquire(queueTimeoutSeconds, TimeUnit.SECONDS)) {
            throw new Exception("Conversion queue timeout after " + queueTimeoutSeconds + "s");
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
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
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
