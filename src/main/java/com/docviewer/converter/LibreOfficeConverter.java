package com.docviewer.converter;

import com.docviewer.config.DocViewerConfig;
import org.jodconverter.core.office.OfficeException;
import org.jodconverter.core.office.OfficeManager;
import org.jodconverter.local.LocalConverter;
import org.jodconverter.local.office.LocalOfficeManager;
import org.slf4j.*;
import java.io.File;

public class LibreOfficeConverter implements DocumentConverter {
    private static final Logger log = LoggerFactory.getLogger(LibreOfficeConverter.class);

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
        converter.convert(source).to(dest).execute();
        log.info("Converted {} -> {}", source.getName(), dest.getName());
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
