package de.myocd.gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 专辑 / 歌手简介页。
 *
 * 专辑：点专辑页的封面或标题进来，显示大图、标题、歌手、简介。
 * 歌手：点歌手页的大图或名字进来，显示大图、名字、简介、详细信息。
 * ugc 简介仅在带登录凭证时请求；歌手详细信息（artist/desc）不要求登录。
 * 大图长按弹下载选项，所有简介 / 详情文字长按整段复制。
 */
@Composable
fun IntroPage(vm: MainViewModel, s: Screen) {
    val go = LocalGo.current
    LaunchedEffect(s, vm.nmLogged) { vm.loadIntro(s) }
    val st = vm.intro
    val isAlbum = s is Screen.AlbumIntro
    val kind = if (isAlbum) "album" else "artist"
    if (st == null || st.kind != kind) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = go.primary)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    IntroImage(vm, st.cover, st.title.ifBlank { if (isAlbum) "专辑封面" else "歌手图片" })
                    Spacer(Modifier.height(16.dp))
                    CopyBlock(
                        vm,
                        st.title.ifBlank { if (isAlbum) "未知专辑" else "未知歌手" },
                        20, FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (isAlbum && st.artist.isNotBlank()) {
                        Txt(
                            "歌手：${st.artist}", 14,
                            Modifier.clickable { vm.openArtistByName(st.artist) }
                                .padding(vertical = 8.dp, horizontal = 12.dp),
                            color = go.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Txt("长按文字可复制，长按图片可下载", 11, color = go.muted)
                    Spacer(Modifier.height(16.dp))
                }
            }

            item {
                InfoCard("简介") {
                    when {
                        !vm.nmLogged -> Txt("登录后才能查看简介", 13, color = go.muted)
                        st.descLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = go.primary,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Txt("正在加载简介…", 13, color = go.muted)
                        }
                        st.desc.isNotBlank() -> CopyBlock(vm, st.desc, 14)
                        else -> Txt("暂无简介", 13, color = go.muted)
                    }
                }
            }

            if (!isAlbum) {
                item {
                    Spacer(Modifier.height(16.dp))
                    InfoCard("歌手详细信息") {
                        when {
                            st.detailLoading -> Txt("正在加载详细信息…", 13, color = go.muted)
                            st.brief.isBlank() && st.sections.isEmpty() -> Txt(
                                if (st.detailOk) "暂无详细信息" else "详细信息获取失败",
                                13, color = go.muted,
                            )
                            st.brief.isNotBlank() -> {
                                Txt("人物简述", 13, weight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                CopyBlock(vm, st.brief, 13)
                            }
                            else -> Unit
                        }
                    }
                }

                // 每段单独一个 item：演艺经历这类长文按屏懒加载，不会一次性铺满
                itemsIndexed(st.sections, key = { i, _ -> "sec$i" }) { _, sec ->
                    Spacer(Modifier.height(12.dp))
                    InfoCard(sec.first) {
                        CopyBlock(vm, sec.second, 13)
                    }
                }

                if (st.topics.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        InfoCard("相关专栏") {
                            st.topics.forEach { t ->
                                CopyBlock(vm, t, 13, color = go.muted)
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 带标题的卡片容器 */
@Composable
private fun InfoCard(title: String, body: @Composable () -> Unit) {
    val go = LocalGo.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(go.surfaceHi)
            .padding(14.dp),
    ) {
        if (title.isNotBlank()) {
            Txt(title, 15, weight = FontWeight.SemiBold, color = go.primary)
            Spacer(Modifier.height(8.dp))
        }
        body()
    }
}

/** 长按整段复制到剪贴板 */
@Composable
private fun CopyBlock(
    vm: MainViewModel,
    text: String,
    size: Int = 14,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalGo.current.on,
) {
    if (text.isBlank()) return
    Txt(
        text, size,
        Modifier.pointerInput(text) {
            detectTapGestures(onLongPress = { vm.copyText(text) })
        },
        weight, color,
    )
}

/** 大图：长按弹出下载选项 */
@Composable
private fun IntroImage(vm: MainViewModel, cover: String?, name: String) {
    val go = LocalGo.current
    var ask by remember { mutableStateOf(false) }
    val w = minOf(LocalConfiguration.current.screenWidthDp * 0.72f, 320f).dp
    Box(
        Modifier.size(w).pointerInput(Unit) {
            detectTapGestures(onLongPress = { ask = true })
        },
    ) {
        Cover(cover, Modifier.fillMaxSize(), 24.dp)
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Txt("图片", 16, weight = FontWeight.SemiBold) },
            text = { Txt("把这张图片保存到 Download/GoMusic/图片？", 13) },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    vm.saveIntroImage(cover, name)
                }) { Txt("下载", 14, color = go.primary) }
            },
            dismissButton = {
                TextButton(onClick = { ask = false }) { Txt("取消", 14, color = go.muted) }
            },
        )
    }
}
