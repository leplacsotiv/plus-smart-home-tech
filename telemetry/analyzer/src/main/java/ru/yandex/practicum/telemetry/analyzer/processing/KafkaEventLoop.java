package ru.yandex.practicum.telemetry.analyzer.processing;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.yandex.practicum.telemetry.analyzer.AnalyzerProperties;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Each instance owns exactly one KafkaConsumer, accessed only by its run thread except for wakeup. */
final class KafkaEventLoop<T> implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventLoop.class);
    private final AnalyzerProperties.Stream config;
    private final Deserializer<T> deserializer;
    private final Consumer<T> handler;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile KafkaConsumer<String, T> consumer;
    private volatile Thread owner;

    KafkaEventLoop(AnalyzerProperties.Stream config, Deserializer<T> deserializer, Consumer<T> handler) {
        this.config = config;
        this.deserializer = deserializer;
        this.handler = handler;
    }

    @Override
    public void run() {
        owner = Thread.currentThread();
        var offsets = new HashMap<TopicPartition, OffsetAndMetadata>();
        try (var input = new KafkaConsumer<String, T>(config.consumer(), new StringDeserializer(), deserializer)) {
            consumer = input;
            input.subscribe(List.of(config.topic()), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    if (!offsets.isEmpty()) input.commitSync(offsets);
                    partitions.forEach(offsets::remove);
                }
                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) { }
            });
            try {
                while (running.get()) {
                    for (var record : input.poll(config.pollTimeout())) {
                        if (!running.get()) break;
                        if (record.value() != null) handler.accept(record.value());
                        offsets.put(new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset() + 1));
                    }
                    if (!offsets.isEmpty()) input.commitAsync(new HashMap<>(offsets), (committed, error) -> {
                        if (error != null) log.warn("Offset commit failed for {}: {}", config.topic(), committed, error);
                    });
                }
            } catch (WakeupException e) {
                if (running.get()) throw e;
            } finally {
                if (!offsets.isEmpty()) {
                    try {
                        input.commitSync(offsets, Duration.ofSeconds(10));
                    } catch (RuntimeException e) {
                        log.error("Final offset commit failed: topic={}", config.topic(), e);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.error("Consumer stopped; failed record remains uncommitted: topic={}", config.topic(), e);
        } finally {
            stopped.countDown();
        }
    }

    void shutdown() {
        running.set(false);
        var input = consumer;
        if (input != null && stopped.getCount() != 0) input.wakeup();
        if (owner != null && owner != Thread.currentThread()) {
            try {
                if (!stopped.await(20, TimeUnit.SECONDS)) log.warn("Consumer shutdown timed out: {}", config.topic());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
