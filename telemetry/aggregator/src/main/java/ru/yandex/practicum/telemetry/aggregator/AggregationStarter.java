package ru.yandex.practicum.telemetry.aggregator;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.kafka.telemetry.event.SensorEventAvro;
import ru.yandex.practicum.kafka.telemetry.serialization.*;
import org.apache.avro.specific.SpecificRecordBase;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AggregationStarter {
    private static final Logger log = LoggerFactory.getLogger(AggregationStarter.class);
    private final AggregatorProperties config;
    private final SnapshotService snapshots;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile KafkaConsumer<String, SensorEventAvro> consumer;
    private volatile Thread owner;

    public AggregationStarter(AggregatorProperties config, SnapshotService snapshots) {
        this.config = config;
        this.snapshots = snapshots;
    }

    public void start() {
        owner = Thread.currentThread();
        var offsets = new HashMap<TopicPartition, OffsetAndMetadata>();
        try (var producer = new KafkaProducer<String, SpecificRecordBase>(config.producer(),
                new StringSerializer(), new AvroSerializer());
             var input = new KafkaConsumer<String, SensorEventAvro>(config.consumer(),
                     new StringDeserializer(), new SensorEventDeserializer())) {
            consumer = input;
            input.subscribe(List.of(config.inputTopic()), new ConsumerRebalanceListener() {
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
                        if (record.value() != null) {
                            var changed = snapshots.update(record.value());
                            if (changed.isPresent()) {
                                var snapshot = changed.get();
                                producer.send(new ProducerRecord<>(config.outputTopic(), snapshot.getHubId(), snapshot)).get();
                            }
                        }
                        offsets.put(new TopicPartition(record.topic(), record.partition()),
                                new OffsetAndMetadata(record.offset() + 1));
                    }
                    if (!offsets.isEmpty()) {
                        input.commitAsync(new HashMap<>(offsets), (committed, error) -> {
                            if (error != null) log.warn("Offset commit failed: {}", committed, error);
                        });
                    }
                }
            } catch (WakeupException e) {
                if (running.get()) throw e;
            } finally {
                producer.flush();
                if (!offsets.isEmpty()) input.commitSync(offsets, Duration.ofSeconds(10));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Aggregator interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Snapshot publication failed; offset not committed", e.getCause());
        } finally {
            stopped.countDown();
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        var input = consumer;
        if (input != null && stopped.getCount() != 0) input.wakeup();
        if (owner != null && owner != Thread.currentThread()) {
            try {
                if (!stopped.await(20, TimeUnit.SECONDS)) log.warn("Aggregator shutdown timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
