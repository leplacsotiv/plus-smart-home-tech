package ru.yandex.practicum.telemetry.collector.mapper;

import org.springframework.stereotype.Component;
import ru.yandex.practicum.grpc.telemetry.event.*;
import ru.yandex.practicum.kafka.telemetry.event.*;

@Component
public class HubEventMapper {
    public HubEventAvro toAvro(HubEventProto event) {
        Object payload = switch (event.getPayloadCase()) {
            case DEVICE_ADDED -> new DeviceAddedEventAvro(
                    ProtoValues.required(event.getDeviceAdded().getId(), "id"),
                    DeviceTypeAvro.valueOf(event.getDeviceAdded().getType().name()));
            case DEVICE_REMOVED -> new DeviceRemovedEventAvro(
                    ProtoValues.required(event.getDeviceRemoved().getId(), "id"));
            case SCENARIO_ADDED -> {
                var p = event.getScenarioAdded();
                yield new ScenarioAddedEventAvro(ProtoValues.required(p.getName(), "name"),
                        p.getConditionsList().stream().map(this::condition).toList(),
                        p.getActionsList().stream().map(this::action).toList());
            }
            case SCENARIO_REMOVED -> new ScenarioRemovedEventAvro(
                    ProtoValues.required(event.getScenarioRemoved().getName(), "name"));
            case PAYLOAD_NOT_SET -> throw new IllegalArgumentException("Hub payload is required");
        };
        return new HubEventAvro(ProtoValues.required(event.getHubId(), "hubId"),
                ProtoValues.timestamp(event.getTimestamp(), event.hasTimestamp()), payload);
    }

    private ScenarioConditionAvro condition(ScenarioConditionProto p) {
        Object value = switch (p.getValueCase()) {
            case BOOL_VALUE -> p.getBoolValue();
            case INT_VALUE -> p.getIntValue();
            case VALUE_NOT_SET -> null;
        };
        return new ScenarioConditionAvro(ProtoValues.required(p.getSensorId(), "sensorId"),
                ConditionTypeAvro.valueOf(p.getType().name()),
                ConditionOperationAvro.valueOf(p.getOperation().name()), value);
    }

    private DeviceActionAvro action(DeviceActionProto p) {
        return new DeviceActionAvro(ProtoValues.required(p.getSensorId(), "sensorId"),
                ActionTypeAvro.valueOf(p.getType().name()), p.hasValue() ? p.getValue() : null);
    }
}
