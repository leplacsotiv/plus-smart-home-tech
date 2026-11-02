package ru.yandex.practicum.order.feign;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.yandex.practicum.order.dto.CreateOrderRequest;
import ru.yandex.practicum.order.dto.OrderItemRequest;
import ru.yandex.practicum.order.exception.DependencyUnavailableException;
import ru.yandex.practicum.order.exception.OrderProcessingException;
import ru.yandex.practicum.order.service.OrderOrchestrationService;
import ru.yandex.practicum.order.service.OrderService;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.import=file:../../infra/config-repo/commerce/order-service.yml",
        "spring.datasource.url=jdbc:h2:mem:circuit_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop"
})
class FeignCircuitBreakerTest {
    private static final AtomicInteger productStatus = new AtomicInteger(200);
    private static final AtomicInteger inventoryStatus = new AtomicInteger(200);
    private static final AtomicInteger calls = new AtomicInteger();
    private static final AtomicReference<String> sourceHeader = new AtomicReference<>();
    private static final HttpServer backend = startBackend();

    @Autowired private ProductClient products;
    @Autowired private InventoryClient inventory;
    @Autowired private CircuitBreakerRegistry breakers;
    @Autowired private TimeLimiterRegistry timeLimiters;
    @Autowired private FeignClientProperties properties;
    @Autowired private OrderOrchestrationService orchestration;
    @Autowired private OrderService orders;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry properties) {
        properties.add("spring.cloud.openfeign.client.config.product-service.url", FeignCircuitBreakerTest::url);
        properties.add("spring.cloud.openfeign.client.config.inventory-service.url", FeignCircuitBreakerTest::url);
    }

    @BeforeEach
    void reset() {
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        productStatus.set(200);
        inventoryStatus.set(200);
        calls.set(0);
    }

    @AfterAll
    static void stop() {
        backend.stop(0);
    }

    @Test
    void loadsExternalPropertiesAndOpensActualFeignCircuitAfterFiveFailures() {
        productStatus.set(503);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> products.getProductById(1L)).isInstanceOf(DependencyUnavailableException.class);
        }
        CircuitBreaker breaker = breakers.circuitBreaker("ProductClientgetProductById");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(5);
        assertThat(breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(timeLimiters.getDefaultConfig().getTimeoutDuration()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getConfig().get("default").getReadTimeout()).isEqualTo(2000);
        assertThat(sourceHeader.get()).isEqualTo("order-service");
        assertThatThrownBy(() -> products.getProductById(1L)).isInstanceOf(DependencyUnavailableException.class);
        assertThat(calls.get()).isEqualTo(5);
    }

    @Test
    void expectedHttpErrorsPassThroughFallbackAndAreIgnoredByCircuitMetrics() {
        productStatus.set(404);
        inventoryStatus.set(409);
        for (int attempt = 0; attempt < 6; attempt++) {
            assertThatThrownBy(() -> products.getProductById(1L)).isInstanceOf(OrderProcessingException.class);
            assertThatThrownBy(() -> inventory.reserveStock(new ReserveRequest(1L, 1)))
                    .isInstanceOf(OrderProcessingException.class);
        }
        inventoryStatus.set(404);
        assertThatThrownBy(() -> inventory.reserveStock(new ReserveRequest(1L, 1)))
                .isInstanceOf(OrderProcessingException.class).hasMessageContaining("складской записи");
        for (String name : List.of("ProductClientgetProductById", "InventoryClientreserveStock")) {
            var breaker = breakers.circuitBreaker(name);
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        }
        assertThat(calls.get()).isEqualTo(13);
    }

    @Test
    void persistsPendingOrderWithPlaceholderWhenCatalogIsUnavailable() {
        productStatus.set(503);
        var pending = orchestration.create(new CreateOrderRequest("Buyer", "pending@example.com",
                List.of(new OrderItemRequest(1L, 2))));
        assertThat(pending.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(pending.totalPrice()).isZero();
        assertThat(orders.findById(pending.id()).items().getFirst().productName()).contains("ожидает проверки");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void inventoryWriteFailureIsNotRetried() {
        inventoryStatus.set(503);
        assertThatThrownBy(() -> inventory.reserveStock(new ReserveRequest(1L, 1)))
                .isInstanceOf(DependencyUnavailableException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    private static String url() {
        return "http://127.0.0.1:" + backend.getAddress().getPort();
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api", exchange -> {
                calls.incrementAndGet();
                sourceHeader.set(exchange.getRequestHeaders().getFirst("X-Source-Service"));
                boolean product = exchange.getRequestURI().getPath().startsWith("/api/products");
                int status = (product ? productStatus : inventoryStatus).get();
                String body = product ? "{\"id\":1,\"name\":\"Lamp\",\"price\":100.00,\"active\":true}"
                        : "{\"success\":true,\"availableQuantity\":9,\"message\":\"OK\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
