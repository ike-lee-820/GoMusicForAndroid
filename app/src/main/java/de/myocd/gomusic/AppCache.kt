package de.myocd.gomusic

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * 可清理的缓存目录。
 *
 * 更新安装包、翻译缓存、封面缓存、播放整首缓存都放 ctx.cacheDir（应用缓存目录）：
 * 系统「清除缓存」能一次清干净，应用卸载也一并带走；
 * 而数据目录（getExternalFilesDir / SharedPreferences）清起来要手动找，
 * 还容易越堆越大。真正不能丢的东西（歌单、下载记录、登录态）仍留在数据目录。
 */
object AppCache {
    /** 更新安装包 */
    private const val DIR_UPDATE = "update"
    /** 歌词翻译缓存 */
    private const val DIR_TRANS = "translate"
    /** 播放整首缓存（PlayCache 用） */
    private const val DIR_PLAY = "playcache"

    fun updateDir(ctx: Context): File = File(ctx.cacheDir, DIR_UPDATE).apply { mkdirs() }

    /** 翻译缓存文件：整篇译文按 ssid 存一份 JSON */
    private fun transFile(ctx: Context): File =
        File(File(ctx.cacheDir, DIR_TRANS).apply { mkdirs() }, "lyric_trans.json")

    /**
     * 读翻译缓存。返回 null 表示还没有缓存文件（调用方据此决定是否迁移旧数据）。
     */
    @Synchronized
    fun readTrans(ctx: Context): Map<String, List<String>>? {
        val f = transFile(ctx)
        if (!f.exists()) return null
        val t = runCatching { f.readText() }.getOrNull() ?: return null
        if (t.isBlank()) return null
        return runCatching {
            Api.json.decodeFromString<Map<String, List<String>>>(t)
        }.getOrNull()
    }

    @Synchronized
    fun writeTrans(ctx: Context, v: Map<String, List<String>>) {
        runCatching {
            val f = transFile(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            // 显式给序列化器：Map 这种泛型容器，只靠实参推不出 T，
            // 编译器会去匹配 (serializer, value) 那个重载然后报参数缺失
            val ser = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
            tmp.writeText(Api.json.encodeToString(ser, v))
            tmp.renameTo(f)
        }
    }

    /** 清空所有可清理缓存（设置里「清除缓存」用），返回释放的字节数 */
    fun clear(ctx: Context): Long {
        var freed = 0L
        listOf(DIR_UPDATE, DIR_TRANS, DIR_PLAY, ImageCache.DIR_NAME).forEach { d ->
            val dir = File(ctx.cacheDir, d)
            dir.walkTopDown().forEach { f ->
                if (f.isFile) { freed += f.length(); runCatching { f.delete() } }
            }
        }
        ImageCache.reset()
        ImageCache.init(ctx)
        return freed
    }
}
