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
import kotlinx.coroutines.async
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
    data class NmSongs(
        val kind: String,
        val id: Long,
        val title: String,
        val cover: String?,
        /** 专辑所属歌手（专辑页要显示歌手名） */
        val artist: String = "",
    ) : Screen
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

/** 网易云搜索每次请求的条数（翻页步长） */
const val NM_PAGE_SIZE = 20

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)

    // ---------- 界面状态 ----------
    /** 当前标签：0 歌曲 1 专辑 2 歌手 3 歌单 */
    var tab by mutableIntStateOf(TAB_HOME); private set
    /** 二级页面栈 */
    val stack = mutableStateListOf<Screen>()
    var query by mutableStateOf("")
    var showPlayer by mutableStateOf(false)
    var showLyrics by mutableStateOf(false)
    var showQueue by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showDownloads by mutableStateOf(false)
    var pickReq by mutableStateOf<PickRequest?>(null)
    var toastMsg by mutableStateOf<String?>(null); private set
    /** 播放倍速 */
    var speed by mutableFloatStateOf(1f); private set

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
    /** 设置里指定的主题色，null = 跟随封面 */
    var fixedTheme by mutableStateOf(prefs.themeColor?.let { Color(it) }); private set
    /** 跟随封面取到的色 */
    var coverColor by mutableStateOf<Color?>(null); private set
    var quality by mutableStateOf(prefs.quality); private set
    var playMode by mutableIntStateOf(prefs.playMode); private set
    /** 后台播放被打断的策略：[FOCUS_NORMAL] / [FOCUS_CALL_ONLY] / [FOCUS_ALWAYS] */
    var playFocus by mutableIntStateOf(prefs.playFocus); private set
    /** 「仅通话暂停」需要电话权限，由 Activity 监听并发起授权 */
    var needPhonePerm by mutableStateOf(false); private set

    /** 主题色：设置里指定的优先，否则跟随封面取色 */
    val primary: Color get() = fixedTheme ?: coverColor ?: DefaultPrimary

    /** Activity 用它判断要不要发起电话权限请求，取一次就清掉 */
    fun consumePhonePerm(): Boolean {
        if (!needPhonePerm) return false
        needPhonePerm = false
        return true
    }

    /** 网易云登录弹层 */
    var showNmLogin by mutableStateOf(false); private set

    // ---------- 网易云曲库 ----------
    /** 是否在设置里开启「接入网易云曲库」 */
    var nmOn by mutableStateOf(prefs.nmEnabled); private set
    /** 网易云账号昵称，空 = 未登录（未登录时不使用网易云搜索） */
    var nmUser by mutableStateOf(prefs.nmAccount.ifBlank { null }); private set
    /**
     * 是否已登录并可用。
     *
     * 只要求「开关打开 + cookie 存在」，不强求昵称：
     * /user/account 经常取不到 profile，昵称拿不到就判定未登录的话，
     * 会出现「明明登录了却不搜索」的情况。
     */
    val nmReady: Boolean get() = nmOn && Netease.cookie.isNotBlank()
    var nmSearching by mutableStateOf(false); private set
    var nmLoadingMore by mutableStateOf(false); private set
    /** 歌曲是否还有下一页（滑到底继续加载） */
    var nmHasMore by mutableStateOf(false); private set
    /** 专辑是否还有下一页 */
    var nmAlbumsHasMore by mutableStateOf(false); private set
    /** 歌手是否还有下一页 */
    var nmArtistsHasMore by mutableStateOf(false); private set
    /** 搜索代号：切标签/改关键词会自增，旧请求的结果不再生效，避免串扰 */
    private var nmGen = 0
    /** 当前搜索关键词与翻页偏移（界面用它判断何时触发加载更多） */
    private var nmKeyword = ""
    private var nmOffset = 0
    private var nmAlbumOffset = 0
    private var nmArtistOffset = 0
    val nmKeywordForUi: String get() = nmKeyword
    val nmOffsetForUi: Int get() = nmOffset
    var nmSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var nmAlbums by mutableStateOf<List<Group>>(emptyList()); private set
    var nmArtists by mutableStateOf<List<Group>>(emptyList()); private set
    /** 网易云专辑 / 歌手点进去后的歌曲页 */
    var nmPage by mutableStateOf<Group?>(null); private set
    var nmPageSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var nmPageLoading by mutableStateOf(false); private set
    /** 歌手页的专辑列表（歌手页有「歌曲」「专辑」两栏） */
    var nmPageAlbums by mutableStateOf<List<Group>>(emptyList()); private set
    var nmPageAlbumsLoading by mutableStateOf(false); private set
    /** 歌手页当前分栏：0 歌曲 1 专辑 */
    var nmPageTab by mutableIntStateOf(0); private set
    /** 分页偏移与「还有下一页」 */
    private var nmPageSongOffset = 0
    private var nmPageAlbumOffset = 0
    var nmPageSongsHasMore by mutableStateOf(false); private set
    var nmPageAlbumsHasMore by mutableStateOf(false); private set
    var nmPageLoadingMore by mutableStateOf(false); private set
    /** 页头封面（歌手页用歌手自己的头像，不用专辑封面） */
    var nmPageCover by mutableStateOf<String?>(null); private set
    /** 详情页内容缓存：kind/id -> (歌曲, 专辑)，返回上层时直接复用，不再重复请求 */
    private val nmPageCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<Song>, List<Group>>>()
    /** 页头副标题：专辑显示歌手名，歌手显示「网易云歌手」 */
    var nmPageSubtitle by mutableStateOf(""); private set
    /** 播放历史（最近在前） */
    var history by mutableStateOf(prefs.history); private set
    /** 歌曲信息快照（含网易云），保证历史随时能显示歌名 */
    var snapshots: Map<String, Song> by mutableStateOf<Map<String, Song>>(prefs.songSnapshots); private set

    val historySongs: List<Song> by derivedStateOf {
        val m = songMap
        val nm = nmSongs.associateBy { it.ssid }
        // nmKnown 是累积的网易云歌曲：切界面后 nmSongs 会被清空，
        // 只查 nmSongs 会让历史里的网易云歌曲查不到 —— 之前就是这样丢的
        val known = nmKnown.toMap()
        val sn = snapshots
        val dl = downloads.associateBy { it.ssid }
        history.mapNotNull { id: String ->
            val s: Song? = m[id] ?: nm[id] ?: known[id] ?: sn[id] ?: dl[id]?.let {
                Song(it.ssid, it.title, it.artist, it.album, it.cover.ifBlank { null })
            }
            s
        }
    }

    /** 记录歌曲快照：播放 / 搜索命中时调用，让历史与界面随时能查到 */
    fun rememberSongs(list: List<Song>) {
        if (list.isEmpty()) return
        val cur = LinkedHashMap<String, Song>(snapshots)
        var changed = false
        for (s in list) {
            if (s.title.isBlank()) continue
            val old = cur[s.ssid]
            // 有更完整信息（有封面 / 有专辑）就覆盖
            if (old == null || (old.coverUrl.isNullOrBlank() && !s.coverUrl.isNullOrBlank())) {
                cur.remove(s.ssid) // 重新插到末尾，保持「最近」顺序
                cur[s.ssid] = s
                changed = true
            }
        }
        if (changed) {
            // 限制容量，避免 Prefs 无限增长
            while (cur.size > MAX_SNAPSHOTS) {
                val first = cur.keys.firstOrNull() ?: break
                cur.remove(first)
            }
            snapshots = cur
            prefs.songSnapshots = cur
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
                        // 授权成功：拿到凭证就立刻写入，不再依赖后续刷新
                        val ck = st.cookie
                        withContext(Dispatchers.Main) {
                            if (ck.isNullOrBlank()) {
                                nmQrTip = "授权完成但未拿到登录凭证，请重新扫码"
                                toast("登录未完成，请重新扫码")
                            } else {
                                nmQr = null
                                nmLogin(ck, null)
                            }
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
        val nm = l.filter { it.source == SRC_NETEASE }
        if (nm.isEmpty()) return
        nm.forEach { nmKnown[it.ssid] = it }
        // 同时落一份快照（持久化），这样切界面 / 重启后历史仍能查到
        rememberSongs(nm)
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
        if (cookie.isBlank()) {
            toast("登录失败：没有拿到登录凭证，请重新扫码")
            return
        }
        prefs.nmCookie = cookie
        Netease.cookie = cookie
        homeLoaded = false
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
        homeLoaded = false
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
                    // 登录态失效：保留 cookie，只标记为未登录，用户重新登录后沿用
                    prefs.nmAccount = ""
                    nmUser = null
                    if (!ok && prefs.nmCookie.isNotBlank()) {
                        toast("网易云登录已失效，请重新登录")
                    }
                } else {
                    prefs.nmAccount = name
                    nmUser = name
                }
            }
        }
    }

    /**
     * 切标签 / 进出二级页时调用：取消正在进行的网易云搜索。
     * 之前在搜索中途切界面会因为结果回来后列表重组而闪退。
     */
    fun cancelNeteaseSearch() {
        nmJob?.cancel()
        nmJob = null
        nmSearching = false
    }

    /** 切换底部标签 */
    fun chooseTab(t: Int) {
        // 不再在这里取消搜索：切标签后每个标签的 LaunchedEffect 会以 tab 为 key
        // 重新发起一次搜索，提前取消会导致新标签搜不到结果
        nmPage = null
        nmPageSongs = emptyList()
        tab = t
        if (t == TAB_HOME) loadHome()
    }

    /** 搜索网易云（防抖）。未开启或未登录时直接清空，不发请求 */
    fun searchNetease(q: String) {
        nmJob?.cancel()
        val kw = q.trim()
        NmLog.note("searchNetease 调用：kw=$kw nmReady=$nmReady")
        nmKeyword = kw
        nmOffset = 0
        nmAlbumOffset = 0
        nmArtistOffset = 0
        nmHasMore = false
        nmAlbumsHasMore = false
        nmArtistsHasMore = false
        if (!nmReady || kw.isEmpty()) {
            nmSearching = false
            nmLoadingMore = false
            nmSongs = emptyList()
            nmAlbums = emptyList()
            nmArtists = emptyList()
            return
        }
        val gen = ++nmGen
        // 立刻置为搜索中：让大加载动画马上出来，不用等防抖
        nmSearching = true
        nmJob = viewModelScope.launch {
            try {
                delay(400)
                // 只搜当前标签需要的那一类：三个类型各发一次请求（还要各自补详情）
                // 是搜索慢的主因，这里按 tab 只发一路。
                val kind = when (tab) {
                    TAB_ALBUMS -> 1
                    TAB_ARTISTS -> 2
                    else -> 0
                }
                NmLog.note("按标签搜索 kind=$kind kw=$kw")
                var s: List<Song> = emptyList()
                var a: List<Group> = emptyList()
                var ar: List<Group> = emptyList()
                when (kind) {
                    1 -> a = withContext(Dispatchers.IO) { Netease.searchAlbums(kw, NM_PAGE_SIZE, 0) }
                        .distinctBy { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }
                    2 -> ar = withContext(Dispatchers.IO) { Netease.searchArtists(kw, NM_PAGE_SIZE, 0) }
                        .distinctBy { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }
                    else -> s = withContext(Dispatchers.IO) { Netease.searchSongs(kw, NM_PAGE_SIZE, 0) }
                        .distinctBy { it.ssid }
                }
                // 分类型记日志：便于确认到底是哪一路没返回
                NmLog.note("分类型结果：歌曲=" + s.size + " 专辑=" + a.size + " 歌手=" + ar.size + "（kw=$kw）")
                // 关键词或代号变了说明已经切走，丢弃这批结果
                if (gen != nmGen || kw != nmKeyword || !nmReady) return@launch
                keepNetease(s)
                // 与本站共用播放次数进入默认排序；次数相同时保持网易云自己的相关度顺序
                val c = counts
                when (kind) {
                    1 -> {
                        nmAlbums = a
                        nmSongs = emptyList()
                        nmArtists = emptyList()
                    }
                    2 -> {
                        nmArtists = ar
                        nmSongs = emptyList()
                        nmAlbums = emptyList()
                    }
                    else -> {
                        nmSongs = s.sortedByDescending { c[it.ssid] ?: 0 }
                        nmAlbums = emptyList()
                        nmArtists = emptyList()
                    }
                }
                nmOffset = NM_PAGE_SIZE
                nmAlbumOffset = NM_PAGE_SIZE
                nmArtistOffset = NM_PAGE_SIZE
                when (kind) {
                    1 -> nmAlbumsHasMore = a.size >= NM_PAGE_SIZE
                    2 -> nmArtistsHasMore = ar.size >= NM_PAGE_SIZE
                    else -> nmHasMore = s.size >= NM_PAGE_SIZE
                }
                NmLog.note("搜索完成：歌曲 " + s.size + " / 专辑 " + a.size + " / 歌手 " + ar.size)
                if (s.isEmpty() && a.isEmpty() && ar.isEmpty()) {
                    NmLog.note("网易云三条搜索都为空，请检查登录态与关键词")
                }
            } catch (e: Throwable) {
                // 切界面导致的取消属于正常行为，不提示、不报错
                if (e is kotlinx.coroutines.CancellationException) throw e
                NmLog.err("searchNetease($kw)", e)
                if (gen == nmGen) {
                    nmSongs = emptyList()
                    nmAlbums = emptyList()
                    nmArtists = emptyList()
                    toast("网易云搜索失败：" + (e.message?.take(40) ?: ""))
                }
            } finally {
                // 只有当前这一代搜索才能收起加载态，避免旧任务把新搜索的加载态清掉
                if (gen == nmGen) nmSearching = false
            }
        }
    }

    /**
     * 列表滑到底时向网易云请求下一页。
     * [kind]：0 歌曲 1 专辑 2 歌手，各自维护偏移量。
     */
    fun loadMoreNetease(kind: Int = 0) {
        val kw = nmKeyword
        if (kw.isBlank() || !nmReady || nmLoadingMore) return
        val off = when (kind) {
            1 -> nmAlbumOffset
            2 -> nmArtistOffset
            else -> nmOffset
        }
        val hasMore = when (kind) {
            1 -> nmAlbumsHasMore
            2 -> nmArtistsHasMore
            else -> nmHasMore
        }
        if (!hasMore) return
        // 上一页还没回来就不再发，避免重复请求同一页
        if (nmLoadingMore) return
        nmLoadingMore = true
        val gen = nmGen
        NmLog.note("翻页开始 kind=$kind offset=$off kw=$kw")
        // 用独立协程：不占用搜索的 nmJob，避免翻页被新搜索取消
        viewModelScope.launch {
            try {
                when (kind) {
                    1 -> {
                        val add = withContext(Dispatchers.IO) { Netease.searchAlbums(kw, NM_PAGE_SIZE, off) }
                            .distinctBy { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }
                        if (gen != nmGen || kw != nmKeyword || !nmReady) return@launch
                        val seen = nmAlbums.map { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }.toHashSet()
                        val real = add.filter { (if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title) !in seen }
                        nmAlbums = nmAlbums + real
                        nmAlbumOffset = off + NM_PAGE_SIZE
                        nmAlbumsHasMore = real.isNotEmpty()
                        NmLog.note("专辑翻页完成 offset=$off 新增 " + real.size)
                    }
                    2 -> {
                        val add = withContext(Dispatchers.IO) { Netease.searchArtists(kw, NM_PAGE_SIZE, off) }
                            .distinctBy { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }
                        if (gen != nmGen || kw != nmKeyword || !nmReady) return@launch
                        val seen = nmArtists.map { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }.toHashSet()
                        val real = add.filter { (if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title) !in seen }
                        nmArtists = nmArtists + real
                        nmArtistOffset = off + NM_PAGE_SIZE
                        nmArtistsHasMore = real.isNotEmpty()
                        NmLog.note("歌手翻页完成 offset=$off 新增 " + real.size)
                    }
                    else -> {
                        val add = withContext(Dispatchers.IO) { Netease.searchSongs(kw, NM_PAGE_SIZE, off) }
                            .distinctBy { it.ssid }
                        if (gen != nmGen || kw != nmKeyword || !nmReady) return@launch
                        keepNetease(add)
                        val seen = nmSongs.map { it.ssid }.toHashSet()
                        val real = add.filter { it.ssid !in seen }
                        nmSongs = nmSongs + real
                        nmOffset = off + NM_PAGE_SIZE
                        nmHasMore = real.isNotEmpty()
                        NmLog.note("歌曲翻页完成 offset=$off 新增 " + real.size)
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NmLog.err("loadMoreNetease($kw,$off)", e)
                when (kind) {
                    1 -> nmAlbumsHasMore = false
                    2 -> nmArtistsHasMore = false
                    else -> nmHasMore = false
                }
            } finally {
                nmLoadingMore = false
            }
        }
    }

    /** 网易云歌曲：点封面进专辑页（走网易云，不查本地库） */
    fun openNmSongAlbum(s: Song) {
        if (s.source != SRC_NETEASE) {
            stack.add(Screen.Album(s.artist, s.album))
            return
        }
        if (s.nmAlbumId > 0) {
            openNmGroup(
                Group(s.album, s.artist, s.coverUrl, emptyList(),
                    artist = s.artist, album = s.album,
                    source = SRC_NETEASE, nmId = s.nmAlbumId, nmKind = "album")
            )
            return
        }
        // 详情没给专辑 id 时，用专辑名搜一次
        viewModelScope.launch {
            val g = withContext(Dispatchers.IO) {
                Netease.searchAlbums("$s.artist ${s.album}", 5).firstOrNull()
            }
            if (g != null) openNmGroup(g) else toast("没有找到这张专辑")
        }
    }

    /** 网易云歌曲：点歌手进歌手页（走网易云，不查本地库） */
    fun openNmSongArtist(s: Song) {
        if (s.source != SRC_NETEASE) {
            stack.add(Screen.Artist(s.artist))
            return
        }
        if (s.nmArtistId > 0) {
            // 不传 s.coverUrl：那是歌曲/专辑封面，用了会把歌手头像显示成专辑图。
            // 歌手真实头像进页面后用 /artist/detail 取。
            openNmGroup(
                Group(s.artist, "网易云歌手", null, emptyList(),
                    artist = s.artist, source = SRC_NETEASE,
                    nmId = s.nmArtistId, nmKind = "artist")
            )
            return
        }
        viewModelScope.launch {
            val g = withContext(Dispatchers.IO) { Netease.searchArtists(s.artist, 5).firstOrNull() }
            if (g != null) openNmGroup(g) else toast("没有找到这个歌手")
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
    fun openNmGroup(g: Group) = openNmGroup(g, push = true)

    /**
     * 确保当前显示的详情页数据与 [s] 对应。
     *
     * 之前详情页在离开时无条件 clearNmPage()，而从歌手页点进专辑时，
     * 旧页面的 dispose 会把新页面刚设好的状态清掉 —— 表现就是
     * 「请求成功了却不显示，要再进一次才有」。现在改成显示时按栈顶校正。
     */
    fun ensureNmPage(s: Screen.NmSongs) {
        val g = nmPage
        if (g != null && g.nmId == s.id && g.nmKind == s.kind) return
        NmLog.note("校正详情页：${s.kind}/${s.id}（原来是 ${g?.nmKind}/${g?.nmId}）")
        openNmGroup(
            Group(s.title, "", s.cover, emptyList(),
                artist = s.artist, source = SRC_NETEASE, nmId = s.id, nmKind = s.kind),
            push = false,
        )
    }

    /** [push] = false 时只更新内容，不往栈里加页面（返回上层时用） */
    private fun openNmGroup(g: Group, push: Boolean) {
        if (push) stack.add(Screen.NmSongs(g.nmKind, g.nmId, g.title, g.cover, g.artist))
        nmPage = g
        nmPageSongs = emptyList()
        nmPageAlbums = emptyList()
        nmPageLoading = true
        nmPageAlbumsLoading = g.nmKind == "artist"
        nmPageTab = 0
        nmPageSongOffset = 0
        nmPageAlbumOffset = 0
        nmPageSongsHasMore = false
        nmPageAlbumsHasMore = false
        // 歌手页不沿用歌曲封面：先留空，拿到歌手详情后填真实头像
        nmPageCover = if (g.nmKind == "artist") null else g.cover
        // 专辑页副标题显示歌手名；列表没带歌手时稍后用 /album 详情补
        nmPageSubtitle = if (g.nmKind == "album") g.artist else ""
        val kind = g.nmKind
        val id = g.nmId
        val ck = "$kind/$id"
        // 命中缓存：直接用，不再发请求（返回上层时尤其有用）
        val hit = nmPageCache[ck]
        if (hit != null) {
            nmPageSongs = hit.first
            nmPageAlbums = hit.second
            nmPageLoading = false
            nmPageAlbumsLoading = false
            nmPageSongOffset = if (kind == "artist") hit.first.size.coerceAtLeast(NM_PAGE_SIZE) else 0
            nmPageAlbumOffset = hit.second.size.coerceAtLeast(NM_PAGE_SIZE)
            nmPageSongsHasMore = kind == "artist" && hit.first.size >= NM_PAGE_SIZE
            nmPageAlbumsHasMore = hit.second.size >= NM_PAGE_SIZE
            return
        }
        viewModelScope.launch {
            val l = withContext(Dispatchers.IO) {
                runCatching { Netease.songsOf(kind, id, NM_PAGE_SIZE, 0) }.getOrDefault(emptyList())
            }
            keepNetease(l)
            nmPageSongs = l
            nmPageSongOffset = NM_PAGE_SIZE
            nmPageSongsHasMore = kind == "artist" && l.size >= NM_PAGE_SIZE
            nmPageLoading = false
            if (kind != "artist") {
                nmPageCache[ck] = l to emptyList()
                // 专辑页副标题显示歌手名；列表里没带歌手时，单独取一次详情
                if (nmPageSubtitle.isBlank()) {
                    launch {
                        val a = withContext(Dispatchers.IO) {
                            runCatching { Netease.albumArtist(id) }.getOrNull()
                        }
                        if (!a.isNullOrBlank()) nmPageSubtitle = a
                    }
                }
            }
            // 歌手：并行取头像与专辑列表
            if (kind == "artist") {
                launch {
                    val d = withContext(Dispatchers.IO) {
                        runCatching { Netease.artistDetail(id) }.getOrNull()
                    }
                    // 用歌手自己的头像，避免误用专辑封面
                    if (d?.cover != null) nmPageCover = d.cover
                }
                launch {
                    val al = withContext(Dispatchers.IO) {
                        runCatching { Netease.artistAlbums(id, NM_PAGE_SIZE, 0) }.getOrDefault(emptyList())
                    }
                    nmPageAlbums = al
                    nmPageAlbumOffset = NM_PAGE_SIZE
                    nmPageAlbumsHasMore = al.size >= NM_PAGE_SIZE
                    nmPageAlbumsLoading = false
                    nmPageCache[ck] = nmPageSongs to al
                }
            }
        }
    }

    /** 歌手 / 专辑页：切换分栏 */
    fun chooseNmPageTab(t: Int) { nmPageTab = t }

    /** 歌手页：歌曲或专辑滑到底时加载下一页 */
    fun loadMoreNmPage(kind: Int) {
        val g = nmPage ?: return
        if (nmPageLoadingMore) return
        val songMode = kind == 0
        if (songMode && (!nmPageSongsHasMore || g.nmKind != "artist")) return
        if (!songMode && !nmPageAlbumsHasMore) return
        val off = if (songMode) nmPageSongOffset else nmPageAlbumOffset
        val kindStr = g.nmKind
        val id = g.nmId
        nmPageLoadingMore = true
        NmLog.note("详情页翻页 kind=$kind offset=$off id=$id")
        viewModelScope.launch {
            try {
                if (songMode) {
                    val add = withContext(Dispatchers.IO) {
                        runCatching { Netease.songsOf(kindStr, id, NM_PAGE_SIZE, off) }.getOrDefault(emptyList())
                    }
                    keepNetease(add)
                    val seen = nmPageSongs.map { it.ssid }.toHashSet()
                    val real = add.filter { it.ssid !in seen }
                    nmPageSongs = nmPageSongs + real
                    nmPageSongOffset = off + NM_PAGE_SIZE
                    nmPageSongsHasMore = real.isNotEmpty()
                } else {
                    val add = withContext(Dispatchers.IO) {
                        runCatching { Netease.artistAlbums(id, NM_PAGE_SIZE, off) }.getOrDefault(emptyList())
                    }
                    val seen = nmPageAlbums.map { it.nmId }.toHashSet()
                    val real = add.filter { it.nmId !in seen }
                    nmPageAlbums = nmPageAlbums + real
                    nmPageAlbumOffset = off + NM_PAGE_SIZE
                    nmPageAlbumsHasMore = real.isNotEmpty()
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NmLog.err("loadMoreNmPage($kind,$off)", e)
                if (songMode) nmPageSongsHasMore = false else nmPageAlbumsHasMore = false
            } finally {
                nmPageLoadingMore = false
            }
        }
    }

    /** 离开网易云歌曲页时清理状态（返回由 TopBar 统一出栈） */
    fun clearNmPage() {
        nmPage = null
        nmPageSongs = emptyList()
        nmPageAlbums = emptyList()
        nmPageLoading = false
        nmPageAlbumsLoading = false
        nmPageLoadingMore = false
        nmPageSongsHasMore = false
        nmPageAlbumsHasMore = false
        nmPageTab = 0
        nmPageSongOffset = 0
        nmPageAlbumOffset = 0
        nmPageCover = null
        nmPageSubtitle = ""
    }

    fun pushHistory(ssid: String) {
        val l = (listOf(ssid) + history.filter { it != ssid }).take(MAX_HISTORY)
        history = l
        prefs.history = l
    }

    fun clearHistory() {
        history = emptyList()
        prefs.history = emptyList()
    }

    /** 单条删除历史 */
    fun removeHistory(ssid: String) {
        val l = history.filter { it != ssid }
        history = l
        prefs.history = l
    }

    /** 历史页开关 */
    var showHistory by mutableStateOf(false)

    // ================= 检查更新 ================
    /** 当前版本 */
    val appVersion: String get() = Updater.current()
    /** 检查中 */
    var updateChecking by mutableStateOf(false); private set
    /** 检查结果；null = 还没查或查询失败 */
    var updateInfo by mutableStateOf<Updater.Info?>(null); private set
    /** 只在启动时静默查一次，有更新才弹出 */
    private var autoChecked = false
    /** 是否显示全屏更新弹窗（启动检测到新版本时自动弹出） */
    var showUpdate by mutableStateOf(false)
    /** 应用内下载进度：已下载 / 总字节；null = 没在下载 */
    var updateProgress by mutableStateOf<Pair<Long, Long>?>(null); private set
    /** 下载好的 APK，等待安装 */
    var updateFile by mutableStateOf<java.io.File?>(null); private set
    /** 需要申请「安装未知应用」权限，由 Activity 处理 */
    var needInstallPerm by mutableStateOf(false); private set
    /** 启动检测弹过一次后，本次进程内不再自动弹 */
    private var autoShown = false

    /** 检查更新。[silent] = true 时查不到更新不提示 */
    fun checkUpdate(silent: Boolean = false) {
        if (updateChecking) return
        updateChecking = true
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { Updater.check() }.getOrNull() }
            updateInfo = info
            updateChecking = false
            when {
                info == null -> if (!silent) toast("检查更新失败，请检查网络")
                info.hasUpdate -> {
                    // 发现新版本：全屏弹窗让用户选更新方式
                    showUpdate = true
                }
                !silent -> toast("当前已是最新版本 ${appVersion}")
            }
        }
    }

    /** 启动时静默检查一次，有新版就弹全屏弹窗 */
    fun autoCheckUpdate() {
        if (autoChecked) return
        autoChecked = true
        checkUpdate(silent = true)
    }

    /** 启动检测完成后若发现有新版，自动弹一次（只弹一次） */
    fun maybeShowUpdateDialog() {
        if (autoShown) return
        val info = updateInfo ?: return
        if (!info.hasUpdate) return
        autoShown = true
        showUpdate = true
    }

    fun closeUpdate() {
        showUpdate = false
        updateProgress = null
    }

    /** 应用内更新：下载 APK 后走系统安装（需要安装权限） */
    fun updateInApp() {
        val info = updateInfo ?: return
        if (updateProgress != null) return
        val dir = java.io.File(app.getExternalFilesDir(null), "update")
        updateProgress = 0L to 0L
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                Updater.downloadApk(info, dir) { done, total ->
                    viewModelScope.launch { updateProgress = done to total }
                }
            }
            if (f == null) {
                updateProgress = null
                toast("下载失败，可改用应用外更新")
                return@launch
            }
            updateProgress = null
            updateFile = f
            // 没授予「安装未知应用」权限时，先让 Activity 去申请
            val canInstall = if (android.os.Build.VERSION.SDK_INT >= 26) {
                app.packageManager.canRequestPackageInstalls()
            } else true
            if (canInstall) installApk(f) else needInstallPerm = true
        }
    }

    /** 安装 APK（权限已授予） */
    fun installApk(f: java.io.File) {
        needInstallPerm = false
        val uri = androidx.core.content.FileProvider.getUriForFile(
            app, "${app.packageName}.fileprovider", f,
        )
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            app.startActivity(i)
        }.onFailure {
            toast("无法打开安装界面：${it.message}")
        }
    }

    /** 安装权限结果回来后，继续安装已下载的 APK */
    fun onInstallPermResult(granted: Boolean) {
        needInstallPerm = false
        val f = updateFile
        if (granted && f != null) installApk(f)
        else if (!granted) toast("需要允许安装未知应用才能更新")
    }

    /** 应用外更新：交给浏览器 / 系统下载器 */
    fun updateByBrowser(): String? {
        val info = updateInfo ?: return null
        return Updater.downloadUrl(info)
    }

    // ================= 首页 =================
    /** 每日推荐（需要登录） */
    var homeRecommend by mutableStateOf<List<Song>>(emptyList()); private set
    /** 新歌速递 */
    var homeNewSongs by mutableStateOf<List<Song>>(emptyList()); private set
    /** 热门歌手 */
    var homeTopArtists by mutableStateOf<List<Group>>(emptyList()); private set
    /** 最新专辑（新碟上架） */
    var homeNewAlbums by mutableStateOf<List<Group>>(emptyList()); private set
    /** 离线模式下首页要展示的已下载歌曲 */
    val homeDownloaded: List<Song> get() = LocalLib.songs(downloads)
    /** 热搜词 */
    var homeHotWords by mutableStateOf<List<String>>(emptyList()); private set
    var homeLoading by mutableStateOf(false); private set
    /** 新歌速递固定「全部」（type = 0），不提供地区切换 */
    val newSongType: Int get() = 0

    private var homeJob: Job? = null
    /** 首页是否已加载过：加载过就不再重复请求，切回首页直接用缓存 */
    private var homeLoaded = false

    /**
     * 加载首页数据：每日推荐 + 新歌速递 + 热搜，三路并行。
     *
     * 默认只在第一次进入首页时真正请求；[force] = true 用于手动刷新。
     */
    fun loadHome(force: Boolean = false) {
        // 换回首页直接用缓存，不再重复请求
        if (homeLoaded && !force) {
            NmLog.note("首页已加载过，使用缓存（force=$force）")
            return
        }
        homeJob?.cancel()
        if (!nmReady) {
            homeRecommend = emptyList()
            homeNewSongs = emptyList()
            homeHotWords = emptyList()
            homeTopArtists = emptyList()
            homeNewAlbums = emptyList()
            homeLoading = false
            return
        }
        homeLoading = true
        homeJob = viewModelScope.launch {
            try {
                val dR = async(Dispatchers.IO) { Netease.recommendSongs() }
                val dN = async(Dispatchers.IO) { Netease.topSongs(newSongType) }
                val dH = async(Dispatchers.IO) { Netease.hotSearch() }
                val dA = async(Dispatchers.IO) { Netease.topArtists(30, 0) }
                val dAl = async(Dispatchers.IO) { Netease.newestAlbums(30) }
                val r = dR.await()
                val n = dN.await()
                val h = dH.await()
                val artists = dA.await()
                val albums = dAl.await()
                keepNetease(r + n)
                homeRecommend = r
                homeNewSongs = n
                homeHotWords = h
                homeTopArtists = artists
                homeNewAlbums = albums
                homeLoaded = true
                NmLog.note(
                    "首页加载完成：推荐 ${r.size} / 新歌 ${n.size} / 热搜 ${h.size}" +
                        " / 热门歌手 ${artists.size} / 最新专辑 ${albums.size}"
                )
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NmLog.err("loadHome", e)
            } finally {
                homeLoading = false
            }
        }
    }

    /** 点热搜词：填入搜索框并回到歌曲页 */
    fun searchHotWord(w: String) {
        query = w
        chooseTab(TAB_SONGS)
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

    /**
     * ssid -> Song。
     *
     * 歌单里可能混着本站与网易云的歌，只查本地 songs 的话
     * 网易云歌曲会查不到 —— 表现就是「加了歌单却看不见」。
     */
    fun songsByIds(ids: List<String>): List<Song> {
        val m = songMap
        val nm = nmSongs.associateBy { it.ssid }
        val known = nmKnown.toMap()
        val sn = snapshots
        val dl = downloads.associateBy { it.ssid }
        return ids.mapNotNull { id ->
            m[id] ?: nm[id] ?: known[id] ?: sn[id] ?: dl[id]?.let {
                Song(it.ssid, it.title, it.artist, it.album, it.cover.ifBlank { null })
            }
        }
    }

    /** 把一批歌曲记进快照，保证加进歌单后随时能查到 */
    fun keepForPlaylist(l: List<Song>) {
        if (l.isEmpty()) return
        rememberSongs(l)
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

    /** 把一首歌追加到播放列表末尾（只加这一首，不动队列里已有的歌） */
    fun addToQueue(s: Song) {
        keepNetease(listOf(s))
        val c = controller
        if (c == null) {
            afterConnect = { addToQueue(s) }
            return
        }
        c.addMediaItem(toItem(s))
        toast("已加入播放列表：${s.title}")
    }

    /** 清空播放列表（保留当前正在播放的那一首） */
    fun clearQueue() {
        val c = controller ?: return
        val n = c.mediaItemCount
        if (n <= 0) return
        val cur = c.currentMediaItemIndex
        // 先移除当前项之后的所有项，再移除之前的，始终保留正在播放的这首
        for (i in n - 1 downTo cur + 1) c.removeMediaItem(i)
        for (i in 0 until cur) c.removeMediaItem(0)
        toast("已清空播放列表")
    }

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

    /** 当前是否为网易云音质档位名（quality 菜单可能是网易云的档位） */
    val nmQualityMode: Boolean get() = curSsid?.let { isNetease(it) } == true
    /** 网易云当前音质档位（空 = 跟随设置） */
    val nmLevel: String get() = prefs.nmLevel

    fun updateQuality(k: String) {
        // 网易云歌曲：档位存到 nmLevel，不动本站的 quality
        if (k in NM_LEVELS) {
            prefs.nmLevel = k
        } else {
            quality = k
            prefs.quality = k
        }
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
            rememberSongs(listOfNotNull(songMap[id] ?: nmKnown[id] ?: snapshots[id]))
        }
        updateCover((songMap[id] ?: nmKnown[id])?.coverUrl ?: item?.mediaMetadata?.artworkUri?.toString())
        // 网易云歌曲：歌词走网易云接口，优先级高于本站
        if (isNetease(id)) {
            if (count) pushHistory(id)
            // 网易云歌曲：音质档位与本站分开，菜单里列网易云自己的档位。
            // 离线时只列已经下载过的音质（没下载的播不了，不显示）
            qualities = if (offline) downloadedQualities(id).ifEmpty { NM_LEVELS } else NM_LEVELS
            val nid = neteaseIdOf(id)
            viewModelScope.launch {
                // 播放前用 /song/detail 补全封面 / 专辑 / 歌手
                // （搜索接口给的 al.picUrl 经常是空的）
                if (nid != null) {
                    val d = withContext(Dispatchers.IO) {
                        runCatching { Netease.detailOf(nid) }.getOrNull()
                    }
                    if (d != null && curSsid == id) {
                        nmKnown[id] = d
                        // 用详情里的完整信息刷新列表里的这一首
                        nmSongs = nmSongs.map { if (it.ssid == id) d else it }
                        updateCover(d.coverUrl)
                        // 记进快照（会持久化），保证历史里始终能查到这首
                        rememberSongs(listOf(d))
                    }
                }
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
            // 离线只能播已下载的音质，菜单里就不列没下载的
            qualities = downloadedQualities(id)
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
                // 离线只显示已下载的音质
                qualities = if (offline) {
                    downloadedQualities(id)
                } else {
                    d.audio.keys.sortedBy { Api.rank(it) }
                }
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
        val ids = ssids.distinct()
        // 把要加的歌（含网易云）记进快照，保证歌单里随时能查到
        keepForPlaylist(songsByIds(ids))
        pickReq = PickRequest(ids, name, cover)
    }

    fun createPlaylist(name: String, ssids: List<String> = emptyList(), cover: String? = null): Playlist {
        val ids = ssids.distinct()
        keepForPlaylist(songsByIds(ids))
        val p = Playlist(UUID.randomUUID().toString(), name.trim(), ids, cover)
        savePl(playlists + p)
        return p
    }

    /**
     * 网易云专辑 / 歌手页：整页歌曲新建一个歌单。
     * 名字用专辑 / 歌手名，封面用它自己的封面。
     */
    fun createFromNmPage(name: String, cover: String?) {
        val list = nmPageSongs
        if (list.isEmpty()) {
            toast("还没有歌曲")
            return
        }
        val p = createPlaylist(name, list.map { it.ssid }, cover)
        toast("已新建歌单「${p.name}」，共 ${p.songs.size} 首")
    }

    fun addToPlaylist(id: String, ssids: List<String>) {
        keepForPlaylist(songsByIds(ssids))
        savePl(playlists.map { p ->
            if (p.id == id) p.copy(songs = (p.songs + ssids).distinct()) else p
        })
        toast("已加入歌单")
    }

    /** 导出歌单：返回还原字符（可生成二维码 / 存文件 / 复制） */
    fun exportPlaylist(id: String): String? {
        val p = playlists.firstOrNull { it.id == id } ?: return null
        val songs = songsByIds(p.songs)
        if (songs.isEmpty()) {
            toast("歌单是空的，没有可导出的内容")
            return null
        }
        return PlaylistShare.encode(p, songs)
    }

    /**
     * 导入歌单：由还原字符还原成一个新歌单。
     * 已存在同名歌单时追加到后面，不覆盖原有歌单。
     */
    fun importPlaylist(text: String): Boolean {
        val snap = PlaylistShare.decode(text)
        if (snap == null) {
            toast("还原字符无法识别，请检查是否完整")
            return false
        }
        if (snap.songs.isEmpty()) {
            toast("这个歌单里没有歌曲")
            return false
        }
        // 记进快照，保证导入后歌单里能查到歌名信息
        rememberSongs(snap.songs.map {
            Song(it.ssid, it.title, it.artist, it.album, it.cover, source = it.source)
        })
        val ids = snap.songs.map { it.ssid }.distinct()
        // 与已有歌单重名时，给导入的这个改名，不动原有歌单
        val p = Playlist(UUID.randomUUID().toString(), uniqueName(snap.name.ifBlank { "导入的歌单" }), ids)
        savePl(playlists + p)
        toast("已导入：${p.name}（${ids.size} 首）")
        return true
    }

    /** 名字已被占用时追加 (2)(3)…，保证不覆盖已有歌单 */
    private fun uniqueName(base: String): String {
        val used = playlists.map { it.name }.toHashSet()
        if (base !in used) return base
        var i = 2
        while ("$base($i)" in used) i++
        return "$base($i)"
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

    /** 当前下载要用的音质（网易云歌曲用网易云档位） */
    private fun downloadQuality(s: Song): String =
        if (s.source == SRC_NETEASE) nmLevel.takeIf { it.isNotBlank() } ?: nmLevelOfQuality(quality)
        else quality

    /** 这首歌是否已经下载过 [q] 这个音质 */
    fun isDownloadedWithQuality(ssid: String, q: String): Boolean =
        downloads.any { it.ssid == ssid && (q.isBlank() || it.quality == q) }

    /** 这首歌已经下载了哪些音质 */
    fun downloadedQualities(ssid: String): List<String> =
        downloads.filter { it.ssid == ssid && it.audio.isNotBlank() }
            .map { it.quality }.filter { it.isNotBlank() }.distinct()

    /** 下载歌曲本体 + 封面 + 歌词 + 歌曲信息，并写入本地记录（离线可用） */
    fun download(list: List<Song>) {
        if (list.isEmpty()) return
        if (offline) { toast("离线模式下无法下载，请先连接服务器"); return }
        // 同一首歌可以下多个音质：按（歌曲 + 音质）判断是否已下载
        val todo = list.filter { s ->
            val q = downloadQuality(s)
            s.ssid !in downloading && !isDownloadedWithQuality(s.ssid, q)
        }
        if (todo.isEmpty()) {
            toast(
                when {
                    list.all { isDownloadedWithQuality(it.ssid, downloadQuality(it)) } ->
                        "这个音质已下载过，换一个音质可在播放页切换后再下载"
                    else -> "已在下载队列中"
                }
            )
            return
        }
        // 列表里可能混着本站 / 网易云，按音质是否一致分组下载
        val groups = todo.groupBy { downloadQuality(it) }
        downloading = downloading + todo.map { it.ssid }
        toast("开始下载 ${todo.size} 首（${qualityLabel(todo.first().let { downloadQuality(it) })}）")
        groups.forEach { (q, items) -> runDownload(items, q) }
    }

    /** 实际执行下载：逐个顺序下载，进度同时更新到通知与应用内进度条 */
    private fun runDownload(todo: List<Song>, quality: String) {
        val noti = Noti(app)
        noti.ensureChannel()
        // 开始下载时立刻给一次应用内提示，不用等通知栏
        toast(
            if (todo.size > 1) "开始下载 ${todo.size} 首（${qualityLabel(quality)}）"
            else "开始下载：${todo.firstOrNull()?.title ?: ""}（${qualityLabel(quality)}）",
        )
        // 逐个顺序下载：速度才是当前这一首的真实速度，通知与进度条也才对得上
        viewModelScope.launch(Dispatchers.IO) {
            var ok = 0
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
                        // 同歌不同音质各自保留一条记录
                        val l = prefs.downloads.filter {
                            !(it.ssid == rec.ssid && it.quality == rec.quality)
                        } + rec
                        prefs.downloads = l
                        downloads = l
                        if (offline) songs = LocalLib.songs(l)
                        ok++
                        noti.done("${s.title} 下载完成")
                        toast("已下载：${s.title}（${qualityLabel(rec.quality)}）")
                    }.onFailure {
                        noti.done("${s.title} 下载失败")
                        toast("下载失败：${s.title}")
                    }
                }
            }
            withContext(Dispatchers.Main) {
                dlStatus = null
                noti.cancel()
                // 批量下载结束时再给一次汇总提示，避免只看到单首的 toast
                if (todo.size > 1) toast("下载结束：成功 $ok 首，失败 ${todo.size - ok} 首")
            }
        }
    }

    /** 重新下载某一音质（文件丢失 / 损坏时用） */
    fun redownloadQuality(rec: DownloadRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            LocalLib.deleteRecord(app, rec)
            val l = prefs.downloads.filterNot { it.ssid == rec.ssid && it.quality == rec.quality }
            prefs.downloads = l
            withContext(Dispatchers.Main) {
                downloads = l
                val song = Song(rec.ssid, rec.title, rec.artist, rec.album, rec.cover.ifBlank { null })
                downloadWithQuality(listOf(song), rec.quality)
            }
        }
    }

    /** 删除某一个音质的下载（同一首歌的其他音质保留） */
    fun deleteDownloadQuality(rec: DownloadRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            LocalLib.deleteRecord(app, rec)
            val l = prefs.downloads.filterNot { it.ssid == rec.ssid && it.quality == rec.quality }
            prefs.downloads = l
            withContext(Dispatchers.Main) {
                downloads = l
                if (offline) songs = LocalLib.songs(l)
                toast("已删除：${rec.title}（${qualityLabel(rec.quality)}）")
            }
        }
    }

    /** 删除一首歌的全部音质 */
    fun deleteDownload(ssid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.downloads.filter { it.ssid == ssid }.forEach { LocalLib.deleteRecord(app, it) }
            val l = prefs.downloads.filterNot { it.ssid == ssid }
            prefs.downloads = l
            withContext(Dispatchers.Main) {
                downloads = l
                if (offline) songs = LocalLib.songs(l)
                toast("已删除下载")
            }
        }
    }

    /** 按指定音质下载（重新下载时用） */
    fun downloadWithQuality(list: List<Song>, q: String) {
        if (list.isEmpty()) return
        if (offline) { toast("离线模式下无法下载，请先连接服务器"); return }
        val todo = list.filter { it.ssid !in downloading && !isDownloadedWithQuality(it.ssid, q) }
        if (todo.isEmpty()) { toast("这个音质已下载过"); return }
        runDownload(todo, q)
    }

    fun clearDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.downloads.forEach { LocalLib.deleteRecord(app, it) }
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
