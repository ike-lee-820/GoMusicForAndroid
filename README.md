# GoMusic Android

根据《GoMusic 界面设计与 API 文档》实现的原生 Android 客户端（Kotlin + Jetpack Compose + Media3）。
后端默认使用线上 `https://gomusic.myocd.de5.net`，可在「设置」里改成自己的地址。

## 已实现
- 歌曲 / 专辑 / 歌手 / 歌单 四个标签，各自搜索
- 专辑页、歌手页（简介展开、专辑/歌曲切换）、歌单页（拖动排序）
- 迷你播放栏、全屏播放页（封面模糊背景、跑马灯、三行歌词、进度条拖动气泡）
- 歌词页（当前句居中高亮，手动滑动 3 秒内不自动居中，点击跳转）
- 播放列表弹层（拖动排序、移除）、播放模式（列表循环/随机/单曲）、倍速 0.5×–2×、音质切换
- 右上角「设置」：主题色（跟随封面取色 Palette / 预设 / 自定义）、服务器地址、下载管理、网页管理页入口
- 播放次数排行、本地歌单、分享链接（`/?s=` `/?p=alb` `/?p=art`，并可用这些链接打开 App）
- 下载：歌曲列表右侧点下载，保存音频 + 封面 + 歌词 + 歌曲信息到 `Download/GoMusic/`（音乐 / 封面 / 歌词 / 歌曲信息），并写入本地索引，可在「设置 → 已下载」查看、播放、删除
- 离线模式：连接服务器失败时自动进入，只展示已下载的歌曲 / 歌手 / 专辑 / 歌单，已下载歌曲本地播放、歌词本地读取，顶部可一键重试
- 后台播放 + 通知栏/锁屏控制（MediaSessionService）

## 网络稳定性
- 请求失败自动重试（最多 3 次，退避等待），首次进入多试一次
- DNS 优先解析 IPv4，规避部分 WiFi 下 IPv6 不通导致的 `Software caused connection abort`
- 请求带 `Connection: close`，避免连接复用被中途断开
- 连接超时 20s / 读取 40s，比原来更宽松

## 未实现 / 已知限制
- 管理页 `/dev`（上传转码依赖 ffmpeg.wasm）不在 App 内，设置里有按钮用浏览器打开。
- 多段音频（如 `normal_part1/2.mp3`）目前只播放第一段。
- `/api/song/:ssid` 的 `audio` 字段按「音质 → 地址数组」解析，若线上结构不同，改 `Data.kt` 的 `Api.detail()` 即可。

## 构建 APK
**方式一：Android Studio**  打开本目录 → 等 Gradle 同步 → Build > Build APK(s)。

**方式二：命令行**（需 JDK 17 + Android SDK，设置 `ANDROID_HOME`）
```
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

**方式三：GitHub Actions**  把本目录推到 GitHub 仓库，Actions 页运行 “Build APK”，在 Artifacts 下载 `GoMusic-debug-apk`。
