package ru.yandex.practicum.order.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "inventory-service")
public interface InventoryClient {

    @PostMapping("/api/inventory/reserve")
    ReserveResponse reserveStock(
            @RequestBody ReserveRequest request
    );

    @PostMapping("/api/inventory/release")
    ReserveResponse releaseStock(@RequestBody ReleaseRequest request);
}
