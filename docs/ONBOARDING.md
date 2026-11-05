# 智历 · 上手指南

> 面向**第一次接手这个仓库的人**（也包括被拉进来的 AI）。
> 目标：30 分钟内能跑起来、看懂一条完整链路、并且知道**哪些改动会炸**。
>
> 这份文档只做「串讲与索引」，不复制细节。产品现状以 [`PROJECT_CONTEXT.md`](../PROJECT_CONTEXT.md) 为准，
> 冲突时以它和代码为准，不以本文为准。

---

## 0. 怎么用这份文档

| 你有多少时间 | 建议路线 |
| --- | --- |
| 5 分钟 | 读 §1、§2，知道这是个什么东西 |
| 30 分钟 | 加读 §3 代码地图 + §5 跑起来（只跑后端也行） |
| 要动手改代码 | **必读 §6 红线 + §7 的坑**，再回 §8 找对应 SOP |
| 要上线/部署 | 不要看本文，直接读 [`DEPLOYMENT_DIRECT.md`](./DEPLOYMENT_DIRECT.md) |

---

## 1. 一句话理解智历

**它不是一个"按 JD 改写简历"的工具，而是一个"先攒可信素材、再按岗位取用"的系统。**

简历里的每一句话都要能指回用户确认过的资料条目；AI 只负责**选材**和**组织表达**，不负责发明经历。

三条不可违背的产品原则（违反其中任何一条，功能做得再漂亮也是错的）：

1. **不编造**：AI 不得补充事实、数字、链接、机构或经历。
2. **人工确认**：AI 产出是草稿/候选，用户点了确认才写进简历版本。
3. **隐私边界**：联系方式由**服务端合并**，不发送给模型；密钥/Token/用户资料不进日志与 Git。

这三条在代码里有对应实现（不是口号）：`AiConsentService`（授权校验）、确认接口的 `40904`（ADR-013）、
`CareerMaterialAiSnapshotSanitizer`（快照脱敏）。

---

## 2. 核心链路

```text
个人档案 + 职业资料库
        │
        ▼
选择/粘贴 JD ──► 规则预筛 ──► AI 选材任务（异步）
                                    │
                                    ▼
                        用户确认选材快照（人工闸门①）
                                    │
                                    ▼
                        AI 生成结构化简历草稿（异步）
                                    │
                                    ▼
                        用户逐项审核确认（人工闸门②）
                                    │
                                    ▼
              独立岗位简历 ──► 版本 / 预览 / PDF / 投递 / 面试资产
```

**两个人工闸门是产品核心**，不是可省略的步骤。任何"一键生成直接落库"的改动都违背设计。

异步任务的统一形态：

```text
POST /api/ai/...  ──►  202 + { taskId }
                          │
        前端轮询 GET /api/ai/tasks/{taskId}
                          │
                  SUCCESS / FAILED / PENDING
```

⚠️ **不要把 202 响应当成结果**——这是本项目最容易写错的前端逻辑（`PROJECT_CONTEXT.md:113`）。

---

## 3. 代码地图

```text
server/            Spring Boot 3.3.2 / Java 17（分层：controller / service / repository / domain）
  └─ src/main/resources/db/migration/   Flyway 迁移，当前最高 V40（新迁移从 V41 起）
web/               Vue 3.5 + TS + Vite 5（自研组件，无第三方 UI 库）
pdf-service/       Node 20 + Puppeteer，私有 PDF 渲染
desktop-shell/     Electron 壳（骨架，安装包已有，图形会话未验）
scripts/           部署、探针、本地验证、持久化门禁
functional-tests/  黑盒回归（4 套件）
deploy/            nginx 站点配置 + systemd 单元（随部署幂等同步）
docs/              当前手册 + 项目档案（索引见 docs/README.md）
```

### 后端模块职责（先看这张表再找文件）

| 模块 | 职责 | 什么时候会进这个包 |
| --- | --- | --- |
| `auth` | 注册登录、JWT/刷新会话、改邮箱/密码 | 登录态、权限、限流 |
| `personalprofile` | 用户唯一个人档案 | 联系方式、职业目标 |
| `careermaterial` | 职业资料 CRUD + 偏好 | 素材增删改、"排除/优先" |
| `jobdescription` | JD CRUD 与解析 | 岗位描述 |
| `ai` | 授权、配额、任务、Worker、百炼调用、选材与生成 | 一切 AI 能力 |
| `resume` | 简历、版本、草稿确认、归档恢复 | 编辑与版本 |
| `scoring` / `ats` | JD 覆盖度、ATS 分析 | 体检 |
| `export` | PDF 任务、私有下载、重试 | 导出 |
| `application` | 投递 CRUD、乐观锁、状态机 | 投递看板 |
| `communication` | 沟通草稿（**绝不自动发送**） | 求职信/邮件 |
| `interview` | 有限轮次面试、反馈、答案资产 | 模拟面试 |
| `imports` | TXT/PDF/DOCX 抽取（**原文件不落盘**） | 导入简历 |
| `desktop` | 桌面导入/导出预览与执行、本地 AI 设置 | 桌面模式 |
| `retention` | 分档留存清扫 | 数据生命周期 |
| `common` | API 响应、异常、Trace ID、可观测性 | — |

### 前端三条约定

- 页面在 `web/src/views/`，请求契约在 `web/src/api/`，文案在 `web/src/i18n/`；
- **页面不得新增硬编码用户文案**（中英双语门禁会拦，`npm run check:i18n`）；
- 简历编辑器由 `ResumeEditorView.vue` 编排，章节/卡片/预览拆在 `web/src/components/resume/`。

---

## 4. 数据与任务的骨架

```text
user
 ├─ personal_profile          （一人一份）
 ├─ career_material           （可复用职业事实）
 ├─ job_description
 ├─ resume ── resume_version  （current_version_id 指向当前编辑起点）
 └─ ai_task
      ├─ JOB_MATERIAL_SELECTION ──► 用户确认的资料快照
      └─ JOB_GENERATION（parent_task_id 指向选材任务）──► 草稿/版本
```

两条容易误解的规则：

- `resume_material_reference` 只记录**最终草稿真正引用**的资料，不是"所有候选"；
- 版本只做**归档与恢复**，不提供永久删除；"恢复为新版本"是复制成新版本，原版本不动。

---

## 5. 跑起来

### 5.1 本地三进程（推荐，不依赖 Docker）

前置：Java 17、Maven 3.9+、Node.js 20+、本地 MySQL 8。

```bash
# 根目录 .env 填 SPRING_DATASOURCE_*；.env.live-ai 填 BAILIAN_API_KEY（不填则 AI 降级，规则功能正常）
cd server      && mvn spring-boot:run -Dspring-boot.run.profiles=local-mysql   # → :8080
cd web         && npm install && npm run dev                                   # → :5173
cd pdf-service && npm install && npm start                                     # → :3001
```

- Flyway 在 API 启动时**自动**跑迁移；
- `PDF_SERVICE_TOKEN` 必须在根 `.env` 与 `pdf-service/.env` **两侧一致**；
- 一键脚本：`.\scripts\Start-LocalValidation.ps1`（详见 [`LOCAL_VALIDATION.md`](./LOCAL_VALIDATION.md)）。

### 5.2 桌面模式（单进程、免登录）

```bash
java -jar intelligent-resume-server-0.5.0.jar \
  --spring.profiles.active=desktop \
  --server.port=8082 --server.address=127.0.0.1
```

`--server.address` **必须是回环地址，否则服务拒绝启动**——这是防止本地库被局域网访问的护栏，不要为了"方便手机调试"改掉。

### 5.3 数据库版本注意事项

MySQL **5.7 不是受支持的全新安装目标**：`V18` 用了 MySQL 8 的窗口函数。
已有 5.7 存量库的升级口径走 `MySql57MigrationLiveIT`（手动门，需要 Docker + 5.7 基线快照）。

---

## 6. 改代码前的红线

每一条都对应一次真实事故，不是风格偏好。

| # | 红线 | 违反了会怎样 |
| --- | --- | --- |
| 1 | **先确认当前流程和状态，再改**。不要按历史 MVP 文档猜现状 | 改一个"已经不存在"的流程 |
| 2 | **用户隔离**：资料/JD/版本/任务/导出都必须归属当前用户 | 跨用户越权（有回归用例专门打这个） |
| 3 | **不要恢复"选一份已有简历作为 JD 生成目标"的旧入口** | 产品定位被推翻，资料库变成摆设 |
| 4 | **AI 输出不得直接写入简历**，必须人工确认 | 违反产品原则 1、2，且溯源链路断裂 |
| 5 | **异步任务必须处理轮询、失败、重试、授权撤销**，不能只看 202 | 前端永远转圈 / 静默失败 |
| 6 | **`.env` 里的键必须在 `application.yml` 有 `${NAME}` 占位符** | 配置被**静默忽略**（历史上让 API 暴露到公网） |
| 7 | **只改与任务相关的文件，绝不回退未知改动** | 抹掉别人未提交的工作 |
| 8 | **密钥/密码/JWT/Cookie/备份/真实简历不得提交或展示** | 安全事故，且 Git 历史洗不掉 |

第 6 条值得单独强调：本项目的配置读取模式是「`application.yml` 声明占位符 + `.env`/环境变量提供值」。
只写进 `.env` 而没有占位符消费的项**不会有任何提示**。改完配置要用真实请求或 `ss -lntp` 核对，
**不要**只看配置文件或 `systemctl is-active`。

---

## 7. 五个最容易踩的坑

### 7.1 CI 双绿 ≠ 真实环境生效

曾经发生过：`deploy/nginx/host.conf` 在仓库里改好了、CI 全绿，但部署链路根本不包含 `deploy/`，
线上从未采用——上传 5MB 文件被 nginx 用 HTML 错误页拦掉，用户拿不到统一信封。

**规则**：改 `deploy/` 或运维脚本，必须真跑一次 `bash scripts/deploy-direct.sh` 并让探针通过
（探针失败脚本以非零码退出，不会给"部署成功"的假结论）。

### 7.2 门禁"永远返绿"比没有门禁更危险

新增门禁必须先用**已知坏样本**证明它会红（注入 → 红 → 还原后核对 md5）。
已经真实发生过两次：`verify(never())` 写错导致断言恒真；`@EnabledIfEnvironmentVariable` 条件未生效，
`mvn` 返回 0 而 surefire 实际 skipped。

**规则**：判定红/绿不能只看退出码，要核对 surefire 报告里**断言真的执行了**。

### 7.3 子串命中是最常见的假绿

在文档里找路径/键名必须加边界，否则父路径会被子路径满足（`docs/04` 命中 `docs/04-xxx.md` 的任何一行）。

### 7.4 限流分桶键不能是 URL

`RateLimitRegistry` 刻意按**规则组**分桶。若按 URL 分桶，换个 `id` 就绕过限额。
同理，`write-default` 兜底存在的意义是让"结构上不存在无上限的写端点"。

### 7.5 本机环境坑（不是代码问题）

| 现象 | 原因 | 处置 |
| --- | --- | --- |
| 本地 API 端口不是 8080 | 工具链注入了 `SERVER__PORT`，Spring 宽松绑定把它解析成 `server.port`，盖过 yml | 启动前清掉该变量，或命令行显式 `--server.port=8080` |
| `mvn ... \| head` 之后有 JVM 僵尸 / 只看到部分输出 | 管道提前关闭（EPIPE）；`tail` 会把"部分"当成"全量" | `mvn ... > /tmp/mvn.log 2>&1` 再读文件 |
| Python 脚本访问本机地址超时 | Python 会读系统代理变量 | `env -u http_proxy -u https_proxy ...` |

> 通用规律：任何 `XXX__YYY` 形式的环境变量都能静默覆盖 `x.y` 配置。

---

## 8. 常见改动怎么做

### 8.1 新增一个 API 端点

1. Controller 用统一响应信封（`ApiResponse`），异常抛 `BusinessException` 带错误码；
2. 写进 `docs/05-接口设计说明书.md`（**没写会被门禁拦**）；
3. 若是写操作，确认命中哪条限流规则；没命中会落到 `write-default`（这是兜底，不是免限流）；
4. 若是 AI 相关端点：返回 `202 + taskId`，并确认是否需要 `X-AI-Preview-Token`。

### 8.2 新增一张表 / 改表结构

1. 迁移文件放 `server/src/main/resources/db/migration/`，**下一个版本号是 `V41`**；
2. 同步 `docs/04-数据库设计说明书.md`（`SchemaDocContractTest` 会拦）；
3. 同时考虑桌面端 H2 是否兼容（桌面 profile 用 H2 文件库）；
4. 迁移计数在**三处是契约**：`docs/CI.md`、`docs/DEPLOYMENT_DIRECT.md`、`README.md` 的 `V1–Vn`。

### 8.3 改 AI 提示词或模型调用

1. 提示词模板在 `ai/provider/PromptTemplates.java` 与各 `*PromptBuilder`；
2. 资料文本有字节预算（`app.ai.prompt.*`），超限按 UTF-8 边界截断并标 `[truncated]`，**不丢弃资料**；
3. 生成类任务默认关闭模型推理（`disable-thinking-task-types`，实测提速约 40 倍）；
   但不是所有模型都接受该参数，返回 400 的会被跳过——不要为此关掉整条链；
4. **不要把用户资料、联系方式、Prompt 原文写进日志或指标标签**。

### 8.4 改前端

1. 文案进 `web/src/i18n/`，不要硬编码；
2. 视图必须处理**加载中 / 空 / 错误**三态（`check:view-states` 会拦，29 个视图全扫）；
3. API 响应要 `unwrap` 统一信封（`check:api-unwrap` 会拦）；
4. 打包体积有棘轮（`check:bundle-budget`），只能降不能涨。

---

## 9. 门禁与验证

```bash
# 后端
cd server && mvn test                     # 注意：不要接管道，重定向到文件再读
# 前端（build 串了 5 道检查：i18n / 草稿字段 / 响应解包 / 视图三态 / 体积预算）
cd web && npm run build && npm run test:unit
# PDF 服务
cd pdf-service && npm run check && npm test
# 部署后（36 项，失败即非零退出）
bash scripts/probe-deployment.sh
```

值得知道的几个门禁（它们守护的东西比"测试数"更重要）：

| 门禁 | 拦的是什么 |
| --- | --- |
| 版本号一致性 | 同一版本号 **13 处**副本（含 jar 名与桌面壳），任一处不一致即红 |
| 单文件行数棘轮 | 生产 ≤300 行、测试 ≤400 行；超限必须登记并锁定上限，只许下调 |
| API 文档覆盖 | 新增端点未写入接口文档即红 |
| Schema 文档契约 | 新增表未写入数据库文档即红 |
| 持久化双口径 | MySQL 8.4 全新安装与 5.7 存量升级分别真跑迁移 |
| 状态矩阵三方一致 | 后端枚举 / 前端数组 / 文档表格逐格比对 |

---

## 10. 术语表

| 术语 | 含义 |
| --- | --- |
| **资料 / career_material** | 可复用的职业事实条目，简历的素材来源 |
| **选材（selection）** | AI 从资料里挑出与 JD 相关的条目，用户确认后才可用于生成 |
| **草稿（draft）** | AI 生成的结构化简历内容，**尚未落库为版本** |
| **溯源（provenance）** | 简历字段指回资料条目的引用关系（`resume_material_reference`） |
| **预览令牌（preview token）** | 发送前把"将要发给模型的确切内容"给用户看的凭证，`R32` |
| **降级（degrade）** | AI 不可用时回落规则结果，并**明确告知原因**，不假装成功 |
| **分档清扫** | A 档无引用者物理删除；B 档被引用者转最小快照 |

---

## 11. 卡住了去哪读

按优先级：

1. [`PROJECT_CONTEXT.md`](../PROJECT_CONTEXT.md) —— 产品边界与模块职责，**永远第一个看**；
2. [`docs/README.md`](./README.md) —— 文档总索引；
3. [`decisions/`](./decisions/) —— 为什么是这样设计的（ADR 不可改写，纠正靠追加）；
4. [`DEPLOYMENT_DIRECT.md`](./DEPLOYMENT_DIRECT.md) —— 部署与线上环境；
5. [`reviews/`](./reviews/) —— 历史上出过什么问题、怎么定位的。

> 引用任何"现存问题清单"之前先核实当前状态：**缺口清单会随修复而失效**，
> 直接引用会让人去修一个已经不存在的问题（详见 `docs/README.md` 维护原则第 9 条）。
