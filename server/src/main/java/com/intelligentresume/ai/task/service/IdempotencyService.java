package com.intelligentresume.ai.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等性服务。基于请求体内容生成 SHA-256 指纹,用于检测重复但内容不同的请求。
 */
@Service
public class IdempotencyService {

    private final ObjectMapper sortedMapper;

    public IdempotencyService() {
        this.sortedMapper = new ObjectMapper();
        this.sortedMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 计算请求体的指纹:SHA-256 哈希的前 32 个十六进制字符。
     * 使用排序键的规范化 JSON 确保相同内容产生相同指纹。
     */
    public String fingerprint(Map<String, Object> requestBody) {
        try {
            Map<String, Object> sorted = sortDeep(requestBody);
            String json = sortedMapper.writeValueAsString(sorted);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 32);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to compute request fingerprint", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sortDeep(Map<String, Object> map) {
        if (map == null) {
            return new TreeMap<>();
        }
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                sorted.put(entry.getKey(), sortDeep((Map<String, Object>) value));
            } else {
                sorted.put(entry.getKey(), value);
            }
        }
        return sorted;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
