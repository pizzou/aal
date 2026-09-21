
package com.logiplatform.config;

import com.logiplatform.security.AuthRateLimitFilter;
import com.logiplatform.security.BrowserCsrfFilter;
import com.logiplatform.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

        @Value("${security.cors.allowed-origins:http://localhost:3000}")
        private String allowedOrigins;

        private final JwtAuthenticationFilter jwtAuthenticationFilter;
        private final AuthRateLimitFilter authRateLimitFilter;
        private final BrowserCsrfFilter browserCsrfFilter;

        public SecurityConfig(
                        JwtAuthenticationFilter jwtAuthenticationFilter,
                        AuthRateLimitFilter authRateLimitFilter,
                        BrowserCsrfFilter browserCsrfFilter) {

                this.jwtAuthenticationFilter = jwtAuthenticationFilter;
                this.authRateLimitFilter = authRateLimitFilter;
                this.browserCsrfFilter = browserCsrfFilter;
        }

        @Bean
        public SecurityFilterChain filterChain(
                        HttpSecurity http) throws Exception {

                http
                                .csrf(csrf -> csrf.disable())

                                .headers(headers -> headers
                                                .contentTypeOptions(
                                                                org.springframework.security.config.Customizer
                                                                                .withDefaults())
                                                .frameOptions(frame -> frame.deny())
                                                .referrerPolicy(referrer -> referrer.policy(
                                                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                                                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)
                                                                .maxAgeInSeconds(31536000)))

                                .cors(cors -> cors.configurationSource(
                                                corsConfigurationSource()))

                                .sessionManagement(session -> session.sessionCreationPolicy(
                                                SessionCreationPolicy.STATELESS))

                                .exceptionHandling(exceptions -> exceptions
                                                .authenticationEntryPoint((request, response, exception) -> {
                                                        response.setStatus(401);
                                                        response.setContentType("application/json");
                                                        response.setCharacterEncoding("UTF-8");
                                                        response.setHeader("Cache-Control", "no-store");
                                                        response.getWriter().write("{\"error\":\"Authentication required\"}");
                                                })
                                                .accessDeniedHandler((request, response, exception) -> {
                                                        response.setStatus(403);
                                                        response.setContentType("application/json");
                                                        response.setCharacterEncoding("UTF-8");
                                                        response.setHeader("Cache-Control", "no-store");
                                                        response.getWriter().write("{\"error\":\"Access denied for this account\"}");
                                                }))

                                .authorizeHttpRequests(auth -> auth

                                                /*
                                                 * Public authentication/bootstrap endpoints.
                                                 */
                                                .requestMatchers(
                                                                "/api/auth/login",
                                                                "/api/auth/send-login-otp",
                                                                "/api/auth/password/forgot",
                                                                "/api/auth/password/reset",
                                                                "/api/auth/csrf",
                                                                "/api/auth/session",
                                                                "/api/public/commercial/**",
                                                                "/api/public/quotes/**",
                                                                "/api/public/tracking/**",
                                                                "/actuator/health",
                                                                "/actuator/prometheus",
                                                                "/actuator/info")
                                                .permitAll()

                                                /*
                                                 * Tenant registration is permanently disabled.
                                                 */
                                                .requestMatchers(
                                                                "/api/auth/register")
                                                .denyAll()

                                                /*
                                                 * USER ADMINISTRATION
                                                 *
                                                 * Only ADMIN can create users, change roles,
                                                 * reset passwords, activate/deactivate users,
                                                 * or view the staff directory.
                                                 */
                                                .requestMatchers("/api/customer/**")
                                                .hasRole("CUSTOMER")

                                                .requestMatchers("/api/platform/**")
                                                .hasAnyRole("ADMIN", "MANAGER", "FINANCE", "OPERATIONS")

                                                .requestMatchers("/api/advanced-logistics/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "OPERATIONS",
                                                                "SALES",
                                                                "FINANCE",
                                                                "DISPATCH",
                                                                "WAREHOUSE",
                                                                "AIR_CARGO")

                                                .requestMatchers("/api/settings/**")
                                                .hasAnyRole("ADMIN", "MANAGER", "FINANCE", "OPERATIONS")

                                                .requestMatchers("/api/users/**")
                                                .hasRole("ADMIN")

                                                .requestMatchers("/api/audit/**")
                                                .hasAnyRole("ADMIN", "MANAGER")

                                                .requestMatchers(
                                                                "/api/reports/**",
                                                                "/api/finance/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "FINANCE")

                                                .requestMatchers("/api/billing/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "FINANCE")

                                                .requestMatchers(
                                                                "/api/commercial/invoices/**",
                                                                "/api/commercial/expenses/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "FINANCE")

                                                .requestMatchers(
                                                                "/api/commercial/quotes/**",
                                                                "/api/commercial/clients/**",
                                                                "/api/commercial/partners/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "SALES")

                                                .requestMatchers("/api/commercial/tasks/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "OPERATIONS",
                                                                "SALES",
                                                                "FINANCE")

                                                .requestMatchers(
                                                                "/api/command-center/import/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "OPERATIONS")

                                                .requestMatchers("/api/command-center/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "OPERATIONS",
                                                                "SALES",
                                                                "FINANCE",
                                                                "DISPATCH",
                                                                "WAREHOUSE",
                                                                "AIR_CARGO")

                                                .requestMatchers(
                                                                org.springframework.http.HttpMethod.GET,
                                                                "/api/shipments/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "OPERATIONS",
                                                                "SALES",
                                                                "FINANCE")

                                                .requestMatchers(
                                                                org.springframework.http.HttpMethod.POST,
                                                                "/api/shipments/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "OPERATIONS",
                                                                "SALES")

                                                .requestMatchers(
                                                                org.springframework.http.HttpMethod.PATCH,
                                                                "/api/shipments/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "OPERATIONS",
                                                                "SALES")

                                                .requestMatchers("/api/operations/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "OPERATIONS",
                                                                "DISPATCH",
                                                                "WAREHOUSE")

                                                .requestMatchers(
                                                                "/api/vehicles/**",
                                                                "/api/drivers/**",
                                                                "/api/trips/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "OPERATIONS",
                                                                "DISPATCH")

                                                .requestMatchers(
                                                                "/api/warehouses/**",
                                                                "/api/inventory/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "OPERATIONS",
                                                                "WAREHOUSE")

                                                .requestMatchers(
                                                                "/api/air-cargo/**",
                                                                "/api/universal/**",
                                                                "/api/ocean/**",
                                                                "/api/road/**",
                                                                "/api/rail/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "OPERATIONS",
                                                                "AIR_CARGO")

                                                .requestMatchers(
                                                                "/api/gps/**",
                                                                "/api/flight-status/**",
                                                                "/api/rating/**",
                                                                "/api/iot/**")
                                                .hasAnyRole(
                                                                "ADMIN",
                                                                "MANAGER",
                                                                "OPERATIONS")

                                                /*
                                                 * Every remaining endpoint requires authentication.
                                                 */
                                                .anyRequest()
                                                .authenticated())

                                .addFilterBefore(
                                                jwtAuthenticationFilter,
                                                UsernamePasswordAuthenticationFilter.class)

                                .addFilterBefore(
                                                authRateLimitFilter,
                                                JwtAuthenticationFilter.class)

                                .addFilterAfter(
                                                browserCsrfFilter,
                                                JwtAuthenticationFilter.class);

                return http.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {

                CorsConfiguration config = new CorsConfiguration();

                List<String> origins = Arrays.stream(allowedOrigins.split(","))
                                .map(String::trim)
                                .filter(origin -> !origin.isBlank())
                                // Render/Vercel environment variables are often entered with a
                                // trailing slash. Browsers send the Origin header without it.
                                .map(SecurityConfig::normalizeOrigin)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList();

                config.setAllowedOrigins(origins);

                config.setAllowedMethods(
                                List.of(
                                                "GET",
                                                "POST",
                                                "PUT",
                                                "DELETE",
                                                "PATCH",
                                                "OPTIONS"));

                config.setAllowedHeaders(
                                List.of(
                                                "Authorization",
                                                "Content-Type",
                                                "Accept",
                                                "Origin",
                                                "X-CSRF-Token",
                                                "X-Request-Id"));

                config.setExposedHeaders(
                                List.of("X-Request-Id"));

                config.setMaxAge(3600L);
                config.setAllowCredentials(true);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

                source.registerCorsConfiguration(
                                "/**",
                                config);

                return source;
        }

        private static String normalizeOrigin(String origin) {
                String value = origin == null ? "" : origin.trim();
                while (value.endsWith("/")) {
                        value = value.substring(0, value.length() - 1);
                }
                if (value.isBlank()) return null;
                if (!value.startsWith("https://") && !value.startsWith("http://")) return null;
                return value;
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder(12);
        }

        @Bean
        public AuthenticationManager authenticationManager(
                        AuthenticationConfiguration configuration)
                        throws Exception {

                return configuration.getAuthenticationManager();
        }
}
