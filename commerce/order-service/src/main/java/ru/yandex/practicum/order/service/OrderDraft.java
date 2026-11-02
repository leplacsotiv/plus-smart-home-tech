package ru.yandex.practicum.order.service;

import java.math.BigDecimal;
import java.util.List;

/** Данные для локального сохранения, уже проверенные внешними сервисами. */
public record OrderDraft(String customerName, String customerEmail, List<Line> items) {
    public OrderDraft {
        items = List.copyOf(items);
    }

    public record Line(Long productId, String productName, int quantity, BigDecimal price) {
    }
}
