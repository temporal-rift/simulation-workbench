package io.github.temporalrift.workbench.shared.infrastructure.config;

import java.util.Collection;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Converts bearer JWT scopes ({@code scope} / {@code scp} claims) into {@code SCOPE_*} authorities
 * so {@code simulation:read} / {@code simulation:write} can be enforced by the filter chain.
 */
public class SimulationAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt source) {
        Collection<GrantedAuthority> authorities = List.of();
        var scope = source.getClaimAsString("scope");
        if (scope == null) {
            var scp = source.getClaimAsStringList("scp");
            if (scp != null) {
                authorities = scp.stream()
                        .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s))
                        .toList();
            }
        } else {
            authorities = List.of(scope.split(" ")).stream()
                    .filter(s -> !s.isBlank())
                    .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s))
                    .toList();
        }
        return new JwtAuthenticationToken(source, authorities);
    }
}
