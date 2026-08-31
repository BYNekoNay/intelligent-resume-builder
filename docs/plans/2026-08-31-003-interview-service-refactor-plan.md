# InterviewService 上帝类拆分任务卡（优化项 #8）

> date: 2026-08-31
> type: refactor
> origin: 全维度优化点诊断 #8（1278 行上帝类，13 个注入依赖）
> author: 架构师高见远（software-architect）
> status: implementation-ready
> executor: 工程师寇豆码

## 0. 前置发现（修正诊断 2 处）

- 依赖注入实际为 **12 个 Bean + 1 个 @Value 配额 = 13 项**（非 9）
- **不存在 InterviewServiceTest 单测**；真正回归网是 `InterviewControllerIT`（22 个有序用例，HTTP 全流程覆盖 start/answer/retry/rule/finish/report/幂等/配额/stale），每步必须全绿
- 勿改 AI 调用模型：保持同步两阶段短事务 + InterviewAiAttempt 追踪（切 AiTask 异步属独立优化项）

## 1. 拆分目标类清单（9 新类 + 1 门面，均在 service 包）

| 类 | 职责（一句话） |
|---|---|
| InterviewRuleEngine | 规则模式纯逻辑：题列表/首题/下一题模板、ruleScore 评分（无依赖，可静态） |
| InterviewStateAssembler | 只读响应组装：buildStateResponse、LastEvaluation、AiFailureInfo、latestFailedAttempt、distinctFeedback、owned/notFound/validation 小助手 |
| InterviewPromptContextAssembler | AI 上下文构建：首题/评估上下文、简历与 JD 归属校验 validateSource、findOwnedResumeVersion |
| InterviewOperationSupport | AI 操作生命周期：attempt 创建、指纹幂等、配额/同意校验、stale/retry 判定、失败标记、评估结果应用（answer/retry/start 共享） |
| InterviewStartService | start 两阶段流程（TX1 建会话+同意/配额/幂等 → 事务外首题 AI → TX2 落库） |
| InterviewAnswerService | answer 两阶段流程（TX1 校验+attempt → 事务外评估 AI+修复 → TX2 记录+outcome） |
| InterviewRetryService | retryAi 两阶段流程（含 retryGeneration 计数、stale 丢弃） |
| InterviewRuleService | continueWithRules + ruleAnswer 规则模式（@Transactional 单事务） |
| InterviewReportService | report + finish 终态与报告聚合（@Transactional） |
| InterviewService（门面） | 保留 8 个 public 签名 + getState，其余纯委托；行数目标 <200 |

## 2. 方法迁移映射表

**public**：start→StartService；answer→AnswerService；retryAi→RetryService；continueWithRules/ruleAnswer→RuleService；finish/report→ReportService；getState→**留在门面**（Controller answer() 依赖它判断 RULE 模式路由）

**private 去向**：
- OperationSupport：callAiForFirstQuestion、providerRequestId、isRetryable、isStale、isCurrentRetry、assertRetryStillCurrent、validateEvaluationProgress、applyEvaluationOutcome、buildAiFeedback、replayOutcome、markAttemptFailed、createAttempt、createRuleAttempt、secureFingerprint/buildFingerprint/buildStartFingerprint、checkInterviewQuota、reserveRepairCall、hasInterviewConsent
- StateAssembler：buildStateResponse、buildLastEvaluation、buildAiFailure(两版)、latestFailedAttempt、distinctFeedback、num、owned、notFound、validation
- PromptContextAssembler：buildFirstQuestionContext、buildEvaluationContext、appendResumeContext、validateSource、findOwnedResumeVersion
- RuleEngine：ruleScore、nextRuleQuestion、RULE_TOPICS/RULE_FIRST_QUESTION/RULE_NEXT_TEMPLATE 常量
- 内部 record/enum：StartPreparation→StartService；AnswerPreparation/AnswerOutcome→AnswerService；RetryService 沿用 long[] 技法

## 3. 依赖重组

- RuleEngine：无
- StateAssembler：InterviewRecordRepository、InterviewAiAttemptRepository
- PromptContextAssembler：InterviewContextSanitizer、JobDescriptionRepository、ResumeRepository、ResumeVersionRepository、InterviewRecordRepository
- OperationSupport：InterviewAiAttemptRepository、AiProviderRegistry、InterviewAiService、UserRepository、TransactionTemplate、AiConsentService、@Value(interviewDailyQuota)
- StartService：sessionRepository、consentService、interviewAiService、tx ＋ 3 支持类
- AnswerService / RetryService：sessionRepository、recordRepository、consentService、interviewAiService、tx ＋ 3 支持类
- RuleService：sessionRepository、recordRepository ＋ StateAssembler/OperationSupport/RuleEngine
- ReportService：sessionRepository、recordRepository ＋ StateAssembler
- 门面：5 个流程服务 ＋ sessionRepository/attemptRepository/tx/StateAssembler（仅 getState 用）

## 4. 接口/Controller 影响

Controller **零改动**（继续注入 InterviewService 门面，类名/Bean 名不变）；DTO/返回类型/REST 契约全部不变；getState 保留门面保证 answer() 路由逻辑不变。

## 5. 测试策略

- 迁移：无现有 ServiceTest 可迁；InterviewAiServiceTest/InterviewContextSanitizerTest 不动；InterviewControllerIT 为每步回归网
- 新增单测（每支持类一个）：RuleEngineTest（评分边界 35/60/100、题轮转）、StateAssemblerTest（计数/LastEvaluation/AiFailureInfo）、PromptContextAssemblerTest（JD 有无、外部简历脱敏、历史截断、validateSource）、OperationSupportTest（指纹确定/区分、配额边界、reserveRepairCall、stale/isCurrentRetry、replayOutcome、createAttempt 字段）；report 聚合由 IT 覆盖，可加 ReportServiceTest 单测维度聚合

## 6. 实施顺序（每步独立 commit，可回滚）

- **T01** 抽 4 支持类＋单测；原私有方法改为委托新类（不保留重复逻辑）。验证：`mvn test -Dtest=InterviewRuleEngineTest,InterviewStateAssemblerTest,InterviewPromptContextAssemblerTest,InterviewOperationSupportTest,InterviewControllerIT`
- **T02** 抽 StartService/AnswerService，门面 start/answer 改委托。验证：`mvn test -Dtest=InterviewControllerIT`
- **T03** 抽 RetryService/RuleService，门面 retryAi/continueWithRules/ruleAnswer 改委托。验证：同上
- **T04** 抽 ReportService（report/finish），删光门面已迁移私有方法，门面仅剩 getState+委托（<200 行）。验证：`mvn test -Dtest=InterviewControllerIT` + 启动冒烟
- **T05** 补齐边界单测 + 全量回归 `mvn test`（surefire 含 *Test 与 *IT）。验收：1278 行→门面 <200、8 个 public 签名不变、全量绿

## 7. 风险与回滚

- @Transactional 位置：continueWithRules/finish/ruleAnswer/report 方法级注解必须原样带到新类 public 方法；start/answer/retryAi 只用 TransactionTemplate，勿混用
- 自调用陷阱：门面只做外部 Bean 委托，禁止在门面私有方法内调 @Transactional 方法（绕过代理）
- 循环依赖：依赖单向（门面→流程→支持→仓储），流程类禁反向注入门面；IT 启动即暴露
- 幂等/stale/retryGeneration 计数必须整体搬迁，禁止拆散到两个类
- 日志铁律：新类日志只记 id/状态/错误码，禁打简历/JD/回答内容；consent 类别列表集中 OperationSupport
- 回滚：每任务一 commit，git revert 单 commit 即回退；验收指标=全量测试绿＋门面 <200 行
