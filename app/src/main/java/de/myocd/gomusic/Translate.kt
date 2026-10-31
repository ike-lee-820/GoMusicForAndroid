package de.myocd.gomusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 歌词翻译：整篇一次提交，主用 OpenRouter（NVIDIA Nemotron 3 Ultra），失败退回思知 AI。
 *
 * 之前是「主翻译接口逐句并发，AI 只做兜底」。主接口返回的是机器译文、
 * 目标语言偶尔还会弄错（翻出来是英文），而 AI 的译文更贴合歌词语境，
 * 所以改成只用 AI。
 *
 * 全文一次提交（而不是一句一个请求）：给 AI 完整上下文，人称、语气、
 * 专有名词在全篇里才翻得一致，逐句翻会出现同一句前后不一致。
 * 代价是一次要等它把整篇生成完，所以超时给得比较长。
 *
 * 对齐方式：原文按「行号. 内容」一起提交，要求 AI 按同样的行号返回；
 * 解析时先按行号严格匹配，对不上再退化为顺序对齐，
 * 两种都对不上就整篇放弃 —— 错位比没有译文更糟。
 */
object Translate {

    // ---- 主用：OpenRouter（NVIDIA Nemotron 3 Ultra，免费） ----
    private const val OR_URL = "https://openrouter.ai/api/v1/chat/completions"
    /**
     * 密钥不写进源码：GitHub 的 secret scanning 会拦下带密钥的提交。
     * 改由构建注入（gradle.properties 的 OR_KEY 或环境变量 OPENROUTER_API_KEY），
     * 没配就退回思知。
     */
    private val OR_KEY get() = BuildConfig.OR_KEY
    private const val OR_MODEL = "nvidia/nemotron-3-ultra-550b-a55b:free"

    // ---- 备用：思知 AI 对话 ----
    private const val AI_URL = "https://api.sizhi.com/chat"
    private const val AI_APPID = "b179db75c4804ae5954d2e7ad794e5bd"

    /**
     * 提示语：必须点明「简体中文」（否则它常用英文回答），
     * 并强调按行号一一对应、只输出译文。
     */
    private const val AI_PROMPT =
        "把下面这段歌词逐行翻译成简体中文。" +
            "要求：顺口、诗意、精简的歌词式翻译；原句没有逗号就尽量不使用逗号分句；" +
            "严格按行号一一对应，第 N 行原文只输出第 N 行的译文，行数必须与原文相同；" +
            "只输出译文本身，禁止任何解释、标题、序号以外的内容；禁止用英文回答。\n" +
            "歌词：\n"

    /** 整篇生成比较慢，单独放宽读超时（共享连接池与线程池） */
    private val http: OkHttpClient by lazy {
        Api.client.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 整篇翻译：一次请求翻完，返回与输入等长的列表，翻不出来的那一句是空串。
     * 调用方按行号对齐，空串即「这句没翻译」。
     * 空行直接跳过，不占用行号。
     */
    suspend fun all(texts: List<String>): List<String> = withContext(Dispatchers.IO) {
        // 只提交非空行：空行让 AI 去「翻译」只会得到莫名其妙的回复
        val idx = texts.mapIndexedNotNull { i, t -> if (t.trim().isBlank()) null else i }
        if (idx.isEmpty()) return@withContext texts.map { "" }
        val numbered = idx.joinToString("\n") { i -> "${i + 1}. ${texts[i].trim()}" }
        val raw = ask(AI_PROMPT + numbered)
            ?: return@withContext texts.map { "" }
        val map = parseNumbered(raw, idx.map { it + 1 })
        texts.mapIndexed { i, t ->
            if (t.trim().isBlank()) return@mapIndexed ""
            val v = map[i + 1] ?: return@mapIndexed ""
            val c = clean(v)
            // 译文必须真的含中文：AI 偶尔还是会用英文回答，那种结果不要
            if (hasChinese(c)) c else ""
        }
    }

    /** 译文里有没有汉字：接口返回英文说明目标语言没生效，不能用 */
    private fun hasChinese(s: String): Boolean = s.any { it in '\u4e00'..'\u9fff' }

    /**
     * 把 AI 的回答按行号拆回每行。
     *
     * AI 可能不守规矩（漏编号、串行、夹带解释），所以先按
     * 「行号 + 分隔符」严格匹配；匹配不齐时，若剥掉编号后剩下的行数
     * 刚好等于原文行数，就按顺序对齐；都对不上就整篇放弃。
     */
    private fun parseNumbered(raw: String, want: List<Int>): Map<Int, String> {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val head = Regex("^(\\d+)\\s*[.、)．:：]\\s*(.*)$")
        val byId = LinkedHashMap<Int, String>()
        for (l in lines) {
            val m = head.find(l) ?: continue
            val id = m.groupValues[1].toIntOrNull() ?: continue
            val body = m.groupValues[2].trim()
            if (body.isNotBlank()) byId[id] = body
        }
        if (want.all { byId.containsKey(it) }) return byId
        // 退化：顺序对齐
        val stripped = lines.map { head.replace(it, "").trim() }.filter { it.isNotBlank() }
        if (stripped.size == want.size) {
            return want.mapIndexed { k, id -> id to stripped[k] }.toMap()
        }
        return emptyMap()
    }

    // ---------------- 请求 ----------------

    /**
     * POST 优先：整篇歌词很长，拼进 URL 会超过服务端对 GET 长度的限制。
     * 少数部署只读 query，所以 POST 没拿到时再退回 GET。
     */
    /**
     * 主用 OpenRouter；它报错 / 拿不到就退回思知。
     * 两者返回的都是整篇译文文本，后面的解析逻辑共用。
     */
    private fun ask(spoken: String): String? {
        openRouter(spoken)?.let { return it }
        return sizhi(spoken)
    }

    /** 主用：OpenRouter，OpenAI 兼容的 chat/completions */
    private fun openRouter(spoken: String): String? {
        if (OR_KEY.isBlank()) return null
        // 未开启 reasoning，响应里就只有译文，不带思考过程
        val body: String = Api.json.encodeToString(
            OrReq(OR_MODEL, listOf(OrMsg("user", spoken))),
        )
        val req = Request.Builder()
            .url(OR_URL)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer $OR_KEY")
            .header("Connection", "close")
            .build()
        return runCatching {
            http.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful || s.isBlank()) return@runCatching null
                val o = parseObj(s) ?: return@runCatching null
                val ch = o["choices"] as? JsonArray ?: return@runCatching null
                val first = ch.firstOrNull() as? JsonObject ?: return@runCatching null
                strOf(first.obj("message"), "content")
            }
        }.getOrNull()
    }

    /** 请求体：用序列化构造，手动拼字符串遇到引号 / 换行会生成非法 JSON */
    @Serializable
    private class OrReq(val model: String, val messages: List<OrMsg>)

    @Serializable
    private class OrMsg(val role: String, val content: String)

    /** 备用：思知。POST 优先（整篇很长，GET 会被服务端截断），拿不到再退回 GET */
    private fun sizhi(spoken: String): String? {
        val uid = UUID.randomUUID().toString()
        val base = "$AI_URL?appid=$AI_APPID&userid=$uid"
        post(base, spoken)?.let { return it }
        return get("$base&spoken=${URLEncoder.encode(spoken, "UTF-8")}")
    }

    private fun post(url: String, spoken: String): String? {
        val body = FormBody.Builder().add("spoken", spoken).build()
        return call(Request.Builder().url(url).post(body).header("Connection", "close").build())
    }

    private fun get(url: String): String? {
        return call(Request.Builder().url(url).header("Connection", "close").build())
    }

    private fun call(req: Request): String? {
        return runCatching {
            http.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful || s.isBlank()) return@runCatching null
                val o = parseObj(s) ?: return@runCatching null
                // status 0 表示成功
                if (intOf(o, "status") != 0) null
                else strOf(o.obj("data")?.obj("info"), "text")
            }
        }.getOrNull()
    }

    // ---------------- 工具 ----------------

    private fun parseObj(s: String): JsonObject? =
        runCatching { Api.json.parseToJsonElement(s) as? JsonObject }.getOrNull()

    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

    private fun intOf(o: JsonObject?, k: String): Int? {
        val p = o?.get(k) as? JsonPrimitive ?: return null
        return p.content.trim().toIntOrNull()
    }

    private fun strOf(o: JsonObject?, k: String): String? {
        val v = o?.get(k) as? JsonPrimitive ?: return null
        if (!v.isString) return null
        return v.content.takeIf { it.isNotBlank() && it != "null" }
    }

    /** 清掉 AI 偶尔加的包装：多余引号、「翻译：」这类前缀 */
    private fun clean(s: String): String {
        var t = s.trim().trim('"', '\'', '“', '”', '「', '」').trim()
        listOf("翻译：", "翻译:", "译文：", "译文:").forEach { p ->
            if (t.startsWith(p)) t = t.removePrefix(p).trim()
        }
        return t
    }
}
