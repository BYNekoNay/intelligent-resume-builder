package com.intelligentresume.ai.generation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资料文本的 prompt 字节预算（ideation #36）。
 *
 * <p>背景：资料选择（{@code MaterialSelectionPromptBuilder}，最多 60 条候选）与岗位定制
 * 生成（{@code JobGenerationPromptBuilder}）此前只受条数限制——sourceText 无准入长度上限、
 * contentJson 单条准入上限 64KB，提示词体积最坏可达数 MB：既浪费配额，也可能直接超出
 * 模型上下文导致整条任务失败。本组件把这两条链路的资料文本收敛到同一预算之下。
 *
 * <p>策略（有意为之）：
 * <ul>
 *     <li><b>裁剪而不是丢弃</b>：所有资料的 materialId 始终保留在提示词中。丢弃会让
 *     「必须使用的资料」在用户无感知的情况下消失，并且要为前端新增一个按原样展示的
 *     unselectedReasons 原因码（见 {@code DraftSectionReview.vue} 的未使用资料列表）。</li>
 *     <li>单条额度 = {@code min(max-material-bytes, max-materials-total-bytes / 条数)}，
 *     因此 n 条资料的原始文本总量 ≤ 总量上限（n 为正整数时恒成立）。</li>
 *     <li>contentJson 超限时按顶层条目整条保留/省略（保持 JSON 合法），并写入
 *     {@code "_truncated": true}；sourceText 按 UTF-8 字符边界截断并追加
 *     {@code [truncated]} 标记，让模型明确知道文本不完整、不要据此补全或编造。</li>
 *     <li>预算按原始 UTF-8 字节计（不含渲染时的 JSON 转义开销）；默认值相对模型上下文
 *     留有余量——本预算用于封顶，不追求与上下文精确对齐。</li>
 * </ul>
 */
@Component
public class MaterialPromptTextBudget {

    /** 模型可见的截断标记；同时用于核算需要预留的字节数。 */
    static final String TRUNCATION_MARKER = "\n[truncated]";
    private static final int MARKER_BYTES = TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length;
    private static final String TRUNCATED_KEY = "_truncated";
    /** contentJson 被裁剪时写入标记所需预留（"{...}":true 加分隔符）。 */
    private static final int CONTENT_TRUNCATED_RESERVE = MARKER_BYTES + 24;

    private final ObjectMapper objectMapper;
    private final int maxMaterialBytes;
    private final int maxMaterialsTotalBytes;

    /** 单元测试/手工装配用默认预算。 */
    public MaterialPromptTextBudget() {
        this(new ObjectMapper(), 65536, 262144);
    }

    @Autowired
    public MaterialPromptTextBudget(
            ObjectMapper objectMapper,
            @Value("${app.ai.prompt.max-material-bytes:65536}") int maxMaterialBytes,
            @Value("${app.ai.prompt.max-materials-total-bytes:262144}") int maxMaterialsTotalBytes) {
        this.objectMapper = objectMapper;
        this.maxMaterialBytes = maxMaterialBytes;
        this.maxMaterialsTotalBytes = maxMaterialsTotalBytes;
    }

    /**
     * 按预算裁剪整批资料的模型可见文本。
     *
     * <p>返回与入参同序的新对象列表（不修改入参实体）；文本未超限的资料原样保留。
     */
    public List<CareerMaterial> clip(List<CareerMaterial> materials) {
        if (materials == null || materials.isEmpty()) {
            return List.of();
        }
        int allowance = allowance(materials.size());
        List<CareerMaterial> result = new ArrayList<>(materials.size());
        for (CareerMaterial material : materials) {
            result.add(clipOne(material, allowance));
        }
        return result;
    }

    /** 单条额度：min(单条上限, 总量/条数)，至少 1 字节，保证总量受总量上限约束。 */
    private int allowance(int count) {
        long share = maxMaterialsTotalBytes / Math.max(count, 1);
        return (int) Math.max(1, Math.min(maxMaterialBytes, share));
    }

    private CareerMaterial clipOne(CareerMaterial material, int allowance) {
        Map<String, Object> content = material.getContentJson();
        long contentBytes = content == null ? 0 : byteSize(content);
        Map<String, Object> clippedContent = content;
        if (content != null && contentBytes > allowance) {
            clippedContent = clipContent(content, allowance);
            contentBytes = byteSize(clippedContent);
        }

        long remaining = Math.max(0, allowance - contentBytes);
        String sourceText = material.getSourceText();
        if (sourceText != null && !sourceText.isEmpty()) {
            if (remaining <= MARKER_BYTES) {
                // 额度已被 contentJson 占满：宁可给空文本，也不留一个无意义的超短前缀。
                sourceText = "";
            } else if (byteSize(sourceText) > remaining) {
                sourceText = clipToUtf8Bytes(sourceText, (int) (remaining - MARKER_BYTES)) + TRUNCATION_MARKER;
            }
        }

        CareerMaterial copy = new CareerMaterial();
        copy.setId(material.getId());
        copy.setUserId(material.getUserId());
        copy.setMaterialType(material.getMaterialType());
        copy.setTitle(material.getTitle());
        copy.setUsagePreference(material.getUsagePreference());
        copy.setContentJson(clippedContent);
        copy.setSourceText(sourceText);
        return copy;
    }

    /**
     * contentJson 超限：按插入顺序整条保留能放下的顶层条目，并写入 {@code _truncated} 标记。
     * 保持 JSON 合法（不切断字符串），省略的字段由标记提示模型数据不完整。
     */
    private Map<String, Object> clipContent(Map<String, Object> content, int allowance) {
        Map<String, Object> clipped = new LinkedHashMap<>();
        long used = 2; // {}
        boolean omitted = false;
        for (Map.Entry<String, Object> entry : content.entrySet()) {
            long entryBytes = byteSize(entry.getKey()) + byteSize(entry.getValue()) + 4; // 引号/冒号/逗号
            if (used + entryBytes + CONTENT_TRUNCATED_RESERVE > allowance && !clipped.isEmpty()) {
                omitted = true;
                continue;
            }
            if (used + entryBytes + CONTENT_TRUNCATED_RESERVE > allowance) {
                omitted = true;
                break;
            }
            clipped.put(entry.getKey(), entry.getValue());
            used += entryBytes;
        }
        if (omitted || (!content.isEmpty() && clipped.isEmpty())) {
            clipped.put(TRUNCATED_KEY, true);
        }
        return clipped;
    }

    /** 按 UTF-8 字符边界截断（不会切出半个 CJK / 代理对），返回不超过 maxBytes 字节的前缀。 */
    private String clipToUtf8Bytes(String text, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private long byteSize(Object value) {
        if (value instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8).length;
        }
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException e) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
        }
    }
}