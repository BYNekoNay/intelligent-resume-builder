# 面试回答接口异步化修复报告（2026-09-26）

> 修复对象：`docs/decisions/OPEN-DECISIONS.md` 2026-09-25 登记、
> `docs/reviews/2026-09-25-full-functional-verification.md` §2 确认的 **P1 缺陷** ——
> 面试回答接口同步等待 AI，实测平均 108.7s，而前端该接口超时 60s，4 轮全部超时。

---

## 0. 一页结论

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| `POST /interviews/{id}/answer` 每轮耗时（真实 AI） | 102.3 / 76.4 / 110.0 / 145.9s（**4 轮全部超过前端 60s 超时**） | **0.1 / 0.0 / 0.0 / 0.0s** |
| 用户提交回答后 | 前端报超时、服务端仍在评估；重试遇 409「当前不在等待回答状态」、finish 遇 409「AI 操作进行中」 → **卡死** | 立即进入"评估中"界面，轮询自动取到下一题 |
| finish / report | 无法走到 | **COMPLETED + 报告完整**（totalScore=30，14 个字段） |
| 后端测试 | 652 | **657**（新增 5 条回归），0 失败 |

**一句话**：把事务外的 AI 评估从请求线程移到**有界后台执行器**，`/answer` 秒回；
前端本来就会在 `EVALUATING_ANSWER` 状态下轮询，因此**前端零改动**。

---

## 1. 根因（两层）

### 1.1 直接原因：同步等待
`InterviewAnswerService.answer()` 的 TX1 之后就地在请求线程调用 `evaluateAnswer`（实测 76–146s），
而前端 `web/src/api/interview.ts` 对该接口的超时是 `timeout: 60_000`。

### 1.2 隐藏的第二个坑：陈旧接管阈值（修复过程中实测暴露）
把评估移到后台后，第一次真实验证发现会话在 76s 后被转成 `AI_ACTION_REQUIRED`、
评估结果在 TX2 被丢弃（`error_code=CONFLICT「会话状态已变更」`）。
根因：`InterviewOperationSupport.PROCESSING_TAKEOVER_SECONDS = 75` —— 这个"挂死接管"阈值
是为旧的同步世界设定的；后台评估合法耗时 76–146s，**轮询中的 getState 在第 75 秒把
正常运行的 attempt 标记为 `PROCESSING_TIMEOUT`**。

> 服务端日志（ground truth）：
> `interview evaluation failed: sessionId=10 attemptId=16 code=CONFLICT msg=会话状态已变更`

---

## 2. 修复内容（3 个文件，前端零改动）

| 文件 | 改动 |
| --- | --- |
| **新增** `interview/service/InterviewEvaluationConfig.java` | 有界后台执行器（2 线程 + 队列 50，`AbortPolicy`），宿主机仅 3.6 GiB，**必须拒绝而非无界堆积**；声明为可注入 `ExecutorService`，测试可传同步执行器保证确定性 |
| `interview/service/InterviewAnswerService.java` | TX1 后**把"事务外 AI 评估 + TX2"抽成 `runEvaluation(...)` 提交执行器**，`/answer` 立即返回 `EVALUATING_ANSWER`；队列满 → attempt 标记为**可重试失败**（`QUEUE_REJECTED`）并返回 `aiFailure`，绝不静默滞留；后台任何未预期异常都兜底落到 attempt（`UNEXPECTED`），避免会话停在评估中 |
| `interview/service/InterviewOperationSupport.java` | `PROCESSING_TAKEOVER_SECONDS` **75 → 600**：评估后台化后合法耗时 76–146s，其自身受 AI 链总预算（600s）约束 —— 超过 600s 才可能是挂死，此时接管才正确 |

**为什么不需要改前端**：`InterviewView.vue` 本来就会在 `EVALUATING_ANSWER` 状态下
`scheduleStatePoll()`（间隔 2s），`applySessionState(result)` 也会正确进入加载界面；
且 attempt 上存有 `pendingAnswer`，`/ai/retry` 无需客户端重发答案即可重跑。

---

## 3. 回归测试（新增 5 条 + 同步更新 2 条）

**新增 `InterviewAnswerServiceTest`（5 条）** —— 此前这条链路**零测试**：

| 用例 | 断言 |
| --- | --- |
| **核心回归**：`answer()` 在 AI 被调用之前就返回 | 返回状态 = `EVALUATING_ANSWER`；此刻 `evaluateAnswer` **从未被调用**；任务已入执行器 |
| 后台评估成功 | AI 被调用、回答记录落库、attempt 置 SUCCESS、状态推进 |
| 后台评估失败 | 会话转 `AI_ACTION_REQUIRED`（不卡在评估中）、attempt 标记失败 |
| 执行器队列满 | 立即返回失败且 `aiFailure` 非空、`QUEUE_REJECTED` 且**可重试** |
| 幂等键复用（评估进行中） | 重放不再次提交评估、不再调用 AI |

**同步更新 2 条**（它们把旧阈值 75s 写死，契约变更属有意为之）：
`InterviewOperationSupportTest.isStale_timeoutWindow`（边界改为 599/601s）与
`InterviewControllerIT.getStateTakesOverAStaleProcessingAttempt`（80s → 601s）。

**全量**：`Tests run: 657, Failures: 0, Errors: 0, Skipped: 5` —— BUILD SUCCESS。

---

## 4. 真实环境验证（部署后，真实百炼）

| 轮次 | `/answer` 耗时 | 之后轮询 | 题目进度 |
| --- | --- | --- | --- |
| #1 | **0.1s** | 96s → AWAITING_ANSWER | 1/4 |
| #2 | **0.0s** | 78s → AWAITING_ANSWER | 2/4 |
| #3 | **0.0s** | — | 3/4 |
| #4 | **0.0s** | 86s → AWAITING_ANSWER | 4/4 |
| finish | **0.0s → COMPLETED** | | |
| report | **0.0s，totalScore=30，14 个字段** | | |

对照修复前（同步）：4 轮 102.3 / 76.4 / 110.0 / 145.9s，全部超时、卡死。
**轮询耗时不降**（评估本身就要这么久），但它已不在请求路径上 —— 用户不再超时，界面持续有进度。

---

## 5. 残留风险与未做项（如实标注）

| 项 | 说明 |
| --- | --- |
| `POST /interviews/start` 仍同步 | 实测 11.5–13.3s，对 60s 超时有 4 倍余量，未改；若模型链降级变慢，同类问题可能在 start 上复现（已登记观察，未修） |
| 接管阈值 600s 的代价 | 若评估真挂死，用户最多在"评估中"界面停留到 600s 才被接管。实际由评估自身的单模型读超时（300s）先兜底，接管只是最后防线 |
| 队列满的用户体验 | 队列 50 满 → 立即返回可重试失败（并发 >52 个同时答题才会发生，当前规模不会触及） |
| 前端 `aiLoadingHint` 文案 | 面试等待文案仍为"5~15 秒"，与真实 76–146s 不符（前端角色已提示）；属文案问题，未在本轮修 |
| 未覆盖 | 浏览器级验证（界面是否正确渲染评估中→下一题的切换）、并发答题压测 |

---

## 6. 变更记录

| 日期 | 变更 | 原因 |
| --- | --- | --- |
| 2026-09-26 | 初版 | 异步化 `/answer` + 陈旧接管阈值对齐后台评估时长；5 条新回归 + 657 全量绿 + 真实环境 4 轮全通过 |
