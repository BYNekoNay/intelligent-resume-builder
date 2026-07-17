# T11 MVP 端到端验收进度报告

日期：2026-07-13  
执行环境：Windows，Java 17，Node.js，H2 本地联调配置  
代码状态：当前工作区未提交版本

## 环境

| 服务 | 地址 | 结果 |
| --- | --- | --- |
| Web | `http://127.0.0.1:5176` | 监听正常 |
| API | `http://127.0.0.1:8082` | `/api/system/health` 返回 `UP` |
| PDF | `http://127.0.0.1:3010` | `/health` 返回 `UP` |

Flyway 在空 H2 数据库依次执行 V1 至 V8，无重复建表或迁移错误。

## 自动化证据

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `Set-Location server; mvn test` | 39 个测试，0 failure，0 error；Flyway V1–V8 全部通过 |
| 前端 | `Set-Location web; npm run build` | `vue-tsc` 与 Vite 构建通过 |
| PDF 静态检查 | `Set-Location pdf-service; npm run check` | 通过 |
| PDF 模板测试 | `Set-Location pdf-service; npm test` | 2 个测试通过 |
| PDF 实际渲染 | 向 `POST :3010/render` 提交 classic 结构化 JSON | 生成 8305 字节文件，签名 `%PDF` |

## 已覆盖场景

- 注册、登录、Token 解析和未授权错误。
- 简历、历史版本、JD、职业资料和资源归属。
- AI 同意、撤回、幂等任务、租约、重试和跨用户访问。
- 岗位定制草稿、来源确认、唯一版本创建和规则覆盖度。
- 私有 PDF 任务、失败、过期、校验和及路径安全。
- 实时润色、零碎资料生成、ATS 体检、投递状态、面试和答案资产。
- PDF、DOCX、TXT 简历文本解析及未知文件拒绝。
- 岗位定制生成只从真实职业资料构造 `_source=career-material:{id}`，不再生成虚构公司/项目；确认后写入 `resume_material_reference`，并可通过 `GET /api/career-materials/{id}/references` 查询版本、路径、状态、理由和快照。
- 面试完成态拒绝额外回答；投递页面支持录入和更新反馈，沟通草稿保持可编辑且不自动发送。
- 答案资产支持按关联 JD 和薄弱项关键词筛选；`DELETE /api/auth/me` 软删除账号、停用登录并撤销全部会话。
- `PerformanceSmokeIT` 对热身后的简历列表与规则评分各采样 12 次，p95 分别按 `<300ms` 与 `<2s` 门槛通过；该测试不替代生产并发压测。
- 真实服务采样（2026-07-13 08:45，API `8082`、PDF `3010`）：简历列表 12 次 p95 `8.75ms`，规则评分 12 次 p95 `41.01ms`，实时 AI 优化 6 次 p95 `42.56ms`，PDF 渲染 3 次 p95 `2506.85ms`；均低于文档中的对应阈值（CRUD 300ms、评分 2s、AI 10s、PDF 15s）。
- 异步岗位定制任务创建真实采样（2026-07-13 08:46，6 次）p95 `38.62ms`，低于任务创建 `<500ms` 门槛；任务结果仍由异步 worker 状态接口读取。

## 浏览器证据

- 登录后打开 `/resumes/1/edit`，实时预览显示简历内容。
- 点击个人概要润色，返回 3 个候选版本、修改建议和追溯记录；候选提供“采纳并写回”，未自动改写。
- `/material-generation`、`/ats`、`/applications`、`/interviews`、`/resume-import`、`/communications`、`/interview-assets` 均成功渲染对应页面标题。
- 375px 移动宽度下编辑器无横向溢出；浏览器控制台错误数为 0。
- API 暂停时，刷新 `/applications` 仍保留页面并显示“投递记录无法加载”，不会错误跳转到登录页；API 恢复后服务健康检查重新返回 `UP`。
- 截图已归档为 `home.png`；实际渲染的 PDF 已归档为 `resume.pdf`，文件签名为 `%PDF`。

## 当前结论

自动化测试、三服务运行基线、断网错误状态、主要浏览器路径、截图和 PDF 归档均已完成。200% 缩放使用 640px 等效视口验证无横向溢出；浏览器原生缩放快捷键在当前自动化环境不改变 DPR，此项以等效视口证据替代。
