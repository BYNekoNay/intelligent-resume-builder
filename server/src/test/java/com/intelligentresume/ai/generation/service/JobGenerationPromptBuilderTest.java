package com.intelligentresume.ai.generation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.jobdescription.domain.JobDescription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JobGenerationPromptBuilder 单元测试。
 *
 * <p>覆盖 002 修复计划 U1 的时间范围契约:
 * 生成提示必须优先结构化日期,同时在来源资料只有自由文本
 * {@code period} 时保留该原文,而不是让模型去拆解或编造日期。
 */
class JobGenerationPromptBuilderTest {

    private JobGenerationPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new JobGenerationPromptBuilder(new ObjectMapper());
    }

    private JobDescription jd(String title) {
        JobDescription jobDescription = new JobDescription();
        jobDescription.setId(1L);
        jobDescription.setUserId(100L);
        jobDescription.setTitle(title);
        jobDescription.setJdText("负责 Spring Boot 微服务开发,3 年以上经验");
        return jobDescription;
    }

    private CareerMaterial material(Long id, MaterialType type, Map<String, Object> contentJson) {
        CareerMaterial material = new CareerMaterial();
        material.setId(id);
        material.setUserId(100L);
        material.setMaterialType(type);
        material.setTitle("材料 " + id);
        material.setContentJson(contentJson);
        material.setSourceText("原始文本内容");
        material.setUsagePreference(UsagePreference.NORMAL);
        return material;
    }

    @Test
    @DisplayName("提示契约要求工作/教育/项目优先输出 startDate 与 endDate")
    void prompt_requestsStructuredDatesForTimeRanges() {
        JobDescription jobDescription = jd("Java后端工程师");
        List<CareerMaterial> fixed = List.of(
                material(1L, MaterialType.WORK_EXPERIENCE, Map.of(
                        "company", "星河科技", "position", "高级后端工程师",
                        "startDate", "2021-03", "endDate", "2023-06")));

        JobGenerationPromptBuilder.Prompt prompt =
                builder.build(jobDescription, fixed, List.of(), List.of(), "v1.0.0");

        assertTrue(prompt.task().contains("startDate"), "任务段应要求输出 startDate");
        assertTrue(prompt.task().contains("endDate"), "任务段应要求输出 endDate");
        assertTrue(prompt.task().contains("\"startDate\": \"2021-03\", \"endDate\": \"2023-06\""),
                "示例输出应使用结构化日期字段,而不是只给 period");
    }

    @Test
    @DisplayName("提示契约禁止从自由文本 period 拆解或编造结构化日期")
    void prompt_forbidsInventingStructuredDatesFromFreeFormPeriod() {
        JobDescription jobDescription = jd("Java后端工程师");
        List<CareerMaterial> fixed = List.of(
                material(1L, MaterialType.WORK_EXPERIENCE, Map.of(
                        "company", "星河科技", "position", "高级后端工程师",
                        "period", "2021 年至今")));

        JobGenerationPromptBuilder.Prompt prompt =
                builder.build(jobDescription, fixed, List.of(), List.of(), "v1.0.0");

        assertTrue(prompt.system().contains("startDate"), "系统规则应提到结构化日期字段");
        assertTrue(prompt.system().contains("period"), "系统规则应允许保留自由文本 period");
        assertTrue(prompt.task().contains("period"), "任务段应允许输出源资料支持的 period");
        assertTrue(prompt.task().contains("Do NOT invent"),
                "任务段应禁止编造结构化日期");
        assertTrue(prompt.task().toLowerCase().contains("never split")
                        || prompt.task().toLowerCase().contains("never guess")
                        || prompt.system().toLowerCase().contains("never split"),
                "提示应明确禁止拆解或猜测自由文本日期");
    }

    @Test
    @DisplayName("教育/项目条目同样遵循结构化日期优先、period 兜底的契约")
    void prompt_appliesTimeRangeContractToEducationAndProjects() {
        JobDescription jobDescription = jd("平台工程师");
        List<CareerMaterial> fixed = List.of(
                material(1L, MaterialType.EDUCATION, Map.of(
                        "school", "示例大学", "degree", "本科", "startDate", "2018-09", "endDate", "2022-06")),
                material(2L, MaterialType.PROJECT_EXPERIENCE, Map.of(
                        "name", "订单平台", "period", "2023 Q2 - 2023 Q4")));

        JobGenerationPromptBuilder.Prompt prompt =
                builder.build(jobDescription, fixed, List.of(), List.of(), "v1.0.0");

        assertTrue(prompt.task().contains("work, education, and project entries")
                || prompt.task().contains("education, and project"),
                "任务段应把时间范围规则应用到 work/education/project 三类条目");
        assertTrue(prompt.task().contains("startDate"));
        assertTrue(prompt.task().contains("endDate"));
        assertTrue(prompt.task().contains("period"));
    }

    @Test
    @DisplayName("数据段保留来源资料原文,不要求模型改写 period")
    void dataSection_keepsSourcePeriodText() {
        JobDescription jobDescription = jd("Java后端工程师");
        List<CareerMaterial> fixed = List.of(
                material(1L, MaterialType.WORK_EXPERIENCE, Map.of(
                        "company", "星河科技", "position", "高级后端工程师",
                        "period", "2021 年至今")));

        JobGenerationPromptBuilder.Prompt prompt =
                builder.build(jobDescription, fixed, List.of(), List.of(), "v1.0.0");

        assertTrue(prompt.data().contains("2021 年至今"),
                "数据段应原样包含来源资料的 period 文本,方便模型直接引用");
        assertTrue(prompt.data().contains("===DATA==="), "数据段应保留不可信数据边界声明");
        assertTrue(prompt.data().contains("not instructions"), "数据段应声明内容不是指令");
    }
}
