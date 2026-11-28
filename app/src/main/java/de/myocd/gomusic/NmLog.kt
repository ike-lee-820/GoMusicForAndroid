package de.myocd.gomusic

import android.content.Context

/**
 * 26.11.87：日志输出已按要求整体移除。
 *
 * 旧实现会把每条网易云请求 / 响应摘要写进 Download/gomusic/log/，
 * 还会起一条常驻写线程。现在保留同名空壳，让上百个调用点无需改动，
 * 但**不再创建文件、不再启动线程、不再输出任何内容**（连崩溃栈也不写）。
 */
object NmLog {

    fun init(@Suppress("UNUSED_PARAMETER") ctx: Context) { /* no-op */ }

    fun path(): String = ""

    fun installCrashHandler(@Suppress("UNUSED_PARAMETER") ctx: Context) { /* no-op */ }

    fun caught(@Suppress("UNUSED_PARAMETER") where: String, @Suppress("UNUSED_PARAMETER") e: Throwable) { /* no-op */ }

    fun req(@Suppress("UNUSED_PARAMETER") url: String) { /* no-op */ }

    fun res(
        @Suppress("UNUSED_PARAMETER") url: String,
        @Suppress("UNUSED_PARAMETER") code: Int?,
        @Suppress("UNUSED_PARAMETER") body: String?,
    ) { /* no-op */ }

    fun err(@Suppress("UNUSED_PARAMETER") url: String, @Suppress("UNUSED_PARAMETER") e: Throwable) { /* no-op */ }

    fun note(@Suppress("UNUSED_PARAMETER") s: String) { /* no-op */ }

    fun crash(@Suppress("UNUSED_PARAMETER") t: Throwable, @Suppress("UNUSED_PARAMETER") where: String = "") { /* no-op */ }
}
