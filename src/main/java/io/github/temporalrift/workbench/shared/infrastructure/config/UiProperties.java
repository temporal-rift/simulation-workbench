package io.github.temporalrift.workbench.shared.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the browser application needs to sign a designer in. It is the identity provider the service already
 * trusts for bearer tokens, plus the public client the application identifies itself as; no secret is involved.
 */
@ConfigurationProperties("workbench.ui")
public record UiProperties(String oidcIssuerUrl, String oidcClientId, String oidcAudience) {}
