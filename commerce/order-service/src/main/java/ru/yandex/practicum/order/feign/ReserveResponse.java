package ru.yandex.practicum.order.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReserveResponse(
        boolean success,
        Integer availableQuantity,
        String message
) {
}
