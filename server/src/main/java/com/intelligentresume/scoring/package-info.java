/**
 * JD 规则覆盖度打分：确定性、可复现、<b>不依赖 AI 可用性</b>。
 *
 * <h2>分数怎么来的</h2>
 * 三个维度加权：{@code keyword} 0.4 + {@code skill} 0.4 + {@code experience} 0.2，
 * 规则版本 {@code v1.0.0}（{@code RuleVersion}）。结果必须能解释到"哪条要求命中/缺失"，
 * 所以 {@code Explanation} 是一等公民，不是调试信息。
 *
 * <h2>这一片最容易改错的地方：同义词词典</h2>
 * 词典必须以<b>嵌套 map</b> 形式经 {@code @ConfigurationProperties}（{@code ScoringProperties}）绑定。
 * 历史上用过 {@code @Value("#{${app.scoring.synonym-dictionary:{}}}")}，该写法对嵌套 YAML 无效，
 * 占位符静默走默认值 {@code {}} —— 词典在运行时为空，含空格的多词关键词（如 {@code Spring Boot}）
 * 即使与简历逐字相同也判缺失，{@code keywordCoverage} 被系统性低估。
 * 完整说明见 {@code application.yml} 该节点上方的警示注释，回归用例
 * {@code ScoringDictionaryBindingIT} / {@code KeywordCoveragePhraseTest}。
 *
 * <h2>文本清洗只走一个入口</h2>
 * {@code Normalizer} 是本仓<b>唯一</b>的清洗实现（静态 {@code Pattern}，避免每 token 两次
 * {@code replaceAll}）。不要在别处再抄一份规则 —— 清洗规则一旦分叉，分词与匹配就会错位。
 *
 * <h2>口径声明</h2>
 * 对外必须带 {@code user-disclaimer}：这是 JD 规则覆盖度，<b>不是</b>企业 ATS 结果，也不是录用概率。
 */
package com.intelligentresume.scoring;
