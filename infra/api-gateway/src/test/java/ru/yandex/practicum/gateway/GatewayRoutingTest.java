package ru.yandex.practicum.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {
    private static final AtomicInteger calls = new AtomicInteger();
    private static final AtomicInteger backendStatus = new AtomicInteger(200);
    private static final HttpServer backend = startBackend();

    @LocalServerPort private int port;
    private WebTestClient client;

    @DynamicPropertySource
    static void discovery(DynamicPropertyRegistry properties) {
        for (String service : List.of("product-service", "inventory-service", "order-service")) {
            properties.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri",
                    () -> "http://127.0.0.1:" + backend.getAddress().getPort());
        }
    }

    @BeforeEach
    void reset() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        calls.set(0);
        backendStatus.set(200);
    }

    @AfterAll
    static void stop() {
        backend.stop(0);
    }

    @Test
    void explicitRoutesReachDiscoveredBackendAndPreservePath() {
        for (String route : List.of("/api/products", "/api/categories/1", "/api/inventory/1", "/api/orders/1")) {
            client.get().uri(route).exchange().expectStatus().isOk()
                    .expectBody(String.class).isEqualTo(route);
        }
        assertThat(calls.get()).isEqualTo(4);
        client.get().uri("/internal/unknown").exchange().expectStatus().isNotFound();
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void retriesReadsTwiceButNeverWrites() {
        backendStatus.set(503);
        client.get().uri("/api/products").exchange().expectStatus().isEqualTo(503);
        assertThat(calls.get()).isEqualTo(3);
        calls.set(0);
        client.post().uri("/api/orders").bodyValue("{}").exchange().expectStatus().isEqualTo(503);
        assertThat(calls.get()).isEqualTo(1);
        calls.set(0);
        client.post().uri("/api/inventory/reserve").bodyValue("{}").exchange().expectStatus().isEqualTo(503);
        assertThat(calls.get()).isEqualTo(1);
        calls.set(0);
        backendStatus.set(500);
        client.get().uri("/api/products").exchange().expectStatus().isEqualTo(500);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void corsPreflightWorksWithoutMatchingOrdinaryRoute() {
        client.options().uri("/not-a-route")
                .header("Origin", "http://localhost:8443")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:8443")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
        assertThat(calls.get()).isZero();
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                byte[] body = exchange.getRequestURI().getPath().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(backendStatus.get(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
