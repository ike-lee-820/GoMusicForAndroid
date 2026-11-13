@file:OptIn(UnstableApi::class)
package de.myocd.gomusic

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 3D 环绕：解码后 PCM 上的独立空间化链路。
 *
 * 与均衡器并列挂在 AudioSink 的 processor 数组里（EQ -> 3D 环绕 -> 输出），
 * 完全不使用频段增益，所以均衡器全关时依然生效；两种解码内核都覆盖，
 * 因为它工作在解码之后，与解码器无关。
 *
 * 做法：交叉馈送 + 对侧耳 ITD（约 0.25ms）+ 质数抽头早期反射，
 * 150Hz 以下用一阶高通分离出来不参与空间化，避免相位抵消让低音发虚；
 * 输出做软限幅，能量叠加不削波。默认关闭。
 */
@UnstableApi
class SurroundAudioProcessor : BaseAudioProcessor() {
    @Volatile private var sampleRate = 44100
    @Volatile private var channels = 2
    @Volatile private var enabled = false
    @Volatile private var strength = 0.55f
    private val lock = Any()
    private var delayL = FloatArray(0)
    private var delayR = FloatArray(0)
    private var pos = 0
    private var lowL = 0f
    private var lowR = 0f

    fun apply(on: Boolean, s: Float) {
        synchronized(lock) {
            enabled = on
            strength = s.coerceIn(0f, 1f)
        }
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate = input.sampleRate
        channels = input.channelCount.coerceAtLeast(1)
        // 50ms 环形缓冲，足够容纳最长的 37ms 反射抽头
        val n = (sampleRate * 0.05f).toInt().coerceAtLeast(256)
        synchronized(lock) {
            delayL = FloatArray(n)
            delayR = FloatArray(n)
            pos = 0
            lowL = 0f
            lowR = 0f
        }
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
            if (enabled && channels >= 2) process(pcm, shorts / 2)
        }

        val out = replaceOutputBuffer(shorts * 2).order(ByteOrder.LITTLE_ENDIAN)
        out.asShortBuffer().put(pcm)
        out.position(shorts * 2)
        out.flip()
    }

    private fun process(pcm: ShortArray, frames: Int) {
        val n = delayL.size
        if (n == 0) return
        val k = strength
        val itd = (sampleRate * 0.00025f).toInt().coerceIn(1, n / 8)
        val taps = intArrayOf(7, 11, 19, 29, 37)
        val tapIdx = IntArray(taps.size) { (taps[it] * sampleRate / 1000).coerceIn(1, n - 1) }
        for (f in 0 until frames) {
            val l = pcm[f * 2] / 32768f
            val r = pcm[f * 2 + 1] / 32768f
            // 一阶高通：低频原样透传，只对 150Hz 以上做空间化
            lowL += 0.02f * (l - lowL)
            lowR += 0.02f * (r - lowR)
            val hiL = l - lowL
            val hiR = r - lowR
            val crossL = delayR[(pos - itd + n) % n] * 0.72f
            val crossR = delayL[(pos - itd + n) % n] * 0.72f
            var ref = 0f
            for (t in tapIdx) ref += (delayL[(pos - t + n) % n] + delayR[(pos - t + n) % n]) * 0.03f
            delayL[pos] = hiL
            delayR[pos] = hiR
            pos = (pos + 1) % n
            pcm[f * 2] = soft(lowL + hiL + k * (crossL + ref))
            pcm[f * 2 + 1] = soft(lowR + hiR + k * (crossR + ref))
        }
    }

    /** 软限幅到约 -1dBFS，避免交叉馈送叠加后削波 */
    private fun soft(v: Float): Short {
        val x = v * 0.89f
        val y = x / (1f + abs(x) * 0.25f)
        return (y.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }

    override fun onFlush() {
        synchronized(lock) {
            delayL.fill(0f)
            delayR.fill(0f)
            pos = 0
            lowL = 0f
            lowR = 0f
        }
    }

    override fun onReset() { onFlush() }
}

/** UI 与播放服务同进程，直接把开关/强度推给 PCM 链路 */
object SurroundBridge {
    @Volatile private var processor: SurroundAudioProcessor? = null
    @Volatile var on: Boolean = false
    @Volatile var strength: Float = 0.55f

    fun attach(p: SurroundAudioProcessor) {
        processor = p
        p.apply(on, strength)
    }

    fun apply(o: Boolean, s: Float) {
        on = o
        strength = s.coerceIn(0f, 1f)
        runCatching { processor?.apply(o, strength) }
            .onFailure { NmLog.note("3D 环绕应用失败：${it.message}") }
    }
}
