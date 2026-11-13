# GoMusic 26.11.25 更新说明

版本：26.11.25（versionCode 127，可直接覆盖 26.11.24）
主题：修复录制时「Failed to add the track to the muxer」导致录制未完成

这版只动录制链路的音轨封装与错误提示。播放器、均衡器、3D 环绕、环绕/背景特效、录制页的样式与选项、更新说明页面、登录与音质逐档回退等全部保持原样。

## 一、问题现象

录制《K歌之王 (Live)》一类歌曲时，通知直接变成：

```
录制未完成
K歌之王 (Live) · 录制失败: Failed to add the track to the muxer
```

任务停在封装阶段，成品没有生成，且提示是一句英文系统异常，看不出该改什么。

## 二、根因

不是下载失败，也不是编码器或唤醒锁的问题，而是**音轨格式与容器不匹配**：

1. 26.11.22 起录制严格使用界面所选的那一档音质，不再降档、不换文件、不复用别的缓存。高音质档位（Hi-Res / 无损 / 超清母带等）服务端返回的常常是 FLAC、APE、WAV 这类无损容器。
2. `RecorderEngine.prepareAudio()` 在「原声」模式下，把 `MediaExtractor.getTrackFormat()` 拿到的容器格式**原样**交给 `MediaMuxer.addTrack()`。
3. MP4 的 MediaMuxer 只接受 AAC（及部分设备上的 MP3 / AC-3 / E-AC-3）音轨。遇到 `audio/flac` 就抛 `IllegalArgumentException: Failed to add the track to the muxer`。
4. 这句异常在 `RecorderService` 里被 `e.message` 原样塞进失败通知，于是你看到的就是那行英文。

另外两处放大了问题：`drain()` 里视频轨与音轨一起添加、紧接着 `muxer.start()`，音轨一失败视频轨已经加好，异常直接冒泡终止；`prepareAudio` 返回的格式还带着容器头的 `duration`、`max-input-size` 等冗余键，某些 muxer 实现也会挑。

## 三、修复内容

**1. 音轨格式白名单 + 自动转码**

新增 `isMuxableAudio()`：`audio/mp4a-latm`（AAC）、`audio/mpeg`、`audio/ac3`、`audio/eac3` 才允许压缩样本原样封装；其余一律先解码成 PCM、再编成 AAC 后写入 MP4。转码时 `strength = 0`，**跳过 VocalReduce**，不改音色、不做人声处理，只是换容器。

这点必须说清：MP4 容器装不了 FLAC/APE，这是容器格式限制，不是偷偷降音质。你选的仍是那一档文件，只是把音频流重新编码成 AAC 才能封装；AAC / M4A 源依旧零重编码、原样嵌入。

**2. 容器格式清洗**

`sanitizeAudioFormat()` 只保留 mime、采样率、声道数、码率、AAC profile、`csd-*`，去掉 `duration` / `max-input-size` / `track-id` 等，避免封装器因多余键拒绝。

**3. 加轨与启动加保护**

- 视频轨、音轨各自经 `addVideoTrack()` / `addAudioTrack()` 添加，异常转成中文诊断（含实际 mime 与可操作建议）
- 编码器未按时回调格式时的兜底路径复用同一套方法，并先判断 `videoTrack < 0` / `audioTrack < 0`，不会重复加轨
- 启动后记一行日志：视频轨与音轨已添加、音轨 mime

**4. 解码器与错误提示**

- 本机没有对应格式解码器时，提示「本机无法解码 xxx 音质，请改选标准 / 较高 / 极高音质后重试」，不再抛英文
- `RecorderService` 增加 `humanizeError()`，把 `Failed to add the track` 等系统异常映射成中文，通知不再直接显示堆栈
- 进度文案区分「DSP 后」与「转码后」，避免没开人声处理却显示在处理人声

## 四、验证方式

1. 录制页选一个高音质档位（此前会失败的那首，例如 Live 版），保持「原声」开始生成
2. 步骤 2 应显示「源格式 audio/flac 需转码为 AAC 才能封装」一类文案，而不是直接失败
3. 通知推进到 100%，成品出现在 影片/GoMusic；日志里会有：
   - `录制音频：源格式 … 不能直接封装进 MP4，自动转码为 AAC（不做人声处理）`
   - `录制引擎：视频轨与音轨已添加，muxer 启动（音轨 mime=audio/mp4a-latm）`
4. 选标准 / 较高（AAC 源）再录一次，日志应显示「压缩样本直接封装，不重编码」

## 五、限制与说明

- 改动以 CI 编译与签名校验为准，未做实机画面与听感验收；转 AAC 会有一次有损编码，追求完全无损原样嵌入请选本身就是 AAC 的档位
- APE / DSD 等格式若本机没有 MediaCodec 解码器，会明确报错并提示换档，不会静默失败
- 26.11.24 的静态层预渲染、相同帧复用、四种背景、左对齐与独立字号、长歌词换行、8–96sp 字号全部保留
- 本轮提供的外部资源链接解析服务全部失败，接口与渲染逻辑按对话正文中的既有实现对齐，未据解析结果改写任何字段
