package com.docviewer.converter;

import com.docviewer.config.DocViewerConfig;
import org.jodconverter.core.document.DocumentFamily;
import org.jodconverter.core.document.DocumentFormat;
import org.jodconverter.core.office.OfficeException;
import org.jodconverter.core.office.OfficeManager;
import org.jodconverter.local.LocalConverter;
import org.jodconverter.local.office.LocalOfficeManager;
import org.slf4j.*;
import java.io.File;

public class LibreOfficeConverter implements DocumentConverter {
    private static final Logger log = LoggerFactory.getLogger(LibreOfficeConverter.class);

    // JodConverter 4.4.x's built-in format registry does not include HWP/HWPX.
    // We supply explicit DocumentFormat objects so LibreOffice can auto-detect
    // the import filter while JodConverter still knows the correct document family.
    private static final DocumentFormat FMT_HWP = DocumentFormat.builder()
        .name("Hangul Word Processor")
        .extension("hwp")
        .mediaType("application/x-hwp")
        .inputFamily(DocumentFamily.TEXT)
        .build();

    private static final DocumentFormat FMT_HWPX = DocumentFormat.builder()
        .name("Hangul Word Processor XML")
        .extension("hwpx")
        .mediaType("application/haansofthwpx")
        .inputFamily(DocumentFamily.TEXT)
        .build();

    private final OfficeManager officeManager;
    private final org.jodconverter.core.DocumentConverter converter;

    public LibreOfficeConverter(DocViewerConfig config) throws OfficeException {
        this.officeManager = LocalOfficeManager.builder()
            .officeHome(config.libreOfficePath)
            .portNumbers(config.loPort)
            .maxTasksPerProcess(50)
            .build();
        this.officeManager.start();
        this.converter = LocalConverter.builder()
            .officeManager(officeManager)
            .build();
        log.info("LibreOffice daemon started (port {})", config.loPort);
    }

    @Override
    public void convert(File source, File dest) throws Exception {
        DocumentFormat inputFmt = resolveInputFormat(source.getName());
        if (inputFmt != null) {
            converter.convert(source).as(inputFmt).to(dest).execute();
        } else {
            converter.convert(source).to(dest).execute();
        }
        log.info("Converted {} -> {}", source.getName(), dest.getName());
    }

    private static DocumentFormat resolveInputFormat(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return null;
        switch (filename.substring(dot + 1).toLowerCase()) {
            case "hwp":  return FMT_HWP;
            case "hwpx": return FMT_HWPX;
            default:     return null;
        }
    }

    @Override
    public boolean isAlive() {
        return officeManager.isRunning();
    }

    @Override
    public void shutdown() {
        try {
            officeManager.stop();
            log.info("LibreOffice daemon stopped");
        } catch (OfficeException e) {
            log.warn("Error stopping LibreOffice daemon", e);
        }
    }
}
