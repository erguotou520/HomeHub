# HomeHub NVR 模块设计（server 侧历史回放 / 录像存储）

目标：homehub server 常驻拉取摄像头 RTSP 流，**分片落盘（按日期/小时分目录）**，
元数据入 SQLite，**按保留期（30/60/90 天可配）定期清理**，并向 App 提供
时间轴 + 回放 + 导出接口。参考 Frigate 录像体系（见文末对照表），按其核心思想
简化实现（v1 无 AI 检测，不区分 motion/alert 保留档）。

关联文档：`camera-management.md`（App 侧实时预览 / PTZ / 通话）。

---

## 1. 核心决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 录制方式 | server 内常驻 `ffmpeg` 子进程，每摄像头一个 | 复用现有 ffmpeg 基础设施（`runtime.video.ffmpeg_path`），不引入新运行时 |
| 分片格式 | MP4 分片，主码流**转码**：缩放 1920×1080 + CRF 压缩，**默认无音频**（`-an`，可开 AAC 32kbps 重编码） | 用户定：录主码流但分辨率压到 1080 再压缩；监控画面不需要 24/7 音频。MP4 容器不支持 G711（实测 muxer 无 alaw），开启时 G711→AAC 重编码，32kbps ≈ 345MB/天/路 |
| 分片时长 | 默认 60s（可配 10–600） | frigate 同款默认；断流最多丢一个分片；转码时每个分片起点强制 IDR |
| 目录布局 | `{data_dir}/recordings/YYYY-MM-DD/HH/{camera}/MM.SS.mp4`（本地时区） | 用户要求按日期分目录；MM.SS=分片起始分秒，相机小时内唯一（frigate 同款） |
| 落盘流程 | 扁平 `.cache/` 缓存目录 → 维护循环 probe → 搬入日期布局 + 入库 | frigate 同款；ffmpeg 的 strftime 分段名不会建目录。**不做 faststart 重写**（实测 ffmpeg 9.0.1 segment muxer 忽略 `+faststart`，moov 本就在段尾；Media3/浏览器经 HTTP Range 直接播可 seek；重写 = NAS 双倍写放大） |
| 元数据 | SQLite `nvr_segments` 表（sqlx + migration，贴合现有 `001..008` 风格） | 时间轴/清理/缺口查询全走 SQL，不扫盘 |
| 保留策略 | `retain_days`（30/60/90 任意配）+ 可选 `max_gb` 上限，双保险 | 用户要求定期清理；max_gb 防 NAS 写满 |
| 清理周期 | 每小时一次（`expire_interval_mins` 可配） | frigate 同款（tmp 每分钟、录像每小时） |
| 回放 | ① 原始 MP4 分段直出（tower-http ServeDir + admin JWT）② 动态拼 HLS 播放列表 ③ 范围导出单个 MP4 | v1 不引入 nginx-vod；Media3 可直接播 MP4 分段 HLS |
| 码流选择 | 录 **主码流 stream1**（2560×1440 源），转码至 **1080p**，**CRF 质量模式 + 2M 码率封顶**（均可配） | 用户定：保主码流画质/云台跟随，存储压下来；监控画面大部分时间静止，CRF 下静止场景码率仅 ~100–400kbps |

### 容量估算（单路，主码流转码 1080p、CRF 28 + maxrate 2M、无音频）

监控画面大部分时间静止，CRF 质量模式下码率随画面变化**自适应**：

| 场景 | 典型码率 | 1 天 | 30 天 | 90 天 |
|---|---|---|---|---|
| 静止为主（固定镜头、白天） | ~100–400kbps | ~1.1–4.3GB | ~33–130GB | ~99–390GB |
| 夜视（IR/低照度噪点，"静止"也降不下太多） | ~200–800kbps | ~2.2–8.6GB | ~66–258GB | ~198–775GB |
| 活动较多（门口/客厅常有人） | ~0.5–1.5Mbps | ~5.4–16GB | ~160–480GB | ~485–1.4TB |
| 满封顶（持续剧烈运动，最坏） | ≤2Mbps | ≤21.6GB | ≤650GB | ≤1.9TB |

> `maxrate 2M` 保证最坏情况容量可预估（≤21.6GB/天/路），静止时段省 5–10 倍。
> 原始主码流 ~4Mbps ≈ 43GB/天/路（90 天 ~4TB）。
> **音频**：默认关。开启 G711 直通后每路 +691MB/天（90 天 ~62GB）——静止场景下音频接近视频体积，只给需要的相机开（`cameras` 可逐路配置，见 §4）。
> 仍嫌大：`fps` 降到 15（再省 ~30–40%）、CRF 升 30–32，或 v2 开"仅运动录制"（见 §5 备注）。

---

## 2. 目录与文件布局

```
{data_dir}/recordings/
  .cache/                        # 临时缓存（ffmpeg 分段直接写这里，扁平）
    home-20260930143059123456.mp4
  2026-09-30/
    14/
      home/
        30.59.mp4                # 起始 30 分 59 秒（本地时区）
        31.59.mp4
      living/
        30.58.mp4
```

- 日期/小时目录用**本地时区**（config `nvr.timezone`，默认 `Asia/Shanghai`）。
- DB 存 **unix 秒**（时区无关）；展示层再转本地时间。
- 相机名作为目录段，非法字符 sanitize（`[^A-Za-z0-9_-]` → `_`）。

---

## 3. 数据库（migration `009_nvr.sql`）

```sql
CREATE TABLE cameras (
  id          INTEGER PRIMARY KEY,
  name        TEXT NOT NULL UNIQUE,          -- 'home'（= recordings 目录段）
  label       TEXT,                          -- 展示名 '客厅'
  rtsp_url    TEXT NOT NULL,                 -- rtsp://user:pass@192.168.8.144/stream1
  enabled     INTEGER NOT NULL DEFAULT 1,
  record_stream INTEGER NOT NULL DEFAULT 1,  -- 1=主 2=子（存 stream 后缀；默认主，转码 1080p）
  record_audio  INTEGER NOT NULL DEFAULT 0,  -- 0=关音频(-an) 1=G.711 直通(容器内映射 acp)
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

CREATE TABLE nvr_segments (
  id           INTEGER PRIMARY KEY,
  camera_id    INTEGER NOT NULL REFERENCES cameras(id) ON DELETE CASCADE,
  path         TEXT NOT NULL UNIQUE,         -- 相对 data_dir：recordings/2026-09-30/14/home/30.59.mp4
  start_time   INTEGER NOT NULL,             -- unix 秒
  end_time     INTEGER NOT NULL,
  duration     REAL NOT NULL,                -- 秒
  size_bytes   INTEGER NOT NULL,
  video_codec  TEXT,
  audio_codec  TEXT,
  audio_rate   INTEGER,
  has_audio    INTEGER,
  width        INTEGER,
  height       INTEGER,
  created_at   INTEGER NOT NULL
);
CREATE INDEX idx_nvr_seg_cam_time ON nvr_segments(camera_id, start_time);
CREATE INDEX idx_nvr_seg_end_time ON nvr_segments(end_time);   -- 清理扫描用
```

- 保留期配置放 config yaml（`nvr:` 节），admin 可经现有 `settings` 表热更新。
- RTSP 凭据只在 DB 与 config 中出现，API 返回时**脱敏**（`rtsp://***:***@host/...`）。

---

## 4. 配置（`config.yaml`，`RuntimeSettings` 增加 `nvr` 节）

```yaml
runtime:
  nvr:
    enabled: true
    record_dir: ""            # 空 = {data_dir}/recordings
    timezone: "Asia/Shanghai" # 目录布局用本地时区
    segment_secs: 60          # 分片时长 10..600
    expire_interval_mins: 60  # 清理周期（分钟）
    retain_days: 30           # 保留天数：30/60/90
    max_gb: 0                 # 0=不启用；>0 时按最旧优先裁剪
    maintain_interval_secs: 10  # 缓存维护循环间隔
    video:                    # 录制转码参数（用户定：主码流降 1080 + 压缩）
      source: "main"          # 源码流：main=stream1
      scale: "1920x1080"      # 目标分辨率（源低于此值时不放大）
      fps: 0                  # 0=保持源帧率(25)；降到 15 可再省 ~30-40%（监控够用）
      crf: 28                 # 质量模式：静止画面码率自动降到很低
      maxrate: "2M"           # 码率上限（封顶，容量可预估）
      bufsize: "4M"           # 缓冲（maxrate 的 2 倍）
      encoder: "auto"         # auto|libx264|h264_nvenc|h264_qsv|videotoolbox
      preset: "veryfast"      # x264 档位（hwaccel 编码器忽略）
      audio: false            # 全局默认；相机级 record_audio 覆盖。G.711=64kbps≈691MB/天/路，默认关
```

---

## 5. 录制器（`services/nvr/recorder.rs`）

每个 enabled 摄像头一个 tokio 任务，内部循环：

```
loop {
    spawn ffmpeg:
      {ffmpeg} -hide_banner -loglevel error \
        -rtsp_transport tcp -stimeout 5000000 -max_muxing_queue_size 1024 \
        -i {rtsp_url} \
        -vf "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=black" \
        [-r {fps}, fps>0 时] \
        -c:v {encoder} [-preset veryfast, 软编时] -crf 28 -maxrate 2M -bufsize 4M \
        -force_key_frames "expr:gte(t,n_forced*{segment_secs})" \
        [-an | -c:a aac -ar 8000 -ac 1 -b:a 32k ]   # 按 record_audio；G711 无法直装 MP4，重编码 AAC \
        -avoid_negative_ts make_zero \
        -f segment -strftime 1 -segment_time {segment_secs} \
        -reset_timestamps 1 \
        '{cache}/{cam}-%Y%m%d%H%M%S.mp4'   # 输出参数即分段模板（无 -segment_filename 选项）
    等待子进程退出
    状态置 down；退避 5s → 10s → … → 60s 后重拉
}
```

要点：
- **每个分片起点强制 IDR**：转码 + 分片组合下必须
  `-force_key_frames expr:gte(t,n_forced*{segment_secs})`，保证每个 MP4 段独立可播、
  能拼进 m3u8、首帧秒开 —— 缺了它，从段中切出的片段开头会花屏/黑场（`-c copy` 直通时
  靠设备 IDR 间隔，转码后由我们完全控制，必须显式指定）。
- **缩放**：`scale=1920:1080:force_original_aspect_ratio=decrease` + `pad` 补齐黑边
  （本机 2560×1440 为 16:9，缩 1920×1080 无黑边；其他比例设备自动适配，不放大）。
- **编码器**：`encoder: auto` 按平台探测（Linux x86：NVENC → QSV → libx264 兜底）。
  单路 1080p@25fps x264 veryfast 约占 1 核的 15–30%；多路时建议 hwaccel，
  docker 机器上注意 `--device` 透传 GPU/核显。
- **码控（质量模式 + 封顶）**：`-crf 28 -maxrate 2M -bufsize 4M`。监控画面大部分时间
  静止，CRF 下静止场景码率自动降到 ~100–400kbps（比固定 2M CBR 省 5–10 倍），
  剧烈运动时顶到 2M 封顶 —— 容量上界仍可预估（≤21.6GB/天/路）。
  不用 CBR 的原因：固定码率在静止画面下浪费 80%+ 的带宽。
- **v2 可选：仅运动录制**（frigate motion 模式同款思路）：解码子码流做帧差检测，
  无运动时暂停/跳过分片（保留 pre-capture 秒数缓存，运动开始前的 5s 不丢），
  静止时段几乎零写入。依赖轻量运动检测，工作量较大，留作容量不够时的升级项。
- **缓存文件名的时间戳**来自 ffmpeg strftime（**墙钟本地时区**，非流时间戳），维护循环据此推导分片起始秒。
  真实 RTSP 是 1x 实时流，60s 分片不会撞秒（实测：`-re` 限速下每分片独立文件）；同秒内断流重启最坏是
  覆盖上一轮未完成的残片（本来就要删）。注意 `%f` 不是 strftime 转换符（ffmpeg 原样输出字面量），
  不要加。
- `-max_muxing_queue_size 1024`：RTSP 长跑防 mux 队列积压撑爆内存（ffmpeg 常见坑）。
- **faststart 实测结论（ffmpeg 9.0.1）**：segment muxer 忽略 `-movflags +faststart`，分片 moov 在
  段尾（ftyp,free,mdat,moov）。Media3 ExoPlayer / hls.js / 浏览器均可经 HTTP Range 播放段尾 moov 的
  MP4（每段多一次尾部 range 请求，moov 通常 <500KB），v1 接受；若日后首帧延迟明显，可加配置开关
  让维护循环对分片做 `-c copy -movflags +faststart` 重写（代价：NAS 写 2 倍）。
- 摄像头掉线/断网：ffmpeg 自动退出 → 退避重拉（等价 frigate 的断流重连）。
- SIGTERM 时 ffmpeg 会收尾当前不完整分片，正常落 cache。
- 状态机（内存 + 周期落 `settings`）：`recording / down / disabled`，
  附 `last_segment_at`（`SELECT MAX(end_time)` 每摄像头）、`restart_count`。
- 热管理：循环每 30s 复查 `cameras` 表，动态 start/stop（新增相机、改码流、停用）。

## 6. 维护循环（`services/nvr/maintainer.rs`，每 `maintain_interval_secs` 一轮）

对 `.cache/` 中**完成**的分片（mtime 早于 `segment_secs + 2s` 且文件名可解析）：

1. `ffprobe` 取 duration / 编码 / 音频 / 分辨率（复用 `services/probe.rs`）。
2. 计算 `end = start + duration`，目标路径
   `recordings/{YYYY-MM-DD}/{HH}/{cam}/{MM}.{SS}.mp4`，`mkdir -p` 后 `rename`
   （与 cache 同卷，原子）。**不做 faststart 重写**：编码时已 `-movflags +faststart`
   （moov 在文件头，Media3/浏览器 HTTP Range 直接播可 seek）；frigate 的 rewrite 是为
   nginx-vod 转 fMP4，homehub 不引 nginx-vod，重写只增加 NAS 写放大。
3. 插入 `nvr_segments`（`path` 唯一约束，冲突则跳过 —— 幂等）。
4. 兜底：cache 中残留超过 1 小时的文件判为损坏，删除并记日志。

## 7. 清理循环（`services/nvr/cleanup.rs`，每 `expire_interval_mins` 一次）

frigate 同款"先删文件后删库再扫空目录"三段式：

1. `cutoff = now - retain_days`
2. 分批（1000/批）：
   - 文件存在 → `remove_file`，`DELETE FROM nvr_segments WHERE id IN (...)`
     （**先删文件再删行**，frigate 同款；若删库后删文件前崩溃，文件成孤儿，
     由步骤 6 的启动清扫 + 本步骤"文件已不存在→删行"在下轮自愈）
   - 文件已不存在（孤儿行）→ 直接删行
3. 自旧到新删除 `recordings/` 下空目录（`HH` → `YYYY-MM-DD`），只删空目录，不递归强删。
4. **max_gb 兜底**：若启用，按 `start_time` 升序继续删除直至低于上限
   （用 `size_bytes` 累加估算，`sum()` 缓存）。
5. 收尾：`PRAGMA wal_checkpoint(TRUNCATE)`（frigate 同款防 WAL 膨胀）。
6. 启动时一次性清理：`.cache` 残留 + 无 DB 行的孤立分片文件（仅删 1 天前，防竞态）。

> 保留档（frigate 的 continuous/motion/alerts 三档）v1 不做：homehub NVR 当前
> 无 AI 检测输入，统一 retain_days。表结构与循环留了扩展位（加 `tier` 列即可）。

---

## 8. API（`handlers/nvr.rs`，全部走现有 admin JWT 中间件）

### 相机与状态
| 端点 | 说明 |
|---|---|
| `GET /api/nvr/cameras` | 列表 + 状态（recording/down、last_segment_at、今日/当前小时分片数） |
| `POST /api/nvr/cameras` | 新增 `{name,label,rtsp_url,record_stream,record_audio}`（凭据脱敏后返回） |
| `PATCH /api/nvr/cameras/:id` / `DELETE` | 改码流/启停 / 删除（`?keep_files=true` 默认保留文件） |
| `POST /api/nvr/cameras/:id/restart` | 重启该路 ffmpeg |
| `GET /api/nvr/storage` | 磁盘占用：总量 / 按日汇总（frigate `get_recordings_storage_usage` 同款） |

### 时间轴（App 回放页用，对标 frigate record API）
| 端点 | 说明 | frigate 对应 |
|---|---|---|
| `GET /api/nvr/cameras/:name/days?from&to` | 按日布尔覆盖（`strftime('%Y-%m-%d')` 聚合） | `/recordings/summary` |
| `GET /api/nvr/cameras/:name/summary?date=` | 当日逐小时 `{hour, duration}` 覆盖条 | `/{cam}/recordings/summary` |
| `GET /api/nvr/cameras/:name/segments?after&before&order&limit` | 分片列表（id/起止/大小/时长，**limit 上限 500**，分页用 after/before） | `/{cam}/recordings` |
| `GET /api/nvr/cameras/:name/gaps?after&before&scale` | 无录像缺口（排序后单趟合并覆盖区间，frigate `no_recordings` 同款算法） | `/recordings/unavailable` |

### 回放
| 端点 | 说明 |
|---|---|
| `GET /api/nvr/recordings/**` | 原始 MP4 分段直出（ServeDir 限 `recordings/` 前缀 + Range 支持），App 可单段直接回放 |
| `GET /api/nvr/cameras/:name/play/hls?start&end` | 动态拼 **VOD 播放列表**：把 [start,end] 内分片拼成 m3u8（`#EXTINF` + 分段 URL），Media3 `HlsMediaSource` 直接播；seek=带新 start 重新请求 |
| `POST /api/nvr/cameras/:name/export` | 范围导出：ffmpeg concat 合并成单 MP4 进 `exports/`（任务队列执行，`GET /api/nvr/exports` 取进度/下载） |

### 回放播放列表细节（v1 简化，不引 nginx-vod）
- m3u8 中分段直接引用原始 `.mp4` 分段（编码时已 `-movflags +faststart`，moov 在文件头），
  Media3 HlsMediaSource 可播 MP4 分段。
- 跨小时/跨日范围：按 `start_time` 排序取分片，首段若从段中切入则
  `#EXT-X-START`/丢弃不完整首段（v1 取整段，误差 ≤60s；v2 再 ffmpeg 精切）。
- 升级路径：量大后换 nginx-vod（fMP4 + `#EXT-X-MAP`）或直接上 HLS 转码。

---

## 9. 装配（`main.rs`）

```rust
// audit_writer / watcher 之后
if config.runtime.nvr.enabled {
    services::nvr::spawn(pool.clone(), config).await?;
    // 内部：recorder 池 + maintainer + cleanup 三个 tokio 任务，共享 NvrState
}
```

- `NvrState`：`Arc<RwLock<HashMap<cam_id, RecorderHandle>>>` + 状态缓存。
- 优雅退出：`CancellationToken` → SIGTERM 各 ffmpeg（收尾当前分片）→ 等 10s → 强杀。
- 模块文件：`services/nvr/{mod.rs, recorder.rs, maintainer.rs, cleanup.rs}`，
  `handlers/nvr.rs`，migration `009_nvr.sql`。

---

## 10. 安全

- 全部 NVR 端点在现有 `admin_auth`（JWT）之后；回放直出同样鉴权。
- RTSP 凭据：DB 明文（本机文件权限 600 兜底），API 一律脱敏回显。
- 后续可加只读 viewer token（App 家庭端只看回放，不能改相机）——v2。

## 11. 落地顺序

1. migration + config + `cameras` CRUD（先能加相机）。
2. recorder（单路拉流分片落 cache）+ maintainer（probe/搬移/入库）。
3. 时间轴四接口 + 原始分段直出（App 先能按分片回看）。
4. HLS 播放列表 + Media3 回放页。
5. cleanup（retain_days + max_gb）+ storage 接口。
6. export 范围导出；viewer token；nginx-vod 升级（按需）。

## 12. 与 Frigate 录像体系对照

| 环节 | Frigate | HomeHub NVR |
|---|---|---|
| 录制 | 每相机 ffmpeg 写 cache 分片 `{cam}@{ts}.mp4` | 每相机 ffmpeg 写 `{cam}-{ts}.mp4`（strftime） |
| 落盘 | maintainer 5s 循环：probe → faststart 重写 → 搬 `YYYY-MM-DD/HH/cam/MM.SS.mp4` | 同款 10s 循环，但**免 faststart 重写**（编码时 `-movflags +faststart`；frigate 的 rewrite 是 nginx-vod fMP4 流程需要） |
| 元数据 | `Recordings` 表（含 motion/objects/keyframes） | `nvr_segments` 表（无 AI 字段，结构可扩展） |
| 时间轴 | summary/days/segments/unavailable 四端点 | 同名四端点，SQL 聚合同款算法 |
| 保留 | continuous/motion/alerts 三档 days + expire_interval | 单档 `retain_days` + `max_gb`，循环结构同款 |
| 清理 | 每小时：删过期 → 扫空目录 → WAL truncate | 同款三段式 |
| 回放 | nginx-vod fMP4 HLS + 原始 MP4 + export | 动态 m3u8（MP4 分段）+ 原始直出 + export；按需升 nginx-vod |
