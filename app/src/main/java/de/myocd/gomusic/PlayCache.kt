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
    /** 上一首的音源地址，等真正切歌后再删 */
    @Volatile
    private var prevUrl: String? = null

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
     * 记录正在用的音源地址。
     *
     * 26.11.55：以前在这里立刻 removeResource(上一首)。但 ExoPlayer 会在
     * 当前首快播完时提前打开下一首的数据源，那时当前首还在读自己那份缓存；
     * 当场删掉就断流重新走网络，听感正是「切下一首要卡半天」。
     * 改成只记地址，等 [dropPrevious]（媒体项真正切换完成）再删。
     * 同一个地址重复调用（重新缓冲）不会误删正在用的那份。
     */
    fun noteUrl(url: String) {
        if (url == curUrl) return
        prevUrl = curUrl
        curUrl = url
    }

    /** 媒体项切换完成后清上一首缓存；必须在 onMediaItemTransition 里调用 */
    fun dropPrevious() {
        val old = prevUrl ?: return
        prevUrl = null
        val c = cache ?: return
        runCatching {
            c.removeResource(old)
            NmLog.note("已清除上一首播放缓存")
        }
    }

    /** 26.11.55：当前实际交给播放器的音源地址，投屏直接复用，不再重新解析 */
    fun current(): String? = curUrl

    @Synchronized
    fun release() {
        runCatching { cache?.release() }
        cache = null
        curUrl = null
        prevUrl = null
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
        prevUrl = null
    }
}
