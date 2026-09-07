# HomeHub

家庭自托管一体化服务：**相册（智能照片管理）+ NAS 文件 + 监控（预留）**，以 WireGuard 作为统一组网与准入手段。

由 `home-nas`（Rust 服务端）与 `my_nvr_app`（Android WireGuard/NVR 客户端）合并整合而来。

## 仓库结构

```
homehub/
├── docs/     # 需求文档 PRD、API 文档
├── server/   # Rust + Axum + SQLite 服务端（源自 home-nas/backend）
├── admin/    # 管理后台 + PC 相册前端（React + TS + Vite）
├── mobile/   # Android 客户端（Kotlin + Compose，源自 my_nvr_app）
└── deploy/   # docker-compose.yml、config.yaml
```

## 设计要点

- **组网即准入**：服务端无用户体系，连上 WireGuard 即可使用；每个请求按来源 IP 归属到 WG peer 并写入审计日志。
- **统一目录管理 + 归属标记**：目录注册表登记所有 NAS 路径，标记 `album/video/music/document` 决定它出现在哪些模块；相册 = 所有 `album` 目录的聚合。
- **任务化**：扫描、缩略图、识别、压缩都走持久化队列，支持并发/限流/工作时段控制，白天不影响上传体验。
- **数据安全**：软删除进回收站、破坏性操作归档 `.originals/`、上传双指纹去重。

## 快速开始

### 服务端

```bash
cp deploy/config.yaml server/config.yaml   # 按实际路径修改 dirs
cd server && cargo run --release           # 监听 0.0.0.0:8485
```

或使用容器（含持久化数据卷）：

```bash
cd deploy && docker compose up -d
```

> **YOLO 物体/场景/人脸识别**：Docker 镜像已内置 ONNX Runtime（`--features onnx`），把三个模型文件放到数据卷的 `models/` 目录（相对路径基于 `DATA_DIR` 解析）后重启即可：
>
> 1. `models/yolov8n.onnx` — 物体检测（[ultralytics 导出](https://docs.ultralytics.com/integrations/onnx/)，COCO 80 类）；
> 2. `models/scene-classification.onnx` — 场景/风景分类（任意 ImageNet 分类器导出 ONNX，标签文件可选）；
> 3. `models/yolov8n-face.onnx` — 人脸检测（如 [yolov8-face](https://github.com/danielsyahputra/yolov8-face)）。
>
> 路径与阈值在 `config.yaml` 的 `runtime.ml.*` 配置；模型缺失时服务端回落 stub 并在日志给出具体路径提示。本地源码构建请加 `--features onnx`（需要 `libssl-dev`，首次会下载 ONNX Runtime 预编译库）。

> **管理员凭据**：`config.yaml` 不设密码（或留旧默认值）时，服务端首次启动会自动生成强随机密码并回写配置文件，同时在日志中以 WARN 级别打印——请从日志获取初始密码。

### 管理后台

```bash
cd admin && npm install && npm run dev     # http://localhost:5173
npm run build                              # 产物由服务端通过 ADMIN_DIST 托管
```

### Android 客户端

用 Android Studio 打开 `mobile/`，或命令行：

```bash
cd mobile && ./gradlew :app:assembleDebug
```

首次启动会进入引导：生成/导入 WireGuard 密钥 → 填写隧道与服务器信息 → 授权 VPN → 连接测试。

## 文档

- [需求文档 PRD](docs/PRD.md)
- [API 文档](docs/api.md)
- [服务端说明](server/README.md)
- [管理后台说明](admin/README.md)
- [Android 客户端说明](mobile/README.md)

## 版本状态

PRD 中的 M1（服务端核心）、M2（管理后台）、M3（手机端基础）、M4（智能流水线）、M5（打磨与监控预留）已落地：

| 能力 | 状态 |
|------|------|
| SQLite、目录注册表、照片扫描 + EXIF、任务系统、回收站、去重、FTS5 搜索、WG 审计、健康告警、**SQLite 定期备份** | 完成 |
| 管理后台（目录/任务/审计/相册/搜索/回收站/监控/系统） | 完成 |
| Android：WG 引导、Compose 导航、相册时间轴/目录/分类/人物/地点、查看器 + 旋转、NAS 文件、后台上传 | 完成 |
| 断点续传上传（分片） | 完成 |
| YOLO 物体/场景/人脸 | Docker 镜像内置 ONNX；模型文件放 `models/` 即启用，缺失时回落 stub |
| 监控播放 | 预留入口，不实现 |
| 地图渲染（Android / PC） | Android 用高德 SDK、PC 用 Leaflet + 高德瓦片；两端各自把 WGS-84 转 GCJ-02 |

地图需要**高德 Key**：Android 在 App「设置 → 地图」填写（存加密存储，运行时注入 SDK）；
PC 端直接取高德公开瓦片，无需 Key。详见 [mobile/README.md](mobile/README.md#地图高德)。
