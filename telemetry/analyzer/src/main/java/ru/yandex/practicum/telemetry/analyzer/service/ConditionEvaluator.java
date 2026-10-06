package ru.yandex.practicum.telemetry.analyzer.service;

import org.springframework.stereotype.Component;
import ru.yandex.practicum.kafka.telemetry.event.*;
import ru.yandex.practicum.telemetry.analyzer.model.Condition;

@Component
public class ConditionEvaluator {
    public boolean matches(Condition condition, SensorsSnapshotAvro snapshot) {
        var state = snapshot.getSensorsState().get(condition.getSensor().getDeviceId());
        if (state == null || condition.getValue() == null) return false;
        int actual = value(condition.getType(), state.getData());
        int comparison = Integer.compare(actual, condition.getValue());
        return switch (condition.getOperation()) {
            case EQUALS -> comparison == 0;
            case GREATER_THAN -> comparison > 0;
            case LOWER_THAN -> comparison < 0;
        };
    }

    private int value(ConditionTypeAvro type, Object payload) {
        return switch (type) {
            case MOTION -> requirePayload(type, payload, MotionSensorAvro.class).getMotion() ? 1 : 0;
            case SWITCH -> requirePayload(type, payload, SwitchSensorAvro.class).getState() ? 1 : 0;
            case LUMINOSITY -> requirePayload(type, payload, LightSensorAvro.class).getLuminosity();
            case TEMPERATURE -> switch (payload) {
                case ClimateSensorAvro p -> p.getTemperatureC();
                case TemperatureSensorAvro p -> p.getTemperatureC();
                case null -> throw incompatiblePayload(type, null);
                default -> throw incompatiblePayload(type, payload);
            };
            case CO2LEVEL -> requirePayload(type, payload, ClimateSensorAvro.class).getCo2Level();
            case HUMIDITY -> requirePayload(type, payload, ClimateSensorAvro.class).getHumidity();
        };
    }

    private <T> T requirePayload(ConditionTypeAvro type, Object payload, Class<T> expectedType) {
        if (expectedType.isInstance(payload)) return expectedType.cast(payload);
        throw incompatiblePayload(type, payload);
    }

    private IllegalArgumentException incompatiblePayload(ConditionTypeAvro type, Object payload) {
        String actualType = payload == null ? "null" : payload.getClass().getSimpleName();
        return new IllegalArgumentException("Condition " + type + " is incompatible with payload " + actualType);
    }
}
