package com.intelligentresume.interview.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎纯逻辑单测：评分边界与话题轮转。
 */
class InterviewRuleEngineTest {

    private final InterviewRuleEngine engine = new InterviewRuleEngine();

    @Test
    @DisplayName("规则评分：空回答仅得基础 35 分")
    void ruleScore_base35() {
        assertEquals(35, engine.ruleScore(""));
        assertEquals(35, engine.ruleScore("短回答"));
    }

    @Test
    @DisplayName("规则评分：长度达到 80 字加 15 分 → 50")
    void ruleScore_length80_adds15() {
        assertEquals(50, engine.ruleScore("x".repeat(80)));
    }

    @Test
    @DisplayName("规则评分：长度达到 160 字再加 10 分 → 60")
    void ruleScore_length160_adds25() {
        assertEquals(60, engine.ruleScore("x".repeat(160)));
    }

    @Test
    @DisplayName("规则评分：STAR 关键词每个 +5 分，封顶 20 分")
    void ruleScore_starKeywordsCapAt20() {
        // 命中 1 个关键词：35 + 15 + 10 + 5 = 65
        assertEquals(65, engine.ruleScore("x".repeat(160) + " situation"));
        // 命中 8 个关键词：35 + 15 + 10 + min(20, 40) = 80
        String allStar = "situation task action result 背景 任务 行动 结果 " + "x".repeat(200);
        assertEquals(80, engine.ruleScore(allStar));
    }

    @Test
    @DisplayName("规则评分：超长回答不会超过 100 上限")
    void ruleScore_neverExceeds100() {
        String huge = "situation task action result 背景 任务 行动 结果 ".repeat(50);
        assertTrue(engine.ruleScore(huge) <= 100);
    }

    @Test
    @DisplayName("下一题模板：按已完成轮数轮转话题")
    void nextRuleQuestion_rotatesTopics() {
        assertEquals("请分享一个关于「自我介绍」的具体经历或思考。", engine.nextRuleQuestion(0));
        assertEquals("请分享一个关于「求职动机」的具体经历或思考。", engine.nextRuleQuestion(1));
        // 第 19 轮回到第一个话题（18 % 18 == 0）
        assertEquals("请分享一个关于「自我介绍」的具体经历或思考。", engine.nextRuleQuestion(18));
        // 每个话题都能命中一次
        assertTrue(engine.RULE_TOPICS.contains("候选人提问"));
        assertEquals(18, engine.RULE_TOPICS.size());
    }
}
