package com.example.helloworld;

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
@RequestMapping("/application")
public class ApplicationPropertiesController {

    private final ConfigurableEnvironment environment;

    public ApplicationPropertiesController(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
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
