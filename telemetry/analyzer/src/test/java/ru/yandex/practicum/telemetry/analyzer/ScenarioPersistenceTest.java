package ru.yandex.practicum.telemetry.analyzer;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.yandex.practicum.kafka.telemetry.event.*;
import ru.yandex.practicum.telemetry.analyzer.repository.*;
import ru.yandex.practicum.telemetry.analyzer.service.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analyzer;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({HubEventService.class, ScenarioService.class, ConditionEvaluator.class})
class ScenarioPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getProperty("analyzer.test.database",
                "jdbc:h2:mem:analyzer;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
    }

    @Autowired HubEventService events;
    @Autowired ScenarioService rules;
    @Autowired SensorRepository sensors;
    @Autowired ScenarioRepository scenarios;
    @Autowired ConditionRepository conditions;
    @Autowired ActionRepository actions;
    @Autowired EntityManager entityManager;

    private void send(String hub, Object payload) {
        events.process(new HubEventAvro(hub, Instant.EPOCH, payload));
    }

    private ScenarioAddedEventAvro scenario(String device, Object value) {
        return new ScenarioAddedEventAvro("rule",
                List.of(new ScenarioConditionAvro(device, ConditionTypeAvro.SWITCH, ConditionOperationAvro.EQUALS, value)),
                List.of(new DeviceActionAvro(device, ActionTypeAvro.ACTIVATE, null),
                        new DeviceActionAvro(device, ActionTypeAvro.SET_VALUE, 0)));
    }

    private SensorsSnapshotAvro snapshot(String hub, boolean state) {
        return new SensorsSnapshotAvro(hub, Instant.ofEpochMilli(1234),
                Map.of("s", new SensorStateAvro(Instant.EPOCH, new SwitchSensorAvro(state))));
    }

    @Test
    void persistsIdempotentUpdatesAndKeepsHubOwnership() {
        send("a", new DeviceAddedEventAvro("s", DeviceTypeAvro.SWITCH_SENSOR));
        send("a", new DeviceAddedEventAvro("s", DeviceTypeAvro.SWITCH_SENSOR));
        send("b", new DeviceAddedEventAvro("s", DeviceTypeAvro.SWITCH_SENSOR));
        send("a", scenario("s", true));
        send("a", scenario("s", 1));
        entityManager.flush();
        entityManager.clear();
        assertEquals(2, sensors.count());
        assertEquals(1, scenarios.count());
        assertEquals(1, conditions.count());
        assertEquals(2, actions.count());
        assertTrue(rules.evaluate(snapshot("b", true)).isEmpty());
        assertTrue(rules.evaluate(snapshot("a", false)).isEmpty());
        var result = rules.evaluate(snapshot("a", true));
        assertEquals(2, result.size());
        assertFalse(result.getFirst().getAction().hasValue());
        assertTrue(result.getLast().getAction().hasValue());
        assertEquals(0, result.getLast().getAction().getValue());
        assertEquals("rule", result.getFirst().getScenarioName());
        assertEquals(234000000, result.getFirst().getTimestamp().getNanos());
    }

    @Test
    void requiresAllConditionsAndMissingSensorDoesNotMatch() {
        send("a", new DeviceAddedEventAvro("s", DeviceTypeAvro.SWITCH_SENSOR));
        send("a", new DeviceAddedEventAvro("other", DeviceTypeAvro.LIGHT_SENSOR));
        var scenario = scenario("s", true);
        scenario.setConditions(List.of(scenario.getConditions().getFirst(),
                new ScenarioConditionAvro("other", ConditionTypeAvro.LUMINOSITY, ConditionOperationAvro.LOWER_THAN, 100)));
        send("a", scenario);
        assertTrue(rules.evaluate(snapshot("a", true)).isEmpty());
    }

    @Test
    void removesDependentScenariosWithoutWeakeningConditions() {
        send("a", new DeviceAddedEventAvro("s", DeviceTypeAvro.SWITCH_SENSOR));
        send("b", new DeviceAddedEventAvro("s", DeviceTypeAvro.SWITCH_SENSOR));
        send("a", scenario("s", true));
        send("b", scenario("s", false));
        send("a", new DeviceRemovedEventAvro("s"));
        send("a", new DeviceRemovedEventAvro("s"));
        entityManager.flush();
        entityManager.clear();
        assertEquals(1, sensors.count());
        assertTrue(scenarios.findByHubId("a").isEmpty());
        assertEquals(1, scenarios.findByHubId("b").size());
        send("b", new ScenarioRemovedEventAvro("rule"));
        send("b", new ScenarioRemovedEventAvro("rule"));
        entityManager.flush();
        assertEquals(0, conditions.count());
        assertEquals(0, actions.count());
    }

    @Test
    void rejectsCrossHubDeviceReferences() {
        send("b", new DeviceAddedEventAvro("s", DeviceTypeAvro.SWITCH_SENSOR));
        assertThrows(IllegalArgumentException.class, () -> send("a", scenario("s", true)));
    }
}
