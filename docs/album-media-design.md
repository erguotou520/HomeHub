# 相册图文混合设计（参考系统相册交互）

> 版本 v0.1 · 2026-09-08 · 对应 PRD v0.3 的「相册支持图片+视频」需求

## 1. 目标与范围

参考系统相册（Google 相册 / 系统图库）的交互逻辑，把相册从「纯照片时间轴」升级为
「照片 + 视频混合媒体库」：

- **本期做**：album 目录视频入库、时长/分辨率元数据、抽帧缩略图、缩略图角标、
  查看器内视频播放（ExoPlayer）、按类型筛选、admin web 播放。
- **本期不做**：多选批量操作、捏合调整列数、视频 AI 识别（YOLO 抽帧）、转码、
  收藏/归档标记。

## 2. 交互对照表（系统相册 → homehub）

| 系统相册交互 | homehub 对应设计 | 备注 |
|---|---|---|
| 底部「照片 / 相册集 / 搜索」 | 保留现有 5 个 Chip：时间轴=照片流，目录=相册集，分类/人物/地点=发现 | 结构不变，内容混入视频 |
| 照片流按「今天/昨天/日期」分组 | 时间轴分组粒度增加 `day`，默认用 `day`；日期头吸顶 | 现有 group=month 保留兼容 |
| 视频缩略图右下角时长 `▶ 0:42` | 左下角 ▶ + `mm:ss` 黑底半透明角标 | 系统相册为右下角，我们放在左下角与标签错开 |
| 点开全屏，左右滑动翻页 | 现有 HorizontalPager 保留，图片/视频混合排列 | 按拍摄时间排序 |
| 图片双击放大 / 捏合缩放 | 现有实现保留 | 补充：scale>1 时锁定翻页 |
| 视频单击显隐控制栏 | 图片单击=显隐信息/操作栏，视频单击=显隐控制栏 | 统一的手势心智 |
| 视频播放进度条、暂停 | Media3 PlayerView 自带控制栏 | 拖动进度时锁住翻页 |
| 上滑查看信息 | 保留现有「信息」按钮面板 | 视频信息增加时长/编码 |
| 返回键退出查看器 | 保留左上返回按钮 | 上一轮需求 |
| 按类型筛选（视频相册） | 时间轴顶部加「全部 / 照片 / 视频」Segmented 过滤 | 对应 API `kind` 参数 |

## 3. 照片流（时间轴）设计

```
┌──────────────────────────────┐
│ 相册            [全部|照片|视频]│  ← 顶部：标题 + 类型过滤
│ ── 今天 · 12 项 ────────────── │  ← 按天分组头（吸顶）
│ ┌───┐ ┌───┐ ┌───┐            │
│ │img│ │vid│ │img│            │  ← 3 列方格，视频角标
│ └───┘ └▶──┘ └───┘            │
│ ┌───┐ ┌───┐ ┌───┐            │
│ └───┘ └───┘ └───┘            │
│ ── 昨天 · 5 项 ─────────────── │
│ …                             │
└──────────────────────────────┘
```

- 分组粒度：`GET /api/photos/timeline?group=day`；服务端把 2024-09-08 之类
  key 格式化为「今天 / 昨天 / 星期X / M月d日 / yyyy年M月」。
- 视频 tile 与图片 tile 同尺寸（1:1 方格），缩略图用 ffmpeg 抽帧。
- 空态文案区分：全部为空 → 提示连 WG 等扫描；筛「视频」为空 →
  「还没有视频」。

## 4. 查看器设计

### 4.1 页面结构

```
┌──────────────────────────────┐
│ ←        3 / 27              │  ← 常驻：返回 + 页码
│                              │
│        图片 / 视频画面         │  ← Pager 按类型渲染
│                              │
│ [信息] [⟲] [⟳]   ▶ ▁▁▂▃ 0:42 │  ← 图片: 信息+旋转
└──────────────────────────────┘     视频: 控制栏(播放/进度/静音/倍速)
```

### 4.2 手势与状态

- **单击**：切换底部操作栏显隐（图片）/ 切换播放控制栏显隐（视频）。
- **双击**：图片=放大/还原（现有）；视频=播放/暂停。
- **捏合**：仅图片；scale > 1 时禁用 Pager 翻页（`userScrollEnabled=false`），
  防止缩放拖动误触发翻页。
- **翻页切换**：离开视频页自动 `pause()`；离开查看器 `release()` 播放器。
- **视频画面适配**：默认 Fit（完整显示），控制栏提供「铺满」切换（竖屏视频
  铺满宽度）。
- **旋转按钮**：仅对 photo 显示。

### 4.3 播放能力矩阵

| 容器/编码 | 直接播放 | 说明 |
|---|---|---|
| mp4 / m4v (h264+aac) | ✅ | 手机拍摄主力格式 |
| mov (h264) | ✅ | iPhone 视频 |
| webm | ✅ | ExoPlayer 原生支持 |
| mkv (h264) | ⚠️ | ExoPlayer 可解大部分，音频 DTS/TrueHD 会无声 |
| avi / flv / ts | ❌ | 仅浏览/缩略图，播放提示「格式不支持，转码能力规划中」 |

## 5. 服务端改造

### 5.1 数据库（migration 002）

```sql
ALTER TABLE photo_assets ADD COLUMN media_kind  TEXT NOT NULL DEFAULT 'photo'; -- photo|video
ALTER TABLE photo_assets ADD COLUMN duration_ms INTEGER;
ALTER TABLE photo_assets ADD COLUMN video_codec TEXT;
CREATE INDEX idx_photos_kind ON photo_assets(media_kind, status);
```

表名沿用 `photo_assets`（语义扩为「媒体资产」），避免大改。

### 5.2 扫描与元数据

- `scan.rs`：album 目录入库条件 `is_image(ext) || is_video(ext)`，
  按扩展名写 `media_kind`（`paths.rs` 已有 `is_video`）。
- 新增 `services/probe.rs`：调用 `ffprobe -v quiet -print_format json
  -show_format -show_streams`（路径可配置，缺省找不到则跳过），提取：
  - `duration_ms`、`width/height`
  - mp4 `creation_time` → `taken_at`；`com.apple.quicktime.creationdate` 优先
  - `gps`（quicktime location）→ gps_lat/lng
- 不用 ffmpeg 的兜底：duration 为空时角标只显示 ▶ 不显示时长；
  mp4 时长也可以用纯 Rust `mp4` crate 解析（备选，二期）。

### 5.3 缩略图

- `thumbs.rs::video_poster` 升级：优先 `ffmpeg -ss 1 -i <file> -frames:v 1
  -vf scale=256:-1 <thumb>`；失败再退回现有「同名 poster 查找」。
- 抽帧走任务队列（kind=`thumb_video`，IO 低峰段执行，CPU 并发 1），
  避免扫描时大量子进程。

### 5.4 API

- `PhotoItem` 序列化增加 `media_kind`、`duration_ms`、`video_codec`。
- `GET /api/photos/list` 增加 `kind=photo|video` 参数。
- `GET /api/photos/timeline` 增加 `group=day`、`kind` 参数。
- **新增 `GET /api/photos/:id/raw`**：302/直出文件流，复用 `stream_file`
  （已支持 Range）。比让客户端拼 `/api/files/:dir/:path` 更稳：路径由
  服务端按 id 解析，且与照片权限/审计一致。ExoPlayer 用该 URL 播放。
- 旋转接口对 video 返回 400。

## 6. 移动端改造

### 6.1 依赖

```toml
# gradle/libs.versions.toml
media3 = "1.4.1"
androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
androidx-media3-ui = { group = "androidx.media3", name = "media3-ui", version.ref = "media3" }
```

### 6.2 代码清单

| 文件 | 改动 |
|---|---|
| `data/Models.kt` | `PhotoItem` + `mediaKind: String`、`durationMs: Long?`、`videoCodec: String?` |
| `ui/components/Common.kt` | `PhotoTile`：video 显示 ▶+时长角标（`util/Format.kt` 加 `formatDuration(ms)`） |
| `ui/screens/album/AlbumScreen.kt` | 顶部类型过滤（全部/照片/视频）；上传选择器 `"image/*"` → `"image/* video/*"`；确认框文案「上传 N 个文件」 |
| `ui/screens/album/AlbumViewModel.kt` | `filterByKind(kind)`；timeline 请求带 kind |
| `ui/screens/album/ViewerScreen.kt` | Pager 页内按 `mediaKind` 渲染 `ImagePage`（现有）或 `VideoPage`（ExoPlayer + AndroidView(PlayerView)）；scale>1 锁翻页；离开页面 pause/release；信息面板加时长/编码 |
| `data/Repository.kt` | `mediaUrl(id) = absolute("api/photos/$id/raw")` 供播放器使用 |

### 6.3 视频播放生命周期

```
进入视频页 → ExoPlayer 创建 → prepare() → 不自动播（系统相册行为）
翻到其他页 → pause() + 保留实例（回来继续）
退出查看器 → release() 全部实例
旋转屏幕/后台 → 沿用 Activity 生命周期即可（App 锁竖屏的话更简单）
```

## 7. Admin web 改造（小）

- 照片网格：video 项左下角时长角标。
- 查看器：photo 用现有 `<img>`，video 用原生 `<video controls preload="metadata">`
  （浏览器自动 Range）。
- 类型过滤下拉复用 `kind` 参数。

## 8. 验收清单

1. album 目录放入 mp4/mov → 扫描后时间轴出现，角标有时长。
2. 点开视频 → 播放/暂停/拖进度/静音可用；翻页离开自动停。
3. 横屏 mp4 Fit 显示完整，竖屏视频铺满切换可用。
4. mkv 可浏览缩略图，播放失败有明确提示。
5. `kind=video` 过滤后时间轴只含视频。
6. 图片缩放放大后不误触发翻页。
7. 无 ffmpeg 环境：视频仍入库可浏览，角标无时长，不阻塞扫描任务。
8. admin web 可列出并播放视频。
