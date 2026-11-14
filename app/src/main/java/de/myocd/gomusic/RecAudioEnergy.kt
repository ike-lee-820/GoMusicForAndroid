package de.myocd.gomusic

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 每 100ms 一组 (bass, mid, high)，严格对应 sn.html 的 updateAudio()：
 * analyser.fftSize = 2048、smoothingTimeConstant = 0.78，
 * getByteFrequencyData 的分频段 1..7 / 8..59 / 60..n*0.8，
 * 三路 lerp 系数 0.28 / 0.20 / 0.16 由 SnowEffect 施加。
 *
 * Android 没有 AnalyserNode，这里用 2048 点 FFT + 汉宁窗 + dB→byte 映射做等价
 * 预计算；只解码、不改音轨、不重编码。拿不到包络时调用方回退到全 0。
 */
class RecEnvelope(private val values: FloatArray, private val bucketMs: Long) {
    val size: Int get() = values.size / 3

    fun bands(ms: Long): FloatArray {
        if (values.isEmpty()) return floatArrayOf(0f, 0f, 0f)
        val i = (ms / bucketMs).toInt().coerceIn(0, size - 1) * 3
        return floatArrayOf(values[i], values[i + 1], values[i + 2])
    }

    /** 兼容旧调用，返回中频能量。 */
    fun at(ms: Long): Float = bands(ms)[1]
}

object RecAudioEnergy {
    private const val BUCKET_MS = 100L
    private const val FFT_SIZE = 2048
    private const val HALF = FFT_SIZE / 2
    private const val SMOOTHING = 0.78f
    private const val MIN_DB = -100.0
    private const val MAX_DB = -30.0
    private const val HI_END = 819

    fun of(file: File, durationMs: Long): RecEnvelope {
        val n = (durationMs / BUCKET_MS).toInt().coerceIn(1, 36000)
        val sums = Array(n) { DoubleArray(3) }
        val counts = IntArray(n)

        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        var ai = -1
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                ai = i
                break
            }
        }
        if (ai < 0) {
            ex.release()
            error("音频文件里没有音轨")
        }
        ex.selectTrack(ai)
        val fmt = ex.getTrackFormat(ai)
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(fmt, null, null, 0)
        dec.start()

        var pcmEnc = AudioFormat.ENCODING_PCM_16BIT
        var channels = 1
        val bi = MediaCodec.BufferInfo()
        var fedEos = false
        var done = false
        var spin = 0

        val win = FloatArray(FFT_SIZE)
        var winPos = 0
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val hann = FloatArray(FFT_SIZE) { i ->
            (0.5 - 0.5 * cos(2.0 * kotlin.math.PI * i / FFT_SIZE)).toFloat()
        }
        val smooth = FloatArray(HALF)

        fun analyze(timeUs: Long) {
            for (i in 0 until FFT_SIZE) {
                re[i] = win[i] * hann[i]
                im[i] = 0f
            }
            fft(re, im)
            var b = 0.0
            var m = 0.0
            var h = 0.0
            for (k in 0 until HALF) {
                val mag = 2.0 * sqrt((re[k] * re[k] + im[k] * im[k]).toDouble()) / FFT_SIZE
                val db = 20.0 * log10(mag + 1e-9)
                val byte = ((db - MIN_DB) / (MAX_DB - MIN_DB) * 255.0)
                    .coerceIn(0.0, 255.0).toFloat()
                val sm = SMOOTHING * smooth[k] + (1f - SMOOTHING) * byte
                smooth[k] = sm
                if (k in 1..7) b += sm.toDouble()
                else if (k in 8..59) m += sm.toDouble()
                else if (k in 60 until HI_END) h += sm.toDouble()
            }
            val idx = (timeUs / 1000L / BUCKET_MS).toInt().coerceIn(0, n - 1)
            sums[idx][0] += b / (7 * 255.0)
            sums[idx][1] += m / (52 * 255.0)
            sums[idx][2] += h / ((HI_END - 60) * 255.0)
            counts[idx]++
            winPos = 0
        }

        while (!done && spin < 120000) {
            spin++
            if (!fedEos) {
                val ii = dec.dequeueInputBuffer(1000L)
                if (ii >= 0) {
                    val ib = dec.getInputBuffer(ii)!!
                    val sz = ex.readSampleData(ib, 0)
                    if (sz < 0) {
                        dec.queueInputBuffer(ii, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        fedEos = true
                    } else {
                        dec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val oi = dec.dequeueOutputBuffer(bi, 1000L)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = dec.outputFormat
                pcmEnc = if (of.containsKey("pcm-encoding"))
                    of.getInteger("pcm-encoding") else AudioFormat.ENCODING_PCM_16BIT
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
                }
                continue
            }
            if (oi >= 0) {
                val ob = dec.getOutputBuffer(oi)
                if (ob != null && bi.size > 0) {
                    ob.position(bi.offset)
                    ob.limit(bi.offset + bi.size)
                    val dup = ob.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    if (pcmEnc == AudioFormat.ENCODING_PCM_FLOAT) {
                        val fb = dup.asFloatBuffer()
                        val frames = fb.limit() / channels
                        for (f in 0 until frames) {
                            var v = 0f
                            for (ch in 0 until channels) v += fb.get()
                            win[winPos++] = v / channels.toFloat()
                            if (winPos == FFT_SIZE) analyze(bi.presentationTimeUs)
                        }
                    } else {
                        val sb = dup.asShortBuffer()
                        val frames = sb.limit() / channels
                        for (f in 0 until frames) {
                            var v = 0f
                            for (ch in 0 until channels) v += sb.get() / 32768f
                            win[winPos++] = v / channels.toFloat()
                            if (winPos == FFT_SIZE) analyze(bi.presentationTimeUs)
                        }
                    }
                }
                dec.releaseOutputBuffer(oi, false)
                if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
            }
        }
        runCatching { dec.stop() }
        runCatching { dec.release() }
        ex.release()

        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val c = counts[i].coerceAtLeast(1)
            out[i * 3] = (sums[i][0] / c).toFloat().coerceIn(0f, 1f)
            out[i * 3 + 1] = (sums[i][1] / c).toFloat().coerceIn(0f, 1f)
            out[i * 3 + 2] = (sums[i][2] / c).toFloat().coerceIn(0f, 1f)
        }
        return RecEnvelope(out, BUCKET_MS)
    }

    /** 就地基 2 Cooley-Tukey FFT，长度固定 2048。 */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var i = 0
        var j = 0
        while (i < n - 1) {
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
            i++
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * kotlin.math.PI / len
            val wr = cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var base = 0
            while (base < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = base + k
                    val bIdx = a + len / 2
                    val ur = re[a]
                    val ui = im[a]
                    val vr = re[bIdx] * cr - im[bIdx] * ci
                    val vi = re[bIdx] * ci + im[bIdx] * cr
                    re[a] = ur + vr
                    im[a] = ui + vi
                    re[bIdx] = ur - vr
                    im[bIdx] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                base += len
            }
            len = len shl 1
        }
    }
}
