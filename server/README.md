# HomeHub Server

Rust + Axum 实现的 HomeHub 服务端：相册（智能照片管理）+ NAS 文件 + 监控（预留）。
准入由 WireGuard 网络层负责，服务端不实现用户体系，只做 **WG 身份审计**。

## 快速开始

```bash
# 1) 准备配置（见 deploy/config.yaml）
cp deploy/config.yaml config.yaml

# 2) 运行（默认读取 ./config.yaml，监听 0.0.0.0:8485）
cargo run --release

# 环境变量
#   CONFIG_PATH  配置文件路径
#   BIND_ADDR    监听地址
#   ADMIN_DIST   管理后台静态资源目录（可选）
#   RUST_LOG     日志级别
```

容器化部署见 `deploy/docker-compose.yml`（单容器 + 数据卷）。

## 模块

```
server/src/
├── config/     配置模型（目录注册表 + 运行时参数），兼容旧 home-nas 的 apps: 格式并自动迁移
├── db/         SQLite 连接池、WAL、settings 读写
├── models/     目录 / 照片 / 标签 / 人脸 / 任务 / 回收站 / 审计 / 告警
├── services/
│   ├── dirs        目录注册表（配置为准，SQLite 镜像）
│   ├── scan        目录扫描（增量指纹 + inotify 监听）
│   ├── photos      相册查询：时间轴 / 目录树 / 分类 / 人物 / 地理 / 分页
│   ├── exif        EXIF：拍摄时间、方向、GPS、相机
│   ├── thumbs      单档 256px 缩略图（.thumbnails/）
│   ├── tasks       持久化任务队列 + 资源控制（并发 / 限流 / 工作时段）
│   ├── workers    各类任务的具体执行（缩略图 / 识别 / 压缩 / EXIF）
│   ├── ml          检测后端（stub / onnx）+ 人脸聚类
│   ├── compress    无损压缩（PNG: oxipng；JPEG: jpegtran）
│   ├── originals   .originals/ 原图保护（仅破坏性操作触发）
│   ├── trash       回收站（软删除 / 还原 / 保留期清理）
│   ├── dedup       双指纹去重（file_hash + pixel_hash）
│   ├── search      FTS5 全局搜索
│   ├── audit       WG peer 注册表 + 异步批量审计日志
│   ├── alerts      健康告警（磁盘 / 任务 / SQLite / 占用）+ Server 酱推送
│   └── backup      SQLite 定期快照（`VACUUM INTO`）
└── handlers/   admin（JWT）/ photos / files / media / trash / upload
```

## 任务与资源控制

任务持久化在 SQLite，重启恢复（`running` → `pending`）。资源控制全部可在管理后台热更新：

| 项 | 默认 | 说明 |
|----|------|------|
| CPU 并发 | 1 | object / scene / face / dedup |
| IO 并发 | 2 | thumb / compress / geo / scan |
| 限流 | 20 文件/秒 | 全局 |
| 工作时段 | 02:00–08:00 | 重任务全速；白天只处理 `priority >= 10`（刚上传） |
| 单文件超时 / 最大重试 | 120s / 3 | 超时按失败计 |
| 全量重扫 | 每周 03:00 | 兜底；日常靠 inotify 增量 |

## 识别（ML）

默认 `stub` 后端不加载模型，仅从图像本身推导粗粒度场景标签，用于跑通流水线。
真实模型需开启 `onnx` feature 并在配置中给出模型路径：

```bash
cargo build --release --features onnx
```

```yaml
runtime:
  ml:
    backend: onnx
    object: { model: /models/yolov8n.onnx, labels: /models/coco-labels.txt, threshold: 0.35 }
    scene:  { model: /models/scene.onnx, labels: /models/scene-labels.txt, threshold: 0.30 }
    face:   { model: /models/yolov8n-face.onnx, threshold: 0.50, cluster-threshold: 48 }
```

人脸分组采用轻量方案：人脸框 → 16x16 感知哈希 → 与各组代表脸做汉明距离聚类（PRD §9 问题 2）。
模型或阈值变更后可在管理后台「人物分组 → 重新聚类」重算。

## 无损压缩

- PNG：`oxipng`（纯 Rust，内置）
- JPEG：`jpegtran -copy all -optimize`（容器内已安装 `libjpeg-turbo-progs`）

只有**节省 ≥ 阈值且解码像素完全一致**时才原子替换原文件，因此**不产生 `.originals/` 副本**。

## 数据安全

- **原图保护**：仅破坏性操作（旋转写回等）把原始像素存入 `.originals/`，默认保留 30 天。
- **回收站**：所有删除先进回收站，默认 30 天后自动清理。
- **去重**：上传时对**源文件**计算 `file_hash`（SHA-256）；已处理文件靠 `pixel_hash`（解码像素 dHash）仍能识别重复。

## SQLite 备份

数据库中的元数据（照片索引、标签、人脸、任务队列、审计）全部来自磁盘，可重建，但重建成本高。
因此服务端按 `runtime.backup`（默认 24 小时、保留 7 份）执行 `VACUUM INTO` 快照：

- 快照写入 `<data-dir>/backups/homehub-<时间戳>.db`，先写 `.tmp` 再原子改名，不会出现半截备份；
- `VACUUM INTO` 在只读事务里拷贝，写入期间服务正常读写，产物同时是整理过的（无 WAL 残留）；
- 超过 `keep` 份后删除最旧的；
- 管理后台「系统信息 → 数据库备份」可查看列表、占用、上次结果，并手动触发。

恢复：停服 → 用快照替换 `homehub.db`（连同删除 `-wal` / `-shm`）→ 启动。

## 测试

```bash
cargo test                          # 单元测试（EXIF 解析、哈希、路径、JWT、分词…）
bash tests/run.sh                   # 端到端冒烟（自动构建 + 生成夹具 + 启动服务端）
```

`tests/run.sh` 会把夹具和配置生成到 `/tmp/homehub-smoke`，监听 `127.0.0.1:8485`，
跑完自动关停服务端（`KEEP=1` 可保留）。
