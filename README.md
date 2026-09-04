# HomeHub

家庭自托管一体化服务：**相册（智能照片管理）+ NAS 文件 + 监控（预留）**，以 WireGuard 作为统一组网与准入手段。

由 `home-nas`（Rust 服务端）与 `my_nvr_app`（Android WireGuard/NVR 客户端）合并整合而来。

## 仓库结构

```
homehub/
├── docs/     # 需求文档（PRD）、架构决策记录
├── server/   # Rust + Axum 后端（源自 home-nas/backend）
├── admin/    # 管理后台前端（React + TS，待初始化）
├── mobile/   # Android 客户端 Kotlin + Compose（源自 my_nvr_app）
└── deploy/   # docker-compose.yml、config.yaml 等部署配置
```

## 快速开始（现状基线）

```bash
# 后端（依赖 PostgreSQL，见 deploy/docker-compose.yml）
cd server && cargo run

# Android 客户端
# 用 Android Studio 打开 mobile/
```

## 文档

- [需求文档 PRD](docs/PRD.md)
