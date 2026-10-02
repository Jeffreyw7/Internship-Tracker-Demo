package com.campus.tracker.mail;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;
@Configuration
@EnableScheduling
public class MailConfiguration {
    @Bean Clock passwordClock(){return Clock.systemUTC();}
}
