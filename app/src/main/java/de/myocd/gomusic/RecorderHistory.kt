package de.myocd.gomusic

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 一条录制记录：状态 + 进度 + 成品位置，Service 写、设置页读 */
@Serializable
data class RecRecord(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val style: String = "经典",
    val quality: String = "",
    val resolution: Int = 1080,
    val aspect: String = "16:9",
    val fps: Int = 30,
    val startedAt: Long = 0L,
    /** 进行中 / 已完成 / 失败 / 已取消 */
    val state: String = "进行中",
    val progress: Int = 0,
    val output: String = "",
    val note: String = "",
    /** 三段式生成的当前阶段，例如「步骤 1/3 · 下载素材」 */
    val stage: String = "",
    /** 当前阶段的详细进度文字，通知 / 录制页 / 列表都显示 */
    val detail: String = "",
    val finishedAt: Long = 0L,
)

/** 全进程共享的录制列表。每次变更同时落盘，Service 与界面在同一进程实时可见。 */
object RecorderHistory {
    private const val SP = "gomusic_rec_history"
    private const val KEY = "records"
    private const val MAX = 200
    private val json = Api.json
    private val _items = mutableStateListOf<RecRecord>()
    val items: List<RecRecord> get() = _items

    fun load(ctx: Context) {
        val list = runCatching {
            val raw = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
            if (raw.isBlank()) emptyList() else json.decodeFromString<List<RecRecord>>(raw)
        }.getOrDefault(emptyList())
        _items.clear()
        _items.addAll(list)
    }

    fun add(ctx: Context, r: RecRecord) {
        _items.add(0, r)
        save(ctx)
    }

    fun update(ctx: Context, id: String, f: (RecRecord) -> RecRecord) {
        val i = _items.indexOfFirst { it.id == id }
        if (i < 0) return
        _items[i] = f(_items[i])
        save(ctx)
    }

    fun remove(ctx: Context, id: String) {
        _items.removeAll { it.id == id }
        save(ctx)
    }

    fun clear(ctx: Context) {
        _items.clear()
        save(ctx)
    }

    private fun save(ctx: Context) {
        runCatching {
            val list = _items.take(MAX)
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString(KEY, json.encodeToString(list)).apply()
        }
    }
}
