package de.myocd.gomusic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 26.11.55：不依赖系统 MediaRouter 的投屏。
 *
 * 旧实现只看 MediaRouter 里由 ROM 注册的 CATEGORY_REMOTE_PLAYBACK route，
 * 国产系统几乎不注册任何 provider，设备列表永远是空的 —— 这就是「投屏无效」。
 * 这里自己走标准 DLNA / UPnP：
 *   1. SSDP M-SEARCH 多播发现局域网 MediaRenderer
 *   2. 读设备描述 XML，取 AVTransport 的 controlURL
 *   3. SOAP SetAVTransportURI + Play 把音源交给电视
 *   4. 音源经 [LocalCastServer] 由手机转发成局域网 HTTP 地址，
 *      绕过在线直链的防盗链 / UA / 时效限制，电视只从手机拉流
 */
data class DlnaDevice(
    val name: String,
    val location: String,
    val controlUrl: String,
)

private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"

object DlnaCast {
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 26.11.60：当前是否连着 WiFi。
     * 蜂窝网络下往 239.255.255.250 发 SSDP 多播会直接抛 IOException，
     * 而且拿不到局域网地址，转发服务也起不来，所以干脆不发。
     */
    fun isWifi(ctx: Context): Boolean = runCatching {
        val cm = ctx.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    /**
     * SSDP 发现；必须在 IO 线程调用。
     *
     * 26.11.60：整条发现链用 runCatching 兜住。旧版 socket 构造 / send /
     * receive / MulticastLock.acquire 的异常都没有捕获，会从 IO 协程一路
     * 冒泡成未捕获异常 —— 用户在移动数据下点投屏就是直接闪退。
     */
    fun discover(ctx: Context): List<DlnaDevice> {
        // 没有 WiFi 就不做多播：既避免异常，也不白等 3.2 秒
        if (!isWifi(ctx)) return emptyList()
        return runCatching discovery@ {
            val wm = ctx.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wm?.createMulticastLock("gomusic-cast")?.apply {
                setReferenceCounted(true)
            }
            try {
                runCatching { lock?.acquire() }
                val socket = runCatching {
                    DatagramSocket(0).apply {
                        soTimeout = 300
                        broadcast = true
                    }
                }.getOrNull() ?: return@discovery emptyList<DlnaDevice>()
                val locations = LinkedHashSet<String>()
                try {
                    val probe = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
                    val bytes = probe.toByteArray(Charsets.US_ASCII)
                    val target = runCatching {
                        InetAddress.getByName("239.255.255.250")
                    }.getOrNull() ?: return@discovery emptyList<DlnaDevice>()
                    repeat(3) {
                        // 单播接口不可达时 send 会抛，忽略这一轮即可
                        runCatching {
                            socket.send(DatagramPacket(bytes, bytes.size, target, 1900))
                        }
                    }
                    val deadline = System.currentTimeMillis() + 3200
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val buf = ByteArray(4096)
                            val dp = DatagramPacket(buf, buf.size)
                            socket.receive(dp)
                            val txt = String(dp.data, 0, dp.length, Charsets.UTF_8)
                            txt.lineSequence().firstOrNull { l ->
                                l.trimStart().startsWith("location:", ignoreCase = true)
                            }?.substringAfter(':', "")?.trim()?.let { locations.add(it) }
                        } catch (e: SocketTimeoutException) {
                            // 继续等下一个响应
                        } catch (e: Exception) {
                            break
                        }
                    }
                } finally {
                    runCatching { socket.close() }
                }
                locations.mapNotNull { loc ->
                    runCatching { parseDevice(loc) }.getOrNull()
                }
            } finally {
                runCatching { if (lock?.isHeld == true) lock.release() }
            }
        }.getOrDefault(emptyList<DlnaDevice>())
    }

    /** 手动输入设备描述地址时用，跳过 SSDP */
    fun fromLocation(location: String): DlnaDevice? = runCatching { parseDevice(location) }.getOrNull()

    private fun parseDevice(location: String): DlnaDevice? {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.string().orEmpty().ifBlank { return null }
        }
        val base = Regex("(?is)<URLBase>\\s*(.*?)\\s*</URLBase>")
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: run {
                val u = URI(location)
                "${u.scheme}://${u.authority}"
            }
        val name = Regex("(?is)<friendlyName>\\s*(.*?)\\s*</friendlyName>")
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: "投屏设备"
        var control: String? = null
        Regex("(?is)<service>(.*?)</service>").findAll(xml).forEach { m ->
            val block = m.groupValues[1]
            if (block.contains("AVTransport")) {
                val c = Regex("(?is)<controlURL>\\s*(.*?)\\s*</controlURL>")
                    .find(block)?.groupValues?.get(1)?.trim()
                if (!c.isNullOrBlank()) control = resolve(base, c)
            }
        }
        val url = control ?: return null
        return DlnaDevice(name, location, url)
    }

    private fun resolve(base: String, path: String): String = when {
        path.startsWith("http", ignoreCase = true) -> path
        path.startsWith("/") -> base.trimEnd('/') + path
        else -> base.trimEnd('/') + "/" + path
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun soap(device: DlnaDevice, action: String, body: String): Boolean = runCatching {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$AVT\">$body</u:$action></s:Body></s:Envelope>"
        val req = Request.Builder().url(device.controlUrl)
            .header("SOAPACTION", "\"$AVT#$action\"")
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .post(xml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    fun play(
        device: DlnaDevice,
        url: String,
        title: String,
        artist: String,
        cover: String?,
    ): Boolean {
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${esc(title)}</dc:title>" +
            "<dc:creator>${esc(artist)}</dc:creator>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            (cover?.let { "<upnp:albumArtURI>${esc(it)}</upnp:albumArtURI>" }.orEmpty()) +
            "<res protocolInfo=\"http-get:*:audio/*:DLNA.ORG_OP=01\">${esc(url)}</res>" +
            "</item></DIDL-Lite>"
        val set = "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${esc(url)}</CurrentURI>" +
            "<CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>"
        if (!soap(device, "SetAVTransportURI", set)) return false
        return soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    fun pause(device: DlnaDevice): Boolean =
        soap(device, "Pause", "<InstanceID>0</InstanceID>")

    fun resume(device: DlnaDevice): Boolean =
        soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    fun stop(device: DlnaDevice): Boolean =
        soap(device, "Stop", "<InstanceID>0</InstanceID>")
}
