package com.example.helloworld;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/control")
public class ProbeController {

    private final ApplicationEventPublisher publisher;

    public ProbeController(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @GetMapping("/health/{status}")
    public ResponseEntity<String> setHealth(@PathVariable String status) {
        return switch (status.toLowerCase()) {
            case "ok" -> {
                AvailabilityChangeEvent.publish(publisher, this, LivenessState.CORRECT);
                yield ResponseEntity.ok("health: ok");
            }
            case "notok" -> {
                AvailabilityChangeEvent.publish(publisher, this, LivenessState.BROKEN);
                yield ResponseEntity.ok("health: not ok");
            }
            default -> ResponseEntity.badRequest().body("use 'ok' or 'notok'");
        };
    }

    @GetMapping("/ready/{status}")
    public ResponseEntity<String> setReady(@PathVariable String status) {
        return switch (status.toLowerCase()) {
            case "ok" -> {
                AvailabilityChangeEvent.publish(publisher, this, ReadinessState.ACCEPTING_TRAFFIC);
                yield ResponseEntity.ok("ready: ok");
            }
            case "notok" -> {
                AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC);
                yield ResponseEntity.ok("ready: not ok");
            }
            default -> ResponseEntity.badRequest().body("use 'ok' or 'notok'");
        };
    }
}
