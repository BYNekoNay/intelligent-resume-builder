package com.intelligentresume.ai.guidance.dto;

import java.util.List;

public record AchievementGuidanceResponse(List<String> questions, boolean writesBackAutomatically) {
}
