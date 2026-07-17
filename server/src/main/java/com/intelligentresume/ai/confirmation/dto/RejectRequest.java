package com.intelligentresume.ai.confirmation.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record RejectRequest(@NotNull LocalDateTime taskUpdatedAt) {}