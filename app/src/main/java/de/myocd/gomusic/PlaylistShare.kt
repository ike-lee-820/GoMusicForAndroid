package de.myocd.gomusic

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 歌单导入 / 导出。
 *
 * 还原字符格式：`GM1:<base64(deflate(json))>`
 * - 导出：可显示二维码、复制字符、或存成 .txt 文件
 * - 导入：可粘贴字符、选还原文件、或选一张二维码图片解析
 *
 * 带版本号前缀，方便后续换格式时区分。
 */
object PlaylistShare {
    private const val PREFIX = "GM1:"
    private val json = Json { ignoreUnknownKeys = true }

    /** 导出用的歌单快照：只带歌名歌手专辑封面，不依赖本地曲库 */
    @Serializable
    data class Snapshot(
        val name: String,
        val songs: List<Item> = emptyList(),
    )

    @Serializable
    data class Item(
        val ssid: String,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val cover: String? = null,
        val source: String = SRC_LOCAL,
    )

    /** 把歌单编码成还原字符 */
    fun encode(p: Playlist, songs: List<Song>): String {
        val snap = Snapshot(
            name = p.name,
            songs = songs.map {
                Item(it.ssid, it.title, it.artist, it.album, it.coverUrl, it.source)
            },
        )
        val raw = json.encodeToString(snap).toByteArray(Charsets.UTF_8)
        val zipped = deflate(raw)
        return PREFIX + Base64.encodeToString(zipped, Base64.NO_WRAP)
    }

    /** 解析还原字符；格式不对返回 null */
    fun decode(text: String): Snapshot? {
        val t = text.trim().removePrefix(PREFIX).trim()
        if (t.isBlank()) return null
        return runCatching {
            val zipped = Base64.decode(t, Base64.DEFAULT)
            val raw = inflate(zipped)
            json.decodeFromString<Snapshot>(raw.toString(Charsets.UTF_8))
        }.onFailure {
            NmLog.note("歌单还原字符解析失败：${it.message}")
        }.getOrNull()
    }

    /** 生成二维码位图；还原字符太长放不进二维码时返回 null */
    fun qrBitmap(text: String, size: Int = 640): Bitmap? = runCatching {
        val w = QRCodeWriter()
        val bits = w.encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.onFailure {
        NmLog.note("生成二维码失败：${it.message}")
    }.getOrNull()

    /** 从图片里识别二维码还原字符；识别不出返回 null */
    fun readQr(bmp: Bitmap): String? {
        return runCatching {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val src: LuminanceSource = RGBLuminanceSource(w, h, px)
            val bin = BinaryBitmap(HybridBinarizer(src))
            MultiFormatReader().decode(bin)?.text
        }.onFailure {
            NmLog.note("识别二维码失败：${it.message}")
        }.getOrNull()
    }

    /** 还原字符是否短到能放进二维码（二维码容量有限，太长要改用文件） */
    fun fitsQr(text: String): Boolean = text.length <= 1800

    private fun deflate(src: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(src)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) {
            val n = d.deflate(buf)
            out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }

    private fun inflate(src: ByteArray): ByteArray {
        val i = Inflater()
        i.setInput(src)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        i.end()
        return out.toByteArray()
    }
}
