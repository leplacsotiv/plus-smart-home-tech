package ru.yandex.practicum.telemetry.analyzer.service;

import org.springframework.stereotype.Component;
import ru.yandex.practicum.kafka.telemetry.event.*;
import ru.yandex.practicum.telemetry.analyzer.model.Condition;

@Component
public class ConditionEvaluator {
    public boolean matches(Condition condition, SensorsSnapshotAvro snapshot) {
        var state = snapshot.getSensorsState().get(condition.getSensor().getDeviceId());
        if (state == null || condition.getValue() == null) return false;
        Integer actual = value(condition.getType(), state.getData());
        if (actual == null) return false;
        int comparison = Integer.compare(actual, condition.getValue());
        return switch (condition.getOperation()) {
            case EQUALS -> comparison == 0;
            case GREATER_THAN -> comparison > 0;
            case LOWER_THAN -> comparison < 0;
        };
    }

    private Integer value(ConditionTypeAvro type, Object payload) {
        return switch (type) {
            case MOTION -> payload instanceof MotionSensorAvro p ? (p.getMotion() ? 1 : 0) : null;
            case SWITCH -> payload instanceof SwitchSensorAvro p ? (p.getState() ? 1 : 0) : null;
            case LUMINOSITY -> payload instanceof LightSensorAvro p ? p.getLuminosity() : null;
            case TEMPERATURE -> switch (payload) {
                case ClimateSensorAvro p -> p.getTemperatureC();
                case TemperatureSensorAvro p -> p.getTemperatureC();
                default -> null;
            };
            case CO2LEVEL -> payload instanceof ClimateSensorAvro p ? p.getCo2Level() : null;
            case HUMIDITY -> payload instanceof ClimateSensorAvro p ? p.getHumidity() : null;
        };
    }
}
