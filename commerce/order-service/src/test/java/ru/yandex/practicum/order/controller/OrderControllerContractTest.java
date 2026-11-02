package ru.yandex.practicum.order.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import ru.yandex.practicum.order.dto.CreateOrderRequest;
import ru.yandex.practicum.order.dto.OrderDto;
import ru.yandex.practicum.order.exception.GlobalExceptionHandler;
import ru.yandex.practicum.order.service.OrderOrchestrationService;
import ru.yandex.practicum.order.service.OrderService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerContractTest {

    private MockMvc mvc;
    private OrderService orders;
    private OrderOrchestrationService orchestration;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        orders = mock(OrderService.class);
        orchestration = mock(OrderOrchestrationService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(orders, orchestration))
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void acceptsOnlyProductAndQuantityAndCallsOrchestrator() throws Exception {
        var result = new OrderDto(7L, "Иван", "ivan@example.com", "CONFIRMED",
                new BigDecimal("2980.00"), null, LocalDateTime.now(), List.of());
        when(orchestration.create(any(CreateOrderRequest.class))).thenReturn(result);

        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerName": "Иван",
                                  "customerEmail": "ivan@example.com",
                                  "items": [{"productId": 42, "quantity": 2}]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        var captor = org.mockito.ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orchestration).create(captor.capture());
        assertThat(captor.getValue().items()).hasSize(1);
        assertThat(captor.getValue().items().getFirst().productId()).isEqualTo(42L);
        assertThat(captor.getValue().items().getFirst().quantity()).isEqualTo(2);
        verifyNoInteractions(orders);
    }

    @Test
    void doesNotRequirePriceAndNameAndIgnoresLegacyExtraFields() throws Exception {
        when(orchestration.create(any(CreateOrderRequest.class))).thenReturn(new OrderDto(
                8L, "Иван", "ivan@example.com", "CONFIRMED",
                BigDecimal.ZERO, null, LocalDateTime.now(), List.of()));

        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerName": "Иван",
                                  "customerEmail": "ivan@example.com",
                                  "items": [{"productId": 42, "quantity": 1,
                                             "productName": "Подделка", "price": 0.01}]
                                }
                                """))
                .andExpect(status().isCreated());

        var captor = org.mockito.ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orchestration).create(captor.capture());
        assertThat(captor.getValue().items().getFirst().quantity()).isEqualTo(1);
        verifyNoInteractions(orders);
    }

    @Test
    void rejectsMissingOrZeroQuantityBeforeOrchestration() throws Exception {
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerName": "Иван",
                                  "customerEmail": "ivan@example.com",
                                  "items": [{"productId": 42, "quantity": 0}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors").exists());

        verifyNoInteractions(orchestration, orders);
    }

    @Test
    void getEndpointsStillDelegateToOrderService() throws Exception {
        when(orders.findById(7L)).thenReturn(new OrderDto(
                7L, "Иван", "ivan@example.com", "CREATED",
                BigDecimal.ZERO, null, LocalDateTime.now(), List.of()));

        mvc.perform(get("/api/orders/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));

        verify(orders).findById(7L);
        verifyNoInteractions(orchestration);
    }
}
