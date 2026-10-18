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

    fun progress(title: String, index: Int, total: Int) {
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("正在下载 $index/$total")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, true)
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

    companion object {
        private const val CH = "gomusic_download"
    }
}
