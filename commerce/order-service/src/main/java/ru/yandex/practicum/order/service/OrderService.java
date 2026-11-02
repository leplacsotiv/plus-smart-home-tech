package ru.yandex.practicum.order.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.yandex.practicum.order.dto.OrderDto;
import ru.yandex.practicum.order.dto.OrderItemDto;
import ru.yandex.practicum.order.entity.CustomerOrder;
import ru.yandex.practicum.order.entity.OrderItem;
import ru.yandex.practicum.order.exception.NotFoundException;
import ru.yandex.practicum.order.repository.OrderRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public OrderDto saveConfirmed(OrderDraft draft) {
        return save(draft, null);
    }

    @Transactional
    public OrderDto savePending(OrderDraft draft, String details) {
        return save(draft, details);
    }

    private OrderDto save(OrderDraft draft, String pendingDetails) {
        BigDecimal totalPrice = draft.items().stream()
                .map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        CustomerOrder order = new CustomerOrder(
                draft.customerName(), draft.customerEmail(), totalPrice, LocalDateTime.now());
        if (pendingDetails == null) {
            order.confirm();
        } else {
            order.awaitConfirmation(pendingDetails);
        }

        draft.items().forEach(item -> order.addItem(new OrderItem(
                item.productId(), item.productName(), item.quantity(), item.price())));
        return toDto(repository.saveAndFlush(order));
    }

    @Transactional(readOnly = true)
    public OrderDto findById(Long id) {
        CustomerOrder order = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Заказ с id=" + id + " не найден"));
        return toDto(order);
    }

    @Transactional(readOnly = true)
    public List<OrderDto> findAll() {
        return toDto(repository.findAllByOrderByCreatedAtDesc());
    }

    @Transactional(readOnly = true)
    public List<OrderDto> findByEmail(String email) {
        return toDto(repository.findAllByCustomerEmailOrderByCreatedAtDesc(email));
    }

    private List<OrderDto> toDto(List<CustomerOrder> orders) {
        return orders.stream().map(this::toDto).toList();
    }

    private OrderDto toDto(CustomerOrder order) {
        List<OrderItemDto> items = order.getItems().stream()
                .map(item -> new OrderItemDto(item.getId(), item.getProductId(), item.getProductName(),
                        item.getQuantity(), item.getPrice()))
                .toList();
        return new OrderDto(order.getId(), order.getCustomerName(), order.getCustomerEmail(),
                order.getStatus().name(), order.getTotalPrice(), order.getStatusDetails(), order.getCreatedAt(), items);
    }
}
