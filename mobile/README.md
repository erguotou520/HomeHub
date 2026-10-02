# HomeHub Android 客户端

Kotlin + Jetpack Compose 实现的 HomeHub 手机端：相册、NAS 文件、监控（预留）三大模块，
以 WireGuard 作为组网与准入手段。minSdk 30。

## 构建

```bash
./gradlew :app:assembleDebug     # 产物 app/build/outputs/apk/debug/
./gradlew :app:lintDebug         # 静态检查
```

需要 JDK 17+ 与 Android SDK（compileSdk 35）；`local.properties` 中指定 `sdk.dir` 或设置 `ANDROID_HOME`。

两项凭据都不入库，缺了照样能构建，只是对应能力不可用：

| 项 | 本地开发 | CI |
| --- | --- | --- |
| 发布签名 | `mobile/release.jks` + `mobile/keystore.properties` | secret `ANDROID_RELEASE_KEYSTORE_BASE64` / `..._PASSWORD` / `..._ALIAS` |
| 高德 SDK Key | `local.properties` 的 `amapKey` | secret `AMAP_KEY` |

缺签名时 `release` 退回 debug 签名（新克隆的仓库开箱即可构建）；缺高德 Key 时构建照常，只是地图页拿不到数据。

### 发版

打 tag 即发布，版本号完全由 tag 派生（`v1.2.1` → versionName `1.2.1`、versionCode `10201`）：

```bash
git tag v1.2.1 && git push github v1.2.1
```

CI 会构建、签名、生成增量包与版本清单并发布 Release，详见根 README 的「发布」。

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
│   ├── screens/files/     # 目录浏览、面包屑、排序、多选、上传 + 全屏文件查看器
│   ├── screens/monitor/   # 监控占位页 + Frigate 地址配置
│   ├── screens/setup/     # 首次引导（三步向导 + 配置导入）
│   └── screens/settings/  # 隧道开关、服务器、安全设置
├── ui/components/         # MediaViewer.kt：图片/视频/音频查看面（相册与文件共用）
│                          # DirectoryPicker.kt：可下钻的目录选择器（上传 / 复制 / 移动共用）
├── util/                  # FileKinds.kt 文件类型判定、OfficeText.kt OOXML 文本抽取
│                          # TempFiles.kt「用其他应用打开」的临时副本目录
├── update/                # 应用内更新：BsPatch 差分、清单、双通道下载、安装
└── work/UploadWorker.kt   # WorkManager 后台分片上传（断点续传 + 进度通知）
```

## 相册

- **浏览视图**：时间轴（按月分组）、目录树、分类（标签）、人物（人脸分组）、地点（高德地图 SDK 渲染聚合点）。
- **查看器**：左右滑动切换、双击/捏合缩放、EXIF 信息面板、旋转 90°/180° 并调用服务端写回。
- **长按操作菜单（分享 / 下载到本机）**：长按任意缩略图（时间轴 / 目录 / 筛选结果 / 语义搜索结果皆可）
  弹出操作菜单，两项都拉取**原图**：
  - **下载到本机**经 SAF 交给系统的「选择保存位置」，回来后报出落盘**目录**
    （`util/SavedFile.kt` 把返回的 `content://` 反解成「内部存储/Download」这样的措辞 ——
    选择器可以指向设备上十几个地方，而它自己不给回执；只报目录是因为文件名是用户刚选过的，
    重复一遍只是噪音）；用户取消选择则静默，不算失败。
  - **分享**走系统分享面板（`ACTION_SEND` + `Intent.createChooser`，见 `util/Sharing.kt`），
    微信 / QQ / 邮件 / 云盘 / 蓝牙 等凡是注册了该类型的应用都会出现，App 不特化任何渠道。
    接收方是异步读文件的，所以字节必须先落到 `cache/preview/handoff`（`TempFiles.writeHandoff`）再
    通过 FileProvider 交给它，不能直接从网络流过去。
  全屏查看器顶栏另有常驻的分享 / 下载按钮。缩略图长按是相册唯一的入口，因为**瓦片本身没有 ⋮ 按钮**
  （文件页每行都有 ⋮，长按则是进多选，菜单里同样有这两项）。菜单与两个动作分别由
  `ui/components/PhotoActions.kt` 的 `PhotoActionMenu` / `rememberPhotoSharer` /
  `rememberPhotoDownloader` 提供，与文件页共用同一个 `rememberLocalSaver`。
- **操作回执**：结果不是 Material 默认的整条 Snackbar（一条四字提示撑成一整条，还留着空的动作位），
  而是 `ui/components/Notice.kt` 渲染的居中胶囊：`NoticeKind.Success` 配圆形对勾、
  `.Failure` 配圆形叉，文案压到最短（`已保存到 Download` / `保存失败` / `没有可分享的应用`）。
  用 `snackbar.notify(...)` 投递；普通字符串消息（如文件页的复制/移动状态行）也能渲染，
  只是不带图标。**宿主必须用 `NoticeHost`**，三个使用点（相册 / 文件 / 语义搜索路由）都换过了。
- **上传**：系统文件选择器多选 → 二次确认（可勾选「上传完成后删除本地」）→ WorkManager 后台
  分片上传（`GET /api/upload/offset` 续传、`POST /api/upload/chunk` 追加、`POST /api/upload/complete`
  落盘并进入识别/缩略图流水线），进度以通知展示；服务端返回 `duplicate_of` 时提示重复。

## NAS 文件

- 目录树浏览（目录在上，文件按名称排序，可切换 mtime/size 与升降序）、面包屑返回上级。
- 新建目录、上传任意文件、下载到本机（SAF）、重命名、复制、移动、删除、多选批量操作。
- **删除一律二次确认**：弹框列出条目名、区分「文件夹（连同其中内容）」与普通文件、显示合计大小，
  并说明删除先进入回收站；操作进行中确认按钮禁用，避免重复提交。
- **复制/移动到共用目录选择器**（与相册上传同一个组件）：可从任意登记目录逐级下钻、面包屑回退、
  顺手新建子目录，并在列表里看到目标目录下已有的文件，确认后返回 `<目录>/<相对路径>`。
- 长按进入多选（选中后单击即切换选中态），底部浮动条提供 复制 / 移动 / 删除。

### 打开方式（不下载，优先本地渲染）

| 类型 | 打开方式 |
|------|----------|
| 图片 `jpg/png/gif/webp/heic/…` | 复用相册的图像查看器：全屏、左右滑动、双击/捏合缩放，并保留旋转/翻转/还原原图（服务端 `POST /api/images/transform/:dir/*path` 对任意图片文件生效） |
| 视频 `mp4/mkv/webm/mov/…` | 复用相册的视频查看器：Media3 控制器，按 Range 直接向 `/api/files/…` 流式播放 |
| 音频 `mp3/flac/m4a/wav/…` | 音乐播放页：黑胶唱片 + 封面随播放旋转 + 唱臂（播放落盘/暂停摆出，有补间动画），标题/歌手/专辑取自文件自带标签，进度条常显、可拖到任意位置。**打开不自动播放**；切到别的文件即暂停并记住位置，划回来从原处接着播 |
| 文本 `txt/md/ini/conf/log/json/csv/xml/…`、代码、字幕 | 全屏文本查看器：`GET /api/documents/:dir/*path`（服务端 1 MiB 上限）、等宽字体、可选中复制，二进制内容会识别并提示改用其他应用 |
| PDF | 下载到应用私有缓存 → 系统内置 `PdfRenderer` 分页渲染（连续滚动）→ 关闭查看器即删除副本 |
| Office `docx/xlsx/pptx` | 本地零依赖文本抽取（`util/OfficeText.kt`：ZIP + XmlPullParser，文档段落 / 表格按列对齐 / 每页文本），顶部横幅提示「纯文本抽取预览」并提供「打开方式」 |
| 其它 `doc/xls/ppt/odt/zip/apk/…` | 「用其他应用打开」：下载到缓存 `cacheDir/preview/` → FileProvider `content://` → 系统 `ACTION_VIEW`（无匹配时退回分享面板），打开新文件时清理超过 6 小时的旧副本 |

### 音乐播放页（`ui/components/MediaViewer.kt` 的 `AudioPage`）

音频不再复用视频那套 `PlayerView`，而是纯 Compose 的音乐界面，原因都在手势上：

- **封面优先内嵌标签**：ExoPlayer 解析 ID3 APIC / FLAC picture / MP4 `covr` 后经
  `onMediaMetadataChanged` 交出 `artworkData`，无需服务端接口；文件没有内嵌图时回退到
  同目录的 `同名.jpg` / `cover|folder|album.jpg`，再没有才用内置的默认标签（见下）。
- **进度条自绘**（`SeekBar`）：所有 pointer 变化都在该节点 `consume`，外层 `HorizontalPager`
  拿不到这一段横向拖动 —— 用 `PlayerView` 时它的旧式 seekbar 不参与 Compose 的消费链，
  拖进度条会被翻页抢走；顺带实现了"点/拖轨道任意位置立即跳转"。
  拖动期间还会把 `userScrollEnabled` 置 false 兜底（退出时复位）。
- **松手后不回弹**：`seekTo` 之后 ExoPlayer 短时间内仍报旧位置，若不屏蔽，每 250ms 的轮询会把
  拇指拽回去。用 `pendingSeekMs` 兜住这段（位置与目标差 <1.5s 或超时 3s 才交还控制权）。
- 左右滑动仍用于切换文件（只有进度条那一条被吃掉），底部另有上一首/下一首按钮。
- **唱臂**（`Tonearm`）：播放时唱头落到唱片沟槽上，暂停时整条臂摆到盘外上方，两段都用
  `animateFloatAsState` 补间。它**不是 `Disc` 的一部分** —— 唱片转、唱臂不转，所以是叠在
  上面的一层独立画布，绕 `TonearmPivotX/Y`（stage 的右上角内）旋转。
  为了让唱臂有地方"停放"，`AudioPage` 现在先算一个比唱片大的 **stage**（`discSize =
  stageSize * 0.76`），盘外那一圈空桌面是摆幅的余量。摆角 48° 是实测定出来的：44° 时唱针
  已经出盘、但唱头下缘仍蹭到沟槽带；48° 时整条臂才真正离开唱片（实测暂停态唱臂最低点亮
  像素距唱片边缘 36px，播放态该像素正好落到盘缘上）。
  部件（臂管、配重、唱头、唱针）都是**先按"臂水平摆好"的坐标画完、再整体旋转**的：
  `verticalGradient` 只有这样才会横跨臂管形成圆柱受光；直接给斜向的杆套屏幕坐标渐变，
  高光会顺着杆长跑，看起来就是一根扁棍。轴承座反过来画在旋转之外 —— 它的高光要固定
  朝左上，不能跟着唱臂转。
- **没有封面时用黑胶标签，不用图形符号**（`DefaultCover`）：底色比盘身亮一档，配上更细更密
  的同心沟槽和一圈描边，看上去就是"这张唱片没做封面"而不是"这里缺了个图标"。
  早先版本在中间压了一个音符，为了避让中心轴孔还得整体上移 —— 结果读起来是"没对齐的音符"
  而不是"刻意的偏移"，索性去掉。
- 打开默认暂停：一屏里往往是别的目录/别人的文件，自动出声很讨厌。
- **切走暂停、回来续播**（`AudioPlaybackMemory`，由 `FileViewerScreen` 用 `remember` 持有）：
  pager 一离开视野就会销毁页面、连带销毁它自己的 ExoPlayer，位置也就没了。所以离开时把位置
  和"是否正在播"存进记忆并 `pause()`，再回来时新建的 player 按记忆 `seekTo`，只有被中途打断的
  音轨才自动续播 —— 手动暂停过的仍停在原位置，从没播过的依旧静默打开。
  离开的判定同时挂在"不再是当前页"和 `onDispose` 上：pager 在拖动过程中就会组合相邻页，
  只靠 `onDispose` 会漏掉"构建过但没成为当前页"的那一份。

内联预览的取舍：Android 上没有轻量的原生 Office 渲染器（Apache POI ≈11 MB 且中端机 OOM、
腾讯 TBS 需下载 40 MB+ 内核、WebView 方案要打包三套 JS 引擎且中文版式不可靠），因此
`docx/xlsx/pptx` 走本地文本抽取，版式交给系统应用；PDF 用平台 `PdfRenderer` 真正内联。

## 应用内更新

全量包已接近 100 MB（高德 SDK + WireGuard native + Media3 + Compose），所以按
**增量优先、全量兜底**做，`update/` 下四个文件各管一段。

- 更新检查读仓库里的静态清单 `release/latest.json`（CI 发版时更新），**不经过服务端** ——
  没有服务端要维护，也没有限流。
- 取用走双通道：先直连，失败后把原始地址套在 `https://proxy.erguotou.me/` 前面重试。
  实测本机直连 `raw.githubusercontent.com` 会被**直接拒绝连接**（curl 错误码 7，55 ms 就返回），
  所以第二条通道不是锦上添花。
- 清单的 `deltas` 列出可用的增量包（含"从哪个版本、旧包 sha256 是什么"）。客户端先比版本号，
  命中之后才去算本机 APK 的指纹 —— 那要读完 93 MB，没命中就不该付这个代价。
- 合并结果必须与清单里全量包的 sha256 **完全一致**：v2/v3 签名覆盖整个文件的字节，
  差一个字节系统就拒装。对不上就静默退回全量，宁可多下一次，也不把装不上的包递出去。

### 增量为什么必须是二进制差分

不能用「只下发变化的几个文件再重新打包」：重打包会改变字节布局，而客户端手里没有私钥，
签名必然失效。bsdiff 把新包**逐字节**还原出来，签名因此仍然有效 —— 这是它能被系统当成
合法升级包的前提。

代价是要带一个 bzip2 实现（JDK 与 Android 都没有），这里用 commons-compress，只引
`BZip2CompressorInputStream`，包体增加约 1.3 MB。

本机实测：93.31 MB 的包 → 94.63 MB 的新包，**增量只有 1.72 MB（省 98.2%）**，
客户端合并耗时 1.2 秒。复现方式：

```bash
bsdiff old.apk new.apk real.patch
HOMEHUB_BSDIFF_DIR=$PWD ./gradlew :app:testDebugUnitTest --tests '*BsPatchTest*'
```

### 安装这一步无法全自动

系统弹框绕不过去：普通应用不能静默安装，用户必须点一次「安装」，首次还要先允许
「安装未知应用」。代码能做的是把包准备好，并把失败原因区分开（签名冲突、空间不足、
用户取消），见 `update/ApkInstaller.kt`。

## 地图（高德）

相册「地点」视图使用 **高德地图 Android SDK**（`com.amap.api:3dmap:10.0.600`）渲染服务端
`/api/photos/geo` 的聚合点：

1. Key 已内置在构建里（`app/build.gradle` 的 `buildConfigField "AMAP_KEY"`），
   用户**不需要**在应用内配置；
2. `GeoMapView.kt` 在 `MapView` 创建前调用 `MapsInitializer.setApiKey(BuildConfig.AMAP_KEY)`，
   因此无需在 `AndroidManifest.xml` 里声明 `com.amap.api.v2.apikey`；
3. 换 Key 只改 `build.gradle`，并在高德后台登记包名 `me.erguotou.homehub` 与对应签名 SHA1。

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
| 全局信任所有证书、WebView 忽略 SSL 错误 | OkHttp + 可配置的单证书信任；服务端暂无 TLS 监听，故明文 HTTP 为**全局放行**（`base-config`，非仅私有网段） |
| 1366 行 MainActivity 承载全部 UI | 多 Screen + Navigation Compose |
| Frigate 登录 JS 注入 hack | 本期监控空置，不迁移 |
