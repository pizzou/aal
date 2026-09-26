package com.logiplatform.config;

import com.logiplatform.security.AuthRateLimitFilter;
import com.logiplatform.security.BrowserCsrfFilter;
import com.logiplatform.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
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
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
            .csrf(csrf -> csrf.disable())

            .headers(headers -> headers
                .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer ->
                    referrer.policy(
                        org.springframework.security.web.header.writers
                            .ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(hsts ->
                    hsts.includeSubDomains(true)
                        .maxAgeInSeconds(31536000))
            )

            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .exceptionHandling(exceptions -> exceptions

                .authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.setHeader("Cache-Control", "no-store");
                    response.getWriter().write(
                        "{\"error\":\"Authentication required\"}"
                    );
                })

                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.setHeader("Cache-Control", "no-store");
                    response.getWriter().write(
                        "{\"error\":\"Access denied for this account\"}"
                    );
                })
            )

            .authorizeHttpRequests(auth -> auth

                /*
                 * PUBLIC
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/auth/login"),
                    new AntPathRequestMatcher("/api/auth/send-login-otp"),
                    new AntPathRequestMatcher("/api/auth/password/forgot"),
                    new AntPathRequestMatcher("/api/auth/password/reset"),
                    new AntPathRequestMatcher("/api/auth/csrf"),

                    new AntPathRequestMatcher("/api/public/**"),
                    new AntPathRequestMatcher("/api/public/commercial/**"),
                    new AntPathRequestMatcher("/api/public/quotes/**"),
                    new AntPathRequestMatcher("/api/public/tracking/**"),

                    new AntPathRequestMatcher("/actuator/health"),
                    new AntPathRequestMatcher("/actuator/prometheus"),
                    new AntPathRequestMatcher("/actuator/info")
                )
                .permitAll()

                /*
                 * SESSION MUST BE AUTHENTICATED.
                 *
                 * This is deliberately NOT public. It validates the
                 * authenticated staff session after OTP login.
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/auth/session")
                )
                .authenticated()

                /*
                 * REGISTRATION DISABLED
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/auth/register")
                )
                .denyAll()

                /*
                 * CUSTOMER
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/customer/**")
                )
                .hasRole("CUSTOMER")

                /*
                 * PLATFORM / SETTINGS
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/platform/**"),
                    new AntPathRequestMatcher("/api/settings/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "FINANCE",
                    "OPERATIONS"
                )

                /*
                 * USERS
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/users/**")
                )
                .hasRole("ADMIN")

                /*
                 * AUDIT
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/audit/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER"
                )

                /*
                 * DATA QUALITY
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/data-quality/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "OPERATIONS",
                    "FINANCE"
                )

                /*
                 * REPORTING / FINANCE
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/reports/**"),
                    new AntPathRequestMatcher("/api/finance/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "FINANCE"
                )

                /*
                 * BILLING
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/billing/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "FINANCE"
                )

                /*
                 * COMMERCIAL FINANCE
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/commercial/invoices/**"),
                    new AntPathRequestMatcher("/api/commercial/expenses/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "FINANCE"
                )

                /*
                 * COMMERCIAL SALES
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/commercial/quotes/**"),
                    new AntPathRequestMatcher("/api/commercial/clients/**"),
                    new AntPathRequestMatcher("/api/commercial/partners/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "SALES"
                )

                /*
                 * COMMERCIAL TASKS
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/commercial/tasks/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "OPERATIONS",
                    "SALES",
                    "FINANCE"
                )

                /*
                 * COMMAND CENTER IMPORT
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/command-center/import/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "OPERATIONS"
                )

                /*
                 * COMMAND CENTER
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/command-center/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "OPERATIONS",
                    "SALES",
                    "FINANCE",
                    "DISPATCH",
                    "WAREHOUSE",
                    "AIR_CARGO"
                )

                

                /*
                 * SHIPMENTS - READ
                 */
                .requestMatchers(new AntPathRequestMatcher("/api/shipments/**", HttpMethod.GET.name()))
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "OPERATIONS",
                    "SALES",
                    "FINANCE"
                )

                /*
                 * SHIPMENTS - CREATE
                 */
                .requestMatchers(new AntPathRequestMatcher("/api/shipments/**", HttpMethod.POST.name()))
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "OPERATIONS",
                    "SALES"
                )

                /*
                 * SHIPMENTS - UPDATE
                 */
                .requestMatchers(new AntPathRequestMatcher("/api/shipments/**", HttpMethod.PATCH.name()))
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "OPERATIONS",
                    "SALES"
                )

                /*
                 * OPERATIONS
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/operations/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "OPERATIONS",
                    "DISPATCH",
                    "WAREHOUSE"
                )

                /*
                 * FLEET
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/vehicles/**"),
                    new AntPathRequestMatcher("/api/drivers/**"),
                    new AntPathRequestMatcher("/api/trips/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "OPERATIONS",
                    "DISPATCH"
                )

                /*
                 * WAREHOUSE
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/warehouses/**"),
                    new AntPathRequestMatcher("/api/inventory/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "OPERATIONS",
                    "WAREHOUSE"
                )

                /*
                 * MULTIMODAL
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/air-cargo/**"),
                    new AntPathRequestMatcher("/api/universal/**"),
                    new AntPathRequestMatcher("/api/ocean/**"),
                    new AntPathRequestMatcher("/api/road/**"),
                    new AntPathRequestMatcher("/api/rail/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "OPERATIONS",
                    "AIR_CARGO"
                )

                /*
                 * GPS / IOT / EXTERNAL STATUS
                 */
                .requestMatchers(
                    new AntPathRequestMatcher("/api/gps/**"),
                    new AntPathRequestMatcher("/api/flight-status/**"),
                    new AntPathRequestMatcher("/api/rating/**"),
                    new AntPathRequestMatcher("/api/iot/**")
                )
                .hasAnyRole(
                    "ADMIN",
                    "MANAGER",
                    "OPERATIONS"
                )

                /*
                 * EVERYTHING ELSE
                 */
                .anyRequest()
                .authenticated()
            )

            /*
             * JWT MUST RUN BEFORE AUTHORIZATION.
             */
            .addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class
            )

            .addFilterBefore(
                authRateLimitFilter,
                JwtAuthenticationFilter.class
            )

            .addFilterAfter(
                browserCsrfFilter,
                JwtAuthenticationFilter.class
            );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
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
                "OPTIONS"
            )
        );

        config.setAllowedHeaders(
            List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Origin",
                "X-CSRF-Token",
                "X-Request-Id"
            )
        );

        config.setExposedHeaders(
            List.of("X-Request-Id")
        );

        config.setMaxAge(3600L);
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
            new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
            "/**",
            config
        );

        return source;
    }

    private static String normalizeOrigin(String origin) {

        String value = origin == null ? "" : origin.trim();

        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        if (value.isBlank()) {
            return null;
        }

        if (!value.startsWith("https://")
                && !value.startsWith("http://")) {
            return null;
        }

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