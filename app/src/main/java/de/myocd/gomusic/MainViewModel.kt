package de.myocd.gomusic

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.os.PowerManager
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private const val MODE_LIST_LOOP = 0
private const val MODE_SHUFFLE = 1
private const val MODE_REPEAT_ONE = 2

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
    /** MV 播放：整屏视频页 */
    data class Video(val title: String, val url: String) : Screen
    /** 26.11.88：应用内 WebView 页面（所有 HTTP(S) 链接统一走这里） */
    data class WebLink(val url: String) : Screen
    /** 专辑简介：大图 / 标题 / 歌手 / 简介 */
    data class AlbumIntro(
        val id: Long, val title: String, val cover: String?, val artist: String = "",
    ) : Screen
    /** 歌手简介：大图 / 名字 / 简介 / 详细信息 */
    data class ArtistIntro(
        val id: Long, val name: String, val cover: String?,
    ) : Screen
}

/** 简介页数据：ugc 简介仅登录可取，歌手详细信息不要求登录 */
data class IntroState(
    val kind: String = "",
    val id: Long = 0,
    val title: String = "",
    val cover: String? = null,
    val artist: String = "",
    val artistId: Long = 0,
    val desc: String = "",
    val descLoading: Boolean = false,
    /** 26.11.80：接口确实通了 / 请求失败，用于区分「暂无简介」与「获取失败可重试」 */
    val descOk: Boolean = false,
    val descFailed: Boolean = false,
    val brief: String = "",
    val sections: List<Pair<String, String>> = emptyList(),
    val topics: List<String> = emptyList(),
    val detailLoading: Boolean = false,
    val detailOk: Boolean = false,
)

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
    /** 26.11.70：收藏 / 订阅歌单与「我喜欢的音乐」，云端不可写 */
    val readOnly: Boolean = false,
    /** 云端内容指纹：updateTime + trackCount，用于跳过无变化的歌单 */
    val cloudStamp: String? = null,
    val trackCount: Int = 0,
    /** 26.11.71：specialType=5 的「我喜欢的音乐」，不与本地歌单并列，合并进「我的喜欢」 */
    val cloudLike: Boolean = false,
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

/** 26.11.43 起底栏三个；26.11.82 扩成五个，展示顺序见 HigUi.TAB3_ITEMS */
const val TAB3_MINE = 0
const val TAB3_HOME = 1
const val TAB3_SEARCH = 2
/** 26.11.82：独立播客页（搜索页不搜播客） */
const val TAB3_PODCAST = 3
/** 26.11.82：私信页面的正式名称「社交」 */
const val TAB3_SOCIAL = 4

/** 播放器内部三页：封面 / 歌词 / 队列 */
const val PLAYER_COVER = 0
const val PLAYER_LYRICS = 1
const val PLAYER_QUEUE = 2

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)

    // ---------- 界面状态 ----------
    /** 当前标签：0 歌曲 1 专辑 2 歌手 3 歌单（旧内容页仍保留，从搜索/我的进入） */
    var tab by mutableIntStateOf(TAB_HOME); private set
    /** 新底栏当前项 */
    var tab3 by mutableIntStateOf(TAB3_HOME); private set
    fun chooseTab3(t: Int) {
        tab3 = t
        stack.clear()
        if (t == TAB3_HOME) chooseTab(TAB_HOME)
        // 26.11.63：切回搜索 Tab 不再重发一次请求、也不重置已选分类与搜索记录。
        // 缓存命中或已有结果直接沿用，避免「每次切过去都卡一下」。
    }

    /** 播放器内部页；图 1 封面 / 图 2 歌词 / 图 4 队列 */
    var playerPage by mutableIntStateOf(PLAYER_COVER); private set
    fun gotoPlayerPage(p: Int) { playerPage = p }
    /** 从外部打开播放器一律先回图 1 封面页 */
    fun openPlayer() {
        playerPage = PLAYER_COVER
        lyricBarHidden = false
        showPlayer = true
    }
    /** 1A：歌词页下滑隐藏播放栏、上滑恢复 */
    var lyricBarHidden by mutableStateOf(false); private set
    fun hideLyricBar(v: Boolean) { lyricBarHidden = v }
    /** 搜索分类：0 综合 1 歌手 2 专辑 3 歌曲 */
    var searchFilter by mutableIntStateOf(0); private set
    fun chooseSearchFilter(i: Int) { searchFilter = i }
    /** 投屏设备选择 */
    var showCast by mutableStateOf(false)
    /** 播放器「更多」操作表（图 17 样式：信息头 + 图标行） */
    var showMoreSheet by mutableStateOf(false)
    /** 二级页面栈 */
    val stack = mutableStateListOf<Screen>()
    var query by mutableStateOf("")
    var showPlayer by mutableStateOf(false)
    var showLyrics by mutableStateOf(false)
    var showQueue by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showDownloads by mutableStateOf(false)
    var showEq by mutableStateOf(false)
    var eqConfig by mutableStateOf(runCatching { prefs.eqConfig }.getOrDefault(EQ_FLAT))
        private set

    // 3D 环绕：开关与强度独立保存，不写进 EqConfig，避免影响均衡器预设
    var showSurround by mutableStateOf(false)
    /** 录制歌曲视频（横竖屏共用全屏页） */
    var showRecorder by mutableStateOf(false)
    /** 录制列表：设置页进入，显示所有历史与进行中进度 */
    var showRecHistory by mutableStateOf(false)
    var surroundOn by mutableStateOf(prefs.surroundOn)
        private set
    var surroundStrength by mutableFloatStateOf(prefs.surroundStrength)
        private set

    fun setSurround(on: Boolean, strength: Float = surroundStrength) {
        surroundOn = on
        surroundStrength = strength.coerceIn(0f, 1f)
        prefs.surroundOn = on
        prefs.surroundStrength = surroundStrength
        SurroundBridge.apply(on, surroundStrength)
    }

    fun setEq(cfg: EqConfig) {
        eqConfig = cfg
        prefs.eqConfig = cfg
        // 服务与界面在同一进程，直接推给 PCM 链路，即刻生效
        EqBridge.apply(cfg)
    }

    /** A/B 试听：临时切到 B 侧 3 秒，再自动回到原曲线 */
    fun eqCompareB() {
        val saved = eqConfig
        val b = eqConfig.copy(
            presetId = eqConfig.compareId.ifBlank { "flat" },
            enabled = true,
        )
        setEq(b)
        viewModelScope.launch {
            delay(3_000)
            if (eqConfig == b) setEq(saved)
        }
    }
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
    /** 播放模式：0 列表循环 / 1 随机播放 / 2 单曲循环 */
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
    /**
     * 网易云功能是否可用：只看开关，不再要求已登录。
     *
     * 之前要求 cookie 非空才能搜网易云 —— 登录一失败，整个网易云曲库就用不了。
     * 实际上搜索、取歌、取地址未登录也能用（只是音质档位少一些），
     * 只有「同步我的歌单 / 我喜欢的音乐」这类个人数据才真的需要登录。
     */
    val nmReady: Boolean get() = nmOn
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
    fun rememberSongs(list: List<Song>, force: Boolean = false) {
        if (list.isEmpty()) return
        val cur = LinkedHashMap<String, Song>(snapshots)
        var changed = false
        for (s in list) {
            if (s.title.isBlank()) continue
            val old = cur[s.ssid]
            // 有更完整信息（有封面 / 有专辑）就覆盖。
            // force 是导入歌单专用：还原字符带来的才是这首歌该显示的信息，
            // 旧快照里同 ssid 的记录可能是别的专辑/歌手，不覆盖就会串歌。
            if (force || old == null || (old.coverUrl.isNullOrBlank() && !s.coverUrl.isNullOrBlank())) {
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
    /** 26.11.81：当前二维码的 key，手动「刷新状态」要拿它去查 /login/qr/check */
    private var qrKey: String? = null
    /** 轮询与手动刷新可能同时拿到 803，只让一个去落登录态 */
    @Volatile private var qrDone = false
    /** 二维码登录：二维码图片（data: URI）与提示文案 */
    var nmQr by mutableStateOf<String?>(null); private set
    var nmQrTip by mutableStateOf("正在获取二维码"); private set
    var nmBusy by mutableStateOf(false); private set
    /** 手动查询中 / 明确答复（不只是改一行状态提示） */
    var nmQrChecking by mutableStateOf(false); private set
    var nmQrReply by mutableStateOf(""); private set

    fun startQrLogin() {
        qrJob?.cancel()
        qrDone = false
        qrKey = null
        nmQrChecking = false
        nmQrReply = ""
        nmQr = null
        nmQrTip = "正在获取二维码"
        qrJob = viewModelScope.launch(Dispatchers.IO) {
            val key = runCatching { Netease.qrKey() }.getOrNull()
            if (key.isNullOrBlank()) {
                withContext(Dispatchers.Main) { nmQrTip = "获取二维码失败，请检查网络" }
                return@launch
            }
            qrKey = key
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
                        if (qrDone) return@launch
                        qrDone = true
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
        qrKey = null
        nmQrChecking = false
        // 26.11.81：保留 nmQrReply。登录成功会立刻关掉弹层，若在这里清掉，
        // 「登录已完成」那句答复就一闪而过；下次进登录页由 startQrLogin 清空。
    }

    /**
     * 26.11.81：二维码登录加「刷新状态」——手动查一次 /login/qr/check，
     * 并给出明确答复。轮询仍在后台跑，两者可能同时拿到 803，用 qrDone 去重。
     */
    fun refreshQrLogin() {
        val key = qrKey
        if (key.isNullOrBlank()) {
            val m = "二维码还没生成，正在重新获取"
            nmQrReply = m
            toast(m)
            startQrLogin()
            return
        }
        if (nmQrChecking) return
        nmQrChecking = true
        nmQrReply = "正在查询登录状态…"
        viewModelScope.launch(Dispatchers.IO) {
            val st = runCatching { Netease.qrCheck(key) }.getOrNull()
            withContext(Dispatchers.Main) {
                nmQrChecking = false
                if (st == null) {
                    val m = "查询失败，请检查网络后再点一次"
                    nmQrReply = m
                    toast(m)
                    return@withContext
                }
                val reply: String = when (st.code) {
                    803 -> {
                        if (qrDone) "登录已完成"
                        else {
                            qrDone = true
                            val ck = st.cookie
                            if (ck.isNullOrBlank()) {
                                nmQrTip = "授权完成但未拿到登录凭证，请重新扫码"
                                "扫码已授权，但没拿到登录凭证，请重新扫码"
                            } else {
                                nmQr = null
                                nmLogin(ck, null)
                                "登录已完成"
                            }
                        }
                    }
                    800 -> {
                        nmQr = null
                        nmQrTip = "二维码已过期，点此重新获取"
                        "二维码已过期，请重新获取二维码"
                    }
                    802 -> {
                        nmQrTip = "已扫码，请在手机上确认"
                        "已扫码，但还没在手机上确认登录"
                    }
                    801 -> {
                        nmQrTip = "用网易云音乐 App 扫码"
                        "还没有人扫码，请用网易云音乐 App 扫码"
                    }
                    else -> "状态 ${st.code}：${st.message ?: "登录尚未完成"}"
                }
                nmQrReply = reply
                toast(reply)
            }
        }
    }

    /**
     * 网易风控：需要用户在浏览器完成安全验证后才能继续登录。
     * 非空时登录弹层会显示提示卡片和「打开验证页」按钮。
     */
    var nmRiskTip by mutableStateOf(""); private set
    var nmRiskUrl by mutableStateOf<String?>(null); private set

    fun clearNmRisk() { nmRiskTip = ""; nmRiskUrl = null }

    /** 命中风控：明确提示 + 给出验证页入口，并保持登录弹层打开 */
    private fun showNmRisk(r: LoginResult) {
        nmRiskUrl = r.verifyUrl ?: r.redirectUrl
        nmRiskTip = (r.message?.takeIf { it.isNotBlank() })
            ?: "当前登录存在安全风险，请先完成验证后再登录"
        showNmLogin = true
        toast(nmRiskTip)
        NmLog.note("登录风控提示：${nmRiskTip}（验证页=${if (nmRiskUrl != null) "有" else "无"}）")
    }

    /** 26.11.88：安全验证页改在应用内 WebView 打开，带固定 UA 与账号 cookie */
    fun openNmVerify() {
        val u = nmRiskUrl ?: return
        openInternalLink(u)
    }

    /** 复制链接：方便在电脑浏览器里完成验证 */
    fun copyNmVerify() {
        val u = nmRiskUrl ?: return
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("网易云验证页", u))
        toast("验证链接已复制")
    }

    /** 发送短信验证码 */
    fun nmSendCode(phone: String) {
        if (phone.isBlank()) { toast("请填写手机号"); return }
        nmBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { Netease.sendCaptcha(phone) }
                .getOrElse { e -> LoginResult(message = e.message ?: "网络异常") }
            withContext(Dispatchers.Main) {
                nmBusy = false
                when {
                    r.sent -> { clearNmRisk(); toast("验证码已发送") }
                    r.blocked -> showNmRisk(r)
                    else -> toast("发送失败：${r.message ?: "请稍后再试"}")
                }
            }
        }
    }

    /**
     * 验证码登录；填了昵称则走注册。
     *
     * 不再先调 /captcha/verify：那一步会把验证码核销掉，
     * 导致紧接着的登录拿到一个已经失效的验证码。
     */
    fun nmLoginPhone(phone: String, code: String, password: String, nickname: String) {
        if (phone.isBlank() || code.isBlank()) { toast("请填写手机号与验证码"); return }
        if (nickname.isNotBlank() && password.isBlank()) { toast("注册请填写密码"); return }
        if (nmBusy) return
        nmBusy = true
        viewModelScope.launch {
            // 整体超时：Netease.get 内部会重试多次且间隔递增，
            // 服务端不响应时总耗时可能到十几秒，卡在那里界面像没反应。
            // 到点就中断并提示，绝不让按钮一直转圈
            val r = withTimeoutOrNull(20_000) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (nickname.isBlank()) Netease.loginByCaptcha(phone, code)
                        else Netease.register(phone, password, code, nickname)
                    }.getOrNull()
                }
            }
            nmBusy = false
            when {
                r == null -> toast("登录超时或网络异常，请稍后再试")
                r.ok -> { clearNmRisk(); nmLogin(r.cookie!!, null) }
                // 风控必须显式提示并可跳转验证页，不能只 toast 一句就算了
                r.blocked -> showNmRisk(r)
                else -> {
                    clearNmRisk()
                    val m = r.message?.takeIf { it.isNotBlank() }
                    toast(if (m != null) "登录失败：$m" else "登录失败（code ${r.code}）")
                    NmLog.note("验证码登录失败 code=${r.code} msg=${r.message}")
                }
            }
        }
    }

    // ---------- 播放状态 ----------
    var controller: MediaController? = null
        private set
    var curSsid by mutableStateOf<String?>(null); private set
    var curIndex by mutableIntStateOf(0); private set
    var isPlaying by mutableStateOf(false); private set
    /** 26.11.60：本次会话是否已经开始播放过。放 VM 里，收起播放器再打开也不会
     *  把「暂停缩小态」错认成「从未播放的原尺寸态」。 */
    var playerEverPlayed by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var queue by mutableStateOf<List<QueueEntry>>(emptyList()); private set
    var lyric by mutableStateOf<List<LrcLine>>(emptyList()); private set
    /** 歌词是否正在加载：UI 据此显示「正在加载」，而不是过早显示「暂无歌词」 */
    var lyricLoading by mutableStateOf(false); private set

    /**
     * 取歌词的超时（毫秒）。
     *
     * 之前没有超时：接口慢或重试退避时会一直卡在「正在加载歌词…」，
     * 明明这首歌没有歌词，界面却长时间不显示「暂无歌词」。
     * 超时就按「拿不到」处理，直接显示「暂无歌词」。
     */
    private val lyricTimeoutMs = 8_000L
    /** 当前歌词属于哪首歌：切音质会重新 replaceMediaItem，但歌词不用重取 */
    private var lyricSsid: String? = null
    /** 上一次歌词加载任务：切歌时取消，避免旧任务回来写脏数据 */
    private var lyricJob: Job? = null
    /** 正在为哪一首加载歌词（与 lyricSsid 分开：后者是「已加载好」的那首） */
    private var lyricLoadingFor: String? = null
    var qualities by mutableStateOf<List<String>>(emptyList()); private set
    /** 确认没有音源的档位：菜单里置灰。请求失败没探出来的不算在内 */
    var qualitiesAbsent by mutableStateOf<Set<String>>(emptySet()); private set

    /** 网易云歌曲不在 songs 里，出现过就记一份，播放页/歌词才能取到 */
    private val nmKnown = java.util.concurrent.ConcurrentHashMap<String, Song>()
    private fun keepNetease(l: List<Song>) {
        val nm = l.filter { it.source == SRC_NETEASE }
        if (nm.isEmpty()) return
        nm.forEach { nmKnown[it.ssid] = it }
        // 同时落一份快照（持久化），这样切界面 / 重启后历史仍能查到
        rememberSongs(nm)
    }
    /** 当前播放队列（按 ssid 索引）：播放器显示的歌必须是队列里那一条 */
    var queueSongs: Map<String, Song> by mutableStateOf(emptyMap()); private set

    /**
     * 当前正在播放的歌。
     *
     * 优先取播放队列里的那一条 —— 还原字符导入的歌单经常不在主曲库
     * （songs / songMap）里，只查 songMap 会拿到碰巧同 ssid 的另一条记录，
     * 于是播放器上的歌手 / 专辑名对了，点进去却跳到不相干的专辑页。
     * 找不到再依次退回主曲库、网易云缓存、快照、下载记录。
     */
    val currentSong: Song? by derivedStateOf {
        val id = curSsid ?: return@derivedStateOf null
        val base = queueSongs[id]
            ?: songMap[id]
            ?: nmKnown[id]
            ?: snapshots[id]
            ?: downloads.firstOrNull { it.ssid == id }?.let {
                Song(it.ssid, it.title, it.artist, it.album, it.cover.ifBlank { null })
            }
            ?: return@derivedStateOf null
        // 详情接口后来补全的 id 类字段，补回队列里的那条，保证跳转精准
        val full = nmKnown[id] ?: return@derivedStateOf base
        base.copy(
            nmId = base.nmId.takeIf { it > 0 } ?: full.nmId,
            nmAlbumId = base.nmAlbumId.takeIf { it > 0 } ?: full.nmAlbumId,
            nmArtistId = base.nmArtistId.takeIf { it > 0 } ?: full.nmArtistId,
            mvId = base.mvId.takeIf { it > 0 } ?: full.mvId,
            coverUrl = base.coverUrl ?: full.coverUrl,
            source = if (base.source == SRC_LOCAL && full.source == SRC_NETEASE) SRC_NETEASE else base.source,
        )
    }

    // ---- MV 可用性：只有确认拿得到地址才显示按钮 ----
    /** ssid -> 有没有 MV（确认过的才在表里） */
    private val mvOk = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** 正在确认的歌，避免同一首重复请求 */
    private val mvProbing = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )
    /** 确认结果回来了要触发一次重组 */
    var mvVersion by mutableStateOf(0); private set

    /**
     * 确认这首歌有没有 MV，结果按歌缓存（同一首只问一次）。
     *
     * 只有接口明确说「拿不到地址」才算没有 MV；请求失败（离线 / 网络抖动）
     * 判断不了，按「有」处理，不会因为一次抖动就把按钮永久藏掉。
     */
    fun probeMv(s: Song?) {
        val id = s?.mvId ?: return
        val k = s!!.ssid
        if (id <= 0L || mvOk.containsKey(k) || !mvProbing.add(k)) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = Netease.mvExists(id)
            withContext(Dispatchers.Main) {
                // 判断不了（网络/接口出错）就按「有」显示：
                // 不能因为一次抖动就把按钮永久藏掉
                mvOk[k] = ok ?: true
                mvVersion++
                mvProbing.remove(k)
            }
        }
    }

    /**
     * MV 按钮能不能显示。
     *
     * mvId > 0 只是「接口给了一个 id」，很多歌的 MV 已经下架或需要付费，
     * 点了也放不出来。所以必须 [probeMv] 确认过才显示；没确认前先不显示，
     * 免得没有 MV 的歌也冒出按钮。
     */
    fun mvAvailable(s: Song?): Boolean {
        mvVersion // 读一次：确认结果回来时这里会重新算
        val id = s?.mvId ?: return false
        if (id <= 0L) return false
        return mvOk[s!!.ssid] ?: false
    }
    // ===================================================================
    // 歌词翻译
    // ===================================================================
    /**
     * 译文缓存：ssid -> 按逻辑行对齐的译文。
     * 必须是 state：换掉它要让上面那些 derivedState 重新算，普通字段不会。
     */
    private var transCache by mutableStateOf(prefs.lyricTrans)

    /** 是否显示译文：已经有译文后再点翻译按钮就是切换显示 */
    var showTrans by mutableStateOf(true); private set

    /** 正在翻译整篇 */
    var transLoading by mutableStateOf(false); private set

    /** 当前歌词整理后的逻辑行（自带翻译的歌词会把原文与译文并成一行）。
     *  名字不能叫 buildLyricRows：那是 Data.kt 里造这个列表的函数。 */
    val lyricRows: List<LyricRow> by derivedStateOf {
        val raw = lyric
        if (raw.isEmpty()) emptyList() else buildLyricRows(raw, transCache[curSsid])
    }

    /** 当前句落在哪个逻辑行：播放位置是按原始行算的，这里换算一次 */
    val lyricRowIndex: Int by derivedStateOf {
        val i = lyricIndex
        if (i < 0) -1 else lyricRows.indexOfFirst { it.index == i }
    }

    /** 歌词里有非中文才给翻译按钮（中文歌不需要） */
    val lyricNeedTrans: Boolean by derivedStateOf { lyricNeedsTranslation(lyricRows) }

    /** 是否已经译过：逐字模型自带译文、歌词自带译文，或已经翻过并写进缓存 */
    val lyricHasTrans: Boolean by derivedStateOf {
        lyricRows.any { !it.trans.isNullOrBlank() } ||
            wordLyric?.lines?.any { !it.trans.isNullOrBlank() } == true
    }

    /**
     * 翻译当前歌词。
     *
     * 点一次就并发翻完整篇并缓存（接口没有频率限制，不用一句一句等）。
     * 已经有译文时不发请求，改成切换译文的显示 —— 按钮这时不该重复翻译。
     */
    /**
     * 需要翻译的行（只含正文段里真正带外文的行）。
     *
     * 纯中文的句子不在里面：它们不需要译文，也不提交给 AI
     * —— 否则 AI 会把中文句原样回一遍，界面上就多出一行没用的译文。
     */
    val lyricTransTargets: Set<Int> by derivedStateOf { computeTransTargets(lyricRows) }

    /**
     * 26.11.65：把译文贴进逐字模型。
     *
     * 之前翻译只写 transCache / showTrans，而新播放器歌词页只读 wordLyric
     * 的 line.trans，所以「翻译完成」后一个字都不显示。两套歌词模型之间
     * 必须显式桥接一次。
     *
     * 对齐规则：优先按行时间戳就近匹配（yrc 与 lrc 常差 20~30ms，容差 800ms），
     * 没有可用时间戳的纯文本歌词再按正文行顺序补齐；制作信息行不参与。
     */
    private fun mergeTransIntoWordLyric(res: List<String>) {
        val w = wordLyric ?: return
        val rows = lyricRows
        val pairs = res.mapIndexedNotNull { k, v ->
            val t = v.trim().takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            k to t
        }
        if (pairs.isEmpty()) return
        val body = w.lines.indices.filter {
            !w.lines[it].meta && w.lines[it].trans.isNullOrBlank()
        }
        if (body.isEmpty()) return
        val used = BooleanArray(pairs.size)
        val assign = HashMap<Int, String>()
        // 1) 时间戳就近匹配
        body.forEach { pos ->
            val line = w.lines[pos]
            if (line.startMs < 0) return@forEach
            var bestJ = -1
            var bestD = Long.MAX_VALUE
            pairs.forEachIndexed { j, (k, _) ->
                if (used[j]) return@forEachIndexed
                val t = rows.getOrNull(k)?.timeMs ?: -1
                if (t < 0) return@forEachIndexed
                val d = kotlin.math.abs(t - line.startMs)
                if (d < bestD) { bestD = d; bestJ = j }
            }
            if (bestJ >= 0 && bestD <= 800L) {
                used[bestJ] = true
                assign[pos] = pairs[bestJ].second
            }
        }
        // 2) 剩下按正文行顺序补齐（纯文本歌词 / 时间戳对不上时）
        var j = 0
        body.forEach { pos ->
            if (assign.containsKey(pos)) return@forEach
            while (j < pairs.size && used[j]) j++
            if (j < pairs.size) {
                used[j] = true
                assign[pos] = pairs[j].second
            }
        }
        if (assign.isEmpty()) return
        // 不覆盖 yrc 自带的译文（ytlrc / tlyric），它比机翻更准
        wordLyric = w.copy(
            lines = w.lines.mapIndexed { i, l ->
                val t = assign[i] ?: return@mapIndexed l
                if (!l.trans.isNullOrBlank()) l else l.copy(trans = t)
            },
            hasTranslation = true,
        )
    }

    /**
     * 26.11.65：所有歌词来源统一从这里装进逐字模型。
     * 翻译结果已经持久化在 transCache，切歌回来 / 重开应用后 wordLyric 是重新
     * 解析的，必须把缓存译文重新贴一次，否则「翻译完下次又不显示」。
     */
    private fun installWordLyric(w: WordLyric?, id: String, lrc: String) {
        wordLyric = w ?: WordLyricParser.parse(lrc = lrc)
        val cached = transCache[id]
        if (!cached.isNullOrEmpty() && cached.any { it.isNotBlank() }) {
            mergeTransIntoWordLyric(cached)
        }
    }

    fun translateLyric() {
        val rows = lyricRows
        val id = curSsid ?: return
        if (rows.isEmpty() && wordLyric == null) { toast("暂无歌词"); return }
        // 已经有译文（自带或译过）：按钮变成显示开关，不再重复请求
        if (lyricHasTrans) { showTrans = !showTrans; return }
        if (transLoading) return
        // 只翻译正文段里含外文的行；纯中文行留空，不显示译文
        val order = lyricTransTargets.sorted()
        if (order.isEmpty()) { toast("歌词已是中文，无需翻译"); return }
        transLoading = true
        viewModelScope.launch {
            val sub = order.map { rows[it].text }
            val got = Translate.all(sub)
            val res = MutableList(rows.size) { "" }
            got.forEachIndexed { k, v -> if (k < order.size) res[order[k]] = v }
            transLoading = false
            if (res.all { it.isBlank() }) { toast("翻译失败，请检查网络"); return@launch }
            transCache = transCache + (id to res)
            prefs.lyricTrans = transCache
            // 翻的过程中可能已经切歌了：缓存照存，但不要改动当前显示
            if (curSsid == id) {
                mergeTransIntoWordLyric(res)
                showTrans = true
                toast("已翻译 ${res.count { it.isNotBlank() }}/${order.size} 句")
            }
        }
    }

    // -------------------------------------------------------------------
    // 下载时补译文 / 离线播放时调取
    // -------------------------------------------------------------------
    /** 多批下载并行时，翻译任务串行跑，避免同时打一堆 AI 请求 */
    private val transMutex = Mutex()

    /**
     * 播放一首歌时：缓存里没有译文就从随下载保存的文件里调回来。
     *
     * 译文缓存放在应用缓存目录，系统「清除缓存」会清掉；下载目录里那份
     * 是跟着歌曲一起保存的，离线播放或清过缓存后都还能显示。
     */
    private fun loadLocalTransIfNeeded(id: String) {
        if (transCache[id]?.any { it.isNotBlank() } == true) return
        val ref = downloads.firstOrNull { it.ssid == id && it.trans.isNotBlank() }?.trans
            ?: return
        viewModelScope.launch {
            val lines = withContext(Dispatchers.IO) { LocalLib.readTrans(app, ref) }
            if (lines.any { it.isNotBlank() } &&
                transCache[id]?.any { it.isNotBlank() } != true
            ) {
                transCache = transCache + (id to lines)
                prefs.lyricTrans = transCache
                if (curSsid == id) mergeTransIntoWordLyric(lines)
                NmLog.note("已从下载文件调取译文：$id")
            }
        }
    }

    /** 一批下载结束后统一补译文（不阻塞下载队列本身） */
    private suspend fun ensureTranslations(done: List<Pair<Song, DownloadRecord>>) {
        transMutex.withLock {
            done.forEach { (s, rec) ->
                // 不能用 runCatching：它的 block 不是 suspend，里面调不了协程函数
                try {
                    ensureTranslated(s, rec)
                } catch (e: Exception) {
                    NmLog.note("下载补译文失败：${s.ssid} ${e.message}")
                }
            }
        }
    }

    /**
     * 下载一首歌后：能拿到译文就默认获取，并随下载一起保存。
     *
     * 「已经获取时跳过」有两层：
     * - 下载目录里已有译文文件 → 不再获取，只把引用和缓存补齐；
     * - 缓存里已有译文（之前点过翻译按钮）→ 不再请求接口，但仍写进下载目录。
     *
     * 歌词自带译文（同时间戳双行）本来就在 .lrc 里，天然随下载保存；
     * 纯中文歌 / 外语句子不足 10% 的也不去麻烦 AI。
     */
    private suspend fun ensureTranslated(s: Song, rec: DownloadRecord) {
        val id = s.ssid
        // 1. 已有译文文件：跳过获取
        val existing = prefs.downloads.firstOrNull { it.ssid == id && it.trans.isNotBlank() }
        if (existing != null) {
            val lines = withContext(Dispatchers.IO) { LocalLib.readTrans(app, existing.trans) }
            withContext(Dispatchers.Main) {
                // 同一首歌每个音质记录都记同一个引用：删到只剩一条才删文件
                val l = prefs.downloads.map {
                    if (it.ssid == id) it.copy(trans = existing.trans) else it
                }
                prefs.downloads = l
                downloads = l
                if (lines.any { it.isNotBlank() } &&
                    transCache[id]?.any { it.isNotBlank() } != true
                ) {
                    transCache = transCache + (id to lines)
                    prefs.lyricTrans = transCache
                }
            }
            return
        }
        // 2. 缓存里已有译文：跳过接口请求，但照样一起保存
        val cached = transCache[id]?.takeIf { it.any { v -> v.isNotBlank() } }
        if (cached != null) { saveTrans(id, s, cached); return }
        // 3. 取歌词（以随下载保存的那份为准，保证离线时行序一致）
        var lrc = withContext(Dispatchers.IO) { LocalLib.readText(app, rec.lyric) }
        if (lrc.isBlank()) {
            lrc = withContext(Dispatchers.IO) {
                val nid = neteaseIdOf(id)
                if (nid != null) runCatching { Netease.lyric(nid) }.getOrDefault("")
                else runCatching { Api.lyrics(id) }.getOrDefault("")
            }
        }
        if (lrc.isBlank()) return
        val rows = buildLyricRows(lyricLines(lrc))
        if (rows.any { !it.trans.isNullOrBlank() }) return  // 歌词自带译文
        if (!lyricNeedsTranslation(rows)) return            // 纯中文 / 外文太少
        val order = computeTransTargets(rows).sorted()
        if (order.isEmpty()) return
        val got = Translate.all(order.map { rows[it].text })
        val res = MutableList(rows.size) { "" }
        got.forEachIndexed { k, v -> if (k < order.size) res[order[k]] = v }
        // 翻不出来就不保存 —— 「可以获取翻译」时才一起保存
        if (res.all { it.isBlank() }) return
        saveTrans(id, s, res)
    }

    /** 把译文写进下载目录，并更新记录与缓存（在线播放也能立刻用） */
    private suspend fun saveTrans(id: String, s: Song, lines: List<String>) {
        val ref = withContext(Dispatchers.IO) { LocalLib.writeTrans(app, s, lines) }
        if (ref.isBlank()) return
        withContext(Dispatchers.Main) {
            val l = prefs.downloads.map { if (it.ssid == id) it.copy(trans = ref) else it }
            prefs.downloads = l
            downloads = l
            transCache = transCache + (id to lines)
            prefs.lyricTrans = transCache
        }
        NmLog.note("译文已随下载保存：$id（${lines.count { it.isNotBlank() }} 句）")
    }

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
            // 26.11.61：旧曲库源站已停用，启动时不再发任何请求等超时。
            // 曲库完全由本地数据组装：已下载、播放过的快照、歌单里的歌、
            // 以及搜索过的网易云歌曲。网易云搜索 / 榜单仍走独立接口，
            // 所以这里不置 offline，下载与音质探测照常可用。
            songs = withContext(Dispatchers.IO) { buildLocalLibrary() }
            offline = false
            loading = false
            pendingLink?.let { pendingLink = null; handleLink(it) }
            // 26.11.66：进入应用即静默同步网易云歌单（登录过才跑，一次会话只跑一次）
            autoSyncNmPlaylists()
        }
    }

    /** 26.11.61：不再依赖源站 /api/songs，从本地持久化数据组装曲库 */
    private fun buildLocalLibrary(): List<Song> {
        val out = LinkedHashMap<String, Song>()
        runCatching { LocalLib.songs(prefs.downloads) }
            .getOrDefault(emptyList()).forEach { out[it.ssid] = it }
        snapshots.values.forEach { s ->
            if (s.title.isNotBlank()) out.putIfAbsent(s.ssid, s)
        }
        nmKnown.values.forEach { s -> out.putIfAbsent(s.ssid, s) }
        playlists.forEach { pl ->
            pl.songs.forEach { id ->
                snapshots[id]?.let { s -> out.putIfAbsent(id, s) }
            }
        }
        return out.values.toList()
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
            autoSyncDone = false
            autoSyncNmPlaylists()
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
        // 先交给 Netease 清洗（去掉 Set-Cookie 属性、去重、补 os=pc），
        // 再把干净的那份持久化：以前直接存原始串，重启后 cookie 里混着
        // Max-Age / Path，请求会被判无效，高音质也拿不到。
        Netease.cookie = cookie
        prefs.nmCookie = Netease.cookie
        homeLoaded = false
        showNmLogin = false
        // 26.11.66：启动时若还没登录，那次自动同步没跑；登录成功后立刻补一次
        autoSyncDone = false
        autoSyncNmPlaylists()
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
        // 26.11.81：先把旧凭证留一份，本地立刻清干净，再后台调用 /logout
        // 注销服务端会话；本地退出不等待网络结果。
        val oldCookie = Netease.cookie
        prefs.nmCookie = ""
        prefs.nmAccount = ""
        Netease.cookie = ""
        homeLoaded = false
        nmUser = null
        nmSongs = emptyList()
        nmAlbums = emptyList()
        nmArtists = emptyList()
        toast("正在退出网易云…")
        viewModelScope.launch(Dispatchers.IO) {
            val ok = if (oldCookie.isBlank()) false
                else runCatching { Netease.logout(oldCookie) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                toast(if (ok) "已退出网易云（服务端会话已注销）"
                      else "已退出本地登录，服务端注销未确认")
            }
        }
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
        // 26.11.34：本地文件只按文件里的 Meta 展示，点封面不跳页、不联网查询
        if (s.source == SRC_DEVICE || LocalAudio.isDevice(s.ssid)) return
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
        // 老还原字符没带专辑 id 时才用名字搜，且必须真的对得上：
        // 直接 firstOrNull() 会跳到搜索结果里第一张不相干的专辑
        viewModelScope.launch {
            val g = withContext(Dispatchers.IO) {
                val hits = Netease.searchAlbums("$s.artist ${s.album}", 10)
                hits.firstOrNull { h ->
                    h.title.isNotBlank() && (
                    h.title.equals(s.album, ignoreCase = true) ||
                        h.title.contains(s.album, ignoreCase = true) ||
                        s.album.contains(h.title, ignoreCase = true))
                }
            }
            if (g != null) openNmGroup(g) else toast("没有找到这张专辑")
        }
    }

    /** 网易云歌曲：点歌手进歌手页（走网易云，不查本地库） */
    fun openNmSongArtist(s: Song) {
        // 26.11.34：本地文件只按文件里的 Meta 展示，点歌手不跳页、不联网查询
        if (s.source == SRC_DEVICE || LocalAudio.isDevice(s.ssid)) return
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
            val names = s.artist.split(Regex("[/,&、]")).map { it.trim() }
                .filter { it.isNotBlank() }.ifEmpty { listOf(s.artist) }
            val g = withContext(Dispatchers.IO) {
                Netease.searchArtists(s.artist, 10).firstOrNull { h ->
                    h.title.isNotBlank() && names.any { n ->
                        h.title.equals(n, ignoreCase = true) ||
                            h.title.contains(n, ignoreCase = true) ||
                            n.contains(h.title, ignoreCase = true)
                    }
                }
            }
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
        // 专辑页副标题显示歌手名（列表没带时稍后补）；歌手页留空
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
                // 专辑页副标题显示歌手名；列表里没带歌手时，单独取一次详情。
                // 榜单（playlist）的副标题是更新频率，不需要补，也不该去调 /album
                if (kind == "album" && nmPageSubtitle.isBlank()) {
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

    /** 26.11.49：听歌历史批量删除（一次写盘，避免逐条刷 prefs） */
    fun removeHistoryBatch(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val l = history.filter { it !in ids }
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

    /**
     * 是否已被系统排除在电池优化之外。
     *
     * 没豁免时，熄屏一段时间进入 Doze 后系统会限制网络并回收后台服务 ——
     * 表现就是「后台放着放着就停了，通知也没了」。国产 ROM 尤其激进。
     * 这个开关只能在系统设置里由用户手动改，应用无法自己打开。
     */
    var batteryExempt by mutableStateOf(true); private set

    /** 重新读一次电池优化状态（设置页打开、从系统设置返回时调） */
    fun refreshBatteryExempt() {
        batteryExempt = runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (Build.VERSION.SDK_INT >= 23) pm?.isIgnoringBatteryOptimizations(app.packageName) ?: true
            else true
        }.getOrDefault(true)
    }

    /** 打开系统的电池优化设置页（让用户手动加白名单） */
    fun openBatterySettings() {
        try {
            val i = if (Build.VERSION.SDK_INT >= 23) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${app.packageName}")
                }
            } else {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(i)
        } catch (e: Exception) {
            try {
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .let { app.startActivity(it) }
            } catch (ignored: Exception) { }
        }
    }

    /**
     * 打开应用详情页。
     *
     * 国产 ROM（小米 / 华为 / OPPO / vivo 等）在电池白名单之外，
     * 还有「自启动」「后台运行」「锁定最近任务」几道开关，没有统一接口，
     * 只能把用户送到应用详情页自己找。
     */
    fun openAppDetails() {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${app.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(i)
        } catch (e: Exception) {
            runCatching { openBatterySettings() }
        }
    }

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

    /**
     * 打开「更新说明」阅读页：不要求有新版本，也不触发下载。
     *
     * 启动时静默检查过一次，updateInfo 通常已有值；万一没查到（断网、
     * 代理全挂），也构造一个最小 Info，让弹窗仍能从仓库拉完整 md 显示。
     */
    fun openUpdateNotes() {
        showUpdate = true
        if (updateInfo != null || updateChecking) return
        updateChecking = true
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { Updater.check() }.getOrNull() }
            updateInfo = info ?: Updater.Info(
                latest = appVersion,
                url = "",
                page = Updater.RELEASES_PAGE,
                notes = "",
                size = 0L,
                hasUpdate = false,
                notesUrl = Updater.notesUrlFor(appVersion),
            )
            updateChecking = false
        }
    }

    fun closeUpdate() {
        showUpdate = false
        updateProgress = null
    }

    /**
     * 清除缓存：封面、歌词翻译、播放整首缓存、更新安装包。
     * 这些都在 ctx.cacheDir 下，删掉不影响歌单、下载记录与登录态。
     */
    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val freed = AppCache.clear(app)
            withContext(Dispatchers.Main) {
                toast(
                    if (freed > 0) "已清除缓存 ${freed / 1024 / 1024} MB"
                    else "没有可清除的缓存",
                )
            }
        }
    }

    /** 应用内更新：下载 APK 后走系统安装（需要安装权限） */
    fun updateInApp() {
        val info = updateInfo ?: return
        if (updateProgress != null) return
        // 安装包放缓存目录：装完就删，也不占数据目录（方便清理）
        val dir = AppCache.updateDir(app)
        updateProgress = 0L to 0L
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                // 下载回调是「每读完一个 64KB 块」触发一次。
                // 之前每个块都 viewModelScope.launch 一个协程去写状态 ——
                // 一个 30MB 的安装包会瞬间堆出几百个协程，全部挤到主线程，
                // 界面被拖住、看着就像有几十个下载在同时跑。
                // 现在直接写状态（Compose 的 State 是线程安全的），
                // 并节流到 200ms 一次；最后一块必定写入，保证进度收尾到 100%。
                var lastEmit = 0L
                Updater.downloadApk(info, dir) { done, total ->
                    val now = System.currentTimeMillis()
                    if (done < total && now - lastEmit < 200) return@downloadApk
                    lastEmit = now
                    updateProgress = done to total
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

    /**
     * 应用内直升：下载完 APK 后直接用 PackageInstaller 覆盖安装。
     *
     * 这是「升级」不是「重装」——MODE_FULL_INSTALL + 同一包名 + 相同签名，
     * 系统会保留数据，用户不需要先卸载。
     *
     * 这里不申请 USER_ACTION_NOT_REQUIRED（免确认）：
     * 多数设备会拒绝它，而且是异步通过广播返回失败的，
     * 提交时看不出问题，结果就是点了更新什么都没发生。
     * 走标准流程会拿到 STATUS_PENDING_USER_ACTION，
     * 由 InstallResultReceiver 把系统确认界面拉起来。
     */
    fun installApk(f: java.io.File) {
        needInstallPerm = false
        updateProgress = null
        updateFile = f
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { commitSession(f) }.onFailure {
                NmLog.note("安装会话提交失败：${it.message}")
            }.isSuccess
            // 会话这条路走不通就退回系统安装界面
            if (!ok) withContext(Dispatchers.Main) { installViaView(f) }
        }
    }

    /** 建立 PackageInstaller 会话并提交 */
    private fun commitSession(f: java.io.File) {
        val installer = app.packageManager.packageInstaller
        val p = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        // 指定被更新的包名，系统才按「升级」处理从而保留数据
        runCatching { p.setAppPackageName(app.packageName) }
        val id = installer.createSession(p)
        installer.openSession(id).use { ses ->
            ses.openWrite("GoMusic", 0, f.length()).use { os ->
                f.inputStream().use { it.copyTo(os) }
                ses.fsync(os)
            }
            val pi = android.app.PendingIntent.getBroadcast(
                app, id,
                android.content.Intent(app, InstallResultReceiver::class.java)
                    .setAction(InstallResultReceiver.ACTION)
                    .putExtra(InstallResultReceiver.EXTRA_SESSION, id)
                    .putExtra(InstallResultReceiver.EXTRA_FILE, f.absolutePath),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (android.os.Build.VERSION.SDK_INT >= 23)
                        android.app.PendingIntent.FLAG_IMMUTABLE else 0),
            )
            ses.commit(pi.intentSender)
        }
        NmLog.note("安装会话已提交 id=$id")
    }

    /** 兜底：用系统安装界面打开 APK */
    fun installViaView(f: java.io.File) {
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.fileprovider", f,
            )
        }.getOrElse {
            toast("无法打开安装界面：${it.message}")
            return
        }
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { app.startActivity(i) }
            .onFailure { toast("无法打开安装界面：${it.message}") }
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
    /** 榜单（/toplist） */
    var homeTops by mutableStateOf<List<Group>>(emptyList()); private set
    /** 离线模式下首页要展示的已下载歌曲 */
    val homeDownloaded: List<Song> get() = LocalLib.songs(downloads)
    /** 热搜词 */
    var homeHotWords by mutableStateOf<List<String>>(emptyList()); private set
    /** 26.11.87：首页新增六个推荐位 */
    var homePersonalized by mutableStateOf<List<Group>>(emptyList()); private set
    var homeRecommendPlaylists by mutableStateOf<List<Group>>(emptyList()); private set
    var homeTopPlaylists by mutableStateOf<List<Group>>(emptyList()); private set
    var homeHqPlaylists by mutableStateOf<List<Group>>(emptyList()); private set
    var homeRecommendMv by mutableStateOf<List<Group>>(emptyList()); private set
    var homeFirstMv by mutableStateOf<List<Group>>(emptyList()); private set
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
            homeTops = emptyList()
            homePersonalized = emptyList()
            homeRecommendPlaylists = emptyList()
            homeTopPlaylists = emptyList()
            homeHqPlaylists = emptyList()
            homeRecommendMv = emptyList()
            homeFirstMv = emptyList()
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
                // 显式写空列表类型：runCatching + getOrDefault(emptyList()) 推不出元素类型
                val dT = async(Dispatchers.IO) {
                    runCatching { Netease.topList() }.getOrDefault(emptyList<Group>())
                }
                // 26.11.87：六个新推荐位各自独立，一路失败不影响其余
                val dP = async(Dispatchers.IO) {
                    runCatching { Netease.homeGroups("/personalized", "limit" to "10") }
                        .getOrDefault(emptyList<Group>())
                }
                val dRp = async(Dispatchers.IO) {
                    runCatching { Netease.homeGroups("/recommend/resource") }
                        .getOrDefault(emptyList<Group>())
                }
                val dTp = async(Dispatchers.IO) {
                    runCatching {
                        Netease.homeGroups("/top/playlist", "order" to "hot", "limit" to "10")
                    }.getOrDefault(emptyList<Group>())
                }
                val dHq = async(Dispatchers.IO) {
                    runCatching {
                        Netease.homeGroups("/top/playlist/highquality", "limit" to "10")
                    }.getOrDefault(emptyList<Group>())
                }
                val dRm = async(Dispatchers.IO) {
                    runCatching { Netease.homeGroups("/personalized/mv") }
                        .getOrDefault(emptyList<Group>())
                }
                val dFm = async(Dispatchers.IO) {
                    runCatching { Netease.homeGroups("/mv/first", "limit" to "10") }
                        .getOrDefault(emptyList<Group>())
                }
                val r = dR.await()
                val n = dN.await()
                val h = dH.await()
                val artists = dA.await()
                val albums = dAl.await()
                val tops = dT.await()
                keepNetease(r + n)
                homeRecommend = r
                homeNewSongs = n
                homeHotWords = h
                homeTopArtists = artists
                homeNewAlbums = albums
                homeTops = tops
                homePersonalized = dP.await()
                homeRecommendPlaylists = dRp.await()
                homeTopPlaylists = dTp.await()
                homeHqPlaylists = dHq.await()
                homeRecommendMv = dRm.await()
                homeFirstMv = dFm.await()
                homeLoaded = true
                NmLog.note(
                    "首页加载完成：推荐 ${r.size} / 新歌 ${n.size} / 热搜 ${h.size}" +
                        " / 榜单 ${tops.size} / 热门歌手 ${artists.size} / 最新专辑 ${albums.size}"
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
    val songMap: Map<String, Song> by derivedStateOf { songs.associateBy { it.ssid } }

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

    /**
     * 专辑 / 歌手页用：以主曲库为准；主曲库里没有这张专辑 / 这个歌手时
     * （还原字符导入的歌、播放队列里的歌常常不在当前曲库），
     * 再从队列 / 网易云缓存 / 快照 / 下载记录里补齐，
     * 否则点进去是空页，看着就像「跳到了别的专辑」。
     */
    fun songsForGroup(artist: String, album: String? = null): List<Song> {
        if (artist.isBlank() || (album != null && album.isBlank())) return emptyList()
        val match: (Song) -> Boolean = {
            it.artist == artist && (album == null || it.album == album)
        }
        val inLib = songs.filter(match)
        if (inLib.isNotEmpty()) return inLib
        val pool = queueSongs.values + nmKnown.values + snapshots.values +
            downloads.map {
                Song(it.ssid, it.title, it.artist, it.album, it.cover.ifBlank { null })
            }
        return pool.filter(match).distinctBy { it.ssid }
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

    /**
     * 队列条目。uri 里带上当前音质：换音质时 uri 跟着变，播放器才会重新解析音源。
     * 只靠 prefs 的话，replaceMediaItem 拿到的是同一个 uri，
     * ExoPlayer 认为没变化，于是「音质切了，音源还是原来那份」。
     */
    private fun toItem(s: Song): MediaItem {
        // 26.11.34：系统送进来的本地音频直接用文件 Uri，不走占位地址，
        // 也就不会去服务端取地址 / 探测音质。其余歌曲仍按音质参数解析。
        val uri = s.audioUri?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: Uri.parse("gomusic:///${s.ssid}?q=${quality}&nl=${prefs.nmLevel}")
        return MediaItem.Builder()
            .setMediaId(s.ssid)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.title)
                    .setArtist(s.artist)
                    .setAlbumTitle(s.album)
                    .setArtworkUri(artworkUri(s.coverUrl))
                    .build()
            )
            .build()
    }

    fun playSongs(list: List<Song>, index: Int) {
        if (list.isEmpty()) return
        // 先记下整份队列：导入歌单里的歌可能不在主曲库，
        // 播放器与「点封面/歌手」都要以这一份为准
        queueSongs = list.associateBy { it.ssid }
        // 26.11.34：本地音乐也要进快照，否则播放历史里查不到这首歌
        rememberSongs(list)
        keepNetease(list)
        val c = controller
        if (c == null) {
            afterConnect = { playSongs(list, index) }
            return
        }
        // 点下去就先把要播这首的地址取起来：取地址要打十几个请求，
        // 而它同时在 ExoPlayer 的准备线程上同步做。提前起一轮，
        // 等播放器真正来解析时多半已经落在缓存里，不用干等。
        // 26.11.55：预取改成同步执行，必须放到 IO，不能再占主线程。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { prefetchCurrent(list, index) }
        }
        c.setMediaItems(list.map(::toItem), index.coerceIn(0, list.lastIndex), 0)
        c.prepare()
        c.play()
        // 队列刚建立就把后面两首也预热上。以前只靠切歌回调触发预取，
        // 而 ExoPlayer 会在当前首快结束时提前解析下一首 —— 那时缓存还是空的，
        // 预取永远比播放解析晚一步，于是「切下一首卡半天」。
        prefetchAhead()
    }

    /**
     * 预取马上要播的这一首的音源地址。
     *
     * 只取这一首，不连带后面几首：预取本身也是十几个请求，
     * 一次预取三首等于把请求量翻三倍，反而把当前这首歌的起播拖慢。
     */
    private fun prefetchCurrent(list: List<Song>, index: Int) {
        val p = Prefs(app)
        Netease.cookie = p.nmCookie
        val nid = neteaseIdOf(list[index.coerceIn(0, list.lastIndex)].ssid) ?: return
        Netease.prefetchAudioUrl(nid, quality, p.nmLevel.takeIf { it.isNotBlank() })
    }

    fun playIndex(i: Int) {
        controller?.let { it.seekTo(i, 0); it.play() }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /**
     * 录制页进入时暂停，退出时按「进来前是否在播」恢复；不动队列与播放位置。
     *
     * 26.11.21：旧版读 vm.isPlaying 快照 + 单行 runCatching，控制器未连好或
     * 状态回调滞后时暂停被静默吞掉。改为无条件发暂停，恢复依据取控制器即时状态；
     * 同时用服务层 action 兜底，MediaSession 没转发也能停 / 续。
     */
    fun pauseForRecorder(): Boolean {
        val c = controller
        val was = c?.isPlaying == true || c?.playWhenReady == true || isPlaying
        runCatching { c?.pause() }
        runCatching {
            app.startService(
                Intent(app, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_REC_PAUSE)
            )
        }
        return was
    }

    fun resumeFromRecorder(wasPlaying: Boolean) {
        if (!wasPlaying) return
        runCatching { controller?.play() }
        runCatching {
            app.startService(
                Intent(app, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_REC_RESUME)
            )
        }
    }

    fun pausePlayback() { pauseForRecorder() }
    fun resumePlayback() { resumeFromRecorder(true) }

    fun next() {
        val c = controller ?: return
        // 单曲循环：不跳列表，直接把这一首从头重播
        if (playMode == MODE_REPEAT_ONE) { c.seekTo(0); c.play(); return }
        c.seekToNext()
        // 只 seekToNext 时，若播放器正处在暂停 / ENDED / 新项还没 prepare，
        // 切过去就是「歌名换了但不出声，还得再点一下播放」。
        c.play()
    }

    fun prev() {
        val c = controller ?: return
        // 单曲循环：同上，从头重播这一首
        if (playMode == MODE_REPEAT_ONE) { c.seekTo(0); c.play(); return }
        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPrevious()
        c.play()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        // 立刻写回，歌词点击跳转不用等下一次 250ms 轮询
        positionMs = ms.coerceAtLeast(0)
    }

    fun moveQueue(from: Int, to: Int) { controller?.moveMediaItem(from, to) }
    fun removeQueue(i: Int) { controller?.removeMediaItem(i) }

    /** 把一首歌追加到播放列表末尾（只加这一首，不动队列里已有的歌） */
    fun addToQueue(s: Song) {
        queueSongs = queueSongs + (s.ssid to s)
        keepNetease(listOf(s))
        val c = controller
        if (c == null) {
            afterConnect = { addToQueue(s) }
            return
        }
        c.addMediaItem(toItem(s))
        toast("已加入播放列表：${s.title}")
    }

    /**
     * 26.11.68：清空播放列表，当前正在播放的那一首也一起移除。
     *
     * 先暂停再一次性清掉整条时间线，避免逐项删除过程中当前项被移除时
     * 触发「自动切到下一首」，把清空误记成一次播放；随后本地队列状态立刻
     * 置空并收起播放器（队列空了留在播放器里没有意义）。
     */
    fun clearQueue() {
        val c = controller ?: return
        val n = c.mediaItemCount
        if (n > 0) {
            c.pause()
            runCatching { c.clearMediaItems() }.getOrElse {
                for (i in c.mediaItemCount - 1 downTo 0) c.removeMediaItem(i)
            }
        }
        queue = emptyList()
        queueSongs = emptyMap()
        showPlayer = false
        toast("已清空播放列表")
    }

    fun cycleMode() {
        playMode = (playMode + 1) % 3
        prefs.playMode = playMode
        controller?.let(::applyMode)
        toast(listOf("列表循环", "随机播放", "单曲循环")[playMode])
    }

    /** 图 4 的 Segmented 直接选模式，不用循环点三次 */
    fun selectPlayMode(m: Int) {
        playMode = m.coerceIn(0, 2)
        prefs.playMode = playMode
        controller?.let(::applyMode)
    }

    private fun applyMode(c: MediaController) {
        when (playMode) {
            MODE_LIST_LOOP -> { c.repeatMode = Player.REPEAT_MODE_ALL; c.shuffleModeEnabled = false }
            MODE_SHUFFLE -> { c.repeatMode = Player.REPEAT_MODE_ALL; c.shuffleModeEnabled = true }
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

    /** 歌曲 id -> 实际能拿到的音质档位，探测过就缓存，避免每首都重探 */
    private val nmLevelsCache =
        java.util.concurrent.ConcurrentHashMap<Long, Netease.LevelProbe>()

    /** 外观：0 亮色 / 1 暗色 / 2 自动（跟随系统） */
    var themeMode by mutableIntStateOf(prefs.themeMode); private set
    fun chooseThemeMode(m: Int) {
        themeMode = m.coerceIn(0, 2)
        prefs.themeMode = themeMode
    }

    /**
     * 26.11.74：「我的喜欢」＝ 本地红心 ∪ 网易云「我喜欢的音乐」歌单。
     *
     * 删除不再「发一次请求就算完」：本地删掉的网易云歌曲先记进
     * pendingUnlike，确认 /like?like=false 成功才出队。没登录 / 没拿到
     * 红心歌单 id / 接口失败都留着，下次同步重放；同步合并云端红心时
     * 这批歌会被排除，不会再并回本地、更不会被当成新歌重新上传。
     */
    var favorites by mutableStateOf(prefs.favorites); private set
    fun isFavorite(ssid: String?): Boolean = ssid != null && ssid in favorites

    /** 重放待删队列，返回真正在云端删掉的 ssid；失败的继续留在队列里 */
    private fun flushPendingUnlike(pid: Long): Set<String> {
        val pending = prefs.pendingUnlike
        if (pending.isEmpty() || pid <= 0L || !canSyncCloud()) return emptySet()
        Netease.cookie = prefs.nmCookie
        val ids = pending.mapNotNull { neteaseIdOf(it) }.filter { it > 0L }.distinct()
        if (ids.isEmpty()) {
            // 全是本地专属歌曲，云端本来就没有，直接出队
            prefs.pendingUnlike = emptySet()
            return emptySet()
        }
        val okIds = Netease.likeRemoveIds(pid, ids)
        val done = pending.filter {
            val n = neteaseIdOf(it); n != null && n in okIds
        }.toSet()
        prefs.pendingUnlike = pending - done
        if (done.isNotEmpty()) NmLog.note("红心待删队列重放 ${done.size}/${pending.size}")
        return done
    }

    /** 把本地红心的改动推到网易云「我喜欢的音乐」歌单 */
    private fun syncFavToCloud(ssids: Collection<String>, add: Boolean) {
        val nm = ssids.filter { neteaseIdOf(it) != null }.toSet()
        if (nm.isEmpty()) return
        prefs.pendingUnlike = if (add) prefs.pendingUnlike - nm
        else prefs.pendingUnlike + nm
        if (!canSyncCloud()) return // 未登录：留在队列，登录后同步重放
        val pid = prefs.nmLikePid
        if (pid <= 0L) {
            // 以前这里直接 return，删除动作就丢了；同步末尾会 flush 待删队列
            syncNmPlaylists(quiet = true)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            Netease.cookie = prefs.nmCookie
            val ids = nm.mapNotNull { neteaseIdOf(it) }.filter { it > 0L }.distinct()
            if (add) {
                val ok = Netease.likeAdd(pid, ids)
                withContext(Dispatchers.Main) {
                    if (ok == 0) toast("网易云红心未同步成功（本地已改）")
                }
            } else {
                val okIds = Netease.likeRemoveIds(pid, ids)
                val done = nm.filter {
                    val n = neteaseIdOf(it); n != null && n in okIds
                }.toSet()
                prefs.pendingUnlike = prefs.pendingUnlike - done
                withContext(Dispatchers.Main) {
                    if (done.size < nm.size) toast("网易云红心未删除成功（本地已删，下次同步重试）")
                }
            }
        }
    }

    fun toggleFavorite(ssid: String?): Boolean {
        if (ssid == null) return false
        val add = ssid !in favorites
        val next = if (add) favorites + ssid else favorites - ssid
        favorites = next
        prefs.favorites = next
        syncFavToCloud(listOf(ssid), add)
        return add
    }

    /** 26.11.49：我的喜欢批量移除 */
    fun removeFavorites(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val next = favorites - ids.toSet()
        favorites = next
        prefs.favorites = next
        syncFavToCloud(ids, add = false)
    }

    /** 26.11.74：清空（二次确认后）同时清空云端红心歌单，失败的留待重放 */
    fun clearFavorites() {
        val removed = favorites
        favorites = emptySet()
        prefs.favorites = emptySet()
        prefs.nmLikeStamp = ""
        val nm = removed.filter { neteaseIdOf(it) != null }.toSet()
        prefs.pendingUnlike = prefs.pendingUnlike + nm
        if (!canSyncCloud()) return
        val pid = prefs.nmLikePid
        if (pid <= 0L) {
            syncNmPlaylists(quiet = true)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            Netease.cookie = prefs.nmCookie
            // 本地清掉的 + 云端全量：本地可能本来就比云端少几首
            val cloudIds = runCatching { Netease.playlistSongs(pid) }
                .getOrDefault(emptyList<Song>())
                .mapNotNull { neteaseIdOf(it.ssid) }
                .filter { it > 0L }
            val ids = (nm.mapNotNull { neteaseIdOf(it) } + cloudIds).distinct()
            val okIds = if (ids.isNotEmpty()) Netease.likeRemoveIds(pid, ids) else emptySet()
            val done = prefs.pendingUnlike.filter {
                val n = neteaseIdOf(it); n != null && n in okIds
            }.toSet()
            prefs.pendingUnlike = prefs.pendingUnlike - done
            withContext(Dispatchers.Main) {
                if (okIds.size < ids.size) toast("云端红心未完全清除（本地已清空，下次同步重试）")
            }
        }
    }

    /** 搜索记录（最近在前），提交时才写 */
    var searchHistory by mutableStateOf(prefs.searchHistory); private set

    /** 26.11.59：最近搜索实体列表，取代界面上的胶囊关键词行。
     *  旧版本只存了关键词，首次升级时把它们转成「搜索词」行，不至于变空。 */
    var recentItems by mutableStateOf(
        prefs.recentItems.ifEmpty {
            prefs.searchHistory.map {
                RecentItem(
                    key = "kw|$it",
                    kind = "keyword",
                    title = it,
                    subtitle = "搜索词",
                )
            }
        }
    ); private set

    private fun putRecent(item: RecentItem) {
        val next = (listOf(item) + recentItems.filter { it.key != item.key }).take(20)
        recentItems = next
        viewModelScope.launch(Dispatchers.IO) {
            prefs.recentItems = next
        }
    }

    /** 从搜索结果点过一首歌：记一条带封面、副标题的行，并异步补一句歌词片段 */
    fun recordRecentSong(s: Song) {
        val key = "song|${s.ssid}"
        putRecent(
            RecentItem(
                key = key,
                kind = "song",
                title = s.title,
                subtitle = "歌曲 · ${s.artist}",
                cover = s.coverUrl,
                ssid = s.ssid,
                artist = s.artist,
            )
        )
        val nid = neteaseIdOf(s.ssid) ?: return
        // 图里歌曲行有「歌词："…"」第三行。只取第一句正文，短超时、失败就留空，
        // 不阻塞点击、不占播放解析线程。
        viewModelScope.launch(Dispatchers.IO) {
            val line = withTimeoutOrNull(2_500L) {
                runCatching {
                    val r = Prefetcher.warmLyric(nid)
                    // 26.11.66：高级逐字歌词的 lrc 开头是 yrc 的 JSON 制作信息
                    // （{"t":0,"c":[{"tx":"作词: "}…]}），按行取会直接把 JSON 原样
                    // 显示成「歌词」。优先用逐字模型里 meta=false 的正文行。
                    val fromWord = r.word.lines.firstOrNull { l ->
                        !l.meta && l.text.isNotBlank() && !l.text.trim().startsWith("{")
                    }?.text?.trim()
                    fromWord ?: r.lrc.lineSequence()
                        .map {
                            it.replace(
                                Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]"""), ""
                            ).trim()
                        }
                        .firstOrNull { t ->
                            t.isNotBlank() &&
                                !t.startsWith("{") && !t.contains("\"tx\"") &&
                                !t.startsWith("作词") && !t.startsWith("作曲") &&
                                !t.startsWith("编曲") && !t.startsWith("制作人")
                        }
                }.getOrNull()
            }
            if (!line.isNullOrBlank()) {
                withContext(Dispatchers.Main) { updateRecentLyric(key, line.take(38)) }
            }
        }
    }

    /** 从搜索结果点过艺人或专辑 */
    fun recordRecentGroup(g: Group) {
        val isArtist = g.nmKind == "artist"
        putRecent(
            RecentItem(
                key = "${g.nmKind}|${g.nmId}|${g.title}",
                kind = if (isArtist) "artist" else "album",
                title = g.title,
                subtitle = if (isArtist) "艺人" else "专辑 · ${g.artist.ifBlank { g.subtitle }}",
                cover = g.cover,
                artist = g.artist,
                nmId = g.nmId,
                nmKind = g.nmKind,
            )
        )
    }

    private fun updateRecentLyric(key: String, line: String) {
        val next = recentItems.map { if (it.key == key) it.copy(lyricLine = line) else it }
        recentItems = next
        prefs.recentItems = next
    }

    /** 移除单条最近搜索（行右侧菜单用） */
    fun removeRecent(key: String) {
        val next = recentItems.filter { it.key != key }
        recentItems = next
        prefs.recentItems = next
    }

    fun recordSearch(q: String) {
        val v = q.trim()
        if (v.isEmpty()) return
        val next = (listOf(v) + searchHistory).distinct().take(20)
        searchHistory = next
        // 关键词也进实体列表，旧版升级后仍看得到搜过的词
        putRecent(
            RecentItem(key = "kw|$v", kind = "keyword", title = v, subtitle = "搜索词")
        )
        // 26.11.64：JSON 编码 + SharedPreferences 写盘挪到 IO。
        // 回车那一刻主线程只做状态更新，界面立刻响应。
        viewModelScope.launch(Dispatchers.IO) {
            prefs.searchHistory = next
        }
    }

    fun clearSearchHistory() {
        searchHistory = emptyList()
        prefs.searchHistory = emptyList()
        recentItems = emptyList()
        prefs.recentItems = emptyList()
    }

    /** 逐字歌词（第 1 期只落数据层；播放器老歌词列表仍用行级 lyric） */
    var wordLyric by mutableStateOf<WordLyric?>(null); private set

    /** 聚合搜索结果：一次回车拿全三类，切板块只切筛选不重发 */
    var aggregate by mutableStateOf<Netease.Aggregate?>(null); private set
    /** 26.11.63：网络还在跑时给界面一个轻状态，空屏不再被当成「没搜到」 */
    var searchBusy by mutableStateOf(false); private set
    /** 26.11.64：正在输入、还没回车。此时零网络请求，也不显示「正在搜索…」 */
    var searchPending by mutableStateOf(false); private set
    /** 输入时的本机曲库即时匹配，打字就有反馈 */
    var localPreview by mutableStateOf<List<Song>>(emptyList()); private set
    /** 超过 2.5 秒三路仍空时给一句明确提示，不让界面一直转 */
    var searchHint by mutableStateOf(""); private set

    // ---- 26.11.65：聚合搜索分页 ----
    /** 歌曲 / 专辑 / 歌手各自是否还有下一页；做成 state 才能驱动列表底部重组 */
    var searchHasMoreSongs by mutableStateOf(true); private set
    var searchHasMoreAlbums by mutableStateOf(true); private set
    var searchHasMoreArtists by mutableStateOf(true); private set
    /** 26.11.87：歌单板块是否还有下一页 */
    var searchHasMorePlaylists by mutableStateOf(true); private set
    var searchMoreLoading by mutableStateOf(false); private set
    /** 本机曲库混入歌曲结果的数量，算服务端 offset 时要扣掉 */
    private var searchLocalCount = 0

    fun searchCanLoadMore(filter: Int = searchFilter): Boolean = when (filter) {
        1 -> searchHasMoreArtists
        2 -> searchHasMoreAlbums
        4 -> searchHasMorePlaylists
        else -> searchHasMoreSongs
    }

    private data class SearchCacheEntry(
        val agg: Netease.Aggregate,
        val at: Long,
        /** 三路全空视为失败结果，不缓存，下次回车允许重试 */
        val complete: Boolean,
        /** 本机曲库混入的数量，翻页时算 offset 要扣掉 */
        val localCount: Int = 0,
    )
    private val searchCacheTtl = 10 * 60 * 1000L
    /** 按关键词缓存聚合结果（LRU 20 条）：重复回车 / 切板块都不再打网络 */
    private val aggregateCache = LinkedHashMap<String, SearchCacheEntry>(20, 0.75f, true)
    private var searchJob: Job? = null
    /** 每次回车递增：旧请求即便晚到也不许覆盖新关键词的结果 */
    private var searchSeq = 0L

    private fun readAggregateCache(k: String): SearchCacheEntry? = synchronized(aggregateCache) {
        val e = aggregateCache[k] ?: return null
        if (!e.complete || System.currentTimeMillis() - e.at > searchCacheTtl) {
            aggregateCache.remove(k)
            null
        } else e
    }
    private fun putAggregateCache(k: String, v: Netease.Aggregate) = synchronized(aggregateCache) {
        val ok = v.songs.isNotEmpty() || v.albums.isNotEmpty() || v.artists.isNotEmpty()
        aggregateCache[k] = SearchCacheEntry(
            v, System.currentTimeMillis(), ok, searchLocalCount,
        )
        while (aggregateCache.size > 20) aggregateCache.remove(aggregateCache.keys.first())
    }

    private fun resetSearchPages() {
        searchHasMoreSongs = true
        searchHasMoreAlbums = true
        searchHasMoreArtists = true
        searchHasMorePlaylists = true
        searchMoreLoading = false
        searchLocalCount = 0
    }

    /** type=10 / 100 返回空时，用已经拿到的歌曲在本地聚合，零额外请求 */
    private fun groupsFromSongs(list: List<Song>): Pair<List<Group>, List<Group>> {
        val al = LinkedHashMap<String, Group>()
        val ar = LinkedHashMap<String, Group>()
        list.forEach { s ->
            if (s.album.isNotBlank() && !al.containsKey(s.album)) {
                al[s.album] = Group(
                    title = s.album, subtitle = s.artist, cover = s.coverUrl,
                    songs = emptyList(), album = s.album, source = s.source,
                    nmId = s.nmAlbumId, nmKind = "album",
                )
            }
            if (s.artist.isNotBlank() && !ar.containsKey(s.artist)) {
                ar[s.artist] = Group(
                    title = s.artist, subtitle = "歌手", cover = s.coverUrl,
                    songs = emptyList(), artist = s.artist, source = s.source,
                    nmId = s.nmArtistId, nmKind = "artist",
                )
            }
        }
        return al.values.toList().take(10) to ar.values.toList().take(10)
    }

    /** 本机曲库匹配：直接遍历列表，不再走 derivedStateOf 的 associateBy 建全量 Map */
    private fun localMatch(k: String): List<Song> =
        songs.asSequence().filter {
            it.title.contains(k, true) || it.artist.contains(k, true) || it.album.contains(k, true)
        }.take(20).toList()

    /**
     * 26.11.64：输入过程零网络。
     * 旧版每敲一个字都保留上一次的大结果并随输入整页重组，还让上一轮在途请求
     * 继续跑，打字自然发涩。现在输入即掐断在途、清空旧结果，只给本机即时匹配。
     * 回车才按约定一次性发歌曲/歌手/专辑三路。
     */
    fun onQueryChange(q: String) {
        query = q
        ++searchSeq
        searchJob?.cancel(); searchJob = null
        searchBusy = false
        searchHint = ""
        resetSearchPages()
        if (q.isBlank()) {
            searchPending = false
            localPreview = emptyList()
            aggregate = null
            return
        }
        searchPending = true
        localPreview = localMatch(q.trim())
        aggregate = null
    }

    fun searchAll(q: String) {
        val k = q.trim()
        val seq = ++searchSeq
        searchPending = false
        if (k.isEmpty()) {
            searchJob?.cancel(); searchJob = null
            aggregate = null; searchBusy = false; searchHint = ""
            return
        }
        recordSearch(k)
        searchFilter = 0
        resetSearchPages()
        readAggregateCache(k)?.let { e ->
            searchJob?.cancel(); searchJob = null
            aggregate = e.agg
            searchLocalCount = e.localCount
            searchBusy = false; searchHint = ""
            return
        }

        // 本机曲库先亮，不等网络；断网或接口失败时歌曲板块仍有内容
        val local = localMatch(k)
        searchLocalCount = local.size
        aggregate = Netease.Aggregate(local, emptyList(), emptyList())
        searchBusy = true
        searchHint = ""

        // 新关键词直接掐掉上一轮：旧请求不再占线程，也不会晚到覆盖新结果
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // 2.5 秒仍只有本地结果就给一句提示，不让人干等一个转圈
            launch {
                delay(2_500L)
                if (seq == searchSeq && searchBusy) {
                    val cur = aggregate
                    if (cur != null && cur.albums.isEmpty() && cur.artists.isEmpty() &&
                        cur.songs.size <= local.size
                    ) {
                        searchHint = "网络较慢，仍在搜索「$k」…"
                    }
                }
            }
            // 26.11.87：歌单作为第四路独立并发，谁先回谁先显示，不等三路主链
            launch(Dispatchers.IO) {
                val pl = runCatching {
                    Netease.cookie = prefs.nmCookie
                    Netease.searchPlaylistGroups(k, 20, 0)
                }.getOrDefault(emptyList())
                withContext(Dispatchers.Main) {
                    if (seq != searchSeq) return@withContext
                    val cur = aggregate ?: return@withContext
                    aggregate = cur.copy(playlists = pl)
                    searchHasMorePlaylists = pl.size >= 20
                    putAggregateCache(k, aggregate!!)
                }
            }
            val result = runCatching {
                Netease.cookie = prefs.nmCookie
                // 三路各自异步、可真取消；每路回来立刻推给界面，不等最慢那路
                Netease.searchAllStream(k) { songs, albums, artists ->
                    if (seq != searchSeq) return@searchAllStream
                    val cur = aggregate
                        ?: Netease.Aggregate(local, emptyList(), emptyList())
                    val newSongs = if (songs != null) {
                        (local + songs).distinctBy { it.ssid }
                    } else cur.songs
                    // 后到的空结果不要冲掉先到的本地兜底
                    var newAlbums = if (albums == null) cur.albums else albums.ifEmpty { cur.albums }
                    var newArtists = if (artists == null) cur.artists else artists.ifEmpty { cur.artists }
                    if (songs != null && songs.isNotEmpty()) {
                        val (fallbackAlbums, fallbackArtists) = groupsFromSongs(songs)
                        if (newAlbums.isEmpty()) newAlbums = fallbackAlbums
                        if (newArtists.isEmpty()) newArtists = fallbackArtists
                    }
                    aggregate = Netease.Aggregate(newSongs, newAlbums, newArtists)
                    // 26.11.66：歌曲一路回来就后台补封面，不占首屏、不串行等详情。
                    // /search 的 al.picUrl 经常为空，enrichResult=false 时更是整批没图，
                    // 这才是「最佳匹配和后续结果都不显示图片」的根因。
                    if (songs != null) enrichSearchCovers(seq, k)
                    if (songs?.isNotEmpty() == true || albums?.isNotEmpty() == true ||
                        artists?.isNotEmpty() == true
                    ) {
                        searchHint = ""
                    }
                }
            }.getOrNull()

            // 只缓存有内容的结果；全空（超时/异常）留着下次重试
            val finalAgg = aggregate
            if (seq == searchSeq && result != null && finalAgg != null) {
                putAggregateCache(k, finalAgg)
                enrichSearchCovers(seq, k)
                searchBusy = false
                searchHint = ""
            } else if (seq == searchSeq) {
                searchBusy = false
            }
        }
    }

    /**
     * 26.11.66：搜索结果的封面统一在后台补。
     *
     * 三路搜索为了提速都关掉了详情补全（enrichResult=false），/search 的
     * al.picUrl 又经常是空串，于是综合页「最佳匹配」和翻页回来的新歌
     * 全是灰占位。这里只挑缺封面的网易云歌曲，一次 /song/detail 批量补齐，
     * 完全异步、不进首屏关键链；补完顺带把本地兜底生成的专辑/歌手卡
     * 的封面也填上。
     */
    private val coverInFlight = HashSet<Long>()

    private fun enrichSearchCovers(seq: Long, k: String) {
        val snapshot = aggregate ?: return
        val need = snapshot.songs
            .filter { it.coverUrl.isNullOrBlank() && it.nmId > 0 && it.nmId !in coverInFlight }
            .take(40)
        if (need.isEmpty()) return
        need.forEach { coverInFlight += it.nmId }
        viewModelScope.launch {
            val filled = withContext(Dispatchers.IO) {
                runCatching { Netease.enrich(need) }.getOrDefault(need)
            }
            need.forEach { coverInFlight -= it.nmId }
            if (seq != searchSeq) return@launch
            val cur = aggregate ?: return@launch
            val byId = filled.associateBy { it.ssid }
            val songs = cur.songs.map { s ->
                val f = byId[s.ssid] ?: return@map s
                if (!s.coverUrl.isNullOrBlank()) s
                else s.copy(
                    coverUrl = f.coverUrl ?: s.coverUrl,
                    album = f.album.takeIf { it.isNotBlank() } ?: s.album,
                    artist = f.artist.takeIf { it.isNotBlank() } ?: s.artist,
                    nmAlbumId = f.nmAlbumId.takeIf { it > 0 } ?: s.nmAlbumId,
                    nmArtistId = f.nmArtistId.takeIf { it > 0 } ?: s.nmArtistId,
                    mvId = f.mvId.takeIf { it > 0 } ?: s.mvId,
                )
            }
            // 兜底聚合出来的专辑/歌手没有封面时用补好的歌曲按名字回填
            val (fa, fr) = groupsFromSongs(songs)
            val albumCovers = fa.associateBy { it.title }
            val artistCovers = fr.associateBy { it.title }
            val albums = cur.albums.map { g ->
                if (!g.cover.isNullOrBlank()) g
                else albumCovers[g.title]?.cover?.let { g.copy(cover = it) } ?: g
            }
            val artists = cur.artists.map { g ->
                if (!g.cover.isNullOrBlank()) g
                else artistCovers[g.title]?.cover?.let { g.copy(cover = it) } ?: g
            }
            aggregate = Netease.Aggregate(songs, albums, artists)
            putAggregateCache(k, aggregate!!)
        }
    }

    private fun groupKey(g: Group): String =
        if (g.nmId > 0) "nm${g.nmId}" else "loc${g.title}"

    /**
     * 26.11.65：聚合搜索页触底翻页。
     *
     * 旧 loadMoreNetease() 只服务四个旧 Tab 的 nmSongs / nmAlbums / nmArtists，
     * 新搜索页读的是 aggregate，UI 里从来没人调用过任何翻页函数 ——
     * 所以滑到底永远不会加载下一页。这里按当前选中板块补对应那一类。
     *
     * offset 直接由当前结果数推导（首页歌曲 20 条、专辑/歌手 10 条），
     * 缓存命中后也能接着翻；本机曲库混入歌曲结果的部分要扣掉。
     */
    fun loadMoreSearch() {
        val k = query.trim()
        val cur = aggregate ?: return
        if (k.isBlank() || searchMoreLoading || searchBusy) return
        val filter = searchFilter
        if (!searchCanLoadMore(filter)) return
        // 按当前板块只补对应那一类：综合 / 歌曲补歌曲，专辑补专辑，歌手补歌手
        val type = when (filter) {
            1 -> 100
            2 -> 10
            4 -> 1000
            else -> 1
        }
        val off = when (type) {
            10 -> cur.albums.size
            100 -> cur.artists.size
            1000 -> cur.playlists.size
            else -> (cur.songs.size - searchLocalCount).coerceAtLeast(0)
        }
        val seq = searchSeq
        searchMoreLoading = true
        viewModelScope.launch {
            try {
                // 异步可取消的搜索专用通道；不做详情补全、不做二次兜底
                if (type == 1000) {
                    val add = withContext(Dispatchers.IO) {
                        runCatching {
                            Netease.cookie = prefs.nmCookie
                            Netease.searchPlaylistGroups(k, NM_PAGE_SIZE, off)
                        }.getOrDefault(emptyList())
                    }
                    if (seq == searchSeq) {
                        val now = aggregate ?: cur
                        val seen = now.playlists.map { groupKey(it) }.toHashSet()
                        val real = add.filter { groupKey(it) !in seen }
                        aggregate = now.copy(playlists = now.playlists + real)
                        searchHasMorePlaylists = real.isNotEmpty()
                        aggregate?.let { putAggregateCache(k, it) }
                    }
                    return@launch
                }
                val page = withContext(Dispatchers.IO) {
                    try {
                        Netease.searchPage(k, type, NM_PAGE_SIZE, off)
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        null
                    }
                }
                if (seq != searchSeq) return@launch
                val now = aggregate ?: cur
                if (page == null || !page.ok) {
                    stopMore(type)
                    return@launch
                }
                when (type) {
                    10 -> {
                        val seen = now.albums.map { groupKey(it) }.toHashSet()
                        val real = page.albums.filter { groupKey(it) !in seen }
                        aggregate = now.copy(albums = now.albums + real)
                        searchHasMoreAlbums = page.size >= NM_PAGE_SIZE && real.isNotEmpty()
                    }
                    100 -> {
                        val seen = now.artists.map { groupKey(it) }.toHashSet()
                        val real = page.artists.filter { groupKey(it) !in seen }
                        aggregate = now.copy(artists = now.artists + real)
                        searchHasMoreArtists = page.size >= NM_PAGE_SIZE && real.isNotEmpty()
                    }
                    else -> {
                        val seen = now.songs.map { it.ssid }.toHashSet()
                        val real = page.songs.filter { it.ssid !in seen }
                        aggregate = now.copy(songs = now.songs + real)
                        searchHasMoreSongs = page.size >= NM_PAGE_SIZE && real.isNotEmpty()
                    }
                }
                // 翻到的页也进缓存，切走再回来不用重翻
                aggregate?.let { putAggregateCache(k, it) }
                // 26.11.66：翻页走的是 enrichResult=false 快路径，新歌没有封面，
                // 同样在后台补一次，别让「后续结果」全是灰图。
                enrichSearchCovers(seq, k)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NmLog.err("loadMoreSearch($k,$off)", e)
                stopMore(type)
            } finally {
                if (seq == searchSeq) searchMoreLoading = false
            }
        }
    }

    private fun stopMore(type: Int) {
        when (type) {
            10 -> searchHasMoreAlbums = false
            100 -> searchHasMoreArtists = false
            1000 -> searchHasMorePlaylists = false
            else -> searchHasMoreSongs = false
        }
    }

    /** 简繁转换模式：0 简体 / 1 繁体 / 2 仅搜索统一 / 3 不转换 */
    var zhConvert by mutableIntStateOf(prefs.zhConvert)
    /** 全局字体：0 系统 / 1 思源宋体 / 2 思源黑体 */
    var fontKind by mutableIntStateOf(prefs.fontKind); private set
    /** 可变字体的字重（wght），250~900，默认 450 */
    var fontWght by mutableIntStateOf(prefs.fontWght); private set


    /** 改简繁设置：立即生效（界面与文字都会跟着变）
     *  名字不能叫 setZhConvert —— 会和 var 自动生成的 setter 撞 JVM 签名 */
    fun chooseZhConvert(m: Int) {
        zhConvert = m
        prefs.zhConvert = m
    }

    /** 切换全局字体（0 系统 / 1 思源宋体 / 2 思源黑体）。字体内置在 APK 里，切换即生效 */
    fun chooseFontKind(k: Int) {
        fontKind = k
        prefs.fontKind = k
    }

    /**
     * 调整可变字体的字重（250~900）。
     * 拖动滑块时每次都会落盘，退出再进保持不变。
     */
    fun chooseFontWght(w: Int) {
        val v = w.coerceIn(250, 900)
        fontWght = v
        prefs.fontWght = v
    }

    // ---------- 歌词排版（只作用于歌词页，与全局字体互不影响） ----------
    /** 歌词字号 sp，默认 [LYRIC_SIZE_DEFAULT] */
    var lyricSize by mutableIntStateOf(prefs.lyricSize); private set
    /** 歌词字体来源，默认跟随全局 */
    var lyricFont by mutableIntStateOf(prefs.lyricFont); private set
    /** 歌词字重 wght，默认 [LYRIC_WGHT_DEFAULT] */
    var lyricWght by mutableIntStateOf(prefs.lyricWght); private set

    fun chooseLyricSize(v: Int) {
        lyricSize = v.coerceIn(LYRIC_SIZE_MIN, LYRIC_SIZE_MAX)
        prefs.lyricSize = lyricSize
    }

    fun chooseLyricFont(v: Int) {
        lyricFont = v.coerceIn(0, 3)
        prefs.lyricFont = lyricFont
    }

    fun chooseLyricWght(v: Int) {
        lyricWght = v.coerceIn(LYRIC_WGHT_MIN, LYRIC_WGHT_MAX)
        prefs.lyricWght = lyricWght
    }

    /** 恢复三项默认值 */
    fun resetLyricStyle() {
        chooseLyricSize(LYRIC_SIZE_DEFAULT)
        chooseLyricFont(LYRIC_FONT_FOLLOW)
        chooseLyricWght(LYRIC_WGHT_DEFAULT)
    }

    /** 当前是否为网易云音质档位名（quality 菜单可能是网易云的档位） */
    val nmQualityMode: Boolean get() = curSsid?.let { isNetease(it) } == true
    /** 网易云当前音质档位（空 = 跟随设置） */
    val nmLevel: String get() = prefs.nmLevel
    /** 26.11.81：切成 Compose 状态，切档位时播放器上的中文名才会立刻刷新 */
    var nmLevelState by mutableStateOf(prefs.nmLevel); private set

    /** 当前网易云歌曲实际用的档位：菜单里高亮这一项 */
    val curLevel: String get() =
        nmLevelState.takeIf { it.isNotBlank() } ?: nmLevelOfQuality(quality)

    /**
     * 26.11.81：播放器进度条下方显示当前音质的中文名，随切歌 / 切档位实时变化。
     * 旧版直接把 quality 键（normal / high / …）当文字画出来，只有 lossless
     * 特判成「无损」，所以用户看到的是一个英文 normal。
     * 网易云歌曲走网易云档位（curLevel），本站曲库走 quality，两套都转中文。
     */
    val qualityDisplay: String get() =
        if (nmQualityMode) nmLevelLabel(curLevel) else qualityLabel(quality)

    fun updateQuality(k: String) {
        // 网易云歌曲：档位存到 nmLevel，不动本站的 quality
        if (k in NM_LEVELS) {
            if (k == curLevel) return
            prefs.nmLevel = k
            nmLevelState = k
        } else {
            quality = k
            prefs.quality = k
        }
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        try {
            val i = c.currentMediaItemIndex
            val pos = c.currentPosition
            val old = c.getMediaItemAt(i)
            // 只改 uri 上的音质参数、保留原有元数据：
            // 之前要回头查 songsByIds 找 Song，查不到就静默放弃、什么都不发生
            val uri = "gomusic:///${old.mediaId}?q=$quality&nl=${prefs.nmLevel}"
            val item = old.buildUpon().setUri(Uri.parse(uri)).build()
            val wasPlaying = c.isPlaying
            c.replaceMediaItem(i, item)
            // 强制重新准备：只 replace 的话 ExoPlayer 会沿用已缓冲的旧音源，
            // 听感上就是「档位切了，声音没变」
            c.stop()
            c.prepare()
            c.seekTo(i, pos)
            if (wasPlaying) c.play()
        } catch (ignored: Exception) {
        }
    }

    // ================= 播放器连接 =================
    private fun attach(c: MediaController) {
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                this@MainViewModel.isPlaying = isPlaying
                if (isPlaying) playerEverPlayed = true
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onItem(mediaItem, true)
                prefetchAhead()
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                refreshQueue()
                prefetchAhead()
            }
        })
        applyMode(c)
        c.setPlaybackSpeed(speed)
        isPlaying = c.isPlaying
        if (c.isPlaying) playerEverPlayed = true
        refreshQueue()
        onItem(c.currentMediaItem, false)
        afterConnect?.let { afterConnect = null; it() }
    }

    /**
     * 打开音质菜单时才探测这首歌有哪些档位（结果缓存，只探一次）。
     *
     * 放在这里而不是切歌时：探测最多要 14 个请求，跟播放毫无关系，
     * 切歌时跑它会和取地址、取歌词抢连接，把整首歌的起播拖慢。
     */
    fun probeQualities() {
        val id = curSsid ?: return
        val nid = neteaseIdOf(id) ?: return
        if (offline) return
        if (nmLevelsCache.containsKey(nid)) {
            nmLevelsCache[nid]?.let {
                qualitiesAbsent = it.absent.toSet()
                if (it.ok.isNotEmpty()) qualities = it.ok
            }
            return
        }
        if (qualitiesProbing) return
        qualitiesProbing = true
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) {
                runCatching { Netease.availableLevels(nid) }.getOrNull()
            }
            qualitiesProbing = false
            if (p != null) {
                nmLevelsCache[nid] = p
                if (curSsid == id) {
                    qualitiesAbsent = p.absent.toSet()
                    if (p.ok.isNotEmpty() && !offline) qualities = p.ok
                }
            }
        }
    }

    private var qualitiesProbing = false

    /**
     * 预取后面两首的完整信息（音源地址 / 逐字歌词 / 歌曲详情）。
     * 走 Prefetcher 的独立 OkHttp dispatcher 与独立协程域，不和当前播放解析抢线程；
     * 切歌时旧任务会被取消，不会累积请求。
     */
    private var lastPrefetchKey: String? = null
    private var lastPrefetchAt = 0L

    private fun prefetchAhead() {
        val c = controller ?: return
        val p = Prefs(app)
        Netease.cookie = p.nmCookie
        val i = c.currentMediaItemIndex + 1
        val ids = (i until minOf(c.mediaItemCount, i + 2)).mapNotNull {
            neteaseIdOf(c.getMediaItemAt(it).mediaId)
        }
        if (ids.isEmpty()) return
        // 26.11.55：一次切歌会连着触发 onTimelineChanged、
        // onMediaItemTransition，playSongs 里还会主动调一次，三处 ids 一样。
        // 以前每处都发一轮预取（每首十几个请求），把播放地址解析挤到队尾。
        // 同一批 1.5 秒内只发一次；ids 变了（真的切歌 / 改音质）立即放行。
        val key = "${c.mediaItemCount}|$i|${ids.joinToString(",")}|$quality|${p.nmLevel}"
        val now = System.currentTimeMillis()
        if (key == lastPrefetchKey && now - lastPrefetchAt < 1_500L) return
        lastPrefetchKey = key
        lastPrefetchAt = now
        Prefetcher.prefetchAhead(ids, quality, p.nmLevel.takeIf { it.isNotBlank() })
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

    /**
     * 歌词加载失败后重试：点「暂无歌词」时调用。
     *
     * 失败 / 超时分支同样会把 lyricSsid 写成当前 id，之后 onItem 遇到同一首歌
     * 就直接 return，界面会一直停在「暂无歌词」，只能切走再切回来。
     * 这里把「已加载 / 在途 / 旧任务」三个标记一起清掉，再走一次完整加载路径
     * （含译文补取与本地歌词兜底），让点击能真正重新发请求。
     */
    fun reloadLyric() {
        val id = curSsid ?: return
        // 26.11.69：以前这里有一句 `if (lyricLoading) return`。加载失败或
        // 超时后 lyricLoading 偶尔残留 true（任务被取消、回调没走到复位），
        // 于是点重试直接被挡掉，界面一直停在「暂无歌词」。重试是用户主动
        // 发起的，必须能打断任何残留状态：先取消旧任务，再把三个标记全清。
        lyricJob?.cancel()
        lyricSsid = null
        lyricLoadingFor = null
        lyric = emptyList()
        wordLyric = null
        lyricLoading = true
        // 清掉预取层的歌词缓存：失败结果以前也会被缓存，不清就永远重放空结果
        neteaseIdOf(id)?.let { Prefetcher.clearLyric(it) }
        // 26.11.35：本地歌重试要真正重读文件标签，不能只重放旧缓存
        if (LocalAudio.isDevice(id)) LocalAudio.clearLyric(app, id)
        NmLog.note("歌词重试：id=$id")
        val item = controller?.currentMediaItem
        if (item == null) {
            lyricLoading = false
            toast("播放器未就绪，请稍后重试")
            return
        }
        onItem(item, false)
    }

    private fun onItem(item: MediaItem?, count: Boolean) {
        val id = item?.mediaId?.takeIf { it.isNotEmpty() }
        curSsid = id
        curIndex = controller?.currentMediaItemIndex ?: 0
        if (id == null) {
            lyric = emptyList()
            lyricLoading = false
            lyricSsid = null
            qualities = emptyList()
            qualitiesAbsent = emptySet()
            return
        }
        // 同一首歌（切音质后会再次走到这里）：歌词不用重来、也不该重复计播放。
        //
        // 但必须同时确认歌词确实已经加载好了 —— 只判 id == lyricSsid 会卡死：
        // lyricSsid 是加载「完成后」才写的，于是 A 的歌词加载中切到 B，
        // B 还没加载完又切回 A 时，lyricSsid 仍是 A，这里直接 return，
        // 而此时 lyric 已被清空、lyricLoading 还是 true —— 界面就一直转圈。
        // 表现就是「下一首再切回上一首，歌词永远加载不出来」。
        // 已经加载好了：直接复用（切音质后会再次走到这里，不该重来）
        if (id == lyricSsid) return
        // 正在为这一首加载：让它跑完，绝不能重启。
        //
        // 上一版把 lyricSsid 提前到加载开始时写入，于是同一首歌在加载期间
        // 再次走到这里时，lyricSsid 已经等于 id，被当成「没在加载」而重启任务。
        // onItem 会被多次调用（连接播放器一次、切歌回调一次），于是任务被
        // 反复取消重启：歌词永远出不来，且每次重启都重新发一轮网络请求，
        // 把连接池占满 —— 音频和图片也跟着变慢。
        if (id == lyricLoadingFor) return
        lyricJob?.cancel()
        lyricLoadingFor = id
        lyric = emptyList()
        // 逐字模型必须跟着切歌清掉，否则会残留上一首的 YRC 时间轴
        wordLyric = null
        lyricLoading = true
        qualities = emptyList()
        qualitiesAbsent = emptySet()
        if (count) {
            val m = counts.toMutableMap()
            m[id] = (m[id] ?: 0) + 1
            counts = m
            prefs.playCounts = m
            pushHistory(id) // 本站与网易云统一记录
            rememberSongs(listOfNotNull(songMap[id] ?: nmKnown[id] ?: snapshots[id]))
        }
        // 26.11.34：设备本地音频 —— 信息全在文件里，绝不发网络请求
        if (LocalAudio.isDevice(id)) {
            qualities = emptyList()
            qualitiesAbsent = emptySet()
            updateCover(snapshots[id]?.coverUrl ?: item?.mediaMetadata?.artworkUri?.toString())
            lyricJob = viewModelScope.launch {
                val t = try {
                    withContext(Dispatchers.IO) {
                        LocalAudio.loadLyric(
                            app, id, snapshots[id]?.audioUri ?: songMap[id]?.audioUri
                        ).ifBlank { localLyrics(id) }
                    }
                } catch (e: Exception) { "" }
                if (curSsid == id) {
                    lyric = lyricLines(t)
                    // 老 /lyric、本站、本地、离线歌词也统一进逐字模型：
                    // 没有 yrc 时退化成「整行一个字组」，仍用新版大字样式渲染。
                    installWordLyric(null, id, t)
                    lyricLoading = false
                    lyricSsid = id
                }
                if (lyricLoadingFor == id) lyricLoadingFor = null
            }
            return
        }
        updateCover((songMap[id] ?: nmKnown[id])?.coverUrl ?: item?.mediaMetadata?.artworkUri?.toString())
        // 缓存里没有译文时，从随下载保存的文件里调回来（离线播放尤其需要）
        loadLocalTransIfNeeded(id)
        // 网易云歌曲：歌词走网易云接口，优先级高于本站
        if (isNetease(id)) {
            if (count) pushHistory(id)
            // 网易云歌曲：音质档位与本站分开，菜单里列网易云自己的档位。
            // 离线时只列已经下载过的音质（没下载的播不了，不显示）
            qualities = if (offline) downloadedQualities(id).ifEmpty { NM_LEVELS } else NM_LEVELS
            val nid = neteaseIdOf(id)
            // 音质档位不再在切歌时探测。
            //
            // 探测要对 7 个档位各发一次请求（失败还会再试一次），最多 14 个
            // 请求 —— 而它只是为了让菜单能置灰「无音源」的档位，跟播放无关。
            // 切歌时它和取地址、取歌词、预取一起涌向同一个服务端，
            // 是「什么都慢」的最大一块。改成打开音质菜单时才探测
            // （见 probeQualities），切歌本身一个请求都不多发。
            if (nid != null && !offline) {
                nmLevelsCache[nid]?.let { hit ->
                    qualitiesAbsent = hit.absent.toSet()
                    if (hit.ok.isNotEmpty()) qualities = hit.ok
                }
            }
            // 封面 / 专辑 / 歌手详情与歌词彻底分开：以前歌词排在 /song/detail
            // 之后串行发，详情一慢歌词就跟着干等。详情自己跑一个任务，
            // 只有 curSsid 仍是这首歌时才写回，切歌不会串数据。
            if (nid != null) {
                viewModelScope.launch {
                    val d = withTimeoutOrNull(5_000L) {
                        withContext(Dispatchers.IO) {
                            runCatching { Netease.detailOf(nid) }.getOrNull()
                        }
                    }
                    if (d != null && curSsid == id) {
                        nmKnown[id] = d
                        probeMv(d)
                        // 用详情里的完整信息刷新列表里的这一首
                        nmSongs = nmSongs.map { if (it.ssid == id) d else it }
                        updateCover(d.coverUrl)
                        // 记进快照（会持久化），保证历史里始终能查到这首
                        rememberSongs(listOf(d))
                    }
                }
            }
            // 歌词单独一条快速通道：不等详情、不等其它接口。
            // 26.11.42：优先 /lyric/new 逐字歌词，任何失败由 Netease 内部
            // 自动退回老 /lyric；再不行才读本地歌词。行级 lyric 仍喂给老列表，
            // 逐字模型 wordLyric 留给第 3 期歌词页渲染。
            lyricJob = viewModelScope.launch {
                var w: WordLyric? = null
                // 加超时：接口慢时不再一直卡在「正在加载歌词…」，
                // 超时就按「没有歌词」处理，界面显示「暂无歌词」
                val t = withTimeoutOrNull(lyricTimeoutMs) {
                    var x = ""
                    if (nid != null) {
                        withContext(Dispatchers.IO) {
                            val r = runCatching { Prefetcher.warmLyric(nid) }.getOrNull()
                            if (r != null) {
                                w = r.word
                                x = r.lrc
                            } else {
                                // warmLyric 内部已经完成「新接口失败 → 老 /lyric」的回退，
                                // 只有整体抛异常时才补一次，避免把老接口连打四遍。
                                x = runCatching { Netease.lyric(nid) }.getOrDefault("")
                            }
                        }
                    }
                    if (x.isBlank()) x = withContext(Dispatchers.IO) { localLyrics(id) }
                    x
                } ?: ""
                if (curSsid == id) {
                    lyric = lyricLines(t)
                    // 老 /lyric、本站、本地、离线歌词也统一进逐字模型；
                    // 同时把持久化的接口译文重新贴回，切歌回来不会丢。
                    installWordLyric(w, id, t)
                    lyricLoading = false
                    lyricSsid = id
                }
                if (lyricLoadingFor == id) lyricLoadingFor = null
            }
            return
        }
        // 离线模式：直接读本地歌词，绝不发网络请求
        // （否则会走 3 次重试 + 退避等待，歌词要十几秒才出来，还会拖住其他请求）
        if (offline) {
            // 离线只能播已下载的音质，菜单里就不列没下载的
            qualities = downloadedQualities(id)
            lyricJob = viewModelScope.launch {
                val t = withTimeoutOrNull(lyricTimeoutMs) {
                    withContext(Dispatchers.IO) { localLyrics(id) }
                } ?: ""
                if (curSsid == id) {
                    lyric = lyricLines(t)
                    // 老 /lyric、本站、本地、离线歌词也统一进逐字模型：
                    // 没有 yrc 时退化成「整行一个字组」，仍用新版大字样式渲染。
                    installWordLyric(null, id, t)
                    lyricLoading = false
                    lyricSsid = id
                }
                if (lyricLoadingFor == id) lyricLoadingFor = null
            }
            return
        }
        lyricJob = viewModelScope.launch {
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
                val more = withTimeoutOrNull(lyricTimeoutMs) {
                    var x = if (t.isBlank()) {
                        withContext(Dispatchers.IO) { runCatching { Api.lyrics(id) }.getOrDefault("") }
                    } else t
                    if (x.isBlank()) x = withContext(Dispatchers.IO) { localLyrics(id) }
                    x
                }
                if (more != null) t = more
                if (curSsid == id) {
                    lyric = lyricLines(t)
                    // 老 /lyric、本站、本地、离线歌词也统一进逐字模型：
                    // 没有 yrc 时退化成「整行一个字组」，仍用新版大字样式渲染。
                    installWordLyric(null, id, t)
                    lyricLoading = false
                    lyricSsid = id
                }
                if (lyricLoadingFor == id) lyricLoadingFor = null
            } catch (ignored: Exception) {
                val t = withContext(Dispatchers.IO) { localLyrics(id) }
                if (curSsid == id && t.isNotBlank()) {
                    lyric = lyricLines(t)
                    installWordLyric(null, id, t)
                }
                if (curSsid == id) {
                    lyricLoading = false
                    lyricSsid = id
                }
                if (lyricLoadingFor == id) lyricLoadingFor = null
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
        uploadLocalPlaylist(p.id)
        return p
    }

    /** 26.11.66：本地新建歌单 → 云端 create + 加歌 + 回填 nmId */
    private fun uploadLocalPlaylist(localId: String) {
        if (!canSyncCloud()) return
        val p = playlists.firstOrNull { it.id == localId } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val pid = runCatching { Netease.playlistCreate(cloudName(p)) }.getOrDefault(0L)
            if (pid <= 0L) return@launch
            val nids = p.songs.mapNotNull { neteaseIdOf(it) }.filter { it > 0L }
            if (nids.isNotEmpty()) runCatching { Netease.playlistAdd(pid, nids) }
            withContext(Dispatchers.Main) {
                savePl(playlists.map {
                    if (it.id == localId && it.nmId <= 0L)
                        it.copy(nmId = pid, cloudBase = cloudName(it)) else it
                })
            }
        }
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

    // ================= 同步网易云歌单 =================

    /** 同步状态：true 表示正在同步，界面上给个转圈 */
    var nmSyncing by mutableStateOf(false); private set
    private var autoSyncDone = false

    /** 与本地歌单重名时，云端那份展示名后面加的标签 */
    private val CLOUD_TAG = " 云"

    /** 云端原始名：带「云」标签的本地展示名要还原后再回写 / 比对 */
    private fun cloudName(p: Playlist): String = p.cloudBase ?: p.name

    private fun canSyncCloud(): Boolean = nmOn && prefs.nmCookie.isNotBlank()

    /** 进入应用时静默同步一次；手动按钮仍走 syncNmPlaylists() */
    private fun autoSyncNmPlaylists() {
        if (!canSyncCloud() || autoSyncDone) return
        autoSyncDone = true
        syncNmPlaylists(quiet = true)
    }

    /**
     * 26.11.71：网易云歌单**全量**双向同步 + 「我喜欢的音乐」合并。
     *
     * ① 取云端全部歌单：自建 + 收藏/订阅，不再按 creator 过滤；
     * ② 每张歌单按 trackIds 拉全量曲目，不是只拿前 10 首；
     * ③ 本地没有的下载下来，重名的那份加「云」标签，cloudBase 记住云端原名；
     * ④ 本地自建歌单反向在云端 create 并回填 nmId；⑤ 顺序也同步到云端。
     *
     * specialType=5 的「我喜欢的音乐」不进歌单列表：它的曲目与本地
     * 「我的喜欢」取并集，本地多出来的网易云歌曲批量上传云端；旧版把它
     * 当普通收藏歌单下载的那一份会被移除，曲目先兜底并入再删。
     * 之后点星标 / 批量移除 / 清空都会实时回写云端，下次同步不会复活。
     *
     * 收藏 / 订阅歌单云端不允许写，标记为 cloudReadOnly：本地仍可编辑，
     * 但改动不回写云端，也不参与云端排序。
     *
     * 自动同步用指纹跳过没变化的歌单与红心；手动同步强制全量刷新。
     * 云端删掉的歌单本地仍保留：接口偶发漏返回时直接删用户本地歌单代价太大。
     */
    fun syncNmPlaylists(quiet: Boolean = false) {
        if (!nmOn) { if (!quiet) toast("请先开启「接入网易云曲库」"); return }
        if (prefs.nmCookie.isBlank()) { if (!quiet) toast("请先登录网易云"); return }
        if (nmSyncing) return
        nmSyncing = true
        Netease.cookie = prefs.nmCookie
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var uid = prefs.nmUid
                if (uid <= 0L) {
                    uid = runCatching { Netease.uid() }.getOrDefault(0L)
                    if (uid > 0L) prefs.nmUid = uid
                }
                if (uid <= 0L) {
                    withContext(Dispatchers.Main) {
                        nmSyncing = false
                        if (!quiet) toast("取不到网易云 uid，请重新登录后再同步")
                    }
                    return@launch
                }
                val cloudAll = runCatching { Netease.userPlaylists(uid) }.getOrDefault(emptyList())
                if (cloudAll.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        nmSyncing = false
                        if (!quiet) toast("没有取到云端歌单，请检查登录态")
                    }
                    return@launch
                }
                // 26.11.73：云端已不再返回的 id 说明删除 / 取消收藏已生效，
                // 黑名单清掉；仍返回的继续屏蔽，避免「删了又回来」。
                val cloudIds = cloudAll.map { it.nmId.toString() }.toSet()
                prefs.nmDeletedPids = prefs.nmDeletedPids.intersect(cloudIds)
                val deleted = prefs.nmDeletedPids
                // 26.11.71：「我喜欢的音乐」不与本地歌单并列，单独合并进「我的喜欢」
                val likeGroup = cloudAll.firstOrNull { it.cloudLike }
                val mine = cloudAll.filterNot {
                    it.cloudLike || it.nmId.toString() in deleted
                }
                if (likeGroup != null) prefs.nmLikePid = likeGroup.nmId
                val local = withContext(Dispatchers.Main) { playlists.toMutableList() }
                // 清掉 26.11.70 把它当普通收藏歌单下载下来的那一份，避免重复显示。
                // 删之前先把曲目留下来：万一这次云端红心拉取失败，这些歌不能凭空消失。
                val likePid = likeGroup?.nmId ?: prefs.nmLikePid
                val legacyLiked = local.filter {
                    (likePid > 0L && it.nmId == likePid) ||
                        (it.cloudReadOnly &&
                            (it.cloudBase == "我喜欢的音乐" ||
                                it.name.removeSuffix(CLOUD_TAG) == "我喜欢的音乐"))
                }
                val legacySsids = legacyLiked.flatMap { it.songs }.toSet()
                if (legacyLiked.isNotEmpty()) {
                    val legacyIds = legacyLiked.map { it.id }.toSet()
                    local.removeAll { it.id in legacyIds }
                }
                val existing = local.filter { it.nmId > 0L }.associateBy { it.nmId }
                // 每批 4 张并发：既不会一次打出几十个请求挤占播放链路，
                // 也不会像以前那样一张一张串行等，几十张歌单同步要几分钟。
                val fetched = ArrayList<Pair<Group, List<Song>?>>()
                mine.chunked(4).forEach { batch ->
                    val part = coroutineScope {
                        batch.map { g -> async<Pair<Group, List<Song>?>> {
                            val old = existing[g.nmId]
                            // null = 云端内容没变，沿用本地曲目（仅自动同步走这条）
                            val unchanged = old != null && quiet &&
                                old.cloudStamp == g.cloudStamp && old.songs.isNotEmpty()
                            if (unchanged) g to null
                            else g to runCatching { Netease.playlistSongs(g.nmId) }
                                .getOrDefault(emptyList<Song>())
                        } }.awaitAll()
                    }
                    fetched.addAll(part)
                }
                val all = ArrayList<Song>()
                var added = 0
                var updated = 0
                var skipped = 0

                // 26.11.71：云端「我喜欢的音乐」与本地「我的喜欢」合并。
                // ① 云端曲目并入本地红心集合；② 本地多出来的网易云歌曲批量上传云端。
                // 指纹相同时自动同步跳过，手动同步强制重跑。
                val favBefore = withContext(Dispatchers.Main) { favorites }
                val likeStamp = likeGroup?.cloudStamp.orEmpty()
                val likeKey = "$likeStamp|${favBefore.sorted().joinToString(",").hashCode()}|${prefs.pendingUnlike.sorted().joinToString(",").hashCode()}"
                // 26.11.74：本地删过、云端还没删成功的红心先重放一次。
                // 成功删掉的 + 仍失败的都要从合并结果里排除：云端快照可能
                // 还带着这些歌，不排除就会并回本地、又被当成新歌传上去。
                val unlikeDone = if (likeGroup != null) flushPendingUnlike(likeGroup.nmId) else emptySet()
                val unlikeBlocked = prefs.pendingUnlike + unlikeDone
                var likedFromCloud = 0
                var likedToCloud = 0
                var likedLocalOnly = 0
                var mergedFav: Set<String> = favBefore
                var favChanged = false
                if (likeGroup != null && (!quiet || prefs.nmLikeStamp != likeKey)) {
                    val likeSongs = runCatching { Netease.playlistSongs(likeGroup.nmId) }
                        .getOrDefault(emptyList<Song>())
                    all.addAll(likeSongs)
                    // trackCount 说云端有歌、却一首都没拉回来 = 这次请求失败。
                    // 这时用上一版留下的曲目做差集基准，并跳过上传，避免把整批
                    // 本地红心当成「云端没有」重复加一遍。trackCount=0 则是云端
                    // 真的空，本地红心照样要传上去。
                    val cloudFailed = likeGroup.trackCount > 0 && likeSongs.isEmpty()
                    val cloudSsids = if (cloudFailed) legacySsids
                    else likeSongs.map { it.ssid }.toSet()
                    mergedFav = (favBefore + cloudSsids + legacySsids) - unlikeBlocked
                    favChanged = mergedFav != favBefore
                    likedFromCloud = (mergedFav.size - favBefore.size).coerceAtLeast(0)
                    likedLocalOnly = mergedFav.count { neteaseIdOf(it) == null }
                    val up = (mergedFav - cloudSsids)
                        .mapNotNull { neteaseIdOf(it) }
                        .filter { it > 0L }
                    if (!cloudFailed && up.isNotEmpty()) {
                        likedToCloud = Netease.likeAdd(likeGroup.nmId, up)
                    } else if (cloudFailed) {
                        NmLog.note("云端红心拉取失败，跳过本次上传 pid=${likeGroup.nmId}")
                    }
                    prefs.nmLikeStamp =
                        "$likeStamp|${mergedFav.sorted().joinToString(",").hashCode()}|${prefs.pendingUnlike.sorted().joinToString(",").hashCode()}"
                } else if (likeGroup != null) {
                    NmLog.note("红心歌单指纹未变，跳过合并 pid=${likeGroup.nmId}")
                }

                for ((g, songs) in fetched) {
                    if (songs != null) all.addAll(songs)
                    val ids = songs?.map { it.ssid }
                    val at = local.indexOfFirst { it.nmId == g.nmId && g.nmId > 0L }
                    if (at >= 0) {
                        val old = local[at]
                        val name = if (old.cloudBase != null && old.name.endsWith(CLOUD_TAG))
                            g.title + CLOUD_TAG else g.title
                        local[at] = old.copy(
                            name = name, cloudBase = g.title,
                            songs = ids ?: old.songs,
                            cover = g.cover ?: old.cover,
                            cloudReadOnly = g.readOnly,
                            cloudStamp = g.cloudStamp,
                        )
                        if (songs == null) skipped++ else updated++
                    } else {
                        val clash = local.any {
                            it.nmId != g.nmId &&
                                (it.name == g.title || it.cloudBase == g.title)
                        }
                        val name = if (clash) g.title + CLOUD_TAG else g.title
                        local.add(
                            Playlist(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                songs = ids ?: emptyList(),
                                cover = g.cover,
                                nmId = g.nmId,
                                cloudBase = g.title,
                                cloudReadOnly = g.readOnly,
                                cloudStamp = g.cloudStamp,
                            )
                        )
                        added++
                    }
                }
                // 本地上传：本地自建（nmId=0）的歌单在云端建一份并回填 id
                var uploaded = 0
                for (i in local.indices) {
                    val pl = local[i]
                    if (pl.nmId > 0L || pl.cloudReadOnly) continue
                    val pid = runCatching { Netease.playlistCreate(cloudName(pl)) }
                        .getOrDefault(0L)
                    if (pid <= 0L) continue
                    val nids = pl.songs.mapNotNull { neteaseIdOf(it) }.filter { it > 0L }
                    if (nids.isNotEmpty()) runCatching { Netease.playlistAdd(pid, nids) }
                    local[i] = pl.copy(nmId = pid, cloudBase = cloudName(pl))
                    uploaded++
                }
                val readOnlyCount = mine.count { it.readOnly }
                withContext(Dispatchers.Main) {
                    rememberSongs(all)
                    if (favChanged) {
                        favorites = mergedFav
                        prefs.favorites = mergedFav
                    }
                    savePl(local)
                    nmSyncing = false
                    if (!quiet) toast(
                        "同步完成：云端 ${mine.size} 张（收藏 $readOnlyCount），" +
                            "新增 $added / 更新 $updated / 未变 $skipped，上传 $uploaded 个；" +
                            "红心合并 +$likedFromCloud / 上传 $likedToCloud" +
                            (if (likedLocalOnly > 0) " / 本地专属 $likedLocalOnly 首不上传" else "")
                    )
                }
                // 云端排序接口只接受本人创建的歌单，收藏歌单传过去会整批失败
                val orderIds = local.filter { !it.cloudReadOnly }
                    .mapNotNull { it.nmId.takeIf { v -> v > 0L } }
                if (orderIds.size >= 2) runCatching { Netease.playlistOrder(orderIds) }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    nmSyncing = false
                    if (!quiet) toast("同步失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 歌单顺序变更后回写云端（ids 为全部云端歌单的目标顺序） */
    private fun syncPlaylistOrder() {
        if (!canSyncCloud()) return
        // 26.11.70：收藏 / 订阅歌单不能参与云端排序，传过去会整批失败
        val ids = playlists.filter { !it.cloudReadOnly }
            .mapNotNull { it.nmId.takeIf { v -> v > 0L } }
        if (ids.size < 2) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Netease.playlistOrder(ids) }
        }
    }

    /** 歌单整体上移 / 下移，并同步云端顺序 */
    fun movePlaylist(id: String, from: Int, to: Int) {
        val l = playlists.toMutableList()
        if (from !in l.indices || to !in l.indices || from == to) return
        val x = l.removeAt(from)
        l.add(to, x)
        savePl(l)
        syncPlaylistOrder()
    }

    /** 26.11.87：原始封面字节备份，放在应用私有目录，卸载才清掉 */
    private fun originalCoverFile(id: String) =
        java.io.File(app.filesDir, "pcolorig_${id.hashCode()}.bin")

    private fun downloadImageBytes(url: String): ByteArray? = runCatching {
        val u = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
        val req = okhttp3.Request.Builder().url(u)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://music.163.com/")
            .build()
        Api.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null
            else r.body?.bytes()?.takeIf { it.size > 64 && it.size < 8 * 1024 * 1024 }
        }
    }.getOrNull()

    /** 更换歌单封面：先备份原图（含字节），本地立即显示，再 multipart 上传云端 */
    fun setPlaylistCover(id: String, localUri: String, bytes: ByteArray?) {
        val p = playlists.firstOrNull { it.id == id } ?: return
        // 只在第一次更换前记原始封面；之后连续换图不会把"上一张自定义图"当成原图
        if (prefs.playlistOriginalCovers[id].isNullOrBlank() && !p.cover.isNullOrBlank()) {
            val orig = p.cover!!
            prefs.playlistOriginalCovers = prefs.playlistOriginalCovers + (id to orig)
            if (orig.startsWith("http")) {
                viewModelScope.launch(Dispatchers.IO) {
                    downloadImageBytes(orig)?.let {
                        runCatching { originalCoverFile(id).writeBytes(it) }
                    }
                }
            }
        }
        savePl(playlists.map { if (it.id == id) it.copy(cover = localUri) else it })
        if (bytes == null || p.nmId <= 0L || p.cloudReadOnly || !canSyncCloud()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { Netease.playlistCover(p.nmId, bytes) }.getOrDefault(false)
            withContext(Dispatchers.Main) { if (!ok) toast("云端封面上传未成功（本地已显示）") }
        }
    }

    fun hasPlaylistCoverBackup(id: String): Boolean =
        prefs.playlistOriginalCovers[id] != null

    /** 复原封面：本地改回原 URL，并把备份的原图字节重新上传，云端也回到原样 */
    fun restorePlaylistCover(id: String) {
        val p = playlists.firstOrNull { it.id == id } ?: return
        val orig = prefs.playlistOriginalCovers[id]
        if (orig == null) { toast("没有可复原的原始封面"); return }
        savePl(playlists.map { if (it.id == id) it.copy(cover = orig) else it })
        val backup = originalCoverFile(id)
        if (backup.exists() && p.nmId > 0L && !p.cloudReadOnly && canSyncCloud()) {
            viewModelScope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    Netease.playlistCover(p.nmId, backup.readBytes())
                }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    toast(if (ok) "封面已复原（含云端）" else "本地已复原，云端上传未成功")
                }
            }
        } else {
            toast("本地封面已复原（云端不可写或没有原图备份）")
        }
    }

    fun addToPlaylist(id: String, ssids: List<String>) {
        val p = playlists.firstOrNull { it.id == id } ?: return
        keepForPlaylist(songsByIds(ssids))
        savePl(playlists.map { q ->
            if (q.id == id) q.copy(songs = (q.songs + ssids).distinct()) else q
        })
        syncNmAdd(p, ssids)
        toast("已加入歌单")
    }

    /**
     * 一次导出多个歌单。
     * 每个歌单生成一行还原字符，多行拼在一起；导入时按行拆分逐个还原。
     */
    fun exportPlaylists(ids: List<String>): String {
        val lines = ids.mapNotNull { id -> exportPlaylist(id) }
        if (lines.isEmpty()) toast("选中的歌单都没有可导出的歌曲")
        return lines.joinToString("\n")
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
        // 支持一次导入多个：每个歌单一行还原字符，逐行还原
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size > 1) {
            var ok = 0
            lines.forEach { if (importOne(it)) ok++ }
            if (ok > 0) toast("已导入 $ok 个歌单")
            else toast("还原字符无法识别，请检查是否完整")
            return ok > 0
        }
        return importOne(text)
    }

    /** 还原单个歌单（一行还原字符） */
    private fun importOne(text: String): Boolean {
        val snap = PlaylistShare.decode(text)
        if (snap == null) {
            return false
        }
        if (snap.songs.isEmpty()) {
            toast("这个歌单里没有歌曲")
            return false
        }
        // 还原成完整 Song：新版还原字符带网易云 id，老版没有的字段
        // 用本地已有快照补齐（点封面/歌手就不会退化成按名字模糊搜）。
        // force = true：导入的信息才是这个歌单该显示的，旧快照同 ssid 的
        // 记录可能是另一张专辑 / 另一个歌手，不覆盖就会串歌。
        rememberSongs(snap.songs.map { item ->
            val old = snapshots[item.ssid]
            Song(
                ssid = item.ssid,
                title = item.title,
                artist = item.artist,
                album = item.album,
                coverUrl = item.cover,
                source = item.source,
                nmId = item.nmId.takeIf { v -> v > 0 } ?: old?.nmId ?: 0,
                nmAlbumId = item.nmAlbumId.takeIf { v -> v > 0 } ?: old?.nmAlbumId ?: 0,
                nmArtistId = item.nmArtistId.takeIf { v -> v > 0 } ?: old?.nmArtistId ?: 0,
                mvId = item.mvId.takeIf { v -> v > 0 } ?: old?.mvId ?: 0,
            )
        }, force = true)
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

    fun renamePlaylist(id: String, name: String) {
        val p = playlists.firstOrNull { it.id == id } ?: return
        val n = name.trim()
        // 带「云」标签的展示名还原成云端原名，标签不传回云端
        val cloud = if (p.cloudBase != null && n.endsWith(CLOUD_TAG))
            n.removeSuffix(CLOUD_TAG) else n
        savePl(playlists.map {
            if (it.id == id)
                it.copy(name = n, cloudBase = if (it.cloudBase != null) cloud else null)
            else it
        })
        if (p.nmId > 0L && !p.cloudReadOnly && canSyncCloud()) {
            viewModelScope.launch(Dispatchers.IO) {
                val ok = runCatching { Netease.playlistUpdate(p.nmId, cloud) }.getOrDefault(false)
                withContext(Dispatchers.Main) { if (!ok) toast("云端歌单改名未成功（本地已改）") }
            }
        }
    }

    /**
     * 26.11.73：删除歌单，本地与云端一起删。
     *
     * - 收藏 / 订阅歌单：走 /playlist/subscribe?t=0 取消收藏（以前被
     *   cloudReadOnly 直接跳过云端，只删了本地，同步又给拉回来）
     * - 我创建的歌单：走 /playlist/delete 真正删掉云端那份
     * - 纯本地歌单：没有云端 id，只删本地
     *
     * nmId 记入黑名单：云端接口偶发失败时下次同步不会把它重新下载回来。
     */
    fun deletePlaylist(id: String) {
        val p = playlists.firstOrNull { it.id == id } ?: return
        savePl(playlists.filter { it.id != id })
        if (p.nmId > 0L) {
            prefs.nmDeletedPids = prefs.nmDeletedPids + p.nmId.toString()
        }
        if (p.nmId > 0L && canSyncCloud()) {
            // 「我喜欢的音乐」是特殊歌单，已合并进「我的喜欢」，不允许删除 / 取消订阅
            val isLikedSpecial = p.cloudBase == "我喜欢的音乐" ||
                p.name.removeSuffix(CLOUD_TAG) == "我喜欢的音乐"
            if (!isLikedSpecial) {
                viewModelScope.launch(Dispatchers.IO) {
                    Netease.cookie = prefs.nmCookie
                    val ok = if (p.cloudReadOnly) {
                        runCatching { Netease.playlistUnsubscribe(p.nmId) }.getOrDefault(false)
                    } else {
                        runCatching { Netease.playlistDelete(p.nmId) }.getOrDefault(false)
                    }
                    withContext(Dispatchers.Main) {
                        if (!ok) toast(
                            if (p.cloudReadOnly) "云端取消收藏未成功（本地已删）"
                            else "云端歌单删除未成功（本地已删）"
                        )
                    }
                }
            }
        }
        syncPlaylistOrder()
    }

    fun movePl(id: String, from: Int, to: Int) {
        val p = playlists.firstOrNull { it.id == id } ?: return
        val l = p.songs.toMutableList()
        if (from !in l.indices || to !in l.indices || from == to) return
        val x = l.removeAt(from)
        l.add(to, x)
        savePl(playlists.map { if (it.id == id) it.copy(songs = l) else it })
        // 26.11.66：/song/order/update 要的是整张歌单的目标顺序，不是被移动的那一首
        if (p.nmId > 0L && !p.cloudReadOnly && canSyncCloud()) {
            val ids = l.mapNotNull { neteaseIdOf(it) }.filter { it > 0L }
            if (ids.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) {
                runCatching { Netease.songOrder(p.nmId, ids) }
            }
        }
    }

    fun removeFromPl(id: String, ssid: String) {
        val p = playlists.firstOrNull { it.id == id } ?: return
        savePl(playlists.map { q -> if (q.id == id) q.copy(songs = q.songs - ssid) else q })
        syncNmRemove(p, listOf(ssid))
    }

    /**
     * 本地歌单删歌后，把删掉的那几首同步到网易云歌单。
     *
     * 只处理网易云来源的歌：本站曲库的歌在网易云那边不存在，
     * 传过去要么被忽略、要么报错，所以直接跳过。
     */
    private fun syncNmRemove(p: Playlist, ssids: List<String>) {
        if (p.nmId <= 0L || p.cloudReadOnly || !canSyncCloud()) return
        val ids = ssids.mapNotNull { neteaseIdOf(it) }.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { Netease.playlistDel(p.nmId, ids) }.getOrDefault(false)
            withContext(Dispatchers.Main) { if (!ok) toast("网易云歌单未同步删除（本地已删）") }
        }
    }

    /**
     * 本地歌单加歌后，把新增的那几首同步到网易云歌单。
     * 同样只同步网易云来源的歌（见 [syncNmRemove]）。
     */
    private fun syncNmAdd(p: Playlist, ssids: List<String>) {
        if (p.nmId <= 0L || p.cloudReadOnly || !canSyncCloud()) return
        val ids = ssids.mapNotNull { neteaseIdOf(it) }.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { Netease.playlistAdd(p.nmId, ids) }.getOrDefault(false)
            withContext(Dispatchers.Main) { if (!ok) toast("网易云歌单未同步添加（本地已加）") }
        }
    }

    // ================= MV =================

    /** 正在取 MV 地址（功能栏按钮据此转圈） */
    var mvLoading by mutableStateOf(false); private set

    /**
     * 播放当前歌曲的 MV。
     *
     * 先暂停音频：视频自带声音，两个一起放会混在一起。
     * 取到地址后压入 [Screen.Video]，由 VideoScreen 播放。
     */
    fun playMv() {
        val s = currentSong
        if (s == null) { toast("暂无歌曲"); return }
        val id = s.mvId
        if (id <= 0L) { toast("这首歌没有 MV"); return }
        if (mvLoading) return
        mvLoading = true
        // 视频自带声音，先把音频停掉，否则两个声音会混在一起
        runCatching { controller?.pause() }
        viewModelScope.launch(Dispatchers.IO) {
            val url = runCatching { Netease.mvUrl(id) }.getOrNull()
            withContext(Dispatchers.Main) {
                mvLoading = false
                if (url.isNullOrBlank()) toast("没有取到 MV 地址")
                else stack.add(Screen.Video(s.title, url))
            }
        }
    }

    /** 26.11.87：首页推荐 / 最新 MV 卡片点击：取直链后进整屏视频页 */
    fun openHomeMv(g: Group) {
        val id = g.nmId
        if (id <= 0L) { toast("这个 MV 没有可用 id"); return }
        if (mvLoading) return
        mvLoading = true
        runCatching { controller?.pause() }
        viewModelScope.launch(Dispatchers.IO) {
            val url = runCatching { Netease.mvUrl(id) }.getOrNull()
            withContext(Dispatchers.Main) {
                mvLoading = false
                if (url.isNullOrBlank()) toast("没有取到 MV 地址")
                else stack.add(Screen.Video(g.title, url))
            }
        }
    }

    // ---- MV 下载 ----
    /** MV 是否正在下载 */
    var mvDownloading by mutableStateOf(false); private set
    /** MV 下载进度 0~1，负数表示还不知道总大小 */
    var mvDownloadProgress by mutableFloatStateOf(-1f); private set

    /**
     * 下载当前 MV 的视频文件到 Download/GoMusic/MV。
     *
     * MV 是直链视频，不走歌曲那套「音频 + 封面 + 歌词 + 信息文件」的流程，
     * 也不写下载记录（它不是一首歌，不能当成歌曲离线播放）。
     */
    fun downloadMv(v: Screen.Video) {
        if (mvDownloading) return
        if (offline) { toast("离线模式下无法下载，请先连接服务器"); return }
        mvDownloading = true
        mvDownloadProgress = 0f
        val noti = Noti(app)
        noti.ensureChannel()
        viewModelScope.launch(Dispatchers.IO) {
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            val r = runCatching {
                LocalLib.downloadMv(app, v.title, v.url) { done, total ->
                    val now = System.currentTimeMillis()
                    val dt = now - lastTime
                    if (dt < 400 && done < total) return@downloadMv
                    lastBytes = done
                    lastTime = now
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    viewModelScope.launch(Dispatchers.Main) {
                        mvDownloadProgress = if (total > 0) done.toFloat() / total else -1f
                        noti.progress(
                            v.title, 1, 1, pct, "",
                            if (total > 0) "${Noti.size(done)} / ${Noti.size(total)}" else Noti.size(done),
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) {
                mvDownloading = false
                mvDownloadProgress = -1f
                noti.cancel()
                r.onSuccess { toast("MV 已保存到 ${LocalLib.dirText()}${LocalLib.MV_DIR}/") }
                    .onFailure { toast("MV 下载失败") }
            }
        }
    }

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
            val done = ArrayList<Pair<Song, DownloadRecord>>()
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
                        done += s to rec
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
            // 汇总提示之后再补译文：AI 整篇翻译可能较慢，
            // 既不能挡住下一首歌的下载进度，也不该把「下载结束」拖到翻译完才显示
            if (done.isNotEmpty()) ensureTranslations(done)
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

    /**
     * 下载整个歌单。
     *
     * 音质按来源分开处理：歌单里可能混着本站歌曲和网易云歌曲，
     * 网易云的档位（exhigh / lossless …）和本站的 quality 键不是一套，
     * [downloadQuality] 会分别取，[download] 再按音质分组下载。
     * 已经下过当前音质的自动跳过。
     */
    fun downloadPlaylist(pl: Playlist) {
        val list = songsByIds(pl.songs)
        if (list.isEmpty()) { toast("歌单里没有歌曲"); return }
        val pending = list.filter { !isDownloadedWithQuality(it.ssid, downloadQuality(it)) }
        if (pending.isEmpty()) {
            toast("这个音质已全部下载过，换音质可在播放页切换后再下载")
            return
        }
        download(list)
    }

    /**
     * 整个歌单按指定音质批量下载。
     *
     * 音质不能一刀切：歌单里可能同时有本站歌曲和网易云歌曲，
     * 两套音质键不同（lossless/high/normal 与 standard/exhigh/lossless…），
     * 所以按来源分开下 —— 网易云那部分用选的档位，
     * 本站那部分只在选的是本站键时才用，否则沿用当前音质设置。
     */
    fun downloadAllWithQuality(songs: List<Song>, q: String) {
        if (songs.isEmpty()) return
        if (offline) { toast("离线模式下无法下载，请先连接服务器"); return }
        val nm = songs.filter { it.source == SRC_NETEASE }
        val local = songs.filter { it.source != SRC_NETEASE }
        val localKey = if (q in listOf("lossless", "high", "normal", "low")) q else quality
        val nmTodo = nm.filter { it.ssid !in downloading && !isDownloadedWithQuality(it.ssid, q) }
        val localTodo = local.filter { it.ssid !in downloading && !isDownloadedWithQuality(it.ssid, localKey) }
        val total = nmTodo.size + localTodo.size
        if (total == 0) { toast("这个音质已下载过"); return }
        downloading = downloading + nmTodo.map { it.ssid } + localTodo.map { it.ssid }
        if (nmTodo.isNotEmpty()) runDownload(nmTodo, q)
        if (localTodo.isNotEmpty()) runDownload(localTodo, localKey)
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

    /** 26.11.61：分享链接不再指向已停用的源站，改指向网易云歌曲页 */
    fun songLink(ssid: String): String {
        val nid = neteaseIdOf(ssid)
        return if (nid != null) "https://music.163.com/#/song?id=$nid" else ""
    }
    fun albumLink(artist: String, album: String) = ""
    fun artistLink(artist: String) = ""

    /**
     * 26.11.34：系统「打开方式 / 分享」送来的本地音频。
     * 读文件 Meta 后直接播放，并记入播放历史；全程不发网络请求。
     */
    fun playExternalAudio(intent: Intent) {
        val uris = LocalAudio.urisFrom(intent)
        if (uris.isEmpty()) return
        val songs = uris.mapNotNull { u ->
            LocalAudio.takeReadable(app, u)
            LocalAudio.songOf(app, u)
        }
        if (songs.isEmpty()) {
            toast("无法读取这个音频文件")
            return
        }
        rememberSongs(songs)
        playSongs(songs, 0)
        toast("正在播放本地音乐：${songs[0].title}")
    }

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
                // 26.11.83：官方房间心跳（每首歌开始时 + 每 15 秒一次）
                ltHeartbeatTick()
            }
        }
    }

    // ================= 专辑 / 歌手简介 =================

    /** 是否带登录凭证：ugc 简介接口仅登录时才有内容 */
    val nmLogged: Boolean get() = Netease.cookie.isNotBlank()

    var intro by mutableStateOf<IntroState?>(null); private set

    /**
     * 进入简介页时加载；同一页面不重复请求。
     *
     * 26.11.80 之前：专辑简介只在「已登录」时请求 /ugc/album/get，
     * 未登录直接显示「登录后才能查看简介」，而该端点部分部署还不存在 ——
     * 结果就是永远拿不到专辑简介。现在主链改走不需要登录的 /album，
     * ugc 退为登录后的补充，两边取更长的那段；封面 / 歌手名一并补齐。
     */
    fun loadIntro(s: Screen, force: Boolean = false) {
        val isAlbum = s is Screen.AlbumIntro
        val id: Long
        val title: String
        val cover: String?
        val artist: String
        when (s) {
            is Screen.AlbumIntro -> {
                id = s.id; title = s.title; cover = s.cover; artist = s.artist
            }
            is Screen.ArtistIntro -> {
                id = s.id; title = s.name; cover = s.cover; artist = ""
            }
            else -> return
        }
        val kind = if (isAlbum) "album" else "artist"
        val cur = intro?.takeIf { it.kind == kind && it.id == id }
        if (!force && cur != null) {
            // 已拿到正文、已明确失败（等用户点重试）、或正在加载，都不重复发请求
            if (cur.desc.isNotBlank() || cur.descFailed || cur.descLoading) return
        }
        intro = IntroState(
            kind = kind, id = id, title = title, cover = cover, artist = artist,
            desc = cur?.desc.orEmpty(),
            descLoading = id > 0,
            detailLoading = !isAlbum && id > 0,
        )
        // 本地条目暂时没有网易云 id：页面先开出来，等后台按名字补 id 再重取
        if (id <= 0) return
        viewModelScope.launch {
            val (base, ugc) = withContext(Dispatchers.IO) {
                coroutineScope {
                    val a = async {
                        runCatching {
                            if (isAlbum) Netease.albumIntro(id)
                            else Netease.artistDetail(id)?.let {
                                Netease.UgcInfo(
                                    cover = it.cover, title = it.title,
                                    artist = it.artist, artistId = it.nmId, ok = true,
                                )
                            } ?: Netease.UgcInfo()
                        }.getOrDefault(Netease.UgcInfo())
                    }
                    val b = async {
                        if (nmLogged) runCatching {
                            if (isAlbum) Netease.ugcAlbum(id) else Netease.ugcArtist(id)
                        }.getOrDefault(Netease.UgcInfo()) else Netease.UgcInfo()
                    }
                    a.await() to b.await()
                }
            }
            intro?.takeIf { it.kind == kind && it.id == id }?.let { c ->
                val desc = listOf(base.desc, ugc.desc, c.desc).maxByOrNull { it.length }.orEmpty()
                val ok = base.ok || ugc.ok
                intro = c.copy(
                    desc = desc,
                    descLoading = false,
                    descOk = ok || desc.isNotBlank(),
                    descFailed = !ok && desc.isBlank(),
                    cover = base.cover ?: ugc.cover ?: c.cover,
                    title = ugc.title.takeIf { it.isNotBlank() }
                        ?: base.title.takeIf { it.isNotBlank() } ?: c.title,
                    artist = ugc.artist.takeIf { it.isNotBlank() }
                        ?: base.artist.takeIf { it.isNotBlank() } ?: c.artist,
                    artistId = ugc.artistId.takeIf { it > 0 }
                        ?: base.artistId.takeIf { it > 0 } ?: c.artistId,
                )
            }
        }
        // 歌手详细信息无论是否登录都取，briefDesc 同时作为简介正文
        if (!isAlbum) {
            viewModelScope.launch {
                val d = withContext(Dispatchers.IO) {
                    runCatching { Netease.artistDesc(id) }.getOrDefault(Netease.ArtistDescInfo())
                }
                intro?.takeIf { it.kind == kind && it.id == id }?.let { c ->
                    intro = c.copy(
                        brief = d.brief, sections = d.sections, topics = d.topics,
                        detailLoading = false, detailOk = d.ok,
                        desc = d.brief.takeIf { it.length > c.desc.length } ?: c.desc,
                        descLoading = false,
                        descOk = d.ok || c.descOk,
                        descFailed = !d.ok && !c.descOk && c.desc.isBlank(),
                    )
                }
            }
        }
    }

    /** 简介卡片上「获取失败，点击重试」 */
    fun retryIntro(s: Screen) {
        loadIntro(s, force = true)
        when (s) {
            is Screen.AlbumIntro -> if (s.id <= 0) resolveIntroId(true, s.title, s.artist, s.cover)
            is Screen.ArtistIntro -> if (s.id <= 0) resolveIntroId(false, s.name, "", s.cover)
            else -> Unit
        }
    }

    /** 本地分组没有在线 id：按名字搜一次，补到当前简介页再重取简介 */
    private fun resolveIntroId(isAlbum: Boolean, title: String, artist: String, cover: String?) {
        viewModelScope.launch {
            val g = withContext(Dispatchers.IO) {
                runCatching {
                    val key = if (artist.isBlank()) title else "$artist $title"
                    val hit = { h: Group ->
                        h.title.isNotBlank() && (
                            h.title.equals(title, ignoreCase = true) ||
                                h.title.contains(title, ignoreCase = true) ||
                                title.contains(h.title, ignoreCase = true))
                    }
                    if (isAlbum) Netease.searchAlbums(key, 10).firstOrNull(hit)
                    else Netease.searchArtists(title, 10).firstOrNull(hit)
                }.getOrNull()
            }
            val idx = stack.lastIndex
            val top = stack.getOrNull(idx)
            if (g != null && top != null) {
                stack[idx] = when {
                    isAlbum && top is Screen.AlbumIntro && top.id <= 0 ->
                        top.copy(id = g.nmId, cover = top.cover ?: g.cover)
                    !isAlbum && top is Screen.ArtistIntro && top.id <= 0 ->
                        top.copy(id = g.nmId, cover = top.cover ?: g.cover)
                    else -> return@launch
                }
                loadIntro(stack.last(), force = true)
            } else if (g == null) {
                intro = intro?.copy(descFailed = true, descLoading = false)
                toast("未找到在线信息，点简介处可重试")
            }
        }
    }

    fun openAlbumIntro(id: Long, title: String, cover: String?, artist: String) {
        // 26.11.80：不再因为 id<=0 直接 toast 不进页面，本地专辑也要能看到信息
        stack.add(Screen.AlbumIntro(id, title, cover, artist))
        loadIntro(stack.last())
        if (id <= 0) resolveIntroId(true, title, artist, cover)
    }

    fun openArtistIntro(id: Long, name: String, cover: String?) {
        stack.add(Screen.ArtistIntro(id, name, cover))
        loadIntro(stack.last())
        if (id <= 0) resolveIntroId(false, name, "", cover)
    }

    /** 本地按名字分组的专辑页：先用歌里带的网易云专辑 id，没有再严格按名字找 */
    fun openLocalAlbumIntro(artist: String, album: String, cover: String?, songs: List<Song>) {
        val known = songs.firstOrNull { it.nmAlbumId > 0 }?.nmAlbumId ?: 0L
        if (known > 0) { openAlbumIntro(known, album, cover, artist); return }
        viewModelScope.launch {
            val g = withContext(Dispatchers.IO) {
                runCatching {
                    Netease.searchAlbums("$artist $album", 10).firstOrNull { h ->
                        h.title.isNotBlank() && (
                            h.title.equals(album, ignoreCase = true) ||
                                h.title.contains(album, ignoreCase = true) ||
                                album.contains(h.title, ignoreCase = true))
                    }
                }.getOrNull()
            }
            // 26.11.80：找不到在线 id 也要把简介页开出来，由 resolveIntroId 继续补，
            // 不能像以前那样只弹一句 toast、点了封面像没反应
            if (g != null) openAlbumIntro(g.nmId, album, cover ?: g.cover, artist)
            else openAlbumIntro(0, album, cover, artist)
        }
    }

    /** 本地按名字分组的歌手页：同上 */
    fun openLocalArtistIntro(name: String, cover: String?, songs: List<Song>) {
        val known = songs.firstOrNull { it.nmArtistId > 0 }?.nmArtistId ?: 0L
        if (known > 0) { openArtistIntro(known, name, cover); return }
        viewModelScope.launch {
            val g = withContext(Dispatchers.IO) {
                runCatching {
                    Netease.searchArtists(name, 10).firstOrNull { h ->
                        h.title.isNotBlank() && (
                            h.title.equals(name, ignoreCase = true) ||
                                h.title.contains(name, ignoreCase = true) ||
                                name.contains(h.title, ignoreCase = true))
                    }
                }.getOrNull()
            }
            if (g != null) openArtistIntro(g.nmId, name, cover ?: g.cover)
            else openArtistIntro(0, name, cover)
        }
    }

    /** 专辑简介页点歌手名：优先用接口给的歌手 id，否则按名字严格找 */
    fun openArtistByName(name: String) {
        if (name.isBlank()) return
        val fixed = intro?.artistId ?: 0L
        if (fixed > 0) {
            openNmGroup(
                Group(name, "网易云歌手", null, emptyList(), artist = name,
                    source = SRC_NETEASE, nmId = fixed, nmKind = "artist")
            )
            return
        }
        viewModelScope.launch {
            val g = withContext(Dispatchers.IO) {
                runCatching {
                    Netease.searchArtists(name, 10).firstOrNull { h ->
                        h.title.isNotBlank() && (
                            h.title.equals(name, ignoreCase = true) ||
                                h.title.contains(name, ignoreCase = true) ||
                                name.contains(h.title, ignoreCase = true))
                    }
                }.getOrNull()
            }
            if (g != null) openNmGroup(g) else stack.add(Screen.Artist(name))
        }
    }

    /** 长按复制整段简介 / 详情 */
    fun copyText(text: String) {
        if (text.isBlank()) return
        runCatching {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("GoMusic 简介", text))
            toast("已复制")
        }.onFailure { toast("复制失败：${it.message}") }
    }

    /**
     * 26.11.80：长按 / 点按钮下载大图，**不依赖界面上那张图有没有加载出来**。
     * 之前只把 st.cover 原样交给 Api.fetch：URL 为空、http 明文、缺 UA 被 CDN 拒、
     * 或前端图还在加载时，下载就跟着一起失败，还只提示一句「图片下载失败」。
     * 现在多候选地址（https 化 / 原图 / 高清档）+ 带请求头逐个重试；
     * 连 URL 都没有时，按当前页面的在线 id 现拉一次详情补封面再下。
     */
    fun saveIntroImage(url: String?, name: String) {
        val st = intro
        val list = buildList {
            url?.takeIf { it.isNotBlank() }?.let { add(it) }
            st?.cover?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        viewModelScope.launch {
            val candidates = if (list.isNotEmpty()) list else withContext(Dispatchers.IO) {
                runCatching {
                    when (st?.kind) {
                        "album" -> Netease.albumIntro(st.id).cover
                        "artist" -> Netease.artistDetail(st.id)?.cover
                        else -> null
                    }
                }.getOrNull()?.let { listOf(it) } ?: emptyList()
            }
            if (candidates.isEmpty()) {
                toast("没有可下载的图片，联网打开一次本页后再试")
                return@launch
            }
            val r = withContext(Dispatchers.IO) {
                runCatching { LocalLib.saveImageSmart(app, name, candidates) }
            }
            toast(r.fold(
                onSuccess = { "图片已保存到 Download/GoMusic/图片" },
                onFailure = { "图片下载失败：${it.message}" },
            ))
        }
    }

    // ================= 26.11.82：社交（私信） / 播客 / 一起听 =================

    /** 应用内私聊分享的内容：歌曲 / 专辑 / 歌单 / 纯文本 */
    data class ShareItem(
        val kind: String,
        val id: Long = 0,
        val name: String = "",
        val cover: String? = null,
        val text: String = "",
    )

    /** /pl/count：私信未读 to 通知未读 */
    var socialCounts by mutableStateOf(0 to 0); private set
    /** 26.11.86：社交页当前子页（0 私信 / 1 找朋友 / 2 一起听），由 VM 持有以便链接跳转 */
    var socialTab by mutableIntStateOf(0); private set
    var conversations by mutableStateOf<List<NmSocial.Conversation>>(emptyList()); private set
    var socialBusy by mutableStateOf(false); private set
    var socialError by mutableStateOf(""); private set
    var chatUid by mutableLongStateOf(0L); private set
    var chatName by mutableStateOf(""); private set
    var chatAvatar by mutableStateOf<String?>(null); private set
    var chatMessages by mutableStateOf<List<NmSocial.ChatMessage>>(emptyList()); private set
    var chatBusy by mutableStateOf(false); private set
    var chatBefore by mutableLongStateOf(0L); private set
    var friendResults by mutableStateOf<List<NmSocial.UserBrief>>(emptyList()); private set
    var friendBusy by mutableStateOf(false); private set
    /** 全局待分享：播放器 / 歌曲菜单 / 专辑 / 歌单点「私聊分享」后弹出选人 */
    var pendingShare by mutableStateOf<ShareItem?>(null); private set

    // ---------- 26.11.83：一起听（网易云官方房间） ----------
    var ltAccount by mutableStateOf(ListenAccount()); private set
    var ltRoomId by mutableStateOf<String?>(null); private set
    var ltInviterId by mutableLongStateOf(0L); private set
    var ltUsers by mutableStateOf<List<RoomUser>>(emptyList()); private set
    var ltMessage by mutableStateOf("请点击获取登录状态"); private set
    var ltSongId by mutableLongStateOf(0L); private set
    var ltPlayStatus by mutableStateOf("PAUSE"); private set
    var ltShareLink by mutableStateOf(""); private set
    var ltPlaylistName by mutableStateOf("未获取"); private set
    var ltTracks by mutableStateOf<List<ListenTrack>>(emptyList()); private set
    var ltBusy by mutableStateOf(false); private set
    private var ltClientSeq = 1
    private var ltLastHeartbeat = 0L
    private var ltAutoLoginDone = false
    /** 26.11.86：一起听的歌单候选（搜索结果 / 我的歌单） */
    var ltPlaylistQuery by mutableStateOf(""); private set
    var ltPlaylistResults by mutableStateOf<List<PlaylistBrief>>(emptyList()); private set
    var ltMyPlaylists by mutableStateOf<List<PlaylistBrief>>(emptyList()); private set
    var ltPlaylistBusy by mutableStateOf(false); private set

    // ---------- 播客 ----------
    var podcastQuery by mutableStateOf(""); private set
    var podcastResults by mutableStateOf<List<NmSocial.Podcast>>(emptyList()); private set
    var recentDj by mutableStateOf<List<NmSocial.Voice>>(emptyList()); private set
    var myCreated by mutableStateOf<List<NmSocial.Podcast>>(emptyList()); private set
    var podcastBusy by mutableStateOf(false); private set
    var podcastDetail by mutableStateOf<NmSocial.Podcast?>(null); private set
    var podcastVoices by mutableStateOf<List<NmSocial.Voice>>(emptyList()); private set
    var voiceBusy by mutableStateOf(false); private set
    /** 26.11.84：加载失败不再静默空白，给明确提示与重试 */
    var podcastLoaded by mutableStateOf(false); private set
    var podcastError by mutableStateOf(""); private set
    var podcastCreating by mutableStateOf(false); private set

    /** 进入播客页：最近播放 + 我创建的播客；失败可重试，不因首次空结果锁死 */
    fun loadPodcastHome(force: Boolean = false) {
        if (podcastLoaded && !force) return
        podcastBusy = true
        podcastError = ""
        viewModelScope.launch {
            val (r, m) = withContext(Dispatchers.IO) {
                NmSocial.recentDj(12) to NmSocial.myCreated()
            }
            recentDj = r
            myCreated = m
            podcastLoaded = true
            podcastBusy = false
            if (r.isEmpty() && m.isEmpty()) {
                podcastError = "接口没有返回内容：检查登录态、服务端播客模块，或点「重试」"
            }
        }
    }

    /** 26.11.84：创建播客。服务端没有该接口时明确报错，不假装建好 */
    fun createPodcast(name: String, desc: String, categoryId: Long) {
        val n = name.trim()
        if (n.isBlank()) { toast("请填写播客名称"); return }
        podcastCreating = true
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                NmSocial.createPodcast(n, desc, categoryId)
            }
            podcastCreating = false
            if (id == null) {
                toast("当前服务端没有创建播客接口，请到网易云网页端创建")
                podcastError = "创建播客失败：api-enhanced 模块里没有 voicelist_create，建议用下方网页端入口"
            } else {
                toast(if (id > 0) "播客已创建（$id）" else "创建请求已发送，正在刷新列表")
                loadPodcastHome(force = true)
            }
        }
    }

    /** 独立播客搜索，不进主搜索页 */
    fun searchPodcasts(q: String) {
        val key = q.trim()
        podcastQuery = key
        if (key.isBlank()) { podcastResults = emptyList(); return }
        podcastBusy = true
        viewModelScope.launch {
            podcastResults = withContext(Dispatchers.IO) { NmSocial.podcastSearch(key) }
            podcastBusy = false
            if (podcastResults.isEmpty()) {
                podcastError = "「$key」没有搜到播客（也可能是接口未返回数据，可重试）"
            } else podcastError = ""
        }
    }

    fun openPodcast(id: Long) {
        podcastDetail = NmSocial.Podcast(id = id)
        podcastVoices = emptyList()
        voiceBusy = true
        viewModelScope.launch {
            val (d, v) = withContext(Dispatchers.IO) {
                NmSocial.podcastDetail(id) to NmSocial.voiceList(id)
            }
            podcastDetail = d
            podcastVoices = v
            voiceBusy = false
        }
    }

    fun closePodcast() { podcastDetail = null; podcastVoices = emptyList() }

    /** 播客内声音搜索 / 状态筛选（/voicelist/list/search） */
    fun searchVoices(radioId: Long, name: String, displayStatus: String? = null) {
        voiceBusy = true
        viewModelScope.launch {
            val v = withContext(Dispatchers.IO) {
                NmSocial.voiceSearch(radioId = radioId, name = name, displayStatus = displayStatus)
            }
            podcastVoices = if (name.isBlank() && displayStatus == null) {
                // 空条件时回到完整列表，避免搜索接口字段差异导致整页空白
                withContext(Dispatchers.IO) { NmSocial.voiceList(radioId) }.ifEmpty { v }
            } else v
            voiceBusy = false
        }
    }

    /** 播放播客声音：详情接口给直链，走设备音频那条路径不取音源 */
    fun playVoice(v: NmSocial.Voice) {
        voiceBusy = true
        viewModelScope.launch {
            val d = withContext(Dispatchers.IO) { NmSocial.voiceDetail(v.id) }
            val url = d.audioUrl ?: v.audioUrl
            voiceBusy = false
            if (url.isNullOrBlank()) {
                toast("这条声音暂时没有可播放的地址")
            } else {
                val s = Song(
                    ssid = "voice${v.id}", title = v.name, artist = podcastDetail?.creator ?: "",
                    album = podcastDetail?.name ?: "", coverUrl = v.cover ?: podcastDetail?.cover,
                    source = "device", audioUri = url,
                )
                playSongs(listOf(s), 0)
                openPlayer()
            }
        }
    }

    /** 声音排序 / 删除：只影响本人创建的播客，成功后刷新列表 */
    fun sortVoice(radioId: Long, programId: Long, position: Int) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                NmSocial.voiceSort(radioId, programId, position)
            }
            toast(if (ok) "顺序已更新" else "排序失败（需要登录且为本人播客）")
            if (ok) openPodcast(radioId)
        }
    }

    fun deleteVoice(radioId: Long, ids: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { NmSocial.voiceDelete(ids) }
            toast(if (ok) "已删除" else "删除失败（需要登录且为本人播客）")
            if (ok) openPodcast(radioId)
        }
    }

    /** 上传声音（文件字节由界面通过 SAF 读入） */
    fun uploadVoice(
        radioId: Long, categoryId: Long, secondCategoryId: Long,
        description: String, songName: String, bytes: ByteArray, mime: String, fileName: String,
    ) {
        viewModelScope.launch {
            toast("正在上传声音…")
            val ok = withContext(Dispatchers.IO) {
                NmSocial.voiceUpload(
                    radioId, categoryId, secondCategoryId, description,
                    bytes, mime, fileName, songName,
                )
            }
            toast(if (ok) "上传成功" else "上传失败（检查登录 / 分类 id / 文件格式）")
            if (ok) openPodcast(radioId)
        }
    }

    // ---------- 私信 ----------

    /** 会话列表 + 私信 / 通知未读数 */
    private fun toCachedConv(c: NmSocial.Conversation) = CachedConversation(
        uid = c.uid, name = c.name, avatar = c.avatar,
        last = c.last, time = c.time, unread = c.unread,
    )

    private fun fromCachedConv(c: CachedConversation) = NmSocial.Conversation(
        uid = c.uid, name = c.name, avatar = c.avatar,
        last = c.last, time = c.time, unread = c.unread,
    )

    /** 已读基线：把「已经看过的那批未读」从会话与总计数里扣掉 */
    private fun applyReadBaseline(list: List<NmSocial.Conversation>): Pair<List<NmSocial.Conversation>, Int> {
        val stamps = prefs.chatReadStamps
        var cleared = 0
        val out = list.map { c ->
            val st = stamps[c.uid.toString()] ?: 0L
            if (st > 0L && chatTimeMs(c.time) <= st) {
                cleared += c.unread.coerceAtLeast(0)
                c.copy(unread = 0)
            } else c
        }
        return out to cleared
    }

    /**
     * 26.11.86：会话列表也走「缓存优先 + 增量新增」。
     * 进页面先铺本地缓存（0 秒可见），网络回来按 uid 合并：服务端本次
     * 返回的会话用最新数据，缓存里服务端没返回的旧会话保留在后面，
     * 不再像以前那样 conversations 非空就直接不发请求。
     */
    fun loadSocial(force: Boolean = false) {
        if (socialBusy) return
        if (conversations.isEmpty()) {
            val cached = prefs.chatListCache().map(::fromCachedConv)
            if (cached.isNotEmpty()) {
                conversations = applyReadBaseline(cached).first
            }
        }
        socialBusy = true
        socialError = ""
        viewModelScope.launch {
            val (counts, list) = withContext(Dispatchers.IO) {
                NmSocial.plCount() to NmSocial.conversations()
            }
            val byUid = HashMap<Long, NmSocial.Conversation>()
            // 缓存先入 map，服务端结果覆盖同名 uid（拿到最新 last / time / unread）
            conversations.forEach { byUid[it.uid] = it }
            list.forEach { byUid[it.uid] = it }
            // 服务端顺序优先（最新在前），其余按 time 降序补在后面
            val freshIds = list.map { it.uid }.toHashSet()
            val merged = list.mapNotNull { byUid[it.uid] } +
                byUid.values.filter { it.uid !in freshIds }.sortedByDescending { it.time }
            // 计数只按服务端本次返回的未读扣，避免缓存里的旧未读重复扣
            val (shown, cleared) = applyReadBaseline(merged)
            conversations = shown
            socialCounts = (counts.first - cleared).coerceAtLeast(0) to counts.second
            prefs.putChatListCache(shown.map(::toCachedConv))
            socialBusy = false
            if (shown.isEmpty()) socialError = "登录后才能查看私信"
        }
    }

    // ---------- 26.11.85：私聊缓存 ----------

    private fun toCached(m: NmSocial.ChatMessage) = CachedChat(
        id = m.id, time = m.time, fromSelf = m.fromSelf, text = m.text,
        kind = m.kind, resId = m.resId, resName = m.resName, resCover = m.resCover,
        raw = m.raw,
    )

    private fun fromCached(c: CachedChat) = NmSocial.ChatMessage(
        id = c.id, time = c.time, fromSelf = c.fromSelf, text = c.text,
        kind = c.kind, resId = c.resId, resName = c.resName, resCover = c.resCover,
        raw = c.raw,
    )

    /** 同一条消息在缓存 / 首页 / 翻页里都会出现，按稳定 key 去重 */
    private fun chatKey(m: NmSocial.ChatMessage): String =
        if (m.id > 0L) "i${m.id}"
        else "t${m.time}|${m.fromSelf}|${m.text}|${m.resId}|${m.resName}"

    /** 只做「合并新增」，绝不整体替换；最后统一按时间升序：最老在上、最新在下 */
    private fun mergeChat(vararg parts: List<NmSocial.ChatMessage>): List<NmSocial.ChatMessage> =
        parts.asSequence().flatten()
            .distinctBy(::chatKey)
            .toList()
            .sortedWith(compareBy<NmSocial.ChatMessage> { it.time }.thenBy { it.id })
            .takeLast(MAX_CHAT_CACHE)

    private fun saveChatCache(uid: Long, list: List<NmSocial.ChatMessage>) {
        if (uid <= 0) return
        prefs.putChatCache(uid, list.map(::toCached))
    }

    /** 服务端偶尔给秒级时间戳，统一换算成毫秒再和本地已读基线比较 */
    private fun chatTimeMs(t: Long): Long =
        if (t > 0L && t < 1_000_000_000_000L) t * 1000L else t

    /** 打开会话时清未读：本地立即归零 + 记已读基线 + 尝试让服务端标记已读 */
    private fun clearChatUnread(uid: Long) {
        val conv = conversations.firstOrNull { it.uid == uid }
        val lastSeen = maxOf(
            chatTimeMs(conv?.time ?: 0L),
            chatMessages.maxOfOrNull { it.time } ?: 0L,
            System.currentTimeMillis(),
        )
        conversations = conversations.map {
            if (it.uid == uid) it.copy(unread = 0) else it
        }
        val (msg, notice) = socialCounts
        socialCounts = (msg - (conv?.unread ?: 0)).coerceAtLeast(0) to notice
        prefs.chatReadStamps = prefs.chatReadStamps + (uid.toString() to lastSeen)
        // 有些部署有标记已读接口；没有就静默失败，本地基线仍能挡住重复提醒
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { NmSocial.markPrivateRead(uid) }
        }
    }

    /** 进入会话：先把本地缓存铺出来（0 秒可见），再拉网络只补新消息 */
    fun openChat(uid: Long, name: String, avatar: String?) {
        chatUid = uid
        chatName = name
        chatAvatar = avatar
        val cached = prefs.chatCache(uid).map(::fromCached)
        chatMessages = mergeChat(cached)
        chatBefore = chatMessages.minOfOrNull { it.time } ?: 0L
        clearChatUnread(uid)
        loadChat()
    }

    fun closeChat() {
        // 关闭前把当前顺序（最老→最新）落盘，下次进来直接是这个顺序
        saveChatCache(chatUid, chatMessages)
        chatUid = 0L
        chatMessages = emptyList()
    }

    fun loadChat(more: Boolean = false) {
        val uid = chatUid
        if (uid <= 0) return
        chatBusy = true
        viewModelScope.launch {
            val before = if (more) chatBefore else 0L
            val fetched = withContext(Dispatchers.IO) {
                NmSocial.history(uid, 30, before)
            }
            // 只合并新增：缓存里的老消息不会因为首页只返回最近 30 条而丢掉
            val merged = mergeChat(chatMessages, fetched)
            chatMessages = merged
            chatBefore = merged.minOfOrNull { it.time }?.takeIf { it > 0 } ?: chatBefore
            saveChatCache(uid, merged)
            // 每次刷新都把已读基线推到最新一条，避免未读红点复活
            if (!more) {
                val newest = merged.maxOfOrNull { it.time } ?: 0L
                if (newest > 0L) {
                    prefs.chatReadStamps = prefs.chatReadStamps +
                        (uid.toString() to maxOf(
                            prefs.chatReadStamps[uid.toString()] ?: 0L, newest,
                        ))
                }
            }
            chatBusy = false
        }
    }

    fun sendChatText(msg: String) {
        val uid = chatUid
        if (uid <= 0 || msg.isBlank()) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                NmSocial.sendText(uid.toString(), msg)
            }
            toast(if (ok) "已发送" else "发送失败，请检查登录与网络")
            if (ok) loadChat()
        }
    }

    /** 资源私信：歌曲 / 专辑 / 歌单 */
    fun sendChatResource(kind: String, id: Long, msg: String, uid: Long = chatUid) {
        if (uid <= 0 || id <= 0) { toast("缺少分享目标或资源 id"); return }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                when (kind) {
                    "song" -> NmSocial.sendSong(uid.toString(), id, msg)
                    "album" -> NmSocial.sendAlbum(uid.toString(), id, msg)
                    "playlist" -> NmSocial.sendPlaylist(uid.toString(), id, msg)
                    else -> NmSocial.sendText(uid.toString(), msg)
                }
            }
            toast(if (ok) "已分享到私信" else "分享失败，请检查登录与网络")
            if (ok && uid == chatUid) loadChat()
        }
    }

    fun chooseSocialTab(i: Int) { socialTab = i }

    /** 26.11.86：点击私信里的歌曲 / 专辑 / 歌单卡片，直接进对应内容 */
    fun openChatResource(m: NmSocial.ChatMessage) {
        val id = m.resId
        if (m.kind == "text" || id <= 0L) {
            toast("这条消息没有可用的资源 id，无法打开")
            return
        }
        val name = m.resName.ifBlank { m.text }
        when (m.kind) {
            "song" -> {
                val so = Song(
                    ssid = "$NM_PREFIX$id",
                    title = name.ifBlank { "网易云歌曲 $id" },
                    artist = "",
                    coverUrl = m.resCover,
                    source = SRC_NETEASE,
                    nmId = id,
                )
                playSongs(listOf(so), 0)
                openPlayer()
            }
            "album", "playlist" -> openNmGroup(
                Group(
                    title = name.ifBlank { if (m.kind == "album") "专辑 $id" else "歌单 $id" },
                    subtitle = "", cover = m.resCover, songs = emptyList(),
                    source = SRC_NETEASE, nmId = id, nmKind = m.kind,
                ),
            )
            else -> toast("暂不支持打开这类消息")
        }
    }

    /**
     * 26.11.86：聊天文本里的链接可点。
     * 一起听分享链接在应用内解析并直接加入房间；其余用浏览器打开。
     */
    fun openChatLink(raw: String) {
        val u = raw.trim().trimEnd('。', '，', ',', '.', '）', ')', '"', '\'', '!', '！')
        if (u.isBlank()) return
        val uri = runCatching { Uri.parse(u) }.getOrNull() ?: return
        val room = uri.getQueryParameter("roomId")
        if (!room.isNullOrBlank() &&
            (u.contains("listen-together", true) || u.contains("listentogether", true))
        ) {
            val inviter = uri.getQueryParameter("inviterId")?.toLongOrNull() ?: 0L
            val song = uri.getQueryParameter("songId")?.toLongOrNull() ?: 0L
            if (song > 0L) ltSongId = song
            // 关掉聊天页才能看到「一起听」子页；进入子页时会自动补登录态，
            // 这里不再并发调 ltAutoLogin，避免两个协程抢 ltBusy 状态
            if (chatUid > 0L) closeChat()
            chooseSocialTab(2)
            ltJoin(room, inviter)
            toast("正在加入一起听房间 $room")
            // 26.11.88：即便属于业务邀请，链接本身也在应用内 WebView 打开
            openInternalLink(u)
            return
        }
        // 26.11.88：所有网页链接统一在应用内 WebView 打开（固定 UA + 当前账号 cookie）
        openInternalLink(u)
    }

    /** 找朋友：数字 uid 直接查资料，其余按昵称搜 */
    fun searchFriends(q: String) {
        val key = q.trim()
        if (key.isBlank()) { friendResults = emptyList(); return }
        friendBusy = true
        viewModelScope.launch {
            friendResults = withContext(Dispatchers.IO) {
                val uid = key.toLongOrNull()
                if (uid != null) listOfNotNull(NmSocial.userDetail(uid))
                else NmSocial.searchUsers(key)
            }
            friendBusy = false
        }
    }

    // ---------- 应用内私聊分享 ----------

    fun beginShare(item: ShareItem) { pendingShare = item; loadSocial() }
    fun cancelShare() { pendingShare = null }

    /** 选中的会话：发对应类型的资源私信，然后进入该会话 */
    fun shareTo(uid: Long, name: String, avatar: String?, msg: String) {
        val item = pendingShare ?: return
        pendingShare = null
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                when (item.kind) {
                    "song" -> NmSocial.sendSong(uid.toString(), item.id, msg)
                    "album" -> NmSocial.sendAlbum(uid.toString(), item.id, msg)
                    "playlist" -> NmSocial.sendPlaylist(uid.toString(), item.id, msg)
                    else -> NmSocial.sendText(uid.toString(), msg.ifBlank { item.text })
                }
            }
            toast(if (ok) "已分享给 ${name}" else "分享失败，请检查登录与网络")
            if (ok) openChat(uid, name, avatar)
        }
    }

    // ---------- 一起听（官方房间协议） ----------

    /** 第一步：取登录态（/login/status 的 userId / nickname） */
    /** 进入「一起听」子页只自动取一次登录态，不要求用户手点按钮 */
    fun ltAutoLogin() {
        if (ltAutoLoginDone || ltAccount.login || ltBusy) return
        ltAutoLoginDone = true
        ltLogin(silent = true)
    }

    /** 进入「一起听」子页会自动调用；silent 时只更新消息条，不弹 toast */
    fun ltLogin(silent: Boolean = false) {
        if (!silent && ltBusy) return
        ltBusy = true
        viewModelScope.launch {
            val a = withContext(Dispatchers.IO) { ListenTogetherApi.account() }
            ltAccount = a
            ltBusy = false
            ltMessage = if (a.login) "已登录 ${a.nickname}，可以创建房间"
            else "未登录，请先在设置里登录网易云"
            if (!a.login && !silent) toast(ltMessage)
        }
    }

    private fun ltShareUrl(): String {
        val id = ltRoomId ?: return ""
        val song = ltSongId.takeIf { it > 0L }
            ?: currentSong?.nmId?.takeIf { it > 0L }
            ?: neteaseIdOf(currentSong?.ssid)
            ?: 0L
        return ListenTogetherApi.shareLink(
            song, id, ltInviterId.takeIf { it > 0L } ?: ltAccount.userId,
        )
    }

    /** 创建房间：room/create → room/check → status；可顺手把链接私信给对方 */
    fun ltCreateRoom(peerUid: Long = 0L) {
        ltBusy = true
        viewModelScope.launch {
            // 26.11.84：不用等用户手点「获取登录状态」，这里自动补一次
            var acc = ltAccount
            if (!acc.login) {
                acc = withContext(Dispatchers.IO) { ListenTogetherApi.account() }
                ltAccount = acc
                ltMessage = if (acc.login) "已登录 ${acc.nickname}，正在创建房间"
                else "未登录，请先在设置里登录网易云"
            }
            if (!acc.login) {
                ltBusy = false
                toast(ltMessage)
                return@launch
            }
            val id = withContext(Dispatchers.IO) { ListenTogetherApi.createRoom() }
            if (id.isNullOrBlank()) {
                ltBusy = false
                ltMessage = "创建房间失败，请检查登录与网络"
                toast(ltMessage)
                return@launch
            }
            ltRoomId = id
            ltInviterId = ltAccount.userId
            ltShareLink = ltShareUrl()
            ltMessage = "创建房间成功：$id"
            withContext(Dispatchers.IO) {
                ListenTogetherApi.checkRoom(id)
                ListenTogetherApi.status().let { st -> ltUsers = st.users }
            }
            ltBusy = false
            if (peerUid > 0L) {
                val invite = "【一起听】$ltMessage，点链接加入：$ltShareLink"
                val ok = withContext(Dispatchers.IO) {
                    NmSocial.sendText(peerUid.toString(), invite)
                }
                toast(if (ok) "房间已创建，邀请已私信发送" else "房间已创建，但邀请发送失败")
            } else toast(ltMessage)
        }
    }

    /** 加入房间：accept → room/check → sync/playlist/get → song/detail */
    fun ltJoin(roomId: String, inviterId: Long) {
        val rid = roomId.trim()
        if (rid.isBlank()) { toast("请填写房间 ID"); return }
        ltBusy = true
        viewModelScope.launch {
            val (ok, st, ids) = withContext(Dispatchers.IO) {
                val accepted = ListenTogetherApi.acceptRoom(rid, inviterId)
                val check = ListenTogetherApi.checkRoom(rid)
                val list = if (accepted || check.inRoom) ListenTogetherApi.syncPlaylistGet(rid)
                else emptyList()
                Triple(accepted || check.inRoom, ListenTogetherApi.status(), list)
            }
            ltBusy = false
            if (!ok) {
                ltMessage = "加入房间失败，检查房间 ID / 邀请者 ID / 登录态"
                toast(ltMessage)
                return@launch
            }
            ltRoomId = rid
            ltInviterId = inviterId
            ltUsers = st.users
            ltShareLink = ltShareUrl()
            ltMessage = "加入房间成功：$rid"
            if (ids.isNotEmpty()) {
                ltPlaylistName = "房间播放列表"
                ltTracks = withContext(Dispatchers.IO) { ListenTogetherApi.songDetail(ids) }
                ltSongId = ids.firstOrNull() ?: 0L
            }
            toast(ltMessage)
        }
    }

    /** 刷新房间状态：在线用户 */
    fun ltRefresh() {
        val id = ltRoomId ?: return
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) { ListenTogetherApi.status() }
            ltUsers = st.users
            ltMessage = if (st.inRoom || st.users.isNotEmpty()) "房间状态已刷新（${st.users.size} 人在线）"
            else "房间状态获取失败，可能已退出房间"
        }
    }

    /** 结束房间 */
    fun ltClose() {
        val id = ltRoomId ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { ListenTogetherApi.endRoom(id) }
            ltMessage = if (ok) "房间关闭成功" else "房间关闭失败（仍清掉本地房间号）"
            toast(ltMessage)
            ltRoomId = null
            ltUsers = emptyList()
            ltShareLink = ""
            ltTracks = emptyList()
            ltPlaylistName = "未获取"
        }
    }

    /** 26.11.86：一起听里搜索歌单（type=1000） */
    fun ltSearchPlaylist(q: String) {
        val k = q.trim()
        ltPlaylistQuery = k
        if (k.isBlank()) { ltPlaylistResults = emptyList(); return }
        ltPlaylistBusy = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { ListenTogetherApi.searchPlaylists(k) }
            ltPlaylistResults = list
            ltPlaylistBusy = false
            if (list.isEmpty()) toast("没搜到歌单，可点「我的歌单」")
        }
    }

    /** 26.11.86：载入我的歌单（自建 + 收藏），直接点选同步进房间 */
    fun ltLoadMyPlaylists() {
        ltPlaylistBusy = true
        viewModelScope.launch {
            val uid = withContext(Dispatchers.IO) {
                ltAccount.userId.takeIf { it > 0L } ?: Netease.uid()
            }
            val list = withContext(Dispatchers.IO) { ListenTogetherApi.myPlaylists(uid) }
            ltMyPlaylists = list
            ltPlaylistResults = list
            ltPlaylistQuery = "我的歌单"
            ltPlaylistBusy = false
            if (list.isEmpty()) toast("没有拿到你的歌单，检查登录态或点「获取登录状态」")
        }
    }

    /** 选中一张歌单：加载曲目并 REPLACE 同步到房间 */
    fun ltSelectPlaylist(pl: PlaylistBrief) {
        if (pl.id <= 0L) return
        ltPlaylistName = pl.name
        toast("已选「${pl.name}」，正在加载")
        ltLoadPlaylist(pl.id.toString())
    }

    /** 加载歌单并 REPLACE 同步到房间 */
    fun ltLoadPlaylist(idText: String) {
        val id = idText.trim().toLongOrNull()
        if (id == null || id <= 0L) { toast("请填写数字歌单 ID"); return }
        val room = ltRoomId
        ltBusy = true
        viewModelScope.launch {
            val pl = withContext(Dispatchers.IO) { ListenTogetherApi.playlistDetail(id) }
            ltPlaylistName = pl.name
            ltTracks = pl.tracks
            ltBusy = false
            if (pl.trackIds.isEmpty()) { toast("歌单里没有歌曲"); return@launch }
            if (room != null) {
                val ok = withContext(Dispatchers.IO) {
                    ListenTogetherApi.syncList(
                        room, ltAccount.userId, ltClientSeq++,
                        pl.trackIds.joinToString(","),
                    )
                }
                toast(if (ok) "歌单已同步到房间" else "歌单已加载，但同步指令未确认")
            } else toast("歌单已加载（未加入房间，不会同步）")
        }
    }

    /** 切歌：GOTO 指令 + 本地播放 + 发一次心跳 */
    fun ltGoto(trackId: Long) {
        if (trackId <= 0L) return
        val former = ltSongId.takeIf { it > 0L }?.toString() ?: "-1"
        ltSongId = trackId
        ltPlayStatus = "PLAY"
        val t = ltTracks.firstOrNull { it.id == trackId }
        val s = Song(
            ssid = "$NM_PREFIX$trackId",
            title = t?.name ?: "网易云歌曲 $trackId",
            artist = t?.artist.orEmpty(),
            coverUrl = t?.cover,
            source = SRC_NETEASE, nmId = trackId,
        )
        playSongs(listOf(s), 0)
        openPlayer()
        val room = ltRoomId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            ListenTogetherApi.playCommand(room, "GOTO", trackId, 0L, "PLAY", ltClientSeq++, former)
            ListenTogetherApi.heartbeat(room, trackId, "PLAY", 0L)
            ltLastHeartbeat = System.currentTimeMillis()
        }
    }

    /** 播放 / 暂停 / 同步进度，progress 用播放器当前位置（毫秒） */
    fun ltPlay(action: String) {
        val room = ltRoomId ?: return
        val song = ltSongId
        if (song <= 0L) { toast("请先在播放列表里选一首歌"); return }
        val status = if (action == "PAUSE") "PAUSE" else "PLAY"
        ltPlayStatus = status
        when (action) {
            "PLAY" -> { controller?.play(); }
            "PAUSE" -> controller?.pause()
            else -> controller?.play()
        }
        val progress = positionMs
        viewModelScope.launch(Dispatchers.IO) {
            ListenTogetherApi.playCommand(
                room, action, song, progress, status, ltClientSeq++,
            )
        }
    }

    /** 轮询里调用：每 15 秒发一次心跳，切歌时会立即发一次 */
    private fun ltHeartbeatTick() {
        val room = ltRoomId ?: return
        val song = ltSongId
        if (song <= 0L) return
        val now = System.currentTimeMillis()
        if (now - ltLastHeartbeat < 15_000L) return
        ltLastHeartbeat = now
        val status = ltPlayStatus
        val progress = positionMs
        viewModelScope.launch(Dispatchers.IO) {
            ListenTogetherApi.heartbeat(room, song, status, progress)
        }
    }


    // ================= 26.11.87：电台（原播客页） =================

    var radioTab by mutableIntStateOf(0); private set
    /** feed.key -> 该接口返回的条目 */
    var radioResults by mutableStateOf<Map<String, List<RadioItem>>>(emptyMap()); private set
    var radioBusyKeys by mutableStateOf<Set<String>>(emptySet()); private set
    var radioError by mutableStateOf(""); private set
    /** feed.key -> 用户填的参数（动态占位符） */
    var radioParams by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var radioDetail by mutableStateOf<RadioItem?>(null); private set
    var radioDetailText by mutableStateOf(""); private set
    var radioDetailPrograms by mutableStateOf<List<RadioItem>>(emptyList()); private set
    var radioDetailBusy by mutableStateOf(false); private set
    var radioSearchQuery by mutableStateOf(""); private set
    var radioSearchResults by mutableStateOf<List<RadioItem>>(emptyList()); private set
    var radioSearchBusy by mutableStateOf(false); private set

    fun chooseRadioTab(i: Int) { radioTab = i }
    fun setRadioParam(feedKey: String, v: String) {
        radioParams = radioParams + (feedKey to v)
    }

    /** 把 {uid} / {xxx} 占位符换成真实值；未解析的一律丢掉，不发字面量花括号 */
    private fun resolveRadio(feed: RadioFeed): List<Pair<String, String>> {
        val uid = runCatching { Netease.uid() }.getOrDefault(0L).toString()
        val custom = radioParams[feed.key]?.trim().orEmpty()
        val parts = custom.split(',', '，')
        val blank = Regex("""\{[^}]*\}""")
        fun sub(x: String): String {
            var v = x.replace("{uid}", uid)
            if (feed.paramName.isNotBlank()) {
                val first = parts.getOrNull(0)?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: feed.paramDefault
                v = v.replace("{${feed.paramName}}", first)
            }
            if (feed.key == "voicetrans") {
                v = v.replace("{programId}", parts.getOrNull(1)?.trim().orEmpty())
            }
            return blank.replace(v, "")
        }
        return feed.params.map { (k, v) -> sub(k) to sub(v) }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
    }

    fun radioFeeds(): List<RadioFeed> =
        RadioFeeds.groups().getOrNull(radioTab)?.second.orEmpty()

    /** 按需加载单个 feed，不一次并发十几个接口拖慢页面 */
    fun loadRadioFeed(feed: RadioFeed) {
        if (feed.key in radioBusyKeys) return
        radioBusyKeys = radioBusyKeys + feed.key
        radioError = ""
        viewModelScope.launch {
            val args = resolveRadio(feed)
            val list = withContext(Dispatchers.IO) { RadioApi.fetch(feed, args) }
            radioResults = radioResults + (feed.key to list)
            radioBusyKeys = radioBusyKeys - feed.key
            if (list.isEmpty()) {
                radioError = "「${feed.title}」没有返回内容：检查登录态 / 参数，可改参数后重试"
            }
        }
    }

    fun searchRadio(q: String) {
        val k = q.trim()
        radioSearchQuery = k
        if (k.isBlank()) { radioSearchResults = emptyList(); return }
        radioSearchBusy = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { RadioApi.search(k) }
            radioSearchResults = r
            radioSearchBusy = false
            if (r.isEmpty()) radioError = "没搜到电台（type=1009 这一路可能没有返回结果）"
        }
    }

    /** 点电台卡片：详情 + 节目列表 + 订阅者 */
    fun openRadioDetail(item: RadioItem) {
        radioDetail = item
        radioDetailText = ""
        radioDetailPrograms = emptyList()
        radioDetailBusy = true
        viewModelScope.launch {
            val args = listOf("rid" to item.id.toString())
            val info = withContext(Dispatchers.IO) {
                RadioApi.detailText("/dj/detail", args)
            }
            val progs = withContext(Dispatchers.IO) {
                RadioApi.fetch(
                    RadioFeed(
                        "djprogram", "节目", "/dj/program",
                        listOf("rid" to item.id.toString(), "limit" to "50", "asc" to "false"),
                        "program",
                    ),
                    listOf("rid" to item.id.toString(), "limit" to "50", "asc" to "false"),
                )
            }
            radioDetailText = info
            radioDetailPrograms = progs
            radioDetailBusy = false
        }
    }

    fun closeRadioDetail() {
        radioDetail = null
        radioDetailText = ""
        radioDetailPrograms = emptyList()
    }

    /** 节目播放：用 mainTrackId 走已有网易云音源链，不在本页另写一套解析 */
    fun playRadioProgram(item: RadioItem) {
        val trackId = item.extra.substringBefore("|").toLongOrNull() ?: item.id
        if (trackId <= 0L) { toast("这条没有可播放的音频 id"); return }
        val so = Song(
            ssid = "$NM_PREFIX$trackId",
            title = item.title,
            artist = item.subtitle,
            coverUrl = item.cover,
            source = SRC_NETEASE,
            nmId = trackId,
        )
        playSongs(listOf(so), 0)
        openPlayer()
    }

    /** 订阅 / 取消订阅电台 */
    fun radioSubscribe(item: RadioItem, sub: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = RadioApi.act(
                "/dj/sub",
                listOf("rid" to item.id.toString(), "t" to if (sub) "1" else "0"),
            )
            withContext(Dispatchers.Main) {
                toast(if (ok) (if (sub) "已订阅" else "已取消订阅") else "订阅指令未确认（检查登录态）")
            }
        }
    }

    /** 广播电台收藏 / 取消收藏 */
    fun radioBroadcastSub(item: RadioItem, sub: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = RadioApi.act(
                "/broadcast/sub",
                listOf("id" to item.id.toString(), "t" to if (sub) "1" else "0"),
            )
            withContext(Dispatchers.Main) {
                toast(if (ok) (if (sub) "已收藏" else "已取消收藏") else "收藏指令未确认")
            }
        }
    }

    /** DIFM 频道收藏 / 取消收藏 */
    fun radioDifmSub(item: RadioItem, sub: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = RadioApi.act(
                if (sub) "/dj/difm/channel/subscribe" else "/dj/difm/channel/unsubscribe",
                listOf("id" to item.id.toString()),
            )
            withContext(Dispatchers.Main) {
                toast(if (ok) (if (sub) "已收藏频道" else "已取消收藏") else "指令未确认")
            }
        }
    }

    /** 广播电台信息 / 节目详情：直接把文本塞进详情区 */
    fun radioShowInfo(path: String, id: Long) {
        if (id <= 0L) return
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) {
                RadioApi.detailText(path, listOf("id" to id.toString()))
            }
            radioDetail = RadioItem(id = id, title = "电台信息")
            radioDetailText = t
            radioDetailPrograms = emptyList()
            radioDetailBusy = false
        }
    }


    // ================= 26.11.88：应用内 WebView 统一入口 =================

    /** 当前 WebView 页要用的 cookie；null = 还没取好，非 null（含空串）= 可以加载 */
    var webCookie by mutableStateOf<String?>(null); private set

    /**
     * 打开任意 HTTP(S) 链接：先进应用内 WebView 页，再异步确定 cookie。
     * 登录用当前账号 cookie，未登录用游客 cookie（/register/anonimous，10 分钟缓存）。
     * 不在主线程发网络请求，所以 WebView 先建好、cookie 到位后再注入并 loadUrl。
     */
    fun openInternalLink(raw: String) {
        val u = raw.trim()
        if (u.isBlank()) return
        webCookie = null
        stack.add(Screen.WebLink(u))
        viewModelScope.launch {
            val ck = withContext(Dispatchers.IO) {
                prefs.nmCookie.takeIf { it.isNotBlank() }
                    ?: runCatching { Netease.anonCookie() }.getOrDefault(null)
                    ?: ""
            }
            // 只给当前这个网页页用；若用户已经关掉就不再写
            if (stack.lastOrNull() is Screen.WebLink) webCookie = ck
        }
    }

    fun closeWebLink() {
        if (stack.lastOrNull() is Screen.WebLink) {
            stack.removeAt(stack.lastIndex)
            webCookie = null
        }
    }

}
