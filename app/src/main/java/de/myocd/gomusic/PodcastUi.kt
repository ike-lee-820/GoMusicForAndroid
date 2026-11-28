package de.myocd.gomusic

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 26.11.82：播客页（独立搜索，不进主搜索页）。
 *
 * 顶部与其余整页共用 HigPageHeader / HigSectionTitle；播客搜索只在
 * 本页的搜索框发起（/voicelist/search），主搜索页完全不碰播客。
 * 详情里可播放声音、按 /voicelist/list/search 筛选、调整顺序、删除、
 * 以及 multipart 上传声音（/voice/upload）。
 */
@Composable
fun PodcastPage(vm: MainViewModel) {
    val go = LocalGo.current
    LaunchedEffect(Unit) { vm.loadPodcastHome() }
    var q by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
    ) {
        HigPageHeader(vm, go, "播客")
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HigSearchBar(
                value = q,
                onChange = { q = it },
                placeholder = "搜索播客（仅在本页搜索）",
                go = go,
                modifier = Modifier.weight(1f),
                onSearch = { vm.searchPodcasts(q) },
            )
            Spacer(Modifier.width(8.dp))
            GoButton("搜索", primary = true) { vm.searchPodcasts(q) }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (vm.podcastQuery.isNotBlank()) {
                item {
                    HigSectionTitle("「${vm.podcastQuery}」的搜索结果", go)
                    Spacer(Modifier.height(8.dp))
                }
                if (vm.podcastBusy) {
                    item { CircularProgressIndicator(color = go.primary) }
                } else if (vm.podcastResults.isEmpty()) {
                    item { Text("没有找到相关播客", fontSize = 14.sp, color = go.muted) }
                } else {
                    items(vm.podcastResults, key = { it.id }) { p ->
                        PodcastRow(p, go) { vm.openPodcast(p.id) }
                    }
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
            item {
                HigSectionTitle("最近播放 · 播客", go)
                Spacer(Modifier.height(8.dp))
            }
            item {
                if (vm.recentDj.isEmpty()) {
                    Text("还没有播放过播客声音", fontSize = 13.sp, color = go.muted)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(vm.recentDj, key = { it.id }) { v ->
                            Column(
                                Modifier.width(120.dp).clickable { vm.playVoice(v) },
                            ) {
                                Cover(v.cover, Modifier.size(120.dp), 10.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(v.name, fontSize = 13.sp, color = go.on, maxLines = 2)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            item {
                HigSectionTitle("我创建的播客", go)
                Spacer(Modifier.height(8.dp))
            }
            item {
                if (vm.myCreated.isEmpty()) {
                    Text("登录后这里会显示你创建的播客", fontSize = 13.sp, color = go.muted)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(vm.myCreated, key = { it.id }) { p ->
                            Column(
                                Modifier.width(120.dp).clickable { vm.openPodcast(p.id) },
                            ) {
                                Cover(p.cover, Modifier.size(120.dp), 10.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(p.name, fontSize = 13.sp, color = go.on, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
    vm.podcastDetail?.let { PodcastDetailSheet(vm, it) }
}

@Composable
private fun PodcastRow(p: NmSocial.Podcast, go: GoColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(p.cover, Modifier.size(56.dp), 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = go.on, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(
                listOf(p.creator, p.category, "${p.voiceCount} 期").filter { it.isNotBlank() }
                    .joinToString(" · ").ifBlank { p.desc },
                fontSize = 12.sp, color = go.muted, maxLines = 1,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
}

/** 播客详情：封面 / 简介 / 声音列表 + 搜索筛选 / 排序 / 删除 / 上传 */
@Composable
private fun PodcastDetailSheet(vm: MainViewModel, p: NmSocial.Podcast) {
    val go = LocalGo.current
    val ctx = LocalContext.current
    var nameQuery by remember { mutableStateOf("") }
    var showStatus by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var upName by remember { mutableStateOf("") }
    var upDesc by remember { mutableStateOf("") }
    var upCat by remember { mutableStateOf("") }
    var upSubCat by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
            ?: "application/octet-stream"
        val bytes = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes != null && bytes.isNotEmpty()) {
            picked = bytes to mime
            if (upName.isBlank()) upName = uri.lastPathSegment?.substringAfterLast('/') ?: "声音"
        } else vm.toast("读取音频文件失败")
    }
    GoSheet({ vm.closePodcast() }, "播客详情") {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(p.cover, Modifier.size(88.dp), 12.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    HigSectionTitle(p.name.ifBlank { "播客 ${p.id}" }, go)
                    if (p.creator.isNotBlank()) {
                        Text("主播：${p.creator}", fontSize = 12.sp, color = go.muted)
                    }
                    if (p.category.isNotBlank()) {
                        Text("分类：${p.category}", fontSize = 12.sp, color = go.muted)
                    }
                }
            }
            if (p.desc.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(p.desc, fontSize = 13.sp, color = go.muted, maxLines = 4)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HigSearchBar(
                    value = nameQuery,
                    onChange = { nameQuery = it },
                    placeholder = "搜索本播客里的声音",
                    go = go,
                    modifier = Modifier.weight(1f),
                    onSearch = { vm.searchVoices(p.id, nameQuery) },
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.Refresh, "重置", tint = go.primary,
                    modifier = Modifier.size(22.dp).clickable {
                        nameQuery = ""
                        vm.searchVoices(p.id, "")
                    },
                )
                Spacer(Modifier.width(6.dp))
                Box {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown, "状态筛选", tint = go.primary,
                        modifier = Modifier.size(22.dp).clickable { showStatus = true },
                    )
                    DropdownMenu(expanded = showStatus, onDismissRequest = { showStatus = false }) {
                        listOf(
                            "" to "全部", "ONLINE" to "已发布",
                            "AUDITING" to "审核中", "ONLY_SELF_SEE" to "仅自己可见",
                        ).forEach { (k, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    showStatus = false
                                    vm.searchVoices(p.id, nameQuery, k.takeIf { it.isNotBlank() })
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (voiceBusy(vm)) {
                CircularProgressIndicator(color = go.primary)
            } else if (vm.podcastVoices.isEmpty()) {
                Text("这个播客里暂时没有声音", fontSize = 14.sp, color = go.muted)
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(vm.podcastVoices, key = { _, v -> v.id }) { i, v ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(v.cover, Modifier.size(44.dp), 6.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(
                                Modifier.weight(1f).clickable { vm.playVoice(v) },
                            ) {
                                Text(v.name, fontSize = 14.sp, color = go.on, maxLines = 1)
                                Text(
                                    listOfNotNull(
                                        v.status.takeIf { it.isNotBlank() },
                                        socialDuration(v.duration),
                                    ).joinToString(" · ").ifBlank { "点按播放" },
                                    fontSize = 11.sp, color = go.muted, maxLines = 1,
                                )
                            }
                            Icon(
                                Icons.Outlined.PlayArrow, "播放", tint = go.primary,
                                modifier = Modifier.size(22.dp).clickable { vm.playVoice(v) },
                            )
                            Spacer(Modifier.width(6.dp))
                            Box {
                                Icon(
                                    Icons.Outlined.Add, "更多", tint = go.muted,
                                    modifier = Modifier.size(22.dp).clickable { menuFor = v.id },
                                )
                                DropdownMenu(
                                    expanded = menuFor == v.id,
                                    onDismissRequest = { menuFor = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("上移") },
                                        onClick = {
                                            menuFor = null
                                            if (i > 0) vm.sortVoice(p.id, v.id, i)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("下移") },
                                        onClick = {
                                            menuFor = null
                                            if (i < vm.podcastVoices.lastIndex) {
                                                vm.sortVoice(p.id, v.id, i + 2)
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除") },
                                        onClick = {
                                            menuFor = null
                                            vm.deleteVoice(p.id, v.id.toString())
                                        },
                                    )
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!uploading) {
                GoButton("上传声音", icon = Icons.Outlined.Add) { uploading = true }
            } else {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(go.surfaceHi).padding(12.dp),
                ) {
                    Column {
                        HigSectionTitle("上传声音（需登录）", go)
                        Spacer(Modifier.height(8.dp))
                        SocialInput(upName, { upName = it }, "声音名称", go)
                        Spacer(Modifier.height(6.dp))
                        SocialInput(upDesc, { upDesc = it }, "声音介绍", go)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SocialInput(upCat, { upCat = it }, "分类 id", go,
                                Modifier.weight(1f), KeyboardType.Number)
                            SocialInput(upSubCat, { upSubCat = it }, "次级分类 id", go,
                                Modifier.weight(1f), KeyboardType.Number)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GoButton(if (picked == null) "选择音频文件" else "已选择文件") {
                                picker.launch("audio/*")
                            }
                            GoButton("上传", primary = true, enabled = picked != null) {
                                val data = picked
                                if (data != null) {
                                    vm.uploadVoice(
                                        p.id,
                                        upCat.trim().toLongOrNull() ?: 0L,
                                        upSubCat.trim().toLongOrNull() ?: 0L,
                                        upDesc, upName, data.first, data.second,
                                        upName.ifBlank { "voice" },
                                    )
                                    uploading = false
                                    picked = null
                                }
                            }
                            GoButton("取消") { uploading = false; picked = null }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun voiceBusy(vm: MainViewModel): Boolean = vm.voiceBusy

private fun socialDuration(ms: Long): String? {
    if (ms <= 0) return null
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
