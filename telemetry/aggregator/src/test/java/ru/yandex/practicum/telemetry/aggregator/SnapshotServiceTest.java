package ru.yandex.practicum.telemetry.aggregator;

import org.junit.jupiter.api.Test;
import ru.yandex.practicum.kafka.telemetry.event.*;
import ru.yandex.practicum.kafka.telemetry.serialization.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotServiceTest {
    private SensorEventAvro event(String hub, String id, long time, boolean value) {
        return new SensorEventAvro(id, hub, Instant.ofEpochMilli(time), new SwitchSensorAvro(value));
    }

    @Test
    void ignoresDuplicatesOlderValuesAndUnchangedReadings() {
        var service = new SnapshotService();
        var first = service.update(event("a", "s", 10, true)).orElseThrow();
        assertTrue(service.update(event("a", "s", 10, true)).isEmpty());
        assertTrue(service.update(event("a", "s", 9, false)).isEmpty());
        assertTrue(service.update(event("a", "s", 20, true)).isEmpty());
        assertTrue(service.update(event("a", "s", 15, true)).isEmpty());
        var changed = service.update(event("a", "s", 21, false)).orElseThrow();
        assertEquals(new SwitchSensorAvro(false), changed.getSensorsState().get("s").getData());
        assertEquals(new SwitchSensorAvro(true), first.getSensorsState().get("s").getData());
        assertEquals(Instant.ofEpochMilli(21), changed.getTimestamp());
    }

    @Test
    void isolatesHubsAndAccumulatesSensors() {
        var service = new SnapshotService();
        service.update(event("a", "s1", 10, true));
        var other = service.update(event("b", "s2", 11, false)).orElseThrow();
        assertEquals(1, other.getSensorsState().size());
        assertFalse(other.getSensorsState().containsKey("s1"));
        assertEquals(2, service.update(event("a", "s3", 12, true)).orElseThrow().getSensorsState().size());
    }

    @Test
    void coursePolicyAcceptsChangedReadingsWithinSameMillisecondButNotDuplicates() {
        var service = new SnapshotService();
        service.update(event("a", "s", 10, true));
        assertTrue(service.update(event("a", "s", 10, true)).isEmpty());
        assertTrue(service.update(event("a", "s", 10, false)).isPresent());
        assertTrue(service.update(event("a", "s", 9, true)).isEmpty());
    }

    @Test
    void roundTripsAvroAndRejectsMalformedBytes() {
        var event = event("a", "s", 10, true);
        try (var serializer = new AvroSerializer(); var reader = new SensorEventDeserializer()) {
            assertEquals(event, reader.deserialize("test", serializer.serialize("test", event)));
            assertNull(reader.deserialize("test", null));
            assertThrows(org.apache.kafka.common.errors.SerializationException.class,
                    () -> reader.deserialize("test", new byte[] {1}));
        }
    }
}
