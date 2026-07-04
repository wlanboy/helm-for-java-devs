package com.example.helloworld;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/version")
public class VersionController {

    private final String versionLabel;
    private final String podName = System.getenv().getOrDefault("HOSTNAME", "unknown");

    public VersionController(@Value("${app.version-label:unknown}") String versionLabel) {
        this.versionLabel = versionLabel;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> version() {
        return Map.of(
                "versionLabel", versionLabel,
                "pod", podName
        );
    }
}
