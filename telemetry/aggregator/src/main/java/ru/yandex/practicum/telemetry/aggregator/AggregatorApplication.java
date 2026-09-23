package ru.yandex.practicum.telemetry.aggregator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AggregatorProperties.class)
public class AggregatorApplication {
    public static void main(String[] args) {
        try (var context = SpringApplication.run(AggregatorApplication.class, args)) {
            context.getBean(AggregationStarter.class).start();
        }
    }
}
