package com.campus.tracker.auth;

import com.campus.tracker.common.ApiException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Shared signup/reset creation policy; login deliberately accepts existing credentials. */
public final class PasswordPolicy {
    private static final Set<String> BLOCKED = loadBlocklist();
    private PasswordPolicy() {}
    public static void verifyConfiguration() {
        if (BLOCKED.isEmpty()) throw new IllegalStateException("Password blocklist cannot be loaded.");
    }
    public static void validate(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 15)
            throw new ApiException(HttpStatus.BAD_REQUEST,"Use at least 15 characters. A unique passphrase is welcome.");
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(HttpStatus.BAD_REQUEST,"Password must fit within 72 UTF-8 bytes. Use fewer characters.");
        if (BLOCKED.contains(password.toLowerCase(Locale.ROOT)))
            throw new ApiException(HttpStatus.BAD_REQUEST,"Choose a password that is not commonly used or compromised.");
    }
    private static Set<String> loadBlocklist() {
        Set<String> values = new HashSet<>();
        try (InputStream stream = PasswordPolicy.class.getResourceAsStream("/password-blocklist.txt")) {
            if (stream == null) throw new IOException();
            read(values,new InputStreamReader(stream,StandardCharsets.UTF_8));
            String supplement = System.getenv("PASSWORD_BLOCKLIST_FILE");
            if (supplement != null && !supplement.isBlank())
                try (Reader file = Files.newBufferedReader(Path.of(supplement),StandardCharsets.UTF_8)) { read(values,file); }
            return Set.copyOf(values);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Password blocklist cannot be loaded.");
        }
    }
    private static void read(Set<String> values, Reader source) throws IOException {
        BufferedReader lines = new BufferedReader(source);
        String line;
        while ((line = lines.readLine()) != null) if (!line.isEmpty() && !line.startsWith("#")) values.add(line.toLowerCase(Locale.ROOT));
    }
}