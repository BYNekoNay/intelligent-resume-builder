package com.intelligentresume.system.dto;

import java.util.List;

public record SystemHealthResponse(String service, String status, String version,
                                   List<String> capabilities, List<CapabilityStatus> checks) {

    public record CapabilityStatus(String capability, String status) {
    }
}
