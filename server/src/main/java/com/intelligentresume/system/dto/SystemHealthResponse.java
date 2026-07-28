package com.intelligentresume.system.dto;

import java.util.List;

public record SystemHealthResponse(String service, String status, String version, List<String> capabilities) {
}
