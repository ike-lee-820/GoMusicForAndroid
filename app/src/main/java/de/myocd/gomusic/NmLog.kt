package de.myocd.gomusic

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网易云请求日志。
 *
 * 每次冷启动在「应用文件夹」下生成一个 log_<时间戳>.txt，
 * 记录每一条网易云请求的完整 URL 与完整响应，方便排查
 * 「搜不到专辑 / 播不了 / 解析失败」这类问题。
 *
 * 目录：Android/data/de.myocd.gomusic/files/ （无需存储权限）
 */
object NmLog {
    private const val MAX_FILE = 2L * 1024 * 1024 // 2 MB 后停止写入，避免撑爆存储
    private var file: File? = null
    private var enabled = false

    /** 在 Application / Activity 启动时调用一次 */
    fun init(ctx: Context) {
        if (file != null) return
        enabled = true
        val name = "log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        file = File(ctx.getExternalFilesDir(null), name)
        w("=== GoMusic 网易云日志 ===")
        w("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        w("目录：${file?.parent}")
        w("")
    }

    fun path(): String = file?.absolutePath ?: "(未初始化)"

    private fun w(line: String) {
        val f = file ?: return
        if (!enabled) return
        try {
            if (f.exists() && f.length() > MAX_FILE) {
                f.appendText("[日志已达上限，停止写入]\n")
                enabled = false
                return
            }
            f.appendText(line + "\n")
        } catch (e: Exception) {
            enabled = false
        }
    }

    /** 记录一次请求（含完整 URL；cookie 打码） */
    fun req(url: String) {
        w("[请求] ${now()}")
        w("  URL: ${mask(url)}")
    }

    /** 记录响应（完整 body） */
    fun res(url: String, code: Int?, body: String?) {
        w("[响应] ${now()}  HTTP ${code ?: "-"}  ${mask(url)}")
        w("  ${body ?: "(空)"}")
        w("")
    }

    /** 记录异常 */
    fun err(url: String, e: Throwable) {
        w("[异常] ${now()}  ${mask(url)}")
        w("  ${e.javaClass.simpleName}: ${e.message}")
        w("")
    }

    /** 记录一行说明（用在关键分支上，便于定位走了哪条路） */
    fun note(s: String) {
        w("[说明] ${now()}  $s")
    }

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    /** cookie / 验证码 / 密码 打码，避免日志泄露登录凭证 */
    private val SECRET = Regex("(cookie|captcha|password|md5_password)=([^&]*)")

    private fun mask(u: String): String =
        u.replace(SECRET) { m -> "${m.groupValues[1]}=***(${m.groupValues[2].length}字符)" }
}
