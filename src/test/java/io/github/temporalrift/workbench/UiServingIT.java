package io.github.temporalrift.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/** The designer application is served on this service's own origin; the API behind it stays bearer-protected. */
@WorkbenchIntegrationTest
class UiServingIT {

    private static final Pattern ASSET = Pattern.compile("/assets/[^\"']+\\.js");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theApplicationIsServedWithoutATokenAtTheRootAndAtItsAssets() throws Exception {
        var page = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<div id=\"root\">")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        mockMvc.perform(get("/")).andExpect(status().isOk());
        var asset = ASSET.matcher(page);
        assertThat(asset.find()).as("the page references a built script").isTrue();
        mockMvc.perform(get(asset.group()))
                .andExpect(status().isOk())
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<div id=\"root\">"))));
    }

    @Test
    void aDeepLinkOpenedInABrowserGetsTheApplication() throws Exception {
        mockMvc.perform(get("/runs/6f3f2a52-4f0a-4c69-9a39-2a8f0e2b9d11").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/experiments/new").accept(MediaType.TEXT_HTML)).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/auth/callback").param("code", "abc").accept(MediaType.TEXT_HTML))
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void theRuntimeConfigurationNamesTheIdentityProviderAndIsNeverCached() throws Exception {
        mockMvc.perform(get("/config.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("window.__APP_CONFIG__ = {")))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.containsString(
                                "\"oidcIssuerUrl\":\"http://localhost:9000/default\"")))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.containsString("\"oidcClientId\":\"simulation-workbench-ui\"")));
    }

    @Test
    void theApplicationMayCallOnlyItsOwnOriginAndItsIdentityProvider() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(header().string(
                                "Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self'")))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                org.hamcrest.Matchers.containsString("connect-src 'self' http://localhost:9000")));
    }

    @Test
    void theApiStillRequiresATokenEvenWhenAskedForHtml() throws Exception {
        mockMvc.perform(get("/api/v1/runs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/runs").accept(MediaType.TEXT_HTML)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/nothing-here").accept(MediaType.TEXT_HTML)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownApiPathIsNeverAnsweredWithTheApplication() throws Exception {
        mockMvc.perform(get("/api/v1/nothing-here")
                        .accept(MediaType.TEXT_HTML)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))))
                .andExpect(status().is4xxClientError())
                .andExpect(forwardedUrl(null));
        mockMvc.perform(get("/api/v1/policies")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }
}
