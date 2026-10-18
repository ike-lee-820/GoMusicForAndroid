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
}

data class Group(
    val title: String,
    val subtitle: String,
    val cover: String?,
    val songs: List<Song>,
    val artist: String = "",
    val album: String = "",
)

data class PickRequest(val ssids: List<String>, val name: String, val cover: String?)
data class QueueEntry(val ssid: String, val title: String, val artist: String, val cover: String?)

val DefaultPrimary = Color(0xFFD0BCFF)

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

    /** 已下载 ssid 集合：列表里做 O(1) 判断，避免滑动时逐首线性扫描 */
    val downloadedIds: Set<String> by derivedStateOf { downloads.mapTo(HashSet()) { it.ssid } }

    // ---------- 设置 ----------
    var quality by mutableStateOf(prefs.quality); private set
    var playMode by mutableIntStateOf(prefs.playMode); private set
    /** 后台播放被打断的策略：[FOCUS_NORMAL] / [FOCUS_CALL_ONLY] / [FOCUS_ALWAYS] */
    var playFocus by mutableIntStateOf(prefs.playFocus); private set
    /** 「仅通话暂停」需要电话权限，由 Activity 监听并发起授权 */
    var needPhonePerm by mutableStateOf(false); private set

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

    val currentSong: Song? by derivedStateOf { curSsid?.let { songMap[it] } }
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
            m.startsWith("HTTP ") -> "服务器返回 $m"
            else -> "加载失败：$m"
        }
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            var attempt = 0
            var ok = false
            // 首次进入（WiFi 刚连上）多试一次，避免瞬间抖动直接报失败
            while (attempt < 2 && !ok) {
                attempt++
                try {
                    val s = withContext(Dispatchers.IO) { Api.songs() }
                    songs = s
                    ok = true
                } catch (e: Exception) {
                    error = netMsg(e)
                    if (attempt < 2) delay(800)
                }
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
        }
        updateCover(songMap[id]?.coverUrl ?: item?.mediaMetadata?.artworkUri?.toString())
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
        val todo = list.filter { it.ssid !in downloading }
        if (todo.isEmpty()) return
        downloading = downloading + todo.map { it.ssid }
        toast("开始下载 ${todo.size} 首")
        Noti(app).ensureChannel()
        todo.forEachIndexed { i, s ->
            viewModelScope.launch(Dispatchers.IO) {
                Noti(app).progress(s.title, i + 1, todo.size)
                val r = runCatching { LocalLib.download(app, s, quality) }
                withContext(Dispatchers.Main) {
                    downloading = downloading - s.ssid
                    r.onSuccess { rec ->
                        val l = prefs.downloads.filter { it.ssid != rec.ssid } + rec
                        prefs.downloads = l
                        downloads = l
                        if (offline) songs = LocalLib.songs(l)
                        Noti(app).done("${s.title} 下载完成")
                        toast("已下载：${s.title}")
                    }.onFailure {
                        Noti(app).done("${s.title} 下载失败")
                        toast("下载失败：${s.title}")
                    }
                }
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
