// Carries a deliberate HTTP status and user-facing message from services to the API error handler.
package com.campus.tracker.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Integer retryAfter;
    public ApiException(HttpStatus status, String message) { this(status,null,message,null); }
    public ApiException(HttpStatus status, String code, String message) { this(status,code,message,null); }
    public ApiException(HttpStatus status, String code, String message, Integer retryAfter) {
        super(message); this.status=status; this.code=code; this.retryAfter=retryAfter;
    }
    public HttpStatus getStatus() { return status; }
    public String getCode(){return code;}
    public Integer getRetryAfter(){return retryAfter;}
}
