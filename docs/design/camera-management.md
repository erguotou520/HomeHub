# 独立摄像管理实现方案（不依赖 Frigate）

目标：在 HomeHub App 的监控 Tab 下，**原生**实现
实时预览 / PTZ 云台 / 双向通话 / 历史回放（server NVR），
以及低优先级的手动报警。
（镜头遮蔽已确认**不做**：ONVIF 无标准指令，App 层遮罩又无设备侧价值。）

本方案面向 TP-LINK TL-IPC44AW-COLOR 6.0（实测于 `192.168.8.144`），
但全部走 **ONVIF Profile S + RTSP** 标准协议，可推广到任意 ONVIF 摄像机。

---

## 0. 设备实测事实（192.168.8.144）

| 项 | 值 |
|---|---|
| 型号 | TL-IPC44AW-COLOR 6.0（4MP 云台 Wi-Fi） |
| 端口 | `80`（Web + ONVIF `…/onvif/device|media|ptz`）、`554`（RTSP）、`2020`（ONVIF 单入口 `/onvif/service`） |
| ONVIF | **2.20 / Profile S**（video+audio 流、PTZ、event；**不含 2-way audio 标准**） |
| 主码流 | `rtsp://admin:***@192.168.8.144/stream1`  H.264 2560×1440 ~23fps |
| 子码流 | `rtsp://admin:***@192.168.8.144/stream2`  H.264 704×576 ~23fps |
| 音频 | G711（mic + 喇叭），**双向通话通道已验证可推流** |
| 通话推流 | `rtsp://…/stream1/intercom`（ffmpeg 推 G711 无错跑满 3s，确认） |
| PTZ | 8 个预置位；连续 pan/tilt/zoom；相对+绝对空间；`GetStatus` 返回 `Position.PanTilt x/y ∈ [-1,1]`；token=`ptz` |
| Profile token | `profile_1` / `profile_2` |
| 快照 | `GetSnapshotUri` → `ActionNotSupported`（快照需从 RTSP 抓帧） |
| 鉴权 | **必须 WSSE `PasswordDigest`**（端口 2020 拒绝 Basic；端口 80 部分操作也 `NotAuthorized`） |

> 鉴权结论：这台设备**只能**用 ONVIF 标准的 WS-Security `UsernameToken` + `PasswordDigest`，
> 普通 HTTP Basic 会被拒。这是实现里最容易踩的坑。

---

## 1. 总体架构

```
┌───────────────────────── Android App (Kotlin / Media3) ─────────────────────────┐
│  监控 Tab                                                                        │
│  ├─ 实时预览   Media3(RtspDataSource) ← rtsp://user:pwd@IP/stream1|stream2       │
│  ├─ 双向通话   Media3 采集麦克风 → 8k G711 → RTSP 推流 rtsp://…/stream1/intercom  │
│  ├─ PTZ 云台   OkHttp SOAP(WSSE digest) → /onvif/ptz  ContinuousMove/Stop/Preset │
│  ├─ 历史回放   HomeHub NVR（server 录制，详见 docs/design/nvr.md）          │
│  ├─ 手动报警   推 intercom 警铃声 / 或设备 API（见 §6）                            │
└──────────────────────────────────────────────────────────────────────────────────┘
        SOAP over HTTP(S)                    RTSP (TCP)
              │                                  │
   http(s)://IP:2020/onvif/service      rtsp://IP:554/…
```

**核心决策：不用重型 ONVIF 库，直接发原始 SOAP（WSSE digest）+ Media3 拉 RTSP。**
理由：
- Profile S 用到的就 4 个服务（Device / Media / PTZ / DeviceIO），SOAP 报文固定、可手写；
- 社区验证过这条路：`jazgogmain-png/v380pro`（LibVLC + 裸 SOAP，专治"stubborn firmware"）；
- 避免引入 `zeep`(Python) / 大型 Java ONVIF 库的鉴权、token 解析 bug 与体积。
- 鉴权封装成一个小工具类 `OnvifClient`，一次写好全项目复用。

如果更想省事，可选现成 Android 库（见 §8 选型对比），但裸 SOAP 对本设备兼容性最稳。

---

## 2. ONVIF 客户端封装（`OnvifClient.kt`）

### 2.1 鉴权：WSSE `PasswordDigest`

```
Digest = Base64( SHA1( Base64decode(Nonce) ‖ Created ‖ Password ) )
```

每次请求都要**新生成** Nonce/Created（设备会校验时效）。

```kotlin
fun wsseHeader(user: String, pwd: String): String {
    val nonce = Base64.encodeToString(Random(16).toByteArray(), NO_WRAP)
    val created = DateTimeFormatter.ISO_INSTANT.format(Instant.now()) // 2026-…Z
    val digest = Base64.encodeToString(
        MessageDigest.getInstance("SHA1")
            .digest(nonce.toByteArray(Charsets.ISO_8859_1)
                    + created.toByteArray() + pwd.toByteArray())
    )
    return """
      <wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
                     xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-wsu-1.0.xsd">
        <wsse:UsernameToken>
          <wsse:Username>$user</wsse:Username>
          <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0#PasswordDigest">$digest</wsse:Password>
          <wsu:Created>$created</wsu:Created>
          <wsu:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0#Base64Binary">$nonce</wsu:Nonce>
        </wsse:UsernameToken>
      </wsse:Security>
    """.trimIndent()
}
```

### 2.2 通用请求（含 WSA 头，端口 2020 需要）

```kotlin
fun call(action: String, bodyInner: String): String {
    val mid = "{${UUID.randomUUID()}}"
    val env = """
      <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                  xmlns:wsa5="http://schemas.xmlsoap.org/ws/2005/08/addressing">
        <s:Header>
          <wsa5:Action s:mustUnderstand="true">$action</wsa5:Action>
          <wsa5:MessageID>$mid</wsa5:MessageID>
          <wsa5:To s:mustUnderstand="true">$SERVICE_URL</wsa5:To>
          <wsa5:ReplyTo><wsa5:Address>http://www.w3.org/2005/08/addressing/role/anonymous</wsa5:Address></wsa5:ReplyTo>
          ${wsseHeader(user, pwd)}
        </s:Header>
        <s:Body>$bodyInner</s:Body>
      </s:Envelope>
    """.trimIndent()
    // POST SERVICE_URL  (SERVICE_URL = http://IP:2020/onvif/service)
    // Content-Type: application/soap+xml; charset=utf-8
    // SOAPAction: "$action"
}
```

> 本设备 `/onvif/service`（2020）与 `/onvif/device|media|ptz`（80）都可用，
> 统一用 **2020 单入口**，最省事。

---

## 3. 实时预览（Media3 / ExoPlayer + RTSP）

- 主码流清晰：`rtsp://user:pwd@IP/stream1`（2560×1440，弱网可切 `stream2`）。
- Media3：`RtspMediaSource.Factory` + `RtspDataSource`，`rtspTransport = tcp`，硬解 H.264 + G711。
- 主/子码流切换 = 换 URL 即可。
- 抓一帧当缩略图：`ExoPlayer` 首帧或独立拉 `stream2` 取一帧（`GetSnapshotUri` 不支持）。

```kotlin
val source = RtspMediaSource.Factory(
    RtspDataSource.Factory().setRequestHeaders(mapOf("Authorization", basic)) // 或 URL 内嵌
).setRtspRequestTimeoutMs(5000).createMediaSource(MediaItem.fromUri("rtsp://…/stream1"))
```

---

## 4. PTZ 云台控制（ONVIF PTZ 服务，已验证）

PTZ token = `ptz`，位置空间 `x/y ∈ [-1, 1]`（连续空间 `DefaultContinuousPanTiltVelocitySpace`）。

### 4.1 连续运动（按住方向键）

`POST /onvif/ptz` — action `…/PTZService/ContinuousMove`：

```xml
<tptz:ContinuousMove xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
  <tptz:ProfileToken>profile_1</tptz:ProfileToken>
  <tptz:Velocity>
    <tt:PanTilt x="0.5" y="0.0"/>   <!-- x>0 向右, y>0 向下（本设备 GetStatus y=1 为"最下"） -->
    <tt:Zoom   x="0.0"/>
  </tt:Velocity>
</tptz:ContinuousMove>
```

松开 → `Stop`：

```xml
<tptz:Stop xmlns:tptz="…">
  <tptz:ProfileToken>profile_1</tptz:ProfileToken>
  <tptz:PanTilt>true</tptz:PanTilt>
  <tptz:Zoom>true</tptz:Zoom>
</tptz:Stop>
```

> 交互：UI 用**按住拖动**（joystick），松手立即 `Stop`；
> `x,y` 幅度 = 速度（0~1）。frigate 的 `frigate/ptz/onvif.py` 就是这么做的（`create_type("ContinuousMove")` + 相对/连续空间），可直接参考其空间选择逻辑。

### 4.2 预置位（8 个，已验证存在）

- 读取：`…/PTZService/GetPresets`
- 调用：`…/PTZService/GotoPreset`（`<tptz:PresetToken>…</tptz:PresetToken>`）
- 设置当前位：`…/PTZService/SetPreset`（`<tptz:HomePosition>false</tptz:HomePosition>`）
- 回中：`…/PTZService/GotoHomePosition`（或 `SetPreset` 建 Home）

### 4.3 相对平移（点哪转哪，可选）

`…/PTZService/RelativeMove`，空间用 `Spaces.RelativePanTiltTranslationSpace` 的 URI，
`Translation.PanTilt.x/y`（frigate 用它做 FOV 级平移）。

### 4.4 状态回显

`…/PTZService/GetStatus` → `Position.PanTilt x/y` + `MoveStatus`，用于云台当前位置指示。

---

## 5. 双向通话（intercom，已验证可推流）

ONVIF Profile S **没有** 2-way audio 标准，但本设备（及多数 TP-Link/Tapo）开了
`rtsp://…/stream1/intercom` 这个 RTSP **推流**通道：

- App 侧：`AudioRecord`(8000Hz / Mono) → 编码 G711（PCMU/A-Law，Media3 无原生 A-Law 编码器，
  用 `MediaCodec` `audio/g711` 或轻量 codec）→ `Media3 RtpStreamSession`/推 RTSP；
- 实测命令（可先验证）：
  `ffmpeg -f lavfi -i "aevalsrc=0:d=3" -c:a pcm_alaw -ar 8000 -rtsp_transport tcp rtsp://…/stream1/intercom` 无错。
- 听：实时预览流 `stream1` 本身带 mic 音频，App 正常出声即可；
  通话时建议拉 `stream2`（轻）叠加，减少卡顿。

> 若纯 Kotlin 推 RTSP 麻烦，可在 App 里内嵌一段 `libav`/`librtmp` 或走设备 Web 的 HTTP 音频接口兜底。
> 注意：通话中扬声器 + 麦克风同开会啸叫 → 默认**半双工**（按住说话 / 释放收听）。

---

## 6. 手动报警（低优先级）

ONVIF 无标准"手动报警"动作。可行：
1. 向 `intercom` 推一段**警铃/提示音**（复用 §5 通话推流通道，最简单）；
2. 触发设备 ONVIF 事件 / 或设备 Web 报警接口（需逆向，低优先）；
3. 联动 HomeHub：本地推一条"手动报警"事件进 HomeHub DB/通知（纯 App 侧，立即可做）。

> 建议第一版只做 **App 侧**（本地报警事件 + 推警铃），设备私有接口留作后续逆向。

## 7. 历史回放：HomeHub server NVR（已定方案）

**决策：不依赖 Frigate、不逆向设备 SD 录像，由 homehub server 自研 NVR。**
完整设计见 **`docs/design/nvr.md`**，要点：

- server 常驻 ffmpeg 每路拉 RTSP **主码流**，**转码压至 1080p**，码率用 **CRF 质量模式
  + 2M 封顶**（监控画面多为静止：静止时仅 ~100–400kbps，剧烈运动顶 2M，
  典型约 1–6GB/天/路），`-f segment -strftime 1 -segment_time 60` 分片到 `.cache/`，
  每个分片起点强制 IDR（`-force_key_frames`）保证独立可播；
- 维护循环 probe + faststart 后搬入 `recordings/YYYY-MM-DD/HH/{camera}/MM.SS.mp4`，
  元数据入 SQLite `nvr_segments`；
- 清理循环每小时：`retain_days`（30/60/90 可配）+ 可选 `max_gb` 兜底 + 扫空目录；
- 回放：原始分段直出（鉴权 + Range）+ 动态拼 m3u8 播放列表（Media3 直播）+ 范围导出 MP4；
- 时间轴 API 对标 frigate：days / summary(逐小时) / segments / gaps(缺口) 四端点。

---

## 8. 开源方案选型对比

| 方案 | 形态 | 适用 | 评价 |
|---|---|---|---|
| **裸 SOAP + Media3（本方案）** | 自写 ~200 行 `OnvifClient` | 本设备已验证 WSSE/PTZ/RTSP | 最稳、体积最小、完全可控；PTZ/通话/鉴权全打通 |
| `v380pro`（v380） | LibVLC + 裸 SOAP + WSSE | HiSilicon/XM 老板卡 2K | 思路最佳参考：裸 SOAP 打 PTZ、WSSE 摘要、LibVLC 拉流 |
| `com.github.03:onvif:1.0.9` | Java 库（发现+PTZ+Android） | 想少写代码 | 功能全，但库较重、鉴权路径需适配 |
| `com.seanproctor:onvifcamera` / `com.rvirin.onvif` | Kotlin/Java（发现+Profile+StreamUri） | 只要流地址 | 偏"发现/取 URI"，PTZ/通话能力弱 |
| `openlink2/link-onvif-client` | Java（发现+PTZ+Imaging+Events+WS-Security） | 服务端/桌面 | 功能最全，但面向 JVM/Spring，移动端略重 |
| frigate `frigate/ptz/onvif.py` | Python（zeep）autotrack | 服务端自动跟踪 | 可参考其 PTZ 空间/速度逻辑，不直接用于 App |

**结论：App 端自写 `OnvifClient`（裸 SOAP + WSSE）+ Media3（RTSP），PTZ/通话/预览全打通；
历史录像走 HomeHub server 自管 NVR（见 `docs/design/nvr.md`）；报警第一版做 App 侧。**

---

## 9. 落地步骤（建议顺序）

1. `OnvifClient.kt`：WSSE digest + 通用 `call()` + 4 个服务封装（Device/Media/PTZ/DeviceIO）。
2. 监控 Tab：Media3 拉 `stream1/stream2` 实时预览 + 主/子码流切换 + 首帧缩略图。
3. PTZ：joystick（ContinuousMove/Stop）+ 8 预置位（GotoPreset/SetPreset/Home）。
4. 双向通话：麦克风采集 → G711 → 推 `stream1/intercom`（半双工）。
5. 手动报警（本地事件 + 推警铃到 intercom）。
6. server NVR 按 `docs/design/nvr.md` 落地顺序实现（相机 CRUD → 录制/维护 → 时间轴/回放 → 清理/导出）。
7. （可选）逆向 `80` 端口 Web 私有 API，补齐设备侧报警/SD 录像能力。

## 10. 已验证的原始报文（可直接复用）

- 服务发现：`POST /onvif/device` `GetServices`（Basic 可过）→ 8 个服务，XAddr 指向 2020。
- `GetDeviceInformation` / `GetProfiles` / `PTZ GetConfigurations` / `PTZ GetPresets` /
  `PTZ GetStatus` / `GetStreamUri` / `GetAudioSources` / `GetAudioOutputs`：
  全部 HTTP 200（WSSE + WSA 头）。
- `GetSnapshotUri` → `ActionNotSupported`。
- RTSP：`stream1`/`stream2` ffprobe 正常；`stream1/intercom` 推 G711 无错。
