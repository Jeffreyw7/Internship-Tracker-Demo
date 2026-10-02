package com.campus.tracker.mail;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;

@Component
public class MailProperties {
    @Value("${campus.password.mail-enabled:false}") private boolean enabled;
    @Value("${campus.password.worker-enabled:true}") private boolean workerEnabled;
    @Value("${campus.password.public-origin:http://127.0.0.1:8080}") private String origin;
    @Value("${campus.password.mail-from:}") private String from;
    @Value("${campus.password.encryption-key:}") private String key;
    @Value("${campus.password.encryption-key-id:v1}") private String keyId;
    @Value("${campus.password.rate-limit-secret:}") private String rateLimitSecret;
    @Value("${campus.password.reset-ttl:30m}") private Duration resetTtl;
    @Value("${campus.password.change-ttl:15m}") private Duration changeTtl;
    @Value("${campus.password.limits.request-minute:5}") private int requestMinute;
    @Value("${campus.password.limits.request-hour:30}") private int requestHour;
    @Value("${campus.password.limits.email-minute:1}") private int emailMinute;
    @Value("${campus.password.limits.email-hour:5}") private int emailHour;
    @Value("${campus.password.limits.email-day:10}") private int emailDay;
    @Value("${campus.password.limits.mail-day:1000}") private int mailDay;
    @Value("${campus.password.limits.confirm-minute:10}") private int confirmMinute;
    @Value("${campus.password.limits.confirm-token:10}") private int confirmToken;
    @PostConstruct void validate() {
        if (!enabled) return;
        URI uri=URI.create(origin);
        boolean loopback=java.util.Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost());
        if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPath()==null || uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || !("https".equals(uri.getScheme()) || (loopback && "http".equals(uri.getScheme()))))
            throw new IllegalArgumentException("Password public origin must be HTTPS (HTTP loopback permitted).");
        if (Base64.getDecoder().decode(key).length!=32 || keyId.isBlank() || keyId.length()>100
                || rateLimitSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<32
                || !from.matches("[^\\s@<>]+@[^\\s@<>]+") || resetTtl.isNegative() || resetTtl.isZero()
                || changeTtl.isNegative() || changeTtl.isZero()
                || java.util.stream.IntStream.of(requestMinute,requestHour,emailMinute,emailHour,emailDay,mailDay,confirmMinute,confirmToken).anyMatch(i->i<1))
            throw new IllegalArgumentException("Password mail configuration is incomplete or invalid.");
    }
    public boolean isEnabled(){return enabled;}
    public boolean isWorkerEnabled(){return workerEnabled;}
    public String getOrigin(){return origin.replaceAll("/$", "");}
    public String getFrom(){return from;}
    public byte[] getKey(){return Base64.getDecoder().decode(key);}
    public String getKeyId(){return keyId;}
    public String getRateLimitSecret(){return rateLimitSecret;}
    public Duration getResetTtl(){return resetTtl;}
    public Duration getChangeTtl(){return changeTtl;}
    public int getRequestMinute(){return requestMinute;}
    public int getRequestHour(){return requestHour;}
    public int getEmailMinute(){return emailMinute;}
    public int getEmailHour(){return emailHour;}
    public int getEmailDay(){return emailDay;}
    public int getMailDay(){return mailDay;}
    public int getConfirmMinute(){return confirmMinute;}
    public int getConfirmToken(){return confirmToken;}
}
