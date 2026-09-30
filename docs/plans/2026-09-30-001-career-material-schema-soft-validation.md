# 职业资料 contentJson 最小 schema 与软校验方案（三步走第一步）

> 性质：**方案 + 盘点**。来源：`docs/reviews/2026-09-26-optimization-opportunities.md` TC-8（P2）。
> 基线：`codex/resume-loop-enhancements @ 48b34c7`（批次④ 完成后）。所有字段结论均带消费方 `文件:行号` 实测出处。
> 一句话：13 类资料中 3 类有硬校验、10 类长期放行；本方案为 10 类定义「候选 schema」，先以**软校验告警**落地（只记日志不拒绝），观察后再决定逐类升级硬校验。

---

## 1. 现状矩阵（实测）

| 校验层 | 覆盖类型 | 落点 | 行为 |
| --- | --- | --- | --- |
| 通用证据规则 | 全部 13 类 | `CareerMaterialEvidence.java:26-44`、`CareerMaterialService.java:158-163` | 必须有来源原文或至少一处有意义的结构化值（`title` 键不算） |
| 大小上限 | 全部 13 类 | `CareerMaterialService.java:146-156` | `app.career-material.content-json.max-bytes`（默认 65536） |
| **硬校验** | 仅 3 类：ACHIEVEMENT / LEADERSHIP_EXPERIENCE / SKILL_EVIDENCE | `CareerMaterialService.java:179-189`（switch）、`:203-231` | 必填字段缺失即 `40001` 拒绝 |
| **软校验（本次新增）** | 其余 10 类 | `CareerMaterialSchema.java` + `CareerMaterialService.java:191-201` | 半填形态记 WARN 日志，**不拒绝** |

3 类硬校验的现行必填（现状契约，不改动）：

- **ACHIEVEMENT**：`relatedMaterialId`（须指向本用户 WORK/PROJECT 资料）、`scenario`、`action`、`outcome`、`period`、`metricName`、`metricDisplayMode ∈ {EXACT, RANGE, QUALITATIVE}`，以及 `metricExactValue`（EXACT）或 `metricDisplayValue`（RANGE/QUALITATIVE）。
- **LEADERSHIP_EXPERIENCE**：`relatedMaterialId`、`responsibilityScope`、`collaborationTargets`、`teamSize`、`crossFunctionalRelationship`、`keyDecision`、`result`。
- **SKILL_EVIDENCE**：`skillName`、`category`、`proficiency`、`yearsOfExperience`、`lastUsedAt`、`applicationDescription`、`outcomeEvidence`；`relatedMaterialIds` 可选但逐项校验归属与类型。

## 2. 消费方字段实测（10 类候选 schema 的取值依据）

| 消费方 | 实测读取的键 | 出处 |
| --- | --- | --- |
| 简历编辑器「插入资料」 | work：`company|organization`、`position|role`、`startDate`、`endDate`、`period`、`description|summary|applicationDescription|responsibilityScope`、`outcome|result|outcomeEvidence`；projects：`name`、`role|position`、同上时间/正文/亮点键；skills：`skillName|name`；education：`school|institution`、`degree`、`major|area`；volunteering：`organization|company`、`role|position`；courses：`name|courseName`、`provider|institution`、`date`；certificates：`name|title`、`issuer|organization`、`date`；publications：`title|name`、`publisher|issuer`、`date`、`url|link`；awards：`name|title`、`issuer|organization`、`date` | `web/src/views/ResumeEditorView.vue:413-424`（各 fallback 组） |
| 前端编辑器（3 类专用表单） | ACHIEVEMENT / LEADERSHIP_EXPERIENCE / SKILL_EVIDENCE 的字段与硬校验一致 | `web/src/components/career-material/CareerMaterialForm.vue:84-97`、`:136-151` |
| 详情展示（键名 → 标签映射） | 3 类硬校验字段 + `crossFunctionalRelationship` 等 | `web/src/components/career-material/CareerMaterialDetail.vue:12-22` |
| AI 选材提示词 | **整体透传 contentJson**，无字段级读取 | `MaterialSelectionPromptBuilder.java:67-77` |
| AI 生成提示词 | **整体透传**（`contentJson=` + JSON 编码） | `JobGenerationPromptBuilder.java:163` |
| AI 快照清洗（ACHIEVEMENT 专项） | `metricDisplayMode`、`metricExactValue`、`metricDisplayValue`、`scenario`、`action`、`outcome`、`metricName`、`period` | `CareerMaterialAiSnapshotSanitizer.java:36-43`、`:97-106` |

结论：**没有任何后端消费方按字段契约校验**（前端编辑器是唯一字段级消费方），因此最小 schema 只能锚定「前端读取键 + 可插入性」，不能凭空发明业务必填。

## 3. 最小 schema 候选表（10 类软校验）

`knownKeys` = 出现即代表「按 schema 填写」的意图键；`subjectKeys` = 能独立构成一条有效资料的主体键（含任一即视为完整）；**半填** = 出现 knownKeys 但全缺 subjectKeys → 告警。

| 类型 | subjectKeys（主体） | knownKeys\subjectKeys（非主体补充键） |
| --- | --- | --- |
| WORK_EXPERIENCE | company, organization, position, role, description, summary | startDate, endDate, period, applicationDescription, responsibilityScope, outcome, result, outcomeEvidence |
| PROJECT_EXPERIENCE | name, role, position, description, summary | startDate, endDate, period, applicationDescription, responsibilityScope, outcome, result, outcomeEvidence |
| SKILL | skillName, name | category, proficiency, yearsOfExperience, lastUsedAt |
| EDUCATION | school, institution, degree | major, area, startDate, endDate, period |
| CERTIFICATE | name, title, issuer | organization, date |
| HIGHLIGHT | description, summary, outcome, result, outcomeEvidence | period |
| AWARD | name, title, issuer, description | organization, date |
| VOLUNTEER_EXPERIENCE | organization, company, role, position, description, summary | startDate, endDate, period, responsibilityScope, outcome, result, outcomeEvidence |
| COURSE | name, courseName, provider | institution, date, description |
| PUBLICATION | title, name, publisher, url | issuer, date, link, description |

以上为**候选**：软校验不拒绝任何请求，候选键集合可随观察期数据调整（改动集中在 `CareerMaterialSchema.java` 一处）。

## 4. 三步走计划

| 步骤 | 内容 | 状态 |
| --- | --- | --- |
| ① 软校验告警 | `CareerMaterialSchema` + `CareerMaterialService.warnOnHalfFilledContent`；半填形态记结构化 WARN，不拒绝 | ✅ **本次完成** |
| ② 观察 | 收集 `soft-schema warning` 日志（类型 + 命中的键名，**不含内容与用户标识**），统计哪些类型/键组合出现半填、量级如何；已在 CONFIRMED 登记册挂 OPEN 条目 | ⏳ 等真实使用数据 |
| ③ 硬校验 | 逐类升级为硬校验（或缺字段返回明确文案）。准入条件（三项全满足才升级单类）：ⓐ 观察期该类型半填告警可解释且量级可控；ⓑ 该类型有前端结构化编辑入口（避免用户无法填写而被拒）；ⓒ 历史数据抽样无系统性拒绝 | ⏳ 未启动 |

## 5. 历史数据风险（硬校验前必读）

| # | 形态 | 证据 | 对硬校验的威胁 |
| --- | --- | --- | --- |
| R-1 | **成就引导形态**：`{originalStatement, section, resumeVersionId, guidanceQuestions, confirmedAnswers}` | `web/src/views/AchievementGuidanceView.vue:64`（存为 WORK_EXPERIENCE / PROJECT_EXPERIENCE / SKILL） | 不含任何 schema 键；若按「company 必填」硬校验，此类存量资料在编辑保存时会被**全量拒绝** |
| R-2 | AI 导入/生成自由形状 | `DraftCommitService.java:130-134`、`JobGenerationService.java:473-479`（`editedValue()`/AI 输出原样落库） | 形状不受控，硬校验会把「资料确认」流程打断 |
| R-3 | 前端 advanced JSON 自由输入（明确允许） | `CareerMaterialForm.vue:117`、`:293` | 属于产品内合法通道，收紧需同步改编辑器与文案 |
| R-4 | 3 类硬校验是**写路径**校验 | `CareerMaterialService.java:117-121`（仅 `req.contentJson() != null` 时校验） | 历史读路径兼容；但旧数据的「再编辑保存」会触发当次校验——升级前需对存量做抽样审计 |

软校验的「意图明显才告警」规则正是为了区分 R-1~R-3（不告警）与真正的半填形态（告警），避免观察期日志被合法自由形态淹没。

## 6. 软校验规则与告警形态（已实现）

- 判定：`CareerMaterialSchema.isHalfFilled(type, content)`（`CareerMaterialSchema.java:79-96`）。
- 触发点：`CareerMaterialService.validateTypeSpecificContent` 的 default 分支（create 与 update 共用入口）。
- 告警样例（测试实测输出）：

```text
Career material contentJson soft-schema warning: materialType=WORK_EXPERIENCE, presentKnownKeys=[startDate, endDate]
```

- 隐私纪律：只记录 `materialType` 与**键名**，不记录内容值、不记录用户标识（对齐日志隐私基线）。
- 空白值（空串/空格/空数组）不计为「已出现」，避免误报。

## 7. 验证

- 判定契约：`CareerMaterialSchemaTest`（30 用例：主体键/半填键抽样、自由形态与成就引导形态不告警、空白值不计、3 类硬校验不参与软校验）。
- 放行契约：`CareerMaterialServiceTest.create_defaultTypesAcceptArbitraryContentJson`（既有）保持通过；新增 `create_halfFilledSchemaContent_isAcceptedWithSoftWarning` 锚定「软化不拒绝」。
- 回归：职业资料模块 75 测试通过；全量后端测试 **739 通过 / 0 失败**（5 skipped，基线 708 + 本次新增 31：SchemaTest 30 + ServiceTest 1）。

## 8. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-09-30 | 初版：13 类校验矩阵 + 消费方字段实测 + 10 类候选 schema + 三步走（① 已落地：软校验告警）；勘误——原优化盘点报告称「最小 schema 候选表已产出」实为交接失真，本表为首次落盘 |