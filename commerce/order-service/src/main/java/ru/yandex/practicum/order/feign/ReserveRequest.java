package ru.yandex.practicum.order.feign;

public record ReserveRequest(
        Long productId,
        Integer quantity
) {
}
