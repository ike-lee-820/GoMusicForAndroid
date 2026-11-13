package de.myocd.gomusic

/**
 * 无 AI 人声减弱（可约等于伴奏）：中置抵消 + 低频回补。
 *
 * 人声、主唱通常混在正中央，左右声道能量接近；伴奏与空间信息更多在两侧。
 * 把信号拆成 mid=(L+R)/2 与 side=(L-R)/2，按强度衰减 mid、保留 side，
 * 就能连续地从「原声」过渡到「近似伴奏」，不需要任何训练数据或模型。
 *
 * mid 里也包含低频（鼓、贝斯通常同样居中），直接全砍会让声音发薄，
 * 所以对 mid 做一次一阶低通，把 150Hz 以下的能量按强度补回两个声道。
 * 全程逐样本 O(n)，离线处理整首歌不占音频回调线程。
 *
 * 更重的 2DFT / Adaptive REPET 原生实现见 app/src/main/cpp，
 * 按该目录 README 启用后即可替换这里的内核，接口保持一致。
 */
object VocalReduce {
    fun process(pcm: ShortArray, frames: Int, channels: Int, strength: Float): ShortArray {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f || channels < 2) return pcm
        val out = ShortArray(pcm.size)
        var lp = 0f
        val a = 0.02f              // 44.1kHz 下约 140Hz 的低通系数
        val bassKeep = 0.85f
        for (f in 0 until frames) {
            val i = f * channels
            val l = pcm[i].toFloat() / 32768f
            val r = pcm[i + 1].toFloat() / 32768f
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f
            lp += a * (mid - lp)
            val bass = lp * bassKeep * s
            val keep = mid * (1f - s)
            out[i] = ((side + keep + bass).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            out[i + 1] = ((-side + keep + bass).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            for (ch in 2 until channels) out[i + ch] = pcm[i + ch]
        }
        return out
    }
}
