package de.myocd.gomusic

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 26.11.55：投屏本机 HTTP 转发。
 *
 * 电视 / 音箱直接拉网易云的临时 CDN 链接，常被防盗链 UA / Referer、
 * 签名过期或 http 明文挡掉（403），表现就是「投过去了但没声音」。
 * 这里在手机上起一个极简 HTTP 服务：设备只访问 http://<手机局域网IP>:<port>/audio，
 * 由手机带正确请求头从 CDN 取流再转发；Range 请求透传，拖动进度也能工作。
 * 封面走 /cover，供 DIDL-Lite 的 albumArtURI 使用。
 */
object LocalCastServer {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 流式转发，不设读取超时
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var audioUrl: String = ""
    @Volatile private var coverUrl: String = ""
    private val running = AtomicBoolean(false)

    /** 手机在局域网里的 IPv4；没连 WiFi / 只有蜂窝时返回 null */
    fun lanIp(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull {
                it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress
            }?.hostAddress
    }.getOrNull()

    /** 启动转发，返回 http://ip:port；失败返回 null */
    fun start(audio: String, cover: String?): String? {
        stop()
        val ip = lanIp() ?: return null
        audioUrl = audio
        coverUrl = cover.orEmpty()
        val s = runCatching {
            ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
        }.getOrNull() ?: return null
        server = s
        running.set(true)
        Thread({
            while (running.get()) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread({ serve(c) }, "gomusic-cast-conn").apply { isDaemon = true }.start()
            }
        }, "gomusic-cast-http").apply { isDaemon = true }.start()
        return "http://$ip:${s.localPort}"
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun serve(socket: Socket) {
        runCatching {
            socket.soTimeout = 0
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val first = readLine(input) ?: return
            val parts = first.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"
            var range: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    range = line.substringAfter(':').trim()
                }
            }
            val target = when {
                path == "/audio" || path.startsWith("/audio/") -> audioUrl
                path == "/cover" || path.startsWith("/cover/") -> coverUrl
                else -> ""
            }
            if (target.isBlank()) {
                writeHead(output, 404, "text/plain", null, false)
                if (method != "HEAD") output.write("not found".toByteArray())
                output.flush()
                return
            }
            val builder = Request.Builder().url(target)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) GoMusic/1.0")
                .header("Accept", "*/*")
            if (!range.isNullOrBlank()) builder.header("Range", range)
            client.newCall(builder.get().build()).execute().use { resp ->
                val body = resp.body
                val type = body?.contentType()?.toString()
                    ?: if (path.startsWith("/cover")) "image/jpeg" else "audio/mpeg"
                val status = if (resp.code == 206) 206 else 200
                val cr = resp.header("Content-Range")
                writeHead(output, status, type, cr, true)
                if (method != "HEAD" && body != null) {
                    body.byteStream().use { src -> writeChunked(src, output) }
                } else {
                    output.write("0\r\n\r\n".toByteArray())
                }
                output.flush()
            }
        }
        runCatching { socket.close() }
    }

    private fun writeHead(
        out: OutputStream, status: Int, type: String,
        contentRange: String?, chunked: Boolean,
    ) {
        val reason = if (status == 206) "Partial Content" else "OK"
        val sb = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            .append("Content-Type: ").append(type).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Connection: close\r\n")
        if (!contentRange.isNullOrBlank()) {
            sb.append("Content-Range: ").append(contentRange).append("\r\n")
        }
        if (chunked) sb.append("Transfer-Encoding: chunked\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun writeChunked(src: InputStream, out: OutputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val n = src.read(buf)
            if (n <= 0) break
            out.write(Integer.toHexString(n).toByteArray(Charsets.US_ASCII))
            out.write("\r\n".toByteArray(Charsets.US_ASCII))
            out.write(buf, 0, n)
            out.write("\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
        out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }

    /** 只读请求头，逐字节读避免 BufferedReader 预读把后面的二进制吃掉的通用问题 */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == 10) return sb.toString()
            if (b == 13) {
                val n = input.read()
                if (n == 10 || n == -1) return sb.toString()
                // 不是 CRLF：把下一个字节并进当前行，容忍不规范实现
                sb.append(b.toChar()).append(n.toChar())
            } else {
                sb.append(b.toChar())
            }
        }
    }
}
