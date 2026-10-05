package com.campus.tracker.config;

import com.campus.tracker.common.DependencyFailureFilter;
import com.campus.tracker.common.RequestCorrelationFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class OperationsConfiguration {
    @Bean FilterRegistrationBean<RequestCorrelationFilter> requestCorrelationFilter() {
        var registration = new FilterRegistrationBean<>(new RequestCorrelationFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean FilterRegistrationBean<DependencyFailureFilter> dependencyFailureFilter() {
        var registration = new FilterRegistrationBean<>(new DependencyFailureFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    /** Scraping requires a separate secret; a normal account can never access management data. */
    @Bean @Order(1) SecurityFilterChain managementSecurity(HttpSecurity http,
            @Value("${campus.operations.metrics-token:}") String token) throws Exception {
        return http.securityMatcher("/actuator/**")
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/prometheus").access((authentication, context) -> {
                            String supplied = context.getRequest().getHeader("Authorization");
                            boolean allowed = !token.isBlank() && supplied != null
                                    && context.getRequest().getMethod().equals("GET")
                                    && MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                                            supplied.getBytes(StandardCharsets.UTF_8));
                            return new AuthorizationDecision(allowed);
                        }).anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> response.setStatus(403))
                        .accessDeniedHandler((request, response, failure) -> response.setStatus(403)))
                .build();
    }
}
