package de.myocd.gomusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException

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

    override fun onCreate() {
        super.onCreate()
        NmLog.init(this)
        val resolver = ResolvingDataSource.Resolver { spec ->
            if (spec.uri.scheme == "gomusic") {
                val ssid = spec.uri.lastPathSegment ?: throw IOException("bad uri")
                // 已下载过的歌曲直接放本地文件，离线也能播
                val local = LocalLib.audioUri(applicationContext, ssid)
                if (local != null) {
                    spec.withUri(local)
                } else {
                    val p = Prefs(applicationContext)
                    val nid = neteaseIdOf(ssid)
                    val url = try {
                        if (nid != null && p.nmCookie.isNotBlank()) {
                            Netease.cookie = p.nmCookie
                            Netease.audioUrl(nid, p.quality)
                                ?: throw IOException("网易云没有可用音源")
                        } else {
                            Api.audioUrl(ssid, p.quality)
                        }
                    } catch (e: Exception) {
                        throw IOException(e)
                    }
                    val u = Uri.parse(url)
                    // 网易云 CDN 校验 Referer / UA，缺了会返回 403
                    // —— 这就是「拿到正确 url 却播不了」的常见原因
                    val isNm = nid != null && p.nmCookie.isNotBlank()
                    NmLog.note("解析到播放地址：$url（${if (isNm) "网易云" else "本站"}）")
                    val base = spec.withUri(u)
                    if (isNm) base.withRequestHeaders(NM_HEADERS) else base
                }
            } else spec
        }
        // 默认带上浏览器 UA：部分 CDN 会拒绝 ExoPlayer 默认的 UA
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("User-Agent" to NM_UA))
        // 用 DefaultDataSource 包一层，才能播放本地 content:// / file:// 的音频
        val dsFactory = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http), resolver)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dsFactory))
            .setAudioAttributes(buildAttrs(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p

        session = MediaSession.Builder(this, p).setCallback(Cb()).build()
        applyFocus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val r = super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_APPLY_FOCUS) applyFocus()
        return r
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
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }
}
