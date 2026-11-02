package ru.yandex.practicum.order.service;

import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.yandex.practicum.order.dto.CreateOrderRequest;
import ru.yandex.practicum.order.dto.OrderItemRequest;
import ru.yandex.practicum.order.exception.OrderProcessingException;
import ru.yandex.practicum.order.feign.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderOrchestrationServiceTest {
    private ProductClient products;
    private InventoryClient inventory;
    private OrderService orders;
    private OrderOrchestrationService orchestration;

    @BeforeEach
    void setUp() {
        products = mock(ProductClient.class);
        inventory = mock(InventoryClient.class);
        orders = mock(OrderService.class);
        orchestration = new OrderOrchestrationService(products, inventory, orders);
    }

    @Test
    void takesCatalogPriceAndReservesDuplicatesOnlyOnce() {
        var request = request(item(1L, 2), item(1L, 1));
        when(products.getProductById(1L)).thenReturn(product(1L, true));
        when(inventory.reserveStock(new ReserveRequest(1L, 3)))
                .thenReturn(new ReserveResponse(true, 5, "OK"));

        orchestration.create(request);

        verify(products, times(1)).getProductById(1L);
        verify(inventory).reserveStock(new ReserveRequest(1L, 3));
        var draft = org.mockito.ArgumentCaptor.forClass(OrderDraft.class);
        verify(orders).saveConfirmed(draft.capture());
        assertThat(draft.getValue().items()).hasSize(2);
        assertThat(draft.getValue().items().getFirst().productName()).isEqualTo("Из каталога");
        assertThat(draft.getValue().items().getFirst().price()).isEqualByComparingTo("1490.00");
    }

    @Test
    void inactiveProductNeverReachesInventory() {
        when(products.getProductById(1L)).thenReturn(product(1L, false));

        assertThatThrownBy(() -> orchestration.create(request(item(1L, 1))))
                .isInstanceOf(OrderProcessingException.class)
                .hasMessageContaining("снят с продажи");
        verifyNoInteractions(inventory, orders);
    }

    @Test
    void releasesEarlierReserveWhenNextReservationFails() {
        when(products.getProductById(1L)).thenReturn(product(1L, true));
        when(products.getProductById(2L)).thenReturn(product(2L, true));
        when(inventory.reserveStock(new ReserveRequest(1L, 1)))
                .thenReturn(new ReserveResponse(true, 4, "OK"));
        when(inventory.reserveStock(new ReserveRequest(2L, 1)))
                .thenReturn(new ReserveResponse(false, 0, "No stock"));

        assertThatThrownBy(() -> orchestration.create(request(item(1L, 1), item(2L, 1))))
                .isInstanceOf(OrderProcessingException.class);
        verify(inventory).releaseStock(new ReleaseRequest(1L, 1));
        verifyNoInteractions(orders);
    }

    @Test
    void releasesReserveWhenLocalOrderSaveFails() {
        when(products.getProductById(1L)).thenReturn(product(1L, true));
        when(inventory.reserveStock(new ReserveRequest(1L, 1)))
                .thenReturn(new ReserveResponse(true, 4, "OK"));
        when(orders.saveConfirmed(any())).thenThrow(new IllegalStateException("DB failed"));

        assertThatThrownBy(() -> orchestration.create(request(item(1L, 1))))
                .isInstanceOf(IllegalStateException.class);
        verify(inventory).releaseStock(new ReleaseRequest(1L, 1));
    }

    @Test
    void rejectsQuantityOverflowBeforeRemoteCalls() {
        assertThatThrownBy(() -> orchestration.create(request(item(1L, Integer.MAX_VALUE), item(1L, 1))))
                .isInstanceOf(OrderProcessingException.class).hasMessageContaining("количество");
        verifyNoInteractions(products, inventory, orders);
    }

    @Test
    void missingProductIsBusinessFailureBeforeAnyReservation() {
        when(products.getProductById(1L)).thenThrow(remoteError(404));
        assertThatThrownBy(() -> orchestration.create(request(item(1L, 1))))
                .isInstanceOf(OrderProcessingException.class).hasMessageContaining("не найден");
        verifyNoInteractions(inventory, orders);
    }

    @Test
    void inventoryConflictReleasesConfirmedReservesInReverseOrder() {
        for (long id = 1; id <= 3; id++) {
            when(products.getProductById(id)).thenReturn(product(id, true));
        }
        when(inventory.reserveStock(new ReserveRequest(1L, 1))).thenReturn(new ReserveResponse(true, 1, "OK"));
        when(inventory.reserveStock(new ReserveRequest(2L, 1))).thenReturn(new ReserveResponse(true, 1, "OK"));
        when(inventory.reserveStock(new ReserveRequest(3L, 1))).thenThrow(remoteError(409));
        assertThatThrownBy(() -> orchestration.create(request(item(1L, 1), item(2L, 1), item(3L, 1))))
                .isInstanceOf(OrderProcessingException.class).hasMessageContaining("Недостаточно");
        var sequence = inOrder(inventory);
        sequence.verify(inventory).releaseStock(new ReleaseRequest(2L, 1));
        sequence.verify(inventory).releaseStock(new ReleaseRequest(1L, 1));
        verifyNoInteractions(orders);
    }

    @Test
    void failedCompensationDoesNotReplaceOriginalFailure() {
        when(products.getProductById(1L)).thenReturn(product(1L, true));
        when(inventory.reserveStock(any())).thenReturn(new ReserveResponse(true, 1, "OK"));
        var original = new IllegalStateException("DB failed");
        when(orders.saveConfirmed(any())).thenThrow(original);
        when(inventory.releaseStock(any())).thenThrow(new IllegalStateException("release failed"));
        assertThatThrownBy(() -> orchestration.create(request(item(1L, 1)))).isSameAs(original);
    }

    private static FeignException remoteError(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "http://service/api", Map.of(), null,
                java.nio.charset.StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("remote", Response.builder().request(request).status(status)
                .headers(Map.of()).build());
    }

    private static CreateOrderRequest request(OrderItemRequest... items) {
        return new CreateOrderRequest("Покупатель", "test@example.org", List.of(items));
    }

    private static OrderItemRequest item(Long id, int qty) {
        return new OrderItemRequest(id, qty);
    }

    private static ProductDto product(Long id, boolean active) {
        return new ProductDto(id, "Из каталога", new BigDecimal("1490.00"), active);
    }
}
