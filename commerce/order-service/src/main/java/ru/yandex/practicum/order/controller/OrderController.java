package ru.yandex.practicum.order.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.yandex.practicum.order.dto.CreateOrderRequest;
import ru.yandex.practicum.order.dto.OrderDto;
import ru.yandex.practicum.order.service.OrderService;
import ru.yandex.practicum.order.service.OrderOrchestrationService;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;
    private final OrderOrchestrationService orchestration;

    public OrderController(OrderService service, OrderOrchestrationService orchestration) {
        this.service = service;
        this.orchestration = orchestration;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDto create(@Valid @RequestBody CreateOrderRequest request) {
        return orchestration.create(request);
    }

    @GetMapping("/{id}")
    public OrderDto findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping
    public List<OrderDto> findAll() {
        return service.findAll();
    }

    @GetMapping("/by-email")
    public List<OrderDto> findByEmail(@RequestParam String email) {
        return service.findByEmail(email);
    }
}
