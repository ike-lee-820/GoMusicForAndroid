@file:OptIn(UnstableApi::class)
package de.myocd.gomusic

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 解码后 PCM 上的均衡器链路。
 *
 * 挂在 [androidx.media3.exoplayer.DefaultRenderersFactory.setAudioProcessors] 上，
 * 所以 10 段增益 / Q 值 / HPF / LPF / 强度 / 响度匹配 / 切换淡入都是真实生效的，
 * 而不是被折叠进系统 5 段 Equalizer。输入输出格式完全一致，
 * MediaSession、通知、队列、进度、音频焦点、缓存全都不受影响。
 */
@UnstableApi
class EqAudioProcessor : BaseAudioProcessor() {
    @Volatile private var sampleRate = 44100
    @Volatile private var channels = 2
    private val engine = EqEngine(sampleRate)
    private val lock = Any()

    fun apply(cfg: EqConfig) {
        synchronized(lock) { engine.update(cfg) }
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 只处理 16bit PCM；其它编码交给下游，不强行接管
        if (input.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate = input.sampleRate
        channels = input.channelCount.coerceAtLeast(1)
        synchronized(lock) { engine.onSampleRateChanged(sampleRate) }
        return input
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes < 2) return
        val shorts = bytes / 2
        val pcm = ShortArray(shorts)
        val dup = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shorts) pcm[i] = dup.getShort()
        inputBuffer.position(inputBuffer.position() + shorts * 2)

        synchronized(lock) {
            if (channels >= 2) engine.process(pcm, shorts / 2)
            else engine.processMono(pcm, shorts)
        }

        val out = replaceOutputBuffer(shorts * 2).order(ByteOrder.LITTLE_ENDIAN)
        out.asShortBuffer().put(pcm)
        out.position(shorts * 2)
        out.flip()
    }

    override fun onFlush() { synchronized(lock) { engine.reset() } }
    override fun onReset() { synchronized(lock) { engine.reset() } }
}
