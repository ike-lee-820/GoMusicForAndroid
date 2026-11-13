package de.myocd.gomusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

data class RecAudioSample(val data: ByteArray, val ptsUs: Long, val flags: Int)
data class RecAudio(val format: MediaFormat, val samples: List<RecAudioSample>, val durationUs: Long)

/** 一次录制任务的全部素材。服务与界面之间用内存单例交接，避免大对象进 Intent。 */
data class RecJob(
    val cfg: RecorderConfig,
    val song: Song,
    val audioFile: File,
    val rows: List<LyricRow>,
    val cover: Bitmap?,
    val typeface: Typeface?,
    val durationMs: Long,
)

object RecorderJobHolder {
    @Volatile
    var pending: RecJob? = null
}

/**
 * 视频编码 + 音轨封装。
 *
 * - 画面：MediaCodec AVC 的 input Surface，逐帧用与预览完全相同的 [renderRecFrame] 绘制。
 * - 音频「原声」：MediaExtractor 读出压缩样本，原封不动写进 MediaMuxer，不解码不重编码。
 * - 音频「减弱人声」：解码成 PCM → [VocalReduce] → AAC 编码，再写进同一容器。
 * - 逐帧按真实帧率 pacing，保证 Surface 自动打上的时间戳与音轨时间轴对齐。
 */
class RecorderEngine(private val ctx: Context) {

    fun render(job: RecJob, outFile: File, onProgress: (Int) -> Unit, cancelled: () -> Boolean): String {
        val (w, h) = RecorderPrefs.dims(job.cfg)
        val fps = job.cfg.fps.coerceIn(15, 60)
        val strength = if (job.cfg.vocalMode == "减弱人声") job.cfg.vocalStrength else 0f

        val audio = prepareAudio(job.audioFile, strength)
        val durationMs = if (job.durationMs > 0) job.durationMs else audio.durationUs / 1000L
        val totalFrames = ((durationMs / 1000f) * fps).roundToInt().coerceIn(1, 60 * 60 * fps)

        val vfmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        vfmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        vfmt.setInteger(MediaFormat.KEY_BIT_RATE, job.cfg.bitrateMbps.coerceIn(2, 24) * 1_000_000)
        vfmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        vfmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val vcodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        vcodec.configure(vfmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface: Surface = vcodec.createInputSurface()
        vcodec.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var audioTrack = -1
        var started = false
        val info = MediaCodec.BufferInfo()

        fun drain(end: Boolean) {
            var guard = 0
            while (guard < 2000) {
                guard++
                val idx = vcodec.dequeueOutputBuffer(info, if (end) 10_000L else 0L)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (videoTrack < 0) videoTrack = muxer.addTrack(vcodec.outputFormat)
                    if (audioTrack < 0) audioTrack = muxer.addTrack(audio.format)
                    if (!started) {
                        muxer.start()
                        started = true
                    }
                    continue
                }
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!end) break else continue
                }
                if (idx < 0) break
                val buf = vcodec.getOutputBuffer(idx) ?: break
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && started && videoTrack >= 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer.writeSampleData(videoTrack, buf, info)
                }
                vcodec.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }

        fun drawFrame(i: Int) {
            val cvs = if (Build.VERSION.SDK_INT >= 23) surface.lockHardwareCanvas()
            else surface.lockCanvas(null)
            try {
                val posMs = i * 1000L / fps
                renderRecFrame(
                    cvs,
                    RecFrameState(job.song, job.cover, job.rows, posMs, durationMs),
                    job.cfg,
                    job.typeface,
                )
            } finally {
                surface.unlockCanvasAndPost(cvs)
            }
        }

        // 第一帧触发编码器输出格式；Surface 时间戳基准取这一刻
        val t0Us = System.nanoTime() / 1000L
        drawFrame(0)
        var guard = 0
        while (!started && guard < 200 && !cancelled()) {
            Thread.sleep(10L)
            drain(false)
            guard++
        }
        if (!started) {
            videoTrack = muxer.addTrack(vfmt)
            audioTrack = muxer.addTrack(audio.format)
            muxer.start()
            started = true
        }

        val frameNs = 1_000_000_000L / fps
        var next = System.nanoTime() + frameNs
        for (i in 1 until totalFrames) {
            if (cancelled()) break
            drawFrame(i)
            drain(false)
            if (i % (fps * 2) == 0) onProgress((i * 100L / totalFrames).toInt().coerceIn(0, 99))
            val wait = next - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
            next += frameNs
        }
        vcodec.signalEndOfInput()
        drain(true)
        runCatching { vcodec.stop() }
        runCatching { vcodec.release() }
        runCatching { surface.release() }

        if (started) {
            var first = Long.MAX_VALUE
            for (s in audio.samples) if (s.ptsUs < first) first = s.ptsUs
            if (first == Long.MAX_VALUE) first = 0L
            val bi = MediaCodec.BufferInfo()
            for (s in audio.samples) {
                if (cancelled()) break
                val b = ByteBuffer.wrap(s.data)
                bi.set(0, s.data.size, t0Us + (s.ptsUs - first), s.flags)
                muxer.writeSampleData(audioTrack, b, bi)
            }
        }
        runCatching { muxer.stop() }
        runCatching { muxer.release() }
        onProgress(100)
        return outFile.absolutePath
    }

    /** 原声 = 直接搬运压缩样本；减弱人声 = 解码 → DSP → AAC 重编码 */
    private fun prepareAudio(file: File, strength: Float): RecAudio {
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
        val inFmt = ex.getTrackFormat(ai)
        val durUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION))
            inFmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val sr = if (inFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val ch = if (inFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        if (strength <= 0f) {
            val list = ArrayList<RecAudioSample>()
            val buf = ByteBuffer.allocate(256 * 1024)
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val d = ByteArray(n)
                buf.position(0)
                buf.get(d)
                val fl = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                list.add(RecAudioSample(d, ex.sampleTime, fl))
                ex.advance()
            }
            ex.release()
            return RecAudio(inFmt, list, durUs)
        }

        // ---- Pass 1：解码成裸 PCM 文件（流式，不把整首歌放进内存） ----
        val mime = inFmt.getString(MediaFormat.KEY_MIME)!!
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(inFmt, null, null, 0)
        dec.start()
        val pcm = File(ctx.cacheDir, "rec_pcm_${System.currentTimeMillis()}.raw")
        var fedEos = false
        var decDone = false
        val dbi = MediaCodec.BufferInfo()
        FileOutputStream(pcm).use { fos ->
            var spin = 0
            while (!decDone && spin < 300000) {
                spin++
                if (!fedEos) {
                    val di = dec.dequeueInputBuffer(1000L)
                    if (di >= 0) {
                        val ib = dec.getInputBuffer(di)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(di, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fedEos = true
                        } else {
                            dec.queueInputBuffer(di, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(dbi, 1000L)
                if (oi >= 0) {
                    val ob = dec.getOutputBuffer(oi)!!
                    ob.position(dbi.offset)
                    ob.limit(dbi.offset + dbi.size)
                    val b = ByteArray(dbi.size)
                    ob.get(b)
                    if (dbi.size > 0) fos.write(b)
                    dec.releaseOutputBuffer(oi, false)
                    if (dbi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decDone = true
                }
            }
        }
        runCatching { dec.stop() }
        runCatching { dec.release() }
        ex.release()

        // ---- Pass 2：逐块 DSP + AAC 编码 ----
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch)
        af.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val samples = ArrayList<RecAudioSample>()
        var encFmt: MediaFormat = af
        var encEos = false
        var sentEos = false
        var pts = 0L
        val ebi = MediaCodec.BufferInfo()

        fun drainEnc() {
            while (true) {
                val oi = enc.dequeueOutputBuffer(ebi, 0L)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    encFmt = enc.outputFormat
                    continue
                }
                if (oi < 0) break
                val ob = enc.getOutputBuffer(oi)!!
                ob.position(ebi.offset)
                ob.limit(ebi.offset + ebi.size)
                val d = ByteArray(ebi.size)
                ob.get(d)
                if (ebi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && d.isNotEmpty()) {
                    samples.add(RecAudioSample(d, ebi.presentationTimeUs, ebi.flags))
                }
                enc.releaseOutputBuffer(oi, false)
                if (ebi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encEos = true
            }
        }

        val frameSamples = ch * 2048
        val raw = ByteArray(frameSamples * 2)
        FileInputStream(pcm).use { fis ->
            var spin = 0
            while (spin < 300000) {
                spin++
                drainEnc()
                if (!sentEos) {
                    val n = fis.read(raw)
                    if (n <= 0) {
                        val ei = enc.dequeueInputBuffer(1000L)
                        if (ei >= 0) {
                            enc.queueInputBuffer(ei, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sentEos = true
                        }
                    } else {
                        val sb = ByteBuffer.wrap(raw, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val arr = ShortArray(sb.remaining())
                        sb.get(arr)
                        val proc = VocalReduce.process(arr, arr.size / ch, ch, strength)
                        var off = 0
                        while (off < proc.size) {
                            val ei = enc.dequeueInputBuffer(1000L)
                            if (ei < 0) {
                                drainEnc()
                                continue
                            }
                            val ib = enc.getInputBuffer(ei)!!
                            ib.clear()
                            val cap = ib.capacity() / 2
                            val take = min(cap, proc.size - off)
                            ib.asShortBuffer().put(proc, off, take)
                            enc.queueInputBuffer(ei, 0, take * 2, pts, 0)
                            pts += take.toLong() * 1_000_000L / (sr * ch)
                            off += take
                            drainEnc()
                        }
                    }
                } else if (encEos) break
            }
        }
        drainEnc()
        runCatching { enc.stop() }
        runCatching { enc.release() }
        runCatching { pcm.delete() }
        return RecAudio(encFmt, samples, durUs)
    }
}
