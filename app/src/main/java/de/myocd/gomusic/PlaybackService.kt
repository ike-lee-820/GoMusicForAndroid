package de.myocd.gomusic

import android.net.Uri
import androidx.annotation.OptIn
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
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val resolver = ResolvingDataSource.Resolver { spec ->
            if (spec.uri.scheme == "gomusic") {
                val ssid = spec.uri.lastPathSegment ?: throw IOException("bad uri")
                // 已下载过的歌曲直接放本地文件，离线也能播
                val local = LocalLib.audioUri(applicationContext, ssid)
                if (local != null) {
                    spec.withUri(local)
                } else {
                    val url = try {
                        Api.audioUrl(ssid, Prefs(applicationContext).quality)
                    } catch (e: Exception) {
                        throw IOException(e)
                    }
                    spec.withUri(Uri.parse(url))
                }
            } else spec
        }
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        // 用 DefaultDataSource 包一层，才能播放本地 content:// / file:// 的音频
        val dsFactory = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http), resolver)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dsFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        session = MediaSession.Builder(this, player).setCallback(Cb()).build()
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
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
