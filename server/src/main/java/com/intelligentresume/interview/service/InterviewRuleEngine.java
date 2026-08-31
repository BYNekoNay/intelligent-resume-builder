package com.intelligentresume.interview.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 面试规则模式纯逻辑（无外部依赖）。
 *
 * <p>包含规则话题列表、首题/下一题模板、规则评分 {@link #ruleScore}。
 */
@Component
public class InterviewRuleEngine {

    public static final int MAX_RULE_TOPICS = 18;

    public static final List<String> RULE_TOPICS = List.of(
            "自我介绍", "求职动机", "核心项目", "困难问题", "技术或业务取舍",
            "岗位技能", "协作冲突", "失败复盘", "优先级管理", "利益相关者沟通",
            "学习能力", "主人翁意识", "量化结果", "不确定性处理", "质量与风险",
            "反馈处理", "职业目标", "候选人提问"
    );

    public static final String RULE_FIRST_QUESTION = "请用两分钟介绍你的核心经历、专业优势和职业目标。";
    public static final String RULE_NEXT_TEMPLATE = "请分享一个关于「%s」的具体经历或思考。";

    /**
     * 规则评分：基础 35 分；回答长度达到 80/160 字各加分；命中 STAR 关键词额外加分，封顶 100。
     */
    public int ruleScore(String answer) {
        int score = 35;
        if (answer.length() >= 80) score += 15;
        if (answer.length() >= 160) score += 10;
        String lower = answer.toLowerCase(Locale.ROOT);
        long star = List.of("situation", "task", "action", "result", "背景", "任务", "行动", "结果")
                .stream().filter(lower::contains).count();
        score += (int) Math.min(20, star * 5);
        return Math.min(100, score);
    }

    /** 按已完成轮数轮转规则话题，生成下一题。 */
    public String nextRuleQuestion(int completedRounds) {
        int idx = completedRounds % RULE_TOPICS.size();
        return RULE_NEXT_TEMPLATE.formatted(RULE_TOPICS.get(idx));
    }
}
