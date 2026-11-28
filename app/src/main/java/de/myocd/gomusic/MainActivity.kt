package de.myocd.gomusic

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val phonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.onPhonePermissionResult(granted)
        }
    // 应用内更新：Android 8+ 需要「允许安装未知应用」权限
    private val installPermission =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { vm.onInstallPermResult(canInstallPackages()) }

    private fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= 26) packageManager.canRequestPackageInstalls() else true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ImageCache.init(this)
        NmLog.init(this)
        CrashHandler.install(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // 26.11.84：Compose 的 imePadding() 需要 decor 不吃掉系统 insets，
        // 否则虚拟键盘弹出时输入栏不会上浮（打字看不到输入框）。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Android 9 及以下写公共下载目录需要存储权限
        if (Build.VERSION.SDK_INT < 29) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (savedInstanceState == null) {
            vm.handleLink(intent?.data)
            intent?.let { vm.playExternalAudio(it) }
        }
        setContent {
            // 「仅通话暂停」需要电话状态权限，由设置面板触发
            LaunchedEffect(vm.needPhonePerm) {
                if (vm.consumePhonePerm()) phonePermission.launch(Manifest.permission.READ_PHONE_STATE)
            }
            // 启动时静默检查一次更新。延后到首帧之后，别和界面渲染、
            // 简繁词典预热一起抢启动那几秒的线程。
            LaunchedEffect(Unit) {
                delay(1200L)
                vm.autoCheckUpdate()
            }
            // 后台预热简繁转换词典，避免第一次显示文字时卡一下
            LaunchedEffect(Unit) {
                withContext(kotlinx.coroutines.Dispatchers.IO) { ZhConvert.warmUp() }
            }
            // 检查完成后若发现新版本，自动弹一次
            LaunchedEffect(vm.updateInfo, vm.updateChecking) {
                if (!vm.updateChecking) vm.maybeShowUpdateDialog()
            }
            // 申请「安装未知应用」权限
            LaunchedEffect(vm.needInstallPerm) {
                if (vm.needInstallPerm && Build.VERSION.SDK_INT >= 26) {
                    installPermission.launch(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                }
            }
            val dark = resolveDark(vm.themeMode, isSystemInDarkTheme())
            GoTheme(vm.primary, rememberAppFont(vm.fontKind, vm.fontWght), vm.themeMode) {
                StatusBarMode(dark)
                // 简繁转换：设置一改，界面与文字全部跟着变
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalZhMode provides vm.zhConvert,
                ) {
                MainScreen(vm)
                // 全屏更新弹窗：启动检测到新版本 / 设置里点更新都会弹
                if (vm.showUpdate) UpdateScreen(vm)
                // 提示放最外层：否则会被设置等底部弹层盖住看不见
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    GoToast(
                        vm.toastMsg,
                        Modifier.navigationBarsPadding().padding(bottom = 56.dp + 64.dp + 16.dp),
                    )
                }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统电池优化设置页返回后，重新读一次白名单状态
        vm.refreshBatteryExempt()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleLink(intent.data)
        vm.playExternalAudio(intent)
    }
}

/**
 * 状态栏：竖屏显示，横屏隐藏。
 *
 * 横屏时顶部那条会把本就不多的竖向空间再吃一截。隐藏后 statusBarsPadding
 * 会变成 0，所以横屏下由 statusBarHeightDp() 在左侧补一条等宽空白，
 * 避免内容压到挖孔 / 圆角那一侧。
 */
@Composable
private fun StatusBarMode(dark: Boolean) {
    val act = LocalContext.current as? ComponentActivity ?: return
    val landscape = isLandscape()
    LaunchedEffect(landscape, dark) {
        val ctl = WindowCompat.getInsetsController(act.window, act.window.decorView)
        val type = WindowInsetsCompat.Type.statusBars()
        // 亮色外观下状态栏图标要深色，否则白底白图标看不见
        ctl.isAppearanceLightStatusBars = !dark
        if (!landscape) {
            ctl.show(type)
            return@LaunchedEffect
        }
        ctl.hide(type)
        // 横屏从顶部「扒下」会临时唤出系统状态栏，系统默认要几秒才自己收。
        // 这里盯着 window insets：状态栏一露出来就起 0.5 秒表，
        // 期间状态栏高度还在变（用户还在下拉 / 点通知）就重置，
        // 稳定 0.5 秒没人碰就主动 hide 把它收起来。
        // 用轮询而不是 hide 之后就撒手：临时栏被系统再次显示时也能继续收。
        ctl.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        var idleSince = 0L
        var lastTop = -1
        while (true) {
            // 50ms 的无限轮询会一直占着主线程读 insets，横屏播放全程都在跑。
            // 200ms 足够在 500ms 稳定窗口内把临时状态栏收起来。
            delay(200)
            val ins = ViewCompat.getRootWindowInsets(act.window.decorView) ?: continue
            val visible = ins.isVisible(type)
            val top = ins.getInsets(type).top
            if (!visible || top <= 0) {
                idleSince = 0L
                lastTop = -1
                continue
            }
            val now = System.currentTimeMillis()
            if (top != lastTop) {
                lastTop = top
                idleSince = now
            } else if (idleSince == 0L) {
                idleSince = now
            }
            if (now - idleSince >= 500) {
                ctl.hide(type)
                idleSince = 0L
                lastTop = -1
            }
        }
    }
}
