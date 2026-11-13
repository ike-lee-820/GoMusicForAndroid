package de.myocd.gomusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 录制跑在前台服务里：退到后台、熄屏都不会被系统掐掉，通知持续显示进度。 */
class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var cancelled = false
    @Volatile
    private var recId: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "视频录制", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            if (recId.isNotBlank()) {
                RecorderHistory.update(applicationContext, recId) {
                    it.copy(state = "已取消", note = "已取消", stage = "已取消", detail = "用户取消", finishedAt = System.currentTimeMillis())
                }
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTI_ID, notif("正在准备录制…", "即将开始", 0))
        val job = RecorderJobHolder.pending
        if (job == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        cancelled = false
        RecorderHistory.load(applicationContext)
        recId = "rec_" + System.currentTimeMillis().toString()
        RecorderHistory.add(applicationContext, RecRecord(
            id = recId,
            title = job.song.title,
            artist = job.song.artist,
            style = job.cfg.style,
            quality = if (job.actualQuality.isNotBlank()) job.actualQuality else job.cfg.quality,
            resolution = job.cfg.resolution,
            aspect = job.cfg.aspect,
            fps = job.cfg.fps,
            startedAt = System.currentTimeMillis(),
            state = "进行中",
            progress = 0,
            stage = "步骤 1/3 · 下载素材",
            detail = "准备下载所选音质与歌词…",
        ))
        scope.launch {
            val tmp = File(cacheDir, "recording_${System.currentTimeMillis()}.mp4")
            try {
                notifyProgress(RecProgress(0, "步骤 1/3 · 下载素材", "准备下载所选音质与歌词…"))
                val inputs = withContext(Dispatchers.IO) {
                    RecorderFlow.prepareInputs(applicationContext, job.cfg, job.song, ::notifyProgress)
                }
                val prepared = job.copy(
                    audioFile = inputs.audioFile,
                    rows = inputs.rows,
                    actualQuality = inputs.quality,
                )
                RecorderEngine(applicationContext).render(
                    prepared, tmp,
                    onProgress = { p -> notifyProgress(p) },
                    cancelled = { cancelled },
                )
                if (!cancelled) {
                    val where = saveToMovies(tmp, job.song)
                    RecorderHistory.update(applicationContext, recId) {
                        it.copy(state = "已完成", progress = 100, output = where,
                            stage = "完成", detail = "已保存到 影片/GoMusic",
                            finishedAt = System.currentTimeMillis())
                    }
                    notifyDone(job.song.title, where)
                }
            } catch (e: Exception) {
                if (!cancelled) {
                    val why = e.message ?: "未知错误"
                    RecorderHistory.update(applicationContext, recId) {
                        it.copy(state = "失败", note = why, stage = "失败", detail = why,
                            finishedAt = System.currentTimeMillis())
                    }
                    notifyDone(job.song.title, "录制失败：$why")
                }
            } finally {
                if (recId.isNotBlank()) {
                    RecorderHistory.update(applicationContext, recId) { r ->
                        if (r.state == "进行中") r.copy(state = "已取消", note = "任务中断",
                            stage = "已取消", detail = "任务中断",
                            finishedAt = System.currentTimeMillis()) else r
                    }
                }
                runCatching { tmp.delete() }
                RecorderJobHolder.pending = null
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelled = true
        scope.cancel()
    }

    private fun notif(stage: String, detail: String, percent: Int) = NotificationCompat.Builder(this, CH)
        .setSmallIcon(R.drawable.ic_fg)
        .setContentTitle("录制：${RecorderJobHolder.pending?.song?.title ?: "歌曲"}")
        .setContentText("$stage · $detail")
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
        .addAction(
            R.drawable.ic_fg, "取消",
            PendingIntent.getService(
                this, 2,
                Intent(this, RecorderService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun notifyProgress(p: RecProgress) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTI_ID, notif(p.stage, p.detail, p.percent))
        }
        if (recId.isNotBlank()) RecorderHistory.update(applicationContext, recId) {
            it.copy(progress = p.percent, stage = p.stage, detail = p.detail)
        }
    }

    private fun notifyDone(title: String, where: String) {
        val failed = where.startsWith("录制失败")
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(if (failed) "录制未完成" else "录制完成")
            .setContentText(if (failed) "$title · $where" else "$title · 已保存到 影片/GoMusic")
            .setAutoCancel(true)
            .build()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTI_ID + 1, n)
        }
    }

    private fun saveToMovies(src: File, song: Song): String {
        val safe = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "GoMusic" }
        val name = "GoMusic_${safe}_${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GoMusic")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching {
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            }.getOrNull()
            if (uri != null) {
                runCatching {
                    contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                        java.io.FileOutputStream(pfd.fileDescriptor).use { out ->
                            src.inputStream().use { it.copyTo(out) }
                        }
                    }
                    cv.clear()
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, cv, null, null)
                }
                return uri.toString()
            }
        }
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "GoMusic")
        dir.mkdirs()
        val dst = File(dir, name)
        src.copyTo(dst, true)
        return dst.absolutePath
    }

    companion object {
        const val ACTION_CANCEL = "de.myocd.gomusic.REC_CANCEL"
        private const val CH = "gomusic_record"
        private const val NOTI_ID = 3101
    }
}
