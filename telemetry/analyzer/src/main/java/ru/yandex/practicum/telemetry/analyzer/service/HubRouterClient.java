package ru.yandex.practicum.telemetry.analyzer.service;

import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.grpc.telemetry.hubrouter.HubRouterControllerGrpc;
import ru.yandex.practicum.grpc.telemetry.event.DeviceActionRequest;
import ru.yandex.practicum.telemetry.analyzer.AnalyzerProperties;
import java.util.concurrent.TimeUnit;

@Component
public class HubRouterClient {
    private static final Logger log = LoggerFactory.getLogger(HubRouterClient.class);
    @GrpcClient("hub-router")
    private HubRouterControllerGrpc.HubRouterControllerBlockingStub stub;
    private final AnalyzerProperties config;

    public HubRouterClient(AnalyzerProperties config) {
        this.config = config;
    }

    public void send(DeviceActionRequest request) {
        stub.withDeadlineAfter(config.actionTimeout().toMillis(), TimeUnit.MILLISECONDS).handleDeviceAction(request);
        log.debug("Action acknowledged: hub={}, scenario={}, sensor={}",
                request.getHubId(), request.getScenarioName(), request.getAction().getSensorId());
    }
}
