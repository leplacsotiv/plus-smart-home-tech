package ru.yandex.practicum.telemetry.collector;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import com.google.protobuf.Empty;
import org.junit.jupiter.api.Test;
import ru.yandex.practicum.grpc.telemetry.event.*;
import ru.yandex.practicum.kafka.telemetry.event.*;
import ru.yandex.practicum.telemetry.collector.controller.CollectorController;
import ru.yandex.practicum.telemetry.collector.kafka.KafkaEventProducer;
import ru.yandex.practicum.telemetry.collector.mapper.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectorContractTest {
    private final SensorEventMapper sensors = new SensorEventMapper();
    private final HubEventMapper hubs = new HubEventMapper();

    @Test
    void mapsAllSensorPayloadsAndTimestamp() {
        var event = SensorEventProto.newBuilder().setId("s").setHubId("h")
                .setTimestamp(Timestamp.newBuilder().setSeconds(123).setNanos(456000000));
        assertEquals(new MotionSensorAvro(1, true, 2), sensors.toAvro(event.setMotionSensor(
                MotionSensorProto.newBuilder().setLinkQuality(1).setMotion(true).setVoltage(2)).build()).getPayload());
        assertEquals(new TemperatureSensorAvro(-3, 26), sensors.toAvro(event.setTemperatureSensor(
                TemperatureSensorProto.newBuilder().setTemperatureC(-3).setTemperatureF(26)).build()).getPayload());
        assertEquals(new LightSensorAvro(1, 2), sensors.toAvro(event.setLightSensor(
                LightSensorProto.newBuilder().setLinkQuality(1).setLuminosity(2)).build()).getPayload());
        assertEquals(new ClimateSensorAvro(1, 2, 3), sensors.toAvro(event.setClimateSensor(
                ClimateSensorProto.newBuilder().setTemperatureC(1).setHumidity(2).setCo2Level(3)).build()).getPayload());
        var result = sensors.toAvro(event.setSwitchSensor(SwitchSensorProto.newBuilder().setState(true)).build());
        assertEquals(new SwitchSensorAvro(true), result.getPayload());
        assertEquals(Instant.ofEpochMilli(123456), result.getTimestamp());
    }

    @Test
    void preservesBooleanIntegerAndOptionalActionValues() {
        var scenario = ScenarioAddedEventProto.newBuilder().setName("rule")
                .addConditions(ScenarioConditionProto.newBuilder().setSensorId("s").setBoolValue(false))
                .addConditions(ScenarioConditionProto.newBuilder().setSensorId("s").setIntValue(0))
                .addActions(DeviceActionProto.newBuilder().setSensorId("s"))
                .addActions(DeviceActionProto.newBuilder().setSensorId("s").setValue(0));
        var result = (ScenarioAddedEventAvro) hubs.toAvro(HubEventProto.newBuilder().setHubId("h")
                .setTimestamp(Timestamp.getDefaultInstance()).setScenarioAdded(scenario).build()).getPayload();
        assertEquals(false, result.getConditions().getFirst().getValue());
        assertEquals(0, result.getConditions().getLast().getValue());
        assertNull(result.getActions().getFirst().getValue());
        assertEquals(0, result.getActions().getLast().getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsInvalidInputAndKafkaFailureWithoutAcknowledgingSuccess() {
        var producer = mock(KafkaEventProducer.class);
        var controller = new CollectorController(sensors, hubs, producer);
        StreamObserver<Empty> observer = mock(StreamObserver.class);
        controller.collectSensorEvent(SensorEventProto.getDefaultInstance(), observer);
        verify(observer).onError(argThat(e -> Status.fromThrowable(e).getCode() == Status.Code.INVALID_ARGUMENT));
        verifyNoInteractions(producer);
        verify(observer, never()).onCompleted();
        reset(observer);
        doThrow(new IllegalStateException("broker unavailable")).when(producer).sendSensorEvent(any());
        controller.collectSensorEvent(SensorEventProto.newBuilder().setHubId("h").setId("s")
                .setTimestamp(Timestamp.getDefaultInstance()).setSwitchSensor(SwitchSensorProto.getDefaultInstance()).build(), observer);
        verify(observer).onError(argThat(e -> Status.fromThrowable(e).getCode() == Status.Code.UNAVAILABLE));
        verify(observer, never()).onNext(any());
    }
}
