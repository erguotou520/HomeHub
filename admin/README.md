# HomeHub 管理后台（PC 端）

React + TypeScript + Vite 实现的 HomeHub 管理后台，同时兼作 PC 端相册客户端。

界面是「中性石墨 + 冷调强调色」的设计系统，令牌、组件规范与配色改动前必读的
几条硬规则见 **[DESIGN.md](./DESIGN.md)**。

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
| 系统信息 | 版本、运行时长、SQLite 体积、磁盘水位、ML 后端；告警历史与 Server 酱通知配置；重复照片分组清理；SQLite 备份（间隔 / 保留份数 / 目录 / 手动触发 / 快照列表） |

## 相册「地点」视图

PC 端用 **Leaflet + 高德瓦片**渲染（`admin/src/components/GeoMap.tsx`），可切换路网 / 影像两种底图。

聚合分两层，各管一件事：

| 层 | 粒度 | 职责 |
|----|------|------|
| 服务端 `/api/photos/geo?precision=` | 经纬度网格，随 zoom 变细（`precisionForZoom`，约等于当前 zoom 下 14 px 的地面距离） | 只做取样，把 payload 压到「每个网格一个点」 |
| 客户端 | **屏幕像素距离**（`CLUSTER_RADIUS_PX = 56`） | 决定最终看到的聚合气泡，每次 `zoomend` 重算 |

屏幕像素半径意味着「相近」是视觉意义上的：相距 2 km 的两点在小比例尺下同属一个气泡，
放大到彼此超过 56 px 后自动拆开；缩小回去又会合并。服务端网格刻意比气泡细，
所以放大时既取到更细的点、气泡本身也在散开，不会出现「卡住不拆」。
平移不触发重新聚合（像素距离与平移无关）。

**只有一种标记**：没有「单张点 / 聚合气泡」的区分，任何点都是一个带数字的气泡，
数字就是它包含的照片数（只有 1 张时显示 `1`）。因此地图上不需要图例。
直径由 `bubbleSize(count)` 按 `log10(count)` 绝对映射到 28–54 px（1000 张封顶），
不随当前视野里的最大聚合数变化——否则满屏都是单点时每个点都会撑到最大直径而互相重叠。

照片的 EXIF GPS 是 WGS-84，而高德瓦片是 GCJ-02（火星坐标），
因此每个点在交给 Leaflet 之前都会做 WGS-84 → GCJ-02 换算，否则标记会偏离几百米。
点击气泡会带上其 `photo_ids` 请求 `/api/photos/list?ids=…` 展开照片（上限 1000 张）。

## 目录结构

```
admin/
├── src/
│   ├── api/        # HTTP 客户端与类型定义（types.ts 与服务端 JSON 一一对应）
│   ├── state/      # 登录态
│   ├── hooks/      # 跨页复用的小钩子
│   ├── components/ # 查看器、地图、Toast / Confirm 反馈层等复用组件
│   ├── pages/      # 各页面
│   └── styles.css
└── vite.config.ts
```

## 反馈层

一次性动作（保存设置、删除、发送测试告警）的结果反馈统一走两个共享组件，
不用浏览器的 `alert()` / `confirm()`（样式脱离设计系统，且同步阻塞整个标签页）：

```tsx
const toast = useToast()
const confirm = useConfirm()

async function save() {
  try {
    await api.put('/api/admin/settings', { alerts })
    toast.ok('通知设置已保存')
  } catch (e) {
    toast.err(e instanceof Error ? e.message : '保存失败')
  }
}

async function purge(id: number) {
  if (!(await confirm({ title: '彻底删除？', body: '删除后无法恢复。', danger: true }))) return
  // ...
}
```

色调：`ok` / `warn`（部分失败、功能不支持）/ `err` / `info`，细节见 DESIGN.md。
