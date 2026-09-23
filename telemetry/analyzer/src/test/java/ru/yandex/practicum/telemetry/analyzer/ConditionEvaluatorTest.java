package ru.yandex.practicum.telemetry.analyzer;

import org.junit.jupiter.api.Test;
import ru.yandex.practicum.kafka.telemetry.event.*;
import ru.yandex.practicum.telemetry.analyzer.model.Condition;
import ru.yandex.practicum.telemetry.analyzer.model.Sensor;
import ru.yandex.practicum.telemetry.analyzer.service.ConditionEvaluator;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConditionEvaluatorTest {
    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    private Condition condition(ConditionTypeAvro type, ConditionOperationAvro operation, Integer value) {
        Sensor sensor = new Sensor();
        sensor.setDeviceId("s");
        Condition condition = new Condition();
        condition.setSensor(sensor);
        condition.setType(type);
        condition.setOperation(operation);
        condition.setValue(value);
        return condition;
    }

    private SensorsSnapshotAvro snapshot(Object data) {
        return new SensorsSnapshotAvro("hub", Instant.EPOCH, Map.of("s", new SensorStateAvro(Instant.EPOCH, data)));
    }

    @Test
    void supportsEveryTypeAndComparison() {
        Object[][] cases = {
                {ConditionTypeAvro.MOTION, new MotionSensorAvro(10, true, 12), 1},
                {ConditionTypeAvro.SWITCH, new SwitchSensorAvro(false), 0},
                {ConditionTypeAvro.LUMINOSITY, new LightSensorAvro(10, 50), 50},
                {ConditionTypeAvro.TEMPERATURE, new TemperatureSensorAvro(-10, 14), -10},
                {ConditionTypeAvro.TEMPERATURE, new ClimateSensorAvro(20, 50, 1000), 20},
                {ConditionTypeAvro.HUMIDITY, new ClimateSensorAvro(20, 50, 1000), 50},
                {ConditionTypeAvro.CO2LEVEL, new ClimateSensorAvro(20, 50, 1000), 1000}
        };
        for (var example : cases) {
            var type = (ConditionTypeAvro) example[0];
            int value = (Integer) example[2];
            var state = snapshot(example[1]);
            assertTrue(evaluator.matches(condition(type, ConditionOperationAvro.EQUALS, value), state));
            assertTrue(evaluator.matches(condition(type, ConditionOperationAvro.GREATER_THAN, value - 1), state));
            assertTrue(evaluator.matches(condition(type, ConditionOperationAvro.LOWER_THAN, value + 1), state));
            assertFalse(evaluator.matches(condition(type, ConditionOperationAvro.GREATER_THAN, value), state));
            assertFalse(evaluator.matches(condition(type, ConditionOperationAvro.LOWER_THAN, value), state));
        }
    }

    @Test
    void rejectsMissingStateWrongPayloadAndAbsentValue() {
        var condition = condition(ConditionTypeAvro.MOTION, ConditionOperationAvro.EQUALS, 1);
        assertFalse(evaluator.matches(condition, new SensorsSnapshotAvro("hub", Instant.EPOCH, Map.of())));
        assertFalse(evaluator.matches(condition, snapshot(new SwitchSensorAvro(true))));
        condition.setValue(null);
        assertFalse(evaluator.matches(condition, snapshot(new MotionSensorAvro(1, true, 2))));
    }
}
