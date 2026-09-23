package ru.yandex.practicum.telemetry.analyzer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.yandex.practicum.telemetry.analyzer.model.Scenario;
import java.util.*;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {
    List<Scenario> findByHubId(String hubId);
    Optional<Scenario> findByHubIdAndName(String hubId, String name);
    @Query("select distinct s from Scenario s where s.hubId = :hubId and "
            + "(exists (select c.id from Condition c where c.scenario = s and c.sensor.id = :sensorId) "
            + "or exists (select a.id from Action a where a.scenario = s and a.sensor.id = :sensorId))")
    List<Scenario> findUsingSensor(String hubId, Long sensorId);
}
