package de.myocd.gomusic

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface Screen {
    data class Album(val artist: String, val album: String) : Screen
    data class Artist(val name: String) : Screen
    data class PlaylistPage(val id: String) : Screen
    /** 网易云专辑 / 歌手里的歌曲列表 */
    data class NmSongs(val kind: String, val id: Long, val title: String, val cover: String?) : Screen
}

data class Group(
    val title: String,
    val subtitle: String,
    val cover: String?,
    val songs: List<Song>,
    val artist: String = "",
    val album: String = "",
    /** 曲库来源：[SRC_LOCAL] / [SRC_NETEASE] */
    val source: String = SRC_LOCAL,
    val nmId: Long = 0,
    /** 网易云专辑 / 歌手，用于取歌曲列表 */
    val nmKind: String = "",
)

data class PickRequest(val ssids: List<String>, val name: String, val cover: String?)
data class QueueEntry(val ssid: String, val title: String, val artist: String, val cover: String?)

val DefaultPrimary = Color(0xFFD0BCFF)

/** 下载进度：已下载字节 / 总字节(-1 未知) / 速度(B/s) */
data class DlStatus(
    val title: String,
    val index: Int,
    val total: Int,
    val done: Long,
    val size: Long,
    val bps: Long,
) {
    val percent: Int get() = if (size > 0) (done * 100 / size).toInt().coerceIn(0, 100) else -1
}

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)

    // ---------- 数据 ----------
    var songs by mutableStateOf<List<Song>>(emptyList()); private set
    var artistInfo by mutableStateOf<Map<String, ArtistInfo>>(emptyMap()); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var playlists by mutableStateOf(prefs.playlists); private set
    var counts by mutableStateOf(prefs.playCounts); private set

    /** 连不上服务器时为 true：只展示已下载内容 */
    var offline by mutableStateOf(false); private set
    /** 已下载索引 */
    var downloads by mutableStateOf(prefs.downloads); private set
    /** 正在下载的 ssid */
    var downloading by mutableStateOf<Set<String>>(emptySet()); private set
    /** 当前下载进度（含速度），界面上显示用 */
    var dlStatus by mutableStateOf<DlStatus?>(null); private set

    /** 已下载 ssid 集合：列表里做 O(1) 判断，避免滑动时逐首线性扫描 */
    val downloadedIds: Set<String> by derivedStateOf { downloads.mapTo(HashSet()) { it.ssid } }

    // ---------- 设置 ----------
    var quality by mutableStateOf(prefs.quality); private set
    var playMode by mutableIntStateOf(prefs.playMode); private set
    /** 后台播放被打断的策略：[FOCUS_NORMAL] / [FOCUS_CALL_ONLY] / [FOCUS_ALWAYS] */
    var playFocus by mutableIntStateOf(prefs.playFocus); private set
    /** 「仅通话暂停」需要电话权限，由 Activity 监听并发起授权 */
    var needPhonePerm by mutableStateOf(false); private set

    // ---------- 悬浮歌词 ----------
    var floatOn by mutableStateOf(prefs.floatOn); private set
    var floatColor by mutableStateOf(Color(prefs.floatColor)); private set
    var floatStroke by mutableFloatStateOf(prefs.floatStroke); private set
    var floatStrokeColor by mutableStateOf(Color(prefs.floatStrokeColor)); private set
    var floatSize by mutableFloatStateOf(prefs.floatSize); private set
    var floatFont by mutableIntStateOf(prefs.floatFont); private set
    /** 需要悬浮窗权限，由 Activity 跳转系统设置页 */
    var needOverlayPerm by mutableStateOf(false); private set

    fun consumeOverlayPerm(): Boolean {
        if (!needOverlayPerm) return false
        needOverlayPerm = false
        return true
    }

    /** 由界面调用：取出一次待处理的权限请求 */
    fun consumePhonePerm(): Boolean {
        if (!needPhonePerm) return false
        needPhonePerm = false
        return true
    }
    var speed by mutableFloatStateOf(1f); private set
    var fixedTheme by mutableStateOf(prefs.themeColor?.let { Color(it) }); private set
    var coverColor by mutableStateOf<Color?>(null); private set
    val primary: Color get() = fixedTheme ?: coverColor ?: DefaultPrimary

    // ---------- 导航与弹层 ----------
    var tab by mutableIntStateOf(0)
    var query by mutableStateOf("")
    val stack = mutableStateListOf<Screen>()
    var showPlayer by mutableStateOf(false)
    var showLyrics by mutableStateOf(false)
    var showQueue by mutableStateOf(false)
    var showTheme by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showDownloads by mutableStateOf(false)
    var pickReq by mutableStateOf<PickRequest?>(null)
    var toastMsg by mutableStateOf<String?>(null); private set

    // ---------- 网易云曲库 ----------
    /** 是否在设置里开启「接入网易云曲库」 */
    var nmOn by mutableStateOf(prefs.nmEnabled); private set
    /** 网易云账号昵称，空 = 未登录（未登录时不使用网易云搜索） */
    var nmUser by mutableStateOf(prefs.nmAccount.ifBlank { null }); private set
    /** 是否已登录并可用 */
    val nmReady: Boolean get() = nmOn && !nmUser.isNullOrBlank() && Netease.cookie.isNotBlank()
    var nmSearching by mutableStateOf(false); private set
    var nmSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var nmAlbums by mutableStateOf<List<Group>>(emptyList()); private set
    var nmArtists by mutableStateOf<List<Group>>(emptyList()); private set
    /** 网易云专辑 / 歌手点进去后的歌曲页 */
    var nmPage by mutableStateOf<Group?>(null); private set
    var nmPageSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var nmPageLoading by mutableStateOf(false); private set
    /** 网易云登录弹层 */
    var showNmLogin by mutableStateOf(false); private set
    /** 播放历史（最近在前） */
    var history by mutableStateOf(prefs.history); private set
    val historySongs: List<Song> by derivedStateOf {
        val m = songMap
        val nm = nmSongs.associateBy { it.ssid }
        val dl = downloads.associateBy { it.ssid }
        history.mapNotNull { id ->
            m[id] ?: nm[id] ?: dl[id]?.let {
                Song(it.ssid, it.title, it.artist, it.album, it.cover.ifBlank { null })
            }
        }
    }
    private var nmJob: Job? = null
    private var qrJob: Job? = null
    /** 二维码登录：二维码图片（data: URI）与提示文案 */
    var nmQr by mutableStateOf<String?>(null); private set
    var nmQrTip by mutableStateOf("正在获取二维码"); private set
    var nmBusy by mutableStateOf(false); private set

    fun startQrLogin() {
        qrJob?.cancel()
        nmQr = null
        nmQrTip = "正在获取二维码"
        qrJob = viewModelScope.launch(Dispatchers.IO) {
            val key = runCatching { Netease.qrKey() }.getOrNull()
            if (key.isNullOrBlank()) {
                withContext(Dispatchers.Main) { nmQrTip = "获取二维码失败，请检查网络" }
                return@launch
            }
            val img = runCatching { Netease.qrImage(key) }.getOrNull()
            withContext(Dispatchers.Main) {
                nmQr = img
                nmQrTip = if (img == null) "获取二维码失败" else "用网易云音乐 App 扫码"
            }
            if (img == null) return@launch
            while (true) {
                delay(1500)
                val st = runCatching { Netease.qrCheck(key) }.getOrNull() ?: continue
                when (st.code) {
                    803 -> {
                        withContext(Dispatchers.Main) {
                            nmLogin(st.cookie ?: "", null)
                            nmQr = null
                        }
                        return@launch
                    }
                    800 -> {
                        withContext(Dispatchers.Main) { nmQrTip = "二维码已过期，点此重新获取" }
                        return@launch
                    }
                    802 -> withContext(Dispatchers.Main) { nmQrTip = "已扫码，请在手机上确认" }
                    801 -> withContext(Dispatchers.Main) { nmQrTip = "用网易云音乐 App 扫码" }
                }
            }
        }
    }

    fun stopQrLogin() {
        qrJob?.cancel()
        nmQr = null
    }

    /** 发送短信验证码 */
    fun nmSendCode(phone: String) {
        if (phone.isBlank()) { toast("请填写手机号"); return }
        nmBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { Netease.sendCaptcha(phone) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                nmBusy = false
                toast(if (ok) "验证码已发送" else "发送失败，请稍后再试")
            }
        }
    }

    /** 验证码登录；填了昵称则走注册 */
    fun nmLoginPhone(phone: String, code: String, password: String, nickname: String) {
        if (phone.isBlank() || code.isBlank()) { toast("请填写手机号与验证码"); return }
        nmBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { Netease.verifyCaptcha(phone, code) }.getOrDefault(false)
            if (!ok) {
                withContext(Dispatchers.Main) { nmBusy = false; toast("验证码校验失败") }
                return@launch
            }
            val cookie = if (nickname.isBlank()) {
                runCatching { Netease.loginByCaptcha(phone, code) }.getOrNull()
            } else {
                runCatching { Netease.register(phone, password, code, nickname) }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                nmBusy = false
                if (cookie.isNullOrBlank()) toast("登录失败，请检查验证码")
                else nmLogin(cookie, null)
            }
        }
    }

    // ---------- 播放状态 ----------
    var controller: MediaController? = null
        private set
    var curSsid by mutableStateOf<String?>(null); private set
    var curIndex by mutableIntStateOf(0); private set
    var isPlaying by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var queue by mutableStateOf<List<QueueEntry>>(emptyList()); private set
    var lyric by mutableStateOf<List<LrcLine>>(emptyList()); private set
    var qualities by mutableStateOf<List<String>>(emptyList()); private set

    /** 网易云歌曲不在 songs 里，出现过就记一份，播放页/歌词才能取到 */
    private val nmKnown = java.util.concurrent.ConcurrentHashMap<String, Song>()
    private fun keepNetease(l: List<Song>) {
        l.forEach { if (it.source == SRC_NETEASE) nmKnown[it.ssid] = it }
    }
    val currentSong: Song? by derivedStateOf { curSsid?.let { songMap[it] ?: nmKnown[it] } }
    val lyricIndex: Int by derivedStateOf {
        if (lyric.isEmpty() || lyric[0].timeMs < 0) -1
        else lyric.indexOfLast { it.timeMs <= positionMs }
    }

    private var pendingLink: Uri? = null
    private var afterConnect: (() -> Unit)? = null
    private var toastJob: Job? = null
    private var pendingFocus: Int? = null

    // ================= 加载 =================
    /** 把异常翻译成人能看懂的提示 */
    private fun netMsg(e: Throwable): String {
        val m = e.message ?: ""
        return when {
            m.contains("Software caused connection abort", true) ||
                m.contains("ECONNABORTED", true) ||
                m.contains("Connection reset", true) ||
                m.contains("Connection timed out", true) ||
                m.contains("timeout", true) -> "连接服务器超时（网络不稳定），已进入离线模式"
            m.contains("Unable to resolve host", true) || m.contains("UnknownHost", true) -> "无法解析服务器地址，已进入离线模式"
            m.contains("Failed to connect", true) || m.contains("ECONNREFUSED", true) -> "无法连接服务器，已进入离线模式"
            m.contains("SocketTimeout", true) || m.contains("timed out", true) -> "连接服务器超时，已进入离线模式"
            m.startsWith("HTTP ") -> "服务器返回 $m"
            else -> "加载失败：$m"
        }
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            var ok = false
            // 只尝试一次：失败立即进入离线模式，不重试、不退避等待
            try {
                songs = withContext(Dispatchers.IO) {
                    // 列表没给封面时，按文档用仓库里的「专辑名/cover.webp」兜底
                    Api.songs(quick = true).map {
                        if (it.coverUrl.isNullOrBlank()) it.copy(coverUrl = guessCoverUrl(it)) else it
                    }
                }
                ok = true
            } catch (e: Exception) {
                error = netMsg(e)
            }
            if (ok) {
                offline = false
                error = null
            } else {
                // 进入离线模式：用已下载的内容填充
                offline = true
                songs = LocalLib.songs(prefs.downloads)
            }
            loading = false
            if (!offline) {
                runCatching { artistInfo = withContext(Dispatchers.IO) { Api.artists() } }
            }
            pendingLink?.let { pendingLink = null; handleLink(it) }
        }
    }

    /** 设置里手动重连 */
    fun reconnect() = load()

    // ================= 悬浮歌词 =================
    /** 启动时按保存的设置恢复悬浮窗 */
    fun restoreFloat() {
        if (floatOn && canOverlay(app)) {
            FloatingLyric.show(app)
            pushFloatLyric()
        }
    }

    fun setFloatOn(on: Boolean) {
        if (on && !canOverlay(app)) {
            needOverlayPerm = true
            return
        }
        floatOn = on
        prefs.floatOn = on
        if (on) {
            FloatingLyric.show(app)
            pushFloatLyric()
        } else {
            FloatingLyric.hide()
        }
    }

    /** 从系统设置页返回后调用 */
    fun onOverlayResult(granted: Boolean) {
        if (!granted) {
            floatOn = false
            prefs.floatOn = false
            toast("没有悬浮窗权限，无法显示悬浮歌词")
            return
        }
        floatOn = true
        prefs.floatOn = true
        FloatingLyric.show(app)
        pushFloatLyric()
    }

    fun updateFloat(
        color: Color? = null,
        strokeColor: Color? = null,
        stroke: Float? = null,
        size: Float? = null,
        font: Int? = null,
    ) {
        color?.let { floatColor = it; prefs.floatColor = it.toArgb() }
        strokeColor?.let { floatStrokeColor = it; prefs.floatStrokeColor = it.toArgb() }
        stroke?.let { floatStroke = it; prefs.floatStroke = it }
        size?.let { floatSize = it; prefs.floatSize = it }
        font?.let { floatFont = it; prefs.floatFont = it }
        FloatingLyric.restyle(app)
    }

    /** 把当前歌词行推到悬浮窗 */
    fun pushFloatLyric() {
        if (!floatOn) return
        val i = lyricIndex
        val l = lyric
        val cur = l.getOrNull(i)?.text ?: ""
        val next = l.getOrNull(i + 1)?.text ?: ""
        FloatingLyric.update(cur, next)
    }

    // ================= 网易云曲库 =================
    init { Netease.cookie = prefs.nmCookie }

    fun setNmEnabled(on: Boolean) {
        nmOn = on
        prefs.nmEnabled = on
        Netease.cookie = prefs.nmCookie
        if (!on) {
            nmSongs = emptyList()
            nmAlbums = emptyList()
            nmArtists = emptyList()
        } else if (nmUser.isNullOrBlank()) {
            showNmLogin = true
        } else {
            refreshNmAccount()
        }
    }

    /** 打开/关闭登录弹层 */
    fun setNmLogin(v: Boolean) { showNmLogin = v }

    /** 登录成功：保存 cookie 与昵称 */
    fun nmLogin(cookie: String, name: String?) {
        prefs.nmCookie = cookie
        Netease.cookie = cookie
        showNmLogin = false
        viewModelScope.launch(Dispatchers.IO) {
            val n = name?.takeIf { it.isNotBlank() }
                ?: runCatching { Netease.accountName() }.getOrNull()
                ?: "网易云用户"
            prefs.nmAccount = n
            withContext(Dispatchers.Main) {
                nmUser = n
                toast("已登录网易云：$n")
            }
        }
    }

    fun nmLogout() {
        prefs.nmCookie = ""
        prefs.nmAccount = ""
        Netease.cookie = ""
        nmUser = null
        nmSongs = emptyList()
        nmAlbums = emptyList()
        nmArtists = emptyList()
        toast("已退出网易云")
    }

    /** 校验登录态，失效则清空 */
    fun refreshNmAccount() {
        if (!nmOn) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { Netease.loggedIn() }.getOrDefault(false)
            val name = if (ok) prefs.nmAccount.takeIf { it.isNotBlank() }
                ?: runCatching { Netease.accountName() }.getOrNull() else null
            withContext(Dispatchers.Main) {
                if (!ok || name.isNullOrBlank()) {
                    prefs.nmCookie = ""
                    prefs.nmAccount = ""
                    Netease.cookie = ""
                    nmUser = null
                } else {
                    prefs.nmAccount = name
                    nmUser = name
                }
            }
        }
    }

    /** 搜索网易云（防抖）。未开启或未登录时直接清空，不发请求 */
    fun searchNetease(q: String) {
        nmJob?.cancel()
        val kw = q.trim()
        if (!nmReady || kw.isEmpty()) {
            nmSearching = false
            nmSongs = emptyList()
            nmAlbums = emptyList()
            nmArtists = emptyList()
            return
        }
        nmJob = viewModelScope.launch {
            delay(400)
            nmSearching = true
            val s = withContext(Dispatchers.IO) { runCatching { Netease.searchSongs(kw) }.getOrDefault(emptyList()) }
            val a = withContext(Dispatchers.IO) { runCatching { Netease.searchAlbums(kw) }.getOrDefault(emptyList()) }
            val ar = withContext(Dispatchers.IO) { runCatching { Netease.searchArtists(kw) }.getOrDefault(emptyList()) }
            if (!nmReady) return@launch
            keepNetease(s)
            // 与本站共用播放次数进入默认排序；次数相同时保持网易云自己的相关度顺序
            val c = counts
            nmSongs = s.sortedByDescending { c[it.ssid] ?: 0 }
            nmAlbums = a
            nmArtists = ar
            nmSearching = false
        }
    }

    /**
     * 两套曲库不合并（不做去重/混排成一套）：
     * - 原站结果少于 8 条：先全部原站，再全部网易云
     * - 否则每 2 条原站后面插入 1 条网易云
     */
    fun <T> mix(local: List<T>, nm: List<T>): List<T> {
        if (nm.isEmpty()) return local
        if (local.isEmpty()) return nm
        if (local.size < 8) return local + nm
        val out = ArrayList<T>(local.size + nm.size)
        var i = 0
        var j = 0
        while (i < local.size || j < nm.size) {
            repeat(2) { if (i < local.size) out.add(local[i++]) }
            if (j < nm.size) out.add(nm[j++])
        }
        return out
    }

    /** 打开网易云专辑 / 歌手的歌曲列表 */
    fun openNmGroup(g: Group) {
        stack.add(Screen.NmSongs(g.nmKind, g.nmId, g.title, g.cover))
        nmPage = g
        nmPageSongs = emptyList()
        nmPageLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            val l = runCatching { Netease.songsOf(g.nmKind, g.nmId) }.getOrDefault(emptyList())
            keepNetease(l)
            withContext(Dispatchers.Main) {
                nmPageSongs = l
                nmPageLoading = false
            }
        }
    }

    /** 离开网易云歌曲页时清理状态（返回由 TopBar 统一出栈） */
    fun clearNmPage() {
        nmPage = null
        nmPageSongs = emptyList()
        nmPageLoading = false
    }

    fun pushHistory(ssid: String) {
        val l = (listOf(ssid) + history.filter { it != ssid }).take(40)
        history = l
        prefs.history = l
    }

    fun clearHistory() {
        history = emptyList()
        prefs.history = emptyList()
    }

    // ================= 列表派生 =================
    // 排行结果缓存：只在歌曲列表本身变化时重算，
    // 这样退出二级页回到歌曲页不会重新洗牌，顺序保持稳定
    private var rankedInput: List<Song>? = null
    private var rankedCache: List<Song> = emptyList()
    val rankedSongs: List<Song>
        get() {
            val s = songs
            if (rankedInput !== s) {
                rankedCache = rank(s)
                rankedInput = s
            }
            return rankedCache
        }

    fun rank(l: List<Song>): List<Song> {
        val c = counts
        return l.shuffled().sortedByDescending { c[it.ssid] ?: 0 } // 稳定排序：并列项保持随机顺序
    }

    // 分组结果同样缓存，搜索时只做过滤，不再重新分组
    private var albumsInput: List<Song>? = null
    private var albumsCache: List<Group> = emptyList()
    val albumGroupList: List<Group>
        get() {
            val s = songs
            if (albumsInput !== s) { albumsCache = albums(s); albumsInput = s }
            return albumsCache
        }

    private var artistsInput: List<Song>? = null
    private var artistsInfo: Map<String, ArtistInfo>? = null
    private var artistsCache: List<Group> = emptyList()
    val artistGroupList: List<Group>
        get() {
            val s = songs
            val ai = artistInfo
            if (artistsInput !== s || artistsInfo !== ai) {
                artistsCache = artistGroups(s)
                artistsInput = s
                artistsInfo = ai
            }
            return artistsCache
        }

    fun albums(l: List<Song>): List<Group> =
        l.groupBy { it.artist to it.album }.map { (k, v) ->
            Group(
                title = k.second.ifBlank { "未知专辑" },
                subtitle = "${k.first} · ${v.size} 首",
                cover = v.firstNotNullOfOrNull { it.coverUrl },
                songs = v, artist = k.first, album = k.second,
            )
        }

    fun artistGroups(l: List<Song>): List<Group> =
        l.groupBy { it.artist }.map { (a, v) ->
            Group(
                title = a.ifBlank { "未知歌手" },
                subtitle = "${v.map { it.album }.distinct().size} 张专辑 · ${v.size} 首",
                cover = artistInfo[a]?.image?.takeIf { it.isNotBlank() }
                    ?: v.firstNotNullOfOrNull { it.coverUrl },
                songs = v, artist = a,
            )
        }

    /** ssid -> Song 索引，随 songs 变化重建一次，避免每个列表项都重建整张表 */
    private val songMap: Map<String, Song> by derivedStateOf { songs.associateBy { it.ssid } }

    fun songsByIds(ids: List<String>): List<Song> {
        val m = songMap
        return ids.mapNotNull { m[it] }
    }

    // ================= 播放 =================
    private fun artworkUri(u: String?): Uri? {
        if (u.isNullOrBlank()) return null
        return when {
            u.startsWith("content://") || u.startsWith("file://") || u.startsWith("/") ->
                runCatching { Uri.parse(u) }.getOrNull()
            else -> Api.abs(u)?.takeIf { it.startsWith("http") }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        }
    }

    private fun toItem(s: Song): MediaItem = MediaItem.Builder()
        .setMediaId(s.ssid)
        .setUri("gomusic:///${s.ssid}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .setAlbumTitle(s.album)
                .setArtworkUri(artworkUri(s.coverUrl))
                .build()
        )
        .build()

    fun playSongs(list: List<Song>, index: Int) {
        if (list.isEmpty()) return
        keepNetease(list)
        val c = controller
        if (c == null) {
            afterConnect = { playSongs(list, index) }
            return
        }
        c.setMediaItems(list.map(::toItem), index.coerceIn(0, list.lastIndex), 0)
        c.prepare()
        c.play()
    }

    fun playIndex(i: Int) {
        controller?.let { it.seekTo(i, 0); it.play() }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNext() }

    fun prev() {
        val c = controller ?: return
        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPrevious()
    }

    fun seekTo(ms: Long) { controller?.seekTo(ms) }

    fun moveQueue(from: Int, to: Int) { controller?.moveMediaItem(from, to) }
    fun removeQueue(i: Int) { controller?.removeMediaItem(i) }

    fun cycleMode() {
        playMode = (playMode + 1) % 3
        prefs.playMode = playMode
        controller?.let(::applyMode)
        toast(listOf("列表循环", "随机播放", "单曲循环")[playMode])
    }

    private fun applyMode(c: MediaController) {
        when (playMode) {
            0 -> { c.repeatMode = Player.REPEAT_MODE_ALL; c.shuffleModeEnabled = false }
            1 -> { c.repeatMode = Player.REPEAT_MODE_ALL; c.shuffleModeEnabled = true }
            else -> { c.repeatMode = Player.REPEAT_MODE_ONE; c.shuffleModeEnabled = false }
        }
    }

    // ================= 后台播放 =================
    fun updateFocus(m: Int) {
        if (m == FOCUS_CALL_ONLY && ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingFocus = m
            needPhonePerm = true
            return
        }
        applyFocus(m)
    }

    private fun applyFocus(m: Int) {
        playFocus = m
        prefs.playFocus = m
        // 通知播放服务重新设置音频焦点策略（同一进程内 startService 即可）
        runCatching {
            app.startService(Intent(app, PlaybackService::class.java).setAction(PlaybackService.ACTION_APPLY_FOCUS))
        }
    }

    fun onPhonePermissionResult(granted: Boolean) {
        val m = pendingFocus
        pendingFocus = null
        if (m == null) return
        if (granted) {
            applyFocus(m)
        } else {
            // 没有电话权限就检测不到通话，按「始终播放」处理
            applyFocus(FOCUS_ALWAYS)
            toast("未授予电话权限，已设为始终播放")
        }
    }

    fun updateSpeed(v: Float) {
        speed = v
        controller?.setPlaybackSpeed(v)
    }

    fun updateQuality(k: String) {
        quality = k
        prefs.quality = k
        // 重新解析当前曲目地址并回到原位置
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        try {
            val i = c.currentMediaItemIndex
            val pos = c.currentPosition
            c.replaceMediaItem(i, c.getMediaItemAt(i))
            c.seekTo(i, pos)
        } catch (ignored: Exception) {
        }
    }

    // ================= 播放器连接 =================
    private fun attach(c: MediaController) {
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { this@MainViewModel.isPlaying = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { onItem(mediaItem, true) }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { refreshQueue() }
        })
        applyMode(c)
        c.setPlaybackSpeed(speed)
        isPlaying = c.isPlaying
        refreshQueue()
        onItem(c.currentMediaItem, false)
        afterConnect?.let { afterConnect = null; it() }
    }

    private fun refreshQueue() {
        val c = controller ?: return
        queue = (0 until c.mediaItemCount).map { i ->
            val m = c.getMediaItemAt(i)
            QueueEntry(
                m.mediaId,
                m.mediaMetadata.title?.toString() ?: "",
                m.mediaMetadata.artist?.toString() ?: "",
                m.mediaMetadata.artworkUri?.toString(),
            )
        }
        curIndex = c.currentMediaItemIndex
    }

    private fun onItem(item: MediaItem?, count: Boolean) {
        val id = item?.mediaId?.takeIf { it.isNotEmpty() }
        curSsid = id
        curIndex = controller?.currentMediaItemIndex ?: 0
        lyric = emptyList()
        qualities = emptyList()
        if (id == null) return
        if (count) {
            val m = counts.toMutableMap()
            m[id] = (m[id] ?: 0) + 1
            counts = m
            prefs.playCounts = m
            pushHistory(id) // 本站与网易云统一记录
        }
        updateCover((songMap[id] ?: nmKnown[id])?.coverUrl ?: item?.mediaMetadata?.artworkUri?.toString())
        // 网易云歌曲：歌词走网易云接口，优先级高于本站
        if (isNetease(id)) {
            if (count) pushHistory(id)
            val nid = neteaseIdOf(id)
            viewModelScope.launch {
                var t = ""
                if (nid != null) {
                    t = withContext(Dispatchers.IO) {
                        runCatching { Netease.lyric(nid) }.getOrDefault("")
                    }
                }
                if (t.isBlank()) t = withContext(Dispatchers.IO) { localLyrics(id) }
                if (curSsid == id) lyric = lyricLines(t)
            }
            return
        }
        // 离线模式：直接读本地歌词，绝不发网络请求
        // （否则会走 3 次重试 + 退避等待，歌词要十几秒才出来，还会拖住其他请求）
        if (offline) {
            viewModelScope.launch {
                val t = withContext(Dispatchers.IO) { localLyrics(id) }
                if (curSsid == id) lyric = lyricLines(t)
            }
            return
        }
        viewModelScope.launch {
            try {
                val d = withContext(Dispatchers.IO) { Api.detail(id) }
                if (curSsid != id) return@launch
                qualities = d.audio.keys.sortedBy { Api.rank(it) }
                var t = d.lyrics
                if (t.isBlank()) t = withContext(Dispatchers.IO) { runCatching { Api.lyrics(id) }.getOrDefault("") }
                if (t.isBlank()) t = withContext(Dispatchers.IO) { localLyrics(id) }
                if (curSsid == id) lyric = lyricLines(t)
            } catch (ignored: Exception) {
                val t = withContext(Dispatchers.IO) { localLyrics(id) }
                if (curSsid == id && t.isNotBlank()) lyric = lyricLines(t)
            }
        }
    }

    /** 取封面主色，并设定亮度下限，保证在深色底上可读 */
    private fun updateCover(url: String?) {
        val u = Api.abs(url)
        if (u == null || !u.startsWith("http")) { coverColor = null; return }
        val target = curSsid
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = ImageRequest.Builder(app).data(u).allowHardware(false).size(160).build()
                val res = app.imageLoader.execute(req)
                val bmp = (res.drawable as? BitmapDrawable)?.bitmap ?: return@launch
                val pal = Palette.from(bmp).generate()
                val rgb = (pal.vibrantSwatch ?: pal.dominantSwatch)?.rgb ?: return@launch
                val hsl = FloatArray(3)
                ColorUtils.colorToHSL(rgb, hsl)
                hsl[2] = hsl[2].coerceAtLeast(0.72f)
                hsl[1] = hsl[1].coerceAtLeast(0.30f)
                val c = Color(ColorUtils.HSLToColor(hsl))
                withContext(Dispatchers.Main) { if (curSsid == target) coverColor = c }
            } catch (ignored: Exception) {
            }
        }
    }

    // ================= 主题 =================
    fun setTheme(c: Color?) {
        fixedTheme = c
        prefs.themeColor = c?.toArgb()
    }

    // ================= 歌单 =================
    private fun savePl(l: List<Playlist>) { playlists = l; prefs.playlists = l }

    fun pick(ssids: List<String>, name: String, cover: String? = null) {
        if (ssids.isEmpty()) return
        pickReq = PickRequest(ssids.distinct(), name, cover)
    }

    fun createPlaylist(name: String, ssids: List<String> = emptyList(), cover: String? = null): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.trim(), ssids.distinct(), cover)
        savePl(playlists + p)
        return p
    }

    fun addToPlaylist(id: String, ssids: List<String>) {
        savePl(playlists.map { p ->
            if (p.id == id) p.copy(songs = (p.songs + ssids).distinct()) else p
        })
        toast("已加入歌单")
    }

    fun renamePlaylist(id: String, name: String) =
        savePl(playlists.map { if (it.id == id) it.copy(name = name.trim()) else it })

    fun deletePlaylist(id: String) = savePl(playlists.filter { it.id != id })

    fun movePl(id: String, from: Int, to: Int) {
        savePl(playlists.map { p ->
            if (p.id != id) p else {
                val l = p.songs.toMutableList()
                val x = l.removeAt(from)
                l.add(to, x)
                p.copy(songs = l)
            }
        })
    }

    fun removeFromPl(id: String, ssid: String) =
        savePl(playlists.map { p -> if (p.id == id) p.copy(songs = p.songs - ssid) else p })

    // ================= 下载 / 提示 / 链接 =================
    fun isDownloaded(ssid: String): Boolean = ssid in downloadedIds

    /** 下载歌曲本体 + 封面 + 歌词 + 歌曲信息，并写入本地记录（离线可用） */
    fun download(list: List<Song>) {
        if (list.isEmpty()) return
        if (offline) { toast("离线模式下无法下载，请先连接服务器"); return }
        // 已下载过、或正在下载的直接跳过，避免重复下载
        val todo = list.filter { it.ssid !in downloading && !isDownloaded(it.ssid) }
        if (todo.isEmpty()) {
            toast(
                when {
                    list.all { isDownloaded(it.ssid) } -> "已下载过，可在「设置 → 已下载」删除后重新下载"
                    else -> "已在下载队列中"
                }
            )
            return
        }
        downloading = downloading + todo.map { it.ssid }
        toast("开始下载 ${todo.size} 首")
        val noti = Noti(app)
        noti.ensureChannel()
        // 逐个顺序下载：速度才是当前这一首的真实速度，通知与进度条也才对得上
        viewModelScope.launch(Dispatchers.IO) {
            todo.forEachIndexed { i, s ->
                noti.progress(s.title, i + 1, todo.size, -1, "", "")
                withContext(Dispatchers.Main) {
                    dlStatus = DlStatus(s.title, i + 1, todo.size, 0L, -1L, 0L)
                }
                var lastBytes = 0L
                var lastTime = System.currentTimeMillis()
                val r = runCatching {
                    LocalLib.download(app, s, quality) { done, total ->
                        val now = System.currentTimeMillis()
                        val dt = now - lastTime
                        if (dt < 400 && done < total) return@download
                        val bps = if (dt > 0) (done - lastBytes) * 1000 / dt else 0
                        lastBytes = done
                        lastTime = now
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        viewModelScope.launch(Dispatchers.Main) {
                            dlStatus = DlStatus(s.title, i + 1, todo.size, done, total, bps)
                            noti.progress(
                                s.title, i + 1, todo.size, pct,
                                Noti.speed(bps),
                                if (total > 0) "${Noti.size(done)} / ${Noti.size(total)}" else Noti.size(done),
                            )
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    downloading = downloading - s.ssid
                    r.onSuccess { rec ->
                        val l = prefs.downloads.filter { it.ssid != rec.ssid } + rec
                        prefs.downloads = l
                        downloads = l
                        if (offline) songs = LocalLib.songs(l)
                        noti.done("${s.title} 下载完成")
                        toast("已下载：${s.title}")
                    }.onFailure {
                        noti.done("${s.title} 下载失败")
                        toast("下载失败：${s.title}")
                    }
                }
            }
            withContext(Dispatchers.Main) {
                dlStatus = null
                noti.cancel()
            }
        }
    }

    /** 重新下载：先删掉旧文件与记录，再下一次（文件丢失/损坏时用） */
    fun redownload(ssid: String) {
        val rec = prefs.downloads.firstOrNull { it.ssid == ssid } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            LocalLib.delete(app, rec)
            val l = prefs.downloads.filter { it.ssid != ssid }
            prefs.downloads = l
            withContext(Dispatchers.Main) {
                downloads = l
                download(listOf(Song(ssid, rec.title, rec.artist, rec.album, rec.cover.ifBlank { null })))
            }
        }
    }

    /** 删除单条下载（同时删除文件） */
    fun deleteDownload(ssid: String) {
        val rec = prefs.downloads.firstOrNull { it.ssid == ssid } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            LocalLib.delete(app, rec)
            val l = prefs.downloads.filter { it.ssid != ssid }
            prefs.downloads = l
            withContext(Dispatchers.Main) {
                downloads = l
                if (offline) songs = LocalLib.songs(l)
                toast("已删除下载")
            }
        }
    }

    fun clearDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.downloads.forEach { LocalLib.delete(app, it) }
            prefs.downloads = emptyList()
            withContext(Dispatchers.Main) {
                downloads = emptyList()
                if (offline) songs = emptyList()
                toast("已清空下载")
            }
        }
    }

    /** 本地歌词：在线取不到时用 */
    fun localLyrics(ssid: String): String {
        val rec = downloads.firstOrNull { it.ssid == ssid } ?: return ""
        return LocalLib.readText(app, rec.lyric)
    }

    fun toast(m: String) {
        toastMsg = m
        toastJob?.cancel()
        toastJob = viewModelScope.launch { delay(2500); toastMsg = null }
    }

    fun songLink(ssid: String) = "$BASE/?s=$ssid"
    fun albumLink(artist: String, album: String) =
        "$BASE/?p=alb&ar=${Uri.encode(artist)}&al=${Uri.encode(album)}"
    fun artistLink(artist: String) = "$BASE/?p=art&ar=${Uri.encode(artist)}"

    fun handleLink(u: Uri?) {
        if (u == null) return
        if (songs.isEmpty()) { pendingLink = u; return }
        u.getQueryParameter("s")?.let { ssid ->
            songs.firstOrNull { it.ssid == ssid }?.let { playSongs(listOf(it), 0) }
            return
        }
        when (u.getQueryParameter("p")) {
            "alb" -> {
                val ar = u.getQueryParameter("ar") ?: return
                val al = u.getQueryParameter("al") ?: return
                stack.add(Screen.Album(ar, al))
            }
            "art" -> stack.add(Screen.Artist(u.getQueryParameter("ar") ?: return))
        }
    }

    // 放在最后：初始化顺序依赖上面的属性
    init {
        load()
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        f.addListener({
            try {
                val c = f.get()
                controller = c
                attach(c)
            } catch (e: Exception) {
                toast("播放器连接失败")
            }
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch {
            while (true) {
                // 只有播放页/歌词页可见时才写进度状态：
                // 否则每 250ms 的 state 写入会让整个界面（含正在滑动的长列表）反复重组
                if (showPlayer || showLyrics) {
                    delay(250)
                    controller?.let { c ->
                        positionMs = c.currentPosition.coerceAtLeast(0)
                        durationMs = if (c.duration == C.TIME_UNSET) 0 else c.duration.coerceAtLeast(0)
                    }
                } else {
                    delay(1000)
                }
            }
        }
    }
}
