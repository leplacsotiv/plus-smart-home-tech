package ru.yandex.practicum.telemetry.collector.mapper;

import com.google.protobuf.Timestamp;
import java.time.Instant;

final class ProtoValues {
    private ProtoValues() { }

    static Instant timestamp(Timestamp value, boolean present) {
        if (!present || value.getSeconds() < -62135596800L || value.getSeconds() > 253402300799L
                || value.getNanos() < 0 || value.getNanos() > 999999999) {
            throw new IllegalArgumentException("Valid timestamp is required");
        }
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    }

    static String required(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
