package com.example.helloworld;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class ToggleStateConverter implements Converter<String, ProbeController.ToggleState> {

    @Override
    public ProbeController.ToggleState convert(String source) {
        return ProbeController.ToggleState.valueOf(source.toUpperCase());
    }
}
