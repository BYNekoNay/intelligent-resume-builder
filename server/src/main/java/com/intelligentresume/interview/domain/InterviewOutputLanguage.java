package com.intelligentresume.interview.domain;

/**
 * 面试产出语言（题目、反馈、建议等人类可读文本的语言，不是代码里的 JSON 属性名）。
 *
 * <p>要点：
 * <ul>
 *   <li>null 在服务层一律按 {@link #ZH_CN} 处理（会话字段默认值也是它）。</li>
 *   <li>AI 返回后会做语言校验，不符即判失败；因此该字段直接影响评估失败率，不能随手改。</li>
 *   <li>只约束叙述性文本，{@code evidenceQuotes} 保留候选人原话，不受此约束。</li>
 * </ul>
 */
public enum InterviewOutputLanguage {
    ZH_CN,
    EN
}
