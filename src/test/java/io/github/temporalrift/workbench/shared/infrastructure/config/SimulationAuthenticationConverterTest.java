package io.github.temporalrift.workbench.shared.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SimulationAuthenticationConverterTest {

    private final SimulationAuthenticationConverter converter = new SimulationAuthenticationConverter();

    @Test
    void spaceSeparatedScopeClaimBecomesAuthorities() {
        var jwt = jwt(Map.of("scope", "simulation:read simulation:write"));

        var authentication = converter.convert(jwt);

        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("SCOPE_simulation:read", "SCOPE_simulation:write");
    }

    @Test
    void scpListClaimBecomesAuthorities() {
        var jwt = jwt(Map.of("scp", List.of("simulation:read")));

        var authentication = converter.convert(jwt);

        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("SCOPE_simulation:read");
    }

    @Test
    void missingScopeClaimsYieldNoAuthorities() {
        var authentication = converter.convert(jwt(Map.of("sub", "designer")));

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt(
                "token",
                Instant.parse("2026-10-07T00:00:00Z"),
                Instant.parse("2026-10-07T01:00:00Z"),
                Map.of("alg", "none"),
                claims);
    }
}
