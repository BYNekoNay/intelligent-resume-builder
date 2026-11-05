package com.intelligentresume.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 资料更新请求：目前只能改展示名（≤128，与 {@code user.display_name} 对齐）。
 * 服务端会先 {@code trim()} 再落库；空串会被 {@code @NotBlank} 拦下。
 */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 128) String displayName
) {}
