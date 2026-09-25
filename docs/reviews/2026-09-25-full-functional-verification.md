# 全功能可用性验证报告（2026-09-25）

> 目标：回答用户的问题 —— **「所有功能是否都能正常工作」**（本轮不涉及部署与运维优化）
> 方法：**我执行取证 + 专家团独立复核**。（专家角色不带工具，因此分工是：我跑真实环境验证，
> 专家复核我的证据与结论，我再对专家指出的每一项**实际补验或给出反面证据**。）
> 环境：`http://127.0.0.1:8088`（真实部署 + 真实百炼模型链），全部为 HTTP 黑盒验证

---

## 0. 一页结论

| 项 | 结果 |
| --- | --- |
| 断言总数 | **184 条**（套件A 31 + 套件B 27 + 套件C 65 + 套件B2 61） |
| 通过 | 套件A **31/31** · 套件B **27/27** · 套件C 60/61（余 1 为我的客户端超时，见下）· 套件B2 部分待判 |
| **确认的真实功能缺陷** | **1 个（P1）**：面试回答接口同步等待 AI，实测平均 **108.7s** 而前端超时 **60s** |
| 排除的"疑似缺陷" | **3 个**（经实测/读码证伪，含专家指控 2 个 + 我自己 1 个） |
| 待产品确认的行为 | 1 个：`ats/checks/{id}/ai-retry` 对纯规则检查返回 200 但不真正发起 AI |
| 端点覆盖 | 91 个端点中**明确验证 74 个**；11 个弱验证；6 个未验证（详见 §3） |

**一句话**：功能的**主干全部可用**（含真实 AI 的 7 类任务、7/7 PDF 模板、面试全流程到报告、
版本归档恢复、账号生命周期、跨账号隔离）；**唯一确认的功能缺陷是面试回答接口的超时错配**。

---

## 1. 验证方法（为什么这样分工）

专家团角色（PM / 架构师 / 前端 / 后端 / QA / 运维）**不带工具**，无法自己跑测试。
所以本轮采用：

1. **我采集并执行**：枚举 91 个端点 → 写 4 套 HTTP 黑盒脚本 → 在真实环境跑真实 AI；
2. **专家独立复核**：把端点清单、覆盖矩阵、原始执行结果与我的自我归因分发给对应角色，
   要求指出"未验证之处、弱断言、以及我把真缺陷误判成测试问题的可能"；
3. **我对专家的每条指控实际补验或给出反面证据**（见 §4）—— 专家也可能看错，
   其结论与我的结论**同等需要被验证**。

---

## 2. 确认的真实功能缺陷（1 个，P1）

### 2.1 面试回答接口同步等待 AI，实测耗时远超前端超时

| 项 | 实测 |
| --- | --- |
| 接口 | `POST /api/interviews/{id}/answer` |
| 实测耗时（4 轮，真实 AI） | **102.3s / 76.4s / 110.0s / 145.9s，平均 108.7s** |
| 对照：`POST /api/interviews/start` | 13.3s（正常） |
| 前端该接口超时 | **`web/src/api/interview.ts` 中 3 处 `timeout: 60_000`** |
| 前端基础超时 | `web/src/api/client.ts` `timeout: 10_000` |

**结论**：**4 轮全部超过前端 60s 超时**（最短 76.4s 也超）。这不是偶发，是结构性的。

**用户会经历什么**（结合代码推演 + 实测）：
1. 提交回答 → 前端 60s 后抛超时错误；
2. 服务端**仍在继续评估**（实测：随后调用 finish 返回 `Cannot finish while an AI operation is in progress`）；
3. 用户重试提交 → 新 `Idempotency-Key` 会新建 attempt，但会话状态此时是 `EVALUATING_ANSWER`
   而非 `AWAITING_ANSWER` → **409「当前不在等待回答状态」**；
4. 想结束面试 → **409「AI 操作进行中」**；
5. 即：**用户会卡在一个既不显示成功、也无法前进的面试里**。

**与既有 ADR 的关系**：这与已修复的 **ADR-004（前端轮询窗口与后端链路总预算不一致）** 属同一类问题 ——
**前端超时窗口与后端真实耗时脱节**。ADR-004 修的是轮询窗口，本次是**同步接口的超时**。

**功能本身没坏**：用足够长的客户端超时（300s）走完全流程成功 ——
`start → 4 轮 answer → finish(COMPLETED) → report`，报告含真实内容
（总分 27、3 条优势、含具体改进建议的弱点、简历建议、表达建议、维度分）。
**所以这是"超时错配"而非"功能不可用"**，但对用户而言效果等同于坏掉。

---

## 3. 端点覆盖矩阵（91 个端点）

| 状态 | 数量 | 说明 |
| --- | --- | --- |
| ✅ **明确验证** | 74 | 有断言校验语义（不仅是 2xx） |
| ⚠️ **弱验证** | 11 | 仅验证了响应码或存在性 |
| ❓ **未验证** | 6 | 见下 |

**明确验证的代表**（本轮新增/补强的）：
账号生命周期全链（注册/登录/**refresh 旋转**/logout/logout-all/改显示名/改邮箱/改密码/删号）、
职业资料完整 CRUD、JD 完整 CRUD + parse + reference、**简历版本归档·取消归档·恢复·切换当前版本**
（含"归档当前版本被拒 409"这一设计规则）、评分 match + 结果回读、ATS check + 回读、
**PDF 导出 7/7 模板**（校验 `%PDF-` 头 + 内嵌中文字体）、投递完整 CRUD（含乐观锁 version）、
**沟通模板 CRUD + 预览 + 草稿**、**面试全流程到报告**、面试答案资产 CRUD + 关键词筛选、
个人档案导入建议、AI 任务续办列表、跨账号隔离（读/列表两侧）、删号后旧 token 失效、
删除简历/JD 后关联资源不可读。

**未验证的 6 个**：
- `POST /api/ai/tasks/{id}/reject`（草稿拒绝路径）—— 我的前置载荷缺必填字段导致未走到，**属于我的缺测**
- `POST /api/exports/tasks/{id}/retry` 的**成功重试路径**（实测对已成功任务返回 409，符合设计）
- `GET /api/jobs` 列表、`POST /api/ai/tasks`（实测被门禁拒绝：`This AI task must start from its domain endpoint`，属设计）
- `POST /api/interviews/{id}/continue-with-rules` 的**正常路径**（仅验证了对不存在会话返回 404；它要求 `ExecutionMode.RULE`，而 AI 模式下会话是 AI 模式，故本轮未构造）
- 移动端视口、可访问性、以及**全部界面层**（见 §5）

---

## 4. 排除的"疑似缺陷"（3 个，均给出反面证据）

专家的复核意见与我的初步判断都可能是错的。逐条核实结果：

| # | 谁提出的 | 说法 | 核实结论 |
| --- | --- | --- | --- |
| 1 | **QA 角色** | 面试 AI 失败时**静默**、用户永远等下去，属产品缺陷；并质疑我把真缺陷误判成测试问题 | ❌ **不成立（两层都不成立）**。① API 层：只授 `RESUME`（缺 `INTERVIEW_ANSWER`）时，状态响应里的 `aiFailure` **明确非空** —— `{"stage":"INITIAL_QUESTION","retryable":false,"reauthorizationRequired":true,"messageCode":"FORBIDDEN"}`；② 界面层：`InterviewView.vue:83` 取值、`:505` `v-if="aiFailure?.retryable"` 显示重试按钮、`:511` `v-if="aiFailure?.reauthorizationRequired"` **引导重新授权**。→ 失败面设计完整 |
| 2 | **前端角色** | `DraftSectionReview.vue` 的 `MATERIAL_TYPE_KEYS` 只列 9 类、缺 `LEADERSHIP_EXPERIENCE`/`SKILL_EVIDENCE`/`VOLUNTEER_EXPERIENCE`，选材确认页会**裸显枚举** | ❌ **不成立**。该常量在前端**不存在**（`DraftSectionReview.vue` 522 行内无任何 `_KEYS`/`_EXPERIENCE`）。真实映射是 `career-material/options.ts` 的 `MATERIAL_TYPE_OPTIONS`，经脚本比对 **13/13 全覆盖**（多出的 3 项是"使用偏好"选项） |
| 3 | **我自己** | 告警规则引用的 4 个 `_total` 指标"应用未暴露 → 规则永不触发" | ❌ 已撤回。它们是 `AppObservability` 中定义的 **Counter**，Micrometer **零样本不导出序列**，而 API 刚重启、调用发生在重启前 → **取样假象** |

**方法论价值**：这 3 条里 2 条来自"读得到源码的专家"，1 条来自我自己。说明
**专家与执行者都需要被验证** —— 与项目既有教训一致（引用任何"现存问题清单"前必须逐条核实）。

---

## 5. 待产品确认的行为（1 个）

`POST /api/ats/checks/{id}/ai-retry` 对**用 `useAi=false` 创建的纯规则检查**：
实测返回 **HTTP 200**，但 `analysisStatus=RULES_FALLBACK`、`analysisSource=RULES`、**`aiTaskId=None`**，
20 秒后复查仍是 `RULES_FALLBACK / RULES` —— 即**返回成功但并未真正发起 AI 分析**。

两种解释都说得通，需产品口径确认：
1. **设计如此**：不允许把纯规则检查"升级"为 AI 分析 → 那么返回 200 会误导调用方，应返回明确拒绝；
2. **空操作缺陷**：应发起 AI 任务却没有 → 则是真缺陷。

（本轮未构造"AI 分析失败后再 retry"这一真正目标场景，因需要人为制造 AI 失败。）

---

## 6. 本轮学到的契约事实（已固化，避免下次再猜）

| 契约 | 事实 |
| --- | --- |
| **AI 触发类 POST 普遍要求必填 `Idempotency-Key` 头** | 面试 `start` / `answer` / `follow-up`、`communications/ai-generate`。缺失即 **400**（而非 403/422） |
| 面试启动响应字段 | **`interviewId`**（不是 `id`/`sessionId`） |
| 评分 match 响应字段 | **`matchResultId`**（不是 `id`） |
| `/jobs/{id}/parse` 返回 | `JobDescriptionDetail`，关键词在 **`parsedKeywordsJson.data.{role,keywords,requirements}`** |
| 版本列表 | `GET /resumes/{resumeId}/versions` 有 **`archived` 布尔参数**，**默认只返回未归档版本**；版本 DTO 用 **`archivedAt`（时间戳）** 而非布尔 |
| 简历版本保存 | `resumeJson` + **必填** `sourceType` |
| 模板预览 | `GET communications/templates/{id}/preview` 需**两个必填** `@RequestParam`：`resumeVersionId`、`jobDescriptionId` |
| 导入建议 | `GET personal-profile/import-suggestion` 需必填 `@RequestParam resumeId` |
| 面试状态机 | `GENERATING_QUESTION / AWAITING_ANSWER / EVALUATING_ANSWER / AI_ACTION_REQUIRED / COMPLETED`；`/answer` **仅当 `AWAITING_ANSWER`** 才接受 |
| `INTERVIEW_COACH` 所需同意类别 | **`["RESUME","INTERVIEW_ANSWER"]`**（有 JD 时另需 `JOB_DESCRIPTION`）。漏授 `INTERVIEW_ANSWER` 会让 AI 尝试以 FORBIDDEN 失败 |
| refresh 旋转语义 | 旧 refresh token 复用 → **401「刷新令牌已失效」**；**新 refresh token 仍可用**（非整族撤销） |
| 删除账号 | login → **403**（账号 DISABLED）；旧 access token → 401 |
| 删号后登录 | 被拒即正确（403 属合理拒绝码，不必是 400/401） |

---

## 7. 我在本轮的测试缺陷（8 处，全部为"凭印象假设契约"）

> 这条单独列出，因为它是本轮**最大的效率损耗源**，也是本轮唯一产出错误结论的原因。

| # | 缺陷 | 后果 |
| --- | --- | --- |
| 1 | 变量拼接 curl 参数，`--noproxy *` 的 `*` 被 glob 展开 | 公网验证输出作废 |
| 2 | 把 `.py` 当 shell 跑（`bash x.py`） | 未执行 |
| 3 | 导出响应按 `data.id` 取值（实际 `taskId`） | 7 个成功任务被误判失败 |
| 4 | 简历版本保存漏传必填 `sourceType` | 后续 4 组用例被整体跳过 |
| 5 | 中文查询参数未 URL 编码 | HTTP 0 假失败 |
| 6 | 面试启动/回答漏传必填 `Idempotency-Key` 头 | 全部 400 假失败 |
| 7 | 面试响应按 `id/sessionId` 取值（实际 `interviewId`） | 整个面试断言段被跳过 |
| 8 | 端点提取正则尾部多一个必需 `)`，漏掉所有无参映射 | 端点数被低估为 74（实际 91） |
| 9 | 空循环假通过：用 glob 未匹配到文件却输出"无缺失" | 差点把"未检查"当成"已通过" |
| 10 | 文件名写错（scp 成 `pi.py` 却执行 `probe_interview.py`） | 探针未运行，白等一轮 |

**对策（已固化进 `MEMORY.md`）**：断言前先读 DTO / 源码；**写入"通过"之前，先用已知坏样本确认判据真的会失败**；
凡"循环内校验"，必须先断言"循环体确实执行过"。

---

## 8. 未覆盖范围（如实标注）

| 未覆盖 | 说明 |
| --- | --- |
| **全部界面层** | 本轮 184 条断言**全部是 HTTP 黑盒**，不经过浏览器。界面能否承接（渲染、交互、文案、布局）**仍未验证** —— 这是与上一轮评估相同的最大空白 |
| 移动端视口 / 可访问性 | 从未测 |
| `reject` 草稿拒绝路径 | 因我的载荷缺字段未走到 |
| 面试 `continue-with-rules` 正常路径 | 需 RULE 模式会话，本轮未构造 |
| 导出/ATS 的"失败后重试成功"路径 | 需人为制造失败（如停 pdf-service） |
| 高并发 / 多用户并发写 | 未做 |
| 真实数据规模 | 仍为合成小数据 |

---

## 9. 附：本轮脚本与可复现命令

| 脚本 | 覆盖 | 说明 |
| --- | --- | --- |
| `.workbuddy/tools/test_remote_ai.py`（项目既有） | 7 类 AI 任务 + 授权门禁 | 31 断言，235s |
| `.workbuddy/tmp/test_non_ai.py` | 简历/版本、PDF×7、ATS、投递、导入、隔离、边界码 | 27 断言 |
| `.workbuddy/tmp/test_full_functional.py` | 账号生命周期、资料/JD/版本/评分/沟通/面试/资产/隔离/删号 | 65 断言 |
| `.workbuddy/tmp/test_batch2.py` | refresh 旋转、删除一致性、ATS ai-retry、AI 门禁、面试补充端点、aiFailure 暴露 | 61 断言 |
| `.workbuddy/tmp/probe_interview.py` | 面试全流程 + 精确计时 | 实测 `/answer` 平均 108.7s |

---

## 10. 变更记录

| 日期 | 变更 | 原因 |
| --- | --- | --- |
| 2026-09-25 | 初版 | 4 套脚本 184 条断言 + 三专家复核 + 逐条补验；确认 1 个 P1 缺陷、排除 3 个疑似缺陷 |
