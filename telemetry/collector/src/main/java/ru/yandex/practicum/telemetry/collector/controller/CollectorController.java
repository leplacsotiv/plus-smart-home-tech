package ru.yandex.practicum.telemetry.collector.controller;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.yandex.practicum.grpc.telemetry.collector.CollectorControllerGrpc;
import ru.yandex.practicum.grpc.telemetry.event.HubEventProto;
import ru.yandex.practicum.grpc.telemetry.event.SensorEventProto;
import ru.yandex.practicum.telemetry.collector.kafka.KafkaEventProducer;
import ru.yandex.practicum.telemetry.collector.mapper.HubEventMapper;
import ru.yandex.practicum.telemetry.collector.mapper.SensorEventMapper;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class CollectorController extends CollectorControllerGrpc.CollectorControllerImplBase {
    private final SensorEventMapper sensors;
    private final HubEventMapper hubs;
    private final KafkaEventProducer producer;

    @Override
    public void collectSensorEvent(SensorEventProto event, StreamObserver<Empty> observer) {
        collect(() -> producer.sendSensorEvent(sensors.toAvro(event)), observer);
    }

    @Override
    public void collectHubEvent(HubEventProto event, StreamObserver<Empty> observer) {
        collect(() -> producer.sendHubEvent(hubs.toAvro(event)), observer);
    }

    private void collect(Runnable operation, StreamObserver<Empty> observer) {
        try {
            operation.run();
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        } catch (IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            log.error("Failed to persist telemetry event", e);
            observer.onError(Status.UNAVAILABLE.withDescription("Kafka publication failed").asRuntimeException());
        }
    }
}
