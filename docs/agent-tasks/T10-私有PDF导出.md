# T10 — 私有 PDF 导出

> 对应 `docs/13-MVP实施契约与任务卡.md` §5 T10。
>
> 必读:`docs/agent-tasks/T00-通用执行前置条件.md`、`T01..T09`(全部)。

---

## 1. 任务卡摘要

```text
任务名称:私有 PDF
目标:用固定 classic 模板异步生成并受控下载 PDF。
当前阶段与优先级:第二阶段 / P0
来源文档:03 §9.7、04 §3.16、05 §11、08 §8
允许范围:export 模块;pdf-service 的 /render 与 /download 路由;export DTO/服务/控制器/测试;前端导出按钮与下载。
禁止:模板选择器;任意 HTML;公开 URL;模板代码不固定(classic 唯一)。
完成定义:HTML 转义、外部 URL/文件 URL 被拒绝、超时、超大输入、临时文件清理、跨用户、过期、软删、校验和、服务认证失败全部有测试。
```

---

## 2. 本卡依赖

### 2.1 前置 Tnn

- [ ] T01 完成(`export_task` 表已建)
- [ ] T02 完成
- [ ] T03 完成(简历版本可用)

### 2.2 外部依赖

- [ ] MySQL 已启动
- [ ] Node.js 20+(pdf-service 需要)
- [ ] PDF 服务在本地 `http://localhost:3001`(内网监听)

---

## 3. 目标文件清单

```text
server/pom.xml                                                                  修改(新增依赖)
server/src/main/resources/application.yml                                       修改(新增 app.export.* 与 pdf-service 配置)
server/src/main/java/com/intelligentresume/export/domain/ExportTask.java        新增
server/src/main/java/com/intelligentresume/export/domain/ExportStatus.java      新增(枚举)
server/src/main/java/com/intelligentresume/export/repository/ExportTaskRepository.java 新增
server/src/main/java/com/intelligentresume/export/dto/CreateExportRequest.java   新增
server/src/main/java/com/intelligentresume/export/dto/ExportTaskStatusResponse.java 新增
server/src/main/java/com/intelligentresume/export/service/PdfServiceClient.java  新增(调用 pdf-service,带服务令牌)
server/src/main/java/com/intelligentresume/export/service/ExportStorageService.java 新增(本地文件存储,key 随机)
server/src/main/java/com/intelligentresume/export/service/ExportService.java     新增
server/src/main/java/com/intelligentresume/export/service/ExportTaskWorker.java  新增(@Scheduled 轮询)
server/src/main/java/com/intelligentresume/export/controller/ExportController.java 新增
server/src/main/java/com/intelligentresume/common/security/ServiceTokenProvider.java 新增(轮换的服务令牌)
server/src/test/java/com/intelligentresume/export/service/PdfServiceClientTest.java 新增(用 WireMock 或 MockServer)
server/src/test/java/com/intelligentresume/export/service/ExportServiceTest.java  新增
server/src/test/java/com/intelligentresume/export/service/ExportStorageServiceTest.java 新增
server/src/test/java/com/intelligentresume/export/controller/ExportControllerIT.java 新增

pdf-service/package.json                                                          修改(新增 puppeteer)
pdf-service/src/server.js                                                         修改(添加 /render 与 /download 路由,加 service-token 中间件)
pdf-service/src/templates/classic.js                                              新增(固定模板,生成 HTML)
pdf-service/src/render.js                                                          新增(转 PDF)
pdf-service/src/sanitize.js                                                        新增(用户字段转义、外部 URL 拒绝)
pdf-service/test/render.test.js                                                    新增(可选用 vitest)

web/src/api/export.ts                                                              新增
web/src/views/ExportTaskView.vue                                                    新增
web/src/stores/export.ts                                                           新增
web/src/router/index.ts                                                            修改
```

> 禁止触碰:`ai/**`、`careermaterial/**`、`resume/**`、`jobdescription/**`、`scoring/**`、`system/**`、`auth/**`、`common/**` 中除 `ServiceTokenProvider` 与本卡新增之外的代码;`web/` 中除新增/修改之外的文件;`docs/01..13`;`docs/agent-tasks/**`;`db/migration/**`;`BaseEntity.java`;`CorsConfig.java`。
>
> **本卡严禁**:
>
> 1. 模板可由客户端选择(只允许 `classic`)
> 2. PDF 服务接收任意 HTML(只接收结构化 JSON + 模板代码 `classic`)
> 3. 公开 URL(下载必须经后端校验)
> 4. 文件名 / 存储键含用户 ID / 简历标题
> 5. 在 HTML 中插入 `<script>`、`<iframe>`、外链 CSS/字体、外部图片

---

## 4. 包结构与命名

```text
server/src/main/java/com/intelligentresume/export/
├── controller/ExportController.java
├── service/
│   ├── ExportService.java
│   ├── ExportTaskWorker.java
│   ├── ExportStorageService.java
│   └── PdfServiceClient.java
├── domain/
│   ├── ExportTask.java
│   └── ExportStatus.java
├── repository/ExportTaskRepository.java
└── dto/
    ├── CreateExportRequest.java
    └── ExportTaskStatusResponse.java
```

```text
pdf-service/src/
├── server.js
├── render.js
├── sanitize.js
└── templates/
    └── classic.js
```

---

## 5. 配置项

### 5.1 `server/pom.xml`(修改)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>   <!-- 仅用于 WebClient;若不愿引入,可改为 RestTemplate -->
</dependency>
```

> 备选:不引入 webflux,用 `RestTemplate` 或 JDK 11+ `HttpClient` 同步调用。本卡推荐 WebClient,但允许替换为 RestTemplate(替换后需同步更新 `PdfServiceClient` 与测试)。

### 5.2 `server/src/main/resources/application.yml`(修改)

```yaml
app:
  export:
    templates:
      allowed: [classic]                 # 唯一允许的模板
    storage:
      root: ${EXPORT_STORAGE_ROOT:./var/exports}
      public-base-url: ""                # 必须空,不允许公开 URL
    pdf-service:
      base-url: ${PDF_SERVICE_BASE_URL:http://127.0.0.1:3001}
      service-token: ${PDF_SERVICE_TOKEN:CHANGE_ME_DEV_ONLY_AT_LEAST_32_BYTES}
      timeout-ms: 15000
    file:
      max-size-bytes: 10485760           # 10 MB
      ttl-seconds: 86400                 # 24 小时过期
    worker:
      poll-interval-ms: 3000
      batch-size: 3
```

### 5.3 `pdf-service/package.json`(修改)

```json
{
  "dependencies": {
    "express": "^4.21.0",
    "puppeteer": "^23.0.0"
  }
}
```

### 5.4 `pdf-service/.env.example`(已存在,本卡修改)

```env
PDF_SERVICE_PORT=3001
PDF_SERVICE_TOKEN=CHANGE_ME_DEV_ONLY_AT_LEAST_32_BYTES
RENDER_TIMEOUT_MS=15000
MAX_INPUT_BYTES=1048576
MAX_OUTPUT_BYTES=10485760
TEMPLATE_DIR=./src/templates
```

> 不要把 token 硬编码到代码。

---

## 6. 数据库变更

无。

---

## 7. 关键代码骨架

### 7.1 实体

```java
@Entity
@Table(name = "export_task")
public class ExportTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resume_version_id", nullable = false)
    private Long resumeVersionId;

    @Column(name = "template_code", nullable = false, length = 64)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ExportStatus status = ExportStatus.PENDING;

    @Column(name = "file_storage_key", length = 512)
    private String fileStorageKey;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // getters / setters
}

enum ExportStatus { PENDING, RUNNING, SUCCESS, FAILED, EXPIRED }
```

### 7.2 Repository

```java
public interface ExportTaskRepository extends JpaRepository<ExportTask, Long> {
    Optional<ExportTask> findByIdAndResumeVersion_UserId(Long id, Long userId);
    // 注:Spring Data 不直接支持跨实体关联;使用 @Query:
    @Query("""
        SELECT e FROM ExportTask e, ResumeVersion v, Resume r
        WHERE e.id = :id AND e.resumeVersionId = v.id AND v.resumeId = r.id AND r.userId = :userId
        """)
    Optional<ExportTask> findByIdForUser(@Param("id") Long id, @Param("userId") Long userId);

    List<ExportTask> findByStatusAndExpiresAtBefore(ExportStatus status, LocalDateTime time);
}
```

### 7.3 DTO

```java
public record CreateExportRequest(
    @NotNull Long resumeVersionId,
    @Pattern(regexp = "classic") String templateCode    // 必须是 "classic"
) {}

public record ExportTaskStatusResponse(
    Long taskId,
    String status,
    String templateCode,
    Long fileSizeBytes,
    String checksumSha256,
    LocalDateTime expiresAt,
    String errorMessage,
    String downloadUrl          // 相对路径 "/api/exports/files/{taskId}",由前端拼接 baseURL
) {}
```

### 7.4 ServiceTokenProvider

```java
@Component
public class ServiceTokenProvider {
    @Value("${app.export.pdf-service.service-token}") String token;
    public String current() { return token; }      // MVP 静态 token;M4 改为轮换
}
```

### 7.5 PdfServiceClient

```java
@Component
public class PdfServiceClient {

    @Value("${app.export.pdf-service.base-url}") String baseUrl;
    @Value("${app.export.pdf-service.timeout-ms}") long timeoutMs;

    private final ServiceTokenProvider tokenProvider;
    private final WebClient webClient;

    public PdfServiceClient(ServiceTokenProvider tokenProvider, WebClient.Builder builder) {
        this.tokenProvider = tokenProvider;
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    public byte[] render(String templateCode, Map<String, Object> payload) {
        // POST /render
        // Headers: Authorization: Bearer <serviceToken>, X-Trace-Id
        // Body: { templateCode, payload }
        // Response: application/pdf bytes
        // 失败抛 BusinessException(PDF_FAILURE, 50003)
    }

    // 实现要点:
    // 1. 超时用 timeoutMs
    // 2. 序列化 payload 后字节数 ≤ app.export.file.max-input-bytes / 10
    // 3. 4xx/5xx 响应读取 errorMessage(若 pdf-service 返回 { code, message })
}
```

### 7.6 ExportStorageService

```java
@Service
public class ExportStorageService {

    public StoredFile store(byte[] content, String suffix);
    public byte[] read(String storageKey);
    public void delete(String storageKey);
    public void cleanupExpired();

    public record StoredFile(String storageKey, long size, String checksumSha256) {}

    // 实现要点:
    // 1. storageKey = UUID.randomUUID() + "." + suffix;
    //    不含 userId / resumeTitle / 时间戳等可猜信息
    // 2. 文件路径: root + "/" + key[0..2] + "/" + key  (按前缀分目录)
    // 3. cleanupExpired:每天一次,扫描 <root>/**/<key>,expires_at < now 的删除
}
```

### 7.7 ExportService

```java
@Service
public class ExportService {

    public ExportTaskStatusResponse create(CreateExportRequest req, Long userId);
    public ExportTaskStatusResponse get(Long taskId, Long userId);
    public Resource download(Long taskId, Long userId);   // 受控流

    // create:
    //   1. 校验 resumeVersion 归属 → NOT_FOUND
    //   2. 校验 templateCode == "classic" → 否则 VALIDATION
    //   3. 创建 ExportTask(status=PENDING, expires_at=now + ttl)
    //   4. 不直接调 PDF 服务,留给 worker
    //   5. 立即返回 ExportTaskStatusResponse(status=PENDING, downloadUrl=/api/exports/files/{id})

    // get:
    //   1. findByIdForUser,跨用户 → NOT_FOUND
    //   2. 若 status=SUCCESS 且 expires_at < now → status=EXPIRED
    //   3. 返回 ExportTaskStatusResponse(不含文件内容)

    // download:
    //   1. findByIdForUser → NOT_FOUND
    //   2. 校验 status=SUCCESS 且 expires_at > now → 否则 41001(自定义 GOONE,或复用 NOT_FOUND)
    //   3. 读取 storage 字节流,以 Resource 返回
    //   4. 不暴露 storageKey
}
```

### 7.8 ExportTaskWorker

```java
@Component
public class ExportTaskWorker {

    @Scheduled(fixedDelayString = "${app.export.worker.poll-interval-ms}")
    public void poll() {
        // 1. SELECT ... WHERE status=PENDING FOR UPDATE SKIP LOCKED LIMIT batch
        // 2. UPDATE status=RUNNING
        // 3. 调用 PdfServiceClient.render(templateCode, payload)
        // 4. ExportStorageService.store(bytes, "pdf")
        // 5. UPDATE status=SUCCESS, file_storage_key, file_size_bytes, checksum_sha256, expires_at
        // 6. 失败:UPDATE status=FAILED, error_message;按可重试/不可重试分类
    }
}
```

### 7.9 Controller 路由

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/exports/pdf` | 创建导出任务(202) |
| GET | `/api/exports/tasks/{id}` | 查询状态 |
| GET | `/api/exports/files/{id}` | 下载(`Content-Type: application/pdf`) |

### 7.10 PDF 服务端(`/render`)

```javascript
// pdf-service/src/server.js 中追加
import { renderPdf } from './render.js';

app.post('/render', authenticateServiceToken, async (req, res) => {
  const { templateCode, payload } = req.body || {};
  if (templateCode !== 'classic') {
    return res.status(400).json({ code: 40001, message: 'templateCode 必须为 classic' });
  }
  const inputBytes = Buffer.byteLength(JSON.stringify(req.body), 'utf8');
  if (inputBytes > Number(process.env.MAX_INPUT_BYTES || 1048576)) {
    return res.status(413).json({ code: 40001, message: '输入过大' });
  }
  try {
    const pdf = await renderPdf(templateCode, payload, {
      timeoutMs: Number(process.env.RENDER_TIMEOUT_MS || 15000),
      maxOutputBytes: Number(process.env.MAX_OUTPUT_BYTES || 10485760),
    });
    res.setHeader('Content-Type', 'application/pdf');
    res.send(pdf);
  } catch (err) {
    res.status(500).json({ code: 50003, message: err.message || 'PDF 渲染失败' });
  }
});

function authenticateServiceToken(req, res, next) {
  const header = req.get('Authorization') || '';
  const expected = `Bearer ${process.env.PDF_SERVICE_TOKEN}`;
  if (header !== expected) {
    return res.status(401).json({ code: 40101, message: '服务认证失败' });
  }
  next();
}
```

### 7.11 模板与转义

```javascript
// pdf-service/src/sanitize.js
import escapeHtml from 'escape-html';   // npm i escape-html

export function sanitizeValue(value) {
  if (value == null) return '';
  if (typeof value === 'string') return escapeHtml(value);
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) return value.map(sanitizeValue);
  if (typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      if (/^(https?|file|javascript|data):/i.test(String(v))) {
        throw new Error('禁止外部 URL / file: / data:');
      }
      out[k] = sanitizeValue(v);
    }
    return out;
  }
  return '';
}
```

```javascript
// pdf-service/src/templates/classic.js
import { sanitizeValue } from '../sanitize.js';

export function renderClassicHtml(payload) {
  const safe = sanitizeValue(payload);   // 全量转义 + 拒绝外链
  // 模板只接受 sanitized JSON,再字符串拼接生成 HTML
  // 禁止任何 {{...}} 模板引擎或动态 eval
  // 只允许字符串拼接或简单模板字面量
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>简历</title>
<style>
  body { font-family: "Helvetica", "Arial", "Microsoft YaHei", sans-serif; max-width: 800px; margin: 2em auto; color: #222; line-height: 1.5; }
  h1 { font-size: 24px; margin-bottom: 0; }
  h2 { font-size: 18px; margin-top: 1.5em; border-bottom: 1px solid #ccc; padding-bottom: 4px; }
  ul { padding-left: 1.5em; }
  .basics-summary { color: #555; }
</style>
</head><body>
${renderBasics(safe.basics)}
${renderSection('工作经历', safe.work, renderWork)}
${renderSection('项目经历', safe.projects, renderProject)}
${renderSection('教育背景', safe.education, renderEducation)}
${renderSection('技能', safe.skills, renderSkill)}
</body></html>`;
}

function renderBasics(b) {
  if (!b) return '';
  return `<h1>${b.name || ''}</h1>
<p class="basics-summary">${b.label || ''} · ${b.email || ''}</p>
<p>${b.summary || ''}</p>`;
}
// ... 其他 render 函数
```

### 7.12 PDF 渲染

```javascript
// pdf-service/src/render.js
import puppeteer from 'puppeteer';
import { renderClassicHtml } from './templates/classic.js';

let browserPromise;

async function getBrowser() {
  if (!browserPromise) {
    browserPromise = puppeteer.launch({
      headless: true,
      args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
    });
  }
  return browserPromise;
}

export async function renderPdf(templateCode, payload, opts) {
  if (templateCode !== 'classic') throw new Error('未知模板');
  const html = renderClassicHtml(payload);
  const browser = await getBrowser();
  const page = await browser.newPage();
  try {
    await page.setContent(html, { timeout: opts.timeoutMs, waitUntil: 'load' });
    const pdf = await page.pdf({
      format: 'A4',
      printBackground: true,
      margin: { top: '20mm', bottom: '20mm', left: '20mm', right: '20mm' },
    });
    if (pdf.length > opts.maxOutputBytes) throw new Error('PDF 输出过大');
    return pdf;
  } finally {
    await page.close();
  }
}
```

### 7.13 关键不变量

- **HTML 转义**:所有用户字段必须经 `escapeHtml`;校验时再次断言没有 `<script` / `onerror` / `javascript:` / `file:` / `http:` / `https:`
- **拒绝外链**:任何值匹配 `^(https?|file|javascript|data):` 一律抛错
- **模板固定**:只允许 `classic`;`templateCode` 不匹配返回 `40001`
- **超时**:`page.setContent` 使用 `opts.timeoutMs`;超时抛错
- **大小限制**:输入 `MAX_INPUT_BYTES`、输出 `MAX_OUTPUT_BYTES`
- **存储 key 随机**:`UUID.randomUUID()` 生成,不拼接业务字段
- **下载受控**:后端校验 `expires_at > now`、用户归属、`status=SUCCESS`
- **服务认证**:每次调 PDF 服务必须带 `Authorization: Bearer <token>`;token 不匹配 → 40101
- **临时清理**:成功后不再保留 Puppeteer 临时页面;失败也 `page.close()`
- **公开 URL 为空**:`public-base-url` 必须为 `""`;不允许任何代码生成 `/exports/<key>` 形式的永久 URL

---

## 8. 前端变更

```text
web/src/api/export.ts                                   新增
web/src/views/ExportTaskView.vue                       新增
web/src/stores/export.ts                                新增
web/src/router/index.ts                                 修改
web/src/api/client.ts                                   修改(支持 responseType: 'blob' 下载)
```

**UI 最小要求**:

- 简历详情页增加「导出 PDF」按钮,模板硬编码 `classic`(前端不允许选择)
- 提交后跳转任务页,轮询状态
- 状态为 `SUCCESS` 时显示「下载 PDF」按钮,点击直接触发浏览器下载(不暴露存储 URL)
- 状态为 `EXPIRED` 时提示「文件已过期,请重新生成」

---

## 9. 测试清单

```text
server/src/test/java/com/intelligentresume/export/service/PdfServiceClientTest.java
    @DisplayName("正常路径: 调用 /render 返回字节")
    void render_returnsBytes()

    @DisplayName("失败路径: pdf-service 返回 500 抛 PDF_FAILURE")
    void render_500_throwsPdfFailure()

    @DisplayName("失败路径: 超时抛 PDF_FAILURE")
    void render_timeout_throwsPdfFailure()

    @DisplayName("失败路径: token 不匹配时 pdf-service 返回 401,客户端识别")
    void render_tokenMismatch_recognized()

server/src/test/java/com/intelligentresume/export/service/ExportStorageServiceTest.java
    @DisplayName("正常路径: 存储后 read 返回相同字节")
    void storeAndRead()

    @DisplayName("正常路径: storageKey 随机且不含 userId")
    void storageKey_randomNoUserId()

    @DisplayName("正常路径: cleanupExpired 删除过期文件")
    void cleanupExpired_removes()

    @DisplayName("正常路径: 不删除未过期文件")
    void cleanupExpired_keepsAlive()

server/src/test/java/com/intelligentresume/export/service/ExportServiceTest.java
    @DisplayName("正常路径: 创建导出返回 PENDING")
    void create_pending()

    @DisplayName("失败路径: templateCode 不是 classic 返回 VALIDATION")
    void create_invalidTemplate_validationFails()

    @DisplayName("失败路径: 跨用户 get 返回 NOT_FOUND")
    void get_crossUser_notFound()

    @DisplayName("失败路径: 跨用户 download 返回 NOT_FOUND")
    void download_crossUser_notFound()

    @DisplayName("失败路径: 下载过期文件返回 NOT_FOUND")
    void download_expired_notFound()

    @DisplayName("失败路径: 下载失败文件返回 NOT_FOUND")
    void download_failed_notFound()

server/src/test/java/com/intelligentresume/export/controller/ExportControllerIT.java
    @DisplayName("POST /api/exports/pdf 202")
    void postCreate_202()

    @DisplayName("GET /api/exports/tasks/{id} 200")
    void getTask_200()

    @DisplayName("GET /api/exports/files/{id} 返回 application/pdf")
    void download_returnsPdf()

    @DisplayName("GET 文件跨用户返回 40401")
    void download_crossUser_40401()

    @DisplayName("GET 文件过期返回 40401")
    void download_expired_40401()

pdf-service/test/render.test.js(可选;若不写,改为手动集成测试)
    test("classic 模板渲染不抛错,输出为 PDF 字节")
    test("payload 含 <script> 被拒绝")
    test("payload 含 http:// 外链被拒绝")
    test("输入超过 MAX_INPUT_BYTES 返回 413")
```

---

## 10. 验证命令

```bash
# 后端测试
cd server
mvn -q -DskipTests compile
mvn test
mvn spring-boot:run

# PDF 服务(另开终端)
cd pdf-service
Copy-Item .env.example .env
npm install
npm run dev

# 验证健康检查
curl http://localhost:3001/health

# 验证服务认证:不带 token 应 401
curl -i -X POST http://localhost:3001/render -H "Content-Type: application/json" -d '{"templateCode":"classic","payload":{}}'
# 期望: 401

# 带正确 token:
TOKEN=$(grep PDF_SERVICE_TOKEN pdf-service/.env | cut -d= -f2)
curl -i -X POST http://localhost:3001/render \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"templateCode":"classic","payload":{"basics":{"name":"Alice"},"work":[]}}'
# 期望: 200 application/pdf

# 后端:登录拿 token + 创建导出
TOKEN=$(...)
curl -X POST http://localhost:8080/api/exports/pdf \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"resumeVersionId":1,"templateCode":"classic"}'
# 期望: 202 + taskId

# 轮询后下载
sleep 5
curl http://localhost:8080/api/exports/tasks/$TASK_ID -H "Authorization: Bearer $TOKEN"
# 期望: status=SUCCESS

curl http://localhost:8080/api/exports/files/$TASK_ID -H "Authorization: Bearer $TOKEN" -o resume.pdf
file resume.pdf
# 期望: PDF document
```

---

## 11. 停止条件

补充 `T00 §6`:

- [ ] 模板可由客户端选择
- [ ] PDF 服务接收任意 HTML
- [ ] 公开 URL 暴露 storage key
- [ ] storage key 含 userId 或简历标题
- [ ] HTML 含未转义的用户字段
- [ ] 调 PDF 服务时无服务认证
- [ ] 跨用户 download 返回 40301(必须 40401)

---

## 12. 完成报告

```text
任务 ID:T10-私有 PDF 导出
状态:DONE / BLOCKED
修改文件: <按 §3 实际改动列出>

未修改的禁止范围:
  - 模板选择器:未引入
  - 任意 HTML:未接收
  - 公开 URL:未生成
  - storage key 含业务字段:未发生
  - 跨用户 40301:未发生

契约来源:
  - 03 §9.7(PDF 服务信任边界)
  - 04 §3.16(export_task 表)
  - 05 §11(导出接口)
  - 07 §5.9(导出测试)
  - 08 §8(文件管理)
  - 12 §3 + 13 §3(不可违反规则 + 全局不变量)

先失败的测试或现状基线:
  - 基线:ExportController 不存在,PdfServiceClient 不存在

验证命令与结果:
  - mvn test -> Tests run: N, Failures: 0
  - pdf-service /render 401 -> 200 -> 返回 PDF 字节
  - 端到端 创建 → 轮询 → 下载 -> 全部通过

权限/归属/幂等/失败场景证据:
  - 必测 1(HTML 转义):通过
  - 必测 2(外部 URL 拒绝):通过
  - 必测 3(超时):通过
  - 必测 4(超大输入):通过
  - 必测 5(临时清理):通过
  - 必测 6(跨用户):通过
  - 必测 7(过期):通过
  - 必测 8(软删):通过(软删 resume_version 后 export_task 失效)
  - 必测 9(校验和):通过
  - 必测 10(服务认证):通过

文档是否需要同步: 否

剩余风险:
  - 本地存储(文件系统)在多实例部署下不共享;M4 引入对象存储
  - Puppeteer 启动慢;首请求延迟较高,可加预热(M4)
  - 模板只有 classic;M3 后再补 modern
```