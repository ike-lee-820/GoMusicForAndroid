package de.myocd.gomusic

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 每 100ms 一个能量值，供雪天 AUDIO REACTIVE 使用。 */
class RecEnvelope(private val values: FloatArray, private val bucketMs: Long) {
    val size: Int get() = values.size
    fun at(ms: Long): Float {
        if (values.isEmpty()) return 0.35f
        return values[(ms / bucketMs).toInt().coerceIn(0, values.lastIndex)]
    }
}

/**
 * 不重编码、不改音轨：只把音频解码一遍算 RMS 包络。
 * 仅「雪天」背景启用；失败时由调用方回退到固定能量 0.35。
 */
object RecAudioEnergy {
    private const val BUCKET_MS = 100L

    fun of(file: File, durationMs: Long): RecEnvelope {
        val n = (durationMs / BUCKET_MS).toInt().coerceIn(1, 36000)
        val sums = DoubleArray(n)
        val counts = LongArray(n)
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
        val bi = MediaCodec.BufferInfo()
        var fedEos = false
        var done = false
        var spin = 0
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
                continue
            }
            if (oi >= 0) {
                val ob = dec.getOutputBuffer(oi)
                if (ob != null && bi.size > 0) {
                    ob.position(bi.offset)
                    ob.limit(bi.offset + bi.size)
                    val idx = (bi.presentationTimeUs / 1000L / BUCKET_MS).toInt().coerceIn(0, n - 1)
                    val rms = rmsOf(ob, pcmEnc)
                    sums[idx] += (rms * rms * bi.size).toDouble()
                    counts[idx] += bi.size.toLong()
                }
                dec.releaseOutputBuffer(oi, false)
                if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
            }
        }
        runCatching { dec.stop() }
        runCatching { dec.release() }
        ex.release()
        val raw = FloatArray(n) { i ->
            if (counts[i] > 0) kotlin.math.sqrt(sums[i] / counts[i]).toFloat() else 0f
        }
        val sm = FloatArray(n) { i ->
            val a = raw[(i - 1).coerceAtLeast(0)]
            val b = raw[i]
            val c = raw[(i + 1).coerceAtMost(n - 1)]
            (a + b * 2f + c) / 4f
        }
        val peak = sm.maxOrNull()?.takeIf { it > 0.0001f } ?: 1f
        val out = FloatArray(n) { i -> 0.15f + 0.85f * (sm[i] / peak).coerceIn(0f, 1f) }
        return RecEnvelope(out, BUCKET_MS)
    }

    private fun rmsOf(buf: ByteBuffer, pcmEnc: Int): Float {
        val dup = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return if (pcmEnc == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = dup.asFloatBuffer()
            val n = fb.limit().coerceAtLeast(1)
            var s = 0.0
            while (fb.hasRemaining()) {
                val v = fb.get()
                s += (v * v).toDouble()
            }
            kotlin.math.sqrt(s / n).toFloat()
        } else {
            val sb = dup.asShortBuffer()
            val n = sb.limit().coerceAtLeast(1)
            var s = 0.0
            while (sb.hasRemaining()) {
                val v = sb.get() / 32768f
                s += (v * v).toDouble()
            }
            kotlin.math.sqrt(s / n).toFloat()
        }
    }
}
