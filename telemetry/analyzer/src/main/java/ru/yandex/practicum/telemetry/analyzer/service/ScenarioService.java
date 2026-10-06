package ru.yandex.practicum.telemetry.analyzer.service;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.yandex.practicum.grpc.telemetry.event.*;
import ru.yandex.practicum.kafka.telemetry.event.SensorsSnapshotAvro;
import ru.yandex.practicum.telemetry.analyzer.repository.ScenarioRepository;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScenarioService {
    private final ScenarioRepository scenarios;
    private final ConditionEvaluator evaluator;

    @Transactional(readOnly = true)
    public List<DeviceActionRequest> evaluate(SensorsSnapshotAvro snapshot) {
        var requests = new ArrayList<DeviceActionRequest>();
        for (var scenario : scenarios.findByHubId(snapshot.getHubId())) {
            if (!scenario.getConditions().stream().allMatch(c -> evaluator.matches(c, snapshot))) continue;
            for (var action : scenario.getActions()) {
                var command = DeviceActionProto.newBuilder().setSensorId(action.getSensor().getDeviceId())
                        .setType(ActionTypeProto.valueOf(action.getType().name()));
                if (action.getValue() != null) command.setValue(action.getValue());
                requests.add(DeviceActionRequest.newBuilder().setHubId(snapshot.getHubId())
                        .setScenarioName(scenario.getName()).setAction(command)
                        .setTimestamp(Timestamp.newBuilder().setSeconds(snapshot.getTimestamp().getEpochSecond())
                                .setNanos(snapshot.getTimestamp().getNano())).build());
            }
        }
        return requests;
    }
}
