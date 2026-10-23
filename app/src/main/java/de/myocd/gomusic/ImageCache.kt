package de.myocd.gomusic

import android.content.Context
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
    private const val DIR = "imgcache"
    private const val MAX_BYTES = 200L * 1024 * 1024

    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir == null) {
            dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        }
    }

    private fun fileOf(url: String): File {
        val md = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        val name = md.joinToString("") { "%02x".format(it) }
        return File(dir ?: return File(""), name)
    }

    /** 已缓存则返回本地文件绝对路径，否则 null */
    fun cached(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith("http")) return null
        val f = fileOf(url)
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    /** 后台把网络图片存到缓存目录（已存在则跳过） */
    fun store(url: String?) {
        if (url.isNullOrBlank()) return
        if (!url.startsWith("http")) return
        val f = fileOf(url)
        if (f.exists() && f.length() > 0) return
        try {
            val bytes = Api.bytes(url) ?: return
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
