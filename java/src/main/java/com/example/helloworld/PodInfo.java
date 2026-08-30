package com.example.helloworld;

import org.springframework.stereotype.Component;

@Component
public class PodInfo {

    private final String podName;

    public PodInfo() {
        this.podName = System.getenv().getOrDefault("HOSTNAME", "unknown");
    }

    public String podName() {
        return podName;
    }
}
