package de.myocd.gomusic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import java.util.concurrent.ConcurrentHashMap

/**
 * 独立预取调度域：和播放解析用的 Netease.urlClient / Api.client 物理隔离。
 *
 * 目标：提前把后面几首的音源地址、逐字歌词、歌曲详情取好，切过去直接命中缓存，
 * 但**绝不与当前播放解析抢线程**。做法：
 * - 自己的 OkHttp dispatcher（maxRequestsPerHost = 4），连接池与播放链路分开
 * - 自己的 SupervisorJob + IO 作用域，切歌时只取消旧预取，不影响播放
 * - 队列深度最多 2 首，每首 3 个并发任务，物理上有界（最多 6 个在途）
 * - 失败只记日志，不重试、不提示、不占用负缓存之外的任何资源
 *
 * 不采用 Android 真多进程：MediaSession / 播放器单例、SharedPreferences、
 * SimpleCache 文件锁都会跨进程分裂，收益远小于代价。
 */
object Prefetcher {
    private val client = Api.client.newBuilder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 12
                maxRequestsPerHost = 4
            }
        )
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val wordCache = ConcurrentHashMap<Long, Netease.LyricResult>()

    private const val MAX_AHEAD = 2

    /** 逐字歌词内存缓存：切歌回来不再请求；必须在 IO 线程调用 */
    fun warmLyric(id: Long): Netease.LyricResult =
        wordCache[id] ?: Netease.lyricWordWithFallback(id).also { wordCache[id] = it }

    /**
     * 预取后面最多 2 首。同一批 id 已经在跑就不重复发；
     * 不在新队列里的旧任务立即取消，避免快速切歌累积几十个请求。
     */
    fun prefetchAhead(ids: List<Long>, quality: String, level: String?) {
        val keep = ids.take(MAX_AHEAD)
        val keepSet = keep.toHashSet()
        jobs.keys.filter { it !in keepSet }.forEach { jobs.remove(it)?.cancel() }
        keep.forEach { nid ->
            if (jobs[nid]?.isActive == true) return@forEach
            jobs[nid] = scope.launch {
                listOf(
                    async { runCatching { Netease.prefetchAudioUrl(nid, quality, level) } },
                    async { runCatching { warmLyric(nid) } },
                    async { runCatching { Netease.detailOf(nid) } },
                ).awaitAll()
            }
        }
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    /** 诊断用：让日志能看到预取客户端确实独立于播放链路 */
    fun diagnostics(): String =
        "独立预取客户端 maxRequests=${client.dispatcher.maxRequests} " +
            "perHost=${client.dispatcher.maxRequestsPerHost} 缓存=${wordCache.size}"
}
