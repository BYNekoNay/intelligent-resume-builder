# PDF 服务

该服务是私有 PDF 导出的独立进程边界，使用 Puppeteer 将经过校验的结构化简历渲染为 A4 PDF。

## 接口

- `GET /health`：返回版本和当前渲染能力，不需要服务令牌。
- `POST /render`：使用 `X-Service-Token` 或 Bearer Token 鉴权，接收 `templateCode` 与 `payload`，返回 `application/pdf`。

当前支持 `classic`、`modern`、`minimal`、`ats`、`executive`、`compact` 和 `academic`。渲染器覆盖简历的全部标准章节、自定义章节及 `layout.sectionOrder`，不加载外部资源。

## 验证

```powershell
npm run check
npm test
```

服务只负责渲染。任务持久化、私有文件存储、下载授权、过期清理和失败重试由 Spring Boot API 的 `export` 模块负责。
