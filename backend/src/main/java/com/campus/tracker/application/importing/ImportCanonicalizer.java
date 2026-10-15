package com.campus.tracker.application.importing;

import com.campus.tracker.common.ApiException;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** Canonical UTF-8 is bounded while Jackson writes, before the final string is allocated. */
@Component
public class ImportCanonicalizer {
    public static final int PAYLOAD_LIMIT = 8 * 1024 * 1024;
    public static final int RECEIPT_LIMIT = 128 * 1024;
    private final ObjectMapper json;
    public ImportCanonicalizer(ObjectMapper mapper) {
        json = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        json.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(8*1024*1024).build());
    }
    public String write(Object value, int maximum) {
        class Bounded extends ByteArrayOutputStream {
            @Override public synchronized void write(int b) { check(1); super.write(b); }
            @Override public synchronized void write(byte[] b,int off,int len) { check(len); super.write(b,off,len); }
            private void check(int n) { if (n > maximum-count) throw failure("IMPORT_SIZE_LIMIT","Import output exceeds its size limit."); }
        }
        try {
            var out = new Bounded(); json.writeValue(out,value); return out.toString(StandardCharsets.UTF_8);
        } catch (ApiException e) { throw e; }
        catch (IOException e) {
            if (e.getCause() instanceof ApiException api) throw api;
            throw failure("IMPORT_SERIALIZATION_FAILED","Import could not be serialized.");
        }
    }
    public <T> T read(String value,Class<T> type) {
        try { return json.readValue(value,type); }
        catch (IOException | IllegalArgumentException e) { throw failure("IMPORT_INVALID_CONTENT","Import JSON is invalid."); }
    }
    public <T> T read(byte[] value,Class<T> type) {
        try { return json.readValue(value,type); }
        catch (IOException | IllegalArgumentException e) { throw failure("IMPORT_INVALID_CONTENT","Import JSON is invalid."); }
    }
    public String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static ApiException failure(String code,String message) { return new ApiException(code.equals("IMPORT_SIZE_LIMIT")?HttpStatus.PAYLOAD_TOO_LARGE:HttpStatus.BAD_REQUEST,code,message); }
}
