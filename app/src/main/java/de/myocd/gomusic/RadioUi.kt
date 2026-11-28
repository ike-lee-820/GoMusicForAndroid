package de.myocd.gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 26.11.87：电台页（原播客页整体替换）。
 *
 * 26.11.90：删掉 Banner；进页面一次性并发获取全部板块，无返回的板块隐藏，
 * 全量结果缓存 48 小时，期间不再自动刷新。评论 / 动态 / 分享等非电台功能不在这里。
 */
@Composable
fun RadioPage(vm: MainViewModel) {
    val go = LocalGo.current
    val detail = vm.radioDetail
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
    ) {
        HigPageHeader(vm, go, "电台")
        Spacer(Modifier.height(12.dp))
        if (detail == null) RadioHome(vm, go) else RadioDetailPane(vm, go, detail)
    }
}

@Composable
private fun RadioHome(vm: MainViewModel, go: GoColors) {
    val groups = RadioFeeds.groups()
    val feeds = vm.radioFeeds()
    // 26.11.90：进页面一次性把全部板块排进请求队列；48h 内 loadRadioAll
    // 会直接读缓存返回，不发任何请求。
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadRadioAll() }

    val visible = feeds.filter { vm.radioResults[it.key]?.isNotEmpty() == true }
    val hidden = if (vm.radioAllDone && !vm.radioLoading) {
        feeds.filter { vm.radioResults[it.key].isNullOrEmpty() }
    } else emptyList()

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(groups.size) { i ->
            GoButton(groups[i].first, primary = vm.radioTab == i) { vm.chooseRadioTab(i) }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "全部板块一次性获取 · 无返回的自动隐藏 · 48 小时内直接使用缓存",
        fontSize = 11.sp, color = go.muted,
    )
    Spacer(Modifier.height(6.dp))
    // 缓存状态条
    Row(verticalAlignment = Alignment.CenterVertically) {
        val stamp = if (vm.radioCacheAt > 0L) {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(vm.radioCacheAt))
        } else ""
        Text(
            when {
                vm.radioLoading -> "正在一次性获取全部板块…"
                vm.radioCacheAt > 0L -> "缓存于 $stamp · 48h 内不自动刷新"
                else -> "尚未缓存"
            },
            fontSize = 11.sp, color = go.muted, modifier = Modifier.weight(1f),
        )
        GoButton("强制刷新", enabled = !vm.radioLoading) { vm.forceRefreshRadio() }
        if (hidden.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            GoButton("隐藏 ${hidden.size}") { vm.toggleRadioHidden() }
        }
    }
    if (vm.radioLoading) {
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp), color = go.primary,
        )
    }
    Spacer(Modifier.height(10.dp))

    if (vm.radioTab == groups.size - 1) {
        RadioSearchBox(vm, go)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(visible, key = { it.key }) { feed ->
            RadioFeedSection(vm, go, feed)
        }
        if (vm.radioHiddenShown && hidden.isNotEmpty()) {
            item {
                Text(
                    "以下板块没有返回内容，已隐藏。改好参数后可单独重取：",
                    fontSize = 11.sp, color = go.muted,
                )
                Spacer(Modifier.height(6.dp))
            }
            items(hidden, key = { "hidden_${it.key}" }) { feed ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(feed.title, fontSize = 13.sp, color = go.on)
                        if (feed.note.isNotBlank()) {
                            Text(feed.note, fontSize = 10.sp, color = go.muted, maxLines = 1)
                        }
                        if (feed.paramName.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            SocialInput(
                                vm.radioParams[feed.key].orEmpty(),
                                { vm.setRadioParam(feed.key, it) },
                                feed.paramName + if (feed.paramDefault.isNotBlank()) "（默认 ${feed.paramDefault}）" else "",
                                go,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    GoButton("重取") { vm.loadRadioFeed(feed) }
                }
                Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun RadioFeedSection(vm: MainViewModel, go: GoColors, feed: RadioFeed) {
    val list = vm.radioResults[feed.key].orEmpty()
    val busy = feed.key in vm.radioBusyKeys
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HigSectionTitle(feed.title, go, Modifier.weight(1f))
            Text("${list.size} 条", fontSize = 11.sp, color = go.muted)
        }
        if (feed.note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(feed.note, fontSize = 11.sp, color = go.muted)
        }
        if (feed.paramName.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            SocialInput(
                vm.radioParams[feed.key].orEmpty(),
                { vm.setRadioParam(feed.key, it) },
                feed.paramName + if (feed.paramDefault.isNotBlank()) "（默认 ${feed.paramDefault}）" else "",
                go,
            )
            Spacer(Modifier.height(6.dp))
            GoButton("应用参数并重取", enabled = !busy) { vm.loadRadioFeed(feed) }
        }
        Spacer(Modifier.height(8.dp))
        if (busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp), color = go.primary,
            )
            Spacer(Modifier.height(6.dp))
        }
        val shown = if (expanded) list else list.take(8)
        shown.forEach { item -> RadioItemRow(vm, go, item) }
        if (list.size > 8) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (expanded) "收起" else "展开全部 ${list.size} 条",
                fontSize = 12.sp, color = go.primary,
                modifier = Modifier.clickable { expanded = !expanded }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
    }
}

@Composable
private fun RadioItemRow(vm: MainViewModel, go: GoColors, item: RadioItem) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp)
            .clickable { onRadioItem(vm, item) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(item.cover, Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)), 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 14.sp, color = go.on, maxLines = 1)
            if (item.subtitle.isNotBlank()) {
                Text(item.subtitle, fontSize = 11.sp, color = go.muted, maxLines = 1)
            }
        }
        when (item.kind) {
            "station" -> {
                GoButton("订阅") { vm.radioSubscribe(item, true) }
                Spacer(Modifier.width(4.dp))
                GoButton("退订") { vm.radioSubscribe(item, false) }
            }
            "broadcast" -> {
                GoButton("收藏") { vm.radioBroadcastSub(item, true) }
                Spacer(Modifier.width(4.dp))
                GoButton("取消") { vm.radioBroadcastSub(item, false) }
            }
            "difm" -> {
                GoButton("收藏") { vm.radioDifmSub(item, true) }
                Spacer(Modifier.width(4.dp))
                GoButton("取消") { vm.radioDifmSub(item, false) }
            }
            "program", "voice", "track" -> Text("播放", fontSize = 12.sp, color = go.primary)
            else -> Text("查看", fontSize = 12.sp, color = go.primary)
        }
    }
}

private fun onRadioItem(vm: MainViewModel, item: RadioItem) {
    when (item.kind) {
        "station" -> vm.openRadioDetail(item)
        "program", "voice", "track" -> vm.playRadioProgram(item)
        "broadcast" -> vm.radioShowInfo("/broadcast/channel/currentinfo", item.id)
        "category" -> {
            vm.setRadioParam("bytype", item.id.toString())
            vm.setRadioParam("radiohot", item.id.toString())
            vm.toast("已把分类 id ${item.id} 填到「分类推荐 / 类别热门电台」")
        }
        "difm" -> {
            vm.setRadioParam("difmplay", item.id.toString())
            vm.toast("已把频道 id ${item.id} 填到「播放列表」")
        }
        else -> vm.openRadioDetail(item)
    }
}

@Composable
private fun RadioSearchBox(vm: MainViewModel, go: GoColors) {
    var q by remember { mutableStateOf(vm.radioSearchQuery) }
    SocialInput(q, { q = it }, "搜索电台名称（type=1009）", go)
    Spacer(Modifier.height(8.dp))
    GoButton("搜索", primary = true, enabled = q.isNotBlank() && !vm.radioSearchBusy) {
        vm.searchRadio(q)
    }
    Spacer(Modifier.height(10.dp))
    if (vm.radioSearchBusy) {
        CircularProgressIndicator(color = go.primary, modifier = Modifier.size(20.dp))
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.radioSearchResults, key = { "rs${it.id}${it.title}" }) { item ->
                RadioItemRow(vm, go, item)
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun RadioDetailPane(vm: MainViewModel, go: GoColors, item: RadioItem) {
    val d = item
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoButton("‹ 返回") { vm.closeRadioDetail() }
                Spacer(Modifier.weight(1f))
                GoButton("刷新") { vm.openRadioDetail(d) }
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            Row(verticalAlignment = Alignment.Top) {
                Cover(d.cover, Modifier.size(110.dp).clip(RoundedCornerShape(12.dp)), 12.dp, hd = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        d.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = go.on,
                    )
                    if (d.subtitle.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(d.subtitle, fontSize = 12.sp, color = go.muted)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoButton("订阅", primary = true) { vm.radioSubscribe(d, true) }
                GoButton("取消订阅") { vm.radioSubscribe(d, false) }
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            if (vm.radioDetailBusy) {
                CircularProgressIndicator(color = go.primary, modifier = Modifier.size(20.dp))
            } else {
                Text(vm.radioDetailText.ifBlank { "暂无详情" }, fontSize = 13.sp, color = go.on)
            }
            Spacer(Modifier.height(14.dp))
        }
        item {
            HigSectionTitle("节目（${vm.radioDetailPrograms.size}）", go)
            Spacer(Modifier.height(6.dp))
        }
        items(vm.radioDetailPrograms, key = { "rp${it.id}${it.title}" }) { p ->
            RadioItemRow(vm, go, p)
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}
