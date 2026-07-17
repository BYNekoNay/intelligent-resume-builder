package com.intelligentresume.ai.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.intelligentresume.ai.task.dto.TaskCreateRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

@Service
public class IdempotencyService {

    private final ObjectMapper canonicalJson = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public String fingerprint(TaskCreateRequest request) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("additionalInput", request.additionalInput() == null ? Map.of() : request.additionalInput());
        payload.put("excludedMaterialIds", valuesOrEmpty(request.excludedMaterialIds()));
        payload.put("includedMaterialIds", valuesOrEmpty(request.includedMaterialIds()));
        payload.put("jobDescriptionId", request.jobDescriptionId());
        payload.put("preferredMaterialIds", valuesOrEmpty(request.preferredMaterialIds()));
        payload.put("targetResumeId", request.targetResumeId());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint AI task request", exception);
        }
    }

    private List<Long> valuesOrEmpty(List<Long> ids) {
        return ids == null ? List.of() : ids;
    }
}
