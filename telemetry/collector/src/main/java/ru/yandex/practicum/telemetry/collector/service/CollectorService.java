package ru.yandex.practicum.telemetry.collector.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.yandex.practicum.kafka.telemetry.event.HubEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.SensorEventAvro;
import ru.yandex.practicum.telemetry.collector.kafka.KafkaEventProducer;
import ru.yandex.practicum.telemetry.collector.mapper.HubEventMapper;
import ru.yandex.practicum.telemetry.collector.mapper.SensorEventMapper;
import ru.yandex.practicum.telemetry.collector.model.hub.HubEvent;
import ru.yandex.practicum.telemetry.collector.model.sensor.SensorEvent;

@Service
@RequiredArgsConstructor
public class CollectorService {

    private final SensorEventMapper sensorEventMapper;
    private final HubEventMapper hubEventMapper;
    private final KafkaEventProducer kafkaEventProducer;

    public void collectSensorEvent(SensorEvent event) {
        SensorEventAvro avroEvent = sensorEventMapper.toAvro(event);
        kafkaEventProducer.sendSensorEvent(avroEvent);
    }

    public void collectHubEvent(HubEvent event) {
        HubEventAvro avroEvent = hubEventMapper.toAvro(event);
        kafkaEventProducer.sendHubEvent(avroEvent);
    }
}
