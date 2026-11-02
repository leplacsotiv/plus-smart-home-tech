package ru.yandex.practicum.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityTest {
    private static final AtomicInteger calls = new AtomicInteger();
    private static final HttpServer backend = startBackend();
    @LocalServerPort private int port;

    @DynamicPropertySource
    static void discovery(DynamicPropertyRegistry properties) {
        for (String service : List.of("product-service", "inventory-service", "order-service")) {
            properties.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri",
                    () -> "http://127.0.0.1:" + backend.getAddress().getPort());
        }
    }

    @AfterAll
    static void stop() {
        backend.stop(0);
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/products,-,-,200", "GET,/api/categories,-,-,200", "GET,/api/inventory,-,-,200",
            "POST,/api/orders,-,-,401", "POST,/api/orders,ivan,ivan,200",
            "PATCH,/api/products/1,ivan,ivan,403", "PATCH,/api/products/1,anna,anna,200",
            "GET,/api/orders,ivan,ivan,403", "GET,/api/orders,anna,anna,200",
            "GET,/api/orders/42,ivan,ivan,200", "GET,/api/orders/by-email?email=test@example.com,ivan,ivan,200",
            "GET,/api/unknown,anna,anna,403", "GET,/api/orders/42,ivan,wrong,401",
            "POST,/api/inventory/reserve,ivan,ivan,403", "POST,/api/inventory/release,anna,anna,200",
            "POST,/api/categories,ivan,ivan,403", "GET,/api/orders/private/all,anna,anna,403"
    })
    void enforcesMatrixAndCallsBackendOnlyForAllowedRequests(String method, String route,
                                                            String username, String password, int expected) {
        calls.set(0);
        var client = client();
        var request = client.method(HttpMethod.valueOf(method)).uri(route);
        if (!username.equals("-")) {
            request.headers(headers -> headers.setBasicAuth(username, password));
        }
        var response = request.exchange().expectStatus().isEqualTo(expected)
                .expectHeader().doesNotExist("Set-Cookie");
        if (expected == 200) {
            response.expectBody(String.class).isEqualTo("backend reached");
            assertThat(calls.get()).isEqualTo(1);
        } else {
            assertThat(calls.get()).isZero();
        }
    }

    @Test
    void anonymousPreflightRunsBeforeAuthentication() {
        calls.set(0);
        client().options().uri("/api/orders")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true")
                .expectHeader().valueEquals("Access-Control-Max-Age", "3600");
        assertThat(calls.get()).isZero();
    }

    @Test
    void rejectsUnknownOriginAndDoesNotRememberAuthentication() {
        calls.set(0);
        client().options().uri("/api/orders").header("Origin", "https://unknown.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange().expectStatus().isForbidden();
        client().get().uri("/api/orders/42").headers(headers -> headers.setBasicAuth("ivan", "ivan"))
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("backend reached");
        client().get().uri("/api/orders/42").exchange().expectStatus().isUnauthorized();
        assertThat(calls.get()).isEqualTo(1);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    private static HttpServer startBackend() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                byte[] bytes = "backend reached".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
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
