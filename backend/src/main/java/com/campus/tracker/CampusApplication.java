// Starts Spring Boot, which discovers controllers, services, repositories, and security configuration.
package com.campus.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Shared sessions are enabled explicitly by the distributed profile, never by classpath presence.
@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.session.SessionAutoConfiguration.class)
public class CampusApplication {
    // Launch the embedded web server and initialize the application context.
    public static void main(String[] args) {
        SpringApplication.run(CampusApplication.class, args);
    }
}
