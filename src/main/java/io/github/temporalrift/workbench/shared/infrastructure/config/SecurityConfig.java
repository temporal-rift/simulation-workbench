package io.github.temporalrift.workbench.shared.infrastructure.config;

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
public class SecurityConfig {

    private static final String WRITE_SCOPE = "SCOPE_simulation:write";
    private static final String READ_SCOPE = "SCOPE_simulation:read";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/experiments", "/api/v1/comparisons")
                        .hasAuthority(WRITE_SCOPE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/experiments/*/runs")
                        .hasAuthority(WRITE_SCOPE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/cancel", "/api/v1/runs/*/resume")
                        .hasAuthority(WRITE_SCOPE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/cases/*/reproductions")
                        .hasAuthority(WRITE_SCOPE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/**")
                        .hasAuthority(READ_SCOPE)
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.authenticationEntryPoint(new UnauthorizedEntryPoint(objectMapper))
                                .jwt(jwt -> jwt.jwtAuthenticationConverter(new SimulationAuthenticationConverter())))
                .exceptionHandling(
                        exceptions -> exceptions.accessDeniedHandler(new SimulationAccessDeniedHandler(objectMapper)))
                .build();
    }
}
