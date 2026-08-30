package com.example.helloworld;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.AvailabilityState;
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

    public enum ToggleState {
        OK, NOTOK
    }

    @GetMapping("/health/{status}")
    public ResponseEntity<String> setHealth(@PathVariable ToggleState status) {
        AvailabilityState state = status == ToggleState.OK ? LivenessState.CORRECT : LivenessState.BROKEN;
        AvailabilityChangeEvent.publish(publisher, this, state);
        return ResponseEntity.ok("health: " + describe(status));
    }

    @GetMapping("/ready/{status}")
    public ResponseEntity<String> setReady(@PathVariable ToggleState status) {
        AvailabilityState state = status == ToggleState.OK ? ReadinessState.ACCEPTING_TRAFFIC : ReadinessState.REFUSING_TRAFFIC;
        AvailabilityChangeEvent.publish(publisher, this, state);
        return ResponseEntity.ok("ready: " + describe(status));
    }

    private static String describe(ToggleState status) {
        return status == ToggleState.OK ? "ok" : "not ok";
    }
}
