@file:OptIn(UnstableApi::class)
package de.myocd.gomusic

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * 播放内核：FFmpeg 解码优先 + 均衡器 PCM 链路。
 *
 * 「功能完全不变」是硬要求：MediaSession / 通知 / 锁屏 / 队列 / 进度 /
 * 音频焦点 / 唤醒锁 / 边播边缓存 / 音质切换全部继续由 Media3 驱动，
 * 这里只替换解码器，并在解码后的 PCM 上挂一条音效链。
 *
 * FFmpegKMP 的 Maven 模块只有 Kotlin API，native FFmpeg 运行时需要本地
 * NDK 编译（且要求 Kotlin 2.4.20）；它的 AudioPlayer 又直出 AudioTrack、
 * 不带会话与通知。Google 的 media3 FFmpeg 解码扩展同样只发布 Java 壳，
 * 不发布带 .so 的 AAR。因此内核做成可插拔：检测到 FFmpeg 渲染器就
 * 优先用 libavcodec 解码，没带原生库时自动由 MediaCodec 接管 ——
 * 控制面一行不改，两种情况下播放能力完全一致。
 * EQ 参数体系按 FFmpegKMP 的 filter graph / 规范 1.0 落到自写双二阶链上。
 */
object EqBridge {
    @Volatile var config: EqConfig = EQ_FLAT
    @Volatile private var processor: EqAudioProcessor? = null

    /** 服务重建时挂上新的处理器，并把当前配置补回去 */
    fun attach(p: EqAudioProcessor) {
        processor = p
        p.apply(config)
    }

    /** UI 改配置时即刻生效（同一进程，无需跨进程通信） */
    fun apply(cfg: EqConfig) {
        config = cfg
        runCatching { processor?.apply(cfg) }
            .onFailure { NmLog.note("均衡器应用失败：${it.message}") }
    }
}

@UnstableApi
fun buildKernelRenderersFactory(
    ctx: Context,
    eq: EqAudioProcessor,
    surround: SurroundAudioProcessor,
): DefaultRenderersFactory {
    // FFmpeg 渲染器是反射加载的：把 decoder_ffmpeg 的 AAR 放进 app/libs 就会命中，
    // 没带原生库也不会崩，MediaCodec 照常接管。日志里能直接看到当前内核。
    val ffmpeg = runCatching {
        Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
        true
    }.getOrDefault(false)
    NmLog.note(
        if (ffmpeg) "播放内核：FFmpeg(libavcodec) 解码优先，均衡器 PCM 链路已挂载"
        else "播放内核：未随包提供 FFmpeg 原生库，使用 MediaCodec；均衡器 PCM 链路已挂载",
    )
    // DefaultRenderersFactory 没有公开的 setAudioProcessors，音效链要通过
    // AudioSink 注入；1.4.1 的 buildAudioSink 是三参数版本。
    val f = object : DefaultRenderersFactory(ctx) {
        @Suppress("DEPRECATION")
        protected override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(eq, surround))
            .build()
    }
    f.setExtensionRendererMode(
        if (ffmpeg) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
    )
    return f
}
