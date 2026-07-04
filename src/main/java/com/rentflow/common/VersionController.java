package com.rentflow.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A tiny read-only endpoint that reports the app's name/version.
 * Proves our own controller layer works end to end (separate from Actuator).
 *
 * @RestController = this class handles HTTP and returns JSON bodies.
 * The values come from the info.app.* keys in application.yml.
 */
@RestController
@RequestMapping("/api")
public class VersionController {

    private final String name;
    private final String version;
    private final String description;

    // @Value injects config values; the part after ':' is a fallback default.
    public VersionController(
            @Value("${info.app.name:RentFlow Backend}") String name,
            @Value("${info.app.version:unknown}") String version,
            @Value("${info.app.description:}") String description) {
        this.name = name;
        this.version = version;
        this.description = description;
    }

    @GetMapping("/version")
    public Map<String, String> version() {
        return Map.of(
                "name", name,
                "version", version,
                "description", description,
                "status", "OK"
        );
    }
}
