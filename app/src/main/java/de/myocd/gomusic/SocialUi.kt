package de.myocd.gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 26.11.82：社交（私信正式名称）。
 *
 * 三段子页：私信会话 / 找朋友 / 一起听。所有请求走 NmSocial 的容错解析，
 * 页面顶部与其余整页共用 HigPageHeader + HigSettingAction，字号只认 HigType。
 * 会话里可以发文本、发资源、发起一起听（主机模式）；全局「私聊分享」
 * 弹层（[ShareTargetSheet]）从歌曲 / 专辑 / 歌单点进来，选会话即可发送。
 */
@Composable
fun SocialPage(vm: MainViewModel) {
    val go = LocalGo.current
    LaunchedEffect(Unit) { vm.loadSocial() }
    if (vm.chatUid > 0) {
        ChatPage(vm)
        return
    }
    // 26.11.86：由 VM 持有，点私信里的一起听链接能直接跳到该子页
    val sub = vm.socialTab
    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        HigPageHeader(vm, go, "社交") {
            // 私信 / 通知未读数：/pl/count，登录后才有意义
            val (msg, notice) = vm.socialCounts
            if (msg + notice > 0) {
                Box(
                    Modifier.padding(end = 10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(go.primary)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("私信 $msg · 通知 $notice", fontSize = 11.sp, color = go.surface)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("私信", "找朋友", "一起听").forEachIndexed { i, t ->
                GoButton(t, primary = sub == i) { vm.chooseSocialTab(i) }
            }
        }
        Spacer(Modifier.height(14.dp))
        when (sub) {
            0 -> ConversationsPane(vm)
            1 -> FindFriendPane(vm)
            else -> ListenTogetherPane(vm)
        }
    }
}

/** 单行输入槽（与 HigSearchBar 同一套单层样式，不叠框） */
@Composable
fun SocialInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    go: GoColors,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Row(
        modifier.fillMaxWidth().height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(go.surfaceHi)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                color = go.on, fontSize = 15.sp, fontFamily = LocalAppFont.current,
            ),
            cursorBrush = SolidColor(go.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = go.muted, fontSize = 15.sp)
                    }
                    inner()
                }
            },
        )
    }
}

/** 26.11.86：聊天文本里的 URL（含一起听分享链接） */
private val CHAT_URL_RE = Regex("""https?://[^\s<>"'，。；！？）)\]]+""")

private fun cleanUrl(u: String): String = u.trimEnd(
    '。', '，', ',', '.', '）', ')', '"', '\'', '!', '！', '；', ';',
)

private fun chatLinks(text: String): List<String> =
    CHAT_URL_RE.findAll(text)
        .map { cleanUrl(it.value) }
        .filter { !NmSocial.isImageUrl(it) }
        .distinct().toList()

/**
 * 气泡里的可点文本：链接单独高亮并可点，点非链接区域时打开资源卡片。
 * 用 ClickableText 而不是给整个 Box 加 clickable，链接和卡片点击不会互相抢事件。
 */
@Composable
private fun ChatText(
    text: String,
    color: Color,
    linkColor: Color,
    onLink: (String) -> Unit,
    onPlain: () -> Unit,
    small: Boolean = false,
) {
    val annotated = buildAnnotatedString {
        var last = 0
        CHAT_URL_RE.findAll(text).forEach { mr ->
            val url = cleanUrl(mr.value)
            append(text.substring(last, mr.range.first))
            if (NmSocial.isImageUrl(url)) {
                // 26.11.91：图片 CDN 地址不画成可点链接、不整条印出来
                append("[图片]")
            } else {
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(mr.value)
                }
                pop()
            }
            last = mr.range.endExclusive
        }
        append(text.substring(last))
    }
    ClickableText(
        text = annotated,
        style = TextStyle(
            color = color,
            fontSize = if (small) 12.sp else 14.sp,
            fontFamily = LocalAppFont.current,
        ),
        onClick = { off ->
            val url = annotated.getStringAnnotations("URL", off, off).firstOrNull()?.item
            if (url != null) onLink(url) else onPlain()
        },
    )
}

private fun socialStamp(t: Long): String {
    if (t <= 0) return ""
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))
    }.getOrDefault("")
}

@Composable
private fun ConversationsPane(vm: MainViewModel) {
    val go = LocalGo.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        HigSectionTitle("最近私信", go)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Outlined.Refresh, "刷新", tint = go.primary,
            modifier = Modifier.size(20.dp).clickable { vm.loadSocial(force = true) },
        )
    }
    Spacer(Modifier.height(8.dp))
    when {
        vm.socialBusy && vm.conversations.isEmpty() -> Box(
            Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = go.primary) }
        vm.conversations.isEmpty() -> EmptyHint(
            vm.socialError.ifBlank { "还没有私信会话，去「找朋友」发起一个" },
        )
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(vm.conversations, key = { it.uid }) { c ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp)
                        .clickable { vm.openChat(c.uid, c.name, c.avatar) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(c.avatar, Modifier.size(48.dp).clip(CircleShape), 24.dp, circle = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name.ifBlank { "用户 ${c.uid}" }, fontSize = 16.sp, color = go.on, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        Text(c.last, fontSize = 13.sp, color = go.muted, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(socialStamp(c.time), fontSize = 11.sp, color = go.muted)
                        if (c.unread > 0) {
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier.clip(CircleShape).background(go.primary)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) { Text("${c.unread}", fontSize = 11.sp, color = go.surface) }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
            }
        }
    }
}

@Composable
private fun FindFriendPane(vm: MainViewModel) {
    val go = LocalGo.current
    var q by remember { mutableStateOf("") }
    HigSectionTitle("添加新朋友", go)
    Spacer(Modifier.height(6.dp))
    Text(
        "输入对方的数字 uid 直接查资料，或按昵称搜索用户；点结果即可发起私信",
        fontSize = 12.sp, color = go.muted,
    )
    Spacer(Modifier.height(10.dp))
    SocialInput(q, { q = it }, "uid 或昵称", go, keyboardType = KeyboardType.Text)
    Spacer(Modifier.height(10.dp))
    GoButton("查找", primary = true, enabled = q.isNotBlank()) { vm.searchFriends(q) }
    Spacer(Modifier.height(12.dp))
    when {
        vm.friendBusy -> CircularProgressIndicator(color = go.primary)
        vm.friendResults.isEmpty() -> EmptyHint("输入 uid 或昵称后点「查找」")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(vm.friendResults, key = { it.id }) { u ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp)
                        .clickable { vm.openChat(u.id, u.name, u.avatar) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(u.avatar, Modifier.size(48.dp).clip(CircleShape), 24.dp, circle = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.name.ifBlank { "用户 ${u.id}" }, fontSize = 16.sp, color = go.on, maxLines = 1)
                        if (u.signature.isNotBlank()) {
                            Text(u.signature, fontSize = 12.sp, color = go.muted, maxLines = 1)
                        }
                    }
                    Icon(Icons.Outlined.Add, "添加", tint = go.primary, modifier = Modifier.size(22.dp))
                }
                Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
            }
        }
    }
}

@Composable
private fun ListenTogetherPane(vm: MainViewModel) {
    val go = LocalGo.current
    // 26.11.84：进页面自动取登录态，不用用户手点「获取登录状态」
    LaunchedEffect(Unit) { vm.ltAutoLogin() }
    var roomIdInput by remember { mutableStateOf("") }
    var inviterInput by remember { mutableStateOf("") }
    var playlistInput by remember { mutableStateOf("") }
    var plQuery by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        HigSectionTitle("一起听 · 官方房间", go)
        Spacer(Modifier.height(6.dp))
        Text(
            "按网易云官方房间协议：创建 / 加入房间、同步歌单、下发播放指令与心跳。" +
                "需要先在设置里登录网易云，房间链接可直接在私信里发给对方。",
            fontSize = 12.sp, color = go.muted,
        )
        Spacer(Modifier.height(10.dp))
        // 消息条（对应示例里的 .message）
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(go.surfaceHi).padding(12.dp),
        ) {
            Text("消息：${vm.ltMessage}", fontSize = 13.sp, color = go.on)
        }
        Spacer(Modifier.height(12.dp))
        if (!vm.ltAccount.login) {
            GoButton("获取登录状态", primary = true, enabled = !vm.ltBusy) { vm.ltLogin() }
            Spacer(Modifier.height(12.dp))
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(go.surfaceHi).padding(12.dp),
        ) {
            Column {
                HigSectionTitle("账号信息", go)
                Spacer(Modifier.height(6.dp))
                Text(
                    "当前登录账号：${vm.ltAccount.nickname}" +
                        (if (vm.ltAccount.userId > 0) "（${vm.ltAccount.userId}）" else ""),
                    fontSize = 13.sp, color = go.on,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        HigSectionTitle("房间管理", go)
        Spacer(Modifier.height(8.dp))
        if (vm.ltRoomId == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoButton("创建房间", primary = true, enabled = !vm.ltBusy) {
                    vm.ltCreateRoom()
                }
            }
            Spacer(Modifier.height(14.dp))
            HigSectionTitle("加入房间", go)
            Spacer(Modifier.height(8.dp))
            SocialInput(roomIdInput, { roomIdInput = it }, "房间 ID", go)
            Spacer(Modifier.height(8.dp))
            SocialInput(inviterInput, { inviterInput = it }, "邀请者 ID", go,
                keyboardType = KeyboardType.Number)
            Spacer(Modifier.height(10.dp))
            GoButton("加入房间", enabled = roomIdInput.isNotBlank() && !vm.ltBusy) {
                vm.ltJoin(roomIdInput, inviterInput.trim().toLongOrNull() ?: 0L)
            }
        } else {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(go.surfaceHi).padding(12.dp),
            ) {
                Column {
                    HigSectionTitle("房间 ${vm.ltRoomId}", go)
                    Spacer(Modifier.height(6.dp))
                    Text("邀请者：${vm.ltInviterId}", fontSize = 12.sp, color = go.muted)
                    Spacer(Modifier.height(8.dp))
                    Text("分享链接", fontSize = 12.sp, color = go.muted)
                    Spacer(Modifier.height(4.dp))
                    Text(vm.ltShareLink, fontSize = 12.sp, color = go.primary)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoButton("刷新房间状态") { vm.ltRefresh() }
                GoButton("关闭房间") { vm.ltClose() }
            }
            Spacer(Modifier.height(14.dp))
            HigSectionTitle("在线用户（${vm.ltUsers.size}）", go)
            Spacer(Modifier.height(6.dp))
            if (vm.ltUsers.isEmpty()) {
                Text("还没有其他在线用户", fontSize = 13.sp, color = go.muted)
            } else {
                vm.ltUsers.forEach { u ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(u.avatarUrl, Modifier.size(40.dp).clip(CircleShape), 20.dp, circle = true)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            u.nickname.ifBlank { "用户 ${u.userId}" },
                            fontSize = 14.sp, color = go.on,
                        )
                    }
                    Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        HigSectionTitle("播放控制", go)
        Spacer(Modifier.height(8.dp))
        Text(
            "当前歌曲 ID：${vm.ltSongId.takeIf { it > 0 } ?: "未选择"} · ${vm.ltPlayStatus}",
            fontSize = 12.sp, color = go.muted,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoButton("播放") { vm.ltPlay("PLAY") }
            GoButton("暂停") { vm.ltPlay("PAUSE") }
            GoButton("同步进度") { vm.ltPlay("seek") }
        }
        Spacer(Modifier.height(16.dp))
        HigSectionTitle("播放列表", go)
        Spacer(Modifier.height(6.dp))
        Text(
            "搜索歌单或直接选自己的歌单，选中后自动把曲目 REPLACE 同步到房间",
            fontSize = 12.sp, color = go.muted,
        )
        Spacer(Modifier.height(8.dp))
        SocialInput(plQuery, { plQuery = it }, "搜索歌单名称 / 关键词", go)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoButton(
                "搜索歌单", primary = true,
                enabled = plQuery.isNotBlank() && !vm.ltPlaylistBusy,
            ) { vm.ltSearchPlaylist(plQuery) }
            GoButton("我的歌单", enabled = !vm.ltPlaylistBusy) { vm.ltLoadMyPlaylists() }
        }
        if (vm.ltPlaylistBusy) {
            Spacer(Modifier.height(10.dp))
            CircularProgressIndicator(color = go.primary)
        }
        if (vm.ltPlaylistResults.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "${vm.ltPlaylistQuery.ifBlank { "候选" }} · ${vm.ltPlaylistResults.size} 张（点选加载）",
                fontSize = 12.sp, color = go.muted,
            )
            Spacer(Modifier.height(6.dp))
            vm.ltPlaylistResults.take(30).forEach { pl ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .clickable { vm.ltSelectPlaylist(pl) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(pl.cover, Modifier.size(40.dp), 6.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pl.name, fontSize = 14.sp, color = go.on, maxLines = 1)
                        Text(
                            buildString {
                                if (pl.creator.isNotBlank()) append(pl.creator)
                                if (pl.trackCount > 0) append(" · ${pl.trackCount} 首")
                            },
                            fontSize = 11.sp, color = go.muted, maxLines = 1,
                        )
                    }
                    Text("选它", fontSize = 12.sp, color = go.primary)
                }
                Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
            }
        }
        Spacer(Modifier.height(14.dp))
        HigSectionTitle("或按歌单 ID 直接加载", go)
        Spacer(Modifier.height(8.dp))
        SocialInput(playlistInput, { playlistInput = it }, "歌单 ID", go,
            keyboardType = KeyboardType.Number)
        Spacer(Modifier.height(8.dp))
        GoButton("加载歌单", enabled = playlistInput.isNotBlank() && !vm.ltBusy) {
            vm.ltLoadPlaylist(playlistInput)
        }
        Spacer(Modifier.height(8.dp))
        Text("歌单名称：${vm.ltPlaylistName}", fontSize = 13.sp, color = go.muted)
        Spacer(Modifier.height(8.dp))
        if (vm.ltTracks.isEmpty()) {
            Text("还没有曲目，加载歌单或加入带歌单的房间", fontSize = 13.sp, color = go.muted)
        } else {
            vm.ltTracks.take(100).forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .clickable { vm.ltGoto(t.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(t.cover, Modifier.size(40.dp), 6.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontSize = 14.sp, color = go.on, maxLines = 1)
                        if (t.artist.isNotBlank()) {
                            Text(t.artist, fontSize = 11.sp, color = go.muted, maxLines = 1)
                        }
                    }
                    Text("播放", fontSize = 12.sp, color = go.primary)
                }
                Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 聊天页：消息气泡 + 文本发送 + 资源分享 + 一起听邀请 */
@Composable
private fun ChatPage(vm: MainViewModel) {
    val go = LocalGo.current
    var text by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 26.11.85：最老在上、最新在下；按「最新一条」而不是列表长度跟随，
    // 缓存先铺出来、网络再补新消息时都能滚到底，翻更早的页不会跳回顶部。
    val newestKey = vm.chatMessages.lastOrNull()?.let { "${it.time}-${it.id}-${it.text}" }
    LaunchedEffect(vm.chatUid, newestKey) {
        if (vm.chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(vm.chatMessages.lastIndex)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(go.surfaceHi)
                    .clickable { vm.closeChat() },
                contentAlignment = Alignment.Center,
            ) { Text("‹", fontSize = 24.sp, color = go.on) }
            Spacer(Modifier.width(10.dp))
            Cover(vm.chatAvatar, Modifier.size(36.dp).clip(CircleShape), 18.dp, circle = true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                HigSectionTitle(vm.chatName.ifBlank { "用户 ${vm.chatUid}" }, go)
            }
            Icon(
                Icons.Outlined.Refresh, "刷新", tint = go.primary,
                modifier = Modifier.size(20.dp).clickable { vm.loadChat() },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoButton("发起一起听") { vm.ltCreateRoom(vm.chatUid) }
            GoButton("分享当前歌曲") {
                val s = vm.currentSong
                val nid = s?.nmId?.takeIf { it > 0 } ?: neteaseIdOf(s?.ssid) ?: 0L
                if (nid > 0) vm.beginShare(
                    MainViewModel.ShareItem("song", nid, s?.title.orEmpty(), s?.coverUrl),
                ) else vm.toast("当前歌曲不是网易云歌曲，无法发送音乐私信")
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            vm.chatBusy && vm.chatMessages.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = go.primary) }
            vm.chatMessages.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center,
            ) { Text("还没有消息，发一句打个招呼吧", fontSize = 14.sp, color = go.muted) }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                // 升序排列后「加载更早」属于顶部，不再挂在新消息后面
                if (vm.chatBefore > 0) {
                    item(key = "load_older") {
                        Text(
                            "加载更早的消息", fontSize = 13.sp, color = go.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                                .clickable { vm.loadChat(more = true) },
                        )
                    }
                }
                items(
                    vm.chatMessages,
                    key = { m ->
                        if (m.id > 0L) "i${m.id}"
                        else "t${m.time}|${m.fromSelf}|${m.text}|${m.resId}"
                    },
                ) { m ->
                    val label = when (m.kind) {
                        "song" -> "🎵 歌曲："
                        "album" -> "💿 专辑："
                        "playlist" -> "📃 歌单："
                        else -> ""
                    }
                    val body = label + if (m.kind == "text") m.text
                    else m.resName.ifBlank { m.text }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        horizontalArrangement = if (m.fromSelf) Arrangement.End else Arrangement.Start,
                    ) {
                        Box(
                            Modifier.widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (m.fromSelf) go.primary else go.surfaceHi)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Column {
                                val canOpen = m.kind != "text" && m.resId > 0L
                                ChatText(
                                    text = body,
                                    color = if (m.fromSelf) go.surface else go.on,
                                    linkColor = if (m.fromSelf) go.surface else go.primary,
                                    onLink = { vm.openChatLink(it) },
                                    onPlain = { if (canOpen) vm.openChatResource(m) },
                                )
                                // 26.11.91：资源私信的原文是一段 JSON，里面全是
                                // picUrl / coverUrl 等封面地址。以前把「没出现在
                                // 正文里的链接」逐条列出来，等于把专辑图片链接印在
                                // 气泡里。现在只有纯文本消息才额外列可点网页链接。
                                if (m.kind == "text" && !m.raw.trim().startsWith("{")) {
                                    val raw = m.raw.ifBlank { m.text }
                                    chatLinks(raw).filter { it !in body }.forEach { u ->
                                        ChatText(
                                            text = u,
                                            color = if (m.fromSelf) go.surface else go.primary,
                                            linkColor = if (m.fromSelf) go.surface else go.primary,
                                            onLink = { vm.openChatLink(u) },
                                            onPlain = { vm.openChatLink(u) },
                                            small = true,
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (socialStamp(m.time).isNotBlank()) {
                                        Text(
                                            socialStamp(m.time), fontSize = 10.sp,
                                            color = if (m.fromSelf) go.surface.copy(alpha = 0.7f) else go.muted,
                                        )
                                    }
                                    if (canOpen) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "· 点击打开", fontSize = 10.sp,
                                            color = if (m.fromSelf) go.surface.copy(alpha = 0.75f) else go.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SocialInput(text, { text = it }, "发一条私信…", go, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(go.primary)
                    .clickable {
                        vm.sendChatText(text)
                        text = ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = go.surface, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 通用空状态 */
@Composable
private fun EmptyHint(text: String) {
    val go = LocalGo.current
    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, color = go.muted, textAlign = TextAlign.Center)
    }
}

/**
 * 26.11.82：全局「私聊分享」选人弹层。
 * 专辑 / 歌单 / 歌曲 / 播放器更多菜单调用 vm.beginShare 后，最外层组合它；
 * 列出会话、可输入 uid 直接发，附言可留空（自动填「分享：名字」）。
 */
@Composable
fun ShareTargetSheet(vm: MainViewModel) {
    val go = LocalGo.current
    val item = vm.pendingShare ?: return
    var uidText by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    /** 26.11.89：0 最近会话 / 1 所有好友（关注 + 粉丝，可分页浏览、搜索） */
    var tab by remember { mutableStateOf(0) }
    var fq by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        vm.loadSocial()
        vm.loadShareFriends(true)
    }
    GoSheet({ vm.cancelShare() }, "分享到私信") {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(item.cover, Modifier.size(52.dp), 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val kindName = when (item.kind) {
                        "song" -> "歌曲"; "album" -> "专辑"
                        "playlist" -> "歌单"; else -> "文本"
                    }
                    HigSectionTitle("$kindName · ${item.name}", go)
                    Text("选一个会话或好友发送", fontSize = 12.sp, color = go.muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            SocialInput(msg, { msg = it }, "附言（可留空）", go)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoButton("最近会话", primary = tab == 0) { tab = 0 }
                GoButton("所有好友", primary = tab == 1) { tab = 1 }
            }
            Spacer(Modifier.height(10.dp))

            if (tab == 0) {
                HigSectionTitle("最近会话", go)
                Spacer(Modifier.height(6.dp))
                if (vm.conversations.isEmpty()) {
                    Text("还没有会话，可以直接输入对方 uid 或切到「所有好友」", fontSize = 13.sp, color = go.muted)
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp).fillMaxWidth()) {
                        items(vm.conversations, key = { it.uid }) { c ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 9.dp)
                                    .clickable {
                                        vm.shareTo(
                                            c.uid, c.name, c.avatar,
                                            msg.ifBlank { "分享：${item.name}" },
                                        )
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Cover(c.avatar, Modifier.size(40.dp).clip(CircleShape), 20.dp, circle = true)
                                Spacer(Modifier.width(10.dp))
                                Text(c.name.ifBlank { "用户 ${c.uid}" }, fontSize = 15.sp, color = go.on)
                            }
                            Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
                        }
                    }
                }
            } else {
                HigSectionTitle("所有好友（关注 + 粉丝）", go)
                Spacer(Modifier.height(6.dp))
                SocialInput(fq, { fq = it }, "搜索好友昵称", go)
                Spacer(Modifier.height(8.dp))
                val shown = vm.shareFriends.filter {
                    fq.isBlank() || it.name.contains(fq.trim(), ignoreCase = true) ||
                        it.signature.contains(fq.trim(), ignoreCase = true)
                }
                when {
                    vm.shareFriendsBusy && vm.shareFriends.isEmpty() ->
                        CircularProgressIndicator(color = go.primary, modifier = Modifier.size(22.dp))
                    shown.isEmpty() -> Text(
                        vm.shareFriendsError.ifBlank { "没有匹配的好友" },
                        fontSize = 13.sp, color = go.muted,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 300.dp).fillMaxWidth()) {
                        items(shown, key = { it.id }) { u ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 9.dp)
                                    .clickable {
                                        vm.shareTo(
                                            u.id, u.name, u.avatar,
                                            msg.ifBlank { "分享：${item.name}" },
                                        )
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Cover(u.avatar, Modifier.size(40.dp).clip(CircleShape), 20.dp, circle = true)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(u.name, fontSize = 15.sp, color = go.on, maxLines = 1)
                                    if (u.signature.isNotBlank()) {
                                        Text(u.signature, fontSize = 11.sp, color = go.muted, maxLines = 1)
                                    }
                                }
                            }
                            Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已加载 ${vm.shareFriends.size} 位" +
                            if (vm.shareFriendsMore) "，还可继续加载" else "，已全部加载",
                        fontSize = 11.sp, color = go.muted, modifier = Modifier.weight(1f),
                    )
                    if (vm.shareFriendsMore) {
                        GoButton("加载更多", enabled = !vm.shareFriendsBusy) {
                            vm.loadShareFriends()
                        }
                    }
                }
                if (vm.shareFriendsBusy && vm.shareFriends.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp), color = go.primary,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HigSectionTitle("发给指定 uid", go)
            Spacer(Modifier.height(6.dp))
            SocialInput(uidText, { uidText = it }, "对方 uid", go, keyboardType = KeyboardType.Number)
            Spacer(Modifier.height(10.dp))
            GoButton("发送", primary = true, enabled = uidText.isNotBlank()) {
                val uid = uidText.trim().toLongOrNull()
                if (uid == null || uid <= 0) vm.toast("uid 必须是数字")
                else vm.shareTo(uid, uidText.trim(), null, msg.ifBlank { "分享：${item.name}" })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
