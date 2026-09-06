# HomeHub 管理后台（PC 端）

React + TypeScript + Vite 实现的 HomeHub 管理后台，同时兼作 PC 端相册客户端。

## 开发

```bash
npm install
npm run dev          # http://localhost:5173，/api 反代到 http://127.0.0.1:8485
```

若服务端不在本机，先启动 Vite 再手动指定后端：

```bash
HOMEHUB_API=http://10.0.0.1:8485 npm run dev
```

## 构建

```bash
npm run build        # 产物在 admin/dist
```

服务端可以通过 `ADMIN_DIST` 直接托管构建产物，实现单容器部署：

```bash
ADMIN_DIST=/app/admin/dist ./homehub-server   # 访问 http://<server>:8485/
```

`ADMIN_DIST` 缺省为 `../admin/dist`；目录不存在时服务端只提供 API。

## 页面

| 页面 | 说明 |
|------|------|
| 登录 | 单一管理员密码 → JWT |
| 相册 | 时间轴 / 目录树 / 分类 / 人物 / 地点（Leaflet + 高德瓦片），全屏查看器（缩放 + 旋转保存） |
| 全局搜索 | FTS5 文件名与标签检索，按类型过滤 |
| 目录管理 | 目录注册表增删改（名称/路径/归属标记/忽略规则/启停）+ 统计；变更写回 `config.yaml` 并热生效 |
| 任务中心 | 各队列深度与速率、全量/增量重扫、失败重试与清空、资源控制参数（并发/限流/工作时段） |
| 身份审计 | WG 设备列表、按设备查看访问日志、流量统计 |
| 回收站 | 浏览 / 还原 / 彻底删除 / 清空 / 占用统计 |
| 监控 | Frigate 地址配置骨架（预留） |
| 系统信息 | 版本、运行时长、SQLite 体积、磁盘水位、ML 后端；告警历史与 ntfy / Telegram / SMTP 通知配置；重复照片分组清理；SQLite 备份（设置 / 手动触发 / 快照列表） |

## 相册「地点」视图

服务端 `/api/photos/geo` 返回按经纬度网格聚合的点，PC 端用 **Leaflet + 高德瓦片**渲染
（`admin/src/components/GeoMap.tsx`），可切换路网 / 影像两种底图。

照片的 EXIF GPS 是 WGS-84，而高德瓦片是 GCJ-02（火星坐标），
因此每个聚合点在交给 Leaflet 之前都会做 WGS-84 → GCJ-02 换算，否则标记会偏离几百米。
点击聚合点会带上该点的 `photo_ids` 请求 `/api/photos/list?ids=…` 展开照片。

## 目录结构

```
admin/
├── src/
│   ├── api/        # HTTP 客户端与类型定义（types.ts 与服务端 JSON 一一对应）
│   ├── state/      # 登录态
│   ├── components/ # 查看器、地图等复用组件
│   ├── pages/      # 各页面
│   ├── ui/         # 导航与布局
│   └── styles.css
└── vite.config.ts
```
