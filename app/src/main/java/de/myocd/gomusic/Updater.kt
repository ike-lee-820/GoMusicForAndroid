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
 * 所有 GitHub 请求都套一层 v6.gh-proxy.com 加速（失败时自动回退直连）。
 */
object Updater {
    private const val OWNER = "ike-lee-820"
    private const val REPO = "GoMusicForAndroid"
    /** GitHub 加速前缀 */
    private const val PROXY = "https://v6.gh-proxy.com/"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    /** 发布列表：用来在多个发布里挑版本号最大的那个 */
    private const val API_RELEASES = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=20"
    val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"
    /** 约定命名的 APK 直链前缀：tag 页面绝不能当下载链接用 */
    private const val DOWNLOAD_BASE = "https://github.com/$OWNER/$REPO/releases/download"
    /** 每版完整更新说明在仓库里的归档目录（纯 ASCII 路径，便于走加速代理） */
    private const val RAW_NOTES_BASE =
        "https://raw.githubusercontent.com/$OWNER/$REPO/main/docs/release-notes/"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 更新专用客户端：不限总时长。
     *
     * 共用 Api.client 有 60s 的 callTimeout，而 APK 有几十 MB，
     * 在慢网络/加速节点不稳定时 60s 根本下不完 —— 下到一半被掐断，
     * 表现就是「下载失败」或一直卡着。检查接口走 GitHub，同样可能很慢，
     * 所以这里只在「连不上」时快速放弃（15s），连上之后不限时。
     * 另外去掉 Connection: close，保持连接复用。
     */
    private val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 检查结果 */
    data class Info(
        val latest: String,          // 最新版本号，如 1.3.6
        val url: String,             // APK 下载直链
        val tag: String = "",        // 原始 tag，用于按约定拼直链
        val extraUrls: List<String> = emptyList(), // 其它 .apk asset 直链
        val page: String,            // Releases 页面
        val notes: String,           // 更新说明（release body 全文，不再截断）
        val size: Long,              // APK 大小（字节），0 = 未知
        val draft: Boolean = false,  // 草稿发布，不参与更新
        val hasUpdate: Boolean,
        val notesUrl: String = "",   // 仓库内该版本完整说明 md 的 raw 地址
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

    /**
     * 加速地址与直连**都**取回来。
     *
     * 26.11.73：以前 getBody() 先成功的就返回，加速节点常缓存到「release 刚创建、
     * APK 还没上传完」那一瞬的旧快照 —— assets 是空数组，解析出的下载直链为空，
     * 后面的 downloadUrl() 就把 html_url（tag 页面）当成 APK 地址去下载，
     * 日志里正是「开始下载 APK：https://github.com/.../releases/tag/v26.11.72」。
     */
    private fun getBodies(url: String): List<String> {
        val out = ArrayList<String>()
        for (u in listOf(PROXY + url, url)) {
            NmLog.note("更新检查请求：$u")
            try {
                val req = Request.Builder().url(u).build()
                client.newCall(req).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    if (r.isSuccessful && t.isNotBlank()) out += t
                    else NmLog.note("更新检查失败 HTTP ${r.code}：$u")
                }
            } catch (e: Exception) {
                NmLog.note("更新检查异常 ${e.message}：$u")
            }
        }
        return out
    }

    /** 解析单个发布条目 */
    private fun parseInfo(root: JsonObject): Info? = runCatching {
        val tag = (root["tag_name"] as? JsonPrimitive)?.content
            ?: (root["name"] as? JsonPrimitive)?.content
            ?: return null
        val latest = norm(tag)
        var size = 0L
        val assetUrls = ArrayList<String>()
        (root["assets"] as? JsonElement)?.jsonArray?.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val name = (o["name"] as? JsonPrimitive)?.content ?: ""
            if (name.endsWith(".apk", true)) {
                val u = (o["browser_download_url"] as? JsonPrimitive)?.content.orEmpty()
                if (u.isNotBlank()) assetUrls += u
                if (size == 0L) size = (o["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            }
        }
        val url = assetUrls.firstOrNull().orEmpty()
        // 以前这里 .take(600) 把说明截断，长文档在应用内永远看不全。
        // GitHub release body 上限 65536 字符，正常更新说明远小于它，直接保留全文。
        val notes = (root["body"] as? JsonPrimitive)?.content.orEmpty()
        Info(
            latest = latest,
            url = url,
            tag = tag,
            extraUrls = assetUrls.drop(1),
            page = (root["html_url"] as? JsonPrimitive)?.content ?: RELEASES_PAGE,
            notes = notes,
            size = size,
            draft = (root["draft"] as? JsonPrimitive)?.content?.toBoolean() ?: false,
            hasUpdate = compare(latest, current()) > 0,
            notesUrl = notesUrlFor(latest),
        )
    }.onFailure {
        NmLog.note("更新检查解析失败：${it.message}")
    }.getOrNull()

    /**
     * 查最新版本；失败返回 null。
     *
     * 优先拉发布列表、取其中版本号最大的那个：
     * /releases/latest 只认非草稿且非预发布的发布，跨大版本发布或被标成
     * 预发布时它会漏掉，导致明明有新版却不提示。列表取最大则不受这些影响。
     */
    fun check(): Info? {
        val all = ArrayList<Info>()
        // 列表接口：代理与直连都解析。代理那份可能缺 assets，直连那份能补上。
        for (body in getBodies(API_RELEASES)) {
            val arr = runCatching {
                json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonArray
            }.getOrNull() ?: continue
            all += arr.mapNotNull { (it as? JsonObject)?.let(::parseInfo) }
                .filter { !it.draft }
        }
        // 列表里已经拿到直链就不必再打单条接口，启动检查不额外多两次请求
        if (all.none { it.url.isNotBlank() }) {
            for (body in getBodies(API_LATEST)) {
                val root = runCatching {
                    json.parseToJsonElement(body) as? JsonObject
                }.getOrNull() ?: continue
                parseInfo(root)?.let { if (!it.draft) all += it }
            }
        }
        if (all.isEmpty()) return null
        // 版本号最大优先；同一版本里「拿到 APK 直链」的优先，
        // 免得选中 assets 为空的那份缓存快照。
        val best = all.maxWith(Comparator { a, b ->
            val c = compare(a.latest, b.latest)
            if (c != 0) c
            else (if (a.url.isNotBlank()) 1 else 0) - (if (b.url.isNotBlank()) 1 else 0)
        })
        NmLog.note("更新检查：最新为 ${best.latest}（当前 ${current()}），直链=${best.url.ifBlank { "空，走约定兜底" }}")
        return best
    }

    /** 某版本完整更新说明的 raw 地址 */
    fun notesUrlFor(version: String): String = RAW_NOTES_BASE + norm(version) + ".md"

    /**
     * 拉取仓库里归档的完整更新说明。
     *
     * 以前这里复用 getBody()：加速节点先返回什么就用什么。代理偶尔只给
     * 文档前半截（或一段错误页），只要非空就会把 release body 的全文覆盖掉，
     * 应用内于是显示「被截断」的说明。现在把两个候选都取回来，按长度挑最完整的，
     * 并且短于 body 90% 的一律不采用 —— 宁可显示 body 全文，也不拿残片替换。
     */
    fun fetchNotes(info: Info): String? {
        val u = info.notesUrl.ifBlank { notesUrlFor(info.latest) }
        val found = mutableListOf<String>()
        for (candidate in listOf(PROXY + u, u)) {
            try {
                val req = Request.Builder().url(candidate).build()
                client.newCall(req).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    if (r.isSuccessful && t.isNotBlank() && looksLikeMd(t)) {
                        found += t
                    } else {
                        NmLog.note("更新文档候选不可用 HTTP ${r.code} 长度=${t.length}")
                    }
                }
            } catch (e: Exception) {
                NmLog.note("更新文档候选异常 ${e.message}")
            }
        }
        val best = found.maxByOrNull { it.length }
        val bodyLen = info.notes.length
        NmLog.note("更新文档：body=$bodyLen raw=${best?.length ?: 0} 候选数=${found.size}")
        if (best == null) return null
        return if (best.length >= (bodyLen * 0.9f).toInt()) best else info.notes.ifBlank { best }
    }

    /** 粗判是否为更新说明 md，避免把代理的错误页当成正文 */
    private fun looksLikeMd(t: String): Boolean =
        t.lines().size > 1 && (
            t.contains("# ") || t.contains("## ") || t.contains("- ") || t.contains("**")
        )

    /**
     * 26.11.73：所有可用于下载的 APK 直链。
     *
     * ① API 给的 browser_download_url；② 按仓库约定命名拼出来的直链。
     * **绝不包含 tag 页面 / releases 页面** —— 那是给人看的 HTML，
     * 拿去当 APK 下载会下到一个网页，安装必然失败。
     */
    fun apkUrls(info: Info): List<String> {
        val out = LinkedHashSet<String>()
        if (info.url.isNotBlank()) out += info.url
        out += info.extraUrls
        val t = info.tag.ifBlank { "v${info.latest}" }
        val variants = listOf(t, if (t.startsWith("v") || t.startsWith("V")) t.substring(1) else "v$t")
        variants.distinct().forEach { v ->
            out += "$DOWNLOAD_BASE/$v/GoMusic-$v.apk"
        }
        return out.filter {
            it.startsWith("http") && !it.contains("/releases/tag/") && !it.endsWith("/releases")
        }
    }

    /** 应用外更新用的地址：API 确认过的直链；没拿到就打开页面让人手动下 */
    fun downloadUrl(info: Info): String =
        if (info.url.isNotBlank()) PROXY + info.url else info.page

    /**
     * 下载 APK 到应用私有目录，返回本地文件。
     *
     * [onProgress] 回调 已下载字节 / 总字节。失败返回 null。
     * 先试加速地址，拿不到再回退原始直连。
     */
    fun downloadApk(
        info: Info,
        dir: java.io.File,
        onProgress: (Long, Long) -> Unit,
    ): java.io.File? {
        // 每个直链都试加速 + 原始两种，但候选里永远不会有 tag 页面
        val urls = apkUrls(info).flatMap { listOf(PROXY + it, it) }.distinct()
        if (urls.isEmpty()) return null
        if (!dir.exists()) dir.mkdirs()
        for (u in urls) {
            NmLog.note("开始下载 APK：$u")
            val out = java.io.File(dir, "GoMusic-${info.latest}.apk")
            if (out.exists()) out.delete()
            var ok = false
            try {
                val req = Request.Builder().url(u).build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        NmLog.note("APK 下载失败 HTTP ${r.code}：$u")
                        return@use
                    }
                    val ct = r.header("Content-Type").orEmpty()
                    if (ct.contains("text/html", true) || ct.contains("text/plain", true)) {
                        NmLog.note("APK 下载拿到网页 Content-Type=$ct，跳过：$u")
                        return@use
                    }
                    val body = r.body ?: return@use
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        out.outputStream().use { os ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                os.write(buf, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                        }
                    }
                }
                ok = looksLikeApk(out) &&
                    (info.size <= 0L || out.length() >= info.size / 2)
                if (!ok) NmLog.note("下载内容不是 APK（字节=${out.length()}），跳过：$u")
            } catch (e: Exception) {
                NmLog.note("APK 下载异常 ${e.message}：$u")
            }
            if (ok) {
                NmLog.note("APK 下载完成：${out.absolutePath}（${out.length()} 字节）")
                return out
            }
            out.delete()
        }
        return null
    }

    /** APK 就是 ZIP，必须以 PK 开头；HTML 页面 / 错误页一律挡掉 */
    private fun looksLikeApk(f: java.io.File): Boolean {
        if (!f.exists() || f.length() < 1024 * 1024) return false
        return runCatching {
            f.inputStream().use { i ->
                val h = ByteArray(4)
                if (i.read(h) != 4) false
                else h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() &&
                    ((h[2] == 3.toByte() && h[3] == 4.toByte()) ||
                        (h[2] == 5.toByte() && h[3] == 6.toByte()) ||
                        (h[2] == 7.toByte() && h[3] == 8.toByte()))
            }
        }.getOrDefault(false)
    }
}
