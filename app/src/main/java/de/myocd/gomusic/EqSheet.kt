@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package de.myocd.gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 均衡器面板。对应规范 5「设计师交付与界面建议」：
 *  - 默认层：预设名 + 一句话听感 + 开关 + 强度滑块
 *  - 高级层：10 段增益、Q 值说明、HPF/LPF 提示、设备校准
 *  - 危险操作提示：低频 >4.5 / 中频 >6 / 高频 >3 dB
 *  - A/B 对比默认做响度匹配，切换预设走 30ms 淡入淡出
 *  - 创意音效单独成组并标注默认关闭；深夜聆听标注是动态功能
 */
@Composable
fun EqSheet(vm: MainViewModel) {
    val go = LocalGo.current
    val cfg = vm.eqConfig
    val set: (EqConfig) -> Unit = { vm.setEq(it) }

    GoSheet({ vm.showEq = false }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("均衡器", 17, Modifier.weight(1f), weight = FontWeight.SemiBold)
                Switch(
                    checked = cfg.enabled,
                    onCheckedChange = { set(cfg.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnPrimary, checkedTrackColor = go.primary,
                        uncheckedThumbColor = go.muted, uncheckedTrackColor = go.surfaceHi,
                    ),
                )
            }
            Txt("按场景挑一条曲线，再调强度；参数是起点值，按你的耳机微调", 12, color = go.muted)
            VS(12)

            // ---------- 强度 ----------
            Txt("强度 ${(cfg.strength * 100).toInt()}%", 13, weight = FontWeight.Medium)
            Slider(
                value = cfg.strength,
                onValueChange = { set(cfg.copy(strength = (it * 20).toInt() / 20f)) },
                valueRange = 0f..1f,
                steps = 20,
                modifier = Modifier.fillMaxWidth(),
            )
            Txt("每次手动调整按 0.5~1dB 步进；强度按比例缩放全部增益", 11, color = go.muted)
            VS(10)

            // ---------- 危险提示 ----------
            val warns = cfg.warnings()
            if (warns.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF7A2E2E).copy(alpha = 0.35f))
                        .padding(10.dp),
                ) {
                    Txt("增益提醒", 12, color = Color(0xFFFFB4AB), weight = FontWeight.SemiBold)
                    warns.forEach { Txt("· $it", 11, color = Color(0xFFFFB4AB)) }
                }
                VS(10)
            }

            // ---------- 预设（按场景分组） ----------
            EqGroup.values().forEach { group ->
                val items = EQ_PRESETS.filter { it.group == group }
                if (items.isEmpty()) return@forEach
                Txt(group.label, 13, color = go.primary, weight = FontWeight.SemiBold)
                VS(4)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items.forEach { p ->
                        val sel = cfg.presetId == p.id
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) go.primary.copy(alpha = 0.22f) else go.surfaceHi)
                                .clickable {
                                    // 创意音效默认关闭，选中时才开启；切换由引擎做 30ms 淡入
                                    set(cfg.copy(presetId = p.id, enabled = true))
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Txt(p.name, 13, color = if (sel) go.primary else go.on, weight = FontWeight.Medium)
                            Txt(p.desc, 11, color = go.muted)
                            if (p.creative) Txt("创意音效 · 默认关闭", 10, color = Color(0xFFFFB74D))
                            if (p.dynamicLoudness) Txt("动态等响度 · 随音量变化", 10, color = Color(0xFF81D4FA))
                        }
                    }
                }
                VS(10)
            }

            // 复古音效的两个变体
            if (cfg.presetId == "retro") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("复古变体", 12, Modifier.weight(1f), color = go.muted)
                    GoButton("电话音效", primary = !cfg.retroRadio) { set(cfg.copy(retroRadio = false)) }
                    HS(6)
                    GoButton("老式收音机", primary = cfg.retroRadio) { set(cfg.copy(retroRadio = true)) }
                }
                Txt("HPF 300Hz / LPF 3.4kHz（收音机 250Hz / 3kHz），切换有淡入，不会爆音", 11, color = go.muted)
                VS(10)
            }

            // 深夜聆听：动态等响度，音量越小两端补偿越多
            if (cfg.presetId == "night") {
                Txt("音量 ${(cfg.nightVolume * 100).toInt()}%（等响度补偿的输入）", 12, color = go.muted)
                Slider(
                    value = cfg.nightVolume,
                    onValueChange = { set(cfg.copy(nightVolume = it)) },
                    valueRange = 0.05f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Txt("这是动态功能，不是固定 EQ；不会与「沉重低音」叠加", 11, color = go.muted)
                VS(10)
            }

            // ---------- 自定义 10 段 ----------
            Txt("自定义", 13, color = go.primary, weight = FontWeight.SemiBold)
            Txt("步进 0.5dB，范围 ±12dB；Q=1 峰值滤波，HPF/LPF 由所选预设决定", 11, color = go.muted)
            VS(4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoButton("使用自定义曲线", primary = cfg.presetId == "custom") {
                    set(cfg.copy(presetId = "custom", enabled = true))
                }
                HS(6)
                GoButton("恢复默认") { set(EQ_FLAT) }
            }
            VS(6)
            EQ_BANDS_HZ.forEachIndexed { i, hz ->
                val label = if (hz >= 1000f) "${(hz / 1000f).toInt()}k" else hz.toInt().toString()
                val db = cfg.customDb.getOrElse(i) { 0f }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(label, 11, Modifier.size(34.dp), color = go.muted, tnum = true)
                    Slider(
                        value = db,
                        onValueChange = { v ->
                            val stepped = (v * 2).toInt() / 2f   // 0.5dB 步进
                            val l = cfg.customDb.toMutableList()
                            while (l.size < 10) l += 0f
                            l[i] = stepped.coerceIn(-12f, 12f)
                            set(cfg.copy(presetId = "custom", customDb = l, enabled = true))
                        },
                        valueRange = -12f..12f,
                        steps = 47,
                        modifier = Modifier.weight(1f).height(28.dp),
                    )
                    Txt(
                        (if (db > 0) "+" else "") + "${(db * 10).toInt() / 10f}dB",
                        11, Modifier.size(48.dp), color = go.muted, tnum = true,
                    )
                }
            }
            VS(10)

            // ---------- A/B 对比与响度匹配 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("A/B 响度匹配", 12, Modifier.weight(1f))
                Switch(
                    checked = cfg.loudnessMatch,
                    onCheckedChange = { set(cfg.copy(loudnessMatch = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnPrimary, checkedTrackColor = go.primary,
                        uncheckedThumbColor = go.muted, uncheckedTrackColor = go.surfaceHi,
                    ),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("B 侧", 12, color = go.muted)
                HS(6)
                GoButton(if (cfg.compareId.isBlank()) "平直（原声）" else eqPresetById(cfg.compareId)?.name ?: "预设") {
                    val next = when (cfg.compareId) {
                        "" -> "vocal"
                        "vocal" -> "bass"
                        "bass" -> ""
                        else -> ""
                    }
                    set(cfg.copy(compareId = next))
                }
                HS(6)
                GoButton("试听 B") { vm.eqCompareB() }
            }
            Txt("对比时保持响度一致，避免「更响 = 更好」的误判", 11, color = go.muted)
            VS(10)

            // ---------- 设备适配 ----------
            Txt("设备校准", 13, color = go.primary, weight = FontWeight.SemiBold)
            Txt("同一条曲线在不同耳机/音箱/车上要微调，不硬编码成唯一值", 11, color = go.muted)
            VS(4)
            Row {
                listOf("耳机", "音箱", "车载").forEachIndexed { i, n ->
                    GoButton(n, primary = cfg.device == i) { set(cfg.copy(device = i)) }
                    if (i < 2) HS(6)
                }
            }
            VS(12)
            Txt(
                "均衡器只能补偿频响，修不了硬件缺陷；低频过量会失真、高频过量会放大底噪。",
                11, color = go.muted,
            )
            VS(8)
        }
    }
}
