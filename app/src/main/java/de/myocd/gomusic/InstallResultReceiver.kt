package de.myocd.gomusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * 接收 PackageInstaller 的安装结果。
 *
 * 免确认的自更新装完后不会有任何界面，靠这里知道成功与否；
 * 失败则退回提示，让用户改用浏览器下载方式更新。
 */
class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "de.myocd.gomusic.INSTALL_RESULT"
        const val EXTRA_SESSION = "session_id"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_SESSION, -1)
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        NmLog.note("安装结果 session=$id status=$status msg=$msg")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 系统要求用户确认：把确认界面拉起来
                val act = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (act != null) {
                    runCatching {
                        act.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(act)
                    }.onFailure { NmLog.note("无法打开安装确认：${it.message}") }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> NmLog.note("更新安装完成")
            else -> NmLog.note("更新安装失败：status=$status $msg")
        }
    }
}
