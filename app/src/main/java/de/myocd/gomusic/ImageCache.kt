package de.myocd.gomusic

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/**
 * 封面磁盘缓存。
 *
 * 只缓存 http(s) 图片；data: URI 本来就在内存里，不落盘
 * （它们当缓存键会拖慢查找，之前踩过这个坑）。
 * 加载过的封面下次直接从缓存文件读，不用再走网络。
 */
object ImageCache {
    /** 目录名：AppCache 清理时要一并清掉，所以对外可见 */
    const val DIR_NAME = "imgcache"
    private const val DIR = DIR_NAME
    private const val MAX_BYTES = 200L * 1024 * 1024

    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir == null) {
            dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        }
    }

    /** 清空后重新指向目录（AppCache.clear 用） */
    fun reset() { dir = null }

    private fun fileOf(url: String): File {
        val md = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        val name = md.joinToString("") { "%02x".format(it) }
        return File(dir ?: return File(""), name)
    }

    /** 已缓存则返回本地文件绝对路径，否则 null */
    fun cached(url: String?): String? = cached(url, 0)

    /**
     * 26.11.55：带最小边长校验的缓存命中。
     * 早期版本缓存过 300×300 / 180×180 的缩略图，后来即使换了高清 URL，
     * 只要 key 相同就会一直复用那张糊图。这里解码 bounds 后直接淘汰过小文件。
     */
    fun cached(url: String?, minSide: Int): String? {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith("http")) return null
        val f = fileOf(url)
        if (!f.exists() || f.length() <= 0) return null
        if (minSide > 0) {
            val ok = try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, o)
                maxOf(o.outWidth, o.outHeight) >= minSide
            } catch (e: Exception) { false }
            if (!ok) { f.delete(); return null }
        }
        return f.absolutePath
    }

    /** 后台把网络图片存到缓存目录（已存在则跳过） */
    fun store(url: String?) {
        if (url.isNullOrBlank()) return
        if (!url.startsWith("http")) return
        val f = fileOf(url)
        if (f.exists() && f.length() > 0) return
        try {
            val bytes = Http.bytes(url) ?: return
            if (bytes.isEmpty()) return
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (tmp.length() > 0) tmp.renameTo(f)
            else tmp.delete()
        } catch (e: Exception) {
            // 缓存失败不影响显示
        }
    }

    /** 超过上限时清理一部分老文件 */
    fun trim() {
        try {
            val d = dir ?: return
            val files = d.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_BYTES) return
            files.sortedBy { it.lastModified() }.forEach {
                if (total <= MAX_BYTES) return
                val len = it.length()
                if (it.delete()) total -= len
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}
