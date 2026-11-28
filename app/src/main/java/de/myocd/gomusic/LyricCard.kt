package de.myocd.gomusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream

/**
 * 26.11.89：歌词卡片。
 *
 * 用 Android Canvas 直接画一张竖版 PNG（不依赖 Compose、不依赖网络图片），
 * 写到应用私有 cache/share，交给 FileProvider 以 image/png 分享出去。
 * 中文字体用系统 sans-serif（系统会自动回退到中文字形），不需要额外资源。
 */
object LyricCard {

    private const val W = 1080
    private const val H = 1440
    private const val PAD = 96f

    fun render(ctx: Context, lines: List<String>, title: String, artist: String): File {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // 背景：深蓝紫渐变，避免歌词在不同主题下看不清
        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, W.toFloat(), H.toFloat(),
                Color.parseColor("#141A2E"), Color.parseColor("#2B1B3D"),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)

        val accent = Paint().apply { color = Color.parseColor("#8AA6FF"); isAntiAlias = true }
        c.drawRoundRect(PAD, 92f, PAD + 96f, 100f, 4f, 4f, accent)

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 56f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isAntiAlias = true
        }
        val subPaint = Paint().apply {
            color = Color.parseColor("#B9C2DA")
            textSize = 32f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isAntiAlias = true
        }
        val linePaint = Paint().apply {
            color = Color.parseColor("#F2F4FF")
            textSize = 44f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isAntiAlias = true
        }

        var y = 180f
        // 歌名最多两行
        wrap(title.trim().ifBlank { "歌词卡片" }, titlePaint, W - PAD * 2).take(2).forEach {
            c.drawText(it, PAD, y, titlePaint)
            y += 72f
        }
        y += 12f
        c.drawText(artist.trim().take(40), PAD, y, subPaint)
        y += 56f

        val sep = Paint().apply { color = Color.parseColor("#44506E"); strokeWidth = 2f }
        c.drawLine(PAD, y, W - PAD, y, sep)
        y += 76f

        // 歌词正文：按可用宽度折行，从上往下顺序画，超出高度就截断
        val maxY = H - 200f
        var cut = false
        for (raw in lines) {
            if (cut) break
            for (seg in wrap(raw, linePaint, W - PAD * 2)) {
                if (y + 66f > maxY) { cut = true; break }
                c.drawText(seg, PAD, y, linePaint)
                y += 66f
            }
            if (!cut) y += 18f // 行间空隙
        }

        val foot = Paint().apply {
            color = Color.parseColor("#8C97B5")
            textSize = 26f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isAntiAlias = true
        }
        c.drawText("GoMusic · 歌词卡片", PAD, H - 92f, foot)
        c.drawText("♪", W - PAD - 40f, H - 92f, foot)

        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "lyric_card_${System.currentTimeMillis()}.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 只留最近 6 张，别把缓存撑大
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
        bmp.recycle()
        return out
    }

    /** 按字符测量折行，中文/英文混排都不会把一个字劈开 */
    private fun wrap(text: String, p: Paint, maxW: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (p.measureText(sb.toString() + ch) > maxW && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            sb.append(ch)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
