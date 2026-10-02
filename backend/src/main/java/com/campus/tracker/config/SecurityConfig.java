// Configures session authentication, CSRF protection, password hashing, and JSON security responses.
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
    // Hash passwords with BCrypt cost 12; plaintext passwords are never stored.
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    // Normalize the login email and load the stored account for Spring Security.
    @Bean UserDetailsService userDetailsService(UserRepository users) {
        return email -> users.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .map(CampusPrincipal::from).orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    // Apply security checks before requests reach the REST controllers.
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json, UserRepository users) throws Exception {
        return http
                .addFilterBefore(new PasswordRequestSizeFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
                .addFilterBefore(new CredentialVersionFilter(users), org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                // Store CSRF tokens in the session so writes require both the token and session cookie.
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                // Allow account setup and health checks; require a signed-in user for other API routes.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/password-reset/request", "/api/auth/password-reset/confirm").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .requestCache(cache -> cache.disable())
                // Process form-encoded login credentials and return a profile or a generic failure message.
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
                // Invalidate the session and remove its cookie when the client signs out.
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID").logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                // Return JSON status errors instead of redirecting API callers to an HTML login page.
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
