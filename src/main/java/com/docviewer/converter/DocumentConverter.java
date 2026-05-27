package com.docviewer.converter;

import java.io.File;

public interface DocumentConverter {
    void convert(File source, File dest) throws Exception;
    boolean isAlive();
    void shutdown();
}
