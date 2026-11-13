# 无 AI 伴奏提取（原生 DSP，可选）

默认**不参与构建**：CI 不保证带 NDK，关掉可避免整包编译失败。
本版运行时的人声减弱由 `VocalReduce.kt`（中置抵消 + 低频回补）完成，
接口为 `process(pcm: ShortArray, frames: Int, channels: Int, strength: Float)`。

## 启用步骤

1. 在 `app/build.gradle.kts` 的 `android {}` 里取消这两行注释：

```kotlin
externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
```

2. 在 `defaultConfig {}` 里按需加：

```kotlin
ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
```

3. 新增 `SepNative.kt`：

```kotlin
object SepNative {
    val ready = runCatching { System.loadLibrary("separation_dsp") }.isSuccess
    external fun reduce(pcm: ShortArray, frames: Int, channels: Int, strength: Float)
}
```

4. 把 `RecorderEngine.prepareAudio` Pass 2 里的 `VocalReduce.process(...)` 换成
   `if (SepNative.ready) { SepNative.reduce(arr, frames, ch, strength); arr } else VocalReduce.process(...)`。

## 实现说明

`separation_dsp.cpp` 是固定周期 REPET-lite：STFT（2048 窗 / hop 512 / Hann）→
对每个频点用「相隔一个重复周期的若干帧」取中值构建重复背景模型 →
软掩码 `(mag - bg) / mag` 按强度衰减重复分量 → ISTFT 重叠相加重建。
重复成分主要是伴奏，主唱随时间变化会被留下/削弱，配合中置抵消得到近似伴奏。

自适应周期（beat spectrum）、2DFT 二维谱峰掩码、NEON 蝶形矢量化是后续增强点，
参数与验收口径见《均衡器音效预设设计与实现规范》之外的伴奏提取方案文档。
