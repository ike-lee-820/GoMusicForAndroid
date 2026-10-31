package de.myocd.gomusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody
import java.net.URLEncoder
import java.util.UUID

/**
 * 歌词翻译。
 *
 * 主接口：POST translate.mapi.myocd.de5.net/translate
 *   body {"text": "...", "target_lang": "zh"}，返回里的 data 就是译文。
 * 这个接口没有频率限制，所以点一次就把整篇歌词并发发出去，
 * 而不是一句一句等。
 *
 * 备用：主接口失效时走思知 AI 对话接口，同样没有限制。
 * AI 输出比较自由，所以把「要什么样的译文」写进提示语里，
 * 拿到结果后再剥掉它偶尔加上的引号和前缀。
 */
object Translate {

    private const val MAIN_URL = "https://translate.mapi.myocd.de5.net/translate"
    private const val AI_URL = "https://api.sizhi.com/chat"
    private const val AI_APPID = "b179db75c4804ae5954d2e7ad794e5bd"
    private const val AI_PROMPT =
        "输出中文翻译，要顺口且诗意且精简的歌词式翻译，原句没有逗号尽量不使用逗号分句，禁止给出其他内容纯翻译："

    /**
     * 每批并发多少个。
     *
     * 一次性把几十个请求全撒出去容易把接口拖慢、也更容易超时，
     * 分批仍是「一次翻译完整篇」，只是每批内部并发、批与批串行。
     */
    private const val CONCURRENCY = 8

    private val json = Api.json

    /**
     * 整篇翻译：并发请求，返回与输入等长的列表，翻不出来的那一句是空串。
     * 调用方按行号对齐，空串即「这句没翻译」。
     */
    suspend fun all(texts: List<String>): List<String> = withContext(Dispatchers.IO) {
        val out = arrayOfNulls<String>(texts.size)
        for (chunk in texts.mapIndexed { i, t -> i to t }.chunked(CONCURRENCY)) {
            val done = chunk.map { (i, t) -> async { i to (one(t) ?: "") } }.awaitAll()
            done.forEach { (i, v) -> out[i] = v }
        }
        out.map { it ?: "" }
    }

    /** 单句：主接口拿不到就用 AI 兜底 */
    fun one(text: String): String? {
        val t = text.trim()
        if (t.isBlank()) return null
        main(t)?.let { return clean(it) }
        return ai(t)?.let { clean(it) }
    }

    // ---------------- 主接口 ----------------

    private fun main(text: String): String? {
        // 用序列化构造 body：手动拼字符串遇到引号 / 换行会生成非法 JSON
        val body = json.encodeToString(
            buildJsonObject {
                put("text", text)
                put("target_lang", "zh")
            },
        )
        val req = Request.Builder()
            .url(MAIN_URL)
            .post(RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), body))
            .header("Connection", "close")
            .build()
        return runCatching {
            Api.client.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful || s.isBlank()) return@runCatching null
                val o = parseObj(s) ?: return@runCatching null
                // code 200 才算成功；接口偶发返回非 200，这时要交给备用
                if (intOf(o, "code") != 200) null else strOf(o, "data")
            }
        }.getOrNull()
    }

    // ---------------- 备用：AI 对话 ----------------

    private fun ai(text: String): String? {
        val spoken = URLEncoder.encode(AI_PROMPT + text, "UTF-8")
        val url = "$AI_URL?appid=$AI_APPID&userid=${UUID.randomUUID()}&spoken=$spoken"
        val req = Request.Builder().url(url).header("Connection", "close").build()
        return runCatching {
            Api.client.newCall(req).execute().use { r ->
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
        runCatching { json.parseToJsonElement(s) as? JsonObject }.getOrNull()

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

    /**
     * 清掉 AI 偶尔加的包装：多余引号、「翻译：」这类前缀。
     * 主接口本来就是干净的，这一步对它无害。
     */
    private fun clean(s: String): String {
        var t = s.trim().trim('"', '\'', '“', '”', '「', '」').trim()
        listOf("翻译：", "翻译:", "译文：", "译文:").forEach { p ->
            if (t.startsWith(p)) t = t.removePrefix(p).trim()
        }
        return t
    }
}
