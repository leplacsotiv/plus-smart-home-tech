package ru.yandex.practicum.order.feign;

public record ReleaseRequest(Long productId, Integer quantity) {
}
