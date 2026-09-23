package ru.yandex.practicum.telemetry.analyzer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("analyzer")
public record AnalyzerProperties(Stream hubs, Stream snapshots, Duration actionTimeout) {
    public record Stream(String topic, Duration pollTimeout, Map<String, Object> consumer) { }
}
