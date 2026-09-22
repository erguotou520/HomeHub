# HomeHub API 文档

- 版本：v1（对应 PRD v0.3 的 M1–M4）
- 基础路径：`http://<wg-ip>:8485`
- 数据面（`/api/photos`、`/api/files`、`/api/media`、`/api/trash`、`/api/search`）**无用户认证**：准入由 WireGuard 网络层保证，每个请求按来源 IP 归属到 WG peer 并写入审计日志。
- 管理面（`/api/admin/*`）使用单一管理员密码登录换取 JWT：`Authorization: Bearer <token>`。

所有响应均为 `application/json`，错误形如 `{"error": "..."}`。

---

## 1. 管理面 `/api/admin/*`

### 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/admin/login` | body `{"password": "..."}` → `{"token": "...", "expires_in": 604800}` |
| GET | `/api/admin/me` | 当前管理员信息 |

> token **只接受** `Authorization: Bearer <token>` 请求头。查询串中的 `?token=` 不再受理（URL 会进入代理日志、浏览器历史与 `Referer`）。

### 目录注册表

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/dirs` | 目录列表 + 统计（文件数/照片数/已打标数/字节数） |
| POST | `/api/admin/dirs` | 新建目录 |
| PUT | `/api/admin/dirs/:id` | 修改目录（名称/路径/归属标记/忽略规则/启停） |
| DELETE | `/api/admin/dirs/:id` | 删除目录登记（磁盘文件不动） |
| POST | `/api/admin/dirs/:id/enabled` | `{"enabled": bool}` |

目录结构：

```json
{
  "name": "photos",
  "path": "/mnt/nas/photos",
  "marks": ["album"],          // album | video | music | document | none，可多选
  "ignore": ["@eaDir", ".thumbnails", "#recycle", ".stfolder"],
  "enabled": true
}
```

变更会写回 `config.yaml` 并立即生效（热加载）。

### 任务中心

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/tasks` | 各队列深度 / 运行中 / 近 5 分钟吞吐 |
| POST | `/api/admin/tasks/rescan` | `{"dir_id": 可选, "full": true}` 全量重扫 |
| GET | `/api/admin/tasks/failed` | 失败任务（含 error） |
| POST | `/api/admin/tasks/retry` | 重试全部失败任务 |
| DELETE | `/api/admin/tasks/failed` | 清空失败任务 |
| POST | `/api/admin/tasks/run` | `{"kind": "clean_trash"}` 立即执行维护任务 |

`kind` 取值：`scan`、`thumb`、`detect_object`、`detect_scene`、`detect_face`、`compress`、`geo`、`dedup_scan`、`clean_trash`、`audit_retention`、`health_check`、`peer_sync`、`backup`。

### 身份审计

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/peers` | WG 设备列表 |
| POST | `/api/admin/peers` | 手工登记 peer |
| PUT | `/api/admin/peers/:id` | 改名 / 启停 |
| GET | `/api/admin/peers/:id/logs?limit=&offset=` | 该设备的访问日志 |
| GET | `/api/admin/traffic` | 每个设备的请求数与流量 |

### 统计与系统

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/stats` | 目录统计、照片数、人脸/人物数、回收站与 `.originals` 占用 |
| GET | `/api/admin/system` | 版本、运行时长、SQLite 体积、磁盘水位、ML 后端、告警历史 |
| GET/PUT | `/api/admin/settings` | 运行时参数（任务并发/限流/工作时段、ML、压缩、原图保护、回收站、审计、告警）热更新 |
| GET | `/api/admin/alerts` | 告警历史 |
| POST | `/api/admin/alerts/resolve` | `{"kind": "disk"}`（省略则全部）标记已恢复 |
| POST | `/api/admin/alerts/test` | 发送一条测试告警 |
| GET | `/api/admin/duplicates` | 重复照片分组 |
| POST | `/api/admin/duplicates/trash` | `{"fingerprint": "..."}` 该组除第一张外移入回收站 |
| GET | `/api/admin/backups` | SQLite 快照列表 + 备份配置 + 上次结果 |
| POST | `/api/admin/backups/run` | 立即备份一次，返回 `{"name": "homehub-<时间戳>.db"}` |
| GET | `/api/admin/fs` | `path` 参数（省略则取 `$HOME`）列子目录，仅返回目录、跳过隐藏项；供目录选择器使用 |

> `GET /api/admin/settings` 中的凭据字段（`alerts.serverchan.send-key`）以 `••••••••` 掩码返回；`PUT` 时若该字段为空或仍是掩码，则保留服务端已存的值，因此保存表单不会清空密钥。
>
> `PUT` 的语义是**深合并**而非整体替换：请求体逐键合并到已存的设置上（对象递归合并，标量 / 数组 / null 直接覆盖）。因此拿着旧快照的客户端保存一次，不会把它没编辑过的段落回退成旧值。

### 人物分组

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/admin/people/:id/merge` | `{"source_id": n}` 合并两个人物 |
| POST | `/api/admin/people/:id/unassign` | `{"face_id": n}` 把某张脸移出分组 |
| POST | `/api/admin/people/recluster` | 按当前阈值重算全部分组 |

---

## 2. 照片 / 相册 `/api/photos/*`

| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| GET | `/api/photos/timeline` | `group=day\|month\|year`（默认 `month`）、`kind=photo\|video`、`from`、`to`、`per_group`（默认 500） | 跨 album 目录聚合的时间轴 |
| GET | `/api/photos/tree` | `dir_id` | 按目录层级聚合 |
| GET | `/api/photos/tags` | – | 标签云（物体 + 场景）与封面 |
| GET | `/api/photos/people` | – | 人脸分组与封面 |
| GET | `/api/photos/geo` | `precision`（网格精度，默认 0.02 度） | 地理聚合点，地图渲染由客户端完成（PC：Leaflet + 高德瓦片；Android：高德 SDK）。两者都需把 WGS-84 转成 GCJ-02 |
| GET | `/api/photos/semantic` | `q`、`dir_id`、`year`、`limit` | 中文自然语言搜图（Chinese-CLIP 余弦排序）；`limit` 上限 200。需构建时启用 `onnx` 且已加载 CLIP 模型，否则返回 400 |
| GET | `/api/photos/list` | `dir_id`、`tag`、`person_id`、`from`、`to`、`has_gps`、`kind`、`ids`、`limit`、`offset` | 条件分页查询；`kind=photo\|video`，`ids` 为逗号分隔的照片 id（最多 1000 个），用于展开地图上的一个聚合点；`limit` 默认 200、上限 1000 |
| GET | `/api/photos/:id` | – | 详情（EXIF 信息 + 标签 + 人脸框） |
| GET | `/api/photos/:id/raw` | – | 按 id 流式读取原图/原视频，支持 `Range`（视频播放用；路径由服务端解析） |
| POST | `/api/photos/:id/rotate` | `{"angle": 90\|180\|270}` | 旋转写回；原像素先归档到 `.originals/` |
| POST | `/api/photos/people/:id/rename` | `{"name": "..."}` | 给人物分组命名 |
| GET | `/api/duplicates` | – | 重复照片分组（file_hash / pixel_hash） |
| GET | `/api/geo/reverse` | `lat`、`lng` | 经纬度反查地名，结果按约 11 m 网格缓存在 `geo_places`；返回 `{"label": ..., "cached": bool}` |

照片对象：

```json
{
  "id": 42,
  "dir_id": 1,
  "dir_name": "photos",
  "rel_path": "2024/05/a.png",
  "name": "a.png",
  "taken_at": 1714566896,
  "width": 200, "height": 150, "size": 573,
  "orientation": 1,
  "gps_lat": 31.2304, "gps_lng": 121.4737,
  "camera_make": "Apple", "camera_model": "iPhone 15",
  "file_hash": "…", "pixel_hash": "…",
  "url": "/api/media/photos/2024/05/a.png?v=1714566896-573",
  "thumb_url": "/api/media/photos/2024/05/a.png?size=thumb&v=1714566896-573",
  "tags": [{"tag": "dog", "kind": "object", "confidence": 0.87}]
}
```

---

## 3. 文件（NAS）`/api/files/*`

寻址统一为 `<目录名>/<相对路径>`。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/dirs` | 已登记目录列表 |
| GET | `/api/files/:dir` | 列目录根 |
| GET | `/api/files/:dir/*path` | 列目录；命中文件则直接下载（支持 Range） |
| POST | `/api/files/:dir/*path` | multipart 上传（字段名任意，可多文件）；`?on-duplicate=skip` 时命中重复直接丢弃 |
| PATCH | `/api/files/:dir/*path` | `{"name": "new.jpg"}` 重命名；或 `{"op":"copy"\|"move","to_dir":"…","to_path":"…"}` |
| DELETE | `/api/files/:dir/*path` | 软删除，进回收站 |
| POST | `/api/mkdir/:dir/*path` | `{"name": "..."}` 新建目录 |
| GET/PUT | `/api/documents/:dir/*path` | 文本读写（读取上限 1 MiB） |

列目录参数：`sort=name|mtime|size`、`desc=true`。目录永远排在前面。

上传响应包含去重提示（`skipped` 仅在 `on-duplicate=skip` 命中重复且被丢弃时出现）：

```json
{"uploaded": [{"name": "a.png", "size": 573, "duplicate_of": "photos/2024/05/a.png", "queued": true}], "count": 1}
```

> **体积上限**：multipart 上传单请求上限 512 MiB；单文件更大时请走下面的分片接口（单个分片上限 16 MiB，单个文件累计上限 16 GiB）。服务端默认的 2 MiB body 限制已在文件上传相关路由上放宽。

---

## 3.1 断点续传上传 `/api/upload/*`

大文件（Android 客户端）走分片上传：`dir` / `path` / `name` 为查询或 body 参数，语义与 `/api/files` 一致。

| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| GET | `/api/upload/offset` | `dir`、`path`、`name` | 返回 `<name>.homehub-part` 已收到的字节数 → `{"offset": n}` |
| POST | `/api/upload/chunk` | `dir`、`path`、`name`、`offset`、`total`(可选) | body 为原始字节流，追加到 `.part` 文件。`offset` 小于当前长度时视为重传并截断重写；`offset` 大于当前长度返回 400（gap） |
| POST | `/api/upload/complete` | body `{"dir","path","name","total"?,"on_duplicate"?}` | 校验大小后走与 multipart 相同的去重 / 索引 / 入队流程，并删除 `.part` |

- 单个分片上限 16 MiB，单个文件累计上限 16 GiB（`total` 省略时同样受累计上限约束）。
- `on_duplicate` 取值 `skip` | `keep`（默认 `keep`）；**注意是下划线命名**，与其余接口一致。

## 3.2 图片编辑 `/api/images/*`

按 `<目录名>/<相对路径>` 寻址（不限于已索引的相册照片），服务端有 `safe_join` + 前缀双重校验。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/images/transform/:dir/*path` | body `{"ops": [...]}`，按顺序应用 rotate / flip / resize 等步骤；原像素先归档到 `.originals/`，响应返回新几何信息 |
| POST | `/api/images/restore/:dir/*path` | 回滚到归档的原始像素 |
| GET | `/api/images/exif/:dir/*path` | 完整元数据面板（EXIF / 尺寸 / 方向 / GPS），含 `has_original` |

> 相册侧另有按 id 的旋转接口 `POST /api/photos/:id/rotate`（见 §2），两者都会归档原图。

## 4. 媒体 `/api/media/*`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/media/:dir/*path` | 原图；`?size=thumb` 返回 256px 缩略图（视频回退为同目录 poster） |
| GET | `/api/media/lyrics/:dir/*path` | LRC 歌词 |
| GET | `/api/media/info/:dir/*path` | 音视频元数据（NFO / 音乐标签） |

**`v` 版本参数（必读）**：照片会被原地改写（旋转 / 翻转 / 还原），路径却不变。
列表接口返回的 `url` / `thumb_url` 因此都带 `v=<mtime>-<size>`：

- 带 `v` → 内容不可变，响应 `Cache-Control: private, max-age=31536000, immutable`，可放心长期缓存；
- 不带 `v`（手拼的 URL、视频流）→ 响应 `Cache-Control: no-cache`，客户端必须每次回源校验。

这样缓存不会跨过一次编辑，缩略图与原图都能立刻刷新。**不要自行剥离 `v` 参数。**

---

## 5. 搜索 `/api/search`

| 方法 | 参数 | 说明 |
|------|------|------|
| GET | `q`、`type=photo\|file`、`from`、`to`、`limit`、`offset` | FTS5 全文检索（文件名 + 标签 + 目录名）；无命中时回退 `LIKE` 子串匹配 |

结果：

```json
{"hits": [{"ftype": "photo", "ref_id": "42", "title": "a.png",
           "snippet": "…<b>a.png</b>…", "photo_id": 42, "dir_id": 1,
           "rel_path": "2024/05/a.png",
           "thumb_url": "/api/media/photos/2024/05/a.png?size=thumb&v=1714566896-573"}]}
```

---

## 6. 回收站 `/api/trash`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/trash` | 列表（含 `purged_due`） |
| GET | `/api/trash/stats` | 条数、占用、保留天数 |
| POST | `/api/trash/:id/restore` | 还原到原路径 |
| DELETE | `/api/trash/:id` | 彻底删除 |
| DELETE | `/api/trash/empty` | 清空 |

---

## 7. 其它

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| GET/PUT | `/api/monitor/config` | 监控配置骨架（v1 不实现播放） |

---

## 8. 任务与资源控制

任务持久化在 SQLite `tasks` 表，重启后自动恢复；每个任务按类型受并发、限流与工作时段约束：

| 类型 | 类别 | 默认并发 |
|------|------|---------|
| `detect_object` / `detect_scene` / `detect_face` / `dedup_scan` | CPU | 1 |
| `thumb` / `compress` / `geo` / `scan` | IO | 2 |

- 限流：全局每秒处理文件数（默认 20）。
- 工作时段：默认 `02:00–08:00` 全速执行重任务；白天只处理 `priority >= 10` 的任务（刚上传的文件）。
- 增量优先：`mtime:size` 指纹未变化的文件不重复入队；另有 inotify 监听实时感知变更。
- 失败重试：`max_attempts`（默认 3），超过后进入 `failed` 等待人工处理。

`backup` 按 `runtime.backup.interval-hours`（默认 24 小时）入队，执行 `VACUUM INTO`
把快照写入 `<data-dir>/backups/`，超过 `keep`（默认 7）份后删除最旧的。
