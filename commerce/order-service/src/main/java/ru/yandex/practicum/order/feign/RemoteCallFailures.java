package ru.yandex.practicum.order.feign;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import ru.yandex.practicum.order.exception.DependencyUnavailableException;
import ru.yandex.practicum.order.exception.OrderProcessingException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public final class RemoteCallFailures {
    private RemoteCallFailures() {
    }

    public static RuntimeException translate(String service, Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof OrderProcessingException business) {
                return business;
            }
            if (current instanceof DependencyUnavailableException unavailable) {
                return unavailable;
            }
            if (current instanceof FeignException feign) {
                return httpFailure(service, feign.status(), cause);
            }
            if (current instanceof IOException || current instanceof TimeoutException
                    || current instanceof CallNotPermittedException) {
                return new DependencyUnavailableException(service + " временно недоступен", cause);
            }
        }
        return new OrderProcessingException(service + " вернул некорректный ответ", cause);
    }

    public static RuntimeException httpFailure(String service, int status, Throwable cause) {
        if (status < 0 || status >= 500) {
            return new DependencyUnavailableException(service + " временно недоступен", cause);
        }
        String reason = switch (status) {
            case 404 -> service.equals("Каталог") ? "Товар не найден" : "Нет складской записи для товара";
            case 409 -> "Недостаточно товара или конфликт состояния склада";
            default -> service + " отклонил запрос или вернул некорректные данные";
        };
        return new OrderProcessingException(reason, cause);
    }
}
