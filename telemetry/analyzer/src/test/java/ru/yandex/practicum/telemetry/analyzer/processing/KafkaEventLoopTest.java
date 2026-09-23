package ru.yandex.practicum.telemetry.analyzer.processing;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import ru.yandex.practicum.telemetry.analyzer.AnalyzerProperties;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes", "unchecked"})
class KafkaEventLoopTest {
    private final TopicPartition partition = new TopicPartition("events", 0);
    private final AnalyzerProperties.Stream config = new AnalyzerProperties.Stream("events", Duration.ofMillis(10), Map.of());

    private ConsumerRecords<String, String> records() {
        return new ConsumerRecords<>(Map.of(partition, List.of(
                new ConsumerRecord<>("events", 0, 0, "hub", "first"),
                new ConsumerRecord<>("events", 0, 1, "hub", "second"))));
    }

    @Test
    void commitsOnlySuccessfullyHandledRecordsWhenHandlerFails() {
        try (var construction = mockConstruction(KafkaConsumer.class, (input, context) ->
                when(input.poll(any(Duration.class))).thenReturn(records()))) {
            var loop = new KafkaEventLoop<String>(config, new StringDeserializer(), value -> {
                if (value.equals("second")) throw new IllegalStateException("downstream unavailable");
            });
            assertThrows(IllegalStateException.class, loop::run);
            var input = construction.constructed().getFirst();
            verify(input).commitSync(Map.of(partition, new OffsetAndMetadata(1)), Duration.ofSeconds(10));
            verify(input, never()).commitAsync(anyMap(), any());
            verify(input).close();
        }
    }

    @Test
    void doesNotCommitFailedFirstRecord() {
        try (var construction = mockConstruction(KafkaConsumer.class, (input, context) ->
                when(input.poll(any(Duration.class))).thenReturn(records()))) {
            var loop = new KafkaEventLoop<String>(config, new StringDeserializer(), value -> {
                throw new IllegalStateException("database unavailable");
            });
            assertThrows(IllegalStateException.class, loop::run);
            var input = construction.constructed().getFirst();
            verify(input, never()).commitSync(anyMap(), any(Duration.class));
            verify(input).close();
        }
    }

    @Test
    void wakeupCommitsCompletedBatchAndClosesConsumer() {
        var processed = new ArrayList<String>();
        var loop = new KafkaEventLoop<String>(config, new StringDeserializer(), processed::add);
        var calls = new AtomicInteger();
        try (var construction = mockConstruction(KafkaConsumer.class, (input, context) ->
                when(input.poll(any(Duration.class))).thenAnswer(call -> {
                    if (calls.getAndIncrement() == 0) return records();
                    loop.shutdown();
                    throw new WakeupException();
                }))) {
            loop.run();
            var input = construction.constructed().getFirst();
            assertEquals(List.of("first", "second"), processed);
            verify(input).wakeup();
            verify(input).commitAsync(eq(Map.of(partition, new OffsetAndMetadata(2))), any());
            verify(input).commitSync(Map.of(partition, new OffsetAndMetadata(2)), Duration.ofSeconds(10));
            verify(input).close();
        }
    }
}
