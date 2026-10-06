package ru.yandex.practicum.telemetry.aggregator;

import org.springframework.stereotype.Service;
import ru.yandex.practicum.kafka.telemetry.event.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class SnapshotService {
    private final Map<String, SensorsSnapshotAvro> snapshots = new HashMap<>();
    public Optional<SensorsSnapshotAvro> update(SensorEventAvro event) {
        var previous = snapshots.get(event.getHubId());
        var states = previous == null ? new HashMap<String, SensorStateAvro>()
                : new HashMap<>(previous.getSensorsState());
        var state = states.get(event.getId());
        if (state != null && (state.getTimestamp().isAfter(event.getTimestamp())
                || state.getData().equals(event.getPayload()))) {
            return Optional.empty();
        }
        states.put(event.getId(), new SensorStateAvro(event.getTimestamp(), event.getPayload()));
        var snapshot = new SensorsSnapshotAvro(event.getHubId(), event.getTimestamp(), states);
        snapshots.put(event.getHubId(), snapshot);
        return Optional.of(snapshot);
    }
}
