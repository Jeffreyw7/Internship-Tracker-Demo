package com.campus.tracker.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class PasswordController {
    private static final String BINDING="campus.password.change-binding";
    private final PasswordService passwords;private final AuthRateLimiter limiter;
    public PasswordController(PasswordService passwords,AuthRateLimiter limiter){this.passwords=passwords;this.limiter=limiter;}
    @PostMapping("/password-reset/request") @ResponseStatus(HttpStatus.ACCEPTED)
    public PasswordDtos.Result reset(@Valid @RequestBody PasswordDtos.ResetRequest body,HttpServletRequest req,HttpServletResponse res){
        res.setHeader("Cache-Control","no-store");passwords.requireEnabled();
        limiter.requireRequestAllowed("reset",req.getRemoteAddr());return passwords.requestReset(body.email());
    }
    @PostMapping("/password-change/request") @ResponseStatus(HttpStatus.ACCEPTED)
    public PasswordDtos.Result change(@Valid @RequestBody PasswordDtos.ChangeRequest body,@AuthenticationPrincipal CampusPrincipal user,
            HttpServletRequest req,HttpServletResponse res){
        res.setHeader("Cache-Control","no-store");passwords.requireEnabled();limiter.requireRequestAllowed("change",req.getRemoteAddr());
        var session=req.getSession();
        // Serialize commit and session binding replacement, so an older response cannot overwrite the newest binding.
        synchronized(session){
            String binding=PasswordToken.generate();var result=passwords.requestChange(user.id(),body.currentPassword(),binding);
            session.setAttribute(BINDING,binding);return result;
        }
    }
    @PostMapping("/password-reset/confirm")
    public PasswordDtos.Result confirmReset(@Valid @RequestBody PasswordDtos.Confirmation body,@AuthenticationPrincipal CampusPrincipal user,
            HttpServletRequest req,HttpServletResponse res){
        res.setHeader("Cache-Control","no-store");passwords.requireEnabled();limiter.requireConfirmationAllowed(body.token(),req.getRemoteAddr());
        passwords.confirmReset(body.token(),body.newPassword());
        endSession(req,res);
        return new PasswordDtos.Result("PASSWORD_RESET_COMPLETED","Password reset. Sign in with your new password.");
    }
    @PostMapping("/password-change/confirm")
    public PasswordDtos.Result confirmChange(@Valid @RequestBody PasswordDtos.Confirmation body,@AuthenticationPrincipal CampusPrincipal user,
            HttpServletRequest req,HttpServletResponse res){
        res.setHeader("Cache-Control","no-store");passwords.requireEnabled();limiter.requireConfirmationAllowed(body.token(),req.getRemoteAddr());
        var session=req.getSession(false);String binding=session==null?null:(String)session.getAttribute(BINDING);
        passwords.confirmChange(user.id(),binding,body.token(),body.newPassword());endSession(req,res);
        return new PasswordDtos.Result("PASSWORD_CHANGE_COMPLETED","Password changed. Sign in with your new password.");
    }
    public static void endSession(HttpServletRequest req,HttpServletResponse res){
        var session=req.getSession(false);if(session!=null)session.invalidate();SecurityContextHolder.clearContext();
        var cookie=new jakarta.servlet.http.Cookie("JSESSIONID","");cookie.setPath("/");cookie.setHttpOnly(true);cookie.setSecure(req.isSecure());cookie.setMaxAge(0);res.addCookie(cookie);
    }
}
