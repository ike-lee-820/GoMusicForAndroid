package de.myocd.gomusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * 应用更新检查。
 *
 * 从 GitHub Releases 拿最新版本号与当前版本比对；
 * 所有 GitHub 请求都套一层 gh-proxy.com 加速（失败时自动回退直连）。
 */
object Updater {
    private const val OWNER = "ike-lee-820"
    private const val REPO = "GoMusicForAndroid"
    /** GitHub 加速前缀 */
    private const val PROXY = "https://gh-proxy.com/"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 检查结果 */
    data class Info(
        val latest: String,          // 最新版本号，如 1.3.6
        val url: String,             // APK 下载直链
        val page: String,            // Releases 页面
        val notes: String,           // 更新说明
        val size: Long,              // APK 大小（字节），0 = 未知
        val hasUpdate: Boolean,
    )

    /** 当前版本号 */
    fun current(): String = BuildConfig.VERSION_NAME

    /** 把 "v1.3.5" / "1.3.5" 统一成 "1.3.5" */
    private fun norm(v: String): String = v.trim().removePrefix("v").removePrefix("V")

    /**
     * 比较版本号。返回 >0 表示 a 比 b 新。
     * 逐段按数字比较，段数不同时缺失的段按 0 处理。
     */
    fun compare(a: String, b: String): Int {
        val x = norm(a).split(".")
        val y = norm(b).split(".")
        val n = maxOf(x.size, y.size)
        for (i in 0 until n) {
            val p = x.getOrNull(i)?.toIntOrNull() ?: 0
            val q = y.getOrNull(i)?.toIntOrNull() ?: 0
            if (p != q) return p - q
        }
        return 0
    }

    /** 依次尝试加速地址与直连，先成功的先用 */
    private fun getBody(url: String): String? {
        val urls = listOf(PROXY + url, url)
        for (u in urls) {
            NmLog.note("更新检查请求：$u")
            try {
                val req = Request.Builder().url(u).header("Connection", "close").build()
                Api.client.newCall(req).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    if (r.isSuccessful && t.isNotBlank()) return t
                    NmLog.note("更新检查失败 HTTP ${r.code}：$u")
                }
            } catch (e: Exception) {
                NmLog.note("更新检查异常 ${e.message}：$u")
            }
        }
        return null
    }

    /** 查最新版本；失败返回 null */
    fun check(): Info? {
        val body = getBody(API_LATEST) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return null
            val tag = (root["tag_name"] as? JsonPrimitive)?.content
                ?: (root["name"] as? JsonPrimitive)?.content
                ?: return null
            val latest = norm(tag)
            var url = ""
            var size = 0L
            (root["assets"] as? JsonElement)?.jsonArray?.forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                val name = (o["name"] as? JsonPrimitive)?.content ?: ""
                if (name.endsWith(".apk", true)) {
                    url = (o["browser_download_url"] as? JsonPrimitive)?.content ?: ""
                    size = (o["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                    return@forEach
                }
            }
            val notes = (root["body"] as? JsonPrimitive)?.content?.take(600) ?: ""
            Info(
                latest = latest,
                url = url,
                page = RELEASES_PAGE,
                notes = notes,
                size = size,
                hasUpdate = compare(latest, current()) > 0,
            )
        }.onFailure {
            NmLog.note("更新检查解析失败：${it.message}")
        }.getOrNull()
    }

    /**
     * APK 下载地址：套一层加速，方便在国内网络下直接下载。
     * 原始直链作为兜底交给调用方。
     */
    fun downloadUrl(info: Info): String =
        if (info.url.isBlank()) info.page else PROXY + info.url
}
