package de.myocd.gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染：把更新说明的 md 原文排成标题、列表、表格、代码块等样式。
 *
 * 以前 UpdateUi 直接用 Txt(notes) 把 md 当纯文本显示，#、|、**、- 这些符号
 * 全部原样堆在屏幕上，看起来既不像文档也像被截断。这里不引入第三方解析库
 * （避免 CI 新增依赖），只覆盖更新说明实际用到的语法；未识别的行按正文显示。
 * 「复制全文」按钮仍然复制原始 md，排版只影响显示，不影响内容。
 */
@Composable
fun MarkdownDoc(src: String, modifier: Modifier = Modifier) {
    val go = LocalGo.current
    val lines = remember(src) { src.lines() }
    Column(modifier) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            when {
                line.isBlank() -> {
                    Spacer(Modifier.height(6.dp))
                    i++
                }
                line == "---" || line == "***" || line == "___" -> {
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(go.muted.copy(alpha = 0.35f)),
                    )
                    Spacer(Modifier.height(8.dp))
                    i++
                }
                line.trimStart().startsWith("```") -> {
                    val sb = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        sb.append(lines[i]).append('\n')
                        i++
                    }
                    if (i < lines.size) i++
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(go.bg).padding(10.dp),
                    ) {
                        Text(
                            sb.toString().trimEnd(),
                            color = go.on,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                line.startsWith("# ") -> {
                    MdHeading(inlineMd(line.removePrefix("# ")), 20, FontWeight.Bold, go.on)
                    i++
                }
                line.startsWith("## ") -> {
                    MdHeading(inlineMd(line.removePrefix("## ")), 16, FontWeight.Bold, go.primary)
                    i++
                }
                line.startsWith("### ") -> {
                    MdHeading(inlineMd(line.removePrefix("### ")), 14, FontWeight.SemiBold, go.on)
                    i++
                }
                line.startsWith("> ") -> {
                    Row(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.width(3.dp).height(18.dp)
                                .background(go.muted.copy(alpha = 0.5f)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            inlineMd(line.removePrefix("> ")),
                            color = go.muted,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    i++
                }
                line.trimStart().startsWith("|") && line.trimEnd().endsWith("|") -> {
                    val rows = mutableListOf<String>()
                    while (i < lines.size) {
                        val r = lines[i].trim()
                        if (!r.startsWith("|") || !r.endsWith("|")) break
                        val cells = r.trim('|').split('|').map { it.trim() }
                        val isSep = cells.isNotEmpty() && cells.all {
                            it.all { c -> c == '-' || c == ':' || c == ' ' }
                        }
                        if (!isSep) rows += r
                        i++
                    }
                    rows.forEachIndexed { idx, r ->
                        val cells = r.trim('|').split('|')
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            cells.forEach { c ->
                                Text(
                                    inlineMd(c.trim()),
                                    color = if (idx == 0) go.primary else go.on,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    MdBullet("•", inlineMd(line.substring(2)))
                    i++
                }
                isNumbered(line) -> {
                    val dot = line.indexOf('.')
                    MdBullet(
                        line.substring(0, dot + 1),
                        inlineMd(line.substring(dot + 1).trimStart()),
                    )
                    i++
                }
                else -> {
                    Text(
                        inlineMd(line),
                        color = go.on,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    i++
                }
            }
        }
    }
}

private fun isNumbered(s: String): Boolean {
    val dot = s.indexOf('.')
    if (dot <= 0) return false
    return s.substring(0, dot).all { it.isDigit() } &&
        dot + 1 < s.length && s[dot + 1] == ' '
}

@Composable
private fun MdHeading(
    text: AnnotatedString,
    size: Int,
    weight: FontWeight,
    color: Color,
) {
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size + 6).sp,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun MdBullet(mark: String, text: AnnotatedString) {
    val go = LocalGo.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            mark,
            color = go.primary,
            fontSize = 13.sp,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text,
            color = go.on,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 行内语法：**粗体** 与 `代码`。其余字符原样保留。 */
private fun inlineMd(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(s[i].toString())
                    i++
                }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.White.copy(alpha = 0.10f),
                        ),
                    ) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(s[i].toString())
                    i++
                }
            }
            else -> {
                append(s[i].toString())
                i++
            }
        }
    }
}
