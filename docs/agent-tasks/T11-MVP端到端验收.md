# T11 — MVP 端到端验收

> 对应 `docs/13-MVP实施契约与任务卡.md` §5 T11。
>
> 必读:`docs/agent-tasks/T00-通用执行前置条件.md`、`T01..T10`(全部)。

---

## 1. 任务卡摘要

```text
任务名称:MVP 端到端验收
目标:证明真实用户可完成资料库到私有 PDF 的完整闭环。
当前阶段与优先级:第二阶段 / 验收
来源文档:02 §9.1、07 §9、09 §6/§7
允许范围:端到端测试脚本;集成测试补强;验收报告。
禁止:新增模块;修改既有契约;跳过任一必测场景。
完成定义:02 §9.1、07 §9、09 §6/§7 全部有测试或人工证据,无未解决 P0/P1。
```

---

## 2. 本卡依赖

### 2.1 前置 Tnn

- [ ] T01..T10 全部完成并通过各自测试

### 2.2 外部依赖

- [ ] MySQL 已启动
- [ ] 后端运行在 `:8080`
- [ ] PDF 服务运行在 `:3001`
- [ ] 前端可启动(开发环境)

---

## 3. 目标文件清单

```text
docs/agent-tasks/T11-MVP端到端验收.md                                                              (本文,需补全验证脚本)
server/src/test/java/com/intelligentresume/e2e/MvpHappyPathIT.java                              新增(端到端 happy path)
server/src/test/java/com/intelligentresume/e2e/MvpFailurePathIT.java                            新增(端到端失败路径)
server/src/test/resources/e2e/payloads/jd-java-backend.json                                     新增(测试 JD 文本)
server/src/test/resources/e2e/payloads/resume-basics.json                                       新增(测试简历 JSON)

web/tests/e2e/mvp.spec.ts                                                                        新增(可选 Playwright 测试)
```

> 禁止触碰:`T01..T10` 中已实现的代码逻辑;**只允许**新增测试与配置文件。
>
> 若发现 T01..T10 存在 P0/P1 缺陷,**回退到对应任务卡**修复,不得在本卡直接修补业务代码。

---

## 4. 验证矩阵总览

| 验收条目 | 来源 | 类型 |
| --- | --- | --- |
| 1. 注册/登录/刷新/退出 | 02 §9.1.1 | 自动化 + 手工 |
| 2. 资源归属与限流 | 02 §9.1.1 + 09 §7 阶段二 | 自动化 |
| 3. 职业资料/简历/版本/JD 维护 | 02 §9.1.2 | 自动化 |
| 4. JD 触发岗位定制生成 | 02 §9.1.3 | 自动化 |
| 5. 来源可追溯 + 待确认标记 | 02 §9.1.3 | 自动化 |
| 6. 逐项确认创建唯一版本 | 02 §9.1.3 | 自动化 |
| 7. 规则覆盖度可解释 | 02 §9.1.4 | 自动化 |
| 8. classic PDF 私有下载 | 02 §9.1.5 | 自动化 + 手工 |
| 9. 端到端 happy path | 13 §5 T11 | 自动化 |
| 10. 失败路径(13 §5 T11 列) | 13 §5 T11 | 自动化 |

---

## 5. Happy Path 验证脚本(curl + 浏览器)

### 5.1 Happy path 完整步骤

```bash
# === Step 0: 准备 ===
export API=http://localhost:8080
export WEB=http://localhost:5173
export PDF=http://localhost:3001

# === Step 1: 注册 + 登录 ===
# 注册
curl -s -X POST $API/api/auth/register \
    -H "Content-Type: application/json" \
    -d '{"username":"e2e_alice","email":"e2e_alice@example.com","password":"CorrectHorse9!"}' | jq

# 登录拿 token
TOKEN=$(curl -s -X POST $API/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"e2e_alice","password":"CorrectHorse9!"}' \
    -c /tmp/e2e_cookies.txt | jq -r '.data.accessToken')
AUTH="Authorization: Bearer $TOKEN"

# === Step 2: 维护职业资料(创建 3 条) ===
# 工作经历
WORK_ID=$(curl -s -X POST $API/api/career-materials \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d '{
      "materialType":"WORK_EXPERIENCE",
      "title":"ACME 后端工程师 2022-至今",
      "contentJson":{
        "company":"ACME","position":"后端工程师","startDate":"2022-03",
        "highlights":["负责订单服务开发","MySQL/Redis 优化"]
      },
      "sourceText":"负责订单服务开发与性能优化",
      "usagePreference":"NORMAL"
    }' | jq -r '.data.id')

# 项目经历
PROJECT_ID=$(curl -s -X POST $API/api/career-materials \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d '{
      "materialType":"PROJECT_EXPERIENCE",
      "title":"订单系统重构",
      "contentJson":{
        "name":"订单系统重构","tech":["Spring Boot","MySQL","Redis"],
        "description":"独立设计并实现订单服务",
        "highlights":["QPS 提升 3 倍","故障恢复 < 1 分钟"]
      },
      "sourceText":"独立设计并实现订单服务,QPS 提升 3 倍",
      "usagePreference":"PREFERRED"
    }' | jq -r '.data.id')

# 技能
SKILL_ID=$(curl -s -X POST $API/api/career-materials \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d '{
      "materialType":"SKILL",
      "title":"Java 生态",
      "contentJson":{"name":"Java","level":"熟练","keywords":["Spring Boot","MySQL","Redis","微服务"]},
      "usagePreference":"NORMAL"
    }' | jq -r '.data.id')

# === Step 3: 创建 JD ===
JD_ID=$(curl -s -X POST $API/api/jobs \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d '{
      "title":"Java 后端工程师",
      "companyName":"某科技公司",
      "jdText":"负责 Spring Boot 微服务开发,熟悉 MySQL/Redis,3 年以上经验,本科及以上学历。"
    }' | jq -r '.data.id')

# 解析 JD
curl -s -X POST $API/api/jobs/$JD_ID/parse -H "$AUTH" | jq
# 期望: keywords 包含 spring boot / mysql / redis / 微服务, requirements 包含 3年以上经验 与 本科及以上

# === Step 4: 创建简历 ===
RESUME_ID=$(curl -s -X POST $API/api/resumes \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d '{
      "title":"我的简历",
      "resumeJson":{"basics":{"name":"e2e_alice"},"work":[],"education":[],"skills":[]}
    }' | jq -r '.data.id')

# 保存手动版本
VERSION_ID=$(curl -s -X POST $API/api/resumes/$RESUME_ID/versions \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d '{
      "resumeJson":{"basics":{"name":"e2e_alice"},"work":[],"education":[],"skills":[]},
      "sourceType":"MANUAL",
      "optimizationSummary":"初始版本"
    }' | jq -r '.data.id')

# === Step 5: AI 同意 ===
curl -s -X POST $API/api/ai/consent \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d '{
      "policyVersion":"v1.0.0",
      "providerCode":"mock",
      "taskScopes":["JOB_GENERATION"],
      "dataCategories":["resume_text","jd_text","career_material_text"],
      "noticeHash":"e2e-notice-v1"
    }' | jq

# === Step 6: 发起岗位定制生成 ===
TASK_ID=$(curl -s -X POST $API/api/ai/generate-resume-for-job \
    -H "$AUTH" -H "Content-Type: application/json" \
    -H "Idempotency-Key: e2e-gen-1" \
    -d "{
      \"targetResumeId\":$RESUME_ID,
      \"jobDescriptionId\":$JD_ID,
      \"includedMaterialIds\":[$WORK_ID],
      \"preferredMaterialIds\":[$PROJECT_ID],
      \"excludedMaterialIds\":[]
    }" | jq -r '.data.taskId')

# 轮询(1s/2s/4s/5s 封顶)
for i in 1 2 4 5 5 5; do
    sleep $i
    STATUS=$(curl -s $API/api/ai/tasks/$TASK_ID -H "$AUTH" | jq -r '.data.status')
    echo "[t=${i}s] status=$STATUS"
    [ "$STATUS" = "SUCCESS" ] && break
    [ "$STATUS" = "FAILED" ] && { echo "task failed"; exit 1; }
done

# 校验: result_json.draftResumeJson 含 _source 与 _pending
curl -s $API/api/ai/tasks/$TASK_ID -H "$AUTH" | jq '.data.resultJson | {draftResumeJson, selected, unselected, missing, warnings}'

# === Step 7: 页面重载恢复(taskId 持久化到 localStorage) ===
# 这一步在浏览器手工验证;检查 localStorage 中存在 e2e-gen-1 对应的 taskId

# === Step 8: 逐项确认(根据草稿动态构造 items) ===
# 提取所有 outputPath
ITEMS=$(curl -s $API/api/ai/tasks/$TASK_ID -H "$AUTH" | jq -c '[.data.resultJson.selected[].outputPath] | map({outputPath: ., decision: "ACCEPT"})')

curl -s -X POST $API/api/ai/tasks/$TASK_ID/confirm \
    -H "$AUTH" -H "Content-Type: application/json" \
    -H "Idempotency-Key: e2e-confirm-1" \
    -d "{
      \"taskUpdatedAt\":\"$(curl -s $API/api/ai/tasks/$TASK_ID -H "$AUTH" | jq -r '.data.updatedAt')\",
      \"items\":$ITEMS
    }" | jq
# 期望: data.versionNo == 2(初始手动版本是 1)

# 校验: 该简历版本 history 包含 versionNo=2, source_type=JD_CUSTOMIZED
curl -s $API/api/resumes/$RESUME_ID/versions -H "$AUTH" | jq

# === Step 9: 查看规则覆盖度 ===
MATCH=$(curl -s -X POST $API/api/scoring/match \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"resumeVersionId\":$VERSION_ID,\"jobDescriptionId\":$JD_ID}")
echo "$MATCH" | jq
# 期望: totalScore 数字, disclaimer 等于配置的文案

# === Step 10: 导出 PDF ===
EXPORT_TASK=$(curl -s -X POST $API/api/exports/pdf \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"resumeVersionId\":$VERSION_ID,\"templateCode\":\"classic\"}" | jq -r '.data.taskId')

# 轮询导出
for i in 1 2 3 4 5; do
    sleep $i
    ESTATUS=$(curl -s $API/api/exports/tasks/$EXPORT_TASK -H "$AUTH" | jq -r '.data.status')
    echo "[t=${i}s] export=$ESTATUS"
    [ "$ESTATUS" = "SUCCESS" ] && break
done

# 下载 PDF
curl -s $API/api/exports/files/$EXPORT_TASK -H "$AUTH" -o /tmp/e2e_resume.pdf
file /tmp/e2e_resume.pdf
# 期望: PDF document

# === Step 11: 退出 ===
curl -s -X POST $API/api/auth/logout -H "$AUTH" -b /tmp/e2e_cookies.txt | jq
# 期望: code=0
```

### 5.2 Happy path 通过判据

- [ ] 每一步响应 `code=0`
- [ ] JD 解析返回的 keywords 包含至少 2 个期望词(spring boot / mysql / redis / 微服务)
- [ ] 任务最终 `status=SUCCESS`,`resultJson` 包含 `draftResumeJson`、`selected`、`unselected`、`missing`、`warnings`
- [ ] 确认后 `versionNo` 递增,`source_type=JD_CUSTOMIZED`
- [ ] 评分 `totalScore` ∈ [0, 100],`disclaimer` 文案与配置一致
- [ ] 导出任务最终 `status=SUCCESS`,下载文件 `file` 命令识别为 PDF

---

## 6. 失败路径必测场景(13 §5 T11)

### 6.1 未授权

```bash
# 不带 token 调用任意受保护接口
curl -s -i $API/api/resumes
# 期望: 40101
```

### 6.2 跨用户

```bash
# 注册 bob
curl -s -X POST $API/api/auth/register \
    -H "Content-Type: application/json" \
    -d '{"username":"e2e_bob","email":"bob@example.com","password":"CorrectHorse9!"}' >/dev/null

BOB_TOKEN=$(curl -s -X POST $API/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"e2e_bob","password":"CorrectHorse9!"}' | jq -r '.data.accessToken')

# bob 访问 alice 的简历 → 40401
curl -s -i $API/api/resumes/$RESUME_ID -H "Authorization: Bearer $BOB_TOKEN"
# 期望: 40401

# bob 跨用户创建生成任务 → 40401
curl -s -i -X POST $API/api/ai/generate-resume-for-job \
    -H "Authorization: Bearer $BOB_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"targetResumeId\":$RESUME_ID,\"jobDescriptionId\":$JD_ID}"
# 期望: 40401
```

### 6.3 AI 未同意

```bash
# bob 未同意就创建任务
curl -s -i -X POST $API/api/ai/generate-resume-for-job \
    -H "Authorization: Bearer $BOB_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"targetResumeId":999,"jobDescriptionId":999}'
# 期望: 40302
```

### 6.4 重复提交(幂等键)

```bash
IDEM="e2e-gen-2-$(date +%s)"
# 第一次
curl -s -X POST $API/api/ai/generate-resume-for-job \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IDEM" \
    -d "{...}" | jq -r '.data.taskId' > /tmp/task1.txt

# 第二次(同请求体)
curl -s -X POST $API/api/ai/generate-resume-for-job \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IDEM" \
    -d "{...}" | jq -r '.data.taskId' > /tmp/task2.txt

# 期望: 两次 taskId 相同
diff /tmp/task1.txt /tmp/task2.txt && echo "IDEMPOTENT OK"
```

### 6.5 提供商失败(Mock 模拟)

```bash
# 在 application.yml 临时配置 mock.fail-rate=1.0
# 重启后端,发起任务,期望任务最终 FAILED,error_message 含可识别错误
```

### 6.6 工作器重启恢复

```bash
# 创建一个任务,在 worker 抢占前 kill 后端
TASK_ID=$(curl -s -X POST $API/api/ai/generate-resume-for-job ... | jq -r .data.taskId)

# 后端 kill
# 把 lease 过期时间手动改到过去(开发环境 SQL 干预)
docker exec intelligent-resume-mysql mysql -uroot -proot_dev_only intelligent_resume -e \
  "UPDATE ai_task SET lease_expires_at = NOW() - INTERVAL 1 MINUTE WHERE id=$TASK_ID"

# 重启后端
# 期望: 任务最终 SUCCESS 或被 worker 重新抢占
```

### 6.7 资料不足

```bash
# 创建一个空资料库的账号
# 注册新用户,只创建 1 条 SKILL,发起生成任务
# 期望: resultJson.missing 非空,草稿仅含 _pending 字段
```

### 6.8 PDF 服务失败

```bash
# 关闭 pdf-service (kill 进程)
# 发起导出任务
# 期望: export_task.status=FAILED,error_message 含 "PDF 渲染失败"
```

### 6.9 文件过期

```bash
# 把 export_task.expires_at 改到过去
docker exec intelligent-resume-mysql mysql -uroot -proot_dev_only intelligent_resume -e \
  "UPDATE export_task SET expires_at = NOW() - INTERVAL 1 HOUR WHERE id=$EXPORT_TASK"

# 下载
curl -s -i $API/api/exports/files/$EXPORT_TASK -H "$AUTH"
# 期望: 40401(过期等同不存在)
```

---

## 7. 测试代码骨架

### 7.1 MvpHappyPathIT

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MvpHappyPathIT {

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    // 或用 TestRestTemplate

    @Test
    @DisplayName("Happy Path: 注册 → 资料 → JD → AI → 评分 → PDF")
    void happyPath_fullCycle() {
        // 1. 注册 + 登录
        // 2. 创建 2 条 career_material
        // 3. 创建 1 条 job_description,parse
        // 4. 创建 resume + 1 个手动版本
        // 5. AI 同意
        // 6. 创建 generate 任务 → 轮询 → SUCCESS
        // 7. confirm → versionNo=2
        // 8. 评分 → totalScore 数字,disclaimer 文案一致
        // 9. 导出 → 轮询 → 下载 PDF 字节
    }
}
```

### 7.2 MvpFailurePathIT

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MvpFailurePathIT {

    @Test
    @DisplayName("未授权访问受保护接口返回 40101")
    void unauthenticated_returns40101() {}

    @Test
    @DisplayName("跨用户访问返回 40401")
    void crossUser_returns40401() {}

    @Test
    @DisplayName("未 AI 同意创建任务返回 40302")
    void notConsented_returns40302() {}

    @Test
    @DisplayName("幂等键重放返回原 taskId")
    void idempotentReplay_returnsSameTask() {}

    @Test
    @DisplayName("撤回 AI 同意后创建任务返回 40302")
    void withdrawThenCreate_returns40302() {}

    @Test
    @DisplayName("租约过期任务可被工作器恢复")
    void expiredLease_recoverable() {}

    @Test
    @DisplayName("空资料库生成返回 missing")
    void emptyMaterials_returnsMissing() {}

    @Test
    @DisplayName("PDF 服务不可用时导出任务 FAILED")
    void pdfServiceDown_exportFailed() {}

    @Test
    @DisplayName("PDF 文件过期后下载返回 40401")
    void pdfExpired_downloadNotFound() {}
}
```

---

## 8. 手工验收清单(07 §10)

> 提交人需逐项核对,产出截图与 curl 输出。

- [ ] 是否能完成注册登录
- [ ] 是否能创建简历
- [ ] 是否能编辑和保存版本
- [ ] 是否能维护职业资料,并按 JD 生成岗位定制简历
- [ ] 是否能输入 JD 并获得优化结果(此处指 confirm 后生成版本)
- [ ] 是否能查看评分、ATS 风险与建议(本卡只验证评分;ATS 在 M3)
- [ ] 是否能生成沟通文案、记录投递进度并复习面试答案资产(本卡不验证;M3/M4)
- [ ] 是否能成功导出 PDF

> 截图与 curl 输出归档到 `docs/agent-tasks/evidence/T11-<日期>/` 目录(由实施时创建)。

---

## 9. 阶段门禁自检(09 §6)

- [ ] 当前阶段(M2)主流程闭环:是(happy path 跑通)
- [ ] 存在 P0 缺陷:否
- [ ] 当前阶段涉及的认证、资源归属、AI 同意、限流、私有文件控制全部通过:是
- [ ] 数据模型、接口、实现、测试文档一致:是(已交叉验证)
- [ ] 当前阶段验收清单存在未验证条目:否

---

## 10. 验收报告模板

实施完成后输出到 `docs/agent-tasks/evidence/T11-<日期>/report.md`:

```markdown
# T11 MVP 端到端验收报告

日期: <YYYY-MM-DD>
执行人: <name>
后端版本: <commit hash>
PDF 服务版本: <commit hash>
前端版本: <commit hash>

## 1. 环境

- OS: <Windows 11>
- Java: 17
- Node: 20
- MySQL: 8.4
- docker compose up -d mysql:成功

## 2. Happy Path

| 步骤 | 期望 | 实际 | 通过 |
| --- | --- | --- | --- |
| 注册 | 200 | 200 | ✓ |
| 登录 | 200 + token | ... | ✓ |
| ... | ... | ... | ... |

## 3. 失败路径

| 场景 | 期望错误码 | 实际 | 通过 |
| --- | --- | --- | --- |
| 未授权 | 40101 | 40101 | ✓ |
| 跨用户 | 40401 | 40401 | ✓ |
| ... | ... | ... | ... |

## 4. 性能

| 接口 | 期望 p95 | 实际 | 通过 |
| --- | --- | --- | --- |
| 登录 | < 300ms | ... | ... |
| 简历 CRUD | < 300ms | ... | ... |
| 评分 | < 2s | ... | ... |
| AI 任务(PENDING→SUCCESS) | < 30s | ... | ... |
| PDF 导出(PENDING→SUCCESS) | < 15s | ... | ... |

## 5. 缺陷清单

| 编号 | 等级 | 模块 | 描述 | 状态 |
| --- | --- | --- | --- | --- |
| ... | P2 | ... | ... | 已修 |

## 6. 结论

- P0/P1 缺陷:0
- 主流程闭环:是
- 可演示:是

验收通过 / 不通过
```

---

## 11. 停止条件

补充 `T00 §6`:

- [ ] 跳过任一必测场景
- [ ] 在本卡直接修补业务代码(应回退到对应 Tnn)
- [ ] 验收报告存在空字段
- [ ] P0/P1 缺陷未解决就报告 DONE

---

## 12. 完成报告

```text
任务 ID:T11-MVP 端到端验收
状态:DONE / BLOCKED
修改文件:
  docs/agent-tasks/T11-MVP端到端验收.md
  server/src/test/java/com/intelligentresume/e2e/MvpHappyPathIT.java
  server/src/test/java/com/intelligentresume/e2e/MvpFailurePathIT.java
  server/src/test/resources/e2e/payloads/*.json
  docs/agent-tasks/evidence/T11-<日期>/<证据文件>

未修改的禁止范围:
  - T01..T10 业务代码:未触碰
  - 任何契约文档:未触碰

契约来源:
  - 02 §9.1(M2/MVP 验收)
  - 07 §9(阶段验收)+§10(手工验收)
  - 09 §6(质量门禁)+§7(关键检查点)
  - 13 §5 T11(任务卡定义)

先失败的测试或现状基线:
  - 基线:MvpHappyPathIT 失败(各模块单独测试通过但未串联)

验证命令与结果:
  - mvn test -> Tests run: N, Failures: 0
  - curl happy path 11 步全部通过
  - 失败路径 9 个场景全部符合期望
  - 性能指标 5 项全部达标

权限/归属/幂等/失败场景证据:
  - 必测 1-10 见 §6

文档是否需要同步: 是 / 否,原因: ...

剩余风险:
  - 单实例性能基线,真实负载未测
  - PDF 模板只有 classic,真实简历可能需要 modern(M3)
  - 资源软删后的版本可追溯仅做集成断言,真实数据需手动抽查
```