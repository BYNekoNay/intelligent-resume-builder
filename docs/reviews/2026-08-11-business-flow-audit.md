# 业务流程与代码审查报告

**日期：** 2026-08-11  
**范围：** 当前工作树的求职工作台改造，以及 AI 生成、ATS 交接、简历编辑、导出和面试迁移的跨层契约。  
**方式：** 只审查和验证；未修改任何产品实现或测试代码。用户自有的 `docs/实习面试项目应对手册.md` 未纳入范围。

## 结论

当前改造已正确落实首页优先级、ATS 历史版本只读、显式创建可编辑继任版本、返回诊断入口和懒加载故障恢复。未发现 P1（会造成数据越权、不可恢复损坏或核心流程完全不可用）问题。

发现两个 P2 问题：一个会让 AI 生成简历在预览和 PDF 中丢失时间信息；另一个会让过期的物料请求错误地影响已切换的编辑上下文。建议在合并前修复，并补足相应的跨流程回归测试。

## 已确认的产品规则

| 场景 | 已确认规则 | 当前实现复核 |
| --- | --- | --- |
| 首页下一步 | 截止临近的待完成投递 -> 面试准备/跟进 -> 失败或待处理生成任务 -> 最近编辑的简历 | `HomeView.vue` 的优先级与工作台测试覆盖该顺序。 |
| ATS 到编辑器 | 保留“返回诊断”入口，历史分析来源只读，用户显式创建继任版本后才可编辑 | `ResumeEditorView.vue` 校验 `restoredFromVersionId` 与 ATS 来源版本一致；伪造 `editVersionId` 会回退到历史只读版本。 |
| 待分析 ATS | `resumeId` 可为空，未完成时不得构造编辑器链接 | 服务端 DTO、前端类型和 ATS 集成测试一致。 |
| 发布后旧页面 | 识别动态模块加载失败后最多重新加载一次；再次失败转为可重试错误页 | `router/index.ts` 使用按目标路径隔离的 session 标记，错误页可清除标记后重试。 |

## 发现

### P2-1：AI 生成的 `period` 在简历预览与导出 PDF 中被忽略

**影响：** 用户确认 AI 生成的工作、项目或教育经历后，系统会保存时间文本，但编辑器预览和最终 PDF 不显示它。简历的关键时间线因此缺失，直接影响投递产物。

**证据链：**

1. [JobGenerationPromptBuilder.java](../../server/src/main/java/com/intelligentresume/ai/generation/service/JobGenerationPromptBuilder.java#L91) 明确要求模型为工作经历输出 `period`。
2. [GenerationConfirmView.vue](../../web/src/views/GenerationConfirmView.vue#L427) 提交的是逐条“接受/编辑/拒绝”决策；[ResumeJsonNormalizer.java](../../server/src/main/java/com/intelligentresume/ai/confirmation/service/ResumeJsonNormalizer.java#L33) 只复制、删改路径并移除元数据，不会把 `period` 转换为日期字段，因此该字段会原样持久化。
3. [resume.ts](../../web/src/types/resume.ts#L31) 的 `ResumeWorkItem` 没有 `period`；[ResumePaper.vue](../../web/src/components/resume/ResumePaper.vue#L82) 仅展示 `startDate` 和 `endDate`；[classic.js](../../pdf-service/src/templates/classic.js#L122) 也只读取这两个字段。
4. [workflow.spec.ts](../../web/e2e/workflow.spec.ts#L1720) 的真实生成草稿样例本身含有 `period`，但测试只检查公司和成果文本，没有确认、预览和导出后的时间信息。

**建议修复：** 选择并统一一个持久化契约。较低风险的方案是让 Web 类型、编辑器预览和 PDF 渲染都将 `period` 作为 `startDate/endDate` 不完整时的展示后备值；同时将生成提示和确认表单逐步迁移到结构化日期字段。补一条“生成 -> 确认 -> 编辑器预览 -> PDF”测试，断言 `period` 可见。

### P2-2：同一简历内切换 ATS 查询参数后，旧物料请求可污染新编辑上下文

**影响：** 用户在简历 A 中打开“从资料库添加”，请求尚未结束时接受同一简历 A 的 ATS 交接查询跳转。旧请求若失败，新页面会显示“物料插入失败/资料库加载失败”，并且旧请求未结束前新页面的插入状态仍可能被禁用。用户会误以为新上下文的操作失败。

**证据链：**

1. [ResumeEditorView.vue](../../web/src/views/ResumeEditorView.vue#L323) 和 [ResumeEditorView.vue](../../web/src/views/ResumeEditorView.vue#L342) 仅用 `props.id` 判断物料异步结果是否仍属于当前页面。
2. [ResumeEditorView.vue](../../web/src/views/ResumeEditorView.vue#L709) 在同一 `props.id` 下监听 ATS 查询参数、清空草稿并重新加载编辑器，但不会增加物料操作的上下文令牌或重置该请求的专属状态。
3. 因为切换前后 `props.id` 都是 A，旧请求在 `catch/finally` 中的 `props.id === targetResumeId` 仍为真，因而可写入共享的 `error`、`materialInsertLoading` 或 `materialLibraryLoading` 状态。

**建议修复：** 为每一次编辑器装载生成上下文序号或稳定的“简历 ID + 交接查询指纹”，物料加载、插入和保存都只允许修改与其发起时相同的上下文。查询变化时清理或失效物料请求状态。新增两条浏览器回归：待处理请求在切换后拒绝，以及待处理请求在切换后成功；两者都不得改变新上下文的提示、内容或加载状态。

## 已验证的关键流程

- ATS：历史版本继任链、空 `resumeId` 的分析中状态、同编辑器查询跳转的脏数据确认，均通过当前实现与专门测试复核。
- 数据库：V18 对历史面试轮次按 `created_at, id` 回填并加唯一约束；升级测试通过。
- 路由：24 个懒加载路由、首次失败后的单次恢复、持续失败错误页、鉴权重定向保持原目标路径，均通过。单次恢复场景另连续运行 5 次，均通过；此前一次批量运行的超时未能复现，因此仅作为后续 CI 观察项，不列为缺陷。
- 前端质量：i18n 守卫覆盖 37 个 Vue 文件和两个语言包；生产构建成功，路由视图仍按需拆分。
- PDF：语法检查和 9 项模板/安全/布局测试通过。

## 验证记录

| 检查 | 结果 |
| --- | --- |
| `web/npm run build` | 通过；i18n 自检 9/9，37 个 Vue 文件，两个语言包。 |
| `web/npx playwright test e2e/route-loading.spec.ts --workers=1` | 通过，24/24。 |
| 路由单次恢复测试重复 5 次 | 通过，5/5。 |
| `server/mvn -Dtest=AtsControllerIT,FlywayMigrationIT,JobGenerationSchemaValidatorTest,JobGenerationServiceTest test` | 通过，34/34。 |
| `pdf-service/npm run check; npm test` | 通过，9/9。 |
| `git diff --check HEAD` | 通过；仅有既有文件换行符提示。 |

## 仍需补强的证据

- 本轮未完成全量 Playwright 套件：组合运行超过审查环境的单次时限。已完成的路由套件不能替代其余业务流程的全量浏览器验证。
- `test:e2e:local` 需要本地 Spring、数据库、PDF 服务和可用 AI 配置；本轮未将模拟 API 测试表述为真实服务互操作证明。
- 没有自动化测试覆盖“AI 生成的 `period` 经确认后在预览和 PDF 可见”，也没有覆盖物料请求在同简历查询切换后的失败/成功收敛。

## 建议处理顺序

1. 修复 `period` 与渲染契约不一致，并增加从生成确认到 PDF 的完整回归。
2. 为编辑器异步操作绑定装载上下文，修复物料请求的跨上下文状态泄漏。
3. 在具备真实依赖的环境执行本地服务烟测，并将该流程纳入可复现的发布证据。

