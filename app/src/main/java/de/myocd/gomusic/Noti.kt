package de.myocd.gomusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/** 下载进度/结果通知（权限未授予时静默失败，不影响下载） */
class Noti(private val ctx: Context) {
    private val nm: NotificationManager? =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val id = 2001

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = this.nm ?: return
        if (nm.getNotificationChannel(CH) != null) return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CH, "下载", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** [percent] 0–100，未知传 -1；[speed] 形如 "1.2 MB/s" */
    fun progress(title: String, index: Int, total: Int, percent: Int, speed: String, size: String) {
        val text = buildString {
            if (speed.isNotBlank()) append("$speed · ")
            if (size.isNotBlank()) append("$size · ")
            if (percent >= 0) append("$percent%")
            if (isBlank()) append("正在准备")
        }
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("正在下载 $index/$total：$title")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (percent < 0) 0 else percent, percent < 0)
            .build()
        runCatching { nm?.notify(id, n) }
    }

    fun done(text: String) {
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("GoMusic")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching { nm?.notify(id + 1, n) }
    }

    fun cancel() {
        runCatching { nm?.cancel(id) }
    }

    companion object {
        private const val CH = "gomusic_download"

        /** 字节数格式化：12.3 MB */
        fun size(bytes: Long): String {
            if (bytes <= 0) return ""
            val kb = bytes / 1024.0
            return when {
                kb < 1024 -> "%.0f KB".format(kb)
                kb < 1024 * 1024 -> "%.1f MB".format(kb / 1024)
                else -> "%.2f GB".format(kb / 1024 / 1024)
            }
        }

        /** 速度格式化：1.2 MB/s */
        fun speed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0) return ""
            return "${size(bytesPerSec)}/s"
        }
    }
}
