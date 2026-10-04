// Enforces the signup password policy on the server, including requests that bypass the browser.
package com.campus.tracker.auth;

import com.campus.tracker.common.ApiException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

final class PasswordPolicy {
    // Use Unicode categories so letters, decimal digits, punctuation, and symbols are supported.
    private static final Pattern UPPER = Pattern.compile("\\p{Lu}");
    private static final Pattern LOWER = Pattern.compile("\\p{Ll}");
    private static final Pattern NUMBER = Pattern.compile("\\p{Nd}");
    private static final Pattern SYMBOL = Pattern.compile("[\\p{P}\\p{S}]");

    private PasswordPolicy() {}

    // Count Unicode code points and require each character category; spaces are not symbols.
    static void validate(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 12
                || !UPPER.matcher(password).find() || !LOWER.matcher(password).find()
                || !NUMBER.matcher(password).find() || !SYMBOL.matcher(password).find()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Use at least 12 characters, including an uppercase letter, a lowercase letter, a number, and a symbol.");
        }
        // BCrypt accepts at most 72 UTF-8 bytes, which can be fewer than 72 characters.
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must fit within 72 UTF-8 bytes. Use fewer characters.");
        }
    }
}
