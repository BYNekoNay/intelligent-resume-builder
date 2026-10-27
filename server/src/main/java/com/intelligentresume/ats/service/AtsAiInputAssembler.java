package com.intelligentresume.ats.service;

import com.intelligentresume.ats.domain.AtsCheckResult;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.resume.domain.ResumeVersion;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ATS 的「任务 input 组装 + 待发 prompt」—— 创建与发送前预览**共用同一实现**（R32 AC-R32-8）。
 *
 * <p>为什么单独成类：{@code AtsService} 已在行数棘轮登记，不能再往里塞预览逻辑；
 * 更重要的是预览与执行必须同源 —— 两处各拼一遍 input，必然出现
 * 「预览里有的内容实际没发」这种最难排查的偏差。
 *
 * <p>本类**只读**：不写库、不调 provider。
 */
@Component
public class AtsAiInputAssembler {

    private final AtsAiPromptBuilder promptBuilder;

    public AtsAiInputAssembler(AtsAiPromptBuilder promptBuilder) {
        this.promptBuilder = promptBuilder;
    }

    /** 一次组装的全部产物：落库用 {@code taskInput}，预览与实际发送用 {@code providerInput}。 */
    public record PreparedInput(Map<String, Object> taskInput, Map<String, Object> providerInput) {
    }

    /**
     * 组装 ATS 分析的任务 input 与待发消息。
     *
     * <p>注意 worker 侧是拿任务 input 现场调 {@code promptBuilder.build(input)} 的，
     * 所以这里必须用**同一份** taskInput 去 build，才能保证预览与发送逐字节相同。
     */
    public PreparedInput prepare(AtsCheckResult result, ResumeVersion version, JobDescription job) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("atsCheckResultId", result.getId());
        raw.put("resumeVersionId", result.getResumeVersionId());
        raw.put("jobDescriptionId", result.getJobDescriptionId());
        raw.put("resumeJson", promptBuilder.sanitizeResume(version.getResumeJson()));
        raw.put("jdText", job.getJdText());
        raw.put("localResult", localSnapshot(result.getResultJson()));
        raw.put("promptVersion", promptBuilder.promptVersion());
        raw.put("schemaVersion", promptBuilder.schemaVersion());
        Map<String, Object> taskInput = promptBuilder.sanitizeInput(raw);
        return new PreparedInput(taskInput, promptBuilder.build(taskInput));
    }

    private Map<String, Object> localSnapshot(Map<String, Object> source) {
        Map<String, Object> safe = source == null ? Map.of() : source;
        Map<String, Object> local = new LinkedHashMap<>();
        for (String key : List.of("checks", "passedChecks", "risks", "priorities", "disclaimer")) {
            local.put(key, safe.get(key));
        }
        return local;
    }
}
