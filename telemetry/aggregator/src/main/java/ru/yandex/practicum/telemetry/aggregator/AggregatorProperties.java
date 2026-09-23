package ru.yandex.practicum.telemetry.aggregator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("aggregator")
public record AggregatorProperties(Map<String, Object> consumer, Map<String, Object> producer,
                                   String inputTopic, String outputTopic, Duration pollTimeout) {
}
