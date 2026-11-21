package de.myocd.gomusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 26.11.44：真投屏（2A）。
 *
 * 走系统 MediaRouter 的 RemotePlayback 协议：
 * - 用 [MediaControlIntent.CATEGORY_REMOTE_PLAYBACK] 发现支持远程播放的 route
 *   （Chromecast / DLNA / 厂商投射服务由系统 route provider 提供）
 * - 选中 route 后把当前歌曲的**真实音源 URL** 用 ACTION_PLAY 控制请求交给设备，
 *   由设备自己拉流播放，不是本地换个图标
 * - stop / pause / resume 同样走 sendControlRequest
 *
 * 国产 ROM 若没有注册任何 remote-playback route provider，设备列表会是空的；
 * 这是系统能力边界，代码路径本身是真实投流，不伪造设备。
 */
object CastRoute {
    fun selector(): MediaRouteSelector = MediaRouteSelector.Builder()
        .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
        .build()

    fun routes(ctx: Context): List<MediaRouter.RouteInfo> = runCatching {
        MediaRouter.getInstance(ctx).routes.filter {
            it.matchesSelector(selector()) && !it.isDefault && it.isEnabled
        }
    }.getOrDefault(emptyList())

    fun send(ctx: Context, route: MediaRouter.RouteInfo, action: String, url: String = ""): Boolean =
        runCatching {
            val i = Intent(action)
            if (action == MediaControlIntent.ACTION_PLAY && url.isNotBlank()) {
                // RemotePlayback 协议：内容 URI 走 data，MIME 走 type。
                // mediarouter 1.7 没有暴露 EXTRA_MEDIA_CONTENT_ID / EXTRA_MEDIA_MIME_TYPE，
                // 用 setDataAndType 等价表达，避免依赖不存在的常量。
                i.setDataAndType(Uri.parse(url), "audio/*")
            }
            route.sendControlRequest(i, null)
            true
        }.getOrDefault(false)

    fun play(ctx: Context, route: MediaRouter.RouteInfo, url: String): Boolean = runCatching {
        route.select()
        send(ctx, route, MediaControlIntent.ACTION_PLAY, url)
    }.getOrDefault(false)

    /** 投出去的是当前音质那一档的真实地址，走已有缓存，不重新解灰 */
    suspend fun currentUrl(vm: MainViewModel): String? {
        val s = vm.currentSong ?: return null
        if (LocalAudio.isDevice(s.ssid)) return null
        return withContext(Dispatchers.IO) {
            val nid = neteaseIdOf(s.ssid)
            val lvl = vm.prefs.nmLevel.takeIf { it.isNotBlank() }
            if (nid != null) {
                Netease.cookie = vm.prefs.nmCookie
                runCatching { Netease.audioUrl(nid, vm.quality, lvl) }.getOrNull()
            } else {
                runCatching { Api.audioUrl(s.ssid, vm.quality) }.getOrNull()
            }
        }
    }
}

@Composable
fun CastSheet(vm: MainViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val go = LocalGo.current
    var devices by remember { mutableStateOf(emptyList<MediaRouter.RouteInfo>()) }
    var hint by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { devices = CastRoute.routes(ctx) }
    GoSheet(onClose, "投屏") {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Text(
                "选择播放设备", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = go.on,
            )
            Spacer(Modifier.height(12.dp))
            if (devices.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "没有发现支持远程播放的设备\n请确认手机与电视 / 音箱在同一网络",
                        fontSize = 14.sp, color = go.muted,
                    )
                }
            } else {
                devices.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().height(64.dp)
                            .clickable {
                                if (vm.currentSong == null) { hint = "当前没有播放歌曲"; return@clickable }
                                vm.showCast = false
                                // URL 在 IO 线程取，取到后由设备自己拉流
                                GlobalScope.launch(Dispatchers.Main) {
                                    val url = CastRoute.currentUrl(vm)
                                    if (url.isNullOrBlank()) {
                                        vm.toast("本地音频或该音质不可用，无法投屏")
                                    } else {
                                        val ok = CastRoute.play(ctx, r, url)
                                        vm.toast(if (ok) "已投到 ${r.name}" else "设备没有响应播放请求")
                                    }
                                }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Cast, null, tint = go.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontSize = 16.sp, color = go.on)
                            Text(
                                if (r.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE) "远程播放" else "本地路由",
                                fontSize = 12.sp, color = go.muted,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
                }
            }
            if (hint.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(hint, fontSize = 13.sp, color = go.primary)
            }
        }
    }
}
