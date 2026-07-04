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
    private final String podName = System.getenv().getOrDefault("HOSTNAME", "unknown");

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
                  ul   { padding-left: 1rem; }
                  li   { margin: 0.3rem 0; }
                  a    { color: #0070f3; text-decoration: none; }
                  a:hover { text-decoration: underline; }
                  h2   { font-size: 1rem; margin-top: 1.2rem; margin-bottom: 0.3rem; }
                </style></head>
                <body>
                  <h1>%s %s</h1>
                  <p style="color:#888;font-size:0.85rem;">pod: %s</p>
                  <h2>Info</h2>
                  <ul>
                    <li><a href="/application">/application</a></li>
                    <li><a href="/db">/db</a></li>
                    <li><a href="/version">/version</a></li>
                  </ul>
                  <h2>Probes</h2>
                  <ul>
                    <li><a href="/actuator/health/liveness">/actuator/health/liveness</a></li>
                    <li><a href="/actuator/health/readiness">/actuator/health/readiness</a></li>
                  </ul>
                  <h2>Controls</h2>
                  <ul>
                    <li><a href="/control/health/ok">/control/health/ok</a></li>
                    <li><a href="/control/health/notok">/control/health/notok</a></li>
                    <li><a href="/control/ready/ok">/control/ready/ok</a></li>
                    <li><a href="/control/ready/notok">/control/ready/notok</a></li>
                  </ul>
                </body>
                </html>
                """.formatted(buildProperties.getArtifact(), buildProperties.getVersion(), podName);
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
