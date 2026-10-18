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

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
        setContent {
            GoTheme(vm.primary) { MainScreen(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleLink(intent.data)
    }
}
