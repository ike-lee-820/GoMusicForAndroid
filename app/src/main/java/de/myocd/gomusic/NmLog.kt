package de.myocd.gomusic

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.Volatile

/**
 * 网易云请求日志。
 *
 * 输出到 /storage/emulated/0/Download/gomusic/log/log_<时间戳>.txt，
 * 记录每一条网易云请求的 URL 与响应摘要，方便排查
 * 「搜不到专辑 / 播不了 / 解析失败」这类问题。
 *
 * Android 10+ 不能直接 File 写公共目录，用 MediaStore.Downloads 落到同一路径。
 *
 * 26.11.64：改成「有界队列 + 单线程批量落盘」。
 * 旧版 w() 在每个 OkHttp 回调线程里同步 appendText 整个响应体（一次 /search
 * 就有几十 KB，还要打开/关闭文件、flush），而 res() 又在 done.complete() 之前
 * 调用 —— 等于每一路搜索结果都得先写完一次大文件才交给界面。播放、预取、
 * 取地址同时也在抢同一个文件且无锁。这是「点了要等好几秒」最隐蔽的一层。
 * 现在调用点只做一次无阻塞 offer，网络回调立刻返回。
 */
object NmLog {
    private const val MAX_FILE = 4L * 1024 * 1024 // 4 MB 后停止写入
    private const val REL_DIR = "gomusic/log"
    private const val MAX_BODY = 600             // 响应体只记摘要，不再整篇落盘
    private const val QUEUE_CAP = 256

    private var stream: OutputStream? = null
    private var file: File? = null
    private val written = AtomicLong(0L)
    @Volatile private var enabled = false
    @Volatile private var pathText = "(未初始化)"

    private val queue = ArrayBlockingQueue<String>(QUEUE_CAP)

    /** 唯一的写线程：串行、批量 drain，避免并发写同一个文件/OutputStream */
    private val writer = Thread({
        while (true) {
            val first = try {
                queue.take()
            } catch (e: InterruptedException) {
                continue
            }
            val sb = StringBuilder(first)
            val rest = ArrayList<String>(64)
            queue.drainTo(rest, 64)
            rest.forEach { sb.append(it) }
            val text = sb.toString()
            runCatching {
                raw(text)
                written.addAndGet(text.toByteArray(Charsets.UTF_8).size.toLong())
            }.onFailure {
                enabled = false
                // 写不进去就别再攒，避免队列一直塞满
                queue.clear()
            }
        }
    }, "gomusic-log").apply {
        isDaemon = true
        start()
    }

    /** 在 Activity / Service 启动时调用一次 */
    fun init(ctx: Context) {
        if (file != null || stream != null) return
        val name = "log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        enabled = true
        // 1) 直接写（Android 9 及以下，或已授予全部文件访问权）
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            REL_DIR,
        )
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            f.appendText("")
            file = f
            pathText = f.absolutePath
        }
        // 2) 兜底：MediaStore（Android 10+ 写公共 Download 目录）
        if (file == null) {
            runCatching {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/$REL_DIR")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    ?: throw IOException("insert 失败")
                val os = ctx.contentResolver.openOutputStream(uri, "wa")
                    ?: throw IOException("openOutputStream 失败")
                stream = os
                if (Build.VERSION.SDK_INT >= 29) {
                    v.clear()
                    v.put(MediaStore.Downloads.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, v, null, null)
                }
                pathText = "/storage/emulated/0/Download/$REL_DIR/$name"
            }
        }
        w(buildString {
            append("=== GoMusic 网易云日志 ===\n")
            append("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            append("路径：$pathText\n\n")
        })
    }

    fun path(): String = pathText

    /** 绝不阻塞：队列满了直接丢这条日志，也不能拖住网络回调 */
    private fun w(line: String) {
        if (!enabled) return
        if (written.get() > MAX_FILE) {
            enabled = false
            return
        }
        queue.offer(line)
    }

    /** 崩溃路径必须同步落盘：进程马上要死，等不到写线程 */
    private fun wSync(line: String) {
        if (!enabled) return
        runCatching {
            raw(line)
            written.addAndGet(line.toByteArray(Charsets.UTF_8).size.toLong())
        }.onFailure { enabled = false }
    }

    private fun raw(line: String) {
        val f = file
        if (f != null) {
            f.appendText(line)
            return
        }
        val os = stream ?: return
        os.write(line.toByteArray(Charsets.UTF_8))
        os.flush()
    }

    /**
     * 安装全局崩溃捕获：闪退原因写进日志文件，方便定位。
     * 装完仍会走系统默认处理（App 会退出），只多写一份栈到日志。
     */
    fun installCrashHandler(ctx: Context) {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                init(ctx)
                wSync(buildString {
                    append("\n[崩溃] ${now()}\n")
                    append("  线程：${t.name}\n")
                    append("  异常：${e.javaClass.name}: ${e.message}\n")
                    append("  栈：\n")
                    e.stackTrace.take(30).forEach { append("    at $it\n") }
                    append("  原因：\n")
                    var c: Throwable? = e.cause
                    var n = 0
                    while (c != null && n < 5) {
                        append("    ${c.javaClass.name}: ${c.message}\n")
                        c.stackTrace.take(10).forEach { append("      at $it\n") }
                        c = c.cause
                        n++
                    }
                    append("\n")
                })
            }
            old?.uncaughtException(t, e)
        }
    }

    /** 记录一次非致命异常（调用方自己 catch 到的） */
    fun caught(where: String, e: Throwable) {
        w("[捕获] ${now()}  $where\n  ${e.javaClass.name}: ${e.message}\n")
        w("  " + e.stackTrace.take(12).joinToString("\n  ") { "at $it" } + "\n\n")
    }

    /** 记录一次请求（含完整 URL；cookie 打码） */
    fun req(url: String) {
        w("[请求] ${now()}\n  URL: ${mask(url)}\n")
    }

    /** 记录响应摘要：只留前 600 字符，避免把整篇 JSON 写进文件 */
    fun res(url: String, code: Int?, body: String?) {
        val b = body ?: "(空)"
        val brief = if (b.length > MAX_BODY) {
            b.take(MAX_BODY) + "…（共 ${b.length} 字符，已截断）"
        } else b
        w("[响应] ${now()}  HTTP ${code ?: "-"}  ${mask(url)}\n  $brief\n\n")
    }

    /** 记录异常 */
    fun err(url: String, e: Throwable) {
        w("[异常] ${now()}  ${mask(url)}\n  ${e.javaClass.simpleName}: ${e.message}\n\n")
    }

    /** 记录一行说明（用在关键分支上，便于定位走了哪条路） */
    fun note(s: String) {
        w("[说明] ${now()}  $s\n")
    }

    /** 崩溃：写完整堆栈，用于定位闪退原因 */
    fun crash(t: Throwable, where: String = "") {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        wSync(buildString {
            append("\n[崩溃] ${now()}")
            if (where.isNotBlank()) append("  位置：$where")
            append("\n")
            append("  ${t.javaClass.name}: ${t.message}\n")
            append(sw.toString())
            append("\n")
        })
    }

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    /** cookie / 验证码 / 密码 打码，避免日志泄露登录凭证 */
    private val SECRET = Regex("(cookie|captcha|password|md5_password)=([^&]*)")

    private fun mask(u: String): String =
        u.replace(SECRET) { m -> "${m.groupValues[1]}=***(${m.groupValues[2].length}字符)" }
}
