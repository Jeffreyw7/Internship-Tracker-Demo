package com.campus.tracker.auth;

import com.campus.tracker.common.ApiException;
import com.campus.tracker.mail.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class PasswordService {
    private final UserRepository users;private final PasswordChallengeRepository challenges;
    private final PasswordEncoder passwords;private final EmailOutbox outbox;
    private final AuthRateLimiter limiter;private final MailProperties config;private final Clock clock;
    private final String dummyHash;
    public PasswordService(UserRepository users,PasswordChallengeRepository challenges,PasswordEncoder passwords,
            EmailOutbox outbox,AuthRateLimiter limiter,MailProperties config,Clock clock){
        this.users=users;this.challenges=challenges;this.passwords=passwords;this.outbox=outbox;
        this.limiter=limiter;this.config=config;this.clock=clock;
        dummyHash=passwords.encode("password-request-timing-placeholder");
    }
    public void requireEnabled(){if(!config.isEnabled())throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"PASSWORD_MAIL_UNAVAILABLE","Password email is unavailable. Try again later.");}
    @Transactional
    public PasswordDtos.Result requestReset(String submitted){
        String email=submitted.trim().toLowerCase(Locale.ROOT);
        boolean send=limiter.reserveEmail(email);
        // Do not populate the persistence context before locking: credentials may change during hashing.
        var id=users.findIdByEmail(email);
        passwords.matches("password-request-timing-placeholder",dummyHash);
        if(send)id.flatMap(users::findByIdForUpdate).ifPresent(u->{
            if(limiter.reserveDelivery())issue(u,"RESET",null);
        });
        return new PasswordDtos.Result("PASSWORD_RESET_REQUEST_ACCEPTED","If an account exists for this email, a reset link will arrive shortly.");
    }
    @Transactional
    public PasswordDtos.Result requestChange(UUID id,String currentPassword,String binding){
        CampusUser user=users.findByIdForUpdate(id).orElseThrow(this::invalid);
        if(currentPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 || !passwords.matches(currentPassword,user.getPasswordHash()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"CURRENT_PASSWORD_INVALID","Your current password is incorrect.");
        if(!limiter.reserveEmail(user.getEmail()) || !limiter.reserveDelivery())throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Wait before requesting another email.",60);
        challenges.revoke(id,"CHANGE",clock.instant());outbox.cancelChallenges(id);
        issue(user,"CHANGE",binding);
        return new PasswordDtos.Result("PASSWORD_CHANGE_REQUEST_ACCEPTED","Check your email and open the link in this browser to change your password.");
    }
    private void issue(CampusUser user,String purpose,String binding){
        String token=PasswordToken.generate();UUID id=UUID.randomUUID();Instant now=clock.instant();
        challenges.insert(id,user,purpose,token,binding,now,now.plus(purpose.equals("RESET")?config.getResetTtl():config.getChangeTtl()));
        outbox.enqueueChallenge(id,new EmailMessage(purpose,user.getEmail(),token,now));
    }
    @Transactional
    public UUID confirmReset(String token,String newPassword){return confirm(null,null,token,newPassword,"RESET");}
    @Transactional
    public UUID confirmChange(UUID id,String binding,String token,String newPassword){return confirm(id,binding,token,newPassword,"CHANGE");}
    private UUID confirm(UUID initiator,String binding,String token,String newPassword,String purpose){
        var first=challenges.find(token,false).orElseThrow(this::invalid);
        CampusUser user=users.findByIdForUpdate(first.userId()).orElseThrow(this::invalid);
        var challenge=challenges.find(token,true).orElseThrow(this::invalid);
        Instant now=clock.instant();
        if(challenge.used()||!challenge.expiresAt().isAfter(now)||!challenge.purpose().equals(purpose)
                ||!challenge.email().equals(user.getEmail())||challenge.version()!=user.getCredentialVersion()
                ||(purpose.equals("CHANGE")&&(!user.getId().equals(initiator)||binding==null
                ||!PasswordToken.hash(binding).equals(challenge.bindingHash()))))throw invalid();
        try{PasswordPolicy.validate(newPassword);}catch(ApiException e){throw new ApiException(HttpStatus.BAD_REQUEST,"PASSWORD_POLICY_FAILED",e.getMessage());}
        user.replacePassword(passwords.encode(newPassword),now);users.flush();
        challenges.consume(challenge.id(),now);challenges.revoke(user.getId(),null,now);outbox.cancelChallenges(user.getId());
        outbox.enqueueNotification(user.getId(),new EmailMessage("CHANGED",user.getEmail(),null,now));
        return user.getId();
    }
    private ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"PASSWORD_CHALLENGE_INVALID","This link is invalid or expired. Request a new email and try again.");}
}
