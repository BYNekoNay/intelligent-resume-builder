# 功能回归套件（functional-tests）

> 来源：2026-09-25 全功能可用性验证（`docs/reviews/2026-09-25-full-functional-verification.md`，
> 184 条断言）与 2026-09-26 面试答题异步化修复（`docs/reviews/2026-09-26-interview-answer-async-fix.md`）。
> **入库动机**：此前这 184 条断言只存在于执行者的临时目录 —— 不进版本库、不进 CI，
> 是缺陷反复逃逸的机制性原因（QA 复核认定的头号结构性盲区）。

## 是什么

4 套 **HTTP 黑盒**回归套件（纯 Python 标准库，零第三方依赖），打在**运行中的服务**上：

| 套件 | 覆盖 | 断言量级 | 依赖 |
| --- | --- | --- | --- |
| `suites/suite_core.py` | 简历/版本、**PDF×7 模板真实导出**、ATS 规则、投递 CRUD+状态迁移+统计、导入、跨账号隔离、边界码 | 27 | 无 AI |
| `suites/suite_lifecycle.py` | 账号生命周期（注册/登录/改邮箱/改密码/logout/删号）、资料/JD/版本归档恢复、评分、沟通模板 CRUD+预览+草稿、**面试全流程**、答案资产、删号 | 65 | 仅面试首题（1 次 AI） |
| `suites/suite_edges.py` | **refresh 旋转**、删除一致性、ATS ai-retry 语义、AI 门禁、`aiFailure` 暴露 | 61 | 无 AI |
| `suites/suite_ai_full.py` | **7 类 AI 任务**（选材/生成/ATS 分析/沟通/润色/成果引导/面试）+ 授权撤回门禁 | 31 | **真实模型密钥** |

统一入口：`run_all.py`（顺序执行 + 汇总 + AI 门控）。

## 运行

```bash
# 前置：一个可用的服务实例（API + 数据库 + pdf-service）。
#   本地直连部署：服务已在 127.0.0.1:8080（nginx 8088 亦可）
#   CI：见 .github/workflows/functional.yml（MySQL service + 打包 jar + 启动 + 运行）

python3 functional-tests/run_all.py http://127.0.0.1:8088

# 只跑某个套件：
python3 functional-tests/suites/suite_core.py http://127.0.0.1:8088
```

### AI 路径的门控（重要）

`suite_ai_full.py` 与 `suite_lifecycle.py` 的面试用例需要**真实模型密钥**。
CI 里默认**跳过**（`FUNCTIONAL_AI_LIVE` 未设为 `true`），并在汇总中明确标注
"该路径本轮未验证，不得视为通过"—— 与后端既有的 `BAILIAN_LIVE_TEST` /
`MYSQL57_LIVE_TEST` 环境门控模式一致。**跳过不是通过**。

## AI 路径最近一次真实验证

| 日期 | 环境 | 结果 | 关键证据 |
| --- | --- | --- | --- |
| 2026-09-30 | 本地（ci profile + 真实百炼密钥 + 8 模型链，实测模型 `qwen3.8-max`） | **31 通过 / 0 失败 / 0 阻塞**（510s） | 7 类 AI 任务全绿：选材（推荐 4/4）、生成（草稿 8 个顶层字段 + 确认建版本）、ATS（HYBRID 无 fallback）、沟通、润色（3 候选待确认）、成果引导、面试（AI 模式首题异步）；撤回同意后 AI 触发被 403 拦截；批次④ 分组线程实证（执行线程 `ai-task-heavy-1`） |
| 2026-09-30 | **CI（GitHub Actions `functional.yml` ai-live job，workflow_dispatch 勾选）** | **31 通过 / 0 失败 / 0 阻塞**（220s） | run `36696664363` 全绿（同轮常规功能回归 job 亦通过）：8 模型链经 `vars.BAILIAN_MODEL_CHAIN` 显式传入（`qwen3.8-max,glm-5.3,...`）、`ai-provider`/`ai-model-chain` 双 UP |
| 2026-10-02 | **CI（同上，workflow_dispatch 勾选）** | **31 通过 / 0 失败 / 0 阻塞** | run `36973380723` 双 job 全绿：**E1 移除 `kimi-k3` 后的 7 模型链首次 CI 真实验证**（`vars.BAILIAN_MODEL_CHAIN` 已同步为与生产 `app/api/.env` 一致的 7 链）+ 第六十九批（失败文案公开接缝等）改动后的 AI 路径复核；同轮功能回归 job 亦通过 |

验证方式：`python functional-tests/suites/suite_ai_full.py <base_url>`（本地 jar 为 `3f0722a` 打包；CI 走 workflow_dispatch）。
CI 侧 `ai-live` job 的启用条件：`BAILIAN_API_KEY` secret + 可选 `BAILIAN_MODEL_CHAIN` variable（未设置时后端回退 `BAILIAN_MODEL` 单模型）+ workflow_dispatch 勾选 `run_ai_live`，见 `.github/workflows/functional.yml`。

## 已固化的契约（写用例前先读，避免再猜）

- **AI 触发类 POST 普遍要求必填 `Idempotency-Key` 请求头**（面试 start/answer/follow-up、
  communications/ai-generate），缺失即 400；且**每轮必须换新键**（ATS 检查按幂等键缓存结果）
- 面试启动响应字段是 `interviewId`；评分 match 是 `matchResultId`；导出是 `taskId`
- `/jobs/{id}/parse` 返回 `JobDescriptionDetail`，关键词在 `parsedKeywordsJson.data.keywords`
- `GET /resumes/{resumeId}/versions` 有 `archived` 参数，**默认只返回未归档版本**；版本字段是 `archivedAt`
- 简历版本保存需 `resumeJson` + **必填** `sourceType`；**当前版本不能归档**（409，属设计）
- 模板预览需 `resumeVersionId` + `jobDescriptionId`；导入建议需 `resumeId`
- 面试状态机 `GENERATING_QUESTION / AWAITING_ANSWER / EVALUATING_ANSWER / AI_ACTION_REQUIRED / COMPLETED`；
  `/answer` 仅当 `AWAITING_ANSWER` 才接受（已异步化：立即返回 `EVALUATING_ANSWER` 后轮询）
- `INTERVIEW_COACH` 所需同意类别 = `["RESUME","INTERVIEW_ANSWER"]`（有 JD 另需 `JOB_DESCRIPTION`）
- 中文查询参数必须 URL 编码；`DELETE /auth/me` 后 login 返回 403（拒绝即正确）

## CI

见 `.github/workflows/functional.yml`：MySQL service container → 打包 jar → 以环境变量
覆盖数据源启动 → 等 readiness → `run_all.py`。AI 套件默认跳过；需要在带密钥的环境
（如测试环境）验证 AI 路径时，以 `FUNCTIONAL_AI_LIVE=true` 手动触发。

## 维护约定

- 每修复一个用户可见缺陷，**必须**在这里补一条能复现原缺陷的断言（只增不删）；
- 断言写法遵循"先读契约再写"：上面列过的字段名/必填项不要再猜；
- 新增端点时同步补对应套件，保持"91 端点覆盖矩阵"不缩水。
