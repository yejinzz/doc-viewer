package com.docviewer.detector;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.docviewer.detector.FileTypeDetector.RenderType.*;

class FileTypeDetectorTest {
    private final FileTypeDetector detector = new FileTypeDetector();

    @Test
    void detectsPdf() {
        assertEquals(PDF, detector.detect("report.pdf"));
        assertEquals(PDF, detector.detect("REPORT.PDF"));
    }

    @Test
    void detectsImages() {
        assertEquals(IMAGE, detector.detect("photo.jpg"));
        assertEquals(IMAGE, detector.detect("image.PNG"));
        assertEquals(IMAGE, detector.detect("anim.gif"));
        assertEquals(IMAGE, detector.detect("modern.webp"));
    }

    @Test
    void detectsText() {
        assertEquals(TEXT, detector.detect("readme.txt"));
        assertEquals(TEXT, detector.detect("data.csv"));
    }

    @Test
    void detectsLibreOfficeFormats() {
        assertEquals(LIBREOFFICE, detector.detect("doc.docx"));
        assertEquals(LIBREOFFICE, detector.detect("sheet.xlsx"));
        assertEquals(LIBREOFFICE, detector.detect("pres.pptx"));
        assertEquals(LIBREOFFICE, detector.detect("old.doc"));
    }

    @Test
    void isSupportedReturnsFalseForUnknown() {
        assertFalse(detector.isSupported("archive.zip"));
        assertFalse(detector.isSupported("binary.exe"));
    }

    @Test
    void isSupportedReturnsTrueForKnown() {
        assertTrue(detector.isSupported("report.pdf"));
        assertTrue(detector.isSupported("doc.hwp"));
        assertTrue(detector.isSupported("image.png"));
    }

    @Test
    void mimeTypeForPdf() {
        assertEquals("application/pdf", detector.mimeType("report.pdf"));
    }

    @Test
    void mimeTypeForJpg() {
        assertEquals("image/jpeg", detector.mimeType("photo.jpg"));
    }

    @Test
    void mimeTypeForUnknownFallback() {
        assertEquals("application/octet-stream", detector.mimeType("file.xyz"));
    }

    @Test
    void detectsHwpFormats() {
        assertEquals(HWP, detector.detect("문서.hwp"));
        assertEquals(HWP, detector.detect("data.hwpx"));
        assertEquals(HWP, detector.detect("DOC.HWP"));
        assertEquals(HWP, detector.detect("DOC.HWPX"));
    }

    @Test
    void customAllowedExtensionsRestrictsIsSupported() {
        FileTypeDetector restricted = new FileTypeDetector(java.util.Set.of("pdf", "hwp"));
        assertTrue(restricted.isSupported("report.pdf"));
        assertTrue(restricted.isSupported("doc.hwp"));
        assertFalse(restricted.isSupported("sheet.xlsx")); // not in custom list
        assertFalse(restricted.isSupported("image.png"));  // not in custom list
    }

    @Test
    void detectStillWorksWithCustomExtensions() {
        FileTypeDetector restricted = new FileTypeDetector(java.util.Set.of("pdf", "hwp", "jpg"));
        assertEquals(FileTypeDetector.RenderType.PDF, restricted.detect("doc.pdf"));
        assertEquals(FileTypeDetector.RenderType.IMAGE, restricted.detect("photo.jpg"));
        assertEquals(FileTypeDetector.RenderType.HWP, restricted.detect("file.hwp"));
    }
}
