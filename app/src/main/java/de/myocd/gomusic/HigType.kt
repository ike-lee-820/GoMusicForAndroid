package de.myocd.gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 26.11.82：全应用统一字号。
 *
 * 之前同一层级的标题散着 15 / 16 / 17 / 18 / 20 / 22 / 28 / 34 八种字号，
 * 三个主 Tab 更是各写各的（首页 20、搜索 28、我的 34），设置按钮也有
 * IconBtn 与圆形底两套。现在所有页面顶部大标题、分区小标题、导航标题、
 * 设置入口都只认这里的常量与组件，新增页面直接复用，不会又长出一种字号。
 */
object HigType {
    /** 页面顶部大标题：主页 / 搜索 / 我的 / 私信 / 播客 / 歌单详情 / 简介页 */
    val PageTitle = 28.sp
    /** 分区 / 分组小标题：所有「我的歌单」「外观」「最近搜索」等 */
    val SectionTitle = 17.sp
    /** 导航栏 / 弹层顶栏标题 */
    val NavTitle = 17.sp
    /** 正文 */
    val Body = 14.sp
    /** 辅助说明 */
    val Caption = 12.sp
    /** 「查看全部 / 去设置」这类行尾动作 */
    val Action = 14.sp
}

@Composable
fun HigPageTitle(title: String, go: GoColors, modifier: Modifier = Modifier) {
    Text(
        title,
        fontSize = HigType.PageTitle,
        fontWeight = FontWeight.Bold,
        color = go.on,
        modifier = modifier,
    )
}

@Composable
fun HigSectionTitle(title: String, go: GoColors, modifier: Modifier = Modifier) {
    Text(
        title,
        fontSize = HigType.SectionTitle,
        fontWeight = FontWeight.SemiBold,
        color = go.on,
        modifier = modifier,
    )
}

/** 唯一的设置按钮：44dp 圆形半透明底 + 20dp 齿轮，亮暗自动取色 */
@Composable
fun HigSettingAction(vm: MainViewModel, go: GoColors) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(go.surfaceHi)
            .clickable { vm.showSettings = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Settings, "设置",
            tint = go.on, modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 页面顶部统一行：大标题 + 右侧统一设置按钮（可再追加私信 / 播客等入口）。
 * 所有整页都从这里起头，避免每个页面自己拼一遍 Row 又写成另一种字号。
 */
@Composable
fun HigPageHeader(
    vm: MainViewModel,
    go: GoColors,
    title: String,
    extra: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HigPageTitle(title, go)
        Spacer(Modifier.weight(1f))
        extra()
        HigSettingAction(vm, go)
    }
}
