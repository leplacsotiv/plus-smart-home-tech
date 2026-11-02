package ru.yandex.practicum.order.feign;

import feign.RequestInterceptor;
import feign.Retryer;
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
}
