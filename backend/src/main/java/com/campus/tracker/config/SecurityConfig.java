// Configures session authentication, CSRF protection, password hashing, and JSON security responses.
package com.campus.tracker.config;

import com.campus.tracker.auth.*;
import com.campus.tracker.auth.abuse.*;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
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

    // Check verification only after the password matches so invalid credentials never disclose account state.
    @Bean AuthenticationProvider authenticationProvider(UserDetailsService users, PasswordEncoder passwords) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users) {
            @Override
            protected org.springframework.security.core.Authentication createSuccessAuthentication(Object principal,
                    org.springframework.security.core.Authentication authentication, UserDetails details) {
                // Sanitize before the authentication filter persists its SecurityContext.
                return super.createSuccessAuthentication(((CampusPrincipal) principal).sessionIdentity(), authentication, details);
            }
            @Override
            protected void additionalAuthenticationChecks(UserDetails details,
                    UsernamePasswordAuthenticationToken authentication) throws AuthenticationException {
                super.additionalAuthenticationChecks(details, authentication);
                if (details instanceof CampusPrincipal principal && !principal.emailAccessAllowed()) {
                    throw new EmailVerificationRequiredException();
                }
            }
        };
        provider.setPasswordEncoder(passwords);
        return provider;
    }

    // Apply security checks before requests reach the REST controllers.
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json, AuthAdmissionLimiter limiter,
            AuthWorkLimiter work, AuthAbuseProperties policies, AuthenticationProvider authenticationProvider) throws Exception {
        return http
                .authenticationProvider(authenticationProvider)
                // Store CSRF tokens in the session so writes require both the token and session cookie.
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .addFilterBefore(new AuthAbuseFilter(limiter, policies, json), CsrfFilter.class)
                .addFilterBefore(new LoginWorkFilter(limiter, work, policies, json), UsernamePasswordAuthenticationFilter.class)
                // Allow account setup and health checks; require a signed-in user for other API routes.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/health", "/livez", "/readyz").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/email-verification/resend", "/api/auth/email-verification/confirm").permitAll()
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
                            response.setContentType("application/json");
                            if (exception instanceof EmailVerificationRequiredException) {
                                response.setStatus(403);
                                json.writeValue(response.getOutputStream(), Map.of(
                                        "message", "Verify your email before signing in.",
                                        "code", "EMAIL_VERIFICATION_REQUIRED"));
                            } else {
                                response.setStatus(401);
                                json.writeValue(response.getOutputStream(), Map.of("message", "Email or password is incorrect."));
                            }
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
