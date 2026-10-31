package de.myocd.gomusic

import android.content.Context
import okhttp3.Request
import java.io.File

/**
 * 播放整首缓存。
 *
 * 在线播放是边下边播，网络一抖就停下来转圈。这里改成先把整首下载到缓存目录，
 * 再交给播放器读本地文件 —— 开始播之后不再依赖网络，不会卡。
 *
 * 代价是点开一首新歌要先等它下完（期间播放器显示缓冲），
 * 换来的是播放过程绝对不中断。
 *
 * 只缓存一首：切到下一首时立刻删掉上一首的整首文件，
 * 不然每首歌留一个完整音频，听一会儿就是几百 MB。
 */
object PlayCache {
    private const val DIR = "playcache"

    /** 当前正在播（或正在准备）的缓存键 */
    @Volatile
    private var curKey: String? = null

    private val lock = Any()

    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.cacheDir, DIR).apply { mkdirs() }

    private fun extOf(url: String): String {
        val p = url.substringBefore('?')
        val e = p.substringAfterLast('.', "")
        return if (e.length in 2..4) e else "mp3"
    }

    private fun fileOf(ctx: Context, key: String, ext: String) = File(dir(ctx), "$key.$ext")

    /** 已缓存且完整的整首文件 */
    fun cached(ctx: Context, key: String): File? {
        val d = dir(ctx)
        return d.listFiles()?.firstOrNull {
            it.name.startsWith("$key.") && !it.name.endsWith(".part") && it.length() > 0
        }
    }

    /**
     * 切歌：立刻清掉上一首的整首缓存。
     * 同名（重播同一首）不删，否则会把正在用的文件删掉。
     */
    fun onSwitch(ctx: Context, key: String) {
        val old = curKey
        curKey = key
        if (old == null || old == key) return
        runCatching {
            dir(ctx).listFiles()?.forEach { f ->
                if (f.name.startsWith("$old.")) f.delete()
            }
        }
    }

    /**
     * 把整首下载到缓存目录，返回本地文件；失败返回 null（调用方回退在线直连）。
     *
     * 下载完会再确认一次「当前要播的还是不是这一首」：
     * 中途切歌的话，白下的文件直接删掉，不留垃圾。
     */
    fun ensure(ctx: Context, key: String, url: String, headers: Map<String, String>): File? {
        cached(ctx, key)?.let { return it }
        val ext = extOf(url)
        val dst = fileOf(ctx, key, ext)
        val tmp = File(dst.parentFile, dst.name + ".part")
        return synchronized(lock) {
            // 锁内再看一次：并发进来时可能已经被别人下好了
            cached(ctx, key)?.let { return@synchronized it }
            try {
                val b = Request.Builder().url(url)
                headers.forEach { (k, v) -> b.header(k, v) }
                tmp.delete()
                var ok = false
                Api.client.newCall(b.build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    val body = r.body ?: throw IllegalStateException("空响应")
                    tmp.outputStream().use { out ->
                        body.byteStream().use { inp -> inp.copyTo(out) }
                    }
                    ok = true
                }
                if (!ok || tmp.length() == 0L) throw IllegalStateException("下载内容为空")
                if (curKey != key) {
                    // 下载期间已经切歌了，这一份没人要
                    tmp.delete()
                    null
                } else {
                    dst.delete()
                    if (tmp.renameTo(dst)) dst else null
                }
            } catch (e: Exception) {
                NmLog.note("整首缓存失败：$key -> ${e.message}")
                runCatching { tmp.delete() }
                null
            }
        }
    }

    /** 清空播放缓存（设置里「清除缓存」用） */
    fun clear(ctx: Context) {
        curKey = null
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
    }
}
