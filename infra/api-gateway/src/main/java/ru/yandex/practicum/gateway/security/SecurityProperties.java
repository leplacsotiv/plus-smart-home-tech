package ru.yandex.practicum.gateway.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(@NotEmpty List<@Valid User> users) {
    public record User(@NotBlank String username, @NotBlank String password,
                       @NotEmpty List<@NotBlank String> roles) {
    }
}
