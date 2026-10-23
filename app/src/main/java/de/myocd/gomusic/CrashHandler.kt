package de.myocd.gomusic

import android.app.Application
import android.content.Context
import kotlin.system.exitProcess

/**
 * 全局崩溃捕获：把未捕获异常的完整堆栈写进日志文件
 * （/storage/emulated/0/Download/gomusic/log/log_<时间戳>.txt），
 * 这样闪退后能直接看日志定位原因。
 *
 * 写完再交给系统默认处理器，保证弹「应用已停止运行」的行为不变。
 */
object CrashHandler {

    fun install(ctx: Context) {
        val app = ctx.applicationContext as? Application ?: return
        NmLog.init(app)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                NmLog.note("进入崩溃处理 线程=${t.name}")
                NmLog.crash(e, "未捕获异常")
            }
            // 交给系统默认处理（弹崩溃对话框）
            old?.uncaughtException(t, e)
                ?: run {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(10)
                }
        }
    }
}
