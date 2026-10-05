package com.campus.tracker.export;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;

/** Local/testing mode only. Objects are outside the frontend's static resource directories. */
public final class FileExportObjectStore implements ExportObjectStore {
    private final Path root;
    public FileExportObjectStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    private Path resolve(String key) {
        ExportObjectStore.validateKey(key); Path file = root.resolve(key).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("Invalid export object path");
        return file;
    }
    @Override public void put(String key, Path source) throws IOException {
        Path destination = resolve(key); Files.createDirectories(destination.getParent());
        Path staging = Files.createTempFile(destination.getParent(), "upload-", ".tmp");
        try {
            Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING);
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(staging); }
    }
    @Override public InputStream open(String key) throws IOException { return Files.newInputStream(resolve(key)); }
    @Override public void delete(String key) throws IOException { Files.deleteIfExists(resolve(key)); }
}
