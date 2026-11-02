package ru.yandex.practicum.order.service;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yandex.practicum.order.dto.CreateOrderRequest;
import ru.yandex.practicum.order.dto.OrderDto;
import ru.yandex.practicum.order.exception.OrderProcessingException;
import ru.yandex.practicum.order.feign.InventoryClient;
import ru.yandex.practicum.order.feign.ProductClient;
import ru.yandex.practicum.order.feign.ProductDto;
import ru.yandex.practicum.order.feign.ReleaseRequest;
import ru.yandex.practicum.order.feign.ReserveRequest;
import ru.yandex.practicum.order.feign.ReserveResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Координатор: HTTP-вызовы происходят без транзакции order_db. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderOrchestrationService {
    private final ProductClient products;
    private final InventoryClient inventory;
    private final OrderService orders;

    public OrderDto create(CreateOrderRequest request) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        try {
            request.items().forEach(item -> quantities.merge(
                    item.productId(), item.quantity(), Math::addExact));
        } catch (ArithmeticException e) {
            throw new OrderProcessingException("Суммарное количество товара слишком велико", e);
        }

        // Сначала проверяем ВСЕ товары: при ошибке не понадобится отменять резервы.
        Map<Long, ProductDto> catalog = new LinkedHashMap<>();
        for (Long productId : quantities.keySet()) {
            catalog.put(productId, loadProduct(productId));
        }

        // Сохраняем строки из исходного заказа, резервируем суммарно по productId.
        List<OrderDraft.Line> lines = request.items().stream()
                .map(item -> {
                    ProductDto product = catalog.get(item.productId());
                    return new OrderDraft.Line(product.id(), product.name(),
                            item.quantity(), product.price());
                })
                .toList();

        List<ReserveRequest> confirmedReserves = new ArrayList<>();
        try {
            for (var entry : quantities.entrySet()) {
                var reserve = new ReserveRequest(entry.getKey(), entry.getValue());
                reserve(reserve);
                confirmedReserves.add(reserve);
            }

            // Единственная локальная DB-транзакция — внутри другого Spring-бина.
            return orders.saveConfirmed(new OrderDraft(
                    request.customerName(), request.customerEmail(), lines));
        } catch (RuntimeException error) {
            releaseConfirmedReserves(confirmedReserves);
            throw error;
        }
    }

    private ProductDto loadProduct(Long id) {
        final ProductDto product;
        try {
            product = products.getProductById(id);
        } catch (FeignException e) {
            if (e.status() == 404) {
                throw new OrderProcessingException("Товар " + id + " не найден", e);
            }
            throw new OrderProcessingException("Не удалось получить товар " + id, e);
        }
        if (product == null || product.id() == null || !id.equals(product.id())) {
            throw new OrderProcessingException("Каталог вернул некорректные данные товара " + id);
        }
        if (!Boolean.TRUE.equals(product.active())) {
            throw new OrderProcessingException("Товар " + id + " снят с продажи");
        }
        if (product.name() == null || product.name().isBlank()
                || product.price() == null || product.price().signum() <= 0) {
            throw new OrderProcessingException("Каталог вернул неполные данные товара " + id);
        }
        return product;
    }

    private void reserve(ReserveRequest request) {
        final ReserveResponse result;
        try {
            result = inventory.reserveStock(request);
        } catch (FeignException e) {
            String reason = switch (e.status()) {
                case 404 -> "Нет складской записи для товара " + request.productId();
                case 409 -> "Недостаточно товара " + request.productId() + " на складе";
                default -> "Не удалось зарезервировать товар " + request.productId();
            };
            throw new OrderProcessingException(reason, e);
        }
        if (result == null || !result.success() || result.availableQuantity() < 0) {
            throw new OrderProcessingException(
                    "Склад не подтвердил резервирование товара " + request.productId());
        }
    }

    private void releaseConfirmedReserves(List<ReserveRequest> reserves) {
        for (int i = reserves.size() - 1; i >= 0; i--) {
            ReserveRequest reserve = reserves.get(i);
            try {
                ReserveResponse result = inventory.releaseStock(new ReleaseRequest(
                        reserve.productId(), reserve.quantity()));
                if (result == null || !result.success()) {
                    log.error("Склад не подтвердил снятие резерва товара {} в количестве {}",
                            reserve.productId(), reserve.quantity());
                }
            } catch (RuntimeException compensationError) {
                // Best effort: не скрываем исходную ошибку, проблему фиксируем в логах.
                log.error("Не удалось снять резерв товара {} в количестве {}",
                        reserve.productId(), reserve.quantity(), compensationError);
            }
        }
    }
}
