# 求职工作流竞品研究与采用记录

研究日期：2026-07-28。本文只采信产品官方页面、官方帮助中心或官方仓库；产品方的录用率、ATS 通过率和成功率营销数字不作为事实或验收指标。

## 已确认的模式

| 产品 | 官方可验证能力 | 本项目采用的模式 |
| --- | --- | --- |
| [Teal Job Tracker](https://help.tealhq.com/en/articles/9508859-getting-started-job-tracker)、[Job Matcher](https://help.tealhq.com/en/articles/12060992-using-the-job-matcher) | 职位追踪、简历与岗位匹配、简历分析被组织为连续工作流 | 始终以 JD 为上下文，连接简历版本、匹配检查与投递阶段 |
| [Huntr Job Tracker](https://huntr.co/product/job-tracker) | 看板阶段、活动时间线、岗位文档、面试里程碑和每岗位材料关联 | 将投递记录呈现为受控阶段看板；卡片显示岗位、简历版本和沟通材料数量 |
| [Kickresume Tailoring](https://www.kickresume.com/en/resume-tailoring/)、[Interview Questions](https://www.kickresume.com/en/ai-job-interview-questions-generator/) | 基于已有履历定制、针对目标岗位生成面试准备内容 | 延续现有的“证据约束 + 用户确认”原则，面试和 AI 建议必须关联真实岗位与版本 |
| [Resume.io Resume Builder](https://resume.io/resume-builder) | 简历、岗位定制、沟通文案和面试准备构成连续旅程 | 用明确的下一步把“简历 → 岗位定制 → 投递 → 面试”串联，不虚构自动化能力 |
| [Reactive Resume](https://github.com/AmruthPillai/Reactive-Resume) | 实时预览、结构化导入导出、多语言与数据控制 | 保持内容与模板分离；后续把可移植和删除保障作为信任能力，而非营销口号 |

## 本轮落地

`web/src/views/ApplicationsView.vue` 现将既有 `ApplicationRecord` 显示为六阶段管道：草稿、已投递、面试中、已获录用、未通过和已撤回。实现复用既有状态机和 PATCH 接口，不新增数据库表、服务、权限或异步任务。

- 顶部只显示可解释的实际指标：记录数、已投递数、面试中数和 Offer 数。
- 每张卡片直接显示 JD 对应的岗位/公司、实际使用的简历版本、已保存沟通材料数量和更新时间。
- 状态下拉只呈现服务端允许的转移；不以拖拽绕过业务规则。
- 反馈和沟通文案按需展开，创建/编辑表单默认收起，移动端保留可横向浏览的稳定阶段列。

## 明确不做

- 不做浏览器扩展、跨站 ATS 自动填表、自动投递或职位抓取。
- 不把匹配分数、ATS 检查或 AI 建议表述为录用概率或结果保证。
- 不新增通用 Company/Contact CRM，不采集招聘联系人等额外个人信息。
- 不拆微服务、不引入 CQRS/事件总线，也不改写现有 AI worker。

## 后续与审批边界

下一阶段可在模块化单体内增量实现投递活动时间线、真实的阶段停留时间和岗位聚合详情。这会涉及 `application_record` 的历史表或活动表、聚合查询 API 和迁移，属于中等数据模型扩展，但不需要改变服务边界。

以下项目在开始前必须单独获得批准：浏览器扩展和自动提交、第三方职位抓取/长期存储、通用公司与联系人 CRM 重构、外部薪资或职业路径数据接入、事件总线/CQRS/微服务拆分、AI worker 执行框架改写或认证权限体系重构。

## 验证

- `npm run build`：通过 i18n 守卫、TypeScript 与生产构建。
- `npm run test:e2e`：40 通过、5 个本地真实服务专项测试跳过。
- 投递页 E2E 覆盖岗位/公司上下文、六阶段列、合法状态迁移、搜索过滤与小屏横向浏览。
