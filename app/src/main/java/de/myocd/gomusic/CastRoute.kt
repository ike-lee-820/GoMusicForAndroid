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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统 MediaRouter 通道（备选）。
 *
 * 国产 ROM 普遍不注册 CATEGORY_REMOTE_PLAYBACK provider，[routes] 往往是空的。
 * 26.11.55 起主路径改走 [DlnaCast] 自己发现 + [LocalCastServer] 本机转发，
 * 这里保留给确实提供了系统投射能力的设备。
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
                i.setDataAndType(Uri.parse(url), "audio/*")
            }
            route.sendControlRequest(i, null)
            true
        }.getOrDefault(false)

    fun play(ctx: Context, route: MediaRouter.RouteInfo, url: String): Boolean = runCatching {
        route.select()
        send(ctx, route, MediaControlIntent.ACTION_PLAY, url)
    }.getOrDefault(false)

    /** 优先复用播放器正在用的那个地址：已解灰、带完整参数，比重新取更可靠 */
    suspend fun currentUrl(vm: MainViewModel): String? {
        PlayCache.current()?.takeIf { it.isNotBlank() }?.let { return it }
        val s = vm.currentSong ?: return null
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
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(emptyList<DlnaDevice>()) }
    var scanning by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var active by remember { mutableStateOf<DlnaDevice?>(null) }
    val sysRoutes = remember { CastRoute.routes(ctx) }

    fun scan() {
        scanning = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { DlnaCast.discover(ctx) }
            devices = found
            scanning = false
            hint = if (found.isEmpty()) {
                "没有自动发现设备。请确认手机与电视 / 音箱在同一 WiFi，点「重新搜索」，或手动填设备描述地址。"
            } else ""
        }
    }

    fun castTo(d: DlnaDevice) {
        val s = vm.currentSong
        if (s == null) { hint = "当前没有播放歌曲"; return }
        active = d
        scope.launch {
            hint = "正在准备音源…"
            val src = withContext(Dispatchers.IO) { CastRoute.currentUrl(vm) }
            if (src.isNullOrBlank()) {
                hint = "当前歌曲没有可用音源，无法投屏"
                return@launch
            }
            if (!src.startsWith("http", ignoreCase = true)) {
                hint = "本地文件暂不支持投屏，请播放在线歌曲后重试"
                return@launch
            }
            // 电视只从手机拉流：绕开在线直链的防盗链 / UA / 时效限制
            val base = withContext(Dispatchers.IO) {
                LocalCastServer.start(src, hdCoverUrl(s.coverUrl, 500))
            }
            if (base == null) {
                hint = "无法建立本机转发，请连接 WiFi 后重试"
                return@launch
            }
            val audio = "$base/audio"
            val cover = s.coverUrl?.let { "$base/cover" }
            val ok = withContext(Dispatchers.IO) {
                DlnaCast.play(d, audio, s.title, s.artist, cover)
            }
            hint = if (ok) "已投到 ${d.name}" else "${d.name} 没有响应，请确认设备支持 DLNA / AVTransport"
        }
    }

    LaunchedEffect(Unit) { scan() }

    GoSheet(onClose, "投屏") {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "选择播放设备", fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, color = go.on,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                hint.ifEmpty { "同一 WiFi 下的电视 / 音箱；音源由手机转发，不要求设备能访问外网" },
                fontSize = 13.sp, color = if (hint.isNotEmpty()) go.primary else go.muted,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (scanning) "正在搜索设备…" else "重新搜索",
                    fontSize = 14.sp, color = go.primary,
                    modifier = Modifier.clickable(enabled = !scanning) { scan() }.padding(vertical = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                active?.let { d ->
                    Text(
                        "停止投屏", fontSize = 14.sp, color = go.primary,
                        modifier = Modifier.clickable {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    DlnaCast.stop(d)
                                    LocalCastServer.stop()
                                }
                                active = null
                                hint = "已停止投屏"
                            }
                        }.padding(vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            devices.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().height(64.dp)
                        .clickable { castTo(d) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Cast, null, tint = go.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontSize = 16.sp, color = go.on, maxLines = 1)
                        Text("DLNA / UPnP", fontSize = 12.sp, color = go.muted)
                    }
                }
                Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
            }

            sysRoutes.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().height(64.dp)
                        .clickable {
                            if (vm.currentSong == null) { hint = "当前没有播放歌曲"; return@clickable }
                            scope.launch {
                                val src = withContext(Dispatchers.IO) { CastRoute.currentUrl(vm) }
                                if (src.isNullOrBlank()) {
                                    hint = "当前歌曲没有可用音源"
                                    return@launch
                                }
                                val base = withContext(Dispatchers.IO) {
                                    LocalCastServer.start(src, null)
                                }
                                val url = if (base != null) "$base/audio" else src
                                val ok = CastRoute.play(ctx, r, url)
                                hint = if (ok) "已投到 ${r.name}" else "${r.name} 没有响应"
                            }
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Cast, null, tint = go.muted, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 16.sp, color = go.on, maxLines = 1)
                        Text("系统投射", fontSize = 12.sp, color = go.muted)
                    }
                }
                Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
            }

            Spacer(Modifier.height(20.dp))
            Text("手动添加设备", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = go.on)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                label = { Text("设备描述地址", fontSize = 13.sp) },
                placeholder = { Text("http://192.168.1.10:8200/rootDesc.xml", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "添加并投屏", fontSize = 14.sp, color = go.primary,
                modifier = Modifier.clickable {
                    val loc = manual.trim()
                    if (loc.isBlank()) { hint = "请先填写设备描述地址"; return@clickable }
                    scope.launch {
                        val d = withContext(Dispatchers.IO) { DlnaCast.fromLocation(loc) }
                        if (d == null) {
                            hint = "地址无法解析，请确认是设备描述 XML 的 URL"
                        } else {
                            if (devices.none { it.location == d.location }) devices = devices + d
                            castTo(d)
                        }
                    }
                }.padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
