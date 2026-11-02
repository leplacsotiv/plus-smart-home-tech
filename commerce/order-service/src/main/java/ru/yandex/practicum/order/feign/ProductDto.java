package ru.yandex.practicum.order.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDto(
        Long id,
        String name,
        BigDecimal price,
        Boolean active
) {
}
