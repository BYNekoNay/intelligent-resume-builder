package com.intelligentresume.interview.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 面试规则模式纯逻辑（无外部依赖）。
 *
 * <p>【小白导读】
 * ① 业务问题：AI 挂了也要让面试继续。这里用「写死的 18 个话题轮流出题 + 按回答长度和
 *    STAR 关键词数打分」的土办法，顶替模型的出题和点评。
 * ② 链路位置：只被 {@link InterviewRuleService} 调用；纯函数、不碰数据库不调模型。
 * ③ 改坏它：降级模式下出题重复/越界（话题数与 MAX_RULE_TOPICS 不一致）、
 *    规则分与五维上限冲突导致前端渲染异常。
 *
 * <p>术语速查：STAR 原则 —— 讲经历的常用框架：情境 Situation、任务 Task、
 * 行动 Action、结果 Result；规则评分就数回答里有没有这些关键词。
 *
 * <p>包含规则话题列表、首题/下一题模板、规则评分 {@link #ruleScore}。
 *
 * <p>要点：
 * <ul>
 *   <li>纯函数，不碰数据库、不调模型；因此可在事务内任意调用，也便于单测。</li>
 *   <li>话题以固定列表轮转，话题数量决定轮转周期；{@link #MAX_RULE_TOPICS} 与列表长度一致，
 *       增删话题必须同步该常量与首题文案。</li>
 *   <li>规则分只依赖回答长度与 STAR 关键词命中次数，与 AI 评估口径无关，
 *       因此不能拿来和 AI 分数横向比较。</li>
 *   <li>规则模式是 AI 不可用时的降级路径，任何改动都要保证「没有模型也能给出结论」。</li>
 * </ul>
 */
@Component
public class InterviewRuleEngine {

    public static final int MAX_RULE_TOPICS = 18;

    /** 话题按业务优先级排列：先动机与项目，再协作与反思，最后收尾提问。 */
    public static final List<String> RULE_TOPICS = List.of(
            "自我介绍", "求职动机", "核心项目", "困难问题", "技术或业务取舍",
            "岗位技能", "协作冲突", "失败复盘", "优先级管理", "利益相关者沟通",
            "学习能力", "主人翁意识", "量化结果", "不确定性处理", "质量与风险",
            "反馈处理", "职业目标", "候选人提问"
    );

    public static final String RULE_FIRST_QUESTION = "请用两分钟介绍你的核心经历、专业优势和职业目标。";
    public static final String RULE_NEXT_TEMPLATE = "请分享一个关于「%s」的具体经历或思考。";

    /**
     * 规则评分：基础 35 分；回答长度达到 80/160 字各加分；命中 STAR 关键词额外加分。
     *
     * <p>实际计分口径：35 + 15(≥80 字) + 10(≥160 字) + min(20, 命中数×5)，
     * 四项之和上限为 35+15+10+20=80，不可能触及 AI 契约的 100 分上限。
     * 具体分值（35/15/10/5/80/160）未找到明确依据，改前先全仓搜确认没有报告口径依赖。
     */
    public int ruleScore(String answer) {
        int score = 35;
        if (answer.length() >= 80) score += 15;
        if (answer.length() >= 160) score += 10;
        // 中英关键词一起匹配：候选人可能用英文缩略语或中文表述，二者都算命中 STAR
        String lower = answer.toLowerCase(Locale.ROOT);
        long star = List.of("situation", "task", "action", "result", "背景", "任务", "行动", "结果")
                .stream().filter(lower::contains).count();
        score += (int) Math.min(20, star * 5);
        return score;
    }

    /**
     * 按已完成轮数轮转规则话题，生成下一题。
     *
     * <p>入参是「已完成轮数」（调用方传的是含本题在内的新轮次数），所以首题之后第一次调用落在
     * index=1，正好避开 index=0 的「自我介绍」与 {@link #RULE_FIRST_QUESTION} 撞题。
     */
    public String nextRuleQuestion(int completedRounds) {
        int idx = completedRounds % RULE_TOPICS.size();
        return RULE_NEXT_TEMPLATE.formatted(RULE_TOPICS.get(idx));
    }
}
