package de.myocd.gomusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.media.AudioFormat
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
    /** 实际拿到并嵌入的音质档位（严格等于界面所选档位） */
    val actualQuality: String = "",
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
 * - 离屏快速编码：不再按真实帧率 sleep，输出帧时间戳按虚拟时间轴重写，音画仍严格对齐。
 */
class RecorderEngine(private val ctx: Context) {

    fun render(job: RecJob, outFile: File, onProgress: (RecProgress) -> Unit, cancelled: () -> Boolean): String {
        val (w, h) = RecorderPrefs.dims(job.cfg)
        val fps = job.cfg.fps.coerceIn(15, 60)
        // 兼容旧配置里可能出现的英文值，避免人声模式被误判成「原声」
        val strength = if (job.cfg.vocalMode in setOf("减弱人声", "reduce", "vocal", "伴奏"))
            job.cfg.vocalStrength.coerceIn(0f, 1f) else 0f
        NmLog.note(
            "录制引擎：人声=${job.cfg.vocalMode} 强度=${(strength * 100).toInt()}% " +
                "比例=${job.cfg.aspect} 分辨率=${job.cfg.resolution} 样式=${job.cfg.style}"
        )

        onProgress(RecProgress(30, "步骤 2/3 · 音频处理", "读取音轨与真实时长…"))
        val audio = prepareAudio(job.audioFile, strength) { p, d ->
            onProgress(RecProgress(30 + (p * 25 / 100), "步骤 2/3 · 音频处理", d))
        }
        onProgress(
            RecProgress(
                55,
                "步骤 3/3 · 录制视频",
                "素材就绪；音频首样本与视频首帧都对齐到 0",
            )
        )
        // 以音频文件真实时长为准：播放器的 durationMs 含缓冲估算，拿它当视频长度会让成品音画长短不一致。
        val durationMs = if (audio.durationUs > 0L) audio.durationUs / 1000L
            else if (job.durationMs > 0) job.durationMs else 180_000L
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
        // 离屏快速编码时 Surface 打的是「墙上时间」，这里按输出顺序给每帧
        // 重新分配从 0 开始的虚拟时间戳，速度不受歌曲时长限制。
        var frameOut = 0

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
                    info.presentationTimeUs = frameOut++ * 1_000_000L / fps
                    muxer.writeSampleData(videoTrack, buf, info)
                }
                vcodec.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }

        // 后台/熄屏时硬件 Canvas 依赖的 GPU 会被挂起，lockHardwareCanvas 会抛异常
        // 或提交空白帧（成品白屏）。优先 CPU 软件 Canvas，失败再回退硬件路径。
        fun drawFrame(i: Int) {
            var last: Throwable? = null
            for (attempt in 0 until 3) {
                val hardware = attempt == 2
                var cvs: Canvas? = null
                runCatching {
                    cvs = if (hardware && Build.VERSION.SDK_INT >= 23) surface.lockHardwareCanvas()
                    else surface.lockCanvas(null)
                }.onFailure { last = it }
                val c = cvs
                if (c != null) {
                    var ok = false
                    try {
                        val posMs = i * 1000L / fps
                        renderRecFrame(
                            c,
                            RecFrameState(job.song, job.cover, job.rows, posMs, durationMs),
                            job.cfg,
                            job.typeface,
                        )
                        ok = true
                    } catch (e: Throwable) {
                        last = e
                    } finally {
                        runCatching { surface.unlockCanvasAndPost(c) }
                    }
                    if (ok) return
                }
                Thread.sleep(20L)
            }
            throw IllegalStateException(
                "第 $i 帧绘制失败（软件 / 硬件 Canvas 都不可用）：${last?.message}", last
            )
        }

        // 第一帧触发编码器输出格式；时间戳全部由 drain 改写，不再依赖墙上时钟
        onProgress(
            RecProgress(
                55,
                "步骤 3/3 · 录制视频",
                "视频 ${w}×${h} · ${fps}fps · ${durationMs / 1000L}s，配置编码器…",
            )
        )
        drawFrame(0)
        var guard = 0
        while (!started && guard < 600 && !cancelled()) {
            drain(false)
            // 部分编码器要收到两三帧才回调 output format，只画一帧会一直等不到
            if (!started && guard > 0 && guard % 20 == 0) runCatching { drawFrame(0) }
            if (!started) Thread.sleep(10L)
            guard++
        }
        if (!started) {
            // 绝不能用 configure 时的 vfmt：不含 SPS/PPS，很多播放器会直接白屏。
            // 用编码器当前实际输出格式；拿不到就明确报错，而不是写一条缺 csd 的轨。
            val of = runCatching { vcodec.outputFormat }.getOrElse {
                error("视频编码器未输出格式，无法添加视频轨：${it.message}")
            }
            videoTrack = muxer.addTrack(of)
            audioTrack = muxer.addTrack(audio.format)
            muxer.start()
            started = true
            NmLog.note("录制引擎：编码器未按时回调格式，已用运行时输出格式启动 muxer")
        }

        // 不再按真实帧率 sleep：Surface 缓冲队列满时 lockHardwareCanvas 会自然
        // 阻塞形成背压，编码速度只取决于 CPU / 编码器吞吐，通常比实时快数倍。
        for (i in 1 until totalFrames) {
            if (cancelled()) break
            try {
                drawFrame(i)
            } catch (e: Throwable) {
                NmLog.note("录制引擎：第 $i / $totalFrames 帧连续失败，终止：${e.message}")
                throw e
            }
            drain(false)
            if (i % (fps / 2).coerceAtLeast(1) == 0)
                onProgress(
                    RecProgress(
                        55 + (i * 44L / totalFrames).toInt().coerceIn(0, 44),
                        "步骤 3/3 · 录制视频",
                        "第 $i / $totalFrames 帧（歌词按时间轴同步）",
                    )
                )
        }
        vcodec.signalEndOfInputStream()
        drain(true)
        runCatching { vcodec.stop() }
        runCatching { vcodec.release() }
        runCatching { surface.release() }

        if (started) {
            var first = Long.MAX_VALUE
            for (s in audio.samples) if (s.ptsUs < first) first = s.ptsUs
            if (first == Long.MAX_VALUE) first = 0L
            NmLog.note("音画对齐：视频首帧 PTS=0，音频首样本 PTS=${first}us，输出统一减去 first")
            val bi = MediaCodec.BufferInfo()
            for (s in audio.samples) {
                if (cancelled()) break
                val b = ByteBuffer.wrap(s.data)
                // 与视频同一条从 0 开始的虚拟时间轴
                bi.set(0, s.data.size, (s.ptsUs - first).coerceAtLeast(0L), s.flags)
                muxer.writeSampleData(audioTrack, b, bi)
            }
        }
        runCatching { muxer.stop() }
        runCatching { muxer.release() }
        onProgress(RecProgress(100, "完成", "视频与音频已封装，准备保存到 影片/GoMusic"))
        return outFile.absolutePath
    }

    /** 原声 = 直接搬运压缩样本；减弱人声 = 解码 → DSP → AAC 重编码 */
    private fun prepareAudio(file: File, strength: Float, onStage: (Int, String) -> Unit): RecAudio {
        onStage(5, "读取音轨信息…")
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
        val mime = inFmt.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
        NmLog.note("录制音频：mime=$mime 输入 sr=$sr ch=$ch 时长=${durUs / 1000L}ms 人声强度=${(strength * 100).toInt()}%")

        if (strength <= 0f) {
            onStage(100, "原声：压缩样本直接封装，不重编码")
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

        onStage(15, "按强度 ${(strength * 100).toInt()}% 解码为 PCM…")
        // ---- Pass 1：解码成裸 PCM 文件（流式，不把整首歌放进内存） ----
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
        // 解码器实际输出格式可能和容器头不同（SBR/PS、float PCM、24bit 等），
        // 按容器头的 ch 去解析 PCM 会把左右声道错位，人声抵消自然「无效」。
        val decOut = runCatching { dec.outputFormat }.getOrNull()
        val outSr = if (decOut != null && decOut.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            decOut.getInteger(MediaFormat.KEY_SAMPLE_RATE) else sr
        val outCh = if (decOut != null && decOut.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            decOut.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else ch
        val pcmEnc = if (decOut != null && decOut.containsKey("pcm-encoding"))
            decOut.getInteger("pcm-encoding") else AudioFormat.ENCODING_PCM_16BIT
        runCatching { dec.stop() }
        runCatching { dec.release() }
        ex.release()
        NmLog.note("录制音频：解码输出 sr=$outSr ch=$outCh pcmEnc=$pcmEnc")
        onStage(45, "中置抵消 / 低频回补处理人声…")

        // ---- Pass 2：逐块 DSP + AAC 编码 ----
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outSr, outCh)
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

        fun drainEnc(waitMs: Long = 1L) {
            while (true) {
                val oi = enc.dequeueOutputBuffer(ebi, waitMs)
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

        onStage(65, "AAC 编码处理后的音频…")
        val bytesPerSample = if (pcmEnc == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frameSamples = outCh * 2048
        val raw = ByteArray(frameSamples * bytesPerSample)
        var dspLogged = false
        fun rms(a: ShortArray): Float {
            var sum = 0.0
            for (v in a) { val f = v / 32768f; sum += f * f }
            return kotlin.math.sqrt(sum / a.size.coerceAtLeast(1)).toFloat()
        }
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
                        val buf = ByteBuffer.wrap(raw, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                        val arr = if (pcmEnc == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            val fl = FloatArray(fb.remaining())
                            fb.get(fl)
                            ShortArray(fl.size) { k -> (fl[k].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                        } else {
                            val sb = buf.asShortBuffer()
                            val a = ShortArray(sb.remaining())
                            sb.get(a)
                            a
                        }
                        val proc = VocalReduce.process(arr, arr.size / outCh, outCh, strength)
                        if (!dspLogged) {
                            dspLogged = true
                            NmLog.note(
                                "人声处理生效：ch=$outCh sr=$outSr 强度=${(strength * 100).toInt()}% " +
                                    "首块 RMS ${rms(arr)} -> ${rms(proc)} 样本=${arr.size}"
                            )
                        }
                        var off = 0
                        while (off < proc.size) {
                            val ei = enc.dequeueInputBuffer(1000L)
                            if (ei < 0) {
                                drainEnc()
                                continue
                            }
                            val ib = enc.getInputBuffer(ei)!!
                            ib.clear()
                            // MediaCodec 输入 buffer 是 direct ByteBuffer，默认 BIG_ENDIAN；
                            // 不显式设成小端，写进去的 16bit PCM 会高低字节颠倒，
                            // 处理后听起来就是「没变化 / 一团糊」。
                            ib.order(ByteOrder.LITTLE_ENDIAN)
                            val cap = ib.capacity() / 2
                            val take = min(cap, proc.size - off)
                            ib.asShortBuffer().put(proc, off, take)
                            enc.queueInputBuffer(ei, 0, take * 2, pts, 0)
                            pts += take.toLong() * 1_000_000L / (outSr * outCh)
                            off += take
                            drainEnc()
                        }
                    }
                } else if (encEos) break
            }
        }
        // 非阻塞 drain 会漏掉编码器尾部几帧；等它把 EOS 之前的数据全部吐出
        var tail = 0
        while (!encEos && tail < 300) {
            drainEnc(20L)
            tail++
        }
        drainEnc(20L)
        NmLog.note("录制音频：DSP 后 AAC 样本=${samples.size} 段，EOS=$encEos")
        runCatching { enc.stop() }
        runCatching { enc.release() }
        runCatching { pcm.delete() }
        onStage(100, "音频处理完成")
        return RecAudio(encFmt, samples, durUs)
    }
}
