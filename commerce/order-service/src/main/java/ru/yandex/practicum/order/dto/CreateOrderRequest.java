package ru.yandex.practicum.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(

        @NotBlank(message = "Имя покупателя обязательно")
        @Size(max = 255, message = "Имя не может быть длиннее 255 символов")
        String customerName,

        @NotBlank(message = "Email обязателен")
        @Email(message = "Некорректный формат email")
        @Size(max = 255, message = "Email не может быть длиннее 255 символов")
        String customerEmail,

        @NotEmpty(message = "Заказ должен содержать хотя бы один товар")
        @Valid
        List<@NotNull(message = "Позиция заказа обязательна") OrderItemRequest> items
) {
}
