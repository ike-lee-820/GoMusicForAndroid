package de.myocd.gomusic

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 网易云登录 / 注册弹层：二维码登录 或 短信验证码登录注册 */
@Composable
fun NmLoginSheet(vm: MainViewModel) {
    val go = LocalGo.current
    var tab by remember { mutableIntStateOf(0) }
    GoSheet({
        vm.setNmLogin(false)
        vm.stopQrLogin()
    }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("网易云账号", 16, Modifier.weight(1f), FontWeight.SemiBold)
                IconBtn(Icons.Outlined.Close, "关闭") {
                    vm.setNmLogin(false)
                    vm.stopQrLogin()
                }
            }
            Txt("登录后才会搜索网易云曲库；未登录时只使用本站曲库", 12, color = go.muted)
            Spacer(Modifier.height(12.dp))
            Row {
                GoButton("扫码登录", primary = tab == 0) { tab = 0 }
                Spacer(Modifier.width(8.dp))
                GoButton("短信登录 / 注册", primary = tab == 1) { tab = 1 }
            }
            Spacer(Modifier.height(14.dp))
            if (tab == 0) QrLogin(vm) else PhoneLogin(vm)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QrLogin(vm: MainViewModel) {
    val go = LocalGo.current
    var started by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!started) {
            started = true
            vm.startQrLogin()
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { vm.stopQrLogin() }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val img = vm.nmQr
        if (img != null) {
            Box(
                Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Cover(img, Modifier.fillMaxSize(), 8.dp)
            }
        } else {
            Box(
                Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(go.surfaceHi),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = go.primary, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Txt(
            vm.nmQrTip, 13,
            Modifier.clickable { vm.startQrLogin() },
            color = if (vm.nmQr == null) go.primary else go.muted,
        )
    }
}

@Composable
private fun PhoneLogin(vm: MainViewModel) {
    val go = LocalGo.current
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var nick by remember { mutableStateOf("") }
    val reg = nick.isNotBlank()
    Column(Modifier.fillMaxWidth()) {
        GoField(phone, { phone = it }, "手机号")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { GoField(code, { code = it }, "验证码") }
            Spacer(Modifier.width(8.dp))
            GoButton(if (vm.nmBusy) "发送中" else "获取验证码", enabled = !vm.nmBusy) { vm.nmSendCode(phone) }
        }
        Spacer(Modifier.height(8.dp))
        GoField(nick, { nick = it }, "昵称（留空为登录，填写即注册）")
        if (reg) {
            Spacer(Modifier.height(8.dp))
            GoField(pwd, { pwd = it }, "密码（注册时必填）")
        }
        Spacer(Modifier.height(14.dp))
        GoButton(
            if (reg) "注册并登录" else "登录",
            primary = true,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            enabled = !vm.nmBusy,
        ) { vm.nmLoginPhone(phone, code, pwd, nick.trim()) }
        Spacer(Modifier.height(8.dp))
        Txt("验证码由网易云下发，注册即表示同意网易云的用户协议", 12, color = go.muted)
    }
}

@Composable
private fun GoField(value: String, onValue: (String) -> Unit, hint: String) {
    val go = LocalGo.current
    TextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        placeholder = { Txt(hint, 14, color = go.muted) },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = go.surfaceHi,
            unfocusedContainerColor = go.surfaceHi,
            disabledContainerColor = go.surfaceHi,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = go.on,
            unfocusedTextColor = go.on,
            cursorColor = go.primary,
        ),
    )
}

/** 网易云专辑 / 歌手里的歌曲列表页 */
@Composable
fun NmSongsPage(vm: MainViewModel, s: Screen.NmSongs) {
    val go = LocalGo.current
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.clearNmPage() } }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverWithSource(s.cover, Modifier.size(88.dp), 16.dp, source = SRC_NETEASE)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(s.title, 18, weight = FontWeight.Bold, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    Txt(if (s.kind == "album") "网易云专辑" else "网易云歌手", 12, color = go.muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                GoButton("播放全部", primary = true, enabled = vm.nmPageSongs.isNotEmpty()) {
                    vm.playSongs(vm.nmPageSongs, 0)
                }
                Spacer(Modifier.width(8.dp))
                GoButton("下载全部", enabled = vm.nmPageSongs.isNotEmpty()) {
                    vm.download(vm.nmPageSongs)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (vm.nmPageLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = go.primary)
            }
        } else {
            val list = vm.nmPageSongs
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(list, key = { _, it -> it.ssid }) { i, song ->
                    SongRow(
                        song, song.ssid == vm.curSsid, vm.isPlaying,
                        downloaded = vm.isDownloaded(song.ssid),
                        downloading = song.ssid in vm.downloading,
                        onClick = { vm.playSongs(list, i) },
                        onAdd = { vm.pick(listOf(song.ssid), song.title, song.coverUrl) },
                        onDownload = { vm.download(listOf(song)) },
                    )
                }
            }
        }
    }
}
