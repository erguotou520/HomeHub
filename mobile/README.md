# HomeHub Android 客户端

Kotlin + Jetpack Compose 实现的 HomeHub 手机端：相册、NAS 文件、监控（预留）三大模块，
以 WireGuard 作为组网与准入手段。minSdk 30。

## 构建

```bash
./gradlew :app:assembleDebug     # 产物 app/build/outputs/apk/debug/
./gradlew :app:lintDebug         # 静态检查
```

需要 JDK 17+ 与 Android SDK（compileSdk 35）；`local.properties` 中指定 `sdk.dir` 或设置 `ANDROID_HOME`。

## 模块结构

```
app/src/main/java/me/erguotou/homehub/
├── MainActivity.kt        # VPN 权限申请、生物识别门禁、引导分流
├── HomeHubApp.kt          # 通知渠道
├── data/
│   ├── Prefs.kt           # EncryptedSharedPreferences（私钥/凭据加密存储）
│   ├── HttpClient.kt      # OkHttp：可配置的自签证书信任（不再全局信任）
│   ├── ApiService.kt      # Retrofit 接口定义
│   ├── Repository.kt      # 相册/文件/回收站/搜索/上传
│   └── Models.kt          # 与服务端 JSON 对应的数据类
├── wireguard/
│   ├── TunnelManager.kt   # wg0 隧道（GoBackend），状态以 StateFlow 暴露
│   ├── WgKeygen.kt        # 基于内置 Curve25519 的密钥生成与公钥推导
│   └── Key/KeyPair/...    # 沿用 my_nvr_app 的密钥工具
├── ui/
│   ├── Root.kt            # Navigation Compose + 底部 Tab
│   ├── screens/album/     # 时间轴 / 目录 / 分类 / 人物 / 地点 + 全屏查看器
│   │                      # GeoMapView.kt：高德地图 SDK 渲染地点聚合点
│   ├── screens/files/     # 目录浏览、面包屑、排序、多选、上传
│   ├── screens/monitor/   # 监控占位页 + Frigate 地址配置
│   ├── screens/setup/     # 首次引导（三步向导 + 配置导入）
│   └── screens/settings/  # 隧道开关、服务器、安全设置
└── work/UploadWorker.kt   # WorkManager 后台分片上传（断点续传 + 进度通知）
```

## 相册

- **浏览视图**：时间轴（按月分组）、目录树、分类（标签）、人物（人脸分组）、地点（高德地图 SDK 渲染聚合点）。
- **查看器**：左右滑动切换、双击/捏合缩放、EXIF 信息面板、旋转 90°/180° 并调用服务端写回。
- **上传**：系统文件选择器多选 → 二次确认（可勾选「上传完成后删除本地」）→ WorkManager 后台
  分片上传（`GET /api/upload/offset` 续传、`POST /api/upload/chunk` 追加、`POST /api/upload/complete`
  落盘并进入识别/缩略图流水线），进度以通知展示；服务端返回 `duplicate_of` 时提示重复。

## NAS 文件

- 目录树浏览（目录在上，文件按名称排序，可切换 mtime/size 与升降序）、面包屑返回上级。
- 新建目录、上传任意文件、下载、重命名、删除（二次确认，服务端软删除进回收站）、多选批量操作。
- 文本文件可直接预览。

## 地图（高德）

相册「地点」视图使用 **高德地图 Android SDK**（`com.amap.api:3dmap:10.0.600`）渲染服务端
`/api/photos/geo` 的聚合点：

1. 在[高德开放平台](https://console.amap.com/)申请 **Android 平台 Key**；
2. 打开 App → 「设置 → 地图」填入 Key（保存在 `EncryptedSharedPreferences`，不写进 APK）；
3. Key 通过 `MapsInitializer.setApiKey()` 在 `MapView` 创建前注入，因此无需在
   `AndroidManifest.xml` 里声明 `com.amap.api.v2.apikey`（若要硬编码，Manifest 里已有注释说明）；
4. 未填 Key 时「地点」页显示引导文案，不会崩溃。

两点注意事项：

- **坐标系**：EXIF GPS 是 WGS-84，高德地图用 GCJ-02，聚合点会经 `CoordinateConverter`
  转换后再打点，否则会偏移几百米。
- **依赖来源**：`com.amap.api` 不在 Maven Central，已在 `settings.gradle` 加上阿里云公共仓库。
  SDK jar 只带 `arm64-v8a` / `armeabi-v7a` 的 `.so`，`build.gradle` 里的 `abiFilters` 与之保持一致。

## 监控

底部 Tab 保留入口，仅提供 Frigate 地址配置（保存但不连接）。

## 安全整改（相对 my_nvr_app）

| 旧实现 | 现在 |
|--------|------|
| SharedPreferences 明文存私钥与密码 | EncryptedSharedPreferences（Keystore 托管密钥） |
| 全局信任所有证书、WebView 忽略 SSL 错误 | OkHttp + 可配置的单证书信任；`usesCleartextTraffic=false`（仅私有网段例外） |
| 1366 行 MainActivity 承载全部 UI | 多 Screen + Navigation Compose |
| Frigate 登录 JS 注入 hack | 本期监控空置，不迁移 |
