package com.campus.tracker.auth;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=PasswordController.class)
public class PasswordErrorHandler {
    @ExceptionHandler({DataAccessException.class,TransactionException.class,IllegalStateException.class})
    ResponseEntity<?> unavailable(Exception error){
        return ResponseEntity.status(503).header("Cache-Control","no-store")
                .body(Map.of("code","PASSWORD_SERVICE_UNAVAILABLE","message","Password service is temporarily unavailable. Try again later."));
    }
}
