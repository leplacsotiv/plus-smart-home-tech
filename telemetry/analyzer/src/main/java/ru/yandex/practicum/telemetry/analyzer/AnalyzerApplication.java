package ru.yandex.practicum.telemetry.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ru.yandex.practicum.telemetry.analyzer.processing.*;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootApplication
@EnableConfigurationProperties(AnalyzerProperties.class)
public class AnalyzerApplication {
    public static void main(String[] args) throws InterruptedException {
        try (var context = SpringApplication.run(AnalyzerApplication.class, args)) {
            var hubs = context.getBean(HubEventProcessor.class);
            var snapshots = context.getBean(SnapshotProcessor.class);
            var failure = new AtomicReference<Throwable>();
            Thread hubThread = new Thread(hubs, "hub-events");
            hubThread.setUncaughtExceptionHandler((thread, error) -> {
                failure.set(error);
                snapshots.shutdown();
            });
            hubThread.start();
            try {
                snapshots.run();
            } finally {
                hubs.shutdown();
                hubThread.join(20000);
            }
            if (failure.get() != null) throw new IllegalStateException("Hub processor failed", failure.get());
        }
    }
}
