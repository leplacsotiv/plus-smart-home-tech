package ru.yandex.practicum.telemetry.analyzer.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.yandex.practicum.kafka.telemetry.event.*;
import ru.yandex.practicum.telemetry.analyzer.model.*;
import ru.yandex.practicum.telemetry.analyzer.repository.*;

@Service
@RequiredArgsConstructor
public class HubEventService {
    private final SensorRepository sensors;
    private final ScenarioRepository scenarios;

    @Transactional
    public void process(HubEventAvro event) {
        String hub = event.getHubId();
        switch (event.getPayload()) {
            case DeviceAddedEventAvro p -> {
                Sensor sensor = sensors.findByDeviceIdAndHubId(p.getId(), hub).orElseGet(Sensor::new);
                sensor.setHubId(hub);
                sensor.setDeviceId(p.getId());
                sensor.setType(p.getType());
                sensors.save(sensor);
            }
            case DeviceRemovedEventAvro p -> sensors.findByDeviceIdAndHubId(p.getId(), hub).ifPresent(sensor -> {
                scenarios.deleteAll(scenarios.findUsingSensor(hub, sensor.getId()));
                scenarios.flush();
                sensors.delete(sensor);
            });
            case ScenarioAddedEventAvro p -> addScenario(hub, p);
            case ScenarioRemovedEventAvro p -> scenarios.findByHubIdAndName(hub, p.getName()).ifPresent(scenarios::delete);
            default -> throw new IllegalArgumentException("Unsupported hub payload " + event.getPayload());
        }
    }

    private void addScenario(String hub, ScenarioAddedEventAvro event) {
        Scenario scenario = scenarios.findByHubIdAndName(hub, event.getName()).orElseGet(Scenario::new);
        scenario.setHubId(hub);
        scenario.setName(event.getName());
        scenario.getConditions().clear();
        scenario.getActions().clear();
        for (var source : event.getConditions()) {
            Condition condition = new Condition();
            condition.setScenario(scenario);
            condition.setSensor(sensor(hub, source.getSensorId()));
            condition.setType(source.getType());
            condition.setOperation(source.getOperation());
            condition.setValue(switch (source.getValue()) {
                case Boolean b -> b ? 1 : 0;
                case Integer i -> i;
                case null -> null;
                default -> throw new IllegalArgumentException("Unsupported condition value");
            });
            scenario.getConditions().add(condition);
        }
        for (var source : event.getActions()) {
            Action action = new Action();
            action.setScenario(scenario);
            action.setSensor(sensor(hub, source.getSensorId()));
            action.setType(source.getType());
            action.setValue(source.getValue());
            scenario.getActions().add(action);
        }
        scenarios.save(scenario);
    }

    private Sensor sensor(String hub, String id) {
        return sensors.findByDeviceIdAndHubId(id, hub)
                .orElseThrow(() -> new IllegalArgumentException("Unknown device " + id + " in hub " + hub));
    }
}
