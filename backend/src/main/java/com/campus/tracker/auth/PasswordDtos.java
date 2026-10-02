package com.campus.tracker.auth;
import jakarta.validation.constraints.*;
public final class PasswordDtos {
    private PasswordDtos(){}
    public record ResetRequest(@NotBlank @Email @Size(max=254) String email){
        @Override public String toString(){return "ResetRequest[REDACTED]";}
    }
    public record ChangeRequest(@NotBlank @Size(max=256) String currentPassword){
        @Override public String toString(){return "ChangeRequest[REDACTED]";}
    }
    public record Confirmation(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
            @NotBlank @Size(max=256) String newPassword){
        @Override public String toString(){return "Confirmation[REDACTED]";}
    }
    public record Result(String status,String message){}
}
