package de.myocd.gomusic

import android.app.Application
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
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

    // ---------- 设置 ----------
    var quality by mutableStateOf(prefs.quality); private set
    var playMode by mutableIntStateOf(prefs.playMode); private set
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

    val currentSong: Song? by derivedStateOf { songs.firstOrNull { it.ssid == curSsid } }
    val lyricIndex: Int by derivedStateOf {
        if (lyric.isEmpty() || lyric[0].timeMs < 0) -1
        else lyric.indexOfLast { it.timeMs <= positionMs }
    }

    private var pendingLink: Uri? = null
    private var afterConnect: (() -> Unit)? = null
    private var toastJob: Job? = null

    // ================= 加载 =================
    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                songs = withContext(Dispatchers.IO) { Api.songs() }
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            }
            loading = false
            runCatching { artistInfo = withContext(Dispatchers.IO) { Api.artists() } }
            pendingLink?.let { pendingLink = null; handleLink(it) }
        }
    }

    // ================= 列表派生 =================
    fun rank(l: List<Song>): List<Song> {
        val c = counts
        return l.shuffled().sortedByDescending { c[it.ssid] ?: 0 } // 稳定排序：并列项保持随机顺序
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

    fun songsByIds(ids: List<String>): List<Song> {
        val m = songs.associateBy { it.ssid }
        return ids.mapNotNull { m[it] }
    }

    // ================= 播放 =================
    private fun toItem(s: Song): MediaItem = MediaItem.Builder()
        .setMediaId(s.ssid)
        .setUri("gomusic:///${s.ssid}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .setAlbumTitle(s.album)
                .setArtworkUri(Api.abs(s.coverUrl)?.takeIf { it.startsWith("http") }?.let { Uri.parse(it) })
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
        updateCover(songs.firstOrNull { it.ssid == id }?.coverUrl ?: item?.mediaMetadata?.artworkUri?.toString())
        viewModelScope.launch {
            try {
                val d = withContext(Dispatchers.IO) { Api.detail(id) }
                if (curSsid != id) return@launch
                qualities = d.audio.keys.sortedBy { Api.rank(it) }
                var t = d.lyrics
                if (t.isBlank()) t = withContext(Dispatchers.IO) { runCatching { Api.lyrics(id) }.getOrDefault("") }
                if (curSsid == id) lyric = lyricLines(t)
            } catch (ignored: Exception) {
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
    fun download(list: List<Song>) {
        if (list.isEmpty()) return
        toast("开始下载 ${list.size} 首")
        viewModelScope.launch(Dispatchers.IO) {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            for (s in list) {
                try {
                    val url = Api.audioUrl(s.ssid, quality)
                    val ext = url.substringBefore('?').substringAfterLast('.', "mp3")
                        .takeIf { it.length in 2..4 } ?: "mp3"
                    val name = "${s.artist} - ${s.title}".replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val r = DownloadManager.Request(Uri.parse(url))
                        .setTitle(s.title)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "GoMusic/$name.$ext")
                    dm.enqueue(r)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { toast("下载失败：${s.title}") }
                }
            }
        }
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
                delay(250)
                controller?.let { c ->
                    positionMs = c.currentPosition.coerceAtLeast(0)
                    durationMs = if (c.duration == C.TIME_UNSET) 0 else c.duration.coerceAtLeast(0)
                }
            }
        }
    }
}
