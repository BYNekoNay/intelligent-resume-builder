# 智历 · Windows 桌面壳（ADR-012 阶段六）

把「本地工作区」包成双击可用的桌面应用。**这一批只做了可验证的骨架**，没有安装包——
完整清单与"哪些已验证 / 哪些没验证"见下面。

## 结构

```
desktop-shell/
  src/backend.js     后端进程编排（起 jar / 等就绪 / 停）—— 纯 Node，可测
  src/main.js        Electron 主进程（窗口、单实例、退出清理）
  scripts/smoke.mjs  编排冒烟测试（不需要 Electron）
```

加载策略是 `loadURL('http://127.0.0.1:8082/')`：前端由**同一个 jar** 托管
（`server/pom.xml` 把 `web/dist` 打进 `static/`，`SpaFallbackFilter` 负责 history 回退）。
这样路由模式、`base '/'`、同源 API、cookie 与浏览器模式完全一致，**前端零改动**。
`file://` 方案需要同时改 `base`、换成 hash 路由、并给 API 配绝对地址，且 SPA 深链会 404 —— 不走那条。

## 怎么跑

```bash
# 1) 先构建两样产物（壳不负责构建，避免"跑起来了但不是这次的产物"）
cd web && npm run build
cd ../server && mvn -DskipTests package

# 2) 冒烟：验证编排（不需要 Electron、不需要图形界面）
cd .. && node desktop-shell/scripts/smoke.mjs

# 3) 真开窗口（需要先装 Electron，约 100MB；本仓默认不装）
cd desktop-shell && npm install && npm start
```

## backend.js 里三条必须做对的事（都对应真实踩过的坑）

| 做法 | 为什么 |
| --- | --- |
| `--server.port` **显式传** | 环境里若有 `SERVER__PORT`（双下划线），Spring 宽松绑定会静默覆盖 profile 里的 8082 |
| 启动前 `delete env.SERVER__PORT` | 同上，双保险（已踩 4 次的坑） |
| `--server.address=127.0.0.1` 必传 | 桌面模式启动时会校验监听地址，非回环直接拒绝启动 |
| `DESKTOP_DATA_DIR` 用绝对路径 | H2 拒绝隐式相对路径 |
| 就绪判定用 `/actuator/health` | **不能**用 `/api/system/health`：桌面模式下它把 `pdf-renderer` 报成 DOWN（不连 Node PDF 服务） |

## 已验证 / 未验证（别混）

**已在本机验证（2026-10-24，`node desktop-shell/scripts/smoke.mjs`）**

- jar 用 desktop profile 起得来，22s 就绪，`/actuator/health` = UP；
- `GET /` 返回 200 且是真 SPA 壳（含 `#app`）；
- `GET /api/desktop/ai-settings` 返回 200 —— 桌面 profile + 本地免登录用户注入 + V38 表迁移都成立；
- 环境里塞了 `SERVER__PORT=65530`，实际没被劫持（65530 无服务）；
- 停后端后 8082 立刻释放（无孤儿 java 进程）。

**未验证（不要当成已完成）**

- **Electron 窗口本身没起过**：本机是无头会话，`npm install && npm start` 留给有图形会话时验；
- **没有安装包**：`electron-builder` / NSIS / 图标 / 代码签名都没做，安装流程完全没跑过；
- **没有 Windows Toast**（R36）：「关闭应用也能提醒」是阶段六的另一半，一行未写；
- **本地 AI 设置还没接到任务链路上**：设置能存、能测连接、密钥 DPAPI 加密落盘，但
  `BailianAiProvider` 仍只读配置文件/环境变量 —— 也就是说"改了设置，实际任务还是用旧配置"。
  见 `docs/decisions/OPEN-DECISIONS.md` 里 2026-10-24 的登记项。
