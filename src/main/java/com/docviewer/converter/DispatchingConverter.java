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
