package ru.yandex.practicum.order.feign;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InventoryClientFallbackFactory implements FallbackFactory<InventoryClient> {
    @Override
    public InventoryClient create(Throwable cause) {
        log.warn("Сбой вызова склада: {}", cause.toString());
        return new InventoryClient() {
            @Override
            public ReserveResponse reserveStock(ReserveRequest request) {
                throw RemoteCallFailures.translate("Склад", cause);
            }

            @Override
            public ReserveResponse releaseStock(ReleaseRequest request) {
                throw RemoteCallFailures.translate("Склад", cause);
            }
        };
    }
}
