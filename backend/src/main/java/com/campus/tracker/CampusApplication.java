// Starts Spring Boot, which discovers controllers, services, repositories, and security configuration.
package com.campus.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CampusApplication {
    // Launch the embedded web server and initialize the application context.
    public static void main(String[] args) {
        SpringApplication.run(CampusApplication.class, args);
    }
}
