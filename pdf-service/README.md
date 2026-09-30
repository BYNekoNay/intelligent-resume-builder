# PDF 服务

该服务是私有 PDF 导出的独立进程边界，使用 Puppeteer 将经过校验的结构化简历渲染为 A4 PDF。

## 接口

- `GET /health`：返回版本、当前渲染能力与容量快照（`capacity.activePages/queued/maxConcurrentPages/maxQueueSize/queueTimeoutMs/draining`），不需要服务令牌。drain 中整体 `status` 为 `DEGRADED`；队列饱和只体现在 `checks[render-capacity]=SATURATED`，不改变整体状态（避免瞬态抖动）。
- `POST /render`：使用 `X-Service-Token` 或 Bearer Token 鉴权，接收 `templateCode` 与 `payload`，返回 `application/pdf`。容量满或服务正在关闭时返回 **503 + `Retry-After`**（`code: 50301`、`retryable: true`），属于可重试失败。

当前支持 `classic`、`modern`、`minimal`、`ats`、`executive`、`compact` 和 `academic`。渲染器覆盖简历的全部标准章节、自定义章节及 `layout.sectionOrder`，不加载外部资源。

## 容量与关闭语义

- 同时最多 `PDF_SERVICE_MAX_CONCURRENT_PAGES`（默认 4）个页面在渲染，超出的请求进入 FIFO 等待队列；队列上限 `PDF_SERVICE_MAX_QUEUE_SIZE`（默认 16，0 = 不排队），队满即返回 503，请求不会无限堆积。
- 排队等待上限 `PDF_SERVICE_QUEUE_TIMEOUT_MS`（默认 15000ms）：排队超时的请求以可重试 503 拒绝，服务端总耗时上界 = 排队上限 + `2 × PDF_SERVICE_RENDER_TIMEOUT_MS`（默认 15000ms，覆盖 `setContent` 与 `pdf` 两次页面操作）。
- **死线链**：服务端上界（默认 45s）必须小于 API 侧读超时（`app.pdf.render-timeout-seconds`，默认 50s），该值又必须小于导出任务租约（`app.pdf.worker.lease-seconds`，默认 90s，且租约须覆盖 `batch-size × 读超时`）。内层死线先触发，服务端的 503/失败路径才能正常到达调用方，不会出现「客户端先断开、服务端后渲染成功」的浪费与误判。链条由 `server` 侧静态门禁 `PdfDeadlineContractTest` 守护。
- 收到 `SIGTERM`/`SIGINT` 后进入 drain：拒绝新请求、清空等待队列，in-flight 渲染继续跑完；最多等待 `PDF_SERVICE_DRAIN_TIMEOUT_MS`（默认 10000ms）后关闭浏览器与监听。
- 容器部署需保证宽限期大于 drain 超时（`deploy/docker-compose.prod.yml` 已设 `stop_grace_period: 30s`）。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PDF_SERVICE_PORT` | 3001 | 监听端口（也可用 `--port=`） |
| `PDF_SERVICE_TOKEN` | 开发占位值 | 服务令牌；生产必须为 ≥32 位非默认值 |
| `PDF_SERVICE_MAX_CONCURRENT_PAGES` | 4 | 并发渲染页面上限（整数 ≥1） |
| `PDF_SERVICE_MAX_QUEUE_SIZE` | 16 | 等待队列上限（整数 ≥0） |
| `PDF_SERVICE_QUEUE_TIMEOUT_MS` | 15000 | 排队等待上限，超时返回可重试 503（整数 ≥1） |
| `PDF_SERVICE_RENDER_TIMEOUT_MS` | 15000 | 单次页面操作（`setContent` / `pdf`）预算（整数 ≥1） |
| `PDF_SERVICE_DRAIN_TIMEOUT_MS` | 10000 | 关闭时等待 in-flight 渲染的上限（整数 ≥1） |

非法取值会在启动时直接以退出码 1 失败。

## 验证

```powershell
npm run check
npm test
```

服务只负责渲染。任务持久化、私有文件存储、下载授权、过期清理和失败重试由 Spring Boot API 的 `export` 模块负责。
