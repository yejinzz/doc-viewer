package com.docviewer.detector;

import java.util.Map;
import java.util.Set;

public class FileTypeDetector {

    public enum RenderType { PDF, TEXT, IMAGE, LIBREOFFICE }

    private static final Set<String> IMAGE_EXTS = Set.of(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico"
    );
    private static final Set<String> TEXT_EXTS = Set.of("txt", "log", "csv");
    private static final Set<String> PDF_EXTS = Set.of("pdf");
    private static final Set<String> LO_EXTS = Set.of(
        "doc", "docx", "hwp", "hwpx", "xls", "xlsx", "ods",
        "ppt", "pptx", "odp", "odt", "rtf"
    );
    private static final Map<String, String> MIME_MAP = Map.ofEntries(
        Map.entry("pdf",  "application/pdf"),
        Map.entry("txt",  "text/plain"),
        Map.entry("csv",  "text/csv"),
        Map.entry("jpg",  "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("png",  "image/png"),
        Map.entry("gif",  "image/gif"),
        Map.entry("webp", "image/webp"),
        Map.entry("bmp",  "image/bmp"),
        Map.entry("svg",  "image/svg+xml")
    );

    public RenderType detect(String filename) {
        String ext = ext(filename);
        if (PDF_EXTS.contains(ext))   return RenderType.PDF;
        if (TEXT_EXTS.contains(ext))  return RenderType.TEXT;
        if (IMAGE_EXTS.contains(ext)) return RenderType.IMAGE;
        return RenderType.LIBREOFFICE;
    }

    public String mimeType(String filename) {
        return MIME_MAP.getOrDefault(ext(filename), "application/octet-stream");
    }

    public boolean isSupported(String filename) {
        String ext = ext(filename);
        return PDF_EXTS.contains(ext) || TEXT_EXTS.contains(ext)
            || IMAGE_EXTS.contains(ext) || LO_EXTS.contains(ext);
    }

    private String ext(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
