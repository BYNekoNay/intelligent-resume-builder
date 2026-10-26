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

# 3) 真开窗口
cd desktop-shell && npm install && npm start

# 4) 打 Windows 安装包（NSIS）。stage-jar 会把**当前版本**的 jar 暂存到 vendor/
cd desktop-shell && npm install && npm run dist
```

**国内网络注意**：`electron` 与 NSIS 二进制从 GitHub 下载会 502。实测可用镜像：

```bash
ELECTRON_BUILDER_BINARIES_MIRROR=https://npmmirror.com/mirrors/electron-builder-binaries/ npm run dist
```

**为什么不启用 `signAndEditExecutable`**：electron-builder 默认要解压 `winCodeSign` 去改 exe 的图标/版本，
而那个包里含有 macOS 的符号链接，Windows 非开发者模式**无权创建**，解压直接失败。
本仓在 `build.win.signAndEditExecutable=false` 下打包，代价是 exe 保持 Electron 默认图标与版本信息。
要恢复的话，需要开发者模式或管理员权限。

**为什么需要 `scripts/stage-jar.mjs`**：2026-10-26 实测踩到 —— `server/target` 里躺着 0.4.0/0.4.1 两代旧 jar，
`extraResources` 通配符把它们**一起打进安装包**，而取 jar 的逻辑取第一个匹配项，装出来跑的是旧版本。
现在构建入口只暂存当前版本那一个，`vendor/` 里也只允许存在它。

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
- **安装器点完能启动**没验过：NSIS 安装包已产出（`release-v042/Zhili Setup 0.1.0.exe`，162MB），
  但"双击安装 → 启动"需要人工在图形会话里走一遍。打包后的**编排**已用
  `node scripts/smoke.mjs --packaged release-v042/win-unpacked` 验证（16s 就绪、SPA 200、桌面端点 200）；
- **代码签名没做**（无证书），exe 会是"未知发布者"；图标仍是 Electron 默认；
- **没有 Windows Toast**（R36）：「关闭应用也能提醒」是阶段六的另一半，一行未写；
- ~~本地 AI 设置还没接到任务链路上~~ —— 2026-10-26 已接：`AiRuntimeOverlay` 在 provider 的 6 个
  决策点生效，保存即生效、关闭/删除回落配置文件（见 `OPEN-DECISIONS` 同日 RESOLVED）。
