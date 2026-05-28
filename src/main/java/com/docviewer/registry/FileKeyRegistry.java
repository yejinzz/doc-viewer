package com.docviewer.registry;

import org.slf4j.*;
import java.io.Closeable;
import java.nio.file.Path;
import java.sql.*;

public class FileKeyRegistry implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(FileKeyRegistry.class);

    private final Connection conn;

    public static class FileKeyEntry {
        public final String key;
        public final String filePath;
        public final String originalName;
        public final String fileHash;
        public final long fileSize;
        public final long lastModified;
        public final String convertStatus;

        FileKeyEntry(String key, String filePath, String originalName,
                     String fileHash, long fileSize, long lastModified, String convertStatus) {
            this.key = key;
            this.filePath = filePath;
            this.originalName = originalName;
            this.fileHash = fileHash;
            this.fileSize = fileSize;
            this.lastModified = lastModified;
            this.convertStatus = convertStatus;
        }
    }

    public FileKeyRegistry(Path dbPath) throws SQLException {
        this("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    public static FileKeyRegistry forTesting() throws SQLException {
        return new FileKeyRegistry("jdbc:sqlite::memory:");
    }

    FileKeyRegistry(String jdbcUrl) throws SQLException {
        this.conn = DriverManager.getConnection(jdbcUrl);
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute(
                "CREATE TABLE IF NOT EXISTS file_keys (" +
                "  key            TEXT PRIMARY KEY," +
                "  file_path      TEXT NOT NULL," +
                "  original_name  TEXT," +
                "  file_hash      TEXT," +
                "  file_size      INTEGER," +
                "  last_modified  INTEGER," +
                "  converted_at   TEXT," +
                "  convert_status TEXT DEFAULT 'registered'," +
                "  error_message  TEXT" +
                ")"
            );
            s.execute("CREATE INDEX IF NOT EXISTS idx_status ON file_keys(convert_status)");
        }
    }

    public void register(String key, String filePath, String originalName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO file_keys (key, file_path, original_name, convert_status) VALUES (?,?,?,'registered')")) {
            ps.setString(1, key);
            ps.setString(2, filePath);
            ps.setString(3, originalName);
            ps.executeUpdate();
        }
    }

    public void markConverted(String key, String fileHash, long fileSize, long lastModified) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE file_keys SET file_hash=?, file_size=?, last_modified=?, " +
                "convert_status='converted', converted_at=datetime('now'), error_message=NULL WHERE key=?")) {
            ps.setString(1, fileHash);
            ps.setLong(2, fileSize);
            ps.setLong(3, lastModified);
            ps.setString(4, key);
            ps.executeUpdate();
        }
    }

    public void markError(String key, String errorMessage) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE file_keys SET convert_status='error', error_message=? WHERE key=?")) {
            ps.setString(1, errorMessage);
            ps.setString(2, key);
            ps.executeUpdate();
        }
    }

    public void updateMetadata(String key, String fileHash, long fileSize, long lastModified) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE file_keys SET file_hash=?, file_size=?, last_modified=? WHERE key=?")) {
            ps.setString(1, fileHash);
            ps.setLong(2, fileSize);
            ps.setLong(3, lastModified);
            ps.setString(4, key);
            ps.executeUpdate();
        }
    }

    public FileKeyEntry findByKey(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT key, file_path, original_name, file_hash, file_size, last_modified, convert_status " +
                "FROM file_keys WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new FileKeyEntry(
                    rs.getString("key"), rs.getString("file_path"), rs.getString("original_name"),
                    rs.getString("file_hash"), rs.getLong("file_size"),
                    rs.getLong("last_modified"), rs.getString("convert_status")
                );
            }
        }
    }

    public String getStatus(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT convert_status FROM file_keys WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void delete(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM file_keys WHERE key=?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            log.warn("Error closing registry connection", e);
        }
    }
}
