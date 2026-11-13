package de.myocd.gomusic

/**
 * 无 AI 人声减弱（可约等于伴奏）：中置抵消 + 低频回补。
 *
 * 人声、主唱通常混在正中央，左右声道能量接近；伴奏与空间信息更多在两侧。
 * 用标准 karaoke 矩阵把中置成分按强度抵消，再把 100Hz 以下的低频补回，
 * 避免鼓与贝斯一起被抵消后声音发薄。全程逐样本 O(n)，离线处理整首歌，
 * 不占音频回调线程，也不依赖任何训练数据或模型。
 *
 * 更重的 2DFT / Adaptive REPET 原生实现见 app/src/main/cpp，
 * 按该目录 README 启用后即可替换这里的内核，接口保持一致。
 */
object VocalReduce {
    fun process(pcm: ShortArray, frames: Int, channels: Int, strength: Float): ShortArray {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f || frames <= 0) return pcm
        val out = ShortArray(pcm.size)

        if (channels < 2) {
            // 单声道没有左右差可做中置抵消。旧版在这里直接 return pcm，
            // 结果「减弱人声」对单声道音源完全无效。改用一阶低通拆出中频，
            // 按强度衰减 300Hz 以上的人声主要能量，低频与极高频保留。
            var lp = 0f
            val a = 0.045f
            for (f in 0 until frames) {
                val i = f * channels
                val x = pcm[i].toFloat() / 32768f
                lp += a * (x - lp)
                val mid = x - lp
                val v = (lp + mid * (1f - s * 0.85f)).coerceIn(-1f, 1f)
                out[i] = (v * 32767f).toInt().toShort()
                for (ch in 1 until channels) out[i + ch] = pcm[i + ch]
            }
            return out
        }

        var lp = 0f
        val a = 0.015f            // 约 100Hz 以下，鼓/贝斯所在的最低区
        val bassGain = 0.9f
        for (f in 0 until frames) {
            val i = f * channels
            val l = pcm[i].toFloat() / 32768f
            val r = pcm[i + 1].toFloat() / 32768f
            val mid = (l + r) * 0.5f
            lp += a * (mid - lp)
            // s=1 → outL=L-R、outR=R-L，中置人声被抵消；s=0 → 原声。
            // 0.85 系数给 side 留出余量，避免两侧反相信号叠加后削波。
            val sideL = l - r
            val sideR = r - l
            val keep = 1f - s
            val bass = lp * bassGain * s
            val vl = (keep * l + s * sideL * 0.85f + bass).coerceIn(-1f, 1f)
            val vr = (keep * r + s * sideR * 0.85f + bass).coerceIn(-1f, 1f)
            out[i] = (vl * 32767f).toInt().toShort()
            out[i + 1] = (vr * 32767f).toInt().toShort()
            for (ch in 2 until channels) out[i + ch] = pcm[i + ch]
        }
        return out
    }
}
