package io.github.temporalrift.workbench.shared.infrastructure.config;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@EnableWebSecurity
@Configuration
@EnableConfigurationProperties(UiProperties.class)
public class SecurityConfig {

    private static final String WRITE_SCOPE = "SCOPE_simulation:write";
    private static final String READ_SCOPE = "SCOPE_simulation:read";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper, UiProperties ui) {
        return http.csrf(AbstractHttpConfigurer::disable)
                .headers(headers ->
                        headers.contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy(ui))))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/experiments",
                                "/api/v1/experiment-previews",
                                "/api/v1/comparisons")
                        .hasAuthority(WRITE_SCOPE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/experiments/*/runs")
                        .hasAuthority(WRITE_SCOPE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/cancel", "/api/v1/runs/*/resume")
                        .hasAuthority(WRITE_SCOPE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/cases/*/reproductions")
                        .hasAuthority(WRITE_SCOPE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/**")
                        .hasAuthority(READ_SCOPE)
                        .requestMatchers("/api/**", "/actuator/**")
                        .authenticated()
                        // The designer application's own pages and assets; everything it reads goes through /api/.
                        .requestMatchers(HttpMethod.GET, "/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.authenticationEntryPoint(new UnauthorizedEntryPoint(objectMapper))
                                .jwt(jwt -> jwt.jwtAuthenticationConverter(new SimulationAuthenticationConverter())))
                .exceptionHandling(
                        exceptions -> exceptions.accessDeniedHandler(new SimulationAccessDeniedHandler(objectMapper)))
                .build();
    }

    /** The application loads only its own scripts and styles; it may call this service and its identity provider. */
    private static String contentSecurityPolicy(UiProperties ui) {
        var connect = new StringBuilder("'self'");
        if (ui.oidcIssuerUrl() != null && !ui.oidcIssuerUrl().isBlank()) {
            var issuer = URI.create(ui.oidcIssuerUrl());
            connect.append(' ').append(issuer.getScheme()).append("://").append(issuer.getAuthority());
        }
        return "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src " + connect
                + "; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
    }
}
