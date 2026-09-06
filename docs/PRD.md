# HomeHub 需求文档（PRD）

- **版本**: v0.3（草案，已吸收 2026-09-04 两轮评审反馈）
- **日期**: 2026-09-04
- **状态**: 待评审
- **来源**: 由 `home-nas`（Rust NAS 服务端）与 `my_nvr_app`（Android WireGuard/NVR 客户端）合并整合而来

**v0.2 变更摘要**: 数据库由 PostgreSQL 改为 **SQLite**；NAS 库模型改为「统一目录管理 + 归属标记」；新增 **WireGuard 身份审计日志**；缩略图定为单档 256px；YOLO 增加**风景/人脸**检测能力；分享功能整体移除；明确不考虑 HEIC、不迁移旧 RN 客户端；任务队列补充资源占用控制策略。

**v0.3 变更摘要**: §10 功能池中 5 项高价值建议确认纳入 v1：**原图保护**（收窄触发面：仅破坏性操作，无损压缩不留原图）、**回收站/软删除**、**全局搜索**（FTS5）、**全局去重**（双指纹：file_hash + pixel_hash，以源文件为查重对象）、**健康告警**（ntfy/Telegram）；新增二期功能池条目「AI 语义搜索」（自然语言查图，PC + Android）。

---

## 1. 项目背景与目标

### 1.1 背景

现有两个独立项目：

| 项目 | 定位 | 技术栈 | 现状 |
|------|------|--------|------|
| home-nas | 家用 NAS 服务端 + RN 客户端 | Rust + Axum 0.7 / PostgreSQL / SQLx；React Native (Expo) | 已具备文件管理、缩略图、用户体系 |
| my_nvr_app | 手机端 NVR 客户端 | Android 原生 Kotlin + Compose，minSdk 30 | 已具备 WireGuard 隧道连接 + X5 WebView 加载 Frigate |

两个项目各自维护，功能重叠不足、体验割裂。本项目的目标是将两者合并为**一个独立项目 HomeHub**，以 WireGuard 为统一组网与准入手段，提供「相册 + NAS 文件 + 监控（预留）」三位一体的自托管家庭服务。

### 1.2 核心目标

1. **组网即准入**：家庭网络连接由 WireGuard 完成，服务端不做用户体系（用户由 WG 控制），手机端连上隧道即可使用全部功能；服务端记录 WG 身份用于审计。
2. **一套服务端**：基于 `home-nas/backend`（Rust + Axum）重构为统一后台，提供管理后台页面（Web）。
3. **一个手机 App**：基于 `my_nvr_app` 的 Android 工程重构，包含相册、NAS 文件、监控（预留）三大模块。
4. **智能照片管理**：对标 immich / mtphoto 的核心能力——YOLO 物体/风景/人脸识别打标、缩略图、无损压缩、多维度浏览（时间轴/目录树/分类/地理位置）。

### 1.3 非目标（本期明确不做）

- 不做多用户/多租户（由 WG 网络层隔离；WG 身份仅用于日志审计，不做权限区分）。
- **不做分享功能**（现有 shares 能力移除；未来如需公网分享另行立项，含安全设计）。
- 监控模块本期仅保留入口与配置页骨架，不实现播放（后续对接 Frigate 或自研录像）。
- 不做 iOS 端；**旧 home-nas RN 客户端废弃，不迁入本仓库**。
- 不支持 HEIC（如后续有 iPhone 照片需求，二期再评估 libheif 转码）。
- 不做公网穿透本身（WG Endpoint 由用户自行准备，如家庭宽带的 DDNS + 端口转发）。

---

## 2. 现状资产盘点（可复用部分）

### 2.1 来自 home-nas（已迁移至 `server/`）

| 资产 | 位置 | 复用方式 |
|------|------|----------|
| Axum 服务骨架、路由组织 | `server/src/main.rs` | 直接沿用 |
| 虚拟路径→本地路径解析（`<子库>/<相对路径>`） | `server/src/config/mod.rs` | 沿用并改造（统一目录 + 归属标记 + 忽略目录） |
| 文件服务（列目录/增删/复制/移动） | `server/src/services/file_service.rs` | 沿用 |
| 缩略图服务（image crate，`.thumbnails/`） | `server/src/services/thumbnail_service.rs` | 沿用，规格改为单档 256px |
| 视频/音乐元数据识别 | `server/src/utils/video_detector.rs`、`music_parser.rs` | 沿用 |
| Dockerfile / docker-compose | `server/Dockerfile`、`deploy/docker-compose.yml` | 沿用并调整（去掉 postgres 容器） |

**废弃**：`users` 表与 `/api/auth/*`、`/api/users` 全部认证代码；`shares` 表与分享 API；PostgreSQL 依赖（改 SQLite）。

### 2.2 来自 my_nvr_app（已迁移至 `mobile/`）

| 资产 | 位置 | 复用方式 |
|------|------|----------|
| WireGuard 隧道管理（wireguard-android tunnel 库 + GoBackend，隧道 wg0） | `mobile/app/src/main/java/me/erguotou/nvr/WireGuardTunnelManager.kt` | 直接复用 |
| Curve25519/Key/KeyPair 密钥工具 | `mobile/.../wireguard/` | 直接复用 |
| VPN 权限申请流程（`VpnService.prepare()` + ActivityResult） | `mobile/.../MainActivity.kt` | 抽取为独立组件 |
| Compose 工程骨架、主题、生物识别门禁（BiometricPrompt） | `mobile/app/` | 沿用 |

**必须废弃/重构的遗留问题**（现状盘点发现的安全与工程问题）：

- `PreferencesManager.kt` 明文 SharedPreferences 存私钥与密码 → 迁移到 EncryptedSharedPreferences/DataStore。
- 网络层全局信任所有证书、WebView 忽略 SSL 错误、`usesCleartextTraffic=true` → 重写为 OkHttp/Retrofit + 可配置的自签证书信任（仅信任服务端证书）。
- `MainActivity.kt` 1366 行单文件承载全部 UI → 拆分为多 Screen + Navigation Compose。
- Frigate 登录 JS 注入 hack → 本期监控空置，不迁移。

---

## 3. 总体架构

### 3.1 架构图

```
┌──────────────────────────── 家庭网络 (WireGuard) ────────────────────────────┐
│                                                                              │
│  ┌─────────────┐   wg0 隧道   ┌───────────────────────────────────────────┐  │
│  │  手机 App    │◄───────────►│  HomeHub Server (Rust + Axum, :8485)      │  │
│  │  (Android)  │  来源IP=身份 │  ├── 文件服务 (统一目录树读写)              │  │
│  │  · 相册      │  (审计日志)  │  ├── 照片服务 (EXIF/GPS/方向/旋转)          │  │
│  │  · NAS 文件  │             │  ├── 任务系统 (缩略图/识别/压缩, 资源受限)   │  │
│  │  · 监控(预留)│             │  ├── 配置服务 (目录/归属标记/忽略规则)       │  │
│  └─────────────┘             │  ├── 身份审计 (WG peer ↔ 隧道IP 映射+日志)  │  │
│                              │  └── Admin API ──► 管理后台 (React Web)   │  │
│  ┌─────────────┐             │        SQLite(单文件) │ 磁盘卷(NAS 路径)    │  │
│  │  PC 浏览器   │◄───────────►└───────────────────────────────────────────┘  │
│  │ 管理后台+相册 │                        WG 网关 (wg show / peer 表)          │
│  └─────────────┘                                                             │
│        WG 内可直接访问 ─────────────►  Frigate NVR (监控，预留对接)             │
└────────────────────── Endpoint: 家庭宽带 DDNS:port (UDP) ─────────────────────┘
```

### 3.2 仓库结构（Monorepo）

```
homehub/
├── docs/            # PRD、架构决策记录（ADR）、API 文档
├── server/          # Rust + Axum 后端（源自 home-nas/backend，已迁入）
├── admin/           # 管理后台 + PC 相册前端（待用 /Users/erguotou/.agents/skills/fe-init 初始化，
│                    #   页面设计用 Ardot (ardot-design-core) 做视觉优化）
├── mobile/          # Android 客户端（源自 my_nvr_app，已迁入）
├── deploy/          # docker-compose.yml、config.yaml、部署脚本
└── README.md
```

### 3.3 技术选型

| 层 | 选型 | 说明 |
|----|------|------|
| 服务端 | Rust 1.85+ + Axum 0.7 + Tokio + SQLx | 沿用 home-nas；锁定文件中的 `oxipng→clap→clap_lex` 需要 edition2024（≥1.85） |
| 数据库 | **SQLite**（SQLx sqlite，单文件 `homehub.db`，WAL 模式） | 轻量易备份；元数据量级（百万行内）完全够用；随 docker 卷挂载持久化 |
| 对象识别 | `ort` crate（ONNX Runtime）+ **三套模型**：YOLOv8n 物体检测、场景分类（风景/室内/食物等，可用 MobileNet 或 CLIP 轻量版）、人脸检测（YOLOv8-face 或 RetinaFace 轻量版） | 人脸本期做到「检测 + 聚类分组」（同脸归组，由用户命名），不做 1:N 比对识别身份 |
| EXIF/方向 | `kamadak-exif` | 读取 EXIF Orientation 与 GPS |
| 图片压缩 | 无损：`oxipng`（PNG）、JPEG 无损变换（mozjpeg/jpegtran 逻辑）；节省率无收益时保留原文件 | 视觉无损，原图保护机制见 §10 建议 A |
| 缩略图 | `image` crate，**单档：最大长/宽 256px** JPEG，存各目录 `.thumbnails/` | 列表流加载 256 档，查看器加载原图 |
| 后台前端 | React + TypeScript（fe-init 初始化，含包管理器检测流程） | 视觉设计通过 Ardot 画布产出并落地 |
| 手机端 | Kotlin + Jetpack Compose + Navigation Compose；OkHttp/Retrofit；Coil | minSdk 30 |
| 手机端地图 | **Android 端用 Android SDK 生态**（osmdroid 或高德 SDK，实施时确认网络可用性） | 服务端只出聚合数据，地图渲染归客户端 |
| PC 端地图 | **Web 端用 Web SDK**（Leaflet + OSM/高德瓦片） | 同上，两端各自选型 |
| 组网 | wireguard-android tunnel 库（GoBackend），沿用 wg0 单隧道 | 密钥本地生成或导入 |

---

## 4. 功能需求

### 4.1 服务端 — 目录与库模型（重构核心）

**统一目录管理**：所有 NAS 目录在管理后台统一登记，形成一张「目录注册表」。每个目录（库）包含：

| 字段 | 说明 |
|------|------|
| 名称 | 展示名，唯一 |
| 本地路径 | 磁盘上的真实路径 |
| **归属标记** | 多选：`album`（相册）/ `video`（视频）/ `music` / `document` / `none`（仅文件管理可见）。一个目录可同时带多个标记（如既是相册又可被文件管理浏览） |
| 忽略目录列表 | 相对路径模式（如 `@eaDir`、`.thumbnails`、`#recycle`、`.stfolder`），命中目录不参与扫描/识别/压缩/相册展示 |
| 启用状态 | 停用的目录不参与任何任务 |

- **App/PC 查相册 = 查询所有带 `album` 标记的目录的集合**（跨目录聚合时间轴/分类等视图）。
- NAS 文件浏览 = 所有登记目录的并集（按目录树呈现），与归属标记无关。
- 配置落盘 `config.yaml`（格式升级为目录注册表，首次启动自动从旧格式迁移），支持热生效。
- 所有数据面请求按 `<目录名>/<相对路径>` 寻址，沿用现有 `resolve_path()` 机制。

### 4.2 服务端 — WireGuard 身份审计（无用户体系的替代）

- 服务端维护 **WG Peer 注册表**：`wg_peers(public_key, name, tunnel_ip, first_seen, last_seen, enabled)`，来源两种方式：
  1. 服务端与 WG 网关同机时，直接解析 `wg show wg0 dump` 自动同步；
  2. 不同机时，由管理后台手工登记「隧道 IP ↔ 设备名」映射。
- **每个数据面请求**根据来源 IP 反查 peer 身份，写入审计日志 `audit_logs(id, peer_id, method, path, status, bytes, created_at)`（异步批量写入，避免拖慢请求）。
- 管理后台可查看：设备列表、每个设备的访问日志与流量统计。
- 审计日志保留策略可配置（默认 90 天），定期清理任务自动执行。

### 4.3 服务端 — 照片/相册服务

#### 4.3.1 照片入库与元数据

- 扫描所有 `album` 标记目录（跳过忽略目录），图片文件（jpg/jpeg/png/webp/gif）登记为照片资产（`photo_assets`）。
- 读取 EXIF：拍摄时间、GPS、方向（Orientation）、尺寸、相机型号。
- **自动方向调整**：按 EXIF Orientation 自动纠正；提供**手动旋转接口**（见 5.2），旋转写回文件（无损 90° 变换优先）并同步重建缩略图。

#### 4.3.2 YOLO 识别与标签归类

- 新照片入库进入识别队列，产出三类标签：
  1. **物体标签**（YOLO 检测：person/dog/car/food...，含置信度）；
  2. **场景标签**（场景分类模型：风景/山/海/日落/室内/夜景...）——用户明确要求支持「风景」归类；
  3. **人脸**（人脸检测产出人脸框 → 感知哈希/嵌入聚类 → `person_groups` 分组；App 端「人物」视图展示各分组，用户可命名分组）。
- 后台可配置：模型路径、各类置信度阈值、最小图片尺寸、排除标签、各模型开关。
- 支持模型升级后全量重跑。

#### 4.3.3 浏览视图（服务端聚合查询接口）

| 视图 | 逻辑 |
|------|------|
| 时间轴 | 按拍摄时间（无 EXIF 则 mtime）分年/月分组，跨 album 目录聚合 |
| 目录树 | 按目录层级聚合（限单目录范围内） |
| 分类 | 按标签聚合（物体+场景混合展示，标签云/列表 + 封面） |
| 人物 | 按人脸分组聚合 |
| 地理位置 | 按 GPS 聚合（经纬度网格聚类）；地图渲染由 Android/PC 各自 SDK 完成，服务端只出聚合点数据 |

#### 4.3.4 任务系统与资源控制（重点）

NAS 文件量级大（十万级+），任务系统设计约束：

- **持久化队列**：SQLite `tasks` 表，重启自动恢复；任务类型：`scan`（目录扫描）、`thumb`（缩略图）、`detect_object`、`detect_scene`、`detect_face`、`compress`、`geo`、`dedup_scan`（全库去重）、`clean_trash`（回收站/原图保留期清理）、`audit_retention`（审计日志清理）。
- **增量优先**：目录扫描记录 mtime/size 指纹，未变化的文件不重复入队；配合文件系统事件监听（`notify` crate，inotify）实时感知新增/修改，避免全量轮询；全量重扫仅手动触发或定时兜底（可配，默认每周一次低峰执行）。
- **资源控制（全部可配）**：
  - 并发度：CPU 密集任务（识别）默认并发 1，IO 密集任务（缩略图/压缩）默认并发 2；
  - 限流：每秒处理文件数上限、单文件超时；推理用 ONNX Runtime 线程数限制（避免吃满 CPU）；
  - **空闲调度**：可配置工作时段（如仅 02:00–08:00 全速跑重任务），白天只处理「用户刚上传的」高优先级任务，保证上传体验；
  - IO 优先级：磁盘操作用低优先级（容器内以并发+限流近似实现）。
- **进度可见**：管理后台任务中心展示各队列深度、速率、失败重试；App 端上传后可看到「处理中 N 张」。

#### 4.3.5 缩略图与无损压缩

- **缩略图**：单档 256px（最大长/宽 256），入 `.thumbnails/`，命名含原图指纹（mtime+size）避免失效。
- **无损压缩**：PNG 走 oxipng、JPEG 走无损重压缩；**仅当节省 ≥3% 且像素不变时原子替换**，否则跳过。**无损压缩不保留原图副本**（像素未变，无回滚价值——这是控制 `.originals/` 体积的关键约束）。
- 服务端定期（可配）兜底补齐缺失缩略图、清理孤儿任务。

#### 4.3.6 数据安全：原图保护 / 回收站 / 去重（v1 确认项）

**原图保护（`.originals/`）**

- **触发面收窄**：仅「破坏性操作」触发——手动旋转写回、未来可能的有损功能。无损压缩不触发。
- 被修改文件的原像素副本存同目录 `.originals/`（副本本身也做无损优化，省 20–30%）。
- **保留期自动清理**（默认 30 天，可配）；管理后台展示 `.originals/` 占用统计；磁盘水位超阈值时暂停保护写入并告警。总开关可配（默认开）。

**回收站/软删除**

- App 与后台的删除操作先把文件移入统一回收站目录（如 `<数据卷>/trash/`，保留原目录结构信息），SQLite 记录 `trashed_at` 与来源路径。
- 回收站支持浏览、还原、彻底删除；默认 30 天后由清理任务自动彻底删除；占用统计在后台可见。
- 相册/文件列表查询默认排除回收站内容。

**去重（双指纹，全局）**

- `file_hash`（字节级 SHA-256）：上传时对**刚上传的源文件**计算并查重（此刻文件尚未被服务端处理），用于精确去重与任务幂等。
- `pixel_hash`（解码后像素哈希）：对无损压缩等字节变化不敏感——已被服务端处理过的照片再次上传时，靠它仍能识别重复。
- 命中重复时提示「已存在（路径）」，用户可选择跳过或仍保留副本。
- **全库去重扫描**：定期任务用 pHash 找跨目录重复/相似照片，产出清单由用户确认后批量移入回收站。

**健康告警**

- 触发条件（可配阈值）：磁盘水位（默认 85%）、任务连续失败（默认 5 次）、SQLite 写入异常、`.originals/`/回收站超限。
- 通知渠道：ntfy（首选，自托管友好）/ Telegram / SMTP 邮件，可多选。
- 告警事件同时记录 SQLite，后台「系统信息」页可查历史。

### 4.4 服务端 — 文件服务（NAS）

- 沿用 `/api/files/:module/*path`：列目录、上传、下载、删除（**软删除，进回收站**）、重命名、复制、移动、新建目录。
- 文件列表返回：名称、类型、大小、修改时间、是否目录；**排序由客户端控制（目录在前、名称字母序）**，服务端支持 `sort=name|mtime|size`。
- 移除用户体系与分享相关代码；数据面信任 WG 内网（建议服务端仅绑定 WG 网段地址或由防火墙限制来源）。

### 4.5 管理后台（Web，React）

认证：单一管理员密码（config.yaml 配置，登录签发 JWT）。页面规划：

| 页面 | 内容 |
|------|------|
| 目录管理 | 目录注册表增删改（名称/路径/归属标记/忽略目录/启停）、各目录统计（文件数/已识别数/压缩节省） |
| 任务中心 | 队列状态（深度/速率/失败）、手动全量重扫、重试失败、资源控制参数（并发/时段/限流） |
| 身份审计 | WG 设备列表、按设备查看访问日志与流量 |
| 相册浏览（PC 端） | 管理后台兼作 PC 相册客户端：时间轴/目录树/分类/人物/地理位置（Leaflet），图片查看与旋转保存，体验对齐 App 端 |
| 全局搜索 | 文件名/标签/时间范围组合搜索（FTS5），覆盖文件与照片 |
| 回收站 | 浏览、还原、彻底删除、占用统计与保留期设置 |
| 监控（预留） | Frigate 地址配置骨架，只读展示 |
| 系统信息 | 版本、磁盘用量、SQLite 大小、任务健康度、告警历史与通知渠道配置 |

### 4.6 手机端（Android App）

#### 4.6.1 组网连接（参考 my_nvr_app）

- 首次引导：生成/导入 WG 密钥对 → 填写/导入隧道配置 → VPN 权限 → 连接测试（服务端探活）。
- 复用 `WireGuardTunnelManager`（wg0，GoBackend）；支持配置导入/导出（JSON，SAF）。
- 安全整改：凭据 EncryptedSharedPreferences；生物识别门禁（沿用 BiometricPrompt）。

#### 4.6.2 相册模块（核心）

- **浏览视图**：时间轴（年/月分组瀑布流 + 快速滚动定位）、目录树、分类（标签）、人物（人脸分组）、地理位置（Android 端地图 SDK 渲染聚合点）。
- **查看器**：全屏、双击/捏合缩放、滑动切换、EXIF 信息面板（时间/地点/相机/大小）。
- **图片旋转调整**：查看器内旋转 90° 并保存，调用服务端旋转接口，完成后刷新。
- **上传**：系统相册/文件选择器多选 → 选目标目录（目录选择器，可新建）→ 可勾选「上传完成后删除本地」→ WorkManager 后台分片上传（断点续传，进度通知）→ 服务端落盘自动进入识别/缩略图流水线。

#### 4.6.3 NAS 文件模块

- 目录树展示：**目录在上，文件按名称字母序**（可切换升降序）；面包屑导航。
- 操作：新建目录、上传任意文件、下载、重命名、移动、复制、删除（二次确认）、多选批量操作。
- 预览：图片直接预览、文本简易预览；音视频暂用系统 Intent 打开。

#### 4.6.4 监控模块（空置）

- 底部 Tab 保留「监控」入口，占位「建设中」+ Frigate 地址配置项（保存不连接）。

---

## 5. 接口设计（概要）

> 详细 OpenAPI 文档在 M1 阶段输出到 `docs/api.md`。

### 5.1 管理后台 API（`/api/admin/*`，需管理员 JWT）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/admin/login` | 管理员密码登录 |
| GET/POST/PUT/DELETE | `/api/admin/dirs` | 目录注册表 CRUD（含归属标记、忽略目录） |
| GET | `/api/admin/peers` | WG 设备列表；`GET /api/admin/peers/:id/logs` 访问日志 |
| GET | `/api/admin/tasks` | 任务队列状态；`POST /api/admin/tasks/rescan` 全量重扫 |
| PUT | `/api/admin/settings` | 任务资源参数、YOLO 参数、审计保留期 |
| GET | `/api/admin/stats` | 各目录统计与磁盘用量 |

### 5.2 数据面 API（WG 内网，无用户认证）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/photos/timeline` | 时间轴分组（跨 album 目录聚合，`?group=month`） |
| GET | `/api/photos/tree` | 目录树聚合 |
| GET | `/api/photos/tags` | 分类聚合 |
| GET | `/api/photos/people` | 人物（人脸分组）聚合 |
| GET | `/api/photos/geo` | 地理聚合点 |
| GET | `/api/photos/list` | 按条件列照片（目录/标签/人物/时间段/分页） |
| GET | `/api/search?q=&type=&from=&to=` | **全局搜索**（文件名 FTS5 / 标签 / 时间范围，覆盖文件与照片） |
| GET | `/api/media/:module/photo/:id` | 原图 / `?size=thumb`（256 缩略图） |
| POST | `/api/photos/:module/:id/rotate` | **图片旋转调整接口（App/PC 用）**，body: `{angle: 90\|180\|270}` |
| GET | `/api/trash` | 回收站列表；`POST /api/trash/:id/restore` 还原；`DELETE /api/trash/:id` 彻底删除 |
| GET | `/api/files/:module/*path` | 列目录/下载（沿用） |
| POST | `/api/files/:module/*path` | 上传（multipart，支持分片续传） |
| PATCH/DELETE | `/api/files/:module/*path` | 重命名/移动/复制/删除 |
| POST | `/api/mkdir/:module` | 新建目录（沿用） |

### 5.3 数据模型（SQLite，核心表）

```sql
-- 目录注册表（config.yaml 为准，SQLite 镜像加速查询）
dirs(id, name, path, marks, ignore_rules, enabled)
-- 照片资产（file_hash: 字节级 SHA-256，上传时对源文件计算；pixel_hash: 解码像素哈希，对无损压缩不敏感）
photo_assets(id, dir_id, rel_path, fingerprint, file_hash, pixel_hash, size, mtime, taken_at,
             width, height, orientation, gps_lat, gps_lng, status)
             -- status: ok | trashed | processing
-- 标签（物体 + 场景）
photo_tags(photo_id, tag, kind, confidence)      -- kind: object | scene
-- 人脸
faces(id, photo_id, box_x, box_y, box_w, box_h, group_id)
person_groups(id, name, representative_face_id)  -- 用户可命名
-- 任务
tasks(id, kind, payload, priority, status, error, created_at, updated_at)
-- 回收站
trash_entries(id, dir_id, rel_path, trash_path, trashed_at, purged_due)
-- 身份审计
wg_peers(id, public_key, name, tunnel_ip, first_seen, last_seen, enabled)
audit_logs(id, peer_id, method, path, status, bytes, created_at)
-- 告警
alerts(id, level, kind, message, created_at, resolved_at)
```

- **废弃**：`users`、`shares` 表及全部关联代码。

---

## 6. 非功能需求

| 项 | 要求 |
|----|------|
| 安全 | 私钥/凭据 Android 端加密存储；数据面仅暴露于 WG 内网；后台 HTTPS（自签可配）；删除操作 App 端二次确认；审计日志可追溯每个 WG 设备的访问 |
| 性能 | 缩略图列表页首屏 < 1s（局域网）；万级照片时间轴流畅（分页 + 256px 缩略图 + 端侧缓存）；任务并发/限流可配，白天不影响上传体验 |
| 可靠 | 上传分片断点续传；任务队列持久化、重启恢复；文件替换原子操作；SQLite WAL 模式 + 定期备份 `homehub.db` |
| 部署 | docker-compose 单容器（server）+ 数据卷；config.yaml 兼容旧格式自动迁移；模型文件目录挂载 |
| 兼容 | Android 11+（minSdk 30）；现有数据目录结构不破坏（`.thumbnails/` 沿用） |

---

## 7. 里程碑规划

| 阶段 | 内容 | 交付物 |
|------|------|--------|
| **M0 仓库整合**（已完成） | 新建 homehub 仓库，迁入 server/mobile 代码，输出 PRD | 本文档 + 可编译基线 |
| **M1 服务端核心** | 移除用户/分享代码；SQLite 接入；目录注册表（归属标记/忽略目录）；照片扫描 + EXIF；任务系统（含资源控制）；回收站/去重（双指纹）基础；数据面 API | server 跑通相册/文件 API |
| **M2 管理后台** | fe-init 初始化 admin；Ardot 设计目录管理/任务中心/身份审计/PC 相册页并落地 | 可用管理后台（含 PC 相册） |
| **M3 手机端基础** | WG 模块组件化；OkHttp/Retrofit 网络层；导航框架；相册时间轴/目录树 + 查看器；NAS 文件浏览 | App 可连、可看 |
| **M4 智能流水线** | YOLO 物体 + 场景 + 人脸检测聚类；分类/人物/地理视图；旋转接口（含 .originals/ 原图保护）；上传流程（选目录/删本地/断点续传/去重提示）；无损压缩任务；FTS5 搜索；健康告警 | 对标 immich/mtphoto 核心体验 |
| **M5 打磨与监控预留** | 生物识别门禁、监控占位页、部署文档、性能调优 | v1.0 |

---

## 8. 已确认决策记录

| 问题 | 决策（2026-09-04） |
|------|--------------------|
| 数据库 | SQLite（WAL），不用 PostgreSQL |
| 缩略图 | 单档 256px |
| 分享 | 本期不做，代码移除 |
| HEIC | 不支持 |
| 旧 RN 客户端 | 废弃 |
| 监控 | 保留入口，后续再定方案 |
| WG 身份 | 存库作审计日志，不做权限 |
| 目录模型 | 统一目录管理 + 归属标记，相册 = album 标记目录集合 |
| YOLO | 物体 + 风景（场景分类）+ 人脸（检测聚类） |
| 地图 | Android 与 PC 各用各自 SDK |
| 地图 SDK 选型 | **高德**：Android 用高德地图 SDK（Key 由用户在 App 内填写，运行时注入）；PC 用 Leaflet + 高德瓦片。两端各自做 WGS-84 → GCJ-02 换算（§9 问题 1 已定） |
| SQLite 备份 | 纳入 v1（§6 可靠）：`VACUUM INTO` 定期快照，默认 24 小时一次、保留 7 份，可在管理后台配置与手动触发 |
| 原图保护 | 纳入 v1；仅破坏性操作（旋转/未来有损）触发，无损压缩不留原图；保留期 30 天可配 |
| 回收站 | 纳入 v1；软删除 + 30 天自动清理 |
| 搜索 | 纳入 v1（FTS5 文件名/标签/时间）；AI 语义搜索列二期 |
| 去重 | 纳入 v1；全局去重，双指纹（file_hash 对源文件、pixel_hash 对已处理文件）+ pHash 全库扫描 |
| 健康告警 | 纳入 v1；ntfy/Telegram/邮件，含磁盘水位/任务失败/SQLite 异常 |
| 其余建议（回忆/备份/WebDAV/视频转码） | 二期再加 |

---

## 9. 待确认问题（Open Questions）

1. ~~**地图 SDK 具体选型**：Android 端 osmdroid（离线友好）还是高德 SDK（国内体验好、需 Key）？Web 端 Leaflet + 何种瓦片源？取决于家中网络环境。~~
   **已定（2026-09-05）**：选**高德**。Android 用高德地图 Android SDK（Key 由用户在「设置 → 地图」填写，
   存 EncryptedSharedPreferences，运行时通过 `MapsInitializer.setApiKey()` 注入，不写进 APK）；
   PC 用 Leaflet + 高德公开瓦片（无需 Key，可切换路网/影像）。代价是两端都要维护一份
   WGS-84 → GCJ-02 换算。
2. **人脸聚类实现深度**：先「检测 + 感知哈希聚类」轻量方案，效果不满意再引入 ArcFace 嵌入聚类？建议 M4 先做轻量版。
3. **服务端与 WG 网关是否同机部署**：决定 WG 身份是自动同步（`wg show dump`）还是手工登记映射。
4. **任务工作时段默认值**：低峰全速时段默认定在 02:00–08:00 是否合适？
5. **AI 语义搜索的技术预研时机**：CLIP 向量化 + SQLite vec0 向量扩展的可行性，建议二期启动前做一次 spike（不影响 v1）。

---

## 10. 二期功能池（v1 不做，后续排期）

- **A. AI 语义搜索**：自然语言查图（如「海边的日落」），基于 CLIP 式图文嵌入 + 向量检索（SQLite vec0 / Qdrant 待定），PC 端与 Android 端同一套 API。
- **B. 精选/回忆**：基于时间（「去年今日」「每周精选」）与标签组合的推荐卡片。
- **C. 照片去重清理增强**：pHash 相似（非完全重复）照片的聚类展示与批量清理建议。
- **D. 数据备份策略**：重要目录定期 rclone/restic 到外部存储，后台配置 + 任务化。
- **E. WebDAV 协议层**：让电视、电脑直接挂载访问 NAS 文件（只读起步）。
- **F. 视频转码预览**：`video` 标记目录 ffmpeg 抽帧缩略图 + HLS 转码流播（immich 式体验），工作量大，单独排期。
