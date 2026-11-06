package de.myocd.gomusic

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * 后台播放服务。
 * 队列条目使用占位地址 gomusic:///<ssid>，真正的播放地址在加载时
 * 通过 /api/song/:ssid 解析（按当前音质选择）。
 *
 * 后台播放策略见 [applyFocus]：可被打断 / 仅通话暂停 / 始终播放。
 */
@OptIn(UnstableApi::class)
/** 网易云 CDN 校验来源，缺 Referer 会返回 403 */
private val NM_HEADERS = mapOf(
    "Referer" to "https://music.163.com/",
    "User-Agent" to NM_UA,
)

private const val NM_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

class PlaybackService : MediaSessionService() {
    companion object {
        /** 设置里改了后台播放策略后，用它通知服务重新应用（服务读 Prefs） */
        const val ACTION_APPLY_FOCUS = "de.myocd.gomusic.APPLY_FOCUS"
    }

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null

    private var callListener: PhoneStateListener? = null
    private var pausedByCall = false

    // ---- 后台保活 ----
    private val handler = Handler(Looper.getMainLooper())
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null

    /** 播放状态变化时把队列 / 进度存成快照，服务被回收重建时靠它复原 */
    private val stateListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // CPU 唤醒锁由 ExoPlayer 的 WAKE_MODE_NETWORK 管，但 WiFi 射频
            // 在熄屏后还可能自己进省电态：边播边缓存会在缓冲中途断流。
            if (isPlaying) acquireWifiLock() else releaseWifiLock()
            savePlaybackState()
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) { savePlaybackState() }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) { savePlaybackState() }
        override fun onPlaybackStateChanged(playbackState: Int) { savePlaybackState() }
    }

    /** 定期存一次进度：切歌 / 暂停等事件之间被强杀时，位置不至于差太多 */
    private val stateSaver = object : Runnable {
        override fun run() {
            if ((player?.mediaItemCount ?: 0) > 0) savePlaybackState()
            handler.postDelayed(this, 15_000L)
        }
    }

    /** Doze 断网又恢复后，播放器可能卡在错误 / IDLE，主动重新准备一次 */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { retryIfStalled() }
        }
        override fun onLost(network: Network) {
            NmLog.note("网络断开，恢复后重试播放")
        }
    }

    override fun onCreate() {
        super.onCreate()
        NmLog.init(this)
        NmLog.installCrashHandler(this)
        val resolver = ResolvingDataSource.Resolver { spec ->
            if (spec.uri.scheme == "gomusic") {
                val ssid = spec.uri.lastPathSegment ?: throw IOException("bad uri")
                val p = Prefs(applicationContext)
                // 已下载过的歌曲直接放本地文件，离线也能播。
                // 同一首歌可能有多个音质的下载，按当前音质挑对应的那份
                // 音质由 MediaItem 的 uri 带过来（?q=&nl=）：换音质时 uri 变了，
                // 播放器才会真正重新解析音源；只靠 prefs 的话 replaceMediaItem
                // 拿到的是同一个 uri，ExoPlayer 认为没变化，音质切了却不换音源
                val q = spec.uri.getQueryParameter("q")?.takeIf { it.isNotBlank() } ?: p.quality
                val nl = spec.uri.getQueryParameter("nl")?.takeIf { it.isNotBlank() } ?: p.nmLevel
                val wantQuality = if (neteaseIdOf(ssid) != null && nl.isNotBlank()) nl else q
                val local = LocalLib.audioUri(applicationContext, ssid, wantQuality)
                if (local != null) {
                    spec.withUri(local)
                } else {
                    val nid = neteaseIdOf(ssid)
                    val url = try {
                        if (nid != null) {
                            // 未登录也要走网易云取地址：只是拿不到高音质档位，
                            // 但仍能播。之前要求 cookie 非空，未登录的网易云
                            // 歌曲会掉到本站接口去取，结果取不到直接播不了
                            Netease.cookie = p.nmCookie
                            Netease.audioUrl(nid, q, nl.takeIf { it.isNotBlank() })
                                ?: throw IOException("网易云没有可用音源")
                        } else {
                            Api.audioUrl(ssid, p.quality)
                        }
                    } catch (e: Exception) {
                        throw IOException(e)
                    }
                    // 网易云 CDN 给的是 http://，Android 9+ 默认禁止明文流量（会直接播不了）。
                    // 先尝试升成 https；万一 https 不通，清单里也已开启 usesCleartextTraffic 兜底。
                    val safe = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
                    val u = Uri.parse(safe)
                    // 网易云 CDN 校验 Referer / UA，缺了会返回 403
                    // —— 这就是「拿到正确 url 却播不了」的常见原因
                    val isNm = nid != null && p.nmCookie.isNotBlank()
                    NmLog.note("解析到播放地址：$url -> $safe（${if (isNm) "网易云" else "本站"}）")
                    // 交给 CacheDataSource：边播边缓存，播完即整首落盘。
                    // 切歌或换音质时 PlayCache.noteUrl 会清掉上一份
                    PlayCache.noteUrl(safe)
                    val base = spec.withUri(u)
                    if (isNm) base.withRequestHeaders(NM_HEADERS) else base
                }
            } else spec
        }
        // 默认带上浏览器 UA：部分 CDN 会拒绝 ExoPlayer 默认的 UA
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("User-Agent" to NM_UA))
            // 默认超时偏长，网络差时会干等很久才有反应
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
        // 用 DefaultDataSource 包一层，才能播放本地 content:// / file:// 的音频
        val upstream = DefaultDataSource.Factory(this, http)
        // 边播边缓存：播放器一边读，数据一边写进 SimpleCache，
        // 不用先把整首下完才开始播（那样每首都要干等）
        val cached = CacheDataSource.Factory()
            .setCache(PlayCache.get(this))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dsFactory = ResolvingDataSource.Factory(cached, resolver)

        // 加大缓冲：默认的缓冲量偏小，网络一抖就容易停下来转圈。
        // prioritizeTimeOverSizeThresholds 让它优先凑够时间长度而不是数据量，
        // 弱网下也能尽快开始播放并持续缓冲
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(30_000, false)
            .build()

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dsFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(buildAttrs(), true)
            .setHandleAudioBecomingNoisy(true)
            // 唤醒锁：熄屏几分钟后系统会让 CPU 休眠，正在播放的歌会停住、
            // 通知也跟着消失（服务被回收）。交给 ExoPlayer 在播放期间持有，
            // 暂停/播完自动释放，不会白耗电。
            // 用 WAKE_MODE_NETWORK：我们是边播边缓存，缓冲时要能继续联网。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = p

        // 点通知回应用（media3 默认只带 launcher intent，部分 ROM 会跳到空白页）
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        p.addListener(stateListener)
        session = MediaSession.Builder(this, p)
            .setCallback(Cb())
            .setSessionActivity(openApp)
            .build()
        applyFocus()
        // 服务若是被系统回收后重建的，新 player 队列是空的 —— 通知会直接消失。
        // 从快照恢复队列（保持暂停），让前台通知和播放栏都回来。
        restoreQueue(p)
        // 监听网络恢复 + 定期存进度
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            connectivityManager = cm
            cm?.registerDefaultNetworkCallback(networkCallback, handler)
        }
        handler.postDelayed(stateSaver, 15_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_APPLY_FOCUS) applyFocus()
        // 被系统回收后让系统重建服务：onCreate 会从队列快照复原，
        // 否则进程一没，前台通知也随之消失
        return START_STICKY
    }

    /** 划掉最近任务时，正在播放就别跟着停（国产 ROM 特别喜欢连服务一起收） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p != null && p.mediaItemCount > 0 && (p.isPlaying || p.playWhenReady)) {
            NmLog.note("任务卡片被划掉：保持前台播放服务")
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    // -------------------------------------------------------------------
    // 后台保活：WiFi 锁 / 网络恢复重连 / 队列快照
    // -------------------------------------------------------------------
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wm?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GoMusic:playback",
                )
            }
            wifiLock?.let { if (!it.isHeld) it.acquire() }
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
    }

    /** 网络回来后，播放器若停在错误 / IDLE，重新准备一次 */
    private fun retryIfStalled() {
        val p = player ?: return
        if (p.mediaItemCount == 0) return
        runCatching {
            when {
                p.playerError != null -> {
                    NmLog.note("网络恢复：播放器出错，重新准备")
                    p.prepare()
                }
                p.playbackState == Player.STATE_IDLE -> {
                    NmLog.note("网络恢复：重新准备播放器")
                    p.prepare()
                }
                // STATE_BUFFERING 时 ExoPlayer 自己会重试，不额外干预
                else -> Unit
            }
        }
    }

    /** 把当前队列与进度写成快照，服务重建时读回来 */
    private fun savePlaybackState() {
        val p = player ?: return
        runCatching {
            val arr = JsonArray((0 until p.mediaItemCount).map { i ->
                val m = p.getMediaItemAt(i)
                val meta = m.mediaMetadata
                JsonObject(
                    mapOf(
                        "s" to JsonPrimitive(m.mediaId),
                        "u" to JsonPrimitive(m.localConfiguration?.uri?.toString() ?: ""),
                        "t" to JsonPrimitive(meta.title?.toString() ?: ""),
                        "a" to JsonPrimitive(meta.artist?.toString() ?: ""),
                        "c" to JsonPrimitive(meta.artworkUri?.toString() ?: ""),
                    ),
                )
            })
            val pr = Prefs(applicationContext)
            pr.lastQueue = arr.toString()
            pr.lastQueueIndex = p.currentMediaItemIndex.coerceAtLeast(0)
            pr.lastQueuePos = p.currentPosition.coerceAtLeast(0L)
        }
    }

    /** 服务重建后复原队列：只放回去，不自动出声，用户点播放继续 */
    private fun restoreQueue(p: ExoPlayer) {
        val pr = Prefs(applicationContext)
        val raw = pr.lastQueue
        if (raw.isBlank()) return
        val arr = runCatching { Api.json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return
        val items = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["s"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val uri = (o["u"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: "gomusic:///$id"
            val title = (o["t"] as? JsonPrimitive)?.content.orEmpty()
            val artist = (o["a"] as? JsonPrimitive)?.content.orEmpty()
            val cover = (o["c"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            MediaItem.Builder()
                .setMediaId(id)
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setArtworkUri(cover?.let { runCatching { Uri.parse(it) }.getOrNull() })
                        .build(),
                )
                .build()
        }
        if (items.isEmpty()) return
        val idx = pr.lastQueueIndex.coerceIn(0, items.lastIndex)
        val pos = pr.lastQueuePos.coerceAtLeast(0L)
        p.setMediaItems(items, idx, pos)
        p.playWhenReady = false
        savePlaybackState()
        NmLog.note("服务重建：已恢复队列 ${items.size} 首，停在 ${pos}ms")
    }

    private fun buildAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /**
     * 应用后台播放策略：
     * - [FOCUS_NORMAL]：交给系统音频焦点，其他应用播放/通话都会暂停本应用
     * - [FOCUS_CALL_ONLY]：不让播放器管理焦点（其他应用播放不会打断），只在通话时暂停，挂断后继续
     * - [FOCUS_ALWAYS]：完全不处理焦点，强行播放
     */
    private fun applyFocus() {
        val p = player ?: return
        val mode = Prefs(this).playFocus
        p.setAudioAttributes(buildAttrs(), mode == FOCUS_NORMAL)
        if (mode == FOCUS_CALL_ONLY) startCallWatch() else stopCallWatch()
    }

    @Suppress("DEPRECATION")
    private fun startCallWatch() {
        if (callListener != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val tm = runCatching { getSystemService(TELEPHONY_SERVICE) as TelephonyManager }.getOrNull()
            ?: return
        val l = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                val p = player ?: return
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (pausedByCall) {
                            pausedByCall = false
                            p.play()
                        }
                    }
                    TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (p.playWhenReady && p.playbackState != androidx.media3.common.Player.STATE_IDLE) {
                            pausedByCall = true
                            p.pause()
                        }
                    }
                }
            }
        }
        runCatching { tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE) }
        callListener = l
    }

    @Suppress("DEPRECATION")
    private fun stopCallWatch() {
        val l = callListener ?: return
        callListener = null
        pausedByCall = false
        runCatching {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(l, PhoneStateListener.LISTEN_NONE)
        }
    }

    private class Cb : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val out = mediaItems.map {
                it.buildUpon()
                    .setUri(it.localConfiguration?.uri ?: Uri.parse("gomusic:///${it.mediaId}"))
                    .build()
            }.toMutableList()
            return Futures.immediateFuture(out)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        stopCallWatch()
        handler.removeCallbacks(stateSaver)
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        releaseWifiLock()
        player?.let {
            runCatching { savePlaybackState() }
            it.removeListener(stateListener)
        }
        // 释放 SimpleCache（边播边缓存用的）
        runCatching { PlayCache.release() }
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }
}
