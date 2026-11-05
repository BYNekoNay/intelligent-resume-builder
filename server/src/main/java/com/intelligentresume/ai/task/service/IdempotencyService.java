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
 * 幂等指纹计算 —— 把请求快照压成一个稳定的短字符串,用来判断"同一个 Idempotency-Key 下的内容有没有变"。
 *
 * <p>要点 / 红线:
 * <ul>
 *   <li>指纹只取 SHA-256 十六进制串的前 32 位(128 bit)。这只用于区分请求,不是安全边界,
 *       不要拿它做签名或鉴权。</li>
 *   <li>稳定性依赖两点:Map 必须递归排序({@code sortDeep} + {@code ORDER_MAP_ENTRIES_BY_KEYS}),
 *       且 **List 顺序保持原样**。只对 map 排序意味着"数组元素换序"会被判为内容变化 —— 这是刻意的,
 *       因为数组顺序在简历里通常有语义。</li>
 *   <li>指纹输入是 {@code AiTaskService#buildInputSnapshot} 产出的快照,不是原始 HTTP 请求体;
 *       改快照字段集等于改变幂等判定口径,会让部署前后的同 key 重试被判成 CONFLICT。</li>
 *   <li>序列化或摘要算法失败一律包成 RuntimeException:宁可让请求 500,也不能退回"空指纹"导致幂等失效。</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    /** 专用 mapper:开启了 map key 排序,不要复用全局 ObjectMapper(它可能被其它配置改动)。 */
    private final ObjectMapper sortedMapper;

    public IdempotencyService() {
        this.sortedMapper = new ObjectMapper();
        this.sortedMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 计算请求体的指纹:SHA-256 哈希的前 32 个十六进制字符。
     * 使用排序键的规范化 JSON 确保相同内容产生相同指纹。
     *
     * <p>嵌套 Map 会被递归排序,但 List 内部元素的顺序原样保留(见类注释)。
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

    /** 递归把每层 Map 换成 TreeMap。null 输入按空 Map 处理,避免上游漏判时 NPE。 */
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
