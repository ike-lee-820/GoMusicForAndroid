package de.myocd.gomusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * 接收 PackageInstaller 的安装结果。
 *
 * - 需要用户确认时，把系统的确认界面拉起来（这是最常见的正常路径）
 * - 成功则提示一下
 * - 失败则退回「用系统安装界面打开 APK」，避免点了更新却毫无反应
 */
class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "de.myocd.gomusic.INSTALL_RESULT"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_FILE = "apk_file"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_SESSION, -1)
        val file = intent.getStringExtra(EXTRA_FILE)
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        NmLog.note("安装结果 session=$id status=$status msg=$msg")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val act = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (act == null) {
                    fallback(ctx, file)
                    return
                }
                val ok = runCatching {
                    act.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(act)
                }.isSuccess
                if (!ok) fallback(ctx, file)
                else toast(ctx, "请在弹出的界面点「安装」")
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // 装完就把缓存目录里的安装包删掉：它只是个临时文件，
                // 留着会让「缓存」白白占着几十 MB
                file?.let { runCatching { java.io.File(it).delete() } }
                toast(ctx, "更新完成")
            }

            else -> {
                NmLog.note("更新安装失败：status=$status $msg")
                // 免确认 / 会话安装被系统拒绝时走这里，退回系统安装界面
                fallback(ctx, file)
            }
        }
    }

    /** 会话安装失败：退回用系统安装界面打开已下载好的 APK */
    private fun fallback(ctx: Context, file: String?) {
        val f = file?.let { java.io.File(it) }
        if (f == null || !f.exists()) {
            toast(ctx, "更新安装失败，请改用「浏览器下载」方式更新")
            return
        }
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", f,
            )
        }.getOrNull()
        if (uri == null) {
            toast(ctx, "更新安装失败，请改用「浏览器下载」方式更新")
            return
        }
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { ctx.startActivity(i) }.isFailure) {
            toast(ctx, "更新安装失败，请改用「浏览器下载」方式更新")
        }
    }

    private fun toast(ctx: Context, s: String) {
        runCatching { Toast.makeText(ctx.applicationContext, s, Toast.LENGTH_LONG).show() }
    }
}
