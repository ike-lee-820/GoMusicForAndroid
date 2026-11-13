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
 * 不带会话与通知。因此用同源的 media3 FFmpeg 解码扩展（libavcodec）平替：
 * 同样是 FFmpeg 内核，控制面一行不改。EQ 参数体系按 FFmpegKMP 的
 * filter graph / 规范 1.0 落到自写双二阶链上。
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
fun buildKernelRenderersFactory(ctx: Context, eq: EqAudioProcessor): DefaultRenderersFactory {
    NmLog.note("播放内核：FFmpeg 解码优先（MediaCodec 回退），均衡器链路已挂载")
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
            .setAudioProcessors(arrayOf<AudioProcessor>(eq))
            .build()
    }
    // EXTENSION_RENDERER_MODE_PREFER：有 FFmpeg 解码器就用它，缺格式时
    // DefaultRenderersFactory 自己回退 MediaCodec，不会让某首歌播不出来。
    f.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    return f
}
