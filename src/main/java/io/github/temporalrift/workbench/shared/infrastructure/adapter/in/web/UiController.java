package io.github.temporalrift.workbench.shared.infrastructure.adapter.in.web;

import java.util.LinkedHashMap;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.shared.infrastructure.config.UiProperties;

/** Serves the designer application's runtime configuration and its client-side routes. */
@Controller
class UiController {

    // Top-level paths owned by the API, the operational endpoints and the built assets are never client routes.
    private static final String ROUTE = "/{first:(?!api$|actuator$|assets$)[^.]+}";

    private final UiProperties properties;
    private final ObjectMapper objectMapper;

    UiController(UiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Read before the application bundle runs, so it is generated from this service's own configuration. */
    @GetMapping(value = "/config.js", produces = "text/javascript")
    ResponseEntity<String> config() {
        var config = new LinkedHashMap<String, String>();
        config.put("oidcIssuerUrl", properties.oidcIssuerUrl());
        config.put("oidcClientId", properties.oidcClientId());
        if (properties.oidcAudience() != null && !properties.oidcAudience().isBlank()) {
            config.put("oidcAudience", properties.oidcAudience());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body("window.__APP_CONFIG__ = " + objectMapper.writeValueAsString(config) + ";\n");
    }

    /** A deep link opened in a browser gets the application, which routes it; API clients never receive HTML. */
    @GetMapping(
            value = {ROUTE, ROUTE + "/**"},
            produces = MediaType.TEXT_HTML_VALUE)
    String route(@PathVariable("first") String first) {
        return "forward:/index.html";
    }
}
