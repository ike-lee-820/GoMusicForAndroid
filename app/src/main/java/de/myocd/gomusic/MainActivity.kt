package de.myocd.gomusic

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect

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
    /** 悬浮窗权限只能跳系统设置页授予 */
    private val overlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            vm.onOverlayResult(canOverlay(this))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Android 9 及以下写公共下载目录需要存储权限
        if (Build.VERSION.SDK_INT < 29) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (savedInstanceState == null) vm.handleLink(intent?.data)
        vm.restoreFloat()
        setContent {
            // 「仅通话暂停」需要电话状态权限，由设置面板触发
            LaunchedEffect(vm.needPhonePerm) {
                if (vm.consumePhonePerm()) phonePermission.launch(Manifest.permission.READ_PHONE_STATE)
            }
            LaunchedEffect(vm.needOverlayPerm) {
                if (vm.consumeOverlayPerm()) {
                    val i = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                    runCatching { overlayPermission.launch(i) }
                        .onFailure { vm.onOverlayResult(false) }
                }
            }
            GoTheme(vm.primary) { MainScreen(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleLink(intent.data)
    }
}
