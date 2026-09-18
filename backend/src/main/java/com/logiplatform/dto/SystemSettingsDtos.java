package com.logiplatform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class SystemSettingsDtos {
    private SystemSettingsDtos() {}

    public record SettingResponse(
            String group,
            String key,
            String value,
            String updatedBy,
            String updatedAt) {}

    public record SettingRequest(
            @NotBlank @Size(max = 80) String group,
            @NotBlank @Size(max = 120) String key,
            @Size(max = 4000) String value) {}
}
