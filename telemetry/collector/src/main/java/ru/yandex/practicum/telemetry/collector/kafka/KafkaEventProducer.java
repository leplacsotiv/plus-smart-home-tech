package ru.yandex.practicum.telemetry.collector.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.kafka.telemetry.event.HubEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.SensorEventAvro;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventProducer {

    private final KafkaProducer<String, SpecificRecordBase> producer;

    @Value("${collector.kafka.topics.sensors}")
    private String sensorsTopic;

    @Value("${collector.kafka.topics.hubs}")
    private String hubsTopic;

    public void sendSensorEvent(SensorEventAvro event) {
        send(sensorsTopic, event.getHubId(), event);
    }

    public void sendHubEvent(HubEventAvro event) {
        send(hubsTopic, event.getHubId(), event);
    }

    private void send(
            String topic,
            String key,
            SpecificRecordBase event
    ) {
        ProducerRecord<String, SpecificRecordBase> record =
                new ProducerRecord<>(topic, key, event);

        try {
            // Acknowledge the RPC only after Kafka has acknowledged the event.
            producer.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka publication interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Kafka publication failed", e.getCause());
        }
    }
}
