package de.myocd.gomusic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 思源宋体（Noto Serif SC，可变字重）按需下载。
 *
 * 之前字体是打包进 APK 的（约 60MB）：仓库按 30MB 分片存、构建前 cat 合并，
 * APK 体积大、构建慢，而且合并这一步一旦漏掉 res/font 就空了，
 * 编译会报一堆看着不相干的错误。
 *
 * 改成用到时才下载：文件存到内部存储，下载过就一直在。
 * 格式是 TTF 可变字体（带 wght 轴），所以字重滑块仍然可用。
 *
 * 源用 GitHub raw，并依次套 gh 加速源 —— 国内直连 raw 通常不通。
 * 下载先写临时文件、完成后再改名，避免半截文件被当成字体加载。
 */
object SongFont {

    private const val PATH =
        "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Serif/Variable/TTF/Subset/NotoSerifSC-VF.ttf"

    /** gh 加速源：把完整 URL 拼在后面即可。都失败就直连 */
    private val MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://gh.llkk.cc/",
        "https://mirror.ghproxy.com/",
        "https://gh-proxy.net/",
    )

    /** 小于这个大小就当没下完：正常字体 25MB 上下 */
    private const val MIN_BYTES = 2_000_000L

    fun file(ctx: Context): File {
        val dir = File(ctx.filesDir, "fonts")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "song.ttf")
    }

    fun has(ctx: Context): Boolean {
        val f = file(ctx)
        return f.exists() && f.length() >= MIN_BYTES
    }

    /** 下载。返回 null 表示成功，否则是失败原因 */
    suspend fun download(ctx: Context, onProgress: (Int) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val dst = file(ctx)
            val tmp = File(dst.parentFile, "song.ttf.tmp")
            val urls = MIRRORS.map { it + PATH } + PATH
            var lastErr = "未知错误"
            for (u in urls) {
                tmp.delete()
                try {
                    val resp = Api.client.newCall(
                        Request.Builder().url(u).header("Connection", "close").build()
                    ).execute()
                    try {
                        if (!resp.isSuccessful) {
                            lastErr = "HTTP ${resp.code}"
                            continue
                        }
                        val body = resp.body
                        if (body == null) {
                            lastErr = "空响应"
                            continue
                        }
                        val total = body.contentLength()
                        body.byteStream().use { ins ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                                    }
                                }
                            }
                        }
                    } finally {
                        resp.close()
                    }
                    if (tmp.exists() && tmp.length() >= MIN_BYTES) {
                        dst.delete()
                        if (tmp.renameTo(dst)) {
                            onProgress(100)
                            return@withContext null
                        }
                        lastErr = "写入失败"
                    } else {
                        lastErr = "文件不完整（${tmp.length()} 字节）"
                    }
                } catch (e: Exception) {
                    lastErr = e.message ?: "网络错误"
                }
            }
            tmp.delete()
            lastErr
        }
}
