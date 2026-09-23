package ru.yandex.practicum.telemetry.collector.mapper;

import org.springframework.stereotype.Component;
import ru.yandex.practicum.grpc.telemetry.event.SensorEventProto;
import ru.yandex.practicum.kafka.telemetry.event.*;

@Component
public class SensorEventMapper {
    public SensorEventAvro toAvro(SensorEventProto event) {
        Object payload = switch (event.getPayloadCase()) {
            case MOTION_SENSOR -> {
                var p = event.getMotionSensor();
                yield new MotionSensorAvro(p.getLinkQuality(), p.getMotion(), p.getVoltage());
            }
            case TEMPERATURE_SENSOR -> {
                var p = event.getTemperatureSensor();
                yield new TemperatureSensorAvro(p.getTemperatureC(), p.getTemperatureF());
            }
            case LIGHT_SENSOR -> {
                var p = event.getLightSensor();
                yield new LightSensorAvro(p.getLinkQuality(), p.getLuminosity());
            }
            case CLIMATE_SENSOR -> {
                var p = event.getClimateSensor();
                yield new ClimateSensorAvro(p.getTemperatureC(), p.getHumidity(), p.getCo2Level());
            }
            case SWITCH_SENSOR -> new SwitchSensorAvro(event.getSwitchSensor().getState());
            case PAYLOAD_NOT_SET -> throw new IllegalArgumentException("Sensor payload is required");
        };
        return new SensorEventAvro(ProtoValues.required(event.getId(), "id"),
                ProtoValues.required(event.getHubId(), "hubId"),
                ProtoValues.timestamp(event.getTimestamp(), event.hasTimestamp()), payload);
    }
}
