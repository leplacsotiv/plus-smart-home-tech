package ru.yandex.practicum.telemetry.analyzer.model;

import ru.yandex.practicum.kafka.telemetry.event.DeviceTypeAvro;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sensors", uniqueConstraints = @UniqueConstraint(columnNames = {"hub_id", "device_id"}))
public class Sensor {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "hub_id", nullable = false)
    private String hubId;
    @Column(name = "device_id", nullable = false)
    private String deviceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private DeviceTypeAvro type;
}
