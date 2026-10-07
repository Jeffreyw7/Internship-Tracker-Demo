// Converts expected API failures into consistent JSON messages without exposing internal exception details.
package com.campus.tracker.common;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(com.campus.tracker.auth.abuse.AuthThrottleException.class)
    ResponseEntity<?> throttled(com.campus.tracker.auth.abuse.AuthThrottleException e) {
        return ResponseEntity.status(429).header("Retry-After", Long.toString(e.retryAfterSeconds()))
                .body(Map.of("message", e.getMessage()));
    }
    // Preserve deliberate service errors such as missing records or rejected registration.
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e) {
        Map<String, String> body = e.getCode() == null ? Map.of("message", e.getMessage())
                : Map.of("message", e.getMessage(), "code", e.getCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    // Report the first invalid request field using its validation message.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).findFirst().orElse("Check the submitted fields.");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    // Reject malformed JSON, invalid parameter types, and unsupported field values.
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class, IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Check the field values, dates, and request format."));
    }

    // Turn concurrent database writes into a conflict that prompts the client to refresh.
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class})
    ResponseEntity<?> conflict(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "This application changed elsewhere. Refresh before editing again."));
    }

    // Report unique-key or other integrity conflicts without disclosing database details.
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> duplicate(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "This record conflicts with an existing record."));
    }
}
