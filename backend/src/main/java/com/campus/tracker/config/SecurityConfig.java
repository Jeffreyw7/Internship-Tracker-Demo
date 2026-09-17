package com.campus.tracker.config;

import com.campus.tracker.auth.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import java.util.Locale;
import java.util.Map;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean UserDetailsService userDetailsService(UserRepository users) {
        return email -> users.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .map(CampusPrincipal::from).orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json) throws Exception {
        return http
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email").passwordParameter("password")
                        .successHandler((request, response, authentication) -> {
                            response.setContentType("application/json");
                            json.writeValue(response.getOutputStream(), AuthDtos.UserView.from((CampusPrincipal) authentication.getPrincipal()));
                        })
                        .failureHandler((request, response, exception) -> {
                            response.setStatus(401); response.setContentType("application/json");
                            json.writeValue(response.getOutputStream(), Map.of("message", "Email or password is incorrect."));
                        }))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID").logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401); response.setContentType("application/json");
                            json.writeValue(response.getOutputStream(), Map.of("message", "Sign in to continue."));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403); response.setContentType("application/json");
                            json.writeValue(response.getOutputStream(), Map.of("message", "This request could not be verified. Reload and sign in again."));
                        }))
                .build();
    }
}
