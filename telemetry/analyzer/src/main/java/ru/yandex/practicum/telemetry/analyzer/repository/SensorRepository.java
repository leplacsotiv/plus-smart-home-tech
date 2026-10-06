package ru.yandex.practicum.telemetry.analyzer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.yandex.practicum.telemetry.analyzer.model.Sensor;
import java.util.*;

public interface SensorRepository extends JpaRepository<Sensor, Long> {
    Optional<Sensor> findByDeviceIdAndHubId(String deviceId, String hubId);
    boolean existsByDeviceIdInAndHubId(Collection<String> deviceIds, String hubId);
}
