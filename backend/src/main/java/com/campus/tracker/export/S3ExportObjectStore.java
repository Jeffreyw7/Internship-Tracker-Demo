package com.campus.tracker.export;

import java.io.*;
import java.nio.file.Path;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** Private bucket operations only. Credentials and generated keys never become client URLs. */
public final class S3ExportObjectStore implements ExportObjectStore {
    private final S3Client client;
    private final String bucket;
    public S3ExportObjectStore(S3Client client, String bucket) { this.client = client; this.bucket = bucket; }
    @Override public void put(String key, Path file) throws IOException {
        validate(key);
        try { client.putObject(request -> request.bucket(bucket).key(key).contentType("text/csv; charset=utf-8"), RequestBody.fromFile(file)); }
        catch (RuntimeException failure) { throw new IOException("Export upload unavailable", failure); }
    }
    @Override public InputStream open(String key) throws IOException {
        validate(key);
        try { return client.getObject(request -> request.bucket(bucket).key(key)); }
        catch (RuntimeException failure) { throw new IOException("Export download unavailable", failure); }
    }
    @Override public void delete(String key) throws IOException {
        validate(key);
        try { client.deleteObject(request -> request.bucket(bucket).key(key)); }
        catch (RuntimeException failure) { throw new IOException("Export cleanup unavailable", failure); }
    }
    private void validate(String key) { ExportObjectStore.validateKey(key); }
}
