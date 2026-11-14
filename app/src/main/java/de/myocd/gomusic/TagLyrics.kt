package de.myocd.gomusic

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/**
 * 26.11.35：直接读取音频容器标签里的内嵌歌词。
 *
 * Android 的 MediaMetadataRetriever 只暴露 TITLE / ARTIST / ALBUM / DURATION 等
 * 固定 key，并没有 LYRIC / USLT / UNSYNCEDLYRICS 这一项；26.11.34 从
 * WRITER / AUTHOR / GENRE / COMPOSER 里找时间戳，实际几乎永远落空。
 * 这里只读文件头尾字节自行解标签，不引第三方库、不联网、不整文件解码。
 */
data class TagLyric(val source: String, val text: String, val timed: Boolean, val rank: Int)

object TagLyrics {
    private const val HEAD_LIMIT = 1_500_000
    private const val TAIL_LIMIT = 512_000
    private val LRC = Regex("""\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?\]""")
    private val LYRIC_KEYS = setOf(
        "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "LYRICS", "LRC",
        "SYNCEDLYRICS", "SYNCED LYRICS", "USLT", "©LYR",
    )

    /** 候选按优先级排序；无时间戳的纯文本补上时间轴，交给现有 parseLrc 滚动 */
    fun candidates(ctx: Context, uri: Uri, durationMs: Long): List<TagLyric> {
        val parts = readParts(ctx, uri) ?: return emptyList()
        val out = ArrayList<TagLyric>()
        out += id3v2At(parts.head, 0)
        val chunk = indexOf(parts.head, "ID3 ".toByteArray(), 0, 256 * 1024)
        if (chunk >= 0) out += id3v2At(parts.head, chunk + 8)
        out += flac(parts.head)
        out += ogg(parts.head)
        out += mp4(parts.head)
        out += mp4(parts.tail)
        out += ape(parts.tail)
        return out.map { c ->
            if (c.timed || LRC.containsMatchIn(c.text)) c
            else c.copy(text = toLrc(c.text, durationMs), timed = true)
        }.sortedBy { it.rank }
    }

    fun best(ctx: Context, uri: Uri, durationMs: Long): TagLyric? =
        candidates(ctx, uri, durationMs).firstOrNull()

    // ------------------------------------------------------------ 读取头尾
    private class Parts(val head: ByteArray, val tail: ByteArray)

    private fun readParts(ctx: Context, uri: Uri): Parts? {
        return try {
            val path = if (uri.scheme == "file") uri.path else null
            val f = path?.let { File(it) }?.takeIf { it.isFile }
            if (f != null) {
                val ch = FileInputStream(f).channel
                val total = f.length()
                val head = readAt(ch, 0L, minOf(total, HEAD_LIMIT.toLong()).toInt())
                val tn = if (total > HEAD_LIMIT.toLong()) minOf(total - HEAD_LIMIT.toLong(), TAIL_LIMIT.toLong()).toInt() else 0
                val tail = if (tn > 0) readAt(ch, total - tn.toLong(), tn) else ByteArray(0)
                ch.close()
                Parts(head, tail)
            } else {
                val afd = ctx.contentResolver.openAssetFileDescriptor(uri, "r") ?: return null
                val ch = FileInputStream(afd.fileDescriptor).channel
                val base = afd.startOffset
                val total = if (afd.length >= 0) afd.length else maxOf(0L, ch.size() - base)
                val head = readAt(ch, base, minOf(total, HEAD_LIMIT.toLong()).toInt())
                val tn = if (total > HEAD_LIMIT.toLong()) minOf(total - HEAD_LIMIT.toLong(), TAIL_LIMIT.toLong()).toInt() else 0
                val tail = if (tn > 0) readAt(ch, base + total - tn.toLong(), tn) else ByteArray(0)
                afd.close()
                Parts(head, tail)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readAt(ch: FileChannel, off: Long, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val b = ByteArray(n)
        ch.position(off)
        var done = 0
        while (done < n) {
            val r = ch.read(ByteBuffer.wrap(b, done, n - done))
            if (r <= 0) break
            done += r
        }
        return if (done == n) b else b.copyOf(done)
    }

    // ------------------------------------------------------------ ID3v2
    private fun id3v2At(buf: ByteArray, start: Int): List<TagLyric> {
        if (start + 10 > buf.size) return emptyList()
        if (b(buf, start) != 'I'.code || b(buf, start + 1) != 'D'.code || b(buf, start + 2) != '3'.code)
            return emptyList()
        val ver = b(buf, start + 3)
        if (ver !in 2..4) return emptyList()
        val flags = b(buf, start + 5)
        val size = if (ver == 4) syncsafe(buf, start + 6) else be32(buf, start + 6)
        if (size <= 0L || size > 64L * 1024 * 1024) return emptyList()
        val tagEnd = minOf(buf.size.toLong(), start + 10L + size).toInt()
        var pos = start + 10
        if (flags and 0x40 != 0) {
            val ext = if (ver == 4) syncsafe(buf, pos) else be32(buf, pos) + 4
            pos += maxOf(4, ext.toInt())
        }
        val out = ArrayList<TagLyric>()
        var guard = 0
        while (pos + 6 <= tagEnd && guard++ < 20000) {
            val idLen = if (ver == 2) 3 else 4
            val hdr = if (ver == 2) 6 else 10
            if (pos + hdr > tagEnd) break
            val id = ascii(buf, pos, idLen)
            if (id.isBlank() || id.all { it.code == 0 }) break
            val fsz = when (ver) {
                2 -> be24(buf, pos + 3).toInt()
                4 -> syncsafe(buf, pos + 4).toInt()
                else -> be32(buf, pos + 4).toInt()
            }
            val dp = pos + hdr
            val de = minOf(tagEnd, dp + fsz)
            if (fsz <= 0 || de <= dp || de > buf.size) break
            var frame = buf.copyOfRange(dp, de)
            if (flags and 0x80 != 0) frame = deUnsync(frame)
            when (id) {
                "USLT", "ULT" -> parseUslt(frame)?.let {
                    out += TagLyric("ID3v2 $id", it, LRC.containsMatchIn(it), 2)
                }
                "SYLT", "SLT" -> parseSylt(frame)?.let {
                    out += TagLyric("ID3v2 $id", it, true, 0)
                }
                "TXXX" -> parseTxxx(frame)?.let {
                    out += TagLyric("ID3v2 TXXX", it, LRC.containsMatchIn(it), 4)
                }
            }
            pos = de
        }
        return out
    }

    private fun parseUslt(f: ByteArray): String? {
        if (f.size < 5) return null
        val enc = b(f, 0)
        val st = skipDesc(f, 4, enc)
        if (st >= f.size) return null
        return decode(f.copyOfRange(st, f.size), enc).trim().takeIf { it.isNotBlank() }
    }

    private fun parseTxxx(f: ByteArray): String? {
        if (f.size < 3) return null
        val enc = b(f, 0)
        val st = skipDesc(f, 1, enc)
        if (st >= f.size) return null
        val de = st - if (enc == 1 || enc == 2) 2 else 1
        if (de < 1) return null
        val desc = decode(f.copyOfRange(1, de), enc)
        if (!desc.uppercase().contains("LYRIC")) return null
        return decode(f.copyOfRange(st, f.size), enc).trim().takeIf { it.isNotBlank() }
    }

    /** SYLT：enc+lang+format+type+desc，逐条 (文本 NUL + 4 字节时间) */
    private fun parseSylt(f: ByteArray): String? {
        if (f.size < 7) return null
        val enc = b(f, 0)
        val fmt = b(f, 4)
        var i = skipDesc(f, 6, enc)
        val wide = enc == 1 || enc == 2
        val term = if (wide) 2 else 1
        val sb = StringBuilder()
        var guard = 0
        while (i < f.size && guard++ < 20000) {
            var e = i
            if (wide) {
                while (e + 1 < f.size && !(b(f, e) == 0 && b(f, e + 1) == 0)) e += 2
            } else {
                while (e < f.size && b(f, e) != 0) e++
            }
            if (e <= i) break
            val text = decode(f.copyOfRange(i, e), enc).trim()
            val tp = e + term
            if (tp + 4 > f.size) break
            val raw = be32(f, tp)
            // format=2 是毫秒；format=1 是 MPEG 帧，按 44.1kHz 约 26ms/帧换算
            val ms = if (fmt == 2) raw else raw * 26L
            if (text.isNotEmpty()) sb.append('[').append(fmtTime(ms)).append(']').append(text).append('\n')
            i = tp + 4
        }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun skipDesc(f: ByteArray, from: Int, enc: Int): Int {
        var i = from
        if (enc == 1 || enc == 2) {
            while (i + 1 < f.size) {
                if (b(f, i) == 0 && b(f, i + 1) == 0) return i + 2
                i += 2
            }
        } else {
            while (i < f.size) {
                if (b(f, i) == 0) return i + 1
                i++
            }
        }
        return f.size
    }

    private fun decode(x: ByteArray, enc: Int): String = try {
        when (enc) {
            1 -> when {
                x.size >= 2 && b(x, 0) == 0xFF && b(x, 1) == 0xFE ->
                    String(x, 2, x.size - 2, Charsets.UTF_16LE)
                x.size >= 2 && b(x, 0) == 0xFE && b(x, 1) == 0xFF ->
                    String(x, 2, x.size - 2, Charsets.UTF_16BE)
                else -> String(x, Charsets.UTF_16LE)
            }
            2 -> String(x, Charsets.UTF_16BE)
            3 -> String(x, Charsets.UTF_8)
            else -> {
                val u = String(x, Charsets.UTF_8)
                if ('\uFFFD' in u) String(x, Charset.forName("GBK")) else u
            }
        }
    } catch (e: Exception) {
        String(x, Charsets.UTF_8)
    }

    private fun deUnsync(x: ByteArray): ByteArray {
        val out = ArrayList<Byte>(x.size)
        var i = 0
        while (i < x.size) {
            out.add(x[i])
            if (b(x, i) == 0xFF && i + 1 < x.size && b(x, i + 1) == 0) i++
            i++
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ FLAC / Ogg Vorbis Comment
    private fun flac(h: ByteArray): List<TagLyric> {
        if (h.size < 4 || ascii(h, 0, 4) != "fLaC") return emptyList()
        var p = 4
        val out = ArrayList<TagLyric>()
        var guard = 0
        while (p + 4 <= h.size && guard++ < 1000) {
            val last = (b(h, p) and 0x80) != 0
            val type = b(h, p) and 0x7f
            val len = be24(h, p + 1).toInt()
            val d = p + 4
            if (d + len > h.size) break
            if (type == 4) out += vorbis(h, d, minOf(h.size, d + len), "FLAC")
            p = d + len
            if (last) break
        }
        return out
    }

    private fun ogg(h: ByteArray): List<TagLyric> {
        val lim = minOf(h.size, 512 * 1024)
        var marker = indexOf(h, "OpusTags".toByteArray(), 0, lim)
        var mlen = 8
        if (marker < 0) {
            val vb = byteArrayOf(
                1, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(),
                'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(),
            )
            marker = indexOf(h, vb, 0, lim)
            mlen = 7
        }
        if (marker < 0) return emptyList()
        return vorbis(h, marker + mlen, h.size, "Ogg")
    }

    private fun vorbis(buf: ByteArray, st: Int, end: Int, src: String): List<TagLyric> {
        var p = st
        if (p + 4 > end) return emptyList()
        val vl = le32(buf, p)
        if (vl < 0L || p + 4L + vl > end.toLong()) return emptyList()
        p = (p + 4L + vl).toInt()
        if (p + 4 > end) return emptyList()
        val cnt = le32(buf, p)
        if (cnt < 0L || cnt > 10000L) return emptyList()
        p += 4
        return vorbisEntries(buf, p, end, cnt, src)
    }

    private fun vorbisEntries(buf: ByteArray, from: Int, end: Int, cnt: Long, src: String): List<TagLyric> {
        var p = from
        val out = ArrayList<TagLyric>()
        var guard = 0
        while (p + 4 <= end && out.size.toLong() < cnt && guard++ < 20000) {
            val l = le32(buf, p)
            if (l < 0L || p + 4L + l > end.toLong()) break
            p += 4
            val kv = String(buf, p, l.toInt(), Charsets.UTF_8)
            p += l.toInt()
            val eq = kv.indexOf('=')
            if (eq > 0) {
                val k = kv.substring(0, eq).uppercase()
                val v = kv.substring(eq + 1).trim()
                if (k in LYRIC_KEYS && v.isNotEmpty()) {
                    out += TagLyric("$src/$k", v, LRC.containsMatchIn(v), if (k.contains("SYNC")) 1 else 3)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------ MP4 / M4A ©lyr
    private fun mp4(buf: ByteArray): List<TagLyric> {
        val pat = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
        val out = ArrayList<TagLyric>()
        var from = 0
        var guard = 0
        while (guard++ < 20) {
            val i = indexOf(buf, pat, from, buf.size)
            if (i < 0) break
            from = i + 4
            val d = indexOf(buf, "data".toByteArray(), i, minOf(buf.size, i + 512))
            if (d < 4) continue
            val sz = be32(buf, d - 4)
            val ps = d + 4 + 8
            val pe = minOf(buf.size.toLong(), d - 4L + sz).toInt()
            if (sz < 16L || pe <= ps || ps > buf.size) continue
            val t = runCatching { String(buf, ps, pe - ps, Charsets.UTF_8) }.getOrNull()?.trim().orEmpty()
            if (t.isNotEmpty()) out += TagLyric("MP4 ©lyr", t, LRC.containsMatchIn(t), 3)
        }
        return out
    }

    // ------------------------------------------------------------ APEv2（文件尾）
    private fun ape(tail: ByteArray): List<TagLyric> {
        val out = ArrayList<TagLyric>()
        for (i in tail.size - 32 downTo 0) {
            if (ascii(tail, i, 8) != "APETAGEX") continue
            val size = le32(tail, i + 12)
            val count = le32(tail, i + 16)
            val fl = le32(tail, i + 20)
            if (size < 32L || size > tail.size.toLong()) break
            var p = tail.size - size.toInt()
            if ((fl and 0x80000000L) != 0L) p += 32
            val end = tail.size - 32
            var guard = 0
            while (p + 8 <= end && out.size.toLong() < count && guard++ < 20000) {
                val vs = le32(tail, p)
                p += 8
                var ke = p
                while (ke < end && b(tail, ke) != 0) ke++
                val key = ascii(tail, p, ke - p).uppercase()
                p = ke + 1
                if (vs < 0L || p.toLong() + vs > end.toLong()) break
                if (key in LYRIC_KEYS) {
                    val v = runCatching { String(tail, p, vs.toInt(), Charsets.UTF_8) }.getOrNull()?.trim().orEmpty()
                    if (v.isNotEmpty()) out += TagLyric("APEv2 $key", v, LRC.containsMatchIn(v), 3)
                }
                p += vs.toInt()
            }
            break
        }
        return out
    }

    // ------------------------------------------------------------ 纯文本补时间轴
    private fun toLrc(text: String, durationMs: Long): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val d = if (durationMs > 0) durationMs else lines.size * 3500L
        val start = minOf(4000L, d / 12L)
        val step = maxOf(1200L, (d - start) / lines.size.toLong())
        val sb = StringBuilder()
        lines.forEachIndexed { i, l ->
            sb.append('[').append(fmtTime(start + i.toLong() * step)).append(']').append(l).append('\n')
        }
        return sb.toString()
    }

    private fun fmtTime(ms: Long): String {
        val m = ms / 60000L
        val s = (ms % 60000L) / 1000L
        val c = ms % 1000L
        return "%02d:%02d.%03d".format(m, s, c)
    }

    // ------------------------------------------------------------ 字节工具
    private fun b(x: ByteArray, i: Int): Int = if (i in x.indices) x[i].toInt() and 0xff else 0
    private fun be32(x: ByteArray, o: Int): Long =
        (b(x, o).toLong() shl 24) or (b(x, o + 1).toLong() shl 16) or
            (b(x, o + 2).toLong() shl 8) or b(x, o + 3).toLong()
    private fun be24(x: ByteArray, o: Int): Long =
        (b(x, o).toLong() shl 16) or (b(x, o + 1).toLong() shl 8) or b(x, o + 2).toLong()
    private fun le32(x: ByteArray, o: Int): Long =
        b(x, o).toLong() or (b(x, o + 1).toLong() shl 8) or
            (b(x, o + 2).toLong() shl 16) or (b(x, o + 3).toLong() shl 24)
    private fun syncsafe(x: ByteArray, o: Int): Long =
        ((b(x, o).toLong() and 0x7fL) shl 21) or ((b(x, o + 1).toLong() and 0x7fL) shl 14) or
            ((b(x, o + 2).toLong() and 0x7fL) shl 7) or (b(x, o + 3).toLong() and 0x7fL)
    private fun ascii(x: ByteArray, o: Int, n: Int): String {
        val a = ByteArray(n)
        for (i in 0 until n) a[i] = (b(x, o + i) and 0x7f).toByte()
        return String(a, Charsets.ISO_8859_1)
    }
    private fun indexOf(h: ByteArray, n: ByteArray, from: Int, limit: Int): Int {
        val end = minOf(limit, h.size)
        var i = maxOf(0, from)
        while (i + n.size <= end) {
            var ok = true
            for (j in n.indices) {
                if (h[i + j] != n[j]) { ok = false; break }
            }
            if (ok) return i
            i++
        }
        return -1
    }
}
