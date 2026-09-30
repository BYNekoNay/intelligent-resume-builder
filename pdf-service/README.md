# PDF 服务

该服务是私有 PDF 导出的独立进程边界，使用 Puppeteer 将经过校验的结构化简历渲染为 A4 PDF。

## 接口

- `GET /health`：返回版本、当前渲染能力与容量快照（`capacity.activePages/queued/maxConcurrentPages/maxQueueSize/draining`），不需要服务令牌。drain 中整体 `status` 为 `DEGRADED`；队列饱和只体现在 `checks[render-capacity]=SATURATED`，不改变整体状态（避免瞬态抖动）。
- `POST /render`：使用 `X-Service-Token` 或 Bearer Token 鉴权，接收 `templateCode` 与 `payload`，返回 `application/pdf`。容量满或服务正在关闭时返回 **503 + `Retry-After`**（`code: 50301`、`retryable: true`），属于可重试失败。

当前支持 `classic`、`modern`、`minimal`、`ats`、`executive`、`compact` 和 `academic`。渲染器覆盖简历的全部标准章节、自定义章节及 `layout.sectionOrder`，不加载外部资源。

## 容量与关闭语义

- 同时最多 `PDF_SERVICE_MAX_CONCURRENT_PAGES`（默认 4）个页面在渲染，超出的请求进入 FIFO 等待队列；队列上限 `PDF_SERVICE_MAX_QUEUE_SIZE`（默认 16，0 = 不排队），队满即返回 503，请求不会无限堆积。
- 收到 `SIGTERM`/`SIGINT` 后进入 drain：拒绝新请求、清空等待队列，in-flight 渲染继续跑完；最多等待 `PDF_SERVICE_DRAIN_TIMEOUT_MS`（默认 10000ms）后关闭浏览器与监听。
- 容器部署需保证宽限期大于 drain 超时（`deploy/docker-compose.prod.yml` 已设 `stop_grace_period: 30s`）。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PDF_SERVICE_PORT` | 3001 | 监听端口（也可用 `--port=`） |
| `PDF_SERVICE_TOKEN` | 开发占位值 | 服务令牌；生产必须为 ≥32 位非默认值 |
| `PDF_SERVICE_MAX_CONCURRENT_PAGES` | 4 | 并发渲染页面上限（整数 ≥1） |
| `PDF_SERVICE_MAX_QUEUE_SIZE` | 16 | 等待队列上限（整数 ≥0） |
| `PDF_SERVICE_DRAIN_TIMEOUT_MS` | 10000 | 关闭时等待 in-flight 渲染的上限（整数 ≥1） |

非法取值会在启动时直接以退出码 1 失败。

## 验证

```powershell
npm run check
npm test
```

服务只负责渲染。任务持久化、私有文件存储、下载授权、过期清理和失败重试由 Spring Boot API 的 `export` 模块负责。
