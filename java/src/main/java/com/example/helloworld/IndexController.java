package com.example.helloworld;

import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/")
public class IndexController {

    private final BuildProperties buildProperties;
    private final ConfigurableEnvironment environment;

    public IndexController(BuildProperties buildProperties, ConfigurableEnvironment environment) {
        this.buildProperties = buildProperties;
        this.environment = environment;
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        return """
                <html>
                <head><style>
                  body { font-family: monospace; max-width: 600px; margin: 2rem auto; color: #333; }
                  h1   { font-size: 1.2rem; margin-bottom: 0.5rem; }
                  a    { color: #0070f3; text-decoration: none; }
                  a:hover { text-decoration: underline; }
                </style></head>
                <body>
                  <h1>%s %s</h1>
                  <p><a href="/application">/application</a></p>
                </body>
                </html>
                """.formatted(buildProperties.getArtifact(), buildProperties.getVersion());
    }

    @GetMapping(value = "/application", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> applicationProperties() {
        return environment.getPropertySources().stream()
                .filter(ps -> ps.getName().contains("application.properties"))
                .filter(ps -> ps instanceof EnumerablePropertySource<?>)
                .map(ps -> (EnumerablePropertySource<?>) ps)
                .flatMap(ps -> Arrays.stream(ps.getPropertyNames())
                        .map(name -> Map.entry(name, ps.getProperty(name))))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
