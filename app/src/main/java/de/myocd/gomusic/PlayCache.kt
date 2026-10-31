package de.myocd.gomusic

import android.content.Context
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File

/**
 * 播放缓存：边播边缓存整首。
 *
 * 之前是「先把整首下载完，再交给播放器读文件」—— 播一首要先等它下完，
 * 大文件等得明显。现在改成 [SimpleCache] + CacheDataSource：播放器一开始读，
 * 数据同时写进缓存，播到哪儿就缓存到哪儿，顺序播完即整首落盘。
 * 听一半切歌只会缓存一半，但切歌时会立刻清掉，不会留残缺文件占空间。
 *
 * 只保留一首：开始播新音源（切歌或换音质）时，立刻删掉上一个音源的缓存。
 */
object PlayCache {
    private const val DIR = "playcache"
    /** 上限：正常只占一首，留些余量应对切歌瞬间两首共存 */
    private const val MAX_BYTES = 400L * 1024 * 1024

    private var cache: SimpleCache? = null

    /** 当前正在用的音源地址 */
    @Volatile
    private var curUrl: String? = null

    @Synchronized
    fun get(ctx: Context): SimpleCache {
        cache?.let { return it }
        val app = ctx.applicationContext
        val dir = File(app.cacheDir, DIR).apply { mkdirs() }
        val c = SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(app),
        )
        cache = c
        return c
    }

    /**
     * 开始播某个音源：换地址（切歌 / 换音质）时立刻清掉上一个音源的缓存。
     * 同一个地址重复调用（重新缓冲）不会误删正在用的那份。
     */
    fun noteUrl(url: String) {
        if (url == curUrl) return
        val old = curUrl
        curUrl = url
        val c = cache ?: return
        if (old != null) {
            runCatching {
                c.removeResource(old)
                NmLog.note("已清除上一首播放缓存")
            }
        }
    }

    @Synchronized
    fun release() {
        runCatching { cache?.release() }
        cache = null
        curUrl = null
    }

    /** 清空播放缓存（设置里「清除缓存」用） */
    @Synchronized
    fun clear(ctx: Context) {
        val c = cache
        if (c != null) {
            runCatching {
                c.keys.toList().forEach { k -> c.removeResource(k) }
            }
        }
        runCatching { File(ctx.applicationContext.cacheDir, DIR).deleteRecursively() }
        curUrl = null
    }
}
