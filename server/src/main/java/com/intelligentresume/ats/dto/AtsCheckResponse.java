package com.intelligentresume.ats.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AtsCheckResponse(Long id, BigDecimal totalScore, Map<String, Object> checks,
                               List<String> passedChecks, List<String> risks,
                               List<String> priorities, String disclaimer) {
}
