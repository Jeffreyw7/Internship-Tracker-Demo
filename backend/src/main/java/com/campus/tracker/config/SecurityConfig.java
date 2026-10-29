// Configures session authentication, CSRF protection, password hashing, and JSON security responses.
package com.campus.tracker.config;

import com.campus.tracker.auth.*;
import com.campus.tracker.auth.abuse.*;
import com.campus.tracker.auth.reset.ResetInputFilter;
import com.campus.tracker.auth.reset.RecoveryCapacity;
import com.campus.tracker.application.importing.*;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.util.WebUtils;
import java.util.Locale;
import java.util.Map;

@Configuration
public class SecurityConfig {
    // Hash passwords with BCrypt cost 12; plaintext passwords are never stored.
    @Bean PasswordEncoder passwordEncoder() {
        PasswordPolicy.verifyConfiguration();
        return new BCryptPasswordEncoder(12);
    }

    // Normalize the login email and load the stored account for Spring Security.
    @Bean UserDetailsService userDetailsService(UserRepository users) {
        return email -> users.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .map(CampusPrincipal::from).orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    // Check verification only after the password matches so invalid credentials never disclose account state.
    @Bean AuthenticationProvider authenticationProvider(UserDetailsService users, PasswordEncoder passwords) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users) {
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
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json, AuthRateLimiter limiter,
            AuthWorkLimiter work, AuthAbuseProperties policies, AuthenticationProvider authenticationProvider,
            JdbcTemplate jdbc, PlatformTransactionManager transactions, RecoveryCapacity capacity,
            ImportAdmissionService importAdmission, ImportWorkLimiter importWork, ImportInputLimits importLimits) throws Exception {
        var versions = new CredentialVersionFilter(jdbc, transactions, json);
        var sessions = new HttpSessionSecurityContextRepository();
        return http
                .authenticationProvider(authenticationProvider)
                .securityContext(context -> context.requireExplicitSave(true))
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'")))
                // Store CSRF tokens in the session so writes require both the token and session cookie.
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .addFilterBefore(new ImportNoStoreFilter(), HeaderWriterFilter.class)
                .addFilterBefore(new ImportHeaderFilter(json), CsrfFilter.class)
                .addFilterBefore(new AuthAbuseFilter(limiter, policies, json), ImportHeaderFilter.class)
                .addFilterBefore(versions, AuthAbuseFilter.class)
                .addFilterAfter(new ResetInputFilter(json,capacity), CsrfFilter.class)
                .addFilterAfter(new ImportRequestFilter(importAdmission, importWork, json, importLimits), AuthorizationFilter.class)
                .addFilterBefore(new LoginWorkFilter(limiter, work, policies, json), UsernamePasswordAuthenticationFilter.class)
                // Allow account setup and health checks; require a signed-in user for other API routes.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/email-verification/resend", "/api/auth/email-verification/confirm",
                                "/api/auth/password-reset/request", "/api/auth/password-reset/confirm").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .requestCache(cache -> cache.disable())
                // Process form-encoded login credentials and return a profile or a generic failure message.
                .formLogin(form -> form
                        // Spring replaces the fluent repository during configure; post-process the filter afterward
                        // so authentication stays request-local until the handler validates and publishes it.
                        .withObjectPostProcessor(new ObjectPostProcessor<UsernamePasswordAuthenticationFilter>() {
                            @Override public <O extends UsernamePasswordAuthenticationFilter> O postProcess(O filter) {
                                filter.setSecurityContextRepository(new RequestAttributeSecurityContextRepository());
                                return filter;
                            }
                        })
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email").passwordParameter("password")
                        .successHandler((request, response, authentication) -> {
                            try {
                                // Session ID rotation reuses the session. Serialize the epoch check and publication
                                // so a rejected older login cannot overwrite another account's newer authentication.
                                synchronized (WebUtils.getSessionMutex(request.getSession())) {
                                    if (!versions.isCurrent((CampusPrincipal) authentication.getPrincipal())) {
                                        SecurityContextHolder.clearContext();
                                        versions.respond(response, 401, "Email or password is incorrect.");
                                        return;
                                    }
                                    sessions.saveContext(SecurityContextHolder.getContext(), request, response);
                                }
                            } catch (RuntimeException unavailable) {
                                SecurityContextHolder.clearContext();
                                versions.respond(response, 503, "Authentication is temporarily unavailable. Please try again.");
                                return;
                            }
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
