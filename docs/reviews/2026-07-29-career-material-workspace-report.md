# 资料库三栏工作区实施报告

日期：2026-07-29

## 执行摘要

资料库已从长表单页面改造为资料优先工作区。桌面端为类型导航、资料列表、详情/编辑三栏；平板端使用右侧抽屉；手机端使用列表与全屏详情/编辑切换。旧资料列表 API 保持兼容，并新增带租户隔离、分页、检索、筛选、稳定排序、摘要和类型计数的工作区接口。本次没有新增数据库表或迁移，也没有修改认证、应用导航或其他业务模块契约。

## API 契约

新增：

```text
GET /api/career-materials/search
  ?q=
  &type=
  &usagePreference=
  &page=0
  &size=25
  &sort=updatedAt,desc
```

响应 `CareerMaterialSearchPage` 包含 `items/page/size/totalElements/totalPages/typeCounts`。列表项包含 `excerpt`，优先取来源原文，否则从结构化成果、职责或技能证据字段生成，并压缩空白、限制长度。

约束：

- `size` 范围为 1–100；越界页返回空 `items` 和正确总数。
- 排序仅接受 `updatedAt,desc`、`updatedAt,asc`、`title,asc`，均追加 `id ASC` 稳定排序。
- 标题和来源原文执行不区分大小写的包含搜索；`%`、`_`、反斜杠按字面量转义。
- 查询和类型计数均限定当前用户且排除软删除数据。
- 保留 `GET /api/career-materials?type=` 数组响应；旧消费者不需要迁移。旧摘要查询已改用轻量 DTO 投影，不加载 JSON 和来源原文大字段。

## 改动文件

后端：

- `server/src/main/java/com/intelligentresume/careermaterial/controller/CareerMaterialController.java`
- `server/src/main/java/com/intelligentresume/careermaterial/repository/CareerMaterialRepository.java`
- `server/src/main/java/com/intelligentresume/careermaterial/service/CareerMaterialService.java`
- `server/src/main/java/com/intelligentresume/careermaterial/dto/CareerMaterialSearchItem.java`
- `server/src/main/java/com/intelligentresume/careermaterial/dto/CareerMaterialSearchPage.java`
- `server/src/main/java/com/intelligentresume/careermaterial/dto/CareerMaterialTypeCount.java`
- `server/src/test/java/com/intelligentresume/careermaterial/controller/CareerMaterialControllerIT.java`
- `server/src/test/java/com/intelligentresume/careermaterial/service/CareerMaterialServiceTest.java`

前端：

- `web/src/api/careerMaterial.ts`
- `web/src/views/CareerMaterialView.vue`
- `web/src/components/career-material/CareerMaterialNavigation.vue`
- `web/src/components/career-material/CareerMaterialList.vue`
- `web/src/components/career-material/CareerMaterialDetail.vue`
- `web/src/components/career-material/CareerMaterialForm.vue`
- `web/src/components/career-material/CareerProfileEditor.vue`
- `web/src/components/career-material/options.ts`
- `web/src/i18n/index.ts`（仅资料库中英文文案）
- `web/e2e/workflow.spec.ts`（仅资料库测试及截图）

## 交互与实现

- 桌面栏宽为 `220px / minmax(400px, 1fr) / minmax(360px, 420px)`，工作区各栏独立滚动。
- 资料列表首屏包含搜索、使用偏好、排序、新建和分页；搜索防抖为 300ms。
- URL 保存 `q/type/usage/page/sort/selected`，刷新和前进后退可恢复状态。
- 只在选中资料时加载详情；同一详情的并发请求会复用进行中的 Promise，过期响应不会覆盖新选择。
- 简历列表和工作/项目关联资料按需加载并缓存；相关资料发生变更时才失效刷新。
- 保存、删除、详情、档案响应使用局部世代校验，防止慢请求覆盖用户随后打开的面板。
- 未保存修改在切换资料、进入档案、关闭面板和离开路由时确认；接受放弃档案修改会恢复服务器基线。
- 保存后保留筛选并选中新资料；删除后选中相邻资料；移动端返回恢复列表滚动位置。
- 新建、编辑、删除、关闭、返回、搜索使用 Lucide 图标，并带 tooltip、`aria-label`、焦点和 Escape 行为。

## 代码审查

执行了复用、质量、效率、正确性、测试、可维护性、安全、API 契约和前端竞态审查。已修复：

- 列表错误、详情错误和保存/删除错误状态串扰。
- 直接编辑触发重复详情请求、过期详情打开错误编辑器。
- 删除绕过未保存确认、编辑无法清空来源原文、放弃档案仍保留草稿。
- 保存、删除、档案及卸载期间的异步响应覆盖新状态。
- 旧列表加载完整大字段、辅助数据提前或无关刷新、重复排序定义和废弃仓储方法。
- 测试对固定资料总数的顺序耦合。

审查后没有遗留的 P0–P2 可执行发现。

## 验证结果

```text
cd server && mvn test
Tests run: 386, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS, 02:54 min
```

```text
cd web && npm run build
i18n guard: 36 Vue files, 2 locales
i18n guard tests: 9 passed
vue-tsc: PASS
vite build: PASS, 1744 modules, 16.91s
```

```text
cd web && npx playwright test --workers=1
68 tests: 62 passed, 6 skipped, 0 failed, 1.0m
```

6 个跳过项均带 `@local-services`，需要实际启动本地前后端、PDF 服务和真实 AI 配置；普通浏览器回归及资料库场景均执行。此前 6 worker 全量运行出现过 1 次导航超时和 1 次 Chromium 页面崩溃，失败项单 worker 重跑 2/2 通过；最终单 worker 全量结果为 0 失败。

资料库后端聚焦验证：25 项，0 失败。资料库浏览器覆盖搜索防抖/URL、分页、按需详情、详情竞态、失败重试、新建、编辑、清空原文、档案建议不自动保存、放弃修改恢复、手机滚动恢复及三种响应式布局。

`git diff --check` 无补丁空白错误；输出仅包含工作区已有的 LF/CRLF 转换提示。

## 视觉验收

以下截图由 Playwright 使用模拟认证与 API 数据生成，并已人工检查无横向溢出、遮挡或文本越界：

- `1440×900`：桌面三栏与空状态；文件名 `career-material-desktop-1440x900.png`。
- `1024×768`：类型侧栏、列表和右侧详情抽屉；文件名 `career-material-tablet-1024x768.png`。
- `390×844`：全屏新建表单与固定操作栏；文件名 `career-material-mobile-390x844.png`。

截图位于对应的 `web/test-results/workflow-*/` Playwright 输出目录，不作为产品源码提交。

## 未完成项与风险

- 未在 MySQL 5.7 实例上执行新 JPQL 搜索；H2 集成测试已覆盖租户隔离、软删除、字面量 `%` 搜索、组合筛选和分页。`_` 与反斜杠转义尚未做生产方言实测。
- 未启动真实本地服务，因此 6 个 `@local-services` 场景未执行；真实 PDF、真实百炼 API 和端到端数据持久化不在本报告中声明通过。
- `q` 按设计保存在 URL，搜索词会进入浏览器历史及可能的 URL 日志；不应在搜索框输入秘密凭证。
- 每次工作区搜索按契约返回全量类型计数，因此会额外执行一次按类型聚合；目标规模 100–500 条可接受，后续只有在指标证明必要时再拆分缓存或独立计数接口。
- Vite 仍报告主 JS chunk 超过 500 kB；这是项目级既有构建告警，本轮没有引入路由级代码拆分。

## Post-Deploy Monitoring & Validation

- 日志：关注 `/api/career-materials/search` 的 4xx/5xx、`unsupported sort`、分页参数校验和慢查询日志。
- 指标：观察该接口 P50/P95/P99、数据库查询时延、错误率和每用户请求频率。
- 健康信号：搜索 2xx 稳定、P95 无明显高于旧列表、详情请求仅在选择资料后出现。
- 失败信号：持续 5xx、跨用户计数异常、搜索或类型聚合进入慢查询榜、移动端横向溢出。
- 缓解：新页面可回退到旧列表调用和单栏渲染；数据库无迁移，无需数据回滚。建议发布后 24 小时由应用维护者观察。

## 工作区状态

未提交、未推送、未创建 PR。工作区中原有 AI 面试及本地验证改动均保留，未被本任务回退或覆盖。
