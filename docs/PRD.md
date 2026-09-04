# HomeHub 需求文档（PRD）

- **版本**: v0.1（草案）
- **日期**: 2026-09-04
- **状态**: 待评审
- **来源**: 由 `home-nas`（Rust NAS 服务端）与 `my_nvr_app`（Android WireGuard/NVR 客户端）合并整合而来

---

## 1. 项目背景与目标

### 1.1 背景

现有两个独立项目：

| 项目 | 定位 | 技术栈 | 现状 |
|------|------|--------|------|
| home-nas | 家用 NAS 服务端 + RN 客户端 | Rust + Axum 0.7 / PostgreSQL / SQLx；React Native (Expo) | 已具备文件管理、缩略图、分享、用户体系 |
| my_nvr_app | 手机端 NVR 客户端 | Android 原生 Kotlin + Compose，minSdk 30 | 已具备 WireGuard 隧道连接 + X5 WebView 加载 Frigate |

两个项目各自维护，功能重叠不足、体验割裂：NAS 客户端没有组网能力（离开家庭网络不可用），NVR 客户端没有相册/文件能力。本项目的目标是将两者合并为**一个独立项目 HomeHub**，以 WireGuard 为统一组网与准入手段，提供「相册 + NAS 文件 + 监控」三位一体的自托管家庭服务。

### 1.2 核心目标

1. **组网即准入**：家庭网络连接由 WireGuard 完成，服务端不再做用户体系（用户由 WG 控制），手机端连上隧道即可使用全部功能。
2. **一套服务端**：基于 `home-nas/backend`（Rust + Axum）重构为统一后台，提供管理后台页面（Web）。
3. **一个手机 App**：基于 `my_nvr_app` 的 Android 工程重构，包含相册、NAS 文件、监控（预留）三大模块。
4. **智能照片管理**：对标 immich / mtphoto 的核心能力——YOLO 识别打标、缩略图、无损压缩、多维度浏览（时间轴/目录树/分类/地理位置）。

### 1.3 非目标（本轮明确不做）

- 不做多用户/多租户（由 WG 网络层天然隔离设备与家庭）。
- 监控模块本期仅预留入口与配置页骨架，不实现播放（后续对接 Frigate 或自研录像）。
- 不做 iOS 端（沿用 Android 原生路线）。
- 不做公网穿透本身（WG Endpoint 由用户自行准备，如家庭宽带的 DDNS + 端口转发）。

---

## 2. 现状资产盘点（可复用部分）

### 2.1 来自 home-nas（已迁移至 `server/`）

| 资产 | 位置 | 复用方式 |
|------|------|----------|
| Axum 服务骨架、路由组织 | `server/src/main.rs` | 直接沿用 |
| 虚拟路径→本地路径解析（`<子库>/<相对路径>`） | `server/src/config/mod.rs` | 沿用并扩展（增加忽略目录配置） |
| 文件服务（列目录/增删/复制/移动） | `server/src/services/file_service.rs` | 沿用 |
| 缩略图服务（image crate，300px JPEG，`.thumbnails/`） | `server/src/services/thumbnail_service.rs` | 沿用并纳入任务队列 |
| 视频/音乐元数据识别 | `server/src/utils/video_detector.rs`、`music_parser.rs` | 沿用 |
| PostgreSQL + SQLx 迁移（users/shares 表） | `server/migrations/` | users 表废弃，shares 视需要保留 |
| Dockerfile / docker-compose | `server/Dockerfile`、`deploy/docker-compose.yml` | 沿用并调整 |

### 2.2 来自 my_nvr_app（已迁移至 `mobile/`）

| 资产 | 位置 | 复用方式 |
|------|------|----------|
| WireGuard 隧道管理（wireguard-android tunnel 库 + GoBackend，隧道 wg0） | `mobile/app/src/main/java/me/erguotou/nvr/WireGuardTunnelManager.kt` | 直接复用 |
| Curve25519/Key/KeyPair 密钥工具 | `mobile/.../wireguard/` | 直接复用 |
| VPN 权限申请流程（`VpnService.prepare()` + ActivityResult） | `mobile/.../MainActivity.kt` | 抽取为独立组件 |
| Compose 工程骨架、主题、生物识别门禁（BiometricPrompt） | `mobile/app/` | 沿用 |

**必须废弃/重构的遗留问题**（现状盘点时发现的安全与工程问题）：

- `PreferencesManager.kt` 使用明文 SharedPreferences 存私钥与密码 → 迁移到 EncryptedSharedPreferences/DataStore。
- 网络层全局信任所有证书（`sslBypassContext`）、WebView 忽略 SSL 错误、`usesCleartextTraffic=true` → 重写为 OkHttp/Retrofit + 可配置的自签证书信任（CertificatePinner / 自定义 TrustManager，仅信任服务端证书）。
- `MainActivity.kt` 1366 行单文件承载全部 UI → 拆分为多 Screen + Navigation Compose。
- Frigate 登录 JS 注入 hack → 本期监控空置，不迁移。

---

## 3. 总体架构

### 3.1 架构图

```
┌──────────────────────────── 家庭网络 (WireGuard) ────────────────────────────┐
│                                                                              │
│  ┌─────────────┐   wg0 隧道    ┌──────────────────────────────────────────┐  │
│  │  手机 App    │◄────────────►│  HomeHub Server (Rust + Axum, :8485)     │  │
│  │  (Android)  │              │  ├── 文件服务 (photo/nas 文件读写)         │  │
│  │  · 相册      │              │  ├── 照片服务 (EXIF/GPS/方向/旋转)         │  │
│  │  · NAS 文件  │              │  ├── 任务系统 (缩略图/YOLO/压缩/索引)      │  │
│  │  · 监控(预留)│              │  ├── 配置服务 (库/路径/忽略目录)           │  │
│  └─────────────┘              │  └── Admin API ──► 管理后台 (React Web)  │  │
│       │                       │            PostgreSQL │ 磁盘卷(NAS 路径)  │  │
│       │  WG 内可直接访问        └──────────────────────────────────────────┘  │
│       └────────────────────────────►  Frigate NVR (监控，预留对接)            │
│                                                                              │
└────────────────────── Endpoint: 家庭宽带 DDNS:port (UDP) ─────────────────────┘
```

### 3.2 仓库结构（Monorepo）

```
homehub/
├── docs/            # PRD、架构决策记录（ADR）
├── server/          # Rust + Axum 后端（源自 home-nas/backend，本轮已迁入）
├── admin/           # 管理后台前端（待用 /Users/erguotou/.agents/skills/fe-init 初始化，
│                    #   页面设计用 Ardot (ardot-design-core) 做视觉优化）
├── mobile/          # Android 客户端（源自 my_nvr_app，本轮已迁入）
├── deploy/          # docker-compose.yml、config.yaml、部署脚本
└── README.md
```

### 3.3 技术选型

| 层 | 选型 | 说明 |
|----|------|------|
| 服务端 | Rust 1.83 + Axum 0.7 + Tokio + SQLx | 沿用 home-nas |
| 数据库 | PostgreSQL 16 | docker-compose 部署 |
| 对象识别 | `ort` crate（ONNX Runtime）+ YOLOv8n/YOLO11n 检测模型（.onnx） | 标签 = 检测类别 + 置信度阈值过滤；模型文件放 `server/models/`，可在后台配置路径 |
| EXIF/方向 | `kamadak-exif` | 读取 EXIF Orientation 与 GPS |
| 图片压缩 | 无损：`oxipng`（PNG）、JPEG 无损变换（`jpegtran` 逻辑或 mozjpeg 绑定）；压缩率无收益时保留原文件 | 目标：视觉无损 |
| 缩略图 | 现有 `image` crate 方案，扩展多尺寸（256/1024） | 存放于各库目录 `.thumbnails/` |
| 管理后台 | React + TypeScript（fe-init 初始化，含检测包管理器流程） | 视觉设计通过 Ardot 画布产出并落地 |
| 手机端 | Kotlin + Jetpack Compose + Navigation Compose；OkHttp/Retrofit；Coil（图片加载）；Compose 自研 zoomable（双击/捏合放大） | minSdk 30 |
| 组网 | wireguard-android tunnel 库（GoBackend），沿用 wg0 单隧道 | 密钥本地生成或导入 |

---

## 4. 功能需求

### 4.1 服务端 — 管理后台（Web）

管理后台面向**家庭管理员**，通过浏览器访问（WG 内网或本机）。认证方式：**单一管理员密码**（config.yaml 配置，登录后签发 JWT），不设多用户。

#### 4.1.1 NAS 配置管理（对应现 config.yaml 的 `apps` 段，可视化）

- 管理多个「库」（Library），每个库含：名称、类型（`album` / `files` / 预留 `videos` `music` `documents`）、本地路径（磁盘目录）、启用状态。
- 每个库可配置**忽略目录列表**（如 `@eaDir`、`.thumbnails`、`#recycle` 等）：这些目录不参与自动整理（不扫描、不打标、不压缩、不出现在相册中），但文件浏览中仍可见（可配置是否隐藏）。
- 配置读写落盘 `config.yaml`（保持与现格式兼容，平滑迁移），修改后热生效（无需重启）。
- 展示各库的统计信息：文件数、已生成缩略图数、已识别数、压缩节省空间。

#### 4.1.2 监控配置（预留）

- 页面骨架：Frigate 地址、凭据占位、摄像头列表占位。本期只读展示，不做实际连接。

#### 4.1.3 任务中心

- 展示后台任务队列状态：待处理/处理中/失败数量，按类型（缩略图、YOLO 识别、压缩、索引）过滤。
- 支持手动触发全量重扫、重试失败任务。
- 展示 YOLO 模型加载状态与路径配置。

#### 4.1.4 系统信息

- 服务版本、磁盘用量（各库路径的容量）、WG 接口状态（若服务端部署在 WG 网关上，只读展示）。

### 4.2 服务端 — 照片/相册服务

#### 4.2.1 照片入库与元数据

- 对 `album` 类型库进行目录扫描，发现图片文件（jpg/jpeg/png/heic/webp/gif）即登记为「照片资产」（数据库表 `photo_assets`）。
- 读取并存储 EXIF：拍摄时间、GPS 经纬度（反解出地理位置名，可后续接反向地理编码）、方向（Orientation）、尺寸、相机型号。
- **自动方向调整**：根据 EXIF Orientation 在服务端生成时自动纠正；同时提供**手动旋转接口**供 App 调用（见 5.2 API），旋转结果写入图片文件并同步更新缩略图（无损 90° 变换优先，有损仅当格式不支持时兜底）。

#### 4.2.2 YOLO 识别与标签归类

- 新照片入库后进入识别队列，YOLO 推理产出标签（如 person, dog, car, food...），连同置信度写入 `photo_tags`。
- 每个「分类」= 标签；App 端按标签聚合浏览。
- 后台可配置：模型路径、置信度阈值、最小图片尺寸（过小的跳过）、排除标签。
- 同一目录内识别结果支持重新识别（模型升级后全量重跑）。

#### 4.2.3 浏览视图（服务端提供聚合查询接口）

| 视图 | 逻辑 |
|------|------|
| 时间轴 | 按拍摄时间（无 EXIF 则文件 mtime）分年/月分组，返回分组 + 封面 |
| 目录树 | 按库内目录层级聚合（与文件浏览一致） |
| 分类 | 按标签聚合，返回标签 + 数量 + 代表封面 |
| 地理位置 | 按照片 GPS 聚合（粗糙网格聚类，如保留 2 位小数经纬度分组），地图上展示数量点 |

#### 4.2.4 缩略图与无损压缩（定期任务）

- **缩略图**：入库/上传后生成 256px（列表用）与 1024px（查看用）两档，存放 `.thumbnails/`；定期任务兜底补齐缺失缩略图。
- **内容识别**：YOLO 队列消费（见 4.2.2）。
- **无损压缩**：定期任务扫描可优化文件——PNG 走 oxipng 重压缩、JPEG 走无损重压缩/优化 Huffman 表；**仅当体积有实质节省（默认 ≥3%）且像素数据不变时替换原文件**，否则跳过；替换前写临时文件原子替换。目的对标 mtphoto：省空间但完全不影响画质。
- 任务系统实现：进程内 tokio 队列 + `tasks` 表持久化（重启可恢复），并发度可配置（默认 2）。

### 4.3 服务端 — 文件服务（NAS）

- 沿用现有 `/api/files/:module/*path` 能力：列目录、上传、下载、删除、重命名、复制、移动、新建目录。
- **移除用户体系**：`/api/auth/*`、`/api/users` 下线；管理员密码仅用于管理后台登录，数据面 API 信任 WG 内网（部署建议：服务端仅监听 WG 网段地址或由防火墙限制来源）。
- 文件列表接口返回：名称、类型、大小、修改时间、是否目录；**排序规则由客户端控制（目录在前、名称字母序）**，服务端按需支持 `sort=name|mtime|size` 参数。

### 4.4 手机端（Android App）

#### 4.4.1 组网连接（参考 my_nvr_app）

- 首次进入引导：生成/导入 WG 密钥对 → 填写或导入隧道配置（Address/DNS/Peer/Endpoint/AllowedIPs/Keepalive）→ 申请 VPN 权限 → 连接测试（对服务端 HTTP 探活）。
- 复用 `WireGuardTunnelManager`（wg0，GoBackend），状态以 Compose State 暴露。
- 支持配置导入/导出（JSON，SAF），支持多 Profile 预留（本期单隧道）。
- 安全整改：配置与凭据存 EncryptedSharedPreferences；App 进入支持生物识别门禁（沿用现有 BiometricPrompt）。

#### 4.4.2 相册模块（功能丰富的核心模块）

- **浏览视图**（对应服务端 4.2.3）：
  - 时间轴：年/月分组瀑布流，悬停快速滚动定位；
  - 目录树：文件夹维度浏览库内照片；
  - 分类：标签云/标签列表 → 点进查看该标签照片；
  - 地理位置：地图（可用 WebView + 简单地图源或 osmdroid）按聚合点浏览。
- **查看器**：全屏查看，支持双击/捏合缩放、左右滑动切换、旋转查看（临时角度）、查看 EXIF 信息面板（时间/地点/相机/大小）。
- **图片旋转调整**：在查看器/详情中可将照片旋转 90° 并「保存」，调用服务端旋转接口，保存后刷新缩略图。
- **上传**：
  - 从系统相册/文件选择器多选本机照片 → 选择目标库与目标目录（目录选择器，可新建目录）→ 可勾选「上传完成后删除本地文件」→ 后台上传（WorkManager，进度通知）。
  - 上传完成（服务端 2xx）后按勾选删除本地。
- **上传后自动化**：服务端文件落盘扫描发现新照片 → 自动进入缩略图 + YOLO 识别 + 压缩流水线（App 端无需触发，只需展示任务进度可选）。

#### 4.4.3 NAS 文件模块

- 目录树展示：**目录在上，文件按名称字母序排列**（升降序可切换）；面包屑导航。
- 常见操作：新建目录、上传文件（任意类型）、下载到本机、重命名、移动、复制、删除（删除需二次确认）、多选批量操作。
- 文件预览：图片直接预览、文本简易预览；视频/音乐暂用系统 Intent 打开（后续迭代内置播放器）。

#### 4.4.4 监控模块（空置）

- 底部 Tab 保留「监控」入口，页内展示「功能建设中」占位 + Frigate 地址配置项（保存但不连接）。

---

## 5. 接口设计（概要）

> 详细 OpenAPI 文档在 M1 阶段输出到 `docs/api.md`。以下为关键接口清单。

### 5.1 管理后台 API（`/api/admin/*`，需管理员 JWT）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/admin/login` | 管理员密码登录 |
| GET/PUT | `/api/admin/config` | 读写 NAS 配置（库列表、路径、忽略目录、任务参数、YOLO 参数） |
| GET | `/api/admin/tasks` | 任务队列状态；`POST /api/admin/tasks/rescan` 触发重扫 |
| GET | `/api/admin/stats` | 各库统计与磁盘用量 |

### 5.2 数据面 API（WG 内网，无用户认证）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/photos/:module/timeline` | 时间轴分组（`?group=month`） |
| GET | `/api/photos/:module/tree` | 目录树聚合 |
| GET | `/api/photos/:module/tags` | 分类聚合 |
| GET | `/api/photos/:module/geo` | 地理聚合 |
| GET | `/api/photos/:module/list` | 按条件列照片（目录/标签/时间段/分页） |
| GET | `/api/media/:module/photo/:id` | 原图 / `?size=thumb\|preview` 缩略图 |
| POST | `/api/photos/:module/:id/rotate` | **图片旋转调整接口（App 用）**，body: `{angle: 90\|180\|270}` |
| GET | `/api/files/:module/*path` | 列目录/下载（沿用） |
| POST | `/api/files/:module/*path` | 上传（multipart） |
| PATCH/DELETE | `/api/files/:module/*path` | 重命名/移动/复制/删除 |
| POST | `/api/mkdir/:module` | 新建目录（沿用） |

### 5.3 数据模型（核心表迁移）

- 删除：`users`。
- 保留：`shares`（可选）。
- 新增：
  - `photo_assets(id, module, rel_path, size, mtime, taken_at, width, height, orientation, gps_lat, gps_lng, status)`；
  - `photo_tags(photo_id, tag, confidence)`（索引 tag）；
  - `tasks(id, kind, payload, status, error, created_at, updated_at)`。

---

## 6. 非功能需求

| 项 | 要求 |
|----|------|
| 安全 | 私钥/凭据 Android 端加密存储；服务端数据面仅暴露于 WG 内网；后台 HTTPS（自签可配）；删除操作 App 端二次确认 |
| 性能 | 缩略图列表页首屏 < 1s（局域网）；万级照片库时间轴滚动流畅（分页 + 缩略图缓存）；任务并发可配置，磁盘 IO 限速可配 |
| 可靠 | 上传支持断点续传（分片或重试）；任务队列持久化，服务重启自动恢复；文件替换原子操作 |
| 部署 | docker-compose 一键部署（postgres + server）；`config.yaml` 兼容旧格式并自动迁移；模型文件目录挂载 |
| 兼容 | Android 11+（minSdk 30）；现有 home-nas 的数据目录结构不破坏（`.thumbnails/` 沿用） |

---

## 7. 里程碑规划

| 阶段 | 内容 | 交付物 |
|------|------|--------|
| **M0 仓库整合**（本轮） | 新建 homehub 仓库，迁入 server/mobile 代码，输出 PRD | 本文档 + 可编译基线 |
| **M1 服务端核心** | 移除用户体系；库/忽略目录配置模型；照片扫描 + EXIF；任务系统骨架；新 API（5.2 全量） | server 可跑通相册/文件 API |
| **M2 管理后台** | fe-init 初始化 admin 工程；Ardot 设计 NAS 配置/任务中心页面并落地；对接 admin API | 可用管理后台 |
| **M3 手机端基础** | 重构 WG 模块为独立组件；网络层换 OkHttp/Retrofit；导航框架；相册时间轴/目录树 + 查看器；NAS 文件浏览 | App 可连、可看 |
| **M4 智能流水线** | YOLO 推理集成（ort）；分类/地理视图；旋转保存接口；上传流程（选目录/删本地/WorkManager）；无损压缩任务 | 对标 immich/mtphoto 核心体验 |
| **M5 打磨与监控预留** | 生物识别门禁、监控占位页、部署文档、性能调优 | v1.0 |

---

## 8. 待确认问题（Open Questions）

1. **YOLO 模型选择**：默认 YOLOv8n 检测模型是否满足预期标签粒度？是否需要额外的人脸识别/场景分类（CLIP）作为二期？
2. **HEIC 支持**：iPhone 照片导入的 HEIC 是否需要服务端转码为 JPEG？（影响解码库选择 `libheif`）
3. **地图源**：地理位置视图的地图瓦片来源（高德/OSM/osmdroid 离线）需要确认网络环境可用性。
4. **旧 home-nas RN 客户端**：确认废弃，不再迁入本仓库。
5. **分享功能**：home-nas 现有公开分享链接能力是否保留？
6. **监控最终方案**：后续是继续 WebView 套 Frigate，还是拉流自研（影响 M5 之后规划）。
