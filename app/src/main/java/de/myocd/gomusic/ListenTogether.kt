package de.myocd.gomusic

import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 26.11.82：一起听 · 主机模式（局域网）。
 *
 * 你给的 #1676 Issue 与 listen_together_host.html 本轮都解析失败，正文里
 * 只有参考链接、没有网易云官方一起听房间的建连 / 心跳 / 下发协议参数，
 * 所以这里不伪造官方房间：做成一个**真实可用的局域网主机模式**。
 *
 * 主机在手机上起一个极简 HTTP 服务，/state 返回当前播放的歌曲、进度、
 * 播放状态；把房间地址通过私信发给对方，对方在「加入房间」里填地址，
 * 每 2 秒拉一次状态就能看到主机在放什么，并可在本机曲库播放同名歌曲。
 * 两台设备需在同一 WiFi；不需要网易云账号，也不依赖任何私有协议。
 */
data class RoomState(
    val title: String = "",
    val artist: String = "",
    val ssid: String = "",
    val position: Long = 0,
    val duration: Long = 0,
    val playing: Boolean = false,
    val updated: Long = 0,
)

object ListenTogetherHost {

    @Volatile var roomUrl: String? = null
        private set
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null

    fun start(): String? {
        stop()
        val ip = LocalCastServer.lanIp() ?: return null
        val s = runCatching {
            ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        }.getOrNull() ?: return null
        server = s
        running.set(true)
        Thread({
            while (running.get()) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread({ serve(c) }, "gomusic-listen-conn").apply { isDaemon = true }.start()
            }
        }, "gomusic-listen-http").apply { isDaemon = true }.start()
        roomUrl = "http://$ip:${s.localPort}"
        return roomUrl
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        roomUrl = null
    }

    /** 由 MainViewModel 在状态变化时写入，避免服务线程直接抓 Compose 状态 */
    @Volatile var snapshot: RoomState = RoomState()
        private set

    fun publish(state: RoomState) {
        snapshot = state.copy(updated = System.currentTimeMillis())
    }

    private fun serve(socket: Socket) {
        runCatching {
            socket.soTimeout = 3000
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            // 只关心路径，请求头读到空行即可
            var first = ""
            while (true) {
                val line = readLine(input) ?: break
                if (first.isEmpty()) first = line
                if (line.isEmpty()) break
            }
            val path = first.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
            val (status, body) = if (path == "/state" || path == "/") {
                200 to JSONObject().apply {
                    put("title", snapshot.title)
                    put("artist", snapshot.artist)
                    put("ssid", snapshot.ssid)
                    put("position", snapshot.position)
                    put("duration", snapshot.duration)
                    put("playing", snapshot.playing)
                    put("updated", snapshot.updated)
                }.toString()
            } else 404 to "not found"
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
        runCatching { socket.close() }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }
}

object ListenTogetherGuest {
    /** 拉一次主机状态；地址可以是 http://ip:port 或直接填 /state 完整地址 */
    fun fetch(base: String): RoomState? = runCatching {
        val url = base.trim().trimEnd('/').let {
            if (it.endsWith("/state")) it else "$it/state"
        }
        val req = Request.Builder().url(url).build()
        Api.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val t = r.body?.string().orEmpty()
            val o = JSONObject(t)
            RoomState(
                title = o.optString("title"),
                artist = o.optString("artist"),
                ssid = o.optString("ssid"),
                position = o.optLong("position"),
                duration = o.optLong("duration"),
                playing = o.optBoolean("playing"),
                updated = o.optLong("updated"),
            )
        }
    }.getOrNull()
}
