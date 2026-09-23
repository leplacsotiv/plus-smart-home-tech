package ru.yandex.practicum.telemetry.analyzer.processing;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.kafka.telemetry.event.HubEventAvro;
import ru.yandex.practicum.kafka.telemetry.serialization.HubEventDeserializer;
import ru.yandex.practicum.telemetry.analyzer.AnalyzerProperties;
import ru.yandex.practicum.telemetry.analyzer.service.*;

@Component
public class HubEventProcessor implements Runnable {
    private final KafkaEventLoop<HubEventAvro> loop;

    public HubEventProcessor(AnalyzerProperties config, HubEventService events) {
        loop = new KafkaEventLoop<>(config.hubs(), new HubEventDeserializer(), events::process);
    }

    @Override
    public void run() { loop.run(); }

    @PreDestroy
    public void shutdown() { loop.shutdown(); }
}
