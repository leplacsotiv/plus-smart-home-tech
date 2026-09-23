package ru.yandex.practicum.telemetry.analyzer.processing;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.kafka.telemetry.event.SensorsSnapshotAvro;
import ru.yandex.practicum.kafka.telemetry.serialization.SensorsSnapshotDeserializer;
import ru.yandex.practicum.telemetry.analyzer.AnalyzerProperties;
import ru.yandex.practicum.telemetry.analyzer.service.*;

@Component
public class SnapshotProcessor implements Runnable {
    private final KafkaEventLoop<SensorsSnapshotAvro> loop;

    public SnapshotProcessor(AnalyzerProperties config, ScenarioService scenarios, HubRouterClient client) {
        loop = new KafkaEventLoop<>(config.snapshots(), new SensorsSnapshotDeserializer(), snapshot -> scenarios.evaluate(snapshot).forEach(client::send));
    }

    @Override
    public void run() { loop.run(); }

    @PreDestroy
    public void shutdown() { loop.shutdown(); }
}
