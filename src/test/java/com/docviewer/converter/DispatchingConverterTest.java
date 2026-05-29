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
    void isAlive_delegatesToBothConverters() {
        RecordingConverter lo = new RecordingConverter();
        RecordingConverter hwp = new RecordingConverter();
        DispatchingConverter dispatcher = new DispatchingConverter(lo, hwp);

        lo.alive = false;
        assertFalse(dispatcher.isAlive());

        lo.alive = true;
        assertTrue(dispatcher.isAlive());

        hwp.alive = false;
        assertFalse(dispatcher.isAlive());
    }

    @Test
    void shutdown_delegatesToBothConverters() {
        RecordingConverter lo = new RecordingConverter();
        RecordingConverter hwp = new RecordingConverter();
        DispatchingConverter dispatcher = new DispatchingConverter(lo, hwp);

        dispatcher.shutdown();

        assertTrue(lo.shutdownCalled);
        assertTrue(hwp.shutdownCalled);
    }
}
