package ru.yandex.practicum.order.feign;

import feign.FeignException;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FeignConfiguration {

    @Bean
    RequestInterceptor sourceServiceHeader() {
        return template -> template.header("X-Source-Service", "order-service");
    }

    @Bean
    Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    ErrorDecoder errorDecoder() {
        return (methodKey, response) -> RemoteCallFailures.httpFailure(
                methodKey.startsWith("ProductClient") ? "Каталог" : "Склад", response.status(),
                FeignException.errorStatus(methodKey, response));
    }

    @Bean
    CircuitBreakerNameResolver circuitBreakerNameResolver() {
        return (clientName, target, method) -> target.type().getSimpleName() + method.getName();
    }
}
