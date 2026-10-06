package ru.yandex.practicum.telemetry.collector.model.hub;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString(callSuper = true)
public class ScenarioAddedEvent extends HubEvent {

    @NotBlank
    @Size(min = 3)
    private String name;

    @NotEmpty
    private List<@Valid ScenarioCondition> conditions;

    @NotEmpty
    private List<@Valid DeviceAction> actions;

    @Override
    public HubEventType getType() {
        return HubEventType.SCENARIO_ADDED;
    }
}
