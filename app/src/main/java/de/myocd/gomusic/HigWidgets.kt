package de.myocd.gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 26.11.58：单层 iOS 风格搜索栏。
 *
 * 之前搜索页是「外层灰色圆角槽 + 内层 SearchField 自己又画一层 surface
 * 背景 / 圆角 / 聚焦边框」，两层框叠在一起，高度还一个 40dp 一个 44dp，
 * 看起来就是框中框、边角错位。这里从头写成单层：整条槽只有一次背景、
 * 一次圆角，输入框本身完全透明，图标与清除按钮都在同一层里。
 */
@Composable
fun HigSearchBar(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    go: GoColors,
    modifier: Modifier = Modifier,
    /** 回车 / 搜索键；本地过滤的搜索框可不传 */
    onSearch: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(go.surfaceHi)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search, null,
            tint = go.muted, modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                color = go.on,
                fontSize = 15.sp,
                fontFamily = LocalAppFont.current,
            ),
            cursorBrush = SolidColor(go.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
            // 不再 fillMaxWidth / height(44.dp)：外层只有 38dp，强行撑高会溢出
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            color = go.muted,
                            fontSize = 15.sp,
                            fontFamily = LocalAppFont.current,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            // iOS 的灰色实心圆叉，不再用文字「✕」
            Box(
                Modifier.size(17.dp).clip(CircleShape)
                    .background(go.muted)
                    .clickable { onChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close, "清除",
                    tint = go.surfaceHi, modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

/** 26.11.82：全应用唯一的设置入口，样式由 HigSettingAction 统一定义 */
@Composable
fun HigSettingsButton(vm: MainViewModel, go: GoColors) = HigSettingAction(vm, go)
